package org.agentos.plugin.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.agentos.plugin.McpToolResult
import org.agentos.plugin.ToolTable
import java.util.concurrent.ConcurrentHashMap

/**
 * 一条连接上的 MCP 服务端（只做 tools）。由 [org.agentos.plugin.McpBinderService] 为每条通道创建一个。
 *
 * - 生命周期：`initialize` 之前只接受 `initialize` 和 `ping`；`notifications/initialized` 不强制。
 * - `tools/call` 在 [handlerDispatcher] 上的子协程里运行；收到 `notifications/cancelled` 就取消它，并且不再回响应
 *   （规范：被取消的请求不必回响应）。handler 抛出的异常转成 `isError` 结果。
 * - 响应超过通道的单条上限时：`tools/call` 换成一个 `isError` 结果说明原因，其他请求换成 JSON-RPC 错误。
 * - 管道关闭后取消所有进行中的调用，[done] 完成。
 */
internal class McpServerSession(
    private val pipe: McpPipe,
    private val serverName: String,
    private val serverVersion: String,
    private val tools: () -> ToolTable,
    parentScope: CoroutineScope,
    private val handlerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: McpLogger = McpLogger.NONE,
) {
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + CoroutineName("mcp-server:$serverName")
    )
    private val inflight = ConcurrentHashMap<String, Job>()
    @Volatile private var initialized = false
    private val finished = CompletableDeferred<String>()

    /** 连接结束（参数是关闭原因）。 */
    val done: Deferred<String> get() = finished

    fun start() {
        scope.launch {
            try {
                for (raw in pipe.incoming) {
                    try {
                        handle(raw)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.log('W', "$serverName: failed to handle a message (${raw.length} chars): ${e.javaClass.simpleName}")
                    } finally {
                        pipe.consumed(raw.length)
                    }
                }
            } finally {
                inflight.values.forEach { it.cancel() }
                inflight.clear()
                finished.complete(pipe.closeReason() ?: "closed")
                scope.cancel()
            }
        }
    }

    /** 工具集变了：通知客户端重新 `tools/list`。初始化之前不发。 */
    fun notifyToolsChanged() {
        if (initialized) pipe.send(RpcCodec.encode(RpcNotification(McpProtocol.TOOLS_LIST_CHANGED, null)))
    }

    fun close(reason: String) = pipe.close(reason)

    private fun handle(raw: String) {
        val message = try {
            RpcCodec.decode(raw)
        } catch (e: IllegalArgumentException) {
            log.log('W', "$serverName: undecodable message (${raw.length} chars): ${e.message}")
            respondError(JsonNull, McpProtocol.PARSE_ERROR, "invalid JSON-RPC message: ${e.message}")
            return
        }
        when (message) {
            is RpcRequest -> onRequest(message)
            is RpcNotification -> onNotification(message)
            is RpcResponse -> Unit // 服务端不发请求，收到的响应忽略
        }
    }

    private fun onRequest(r: RpcRequest) {
        when (r.method) {
            McpProtocol.INITIALIZE -> {
                val requested = (r.params?.get("protocolVersion") as? JsonPrimitive)?.contentOrNull
                val version = requested?.takeIf { it in McpProtocol.SUPPORTED_PROTOCOL_VERSIONS } ?: McpProtocol.LATEST_PROTOCOL_VERSION
                initialized = true
                respond(r.id, buildJsonObject {
                    put("protocolVersion", version)
                    put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", true) }) })
                    put("serverInfo", buildJsonObject {
                        put("name", serverName)
                        put("version", serverVersion)
                    })
                })
            }
            McpProtocol.PING -> respond(r.id, JsonObject(emptyMap()))
            McpProtocol.TOOLS_LIST -> {
                if (!initialized) return respondError(r.id, McpProtocol.INVALID_REQUEST, "not initialized")
                val table = tools()
                respond(r.id, buildJsonObject { put("tools", buildJsonArray { table.tools.values.forEach { add(it.descriptor) } }) })
            }
            McpProtocol.TOOLS_CALL -> onCall(r)
            else -> respondError(r.id, McpProtocol.METHOD_NOT_FOUND, "method not supported: ${r.method}")
        }
    }

    private fun onCall(r: RpcRequest) {
        if (!initialized) return respondError(r.id, McpProtocol.INVALID_REQUEST, "not initialized")
        val name = (r.params?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return respondError(r.id, McpProtocol.INVALID_PARAMS, "tools/call needs a string \"name\"")
        val tool = tools().tools[name] ?: return respondError(r.id, McpProtocol.INVALID_PARAMS, "Unknown tool: $name")
        val rawArgs = r.params?.get("arguments")
        val args = when (rawArgs) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> rawArgs
            else -> return respondError(r.id, McpProtocol.INVALID_PARAMS, "\"arguments\" must be an object")
        }
        val key = r.id.key
        if (inflight.containsKey(key)) return respondError(r.id, McpProtocol.INVALID_REQUEST, "duplicate request id")
        val job = scope.launch(handlerDispatcher + CoroutineName("tool:$name"), start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val result = try {
                tool.handler(args)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.log('W', "$serverName: tool $name threw ${e.javaClass.simpleName}")
                McpToolResult.error("$name failed: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}")
            }
            // 已被取消（notifications/cancelled 先把它从 inflight 里拿走了）就不回
            if (inflight.remove(key) != null) respondResult(r.id, name, result)
        }
        inflight[key] = job
        job.start()
    }

    private fun onNotification(n: RpcNotification) {
        when (n.method) {
            McpProtocol.INITIALIZED -> initialized = true
            McpProtocol.CANCELLED -> {
                val id = n.params?.get("requestId") as? JsonPrimitive ?: return
                inflight.remove(id.key)?.let {
                    it.cancel(CancellationException("cancelled by client"))
                    log.log('I', "$serverName: a tools/call was cancelled by the client")
                }
            }
            else -> Unit
        }
    }

    private fun respondResult(id: JsonPrimitive, tool: String, result: McpToolResult) {
        val encoded = RpcCodec.encode(RpcResponse(id, result.toJson(), null))
        if (encoded.length <= pipe.maxMessageChars) {
            pipe.send(encoded)
            return
        }
        log.log('W', "$serverName: result of $tool is ${encoded.length} chars, over the ${pipe.maxMessageChars} limit")
        respond(id, McpToolResult.error(
            "The result of $tool is too large to return (${encoded.length} characters; the limit is ${pipe.maxMessageChars}). " +
                "Ask for less data, for example with a smaller limit or a narrower query."
        ).toJson())
    }

    private fun respond(id: JsonPrimitive, result: JsonElement) {
        val encoded = RpcCodec.encode(RpcResponse(id, result, null))
        if (pipe.send(encoded) == SendResult.TOO_LARGE) {
            respondError(id, McpProtocol.INTERNAL_ERROR, "response exceeds ${pipe.maxMessageChars} characters")
        }
    }

    private fun respondError(id: JsonPrimitive, code: Int, message: String) {
        pipe.send(RpcCodec.encode(RpcResponse(id, null, RpcError(code, message))))
    }
}
