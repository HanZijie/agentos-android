package org.agentos.acp

import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import com.agentclientprotocol.rpc.decodeJsonRpcMessage

/**
 * JSON-RPC 消息与线上字符串的互转。Binder 通道和电脑端网关（W9）共用。
 *
 * 编码按具体类型的 serializer 进行：SDK 0.30.1 的 `JsonRpcMessage` 是 `@Serializable sealed interface`，
 * 按接口类型编码会多出一个 `"type":"com.agentclientprotocol.rpc.JsonRpcRequest"` 类鉴别字段
 * （S3 问题 3；SDK 自带的 StdioTransport 就是这样输出的），不是干净的 JSON-RPC。
 */
object JsonRpcCodec {
    fun encode(message: JsonRpcMessage): String = when (message) {
        is JsonRpcRequest -> ACPJson.encodeToString(JsonRpcRequest.serializer(), message)
        is JsonRpcNotification -> ACPJson.encodeToString(JsonRpcNotification.serializer(), message)
        is JsonRpcResponse -> ACPJson.encodeToString(JsonRpcResponse.serializer(), message)
    }

    /** 解析一条 JSON-RPC 消息；不是合法的 JSON-RPC 对象时抛异常。 */
    fun decode(raw: String): JsonRpcMessage = decodeJsonRpcMessage(raw)
}
