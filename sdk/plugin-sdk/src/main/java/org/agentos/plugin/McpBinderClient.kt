package org.agentos.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.agentos.channel.ChannelConfig
import org.agentos.channel.IMcpService
import org.agentos.plugin.internal.AndroidMcpLog
import org.agentos.plugin.internal.McpLogger
import org.agentos.plugin.internal.McpPipe
import org.agentos.plugin.internal.McpProtocol
import org.agentos.plugin.internal.RpcCodec
import org.agentos.plugin.internal.RpcError
import org.agentos.plugin.internal.RpcNotification
import org.agentos.plugin.internal.RpcRequest
import org.agentos.plugin.internal.RpcResponse
import org.agentos.plugin.internal.SendResult
import org.agentos.plugin.internal.key
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP 客户端（只做 tools），AgentOS 的 Extension Host 和插件 App 的自测都用它。
 *
 * 用法：[connect]（给定 [IMcpService]）或 [bind]（给定 [ComponentName]，自己 bind / unbind）→ [initialize] →
 * [listTools] / [callTool]；[toolsChanged] 在服务端发 `notifications/tools/list_changed` 时发出；用完 [close]。
 *
 * 失败的区分（Extension Host 据此给出 Completed / NotDispatched / Unknown）：
 * - [McpRpcException]：服务端回了 JSON-RPC 错误（未知工具、参数不合法），工具没有执行；
 * - [McpClosedException]：连接关闭，`dispatched` 说明请求是否已经交给通道；
 * - [McpTimeoutException]：已经发出、等不到结果（已发送取消通知）；
 * - [McpRequestTooLargeException]：请求超长，没有发出。
 *
 * 调用方协程被取消时，向服务端发 `notifications/cancelled`，然后照常抛 CancellationException。
 */
