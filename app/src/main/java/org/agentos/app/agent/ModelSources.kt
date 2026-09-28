package org.agentos.app.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.agentos.runtime.net.HostFetch
import org.agentos.runtime.pi.ModelCatalog
import org.agentos.runtime.ports.ModelConfigPort
import org.agentos.runtime.ports.ModelSpec
import org.agentos.runtime.ports.RuntimeLog
import org.agentos.runtime.ports.warn
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.Locale

/**
 * BYOK 请求被拒绝。消息格式 `agentos.byok.<code>: <说明>`（与 `agentos.acp.<code>` 相同），经 Binder 原样回到调用方。
 * 说明里**不回显**调用方传入的任何值（key 不必说，baseUrl、模型名也不回显：界面 bug 可能把 key 填进别的字段）。
 */
class ByokException(val code: String, message: String) : IllegalArgumentException("agentos.byok.$code: $message")

/** 保存失败（磁盘、Keystore）。经 Binder 回到调用方时是 IllegalStateException。 */
class ByokStorageException(message: String) : IllegalStateException("agentos.byok.${ModelSources.STORAGE_FAILED}: $message")

/**
 * BYOK 模型来源（F9），`HostPort.models` 的实现：用户选的厂商预设或自定义兼容端点，加上用 [KeystoreSecrets] 加密的 key。
 *
 * - 厂商预设和自定义端点的模板都来自 B 的 [ModelCatalog]（`assets/model-catalog.json`）。
 * - 保存在 `:agent` 的 CE 私有目录 `files/byok/model-source.json`：模型来源（明文，不含 key）+ key 的密文，
 *   写临时文件再 rename，一次原子替换。
 * - 热加载：[set] / [clear] 成功后立即更新 [activeModel] 和 key；宿主层从下一轮起用新模型，进行中的一轮不受影响
 *   （换下来的 key 见 [KeystoreSecrets.activate]）。
 * - 只有“可用”（模型解析成功、key 能解密且绑定的端点覆盖模型的 baseUrl）时 [activeModel] 才非 null；
 *   否则任务以 model_not_configured 失败，设置页显示 [get] 里的 problems。
 *
 * 线程：所有公开方法可以从 Binder 线程并发调用（内部加锁）。
 */
