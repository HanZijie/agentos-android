package org.agentos.app.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.agentos.app.R
import org.agentos.app.i18n.Strings

/**
 * 设置页的“自动选择会话（Jev）”（IAgentControl v4，D5.1）：状态解析、输入校验、文案。纯 Kotlin（JevLogicTest）。
 * key 只从密码框直接进 setJevSource，这里不保存、不回显。文案在 strings_p2.xml（`jev_*`），经 [Strings] 取。
 */
object Jev {
    /** [IAgentControl.getJevSource]。 */
    data class Source(
        val configured: Boolean,
        val endpoint: String,
        val defaultEndpoint: String,
        val customEndpoint: Boolean,
        val keySet: Boolean,
        val keyMasked: String?,
        val usable: Boolean,
        val problems: List<String>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Source {
        val o = json.parseToJsonElement(text) as JsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull == true
        return Source(
            configured = b("configured"),
            endpoint = s("endpoint").orEmpty(),
            defaultEndpoint = s("defaultEndpoint").orEmpty(),
            customEndpoint = b("customEndpoint"),
            keySet = b("keySet"),
            keyMasked = s("keyMasked"),
            usable = b("usable"),
            problems = (o["problems"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }

    class Invalid(val field: String, val message: String)

    /**
     * 填写是否可以保存。[endpoint] 留空 = 默认；[keyEntered] 为 false 时只有 endpoint 与已保存的相同才允许（沿用 key）。
     */
    fun validate(endpoint: String, keyEntered: Boolean, current: Source?, strings: Strings): Invalid? {
        val ep = endpoint.trim()
        if (ep.isNotEmpty() && !Byok.isAllowedEndpoint(ep)) {
            return Invalid("endpoint", strings.get(R.string.byok_url_rule))
        }
        if (!keyEntered) {
            val effective = ep.ifEmpty { current?.defaultEndpoint.orEmpty() }
            val same = current?.keySet == true && effective == current.endpoint
            if (!same) return Invalid("key", strings.get(if (current?.keySet == true) R.string.jev_key_changed_address else R.string.jev_key_needed))
        }
        return null
    }

    /** setJevSource / clearJevSource 的错误（`agentos.jev.<code>: …`），不回显输入。 */
    fun errorText(message: String?, strings: Strings): String {
        val code = message?.substringAfter("agentos.jev.", "")?.substringBefore(':')?.trim().orEmpty()
        return when (code) {
            "invalid_endpoint" -> strings.get(R.string.jev_err_invalid_endpoint)
            "invalid_key" -> strings.get(R.string.jev_err_invalid_key)
            "key_required" -> strings.get(R.string.jev_key_changed_address)
            "storage_failed" -> strings.get(R.string.jev_err_storage_failed)
            "" -> strings.get(R.string.settings_operation_failed)
            else -> strings.get(R.string.jev_err_failed_code, code)
        }
    }

    fun problemText(code: String, strings: Strings): String = when (code) {
        "key_unreadable" -> strings.get(R.string.jev_problem_key_unreadable)
        "file_unreadable" -> strings.get(R.string.jev_problem_file_unreadable)
        else -> code
    }

    fun statusText(s: Source, strings: Strings): String = when {
        !s.configured -> strings.get(R.string.jev_status_not_configured)
        !s.usable -> strings.get(R.string.model_saved_unusable)
        else -> strings.get(R.string.jev_status_enabled)
    }

    /** What it does and what it sends (session-selection.md): said before the user enters a key. */
    fun explain(strings: Strings): String = strings.get(R.string.jev_explain)
}