class McpBinderClient internal constructor(
    private val pipe: McpPipe,
    parentScope: CoroutineScope,
    val name: String = "mcp-client",
    private val log: McpLogger = McpLogger.NONE,
    private val onClosed: (() -> Unit)? = null,
) {
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + CoroutineName(name)
    )
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<RpcResponse>>()
    private val closedReason = CompletableDeferred<String>()
    private val toolsChangedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 服务端发来 `notifications/tools/list_changed`。 */
    val toolsChanged: SharedFlow<Unit> = toolsChangedFlow.asSharedFlow()

    /** [initialize] 之后可用。 */
    @Volatile var serverInfo: McpServerInfo? = null
        private set

    val isOpen: Boolean get() = !closedReason.isCompleted

    /** 连接关闭时完成，值是关闭原因。 */
    val closed: Deferred<String> get() = closedReason

    init {
        scope.launch(CoroutineName("$name.read")) {
            try {
                for (raw in pipe.incoming) {
                    try {
                        onMessage(raw)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.log('W', "$name: failed to handle a message (${raw.length} chars): ${e.javaClass.simpleName}")
                    } finally {
                        pipe.consumed(raw.length)
                    }
                }
            } finally {
                val reason = pipe.closeReason() ?: "closed"
                closedReason.complete(reason)
                // 先标记关闭、再清 pending：与 request() 里“先登记、再检查是否已关闭”配对，不会漏掉等待者
                for (k in pending.keys.toTypedArray()) {
                    pending.remove(k)?.completeExceptionally(McpClosedException(reason, dispatched = true))
                }
                runCatching { onClosed?.invoke() }
                scope.cancel()
            }
        }
    }

    /**
     * MCP 初始化：发 `initialize`，校验协议修订版，再发 `notifications/initialized`。
     * @throws McpException 修订版不支持（连接随之关闭）或其他失败
     */
    suspend fun initialize(
        clientName: String = "agentos",
        clientVersion: String = "1.0.0",
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ): McpServerInfo {
        val result = request(McpProtocol.INITIALIZE, buildJsonObject {
            put("protocolVersion", McpProtocol.LATEST_PROTOCOL_VERSION)
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject {
                put("name", clientName)
                put("version", clientVersion)
            })
        }, timeoutMillis) as? JsonObject ?: throw McpException("initialize: result is not an object")
        val version = (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        if (version == null || version !in McpProtocol.SUPPORTED_PROTOCOL_VERSIONS) {
            close("unsupported protocol version")
            throw McpException("initialize: unsupported MCP protocol version $version (supported: ${McpProtocol.SUPPORTED_PROTOCOL_VERSIONS})")
        }
        val info = result["serverInfo"] as? JsonObject
        val tools = (result["capabilities"] as? JsonObject)?.get("tools") as? JsonObject
        val server = McpServerInfo(
            name = (info?.get("name") as? JsonPrimitive)?.contentOrNull ?: "",
            version = (info?.get("version") as? JsonPrimitive)?.contentOrNull ?: "",
            protocolVersion = version,
            toolsListChanged = (tools?.get("listChanged") as? JsonPrimitive)?.booleanOrNull == true,
        )
        notify(McpProtocol.INITIALIZED, null)
        serverInfo = server
        return server
    }

    /** `tools/list`（自动翻页）。格式不对的条目跳过（第三方输入）。 */
    suspend fun listTools(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): List<McpTool> {
        val out = ArrayList<McpTool>()
        var cursor: String? = null
        var skipped = 0
        for (page in 0 until McpProtocol.MAX_LIST_PAGES) {
            val result = request(McpProtocol.TOOLS_LIST, cursor?.let { c -> buildJsonObject { put("cursor", c) } }, timeoutMillis) as? JsonObject
                ?: throw McpException("tools/list: result is not an object")
            for (e in (result["tools"] as? JsonArray).orEmpty()) {
                val t = (e as? JsonObject)?.let(::parseTool)
                if (t == null) skipped++ else out += t
            }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull ?: break
        }
        if (skipped > 0) log.log('W', "$name: tools/list skipped $skipped malformed tool(s)")
        return out
    }

    /**
     * `tools/call`。工具执行失败时返回 `isError = true` 的结果，不抛异常。
     * @param timeoutMillis 0 = 不限；超时后发送取消通知并抛 [McpTimeoutException]。
     */
    suspend fun callTool(name: String, arguments: JsonObject = JsonObject(emptyMap()), timeoutMillis: Long = 0): McpToolResult {
        val result = request(McpProtocol.TOOLS_CALL, buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }, timeoutMillis) as? JsonObject ?: throw McpException("tools/call: result is not an object")
        return McpToolResult.fromJson(result)
    }

    suspend fun ping(timeoutMillis: Long = DEFAULT_TIMEOUT_MS) {
        request(McpProtocol.PING, null, timeoutMillis)
    }

    /**
     * 诊断用的通道统计（binder-channel-v1：peerUid、uidRejects、收发计数、流控；不含消息内容）。
     * 每个入站调用都按 peerUid（插件 App 的 UID，取自 PackageManager）校验 `Binder.getCallingUid()`，不符的计入 uidRejects，然后关掉通道。
     */
    fun stats(): org.json.JSONObject? = pipe.stats()

    /** 关闭连接；进行中的请求以 [McpClosedException] 结束。用 [bind] 建立的连接同时 unbind。 */
    fun close(reason: String = "client closed") = pipe.close(reason)

    // ------------------------------------------------------------------ 内部

    private suspend fun request(method: String, params: JsonObject?, timeoutMillis: Long): JsonElement {
        val id = JsonPrimitive(nextId.getAndIncrement())
        val waiter = CompletableDeferred<RpcResponse>()
        pending[id.key] = waiter
        val encoded = RpcCodec.encode(RpcRequest(id, method, params))
        when (pipe.send(encoded)) {
            SendResult.OK -> Unit
            SendResult.TOO_LARGE -> {
                pending.remove(id.key)
                throw McpRequestTooLargeException(method, encoded.length, pipe.maxMessageChars)
            }
            SendResult.CLOSED -> {
                pending.remove(id.key)
                throw McpClosedException(pipe.closeReason() ?: "closed", dispatched = false)
            }
        }
        if (closedReason.isCompleted) {
            pending.remove(id.key)?.completeExceptionally(McpClosedException(pipe.closeReason() ?: "closed", dispatched = true))
        }
        val response = try {
            if (timeoutMillis > 0) {
                withTimeoutOrNull(timeoutMillis) { waiter.await() } ?: run {
                    cancelRemote(id, "timeout")
                    throw McpTimeoutException(method, timeoutMillis)
                }
            } else {
                waiter.await()
            }
        } catch (e: CancellationException) {
            cancelRemote(id, "cancelled by client")
            throw e
        }
        response.error?.let { throw McpRpcException(it.code, it.message) }
        return response.result ?: JsonObject(emptyMap())
    }

    /** 不再等这个请求，并告诉服务端（规范的 notifications/cancelled）。 */
    private fun cancelRemote(id: JsonPrimitive, reason: String) {
        if (pending.remove(id.key) != null && isOpen) {
            notify(McpProtocol.CANCELLED, buildJsonObject {
                put("requestId", id)
                put("reason", reason)
            })
        }
    }

    private fun notify(method: String, params: JsonObject?) {
        pipe.send(RpcCodec.encode(RpcNotification(method, params)))
    }

    private fun onMessage(raw: String) {
        val message = try {
            RpcCodec.decode(raw)
        } catch (e: IllegalArgumentException) {
            log.log('W', "$name: undecodable message (${raw.length} chars): ${e.message}")
            return
        }
        when (message) {
            is RpcResponse -> pending.remove(message.id.key)?.complete(message)
            is RpcNotification -> if (message.method == McpProtocol.TOOLS_LIST_CHANGED) toolsChangedFlow.tryEmit(Unit)
            is RpcRequest -> {
                // 服务端发来的请求：只回 ping，其余（sampling、roots、elicitation）不支持
                val reply = if (message.method == McpProtocol.PING) {
                    RpcResponse(message.id, JsonObject(emptyMap()), null)
                } else {
                    RpcResponse(message.id, null, RpcError(McpProtocol.METHOD_NOT_FOUND, "client does not support ${message.method}"))
                }
                pipe.send(RpcCodec.encode(reply))
            }
        }
    }

    private fun parseTool(o: JsonObject): McpTool? {
        val toolName = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val schema = o["inputSchema"] as? JsonObject ?: return null
        val annotations = o["annotations"] as? JsonObject
        return McpTool(
            name = toolName,
            description = (o["description"] as? JsonPrimitive)?.contentOrNull ?: "",
            inputSchema = schema,
            annotations = McpToolAnnotations.fromJson(annotations),
            title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: (annotations?.get("title") as? JsonPrimitive)?.contentOrNull,
        )
    }

    companion object {
        /** initialize、tools/list、ping 的默认超时。 */
        const val DEFAULT_TIMEOUT_MS = 15_000L

        /**
         * 在已经拿到的 [IMcpService] 上建立连接（还没有初始化，接着调用 [initialize]）。
         * @param serviceUid 提供服务的 App 的 UID，入站消息按它校验。
         */
        suspend fun connect(
            service: IMcpService,
            serviceUid: Int,
            scope: CoroutineScope,
            config: ChannelConfig = ChannelConfig.DEFAULT,
            name: String = "mcp-client",
        ): McpBinderClient = connectInternal(service, serviceUid, scope, config, name, null)

        /**
         * bind 一个 MCP 服务（`BIND_AUTO_CREATE`）并建立连接；[close] 或连接断开时自动 unbind。还没有初始化。
         * 绑定其他 App 的服务需要 `org.agentos.permission.BIND_MCP_SERVICE`（只有 AgentOS 有）；
         * 插件 App 绑定自己的服务不需要（同一个 App）。
         *
         * @throws SecurityException 没有权限
         * @throws McpException bindService 返回 false（服务不存在或没有导出）
         * @throws McpTimeoutException 在 [timeoutMillis] 内没有连上
         */
        suspend fun bind(
            context: Context,
            component: ComponentName,
            scope: CoroutineScope,
            timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
            config: ChannelConfig = ChannelConfig.DEFAULT,
        ): McpBinderClient {
            val app = context.applicationContext ?: context
            val connected = CompletableDeferred<IBinder>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (service != null) connected.complete(service)
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit // 通道经 linkToDeath 关闭

                override fun onNullBinding(name: ComponentName?) {
                    connected.completeExceptionally(McpException("$component returned a null binding"))
                }
            }
            fun unbind() = runCatching { app.unbindService(connection) }
            val ok = app.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)
            if (!ok) {
                unbind()
                throw McpException("bindService($component) returned false: the service does not exist or is not exported")
            }
            try {
                val binder = withTimeoutOrNull(timeoutMillis) { connected.await() }
                    ?: throw McpTimeoutException("bind", timeoutMillis)
                val uid = app.packageManager.getApplicationInfo(component.packageName, 0).uid
                return connectInternal(IMcpService.Stub.asInterface(binder), uid, scope, config, "mcp-client:${component.shortClassName}", ::unbind)
            } catch (e: Throwable) {
                unbind()
                throw e
            }
        }

        private suspend fun connectInternal(
            service: IMcpService,
            serviceUid: Int,
            scope: CoroutineScope,
            config: ChannelConfig,
            name: String,
            onClosed: (() -> Unit)?,
        ): McpBinderClient {
            val transport = McpBinderTransport.connect(service, serviceUid, scope, config, name)
            return McpBinderClient(transport.pipe, scope, name, AndroidMcpLog, onClosed)
        }
    }
}