class ModelSources(
    dir: File,
    private val secrets: KeystoreSecrets,
    catalogSource: () -> ModelCatalog,
    private val log: RuntimeLog = RuntimeLog.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
) : ModelConfigPort {

    private val dir = dir
    private val file = File(dir, FILE_NAME)
    private val catalog: Result<ModelCatalog> by lazy { runCatching(catalogSource) }

    private val active = MutableStateFlow<ModelSpec?>(null)
    override val activeModel: StateFlow<ModelSpec?> = active.asStateFlow()

    private var loaded = false
    private var record: Record? = null
    private val problems = LinkedHashSet<String>()

    /** 保存的一条模型来源。[source] 是规范化后的请求，[model] 是解析出的 pi-ai Model 对象。 */
    private class Record(
        val updatedAt: Long,
        val source: Source,
        val model: JsonObject,
        val providerName: String?,
        val keyLabel: String?,
        val key: SealedKey?,
    )

    private data class Source(
        val kind: String,
        val provider: String? = null,
        val model: String,
        val api: String? = null,
        val baseUrl: String? = null,
        val name: String? = null,
        val contextWindow: Int? = null,
        val maxTokens: Int? = null,
        val reasoning: Boolean? = null,
        val input: List<String>? = null,
        val thinkingLevel: String = "off",
    )

    private class Resolved(val model: JsonObject, val providerName: String?, val keyLabel: String?, val bindings: List<String>)

    // ------------------------------------------------------------------ 读取

    /** 读取保存的模型来源并解密 key（每个进程一次；第一次调用任何方法时也会触发）。 */
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = try {
            if (!file.isFile) return
            file.readText()
        } catch (e: IOException) {
            problems += PROBLEM_CONFIG_UNREADABLE
            log.warn(TAG, "cannot read model source: ${e.javaClass.simpleName}")
            return
        }
        val rec = runCatching { decodeRecord(text) }.getOrElse {
            problems += if (it is NewerFormatException) PROBLEM_NEWER_FORMAT else PROBLEM_CONFIG_UNREADABLE
            log.warn(TAG, "model source is not readable: ${it.javaClass.simpleName}")
            return
        }
        // 预设按当前的目录重新解析（App 更新可能改了模型参数）；目录里没有了就用保存的模型对象
        var model = rec.model
        var providerName = rec.providerName
        var keyLabel = rec.keyLabel
        if (rec.source.kind == KIND_PRESET) {
            val cat = catalog.getOrNull()
            if (cat == null) {
                problems += PROBLEM_CATALOG_UNAVAILABLE
            } else {
                val r = runCatching { resolve(rec.source, cat) }.getOrNull()
                if (r == null) {
                    problems += PROBLEM_MODEL_NOT_IN_CATALOG
                } else {
                    model = r.model
                    providerName = r.providerName
                    keyLabel = r.keyLabel
                    if (rec.key != null && r.bindings != rec.key.bindings) problems += PROBLEM_KEY_ENDPOINT_MISMATCH
                }
            }
        }
        val key = rec.key?.let { sealed ->
            try {
                secrets.unseal(sealed)
            } catch (e: Exception) {
                problems += PROBLEM_KEY_UNREADABLE
                log.warn(TAG, "stored model key cannot be decrypted: ${e.javaClass.simpleName}")
                null
            }
        }
        install(Record(rec.updatedAt, rec.source, model, providerName, keyLabel, rec.key), key)
    }

    /**
     * 当前模型来源（IAgentControl.getModelSource）。key 只给首尾各 4 位。
     * 没配置时 `{"configured": false, "problems": [...]}`。
     */
    @Synchronized
    fun get(): JsonObject {
        ensureLoaded()
        val rec = record ?: return buildJsonObject {
            put("configured", false)
            put("usable", false)
            putJsonArray("problems") { problems.forEach { add(JsonPrimitive(it)) } }
        }
        val s = rec.source
        return buildJsonObject {
            put("configured", true)
            put("usable", active.value != null)
            put("kind", s.kind)
            s.provider?.let { put("provider", it) }
            rec.providerName?.let { put("providerName", it) }
            put("model", s.model)
            put("modelName", rec.model.str("name") ?: s.model)
            put("api", rec.model.str("api"))
            put("baseUrl", rec.model.str("baseUrl"))
            put("thinkingLevel", s.thinkingLevel)
            rec.model["reasoning"]?.let { put("reasoning", it) }
            rec.model["input"]?.let { put("input", it) }
            rec.model["contextWindow"]?.let { put("contextWindow", it) }
            rec.model["maxTokens"]?.let { put("maxTokens", it) }
            putJsonObject("key") {
                put("set", secrets.isSet)
                secrets.masked()?.let { put("masked", it) }
                rec.keyLabel?.let { put("label", it) }
                putJsonArray("endpoints") { secrets.bindings().forEach { add(JsonPrimitive(it)) } }
            }
            put("updatedAt", rec.updatedAt)
            putJsonArray("problems") { problems.forEach { add(JsonPrimitive(it)) } }
        }
    }

    /**
     * 厂商预设（IAgentControl.getModelPresets）。[providerId] 为空：厂商列表（不含模型）；否则这个厂商和它的模型。
     * 分两级是为了控制 Binder 返回值大小（整个目录上千个模型）。
     */
    fun presets(providerId: String?): JsonObject {
        val cat = catalog.getOrNull() ?: throw ByokException(CATALOG_UNAVAILABLE, "model-catalog.json is not available in this build")
        if (providerId.isNullOrBlank()) {
            return buildJsonObject {
                put("schemaVersion", cat.schemaVersion)
                cat.piAiVersion?.let { put("piAi", it) }
                putJsonArray("customApis") { cat.apis.filter { it in ModelSpec.SUPPORTED_APIS }.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("thinkingLevels") { THINKING_LEVELS.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("providers") { cat.providers.forEach { add(providerJson(it, withModels = false)) } }
            }
        }
        val p = cat.provider(providerId) ?: throw ByokException(UNKNOWN_PROVIDER, "no such provider in the model catalog")
        return buildJsonObject { put("provider", providerJson(p, withModels = true)) }
    }

    /** 诊断用的摘要：不含 key，自定义端点只给 scheme + host（路径里可能带账号信息）。 */
    @Synchronized
    fun status(): JsonObject {
        ensureLoaded()
        val rec = record
        val model = active.value
        return buildJsonObject {
            put("configured", rec != null)
            put("usable", model != null)
            rec?.let { r ->
                put("kind", r.source.kind)
                r.source.provider?.let { put("provider", it) }
                put("model", r.source.model)
                put("api", r.model.str("api"))
                put("endpoint", r.model.str("baseUrl")?.let(::originOf))
            }
            put("keySet", secrets.isSet)
            put("keyEndpoints", secrets.bindings().size)
            put("retiredKeyHeld", secrets.holdsRetiredKey)
            put("credentialResolves", model?.baseUrl?.let { resolves(it) } ?: false)
            putJsonArray("problems") { problems.forEach { add(JsonPrimitive(it)) } }
            putJsonObject("catalog") {
                val cat = catalog.getOrNull()
                put("loaded", cat != null)
                if (cat != null) {
                    put("providers", cat.providers.size)
                    put("models", cat.providers.sumOf { it.models.size })
                    cat.piAiVersion?.let { put("piAi", it) }
                } else {
                    put("error", catalog.exceptionOrNull()?.javaClass?.simpleName)
                }
            }
        }
    }

    /** 模型端点下的一个请求能否拿到 key（与 HostFetch 走同一条匹配路径）。 */
    private fun resolves(baseUrl: String): Boolean =
        runCatching { secrets.apiKeyFor(URI(baseUrl.trimEnd('/') + "/v1/messages")) != null }.getOrDefault(false)

    // ------------------------------------------------------------------ 修改

    /**
     * 设置模型来源（IAgentControl.setModelSource），立即生效（下一轮起）。返回设置后的 [get]。
     *
     * [apiKey] 为 null 或空：沿用已保存的 key——只在新来源的端点与 key 绑定的端点完全相同时允许（同一厂商换模型）；
     * 否则 `agentos.byok.key_required`。key 前后的空白会去掉。
     */
    @Synchronized
    fun set(sourceJson: String, apiKey: String?): JsonObject {
        ensureLoaded()
        val source = parseSource(sourceJson)
        val cat = catalog.getOrNull() ?: throw ByokException(CATALOG_UNAVAILABLE, "model-catalog.json is not available in this build")
        val resolved = resolve(source, cat)
        val newKey = apiKey?.trim()?.takeIf { it.isNotEmpty() }?.also(::checkKey)
        val sealed: SealedKey = if (newKey != null) {
            try {
                secrets.seal(newKey, resolved.bindings).also {
                    // 立刻解一次：Keystore 有问题时现在就失败，而不是等到下次启动
                    check(secrets.unseal(it) == newKey)
                }
            } catch (e: Exception) {
                log.warn(TAG, "cannot encrypt the model key: ${e.javaClass.simpleName}")
                throw ByokStorageException("the key could not be encrypted with Android Keystore")
            }
        } else {
            val old = record?.key
            if (old == null || !secrets.isSet || PROBLEM_KEY_UNREADABLE in problems || old.bindings != resolved.bindings) {
                throw ByokException(KEY_REQUIRED, "an API key is required for this endpoint")
            }
            old
        }
        val rec = Record(clock(), source, resolved.model, resolved.providerName, resolved.keyLabel, sealed)
        try {
            writeAtomically(encodeRecord(rec))
        } catch (e: IOException) {
            log.warn(TAG, "cannot save model source: ${e.javaClass.simpleName}")
            throw ByokStorageException("the model source could not be saved")
        }
        problems.clear()
        install(rec, newKey ?: KEEP_KEY)
        return get()
    }

    /** 清除模型来源和 key（IAgentControl.clearModelSource）。之后任务以 model_not_configured 失败。 */
    @Synchronized
    fun clear() {
        ensureLoaded()
        File(dir, "$FILE_NAME.tmp").delete()
        if (file.exists() && !file.delete()) throw ByokStorageException("the model source could not be deleted")
        problems.clear()
        record = null
        active.value = null
        secrets.activate(null, emptyList())
        secrets.destroyMasterKey()
    }

    // ------------------------------------------------------------------ 内部

    /** 让 [rec] 生效。[key] 为 [KEEP_KEY] 时 key 不变（绑定相同），为 null 时没有可用的 key。 */
    private fun install(rec: Record, key: String?) {
        record = rec
        if (key !== KEEP_KEY) secrets.activate(key, rec.key?.bindings.orEmpty())
        val baseUrl = rec.model.str("baseUrl")
        val usable = secrets.isSet && baseUrl != null && resolves(baseUrl) && PROBLEM_KEY_ENDPOINT_MISMATCH !in problems
        active.value = if (usable) ModelSpec(rec.model, rec.source.thinkingLevel) else null
    }

    private fun resolve(s: Source, cat: ModelCatalog): Resolved = when (s.kind) {
        KIND_PRESET -> {
            val p = cat.provider(s.provider!!) ?: throw ByokException(UNKNOWN_PROVIDER, "no such provider in the model catalog")
            val m = p.models.firstOrNull { it.id == s.model }
                ?: throw ByokException(UNKNOWN_MODEL, "provider ${p.id} has no such model")
            if (m.api !in ModelSpec.SUPPORTED_APIS) throw ByokException(UNSUPPORTED_API, "this model uses an unsupported API family")
            Resolved(m.json, p.name, p.keyLabel, normalizeBindings(p.baseUrls + m.baseUrl))
        }
        else -> {
            val api = s.api!!
            if (api !in ModelSpec.SUPPORTED_APIS || api !in cat.apis) {
                throw ByokException(UNSUPPORTED_API, "api must be one of ${ModelSpec.SUPPORTED_APIS.sorted()}")
            }
            val base = checkEndpoint(s.baseUrl!!)
            val spec = try {
                cat.customModel(
                    api = api, id = s.model, baseUrl = base, name = s.name ?: s.model,
                    contextWindow = s.contextWindow, maxTokens = s.maxTokens, reasoning = s.reasoning, input = s.input,
                )
            } catch (e: IllegalArgumentException) {
                throw ByokException(INVALID_SOURCE, "the custom endpoint was rejected by the model template")
            }
            Resolved(spec.json, null, null, listOf(base))
        }
    }

    private fun providerJson(p: ModelCatalog.Provider, withModels: Boolean): JsonObject = buildJsonObject {
        put("id", p.id)
        put("name", p.name)
        putJsonArray("apis") { p.apis.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("baseUrls") { p.baseUrls.forEach { add(JsonPrimitive(it)) } }
        put("keyLabel", p.keyLabel)
        put("modelCount", p.models.size)
        if (withModels) {
            putJsonArray("models") {
                p.models.forEach { m ->
                    add(
                        buildJsonObject {
                            put("id", m.id)
                            put("name", m.name)
                            put("api", m.api)
                            put("baseUrl", m.baseUrl)
                            put("reasoning", m.reasoning)
                            putJsonArray("input") { m.input.forEach { add(JsonPrimitive(it)) } }
                            m.contextWindow?.let { put("contextWindow", it) }
                            m.maxTokens?.let { put("maxTokens", it) }
                        },
                    )
                }
            }
        }
    }

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

    private class NewerFormatException : IOException("model source format is newer than this app")

    companion object {
        private const val TAG = "ModelSources"
        const val FILE_NAME = "model-source.json"
        private const val FORMAT_VERSION = 1

        const val KIND_PRESET = "preset"
        const val KIND_CUSTOM = "custom"

        /** Pi 的 thinkingLevel（ports.ModelSpec）。 */
        val THINKING_LEVELS = listOf("off", "minimal", "low", "medium", "high")
        val INPUT_KINDS = setOf("text", "image")
        private const val MAX_KEY_CHARS = 4096
        private const val MAX_FIELD_CHARS = 256
        private const val MAX_URL_CHARS = 2048

        // 错误码（agentos.byok.<code>）
        const val INVALID_SOURCE = "invalid_source"
        const val UNKNOWN_PROVIDER = "unknown_provider"
        const val UNKNOWN_MODEL = "unknown_model"
        const val UNSUPPORTED_API = "unsupported_api"
        const val INVALID_ENDPOINT = "invalid_endpoint"
        const val INVALID_THINKING_LEVEL = "invalid_thinking_level"
        const val INVALID_KEY = "invalid_key"
        const val KEY_REQUIRED = "key_required"
        const val CATALOG_UNAVAILABLE = "catalog_unavailable"
        const val STORAGE_FAILED = "storage_failed"

        // get() / status() 的 problems
        const val PROBLEM_CONFIG_UNREADABLE = "config_unreadable"
        const val PROBLEM_NEWER_FORMAT = "config_newer_format"
        const val PROBLEM_KEY_UNREADABLE = "key_unreadable"
        const val PROBLEM_KEY_ENDPOINT_MISMATCH = "key_endpoint_mismatch"
        const val PROBLEM_MODEL_NOT_IN_CATALOG = "model_not_in_catalog"
        const val PROBLEM_CATALOG_UNAVAILABLE = "catalog_unavailable"

        /** [install] 的哨兵：key 不变。 */
        private val KEEP_KEY = String(charArrayOf('k', 'e', 'e', 'p'))

        private val json = Json { ignoreUnknownKeys = true }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun originOf(url: String): String? = runCatching {
            val u = URI(url)
            "${u.scheme}://${u.host}${if (u.port >= 0) ":${u.port}" else ""}"
        }.getOrNull()

        private fun normalizeBindings(urls: List<String>): List<String> = urls.map { it.trim().trimEnd('/') }.distinct()

        /** key：去掉首尾空白后 1..4096 个字符，不含空白和控制字符（粘贴了 "Bearer …" 或换行时报错）。 */
        fun checkKey(key: String) {
            if (key.length > MAX_KEY_CHARS) throw ByokException(INVALID_KEY, "the key is too long")
            if (key.any { it.isWhitespace() || it.isISOControl() }) {
                throw ByokException(INVALID_KEY, "the key must not contain whitespace or control characters")
            }
        }

        /** 自定义端点：绝对 https URL（http 只允许回环地址，与 HostFetch 一致），不带用户信息、query、fragment。 */
        fun checkEndpoint(raw: String): String {
            val url = raw.trim().trimEnd('/')
            val bad = ByokException(INVALID_ENDPOINT, "baseUrl must be an absolute https URL without credentials, query or fragment (http only for loopback)")
            if (url.isEmpty() || url.length > MAX_URL_CHARS) throw bad
            val u = runCatching { URI(url) }.getOrNull() ?: throw bad
            val scheme = u.scheme?.lowercase(Locale.ROOT) ?: throw bad
            val host = u.host?.takeIf { it.isNotEmpty() } ?: throw bad
            if (scheme != "https" && !(scheme == "http" && HostFetch.isLoopback(host.lowercase(Locale.ROOT)))) throw bad
            if (u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null) throw bad
            if (u.rawPath.orEmpty().split('/').any { it == ".." || it == "." }) throw bad
            return url
        }

        private fun parseSource(text: String): Source {
            val o = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: throw ByokException(INVALID_SOURCE, "sourceJson must be a JSON object")
            fun str(key: String, required: Boolean): String? {
                val v = o[key] ?: return if (required) throw ByokException(INVALID_SOURCE, "missing \"$key\"") else null
                if (v is JsonNull && !required) return null
                val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                    ?: throw ByokException(INVALID_SOURCE, "\"$key\" must be a string")
                if (s.isEmpty()) return if (required) throw ByokException(INVALID_SOURCE, "\"$key\" is empty") else null
                if (s.length > MAX_URL_CHARS || (key != "baseUrl" && s.length > MAX_FIELD_CHARS)) {
                    throw ByokException(INVALID_SOURCE, "\"$key\" is too long")
                }
                return s
            }
            fun positiveInt(key: String): Int? {
                val v = o[key] ?: return null
                if (v is JsonNull) return null
                return (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 }
                    ?: throw ByokException(INVALID_SOURCE, "\"$key\" must be a positive integer")
            }
            val kind = str("kind", true)
            val thinking = str("thinkingLevel", false) ?: "off"
            if (thinking !in THINKING_LEVELS) throw ByokException(INVALID_THINKING_LEVEL, "thinkingLevel must be one of $THINKING_LEVELS")
            val model = str("model", true)!!
            return when (kind) {
                KIND_PRESET -> Source(KIND_PRESET, provider = str("provider", true), model = model, thinkingLevel = thinking)
                KIND_CUSTOM -> {
                    val reasoning = o["reasoning"]?.let { v ->
                        if (v is JsonNull) null
                        else (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                            ?: throw ByokException(INVALID_SOURCE, "\"reasoning\" must be a boolean")
                    }
                    val input = o["input"]?.let { v ->
                        if (v is JsonNull) return@let null
                        val list = (v as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        if (list == null || list.isEmpty() || list.any { it == null || it !in INPUT_KINDS }) {
                            throw ByokException(INVALID_SOURCE, "\"input\" must be a non-empty array of $INPUT_KINDS")
                        }
                        list.filterNotNull().distinct()
                    }
                    Source(
                        KIND_CUSTOM, model = model, api = str("api", true), baseUrl = str("baseUrl", true), name = str("name", false),
                        contextWindow = positiveInt("contextWindow"), maxTokens = positiveInt("maxTokens"),
                        reasoning = reasoning, input = input, thinkingLevel = thinking,
                    )
                }
                else -> throw ByokException(INVALID_SOURCE, "\"kind\" must be \"$KIND_PRESET\" or \"$KIND_CUSTOM\"")
            }
        }

        private fun sourceJson(s: Source): JsonObject = buildJsonObject {
            put("kind", s.kind)
            s.provider?.let { put("provider", it) }
            put("model", s.model)
            s.api?.let { put("api", it) }
            s.baseUrl?.let { put("baseUrl", it) }
            s.name?.let { put("name", it) }
            s.contextWindow?.let { put("contextWindow", it) }
            s.maxTokens?.let { put("maxTokens", it) }
            s.reasoning?.let { put("reasoning", it) }
            s.input?.let { l -> putJsonArray("input") { l.forEach { add(JsonPrimitive(it)) } } }
            put("thinkingLevel", s.thinkingLevel)
        }

        private fun encodeRecord(r: Record): String = buildJsonObject {
            put("v", FORMAT_VERSION)
            put("updatedAt", r.updatedAt)
            put("source", sourceJson(r.source))
            put("model", r.model)
            r.providerName?.let { put("providerName", it) }
            r.keyLabel?.let { put("keyLabel", it) }
            r.key?.let { k ->
                putJsonObject("key") {
                    put("bindings", buildJsonArray { k.bindings.forEach { add(JsonPrimitive(it)) } })
                    put("sealed", k.blob)
                }
            }
        }.toString()

        private fun decodeRecord(text: String): Record {
            val o = json.parseToJsonElement(text).jsonObject
            val v = o["v"]?.jsonPrimitive?.intOrNull ?: throw IOException("no format version")
            if (v > FORMAT_VERSION) throw NewerFormatException()
            val s = o["source"]!!.jsonObject
            val source = Source(
                kind = s.str("kind")!!.also { require(it == KIND_PRESET || it == KIND_CUSTOM) },
                provider = s.str("provider"),
                model = s.str("model")!!,
                api = s.str("api"),
                baseUrl = s.str("baseUrl"),
                name = s.str("name"),
                contextWindow = s["contextWindow"]?.jsonPrimitive?.intOrNull,
                maxTokens = s["maxTokens"]?.jsonPrimitive?.intOrNull,
                reasoning = s["reasoning"]?.jsonPrimitive?.booleanOrNull,
                input = (s["input"] as? JsonArray)?.map { it.jsonPrimitive.content },
                thinkingLevel = s.str("thinkingLevel")?.takeIf { it in THINKING_LEVELS } ?: "off",
            )
            require(source.kind != KIND_PRESET || source.provider != null)
            require(source.kind != KIND_CUSTOM || (source.api != null && source.baseUrl != null))
            val key = (o["key"] as? JsonObject)?.let { k ->
                SealedKey(k["bindings"]!!.jsonArray.map { it.jsonPrimitive.content }, k.str("sealed")!!)
            }
            return Record(
                updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                source = source,
                model = o["model"]!!.jsonObject,
                providerName = o.str("providerName"),
                keyLabel = o.str("keyLabel"),
                key = key,
            )
        }
    }
}
