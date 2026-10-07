package org.agentos.app.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 设置页的“自动选择会话（Jev）”（IAgentControl v4，D5.1）：状态解析、输入校验、文案。纯 Kotlin（JevLogicTest）。
 * key 只从密码框直接进 setJevSource，这里不保存、不回显。
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
    fun validate(endpoint: String, keyEntered: Boolean, current: Source?): Invalid? {
        val ep = endpoint.trim()
        if (ep.isNotEmpty() && !Byok.isAllowedEndpoint(ep)) {
            return Invalid("endpoint", "地址必须以 https:// 开头（http 只允许本机），且不能带用户名、? 参数或 # 片段")
        }
        if (!keyEntered) {
            val effective = ep.ifEmpty { current?.defaultEndpoint.orEmpty() }
            val same = current?.keySet == true && effective == current.endpoint
            if (!same) return Invalid("key", if (current?.keySet == true) "换了地址，需要重新填写 key" else "请填写 Jev key")
        }
        return null
    }

    /** setJevSource / clearJevSource 的错误（`agentos.jev.<code>: …`），不回显输入。 */
    fun errorText(message: String?): String {
        val code = message?.substringAfter("agentos.jev.", "")?.substringBefore(':')?.trim().orEmpty()
        return when (code) {
            "invalid_endpoint" -> "地址不合法：必须是 https://（本机地址可以用 http://），不能带用户名、参数或片段"
            "invalid_key" -> "key 里有空格或控制字符，请重新粘贴"
            "key_required" -> "换了地址，需要重新填写 key"
            "storage_failed" -> "保存失败：Android Keystore 或存储出了问题，请重试"
            "" -> "操作失败，请重试"
            else -> "操作失败（$code）"
        }
    }

    fun problemText(code: String): String = when (code) {
        "key_unreadable" -> "已保存的 key 解不开（设备的密钥库变了），请重新填写 key"
        "file_unreadable" -> "保存的设置读不出来，请重新填写"
        else -> code
    }

    fun statusText(s: Source): String = when {
        !s.configured -> "未配置：同一个调用方有多个历史会话时，每次都新建会话"
        !s.usable -> "已保存，但暂时不可用"
        else -> "已启用"
    }

    /** What it does and what it sends (session-selection.md): said before the user enters a key. */
    const val EXPLAIN =
        "用户（或其他 App）不指定会话时，AgentOS 会把这次的问题和这个调用方自己最近几个会话的摘要发给 Jev 服务，由它挑出该继续哪个会话，" +
            "挑不出来就新建。不配置也能正常使用，只是每次都新建会话。摘要含会话里的问答文字，不含模型 key。"
}
