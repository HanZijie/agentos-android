package org.agentos.app.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import kotlinx.coroutines.runBlocking
import org.agentos.runtime.net.BaseUrlCredentials
import org.agentos.runtime.ports.Credential
import org.agentos.runtime.ports.SecretPort
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
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
 * - 解密后的 key 留在 `:agent` 内存里（不是每次请求都经 Keystore 解密）：日志去 key（[redact]）要用明文比对；
 *   清除后 Keystore 主密钥已删除，进行中的任务仍要用换下来的 key 跑完这一轮。
 * - key 绑定到一组 baseUrl（厂商预设：该厂商的全部 baseUrl；自定义端点：用户填的那一个）。绑定关系同时作为 GCM 的
 *   AAD：密文被挪给别的端点就解不开；换端点必须重新输入 key。
 * - 热加载：[activate] 之后下一个请求就用新 key。换下来的 key 留到运行时空闲（[retire]）再丢弃，
 *   所以进行中的任务在这一轮里的后续请求不受影响。
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

    /** 新 key 优先；换下来的 key 只服务它自己绑定的端点。 */
    override suspend fun credentialFor(url: String): Credential? {
        val c = current
        val r = retired
        return c?.match(url) ?: r?.match(url)
    }

    /** [credentialFor] 的非挂起版本（ModelSources 判断“可用”、诊断用）；匹配本身不挂起、不做 I/O。 */
    fun resolves(url: String): Boolean = runBlocking { credentialFor(url) != null }

    /** 把 [text] 里出现的 key（当前的和换下来的）换成 `****`。 */
    fun redact(text: String): String {
        var out = text
        for (s in listOfNotNull(current, retired)) {
            if (s.key.length >= MIN_REDACT_LENGTH && out.contains(s.key)) out = out.replace(s.key, "****")
        }
        return out
    }

    override fun toString(): String = "KeystoreSecrets(set=$isSet, retired=$holdsRetiredKey)"

    companion object {
        /** 太短的 key（本地测试端点的 "x" 之类）替换了反而破坏日志，也没有保密意义。 */
        private const val MIN_REDACT_LENGTH = 6

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
