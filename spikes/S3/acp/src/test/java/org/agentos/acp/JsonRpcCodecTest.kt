package org.agentos.acp

import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.rpc.RequestId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRpcCodecTest {
    private val request = JsonRpcRequest(
        id = RequestId.create(7),
        method = MethodName("session/prompt"),
        params = buildJsonObject { put("sessionId", "s1") },
    )
    private val notification = JsonRpcNotification(
        method = MethodName("session/update"),
        params = buildJsonObject { put("x", 1) },
    )
    private val response = JsonRpcResponse(id = RequestId.create(7), result = buildJsonObject { put("stopReason", "end_turn") })

    @Test
    fun encodesPlainJsonRpcWithoutClassDiscriminator() {
        for (m in listOf<JsonRpcMessage>(request, notification, response)) {
            val obj = ACPJson.parseToJsonElement(JsonRpcCodec.encode(m)).jsonObject
            assertFalse("unexpected type key in ${JsonRpcCodec.encode(m)}", obj.containsKey("type"))
            assertEquals(JsonPrimitive("2.0"), obj["jsonrpc"])
        }
    }

    @Test
    fun roundTrips() {
        for (m in listOf<JsonRpcMessage>(request, notification, response)) {
            assertEquals(m, JsonRpcCodec.decode(JsonRpcCodec.encode(m)))
        }
    }

    /** 记录 SDK 自带编码方式（StdioTransport 用的就是它）在 0.30.1 上的实际输出。 */
    @Test
    fun recordsSdkPolymorphicEncoding() {
        val sdk = ACPJson.encodeToString<JsonRpcMessage>(request)
        println("SDK polymorphic encoding: $sdk")
        println("JsonRpcCodec encoding:    ${JsonRpcCodec.encode(request)}")
        assertTrue(sdk.contains("\"method\":\"session/prompt\""))
    }
}
