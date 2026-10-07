package org.agentos.app.agent

import org.agentos.app.settings.Byok
import org.agentos.runtime.ports.RuntimeLog
import org.agentos.runtime.ports.warn
import org.agentos.runtime.router.JevConfig
import org.agentos.runtime.router.JevException
import org.agentos.runtime.router.JevProvider
import org.agentos.runtime.router.JevRequest
import org.agentos.runtime.router.HttpJevProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Jev 设置被拒绝。消息 `agentos.jev.<code>: <说明>`，说明里不回显调用方传入的任何值（key、endpoint 都不回显）。 */
class JevSourceException(val code: String, message: String) : IllegalArgumentException("agentos.jev.$code: $message")

/** 保存失败（磁盘、Keystore）。 */
class JevStorageException(message: String) : IllegalStateException("agentos.jev.${JevSources.STORAGE_FAILED}: $message")

/**
 * Jev（自动选会话，core/contracts/session-selection.md）的 endpoint 和 key。
 *
 * - key 用**自己的** Android Keystore 主密钥（别名 [JEV_ALIAS]）加密，写在 `files/jev/jev-source.json`，GCM 的 AAD 含 endpoint：
 *   密文挪给别的 endpoint 就解不开，换 endpoint 必须重新输入 key。清除模型 key 时不会删这把主密钥，反过来也一样。
 * - 解密后的 key 只交给 [KeystoreSecrets.activateJev]（内存里的第二个凭据位），HttpJevProvider 经 `SecretPort.credentialFor(endpoint)`
 *   取用；不进日志、事件、Store、诊断。
 * - endpoint 留空 = [DEFAULT_ENDPOINT]。规则与模型端点相同（F9）：https，只有回环地址（127.0.0.1、localhost、::1）可以用 http。
 * - 清除 = 立即作废：key 当场从内存丢掉，文件删掉，主密钥删掉；在途的 Jev 请求被撤销信号中止，路由回退为新建会话。
 */
