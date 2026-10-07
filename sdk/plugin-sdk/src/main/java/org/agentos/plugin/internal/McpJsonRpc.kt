package org.agentos.plugin.internal

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * MCP 的协议层常量。只实现 tools（S5：自己写的 JSON-RPC 层，见 docs/spikes/S5.md）。
 * Binder 通道一条就是一次连接，所以按有会话的修订版实现生命周期（initialize → initialized → …）。
 */
internal object McpProtocol {
    /** 实现的修订版（initialize 里首选）。 */
    const val LATEST_PROTOCOL_VERSION = "2025-06-18"

    /** 能接受的修订版，新的在前。tools 部分在这几个修订版之间没有不兼容的变化。 */
    val SUPPORTED_PROTOCOL_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

    const val JSONRPC = "2.0"

    // 方法
    const val INITIALIZE = "initialize"
    const val INITIALIZED = "notifications/initialized"
    const val PING = "ping"
    const val TOOLS_LIST = "tools/list"
    const val TOOLS_CALL = "tools/call"
    const val CANCELLED = "notifications/cancelled"
    const val TOOLS_LIST_CHANGED = "notifications/tools/list_changed"

    // JSON-RPC 错误码
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    /** tools/list 翻页的上限（防止对端无限给 nextCursor）。 */
    const val MAX_LIST_PAGES = 100
}

internal sealed interface RpcMessage

internal data class RpcRequest(val id: JsonPrimitive, val method: String, val params: JsonObject?) : RpcMessage

internal data class RpcNotification(val method: String, val params: JsonObject?) : RpcMessage

internal data class RpcResponse(val id: JsonPrimitive, val result: JsonElement?, val error: RpcError?) : RpcMessage

internal data class RpcError(val code: Int, val message: String, val data: JsonElement? = null)

/** 当作 map 的键：保留字符串和数字的区别（"1" 与 1 是不同的 id）。 */
internal val JsonPrimitive.key: String get() = toString()

/** JSON-RPC 2.0 消息与字符串之间的转换。不支持批量（MCP 2025-06-18 去掉了批量）。 */
internal object RpcCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(m: RpcMessage): String = when (m) {
        is RpcRequest -> buildJsonObject {
            put("jsonrpc", McpProtocol.JSONRPC)
            put("id", m.id)
            put("method", m.method)
            m.params?.let { put("params", it) }
        }
        is RpcNotification -> buildJsonObject {
            put("jsonrpc", McpProtocol.JSONRPC)
            put("method", m.method)
            m.params?.let { put("params", it) }
        }
        is RpcResponse -> buildJsonObject {
            put("jsonrpc", McpProtocol.JSONRPC)
            put("id", m.id)
            if (m.error != null) {
                put("error", buildJsonObject {
                    put("code", m.error.code)
                    put("message", m.error.message)
                    m.error.data?.let { put("data", it) }
                })
            } else {
                put("result", m.result ?: JsonObject(emptyMap()))
            }
        }
    }.toString()

    /** 解析失败抛 [IllegalArgumentException]（消息里不带原文）。 */
    fun decode(text: String): RpcMessage {
        val o = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("not JSON")
        } as? JsonObject ?: throw IllegalArgumentException("not a JSON-RPC object (batches are not supported)")
        if ((o["jsonrpc"] as? JsonPrimitive)?.contentOrNull != McpProtocol.JSONRPC) throw IllegalArgumentException("jsonrpc must be \"2.0\"")
        val id = o["id"].let { if (it == null || it is JsonNull) null else it as? JsonPrimitive ?: throw IllegalArgumentException("bad id") }
        val method = (o["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val params = o["params"].let { if (it == null || it is JsonNull) null else it as? JsonObject ?: throw IllegalArgumentException("params must be an object") }
        return when {
            method != null && id != null -> RpcRequest(id, method, params)
            method != null -> RpcNotification(method, params)
            id != null && o.containsKey("error") -> {
                val e = o["error"] as? JsonObject ?: throw IllegalArgumentException("bad error")
                RpcResponse(
                    id, null,
                    RpcError(
                        (e["code"] as? JsonPrimitive)?.intOrNull ?: McpProtocol.INTERNAL_ERROR,
                        (e["message"] as? JsonPrimitive)?.contentOrNull ?: "",
                        e["data"],
                    ),
                )
            }
            id != null && o.containsKey("result") -> RpcResponse(id, o["result"], null)
            else -> throw IllegalArgumentException("neither request, notification nor response")
        }
    }
}

/** 出站的结果。 */
internal enum class SendResult { OK, TOO_LARGE, CLOSED }

/**
 * 承载 MCP 消息的双向管道。生产实现是 [org.agentos.plugin.McpBinderTransport]（binder-channel-v1）；
 * 单元测试用内存实现。
 */
internal interface McpPipe {
    /** 入站消息，按顺序；管道关闭后先交付已收到的再结束。每条处理完调用 [consumed]。 */
    val incoming: ReceiveChannel<String>

    /** 一条入站消息处理完（流控回 ack）。 */
    fun consumed(length: Int)

    /** 出站：只入队，不阻塞。 */
    fun send(text: String): SendResult

    /** 单条消息的上限（字符）。 */
    val maxMessageChars: Int

    fun close(reason: String)

    /** 关闭原因（[incoming] 结束后一定有值）。 */
    fun closeReason(): String?
}
