package org.agentos.app.ext.mcp

import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.agentos.extensions.McpServerDecl
import org.agentos.extensions.host.ConnectFailed
import org.agentos.extensions.host.ConnectionLost
import org.agentos.extensions.host.McpCallResult
import org.agentos.extensions.host.McpContentPart
import org.agentos.extensions.host.McpLinkException
import org.agentos.extensions.host.McpServerConnector
import org.agentos.extensions.host.McpServerLink
import org.agentos.extensions.host.McpToolInfo
import org.agentos.extensions.host.NotSent
import org.agentos.extensions.host.ServerError
import org.agentos.extensions.host.TimedOut
import org.agentos.extensions.registry.PluginRecord
import org.agentos.plugin.McpBinderClient
import org.agentos.plugin.McpClosedException
import org.agentos.plugin.McpContent
import org.agentos.plugin.McpException
import org.agentos.plugin.McpRequestTooLargeException
import org.agentos.plugin.McpRpcException
import org.agentos.plugin.McpTimeoutException
import org.agentos.plugin.McpTool
import org.agentos.plugin.McpToolResult
import org.agentos.extensions.host.McpToolAnnotations as LinkAnnotations

/**
 * A 的 [McpServerConnector] 在 Android 上的实现（Binder 服务器）：`bindService(BIND_AUTO_CREATE)` → `IMcpService.open` →
 * MCP 初始化，都由 sdk:plugin-sdk 的 [McpBinderClient] 完成；这里只做异常和类型的映射（[mapFailure]、[toCallResult]、[toToolInfo]）。
 *
 * - 绑定的是插件记录里的包名 + 清单里的 Service 类名；绑定需要 `BIND_MCP_SERVICE`（AgentOS 持有，signature）。
 * - 入站消息按插件 App 的 UID 校验（BinderChannel 做；UID 取自 PackageManager）。
 * - 远端 Streamable HTTP（W19）还没有：一律 [ConnectFailed]。
 */
class BinderMcpConnector(
    private val context: Context,
    private val scope: CoroutineScope,
    private val clientVersion: String,
    private val connectTimeoutMillis: Long = CONNECT_TIMEOUT_MS,
) : McpServerConnector {

    override suspend fun connect(plugin: PluginRecord, server: McpServerDecl): McpServerLink {
        val decl = server as? McpServerDecl.Binder
            ?: throw ConnectFailed("server ${server.name}: remote Streamable HTTP servers are not supported yet")
        val component = ComponentName(plugin.identity.packageName, decl.service)
        val client = try {
            withTimeout(connectTimeoutMillis) {
                val c = McpBinderClient.bind(context, component, scope, timeoutMillis = connectTimeoutMillis)
                try {
                    c.initialize(CLIENT_NAME, clientVersion, connectTimeoutMillis)
                } catch (e: Throwable) {
                    c.close("initialize failed")
                    throw e
                }
                c
            }
        } catch (e: TimeoutCancellationException) {
            throw ConnectFailed("${component.flattenToShortString()}: no connection within $connectTimeoutMillis ms", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw ConnectFailed("${component.flattenToShortString()}: permission denied (${e.message})", e)
        } catch (e: Exception) {
            throw ConnectFailed("${component.flattenToShortString()}: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        val link = BinderMcpLink(client, component.flattenToShortString())
        live.add(link)
        connects.incrementAndGet()
        client.closed.invokeOnCompletion { live.remove(link) }
        return link
    }

    private val live = java.util.concurrent.ConcurrentHashMap.newKeySet<BinderMcpLink>()
    private val connects = java.util.concurrent.atomic.AtomicLong()

    /** 诊断：连接次数与每条活着的链接的通道统计（peerUid、uidRejects、收发计数；不含消息内容）。 */
    fun stats(): org.json.JSONObject = org.json.JSONObject()
        .put("connects", connects.get())
        .put("links", org.json.JSONArray(live.map { l ->
            org.json.JSONObject().put("component", l.component).put("channel", l.stats() ?: org.json.JSONObject.NULL)
        }))

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val CLIENT_NAME = "agentos"
    }
}

/** 一条已经初始化好的 Binder MCP 连接。只抛 [McpLinkException] 的子类和 CancellationException。 */
class BinderMcpLink(private val client: McpBinderClient, val component: String = "") : McpServerLink {

    fun stats(): org.json.JSONObject? = client.stats()


    override suspend fun listTools(timeoutMillis: Long): List<McpToolInfo> = mapped {
        client.listTools(if (timeoutMillis > 0) timeoutMillis else McpBinderClient.DEFAULT_TIMEOUT_MS).map { it.toToolInfo() }
    }

    override suspend fun callTool(name: String, arguments: JsonObject, timeoutMillis: Long): McpCallResult = mapped {
        client.callTool(name, arguments, timeoutMillis).toCallResult()
    }

    override val toolsChanged: Flow<Unit> get() = client.toolsChanged

    override val closed: Deferred<String> get() = client.closed

    override fun close() = client.close("closed by the extension host")

    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw mapFailure(e)
    }
}

/**
 * SDK 的失败 → A 的分类（决定 ToolPort 的结局，architecture F8）：
 * - 没有发出：[McpClosedException]（dispatched=false）、[McpRequestTooLargeException] → [NotSent]；
 * - 已经发出、没有回复：[McpClosedException]（dispatched=true）→ [ConnectionLost]，[McpTimeoutException] → [TimedOut]；
 * - 服务端回了 JSON-RPC 错误：[McpRpcException] → [ServerError]；
 * - 其他（回复格式不对等）：服务端有回复但不能用 → [ServerError]（-32603）；拿不准的异常 → [ConnectionLost]（不重放）。
 */
fun mapFailure(e: Throwable): McpLinkException = when (e) {
    is McpLinkException -> e
    is McpClosedException -> if (e.dispatched) ConnectionLost(e.message ?: "connection closed", e) else NotSent(e.message ?: "connection closed", e)
    is McpRequestTooLargeException -> NotSent(e.message ?: "request too large", e)
    is McpTimeoutException -> TimedOut(e.timeoutMillis)
    is McpRpcException -> ServerError(e.code, e.message ?: "")
    is McpException -> ServerError(-32603, e.message ?: "invalid response")
    else -> ConnectionLost("${e.javaClass.simpleName}: ${e.message}", e)
}

fun McpTool.toToolInfo(): McpToolInfo = McpToolInfo(
    name = name,
    title = title,
    description = description.ifEmpty { null },
    inputSchema = inputSchema,
    annotations = annotations.let { a ->
        if (a.readOnlyHint == null && a.destructiveHint == null && a.idempotentHint == null && a.openWorldHint == null) null
        else LinkAnnotations(a.readOnlyHint, a.destructiveHint, a.idempotentHint, a.openWorldHint)
    },
)

fun McpToolResult.toCallResult(): McpCallResult = McpCallResult(
    content = content.map { part ->
        when (part) {
            is McpContent.Text -> McpContentPart.Text(part.text)
            is McpContent.Raw -> {
                val type = (part.json["type"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
                val data = (part.json["data"] as? JsonPrimitive)?.contentOrNull
                val mime = (part.json["mimeType"] as? JsonPrimitive)?.contentOrNull
                if (type == "image" && data != null && mime != null) McpContentPart.Image(data, mime) else McpContentPart.Other(type, part.json)
            }
        }
    },
    structuredContent = structuredContent,
    isError = isError,
)
