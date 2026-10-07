package org.agentos.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * MCP 工具注解（规范的 ToolAnnotations）。都是提示：AgentOS 只会按注解调高风险（destructiveHint=true 升为高风险），
 * 不会因为 readOnlyHint 放宽确认（docs/extensions.md 5.4）。
 */
data class McpToolAnnotations(
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
) {
    internal fun toJson(title: String?): JsonObject? {
        if (title == null && readOnlyHint == null && destructiveHint == null && idempotentHint == null && openWorldHint == null) return null
        return buildJsonObject {
            title?.let { put("title", it) }
            readOnlyHint?.let { put("readOnlyHint", it) }
            destructiveHint?.let { put("destructiveHint", it) }
            idempotentHint?.let { put("idempotentHint", it) }
            openWorldHint?.let { put("openWorldHint", it) }
        }
    }

    internal companion object {
        fun fromJson(o: JsonObject?): McpToolAnnotations {
            if (o == null) return McpToolAnnotations()
            fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull
            return McpToolAnnotations(b("readOnlyHint"), b("destructiveHint"), b("idempotentHint"), b("openWorldHint"))
        }
    }
}

/** 工具结果里的一段内容。服务端（插件）一般只用 [Text]；客户端收到的其他类型（图片等）原样放在 [Raw] 里。 */
sealed class McpContent {
    data class Text(val text: String) : McpContent()

    /** 不认识的内容类型，保留原始 JSON（含 "type"）。 */
    data class Raw(val json: JsonObject) : McpContent()

    internal fun toJson(): JsonObject = when (this) {
        is Text -> buildJsonObject {
            put("type", "text")
            put("text", text)
        }
        is Raw -> json
    }

    internal companion object {
        fun fromJson(o: JsonObject): McpContent =
            if ((o["type"] as? JsonPrimitive)?.contentOrNull == "text") Text((o["text"] as? JsonPrimitive)?.contentOrNull ?: "") else Raw(o)
    }
}

/**
 * 一次 `tools/call` 的结果（MCP 的 CallToolResult）。插件里用 [text]、[json]、[error] 构造；
 * [McpBinderClient.callTool] 返回的也是它。
 *
 * - [isError] = true 表示工具执行失败（参数不对、对象不存在、业务规则不允许），原因写在 [content] 里给模型看；
 *   这不是协议错误。
 * - [structuredContent]：结构化结果（MCP 2025-06-18 起），只能是 JSON 对象。
 */
class McpToolResult internal constructor(
    val content: List<McpContent>,
    val structuredContent: JsonObject? = null,
    val isError: Boolean = false,
) {
    /** 所有文本段拼在一起（换行分隔）；方便测试和日志里取长度。 */
    val text: String get() = content.filterIsInstance<McpContent.Text>().joinToString("\n") { it.text }

    internal fun toJson(): JsonObject = buildJsonObject {
        put("content", buildJsonArray { content.forEach { add(it.toJson()) } })
        structuredContent?.let { put("structuredContent", it) }
        if (isError) put("isError", true)
    }

    override fun toString(): String =
        "McpToolResult(isError=$isError, content=${content.size} part(s), ${text.length} chars, structured=${structuredContent != null})"

    companion object {
        /** 一段纯文本。 */
        fun text(text: String): McpToolResult = McpToolResult(listOf(McpContent.Text(text)))

        /**
         * 紧凑 JSON 文本放进 content；值是对象时同时填 structuredContent（规范要求 structuredContent 是对象，
         * 数组或标量只放文本）。
         */
        fun json(value: JsonElement): McpToolResult =
            McpToolResult(listOf(McpContent.Text(value.toString())), structuredContent = value as? JsonObject)

        /** 工具执行失败：isError = true，[message] 是给模型看的一句话原因。 */
        fun error(message: String): McpToolResult = McpToolResult(listOf(McpContent.Text(message)), isError = true)

        internal fun fromJson(o: JsonObject): McpToolResult = McpToolResult(
            content = (o["content"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(McpContent::fromJson) },
            structuredContent = o["structuredContent"] as? JsonObject,
            isError = (o["isError"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }
}

/** 客户端从 `tools/list` 拿到的一个工具。描述和 schema 来自第三方，是不可信输入。 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val annotations: McpToolAnnotations = McpToolAnnotations(),
    val title: String? = null,
)

/** `initialize` 的结果。 */
data class McpServerInfo(
    val name: String,
    val version: String,
    /** 双方商定的 MCP 协议修订版。 */
    val protocolVersion: String,
    /** 服务端是否会发 `notifications/tools/list_changed`。 */
    val toolsListChanged: Boolean,
)

/** MCP 调用失败（[McpBinderClient] 抛出）。 */
open class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 服务端回了 JSON-RPC 错误（未知工具、参数不合法、未初始化…）。服务端收到了请求，但没有执行工具。 */
class McpRpcException(val code: Int, message: String) : McpException("mcp error $code: $message")

/**
 * 连接关闭了（本端关闭、对端关闭、对端进程死亡、通道违规）。
 * [dispatched] = 请求已经交给通道（可能已到达服务端，副作用未知）；false = 确定没有发出。
 */
class McpClosedException(val reason: String, val dispatched: Boolean) :
    McpException("mcp connection closed ($reason)${if (dispatched) "; the request was already sent" else ""}")

/** 等待超时。请求已经发出（已发送取消通知），副作用未知。 */
class McpTimeoutException(val method: String, val timeoutMillis: Long) :
    McpException("mcp $method timed out after $timeoutMillis ms")

/** 请求编码后超过通道的单条上限，没有发出（没有副作用）。 */
class McpRequestTooLargeException(val method: String, val length: Int, val limit: Int) :
    McpException("mcp $method request is $length characters, over the $limit limit; not sent")

/** 插件 App 与 AgentOS 之间的约定（docs/extensions.md 4.1）。 */
object AgentPluginContract {
    /** 插件 App 导出的 MCP Service 的 intent action；AgentOS 在 `<queries>` 里声明它来发现插件。 */
    const val ACTION_PLUGIN = "org.agentos.intent.action.PLUGIN"

    /** MCP Service 要求的权限：AgentOS 定义，signature，只有 AgentOS 持有。 */
    const val PERMISSION_BIND_MCP_SERVICE = "org.agentos.permission.BIND_MCP_SERVICE"

    /** Service 的 meta-data：插件包在 assets 里的目录。 */
    const val META_PLUGIN_ASSETS = "org.agentos.plugin.assets"

    /** 插件包目录的默认值。 */
    const val DEFAULT_PLUGIN_ASSETS = "agent-plugin"
}
