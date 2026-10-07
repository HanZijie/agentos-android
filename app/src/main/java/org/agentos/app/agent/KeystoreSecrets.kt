package org.agentos.app.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.agentos.runtime.net.BaseUrlCredentials
import org.agentos.runtime.ports.Credential
import org.agentos.runtime.ports.SecretPort
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * 模型 key（F9）：Android Keystore 里的 AES-256-GCM 主密钥加密后保存，解密后只留在 `:agent` 的内存里。
 *
 * - 实现 A 定义的 [SecretPort]（`HostPort.secrets`；B2 起 net/HostFetch 也直接用它注入 key），内部委托给 B 的
 *   [BaseUrlCredentials]：scheme、host、port 完全相同，路径在段边界上以 baseUrl 的路径开头；Entry 的 key 每次请求时求值。
 * - 解密后的 key 留在 `:agent` 内存里（不是每次请求都经 Keystore 解密）：日志去 key（[redact]）要用明文比对，
 *   更换 key 时进行中的这一轮还要用换下来的 key。
 * - key 绑定到一组 baseUrl（厂商预设：该厂商的全部 baseUrl；自定义端点：用户填的那一个）。绑定关系同时作为 GCM 的
 *   AAD：密文被挪给别的端点就解不开；换端点必须重新输入 key。
 * - **更换 = 热加载**（[activate]）：下一个请求就用新 key。换下来的 key 只服务它自己绑定的端点，留到运行时空闲
 *   （[retire]）再丢弃，所以进行中的这一轮不被打断。
 * - **清除 = 立即作废**（[revoke]，architecture F9）：当前的和换下来的 key 一起丢掉，不等空闲；此后 [credentialFor]
 *   一律返回 null，之后的模型请求（包括同一轮里工具调用之后的下一次请求）都拿不到 key，以 model_not_configured 结束
 *   （第一层）。随后在 [revocations] 上发出这两个 [Credential] 对象（第二层）：HostFetch 按对象身份中止携带它们的
 *   在途调用，正在传输的那一次响应也就此结束（KEY_REVOKED → model_not_configured、details.reason=key_revoked）。
 *   更换和空闲时丢弃换下来的 key（[retire]）都不发：那时没有要中止的调用。
 * - key 不进日志、诊断、心跳、异常消息：本类的 toString 不含 key，[redact] 是日志的最后一道防线。
 *
 * 持久化由 [ModelSources] 负责（与模型来源写在同一个文件里，一次 rename 原子替换）；本类只做加解密和匹配。
 *
 * @param idle 运行时当前是否空闲（没有进行中的任务）。空闲时换下来的 key 立即丢弃。
 */
