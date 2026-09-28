package org.agentos.runtime.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 电脑端接入的开关与配对（architecture F11，W9）。握手格式见 core/protocol/acp-mapping.md 第 10 节。
 *
 * - **开关默认关闭**。关闭时清掉配对码和全部配对：已经配对的电脑要重新配对。
 * - **配对码**：6 位数字，同一时刻最多一个；**一次性**（配对成功即作废）；[PairingConfig.codeTtlMillis] 后**过期**；
 *   输错 [PairingConfig.maxCodeAttempts] 次作废。配对码只在内存里，`:agent` 重启即失效。
 * - 配对成功后发一个**令牌**（32 字节随机数，base64url），电脑端保存；之后的连接提交令牌，不再要配对码。
 *   设备上只保存令牌的 SHA-256。令牌在开关关闭或被撤销（[revoke]）之前一直有效。
 *
 * 线程安全：所有方法互斥执行。
 */
class DesktopPairing(
    private val store: PairingStore,
    val config: PairingConfig = PairingConfig(),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val random: SecureRandom = SecureRandom(),
) {
    private var state: PairingState = runCatching { store.load() }.getOrNull() ?: PairingState()
    private var active: ActiveCode? = null

    private class ActiveCode(val code: String, val deadline: Long, val expiresAtMillis: Long, val ttlMillis: Long, var attempts: Int = 0)

    val enabled: Boolean
        @Synchronized get() = state.enabled

    /** 打开或关闭电脑端接入；关闭时清掉配对码和全部配对。返回状态是否改变。 */
    @Synchronized
    fun setEnabled(on: Boolean): Boolean {
        if (state.enabled == on) return false
        active = null
        state = if (on) state.copy(enabled = true) else PairingState(enabled = false)
        save()
        return true
    }

    /** 生成新的配对码（替换旧的）。开关关闭时抛 [IllegalStateException]。 */
    @Synchronized
    fun newCode(ttlMillis: Long = config.codeTtlMillis): PairingCode {
        check(state.enabled) { "desktop access is turned off" }
        require(ttlMillis > 0) { "ttlMillis must be positive" }
        val bound = pow10(config.codeDigits)
        val code = random.nextInt(bound).toString().padStart(config.codeDigits, '0')
        val c = ActiveCode(code, monotonicClock() + ttlMillis, wallClock() + ttlMillis, ttlMillis)
        active = c
        return PairingCode(code, c.expiresAtMillis, ttlMillis)
    }

    /** 当前有效的配对码（给设置页显示）；没有或已过期时为 null。 */
    @Synchronized
    fun activeCode(): PairingCode? = liveCode()?.let { PairingCode(it.code, it.expiresAtMillis, it.ttlMillis) }

    /** 诊断用：当前配对码的状态，不含配对码本身。 */
    @Synchronized
    fun codeStatus(): CodeStatus? = liveCode()?.let { CodeStatus(it.expiresAtMillis, config.maxCodeAttempts - it.attempts) }

    @Synchronized
    fun cancelCode() {
        active = null
    }

    /** 用配对码配对。成功时配对码作废，返回新配对和它的令牌（令牌只在这里出现一次）。 */
    @Synchronized
    fun pairWithCode(submitted: String, clientLabel: String?): PairingResult {
        if (!state.enabled) return PairingResult.Rejected(PairingFailure.DISABLED)
        val c = active ?: return PairingResult.Rejected(PairingFailure.INVALID_CODE)
        if (monotonicClock() >= c.deadline) {
            active = null
            return PairingResult.Rejected(PairingFailure.CODE_EXPIRED)
        }
        val normalized = submitted.filterNot { it == ' ' || it == '-' }
        if (!MessageDigest.isEqual(normalized.toByteArray(), c.code.toByteArray())) {
            c.attempts++
            if (c.attempts >= config.maxCodeAttempts) {
                active = null
                return PairingResult.Rejected(PairingFailure.TOO_MANY_ATTEMPTS)
            }
            return PairingResult.Rejected(PairingFailure.INVALID_CODE)
        }
        active = null
        val token = newToken()
        val now = wallClock()
        val pairing = Pairing(
            id = "dp_" + hex(ByteArray(6).also(random::nextBytes)),
            tokenSha256 = sha256(token),
            label = sanitizeLabel(clientLabel),
            pairedAtMillis = now,
            lastSeenMillis = now,
        )
        // 超过上限时挤掉最久没用的配对
        val kept = state.pairings.sortedByDescending { it.lastSeenMillis }.take(config.maxPairings - 1)
        state = state.copy(pairings = kept + pairing)
        save()
        return PairingResult.Accepted(pairing.info(), token)
    }

    /** 用令牌重新连接。 */
    @Synchronized
    fun verifyToken(token: String): PairingResult {
        if (!state.enabled) return PairingResult.Rejected(PairingFailure.DISABLED)
        if (!TOKEN_PATTERN.matches(token)) return PairingResult.Rejected(PairingFailure.INVALID_TOKEN)
        val hash = sha256(token).toByteArray()
        var found: Pairing? = null
        for (p in state.pairings) if (MessageDigest.isEqual(p.tokenSha256.toByteArray(), hash)) found = p
        val p = found ?: return PairingResult.Rejected(PairingFailure.INVALID_TOKEN)
        val seen = p.copy(lastSeenMillis = wallClock())
        state = state.copy(pairings = state.pairings.map { if (it.id == p.id) seen else it })
        save()
        return PairingResult.Accepted(seen.info(), token = null)
    }

    @Synchronized
    fun isPaired(pairingId: String): Boolean = state.enabled && state.pairings.any { it.id == pairingId }

    @Synchronized
    fun pairings(): List<DesktopPairingInfo> = state.pairings.map { it.info() }

    /** 撤销一个配对（电脑端要重新配对）。 */
    @Synchronized
    fun revoke(pairingId: String): Boolean {
        val left = state.pairings.filterNot { it.id == pairingId }
        if (left.size == state.pairings.size) return false
        state = state.copy(pairings = left)
        save()
        return true
    }

    @Synchronized
    fun revokeAll(): Int {
        val n = state.pairings.size
        if (n > 0) {
            state = state.copy(pairings = emptyList())
            save()
        }
        return n
    }

    private fun liveCode(): ActiveCode? {
        val c = active ?: return null
        if (monotonicClock() >= c.deadline) {
            active = null
            return null
        }
        return c
    }

    private fun save() = store.save(state)

    private fun newToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    private fun Pairing.info() = DesktopPairingInfo(id, label, pairedAtMillis, lastSeenMillis)

    companion object {
        /** 32 字节 base64url（无填充）= 43 个字符。 */
        private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")

        const val DEFAULT_LABEL = "desktop"
        const val MAX_LABEL_CHARS = 64

        /** 电脑端自报的名字只用来在设置页区分配对：去掉控制字符，最多 64 个字符。 */
        fun sanitizeLabel(label: String?): String =
            label?.filterNot { it.isISOControl() }?.trim()?.take(MAX_LABEL_CHARS)?.takeIf { it.isNotBlank() } ?: DEFAULT_LABEL

        fun sha256(text: String): String = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        private fun pow10(n: Int): Int {
            var r = 1
            repeat(n) { r *= 10 }
            return r
        }
    }
}

