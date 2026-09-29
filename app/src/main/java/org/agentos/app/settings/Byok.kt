package org.agentos.app.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Pure logic of the BYOK settings (F9): parse what IAgentControl v2 returns, build what it accepts,
 * explain its errors. No Android types, so it is unit-tested on the JVM (ByokTest).
 * The key never passes through here: it goes straight from the password field to setModelSource.
 */
object Byok {
    const val ANTHROPIC = "anthropic-messages"
    const val OPENAI = "openai-completions"

    data class Provider(val id: String, val name: String, val apis: List<String>, val keyLabel: String?, val modelCount: Int)
    data class Model(
        val id: String,
        val name: String,
        val api: String,
        val reasoning: Boolean,
        val contextWindow: Long?,
        val maxTokens: Long?,
    )

    data class Catalog(val providers: List<Provider>, val thinkingLevels: List<String>, val customApis: List<String>)

    /** getModelSource(). */
    data class Source(
        val configured: Boolean,
        val usable: Boolean,
        val kind: String?,
        val provider: String?,
        val providerName: String?,
        val model: String?,
        val modelName: String?,
        val api: String?,
        val baseUrl: String?,
        val thinkingLevel: String?,
        val keySet: Boolean,
        val keyMasked: String?,
        val keyLabel: String?,
        val keyEndpoints: List<String>,
        val problems: List<String>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.arr(k: String): List<String> =
        (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    fun parseCatalog(text: String): Catalog {
        val o = json.parseToJsonElement(text) as JsonObject
        val providers = (o["providers"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            Provider(
                id = p.str("id") ?: return@mapNotNull null,
                name = p.str("name") ?: p.str("id")!!,
                apis = p.arr("apis"),
                keyLabel = p.str("keyLabel"),
                modelCount = (p["modelCount"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }
        return Catalog(providers, o.arr("thinkingLevels").ifEmpty { listOf("off") }, o.arr("customApis"))
    }

    fun parseModels(text: String): List<Model> {
        val o = json.parseToJsonElement(text) as JsonObject
        val provider = o["provider"] as? JsonObject ?: return emptyList()
        return (provider["models"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            Model(
                id = m.str("id") ?: return@mapNotNull null,
                name = m.str("name") ?: m.str("id")!!,
                api = m.str("api") ?: "",
                reasoning = (m["reasoning"] as? JsonPrimitive)?.booleanOrNull ?: false,
                contextWindow = (m["contextWindow"] as? JsonPrimitive)?.longOrNull,
                maxTokens = (m["maxTokens"] as? JsonPrimitive)?.longOrNull,
            )
        }
    }

    fun parseSource(text: String): Source {
        val o = json.parseToJsonElement(text) as JsonObject
        val key = o["key"] as? JsonObject
        return Source(
            configured = (o["configured"] as? JsonPrimitive)?.booleanOrNull ?: false,
            usable = (o["usable"] as? JsonPrimitive)?.booleanOrNull ?: false,
            kind = o.str("kind"),
            provider = o.str("provider"),
            providerName = o.str("providerName"),
            model = o.str("model"),
            modelName = o.str("modelName"),
            api = o.str("api"),
            baseUrl = o.str("baseUrl"),
            thinkingLevel = o.str("thinkingLevel"),
            keySet = (key?.get("set") as? JsonPrimitive)?.booleanOrNull ?: false,
            keyMasked = key?.str("masked"),
            keyLabel = key?.str("label"),
            keyEndpoints = key?.arr("endpoints") ?: emptyList(),
            problems = o.arr("problems"),
        )
    }

    // ------------------------------------------------------------------ building a request

    sealed interface Form {
        val thinkingLevel: String

        data class Preset(val provider: String, val model: String, override val thinkingLevel: String = "off") : Form

        data class Custom(
            val api: String,
            val baseUrl: String,
            val model: String,
            val name: String? = null,
            val contextWindow: Long? = null,
            val maxTokens: Long? = null,
            val reasoning: Boolean = false,
            override val thinkingLevel: String = "off",
        ) : Form
    }

    /** A problem found before calling the runtime; [field] names the input to highlight. */
    data class Invalid(val field: String, val message: String)

    /**
     * Checks the form and whether an empty key field is acceptable: an empty key keeps the saved key,
     * which the runtime allows only when the endpoint is unchanged (same preset provider, or the same
     * custom baseUrl); otherwise a key is required.
     */
    fun validate(form: Form, keyEntered: Boolean, current: Source?): Invalid? {
        when (form) {
            is Form.Preset -> {
                if (form.provider.isBlank()) return Invalid("provider", "请选择模型厂商")
                if (form.model.isBlank()) return Invalid("model", "请选择模型")
            }
            is Form.Custom -> {
                if (form.api != ANTHROPIC && form.api != OPENAI) return Invalid("api", "请选择协议")
                val url = form.baseUrl.trim()
                if (url.isEmpty()) return Invalid("baseUrl", "请填写 API 地址")
                if (!isAllowedEndpoint(url)) {
                    return Invalid("baseUrl", "地址必须以 https:// 开头（http 只允许本机），且不能带用户名、? 参数或 # 片段")
                }
                if (form.model.isBlank()) return Invalid("model", "请填写模型名")
                if (form.model.any { it.isWhitespace() }) return Invalid("model", "模型名不能含空格")
            }
        }
        if (!keyEntered && !sameEndpoint(form, current)) return Invalid("key", "换了厂商或地址，需要重新填写 key")
        return null
    }

    fun sameEndpoint(form: Form, current: Source?): Boolean {
        if (current == null || !current.configured || !current.keySet) return false
        return when (form) {
            is Form.Preset -> current.kind == "preset" && current.provider == form.provider
            is Form.Custom -> current.kind == "custom" && current.baseUrl?.trimEnd('/') == form.baseUrl.trim().trimEnd('/')
        }
    }

    /**
     * architecture F9: https only; the single exception is plain http to a loopback address
     * (127.0.0.1, localhost, ::1) for a model server running on the phone. No user info, query, fragment.
     */
    fun isAllowedEndpoint(url: String): Boolean {
        val m = Regex("^(https?)://([^/?#@\\s]+)(/[^?#\\s]*)?$", RegexOption.IGNORE_CASE).matchEntire(url) ?: return false
        val scheme = m.groupValues[1].lowercase()
        val authority = m.groupValues[2].lowercase()
        val host = if (authority.startsWith("[")) authority.substringBefore(']').removePrefix("[") else authority.substringBefore(':')
        if (host.isEmpty()) return false
        return scheme == "https" || host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    fun sourceJson(form: Form): String = when (form) {
        is Form.Preset -> buildJsonObject {
            put("kind", "preset")
            put("provider", form.provider)
            put("model", form.model)
            put("thinkingLevel", form.thinkingLevel)
        }
        is Form.Custom -> buildJsonObject {
            put("kind", "custom")
            put("api", form.api)
            put("baseUrl", form.baseUrl.trim())
            put("model", form.model.trim())
            form.name?.takeIf { it.isNotBlank() }?.let { put("name", it.trim()) }
            form.contextWindow?.let { put("contextWindow", it) }
            form.maxTokens?.let { put("maxTokens", it) }
            if (form.reasoning) put("reasoning", true)
            put("input", buildJsonArray { add(JsonPrimitive("text")) })
            put("thinkingLevel", form.thinkingLevel)
        }
    }.toString()

    // ------------------------------------------------------------------ errors

    /** "agentos.byok.<code>: …" from IAgentControl → a sentence for the user. Never echoes input values. */
    fun errorText(message: String?): String {
        val code = message?.takeIf { it.startsWith("agentos.byok.") }?.removePrefix("agentos.byok.")?.substringBefore(':')?.trim()
        return when (code) {
            "invalid_source" -> "设置内容不完整或格式不对"
            "unknown_provider" -> "没有这个模型厂商（目录可能已更新），请重新选择"
            "unknown_model" -> "这个厂商没有这个模型，请重新选择"
            "unsupported_api" -> "不支持这个协议，只能选 Anthropic Messages 或 OpenAI Chat Completions"
            "invalid_endpoint" -> "API 地址不合法：必须是 https://（http 只允许本机），不能带用户名、? 参数或 # 片段"
            "invalid_thinking_level" -> "思考强度不在可选范围内"
            "invalid_key" -> "key 的格式不对：不能含空格或换行，最长 4096 个字符"
            "key_required" -> "换了厂商或地址，需要重新填写 key"
            "catalog_unavailable" -> "厂商目录读不出来，请重新安装 AgentOS"
            "storage_failed" -> "保存失败（存储或 Android Keystore 出错），请重试"
            null -> "保存失败，请重试"
            else -> "保存失败（$code）"
        }
    }

    fun problemText(problem: String): String = when (problem) {
        "config_unreadable", "config_newer_format" -> "保存的设置读不出来，请重新设置"
        "key_unreadable" -> "key 无法解密（例如数据被恢复到了另一台手机），请重新填写 key"
        "key_endpoint_mismatch" -> "App 更新后厂商地址变了，请重新填写 key"
        "model_not_in_catalog" -> "这个模型已不在最新目录里，沿用保存的参数，仍然可用"
        "catalog_unavailable" -> "厂商目录读不出来"
        else -> problem
    }

    fun thinkingText(level: String): String = when (level) {
        "off" -> "关闭"
        "minimal" -> "最少"
        "low" -> "低"
        "medium" -> "中"
        "high" -> "高"
        else -> level
    }

    fun apiText(api: String): String = when (api) {
        ANTHROPIC -> "Anthropic Messages"
        OPENAI -> "OpenAI Chat Completions"
        else -> api
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