class KeystoreSecrets(
    private val cipher: SecretCipher,
    private val idle: () -> Boolean = { true },
) : SecretPort {

    private class Slot(val key: String, val bindings: List<String>) {
        val credential = Credential(key)
        private val lookup = BaseUrlCredentials(bindings.map { url -> BaseUrlCredentials.Entry(url) { credential } })

        suspend fun match(url: String): Credential? = lookup.credentialFor(url)

        override fun toString() = "Slot(****, ${bindings.size} endpoints)"
    }

    @Volatile private var current: Slot? = null

    /** 换下来、还没丢弃的 key（进行中的任务可能还在用）。 */
    @Volatile private var retired: Slot? = null

    /**
     * 第二个凭据位：Jev（自动选会话）的 key，只绑定 Jev 的 endpoint，所以 [credentialFor] 对模型端点不会交出它，
     * 对 Jev endpoint 也不会交出模型 key。清除（[revokeJev]）同样立即作废，不留“换下来”的副本（Jev 请求只有几秒）。
     * 持久化由 [JevSources] 负责（自己的 Keystore 主密钥，清除模型 key 时不受影响）。
     */
    @Volatile private var jev: Slot? = null

    private val served = AtomicLong()
    private val denied = AtomicLong()
    // 计数器；不能叫 revocations：那是 SecretPort 的撤销信号（Flow<Credential>，A 追加，发送由 C 实现）
    private val revokedCount = AtomicLong()
    @Volatile private var lastRevokedAtMs = 0L

    // 撤销信号：热流、不重放（HostFetch 构造时就订阅）。buffer 足够大，[revoke] 在锁里用 tryEmit 不挂起；
    // 没有订阅者时 tryEmit 直接成功（没有人要中止），发不出去的计入 signalsDropped（诊断里应当一直是 0）。
    private val signal = MutableSharedFlow<Credential>(extraBufferCapacity = SIGNAL_BUFFER)
    private val signalsSent = AtomicLong()
    private val signalsDropped = AtomicLong()

    /** 清除时被撤销的 key（当前的和换下来的），按对象身份比较；更换不发。 */
    override val revocations: Flow<Credential> = signal.asSharedFlow()

    /** 用主密钥加密 [key]，绑定到 [bindings]。不改变当前生效的 key。 */
    fun seal(key: String, bindings: List<String>): SealedKey {
        val blob = cipher.seal(key.toByteArray(Charsets.UTF_8), aad(bindings))
        return SealedKey(bindings, Base64.getEncoder().encodeToString(blob))
    }

    /** 解密。主密钥丢失（例如数据被恢复到另一台设备）或密文、绑定被改动时抛 [GeneralSecurityException]。 */
    fun unseal(sealed: SealedKey): String {
        val blob = try {
            Base64.getDecoder().decode(sealed.blob)
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException("sealed key is not base64")
        }
        return String(cipher.open(blob, aad(sealed.bindings)), Charsets.UTF_8)
    }

    /** 从下一个请求起使用 [key]（null = 不再有 key）。原来的 key 在运行时空闲后丢弃。 */
    @Synchronized
    fun activate(key: String?, bindings: List<String>) {
        val old = current
        current = key?.let { Slot(it, bindings) }
        if (old != null && (old.key != key || old.bindings != bindings)) retired = old
        if (idle()) retired = null
    }

    /** 运行时空闲时调用（AgentProcess 在任务数归零时）：丢弃换下来的 key。 */
    fun retire() {
        retired = null
    }

    /**
     * 清除模型来源时调用：当前的和换下来的 key **立即**丢掉，不管有没有进行中的任务。此后 [credentialFor] 一律返回 null。
     * 主密钥由调用方随后用 [destroyMasterKey] 删除。
     */
    @Synchronized
    fun revoke() {
        val withdrawn = listOfNotNull(current, retired).map { it.credential }.distinct()
        // 第一层：先让 credentialFor 拿不到（信号晚到的窗口由 HostFetch 的 recentlyRevoked 兜住）
        current = null
        retired = null
        if (withdrawn.isEmpty()) return
        revokedCount.incrementAndGet()
        lastRevokedAtMs = System.currentTimeMillis()
        // 第二层：中止携带这些 key 的在途调用
        for (c in withdrawn) {
            if (signal.tryEmit(c)) signalsSent.incrementAndGet() else signalsDropped.incrementAndGet()
        }
    }

    /** 从下一个请求起用 [key] 访问 Jev 的 [endpoint]（null = 不再有 Jev key）。 */
    @Synchronized
    fun activateJev(key: String?, endpoint: String) {
        jev = key?.let { Slot(it, listOf(endpoint)) }
    }

    /** 清除 Jev key：立即作废，此后 [credentialFor] 对 Jev endpoint 返回 null；发出撤销信号中止在途的 Jev 请求。 */
    @Synchronized
    fun revokeJev() {
        val old = jev ?: return
        jev = null
        if (signal.tryEmit(old.credential)) signalsSent.incrementAndGet() else signalsDropped.incrementAndGet()
    }

    val jevIsSet: Boolean get() = jev != null

    /** Jev key 的首尾各 4 位；没有时为 null。 */
    fun jevMasked(): String? = jev?.let { Credential(it.key).masked() }

    /** 清除模型来源时调用：删除 Keystore 里的主密钥，旧密文从此无法解密。下次 [seal] 会新建一把。 */
    fun destroyMasterKey() = cipher.destroy()

    val isSet: Boolean get() = current != null
    val holdsRetiredKey: Boolean get() = retired != null

    /** 当前 key 绑定的 baseUrl。 */
    fun bindings(): List<String> = current?.bindings.orEmpty()

    /** 当前 key 的首尾各 4 位（F9；长度不超过 8 时为 "****"）；没有 key 时为 null。 */
    fun masked(): String? = current?.let { Credential(it.key).masked() }

    /** 主密钥的状态（诊断用，不含任何 key 内容）。 */
    fun keystoreStatus(): Map<String, Any?> = runCatching { cipher.status() }.getOrElse { mapOf("error" to it.javaClass.simpleName) }

    /** 新 key 优先；换下来的 key 只服务它自己绑定的端点。清除之后一律返回 null。 */
    override suspend fun credentialFor(url: String): Credential? {
        val c = current
        val r = retired
        val found = c?.match(url) ?: r?.match(url) ?: jev?.match(url)
        if (found != null) served.incrementAndGet() else denied.incrementAndGet()
        return found
    }

    /**
     * 请求计数（诊断、设备用例用，不含任何 key 内容）：交出 key 的次数、找不到 key 的次数、撤销次数；
     * 撤销信号发出 / 发不出去的条数，以及当前订阅者数（每个 HostFetch 一个）。
     */
    fun stats(): Map<String, Any?> = mapOf(
        "served" to served.get(), "denied" to denied.get(),
        "revocations" to revokedCount.get(), "lastRevokedAtMs" to lastRevokedAtMs,
        "signals" to signalsSent.get(), "signalsDropped" to signalsDropped.get(),
        "subscribers" to signal.subscriptionCount.value.toLong(),
    )

    /** 只判断能否匹配（ModelSources 判断“可用”、诊断用），不计入 [stats]；匹配本身不挂起、不做 I/O。 */
    fun resolves(url: String): Boolean = runBlocking {
        val c = current
        val r = retired
        (c?.match(url) ?: r?.match(url)) != null
    }

    /** 把 [text] 里出现的 key（当前的和换下来的）换成 `****`。 */
    fun redact(text: String): String {
        var out = text
        for (s in listOfNotNull(current, retired, jev)) {
            if (s.key.length >= MIN_REDACT_LENGTH && out.contains(s.key)) out = out.replace(s.key, "****")
        }
        return out
    }

    override fun toString(): String = "KeystoreSecrets(set=$isSet, retired=$holdsRetiredKey, jev=$jevIsSet)"

    companion object {
        /** 太短的 key（本地测试端点的 "x" 之类）替换了反而破坏日志，也没有保密意义。 */
        private const val MIN_REDACT_LENGTH = 6

        /** 一次清除最多发 2 条（当前的和换下来的）；订阅者（HostFetch）在锁里处理，几乎不积压。 */
        private const val SIGNAL_BUFFER = 64

        /** GCM 的 AAD：格式版本 + 绑定的 baseUrl，逐行。 */
        fun aad(bindings: List<String>): ByteArray =
            ("agentos-byok-v1\n" + bindings.joinToString("\n")).toByteArray(Charsets.UTF_8)
    }
}