/**
 * @property codeTtlMillis 配对码的有效期，默认 5 分钟。
 * @property maxCodeAttempts 同一个配对码最多试几次（含正确的那次之前的错误次数），默认 5。
 * @property maxPairings 最多保留几个已配对的电脑，默认 16。
 */
data class PairingConfig(
    val codeTtlMillis: Long = 5 * 60_000L,
    val maxCodeAttempts: Int = 5,
    val maxPairings: Int = 16,
    val codeDigits: Int = 6,
) {
    init {
        require(codeDigits in 4..9) { "codeDigits must be 4..9" }
        require(maxCodeAttempts >= 1 && maxPairings >= 1)
    }
}

/** 给用户看的配对码。toString 不含配对码本身，避免进日志。 */
class PairingCode(val code: String, val expiresAtMillis: Long, val ttlMillis: Long) {
    override fun toString() = "PairingCode(****, expiresAtMillis=$expiresAtMillis)"
}

data class CodeStatus(val expiresAtMillis: Long, val attemptsLeft: Int)

/** 已配对的电脑（给设置页显示）。[label] 是电脑端自报的名字，只作区分，不作身份。 */
data class DesktopPairingInfo(val id: String, val label: String, val pairedAtMillis: Long, val lastSeenMillis: Long)

sealed interface PairingResult {
    /** @property token 只在用配对码新配对时有值。 */
    class Accepted(val pairing: DesktopPairingInfo, val token: String?) : PairingResult {
        override fun toString() = "Accepted(${pairing.id}, token=${if (token == null) "none" else "****"})"
    }

    data class Rejected(val failure: PairingFailure) : PairingResult
}

/** 握手失败的原因（`error.data.details.reason`，acp-mapping.md 第 10 节）。 */
enum class PairingFailure(val wire: String, val message: String) {
    DISABLED("disabled", "desktop access is turned off on the phone"),
    PAIRING_REQUIRED("pairing_required", "the first message must be a _org.agentos/pair request carrying a pairing code or token"),
    INVALID_CODE("invalid_code", "wrong pairing code, or no pairing code is active; generate one on the phone"),
    CODE_EXPIRED("code_expired", "the pairing code has expired; generate a new one on the phone"),
    TOO_MANY_ATTEMPTS("too_many_attempts", "too many wrong pairing codes; this code is void, generate a new one on the phone"),
    INVALID_TOKEN("invalid_token", "this computer is no longer paired (revoked, or desktop access was turned off); pair again with a new code"),
}

// ------------------------------------------------------------------ 持久化

/** 持久化的状态：开关和配对（令牌只存 SHA-256）。配对码不持久化。 */
@Serializable
data class PairingState(
    val version: Int = 1,
    val enabled: Boolean = false,
    val pairings: List<Pairing> = emptyList(),
)

@Serializable
data class Pairing(
    val id: String,
    val tokenSha256: String,
    val label: String,
    val pairedAtMillis: Long,
    val lastSeenMillis: Long,
)

interface PairingStore {
    /** 读取保存的状态；没有时返回 null。读不出来（损坏）时也返回 null：回到默认的关闭状态。 */
    fun load(): PairingState?

    fun save(state: PairingState)
}

class MemoryPairingStore(@Volatile var state: PairingState? = null) : PairingStore {
    override fun load() = state

    override fun save(state: PairingState) {
        this.state = state
    }
}

/** 存在 App 私有目录里的一个 JSON 文件；先写临时文件再原子替换。 */
class FilePairingStore(private val file: File) : PairingStore {
    override fun load(): PairingState? {
        if (!file.isFile) return null
        return try {
            json.decodeFromString(PairingState.serializer(), file.readText())
        } catch (e: Exception) {
            null
        }
    }

    override fun save(state: PairingState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(PairingState.serializer(), state))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
