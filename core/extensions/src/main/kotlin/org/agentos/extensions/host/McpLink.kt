package org.agentos.extensions.host

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import org.agentos.extensions.McpServerDecl
import org.agentos.extensions.registry.PluginRecord

/*
 * Extension Host 的纯逻辑（[ExtensionToolHost]）与 Android 之间的**接缝**：连接器、链接、工具信息、调用结果、异常、服务器状态。
 * 这里的类型都不依赖 Android，也不依赖 sdk:plugin-sdk（core:extensions 不能依赖它）；C 的实现用 McpBinderClient 做映射。
 */

/** 一个 MCP 服务器：插件 ID（[PluginRecord.id]，`<包名>/<assets 目录>`）加服务器名（清单里的名字）。 */
data class ServerKey(val pluginId: String, val server: String)

/**
 * 建立到一个 MCP 服务器的连接。Android 上：Binder 服务器 `bindService(BIND_AUTO_CREATE)` → `IMcpService.open` → MCP 初始化；
 * 远端 Streamable HTTP 服务器建立 HTTP 会话（M2 起）。**连接完成时 MCP 初始化已经做完**，返回的 [McpServerLink] 可以直接用。
 */
interface McpServerConnector {
    /**
     * @param plugin 插件的注册表记录（要用 `identity.packageName`、`id`）
     * @param server 要连的服务器声明（[McpServerDecl.Binder] 或 [McpServerDecl.StreamableHttp]）
     * @throws ConnectFailed 连不上（App 被停用、bind 失败、MCP 初始化失败、超时…）：**请求确定没有发出**
     */
    suspend fun connect(plugin: PluginRecord, server: McpServerDecl): McpServerLink
}

/**
 * 一条已经初始化好的 MCP 连接。**取消语义**：[callTool] 所在的协程被取消时，实现要尽力向服务端发
 * `notifications/cancelled`，然后照常抛 CancellationException（McpBinderClient 就是这样做的）；[ExtensionToolHost] 不再另外发取消。
 *
 * 所有挂起方法只抛 [McpLinkException] 的子类（和 CancellationException）；其他异常实现要先包装。
 */
interface McpServerLink {
    /** `tools/list`（含分页，实现负责取完）。 */
    suspend fun listTools(timeoutMillis: Long): List<McpToolInfo>

    /**
     * `tools/call`。[timeoutMillis] 到了还没有响应抛 [TimedOut]（请求已经发出）；服务端回 JSON-RPC 错误抛 [ServerError]；
     * 工具自己执行失败是**正常返回**，[McpCallResult.isError] 为 true。
     */
    suspend fun callTool(name: String, arguments: JsonObject, timeoutMillis: Long): McpCallResult

    /** 服务端发来 `notifications/tools/list_changed`。 */
    val toolsChanged: Flow<Unit>

    /** 连接关闭时完成，值是原因（对端进程死亡、`close()`、协议错误…）。主动 [close] 也会完成。 */
    val closed: Deferred<String>

    /** 关闭连接并释放底层资源（Binder 上是 unbind）。可以重复调用。 */
    fun close()
}

/** MCP 的 ToolAnnotations。都是提示，不可信：只用于调高风险（`RiskPolicy`）。 */
data class McpToolAnnotations(
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

/** `tools/list` 里的一个工具（**原文**：名字、描述、title 都来自第三方，不可信）。 */
data class McpToolInfo(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val inputSchema: JsonObject,
    val annotations: McpToolAnnotations? = null,
)

/** 工具结果里的一段内容。文本以外的类型保留原始 JSON（图片等：base64 的 `data` 与 `mimeType`）。 */
sealed interface McpContentPart {
    data class Text(val text: String) : McpContentPart

    /** 图片：[data] 是 base64。 */
    data class Image(val data: String, val mimeType: String) : McpContentPart

    /** 不认识的内容类型（音频、资源链接、嵌入资源…）：保留原始 JSON，宿主层只给模型一句“省略了一段 <type> 内容”。 */
    data class Other(val type: String, val json: JsonObject) : McpContentPart
}

/**
 * 一次 `tools/call` 的结果（MCP 的 CallToolResult）。[isError] 为 true 表示**工具执行失败**（参数不对、对象不存在、业务规则不允许），
 * 原因在 [content] 里给模型看；这不是协议错误，调用本身是完成了的。
 */
data class McpCallResult(
    val content: List<McpContentPart>,
    val structuredContent: JsonObject? = null,
    val isError: Boolean = false,
)

/**
 * 链接层的异常。**分类决定 ToolPort 的结局**（docs/architecture F8：结果未知不重放）：
 * - [mayHaveBeenSent] == false（[ConnectFailed]、[NotSent]）：请求**确定没有发出**，没有副作用 → `NotDispatched`；
 * - [mayHaveBeenSent] == true 且没有拿到回复（[ConnectionLost]、[TimedOut]）：请求**已经发出**，副作用未知 → `Unknown`，不重放；
 * - [ServerError]：服务端回了 JSON-RPC 错误（未知工具、参数无效…），**有明确回复** → 当作 isError 的结果交回模型。
 */
sealed class McpLinkException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    abstract val mayHaveBeenSent: Boolean
}

/** 连不上（bind 失败、App 没装或被停用、初始化失败、连接超时）。连接阶段的失败，请求没有发出。 */
class ConnectFailed(message: String, cause: Throwable? = null) : McpLinkException(message, cause) {
    override val mayHaveBeenSent: Boolean get() = false
}

/** 连接已经关闭或不可用，请求没有写进去。实现只在**确定**没写出去时抛它；拿不准就抛 [ConnectionLost]。 */
class NotSent(message: String, cause: Throwable? = null) : McpLinkException(message, cause) {
    override val mayHaveBeenSent: Boolean get() = false
}

/** 请求发出之后连接断了（对端进程死亡、Binder 死亡通知）：结果未知。 */
class ConnectionLost(message: String, cause: Throwable? = null) : McpLinkException(message, cause) {
    override val mayHaveBeenSent: Boolean get() = true
}

/** 请求发出之后在 [timeoutMillis] 内没有响应：结果未知（服务端可能还在执行）。 */
class TimedOut(val timeoutMillis: Long) : McpLinkException("no response within $timeoutMillis ms") {
    override val mayHaveBeenSent: Boolean get() = true
}

/** 服务端回了 JSON-RPC 错误。[code] 是 JSON-RPC 的 error.code。 */
class ServerError(val code: Int, val detail: String) : McpLinkException("MCP error $code: $detail") {
    override val mayHaveBeenSent: Boolean get() = true
}

/** 一个 MCP 服务器现在的状态，给插件页（[ExtensionToolHost.serverStates]）。 */
sealed interface ServerState {
    /** 现在没有连接（还没用到，或空闲 30 秒后已断开）。工具列表可能有缓存。 */
    data object Idle : ServerState

    /** 已连接。 */
    data object Connected : ServerState

    /** 连不上（[reason] 是最近一次失败的原因）：缓存的工具列表仍然保留，调用会被拒绝（NotDispatched）。 */
    data class Unreachable(val reason: String) : ServerState

    /** 不会连接：插件不可用 / 签名未确认，或被用户策略禁用。 */
    data class Disabled(val reason: DisabledReason) : ServerState
}

enum class DisabledReason {
    /** 用户策略：插件、服务器级禁用。 */
    USER_POLICY,

    /** 插件状态不是 READY（不可用、签名变化、签名未确认），或这个服务器不在插件的可用服务器里。 */
    PLUGIN_NOT_READY,
}