class JevSources(
    private val dir: File,
    private val cipher: SecretCipher,
    private val secrets: KeystoreSecrets,
    private val log: RuntimeLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Record(val endpoint: String, val sealed: SealedKey, val updatedAt: Long)

    private val file = File(dir, FILE_NAME)
    private var loaded = false
    private var record: Record? = null
    private var problem: String? = null

    /** 当前 endpoint（没设置过时是默认值）。 */
    @Synchronized
    fun endpoint(): String {
        ensureLoaded()
        return record?.endpoint ?: DEFAULT_ENDPOINT
    }

    /** 有可用的 key（能解密、已交给 [KeystoreSecrets]）。 */
    @Synchronized
    fun usable(): Boolean {
        ensureLoaded()
        return secrets.jevIsSet
    }

    /** 状态，给设置页和诊断；没有 key 内容，只有首尾各 4 位。 */
    @Synchronized
    fun get(): JsonObject {
        ensureLoaded()
        return buildJsonObject {
            put("configured", record != null)
            put("endpoint", record?.endpoint ?: DEFAULT_ENDPOINT)
            put("defaultEndpoint", DEFAULT_ENDPOINT)
            put("customEndpoint", record != null && record?.endpoint != DEFAULT_ENDPOINT)
            put("keySet", secrets.jevIsSet)
            put("keyMasked", secrets.jevMasked())
            put("usable", secrets.jevIsSet)
            put("problems", JsonArray(listOfNotNull(problem).map { JsonPrimitive(it) }))
            put("updatedAt", record?.updatedAt?.let { JsonPrimitive(it) } ?: JsonNull)
        }
    }

    /**
     * 保存 endpoint 和 key。[endpoint] 为 null 或空白 = 默认。[apiKey] 为 null 或空白 = 沿用已保存的 key，只在 endpoint 不变时允许。
     * @throws JevSourceException 请求不对（invalid_endpoint、key_required）
     * @throws JevStorageException 保存失败
     */
    @Synchronized
    fun set(endpoint: String?, apiKey: String?): JsonObject {
        ensureLoaded()
        val ep = endpoint?.trim().orEmpty().ifEmpty { DEFAULT_ENDPOINT }
        if (!Byok.isAllowedEndpoint(ep)) {
            throw JevSourceException(INVALID_ENDPOINT, "the endpoint must be https:// (http:// only for 127.0.0.1, localhost or ::1) without user info, query or fragment")
        }
        val newKey = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        if (newKey != null && newKey.any { it.isISOControl() || it.isWhitespace() }) {
            throw JevSourceException(INVALID_KEY, "the key contains whitespace or control characters")
        }
        val old = record
        val sealed: SealedKey = if (newKey != null) {
            try {
                seal(newKey, ep).also { check(unseal(it) == newKey) }
            } catch (e: Exception) {
                log.warn(TAG, "cannot encrypt the Jev key: ${e.javaClass.simpleName}")
                throw JevStorageException("the key could not be encrypted with Android Keystore")
            }
        } else {
            if (old == null || old.endpoint != ep || !secrets.jevIsSet) throw JevSourceException(KEY_REQUIRED, "an API key is required for this endpoint")
            old.sealed
        }
        val rec = Record(ep, sealed, clock())
        try {
            writeAtomically(encode(rec))
        } catch (e: IOException) {
            log.warn(TAG, "cannot save the Jev source: ${e.javaClass.simpleName}")
            throw JevStorageException("the Jev source could not be saved")
        }
        record = rec
        problem = null
        if (newKey != null) secrets.activateJev(newKey, ep)
        return get()
    }

    /** 清除：key 立即作废，文件和主密钥删除。 */
    @Synchronized
    fun clear() {
        ensureLoaded()
        File(dir, "$FILE_NAME.tmp").delete()
        if (file.exists() && !file.delete()) throw JevStorageException("the Jev source could not be deleted")
        record = null
        problem = null
        secrets.revokeJev()
        cipher.destroy()
    }

    // ------------------------------------------------------------------ 持久化

    private fun aad(endpoint: String) = "agentos-jev-v1\n$endpoint".toByteArray(Charsets.UTF_8)

    private fun seal(key: String, endpoint: String): SealedKey =
        SealedKey(listOf(endpoint), Base64.getEncoder().encodeToString(cipher.seal(key.toByteArray(Charsets.UTF_8), aad(endpoint))))

    private fun unseal(s: SealedKey): String {
        val blob = try {
            Base64.getDecoder().decode(s.blob)
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException("sealed key is not base64")
        }
        return String(cipher.open(blob, aad(s.bindings.single())), Charsets.UTF_8)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        try {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            val ep = (o["endpoint"] as JsonPrimitive).content
            val blob = ((o["key"] as JsonObject)["blob"] as JsonPrimitive).content
            val sealed = SealedKey(listOf(ep), blob)
            val rec = Record(ep, sealed, (o["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L)
            record = rec
            try {
                secrets.activateJev(unseal(sealed), ep)
            } catch (e: GeneralSecurityException) {
                // 主密钥丢了（数据被恢复到别的设备）：没有可用的 key，设置页提示重新输入
                problem = PROBLEM_KEY_UNREADABLE
                log.warn(TAG, "the Jev key cannot be decrypted: ${e.javaClass.simpleName}")
            }
        } catch (e: Exception) {
            problem = PROBLEM_FILE_UNREADABLE
            log.warn(TAG, "cannot read the Jev source: ${e.javaClass.simpleName}")
        }
    }

    private fun encode(r: Record): String = buildJsonObject {
        put("v", 1)
        put("endpoint", r.endpoint)
        put("updatedAt", r.updatedAt)
        put("key", buildJsonObject { put("blob", r.sealed.blob) })
    }.toString()

    private fun writeAtomically(text: String) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.name}")
        val tmp = File(dir, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("rename failed")
        }
    }

    companion object {
        const val TAG = "JevSources"
        const val FILE_NAME = "jev-source.json"
        const val JEV_ALIAS = "org.agentos.jev.v1"

        /** 2026-09-29 整合人：api.typesafe.ai（omnilabs.vibeadmin.cn 对现有 key 返回 401）。与 JevConfig 的默认值相同。 */
        const val DEFAULT_ENDPOINT = "https://api.typesafe.ai/v1/systemone"

        const val INVALID_ENDPOINT = "invalid_endpoint"
        const val INVALID_KEY = "invalid_key"
        const val KEY_REQUIRED = "key_required"
        const val STORAGE_FAILED = "storage_failed"
        const val PROBLEM_KEY_UNREADABLE = "key_unreadable"
        const val PROBLEM_FILE_UNREADABLE = "file_unreadable"
    }
}

/**
 * 交给 `RuntimeConfig.jev` 的 [JevProvider]：运行时只在构造时拿一次，所以这里每次 [choose] 现读 [JevSources]：
 * 没有 key = `jev_unconfigured`（新建会话），有 key 就用当前 endpoint 的 [HttpJevProvider]（key 经 SecretPort 取，不在这里）。
 */
class ConfiguredJevProvider(
    private val sources: JevSources,
    private val secrets: KeystoreSecrets,
    private val timeoutMillis: Long = JevConfig().timeoutMillis,
) : JevProvider {
    private val providers = ConcurrentHashMap<String, HttpJevProvider>()

    override suspend fun choose(request: JevRequest): String {
        if (!sources.usable()) throw JevException("jev_unconfigured")
        val endpoint = sources.endpoint()
        val provider = providers.getOrPut(endpoint) { HttpJevProvider(JevConfig(endpoint = endpoint, timeoutMillis = timeoutMillis), secrets) }
        return provider.choose(request)
    }
}