/** 加密后的 key：绑定的 baseUrl（明文）+ 密文（base64，见 [SealedBlob]）。 */
class SealedKey(val bindings: List<String>, val blob: String) {
    override fun toString() = "SealedKey(${bindings.size} endpoints)"
}

/** 加解密。Android 上是 [AndroidKeystoreCipher]；电脑上的单测用软件 AES-GCM。 */
interface SecretCipher {
    /** 加密 [plain]，[aad] 参与认证但不加密。返回 [SealedBlob] 格式。 */
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray

    /** 解密；[aad] 不符、密文被改、主密钥不存在时抛 [GeneralSecurityException]。 */
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray

    /** 删除主密钥。 */
    fun destroy()

    /** 诊断信息（不含密钥材料）。 */
    fun status(): Map<String, Any?>
}

/** 密文格式 v1：`[0x01][IV 长度][IV][GCM 密文 + 128 位 tag]`。 */
object SealedBlob {
    private const val VERSION: Byte = 1
    const val TAG_BITS = 128

    fun encode(iv: ByteArray, ciphertext: ByteArray): ByteArray =
        byteArrayOf(VERSION, iv.size.toByte()) + iv + ciphertext

    fun decode(blob: ByteArray): Pair<ByteArray, ByteArray> {
        if (blob.size < 2 || blob[0] != VERSION) throw GeneralSecurityException("unsupported sealed key format")
        val ivLen = blob[1].toInt() and 0xff
        if (ivLen !in 12..16 || blob.size < 2 + ivLen + TAG_BITS / 8) throw GeneralSecurityException("truncated sealed key")
        return blob.copyOfRange(2, 2 + ivLen) to blob.copyOfRange(2 + ivLen, blob.size)
    }
}

/**
 * Android Keystore 里的 AES-256-GCM 主密钥（不需要用户认证：后台任务要能随时读 key）。
 * 有 TEE / StrongBox 时密钥材料不出安全硬件；模拟器上是软件实现，接口相同。
 */
class AndroidKeystoreCipher(private val alias: String = DEFAULT_ALIAS) : SecretCipher {

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun createKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    @Synchronized
    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val key = existingKey() ?: createKey()
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key)
        c.updateAAD(aad)
        val ciphertext = c.doFinal(plain)
        return SealedBlob.encode(c.iv, ciphertext)
    }

    @Synchronized
    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
        val (iv, ciphertext) = SealedBlob.decode(sealed)
        val key = existingKey() ?: throw GeneralSecurityException("keystore master key is missing")
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(SealedBlob.TAG_BITS, iv))
        c.updateAAD(aad)
        return c.doFinal(ciphertext)
    }

    @Synchronized
    override fun destroy() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    override fun status(): Map<String, Any?> {
        val key = existingKey() ?: return mapOf("alias" to alias, "present" to false)
        val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        val level = when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "tee"
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "unknown_secure"
            else -> "unknown"
        }
        return mapOf("alias" to alias, "present" to true, "securityLevel" to level, "keySize" to info.keySize)
    }

    companion object {
        const val DEFAULT_ALIAS = "org.agentos.byok.v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
