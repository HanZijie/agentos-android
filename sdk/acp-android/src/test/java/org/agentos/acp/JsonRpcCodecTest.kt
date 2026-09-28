package org.agentos.acp

import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.rpc.RequestId
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        params = buildJsonObject { put("text", "中文 😀") },
    )
    private val response = JsonRpcResponse(id = RequestId.create("r-1"), result = buildJsonObject { put("stopReason", "end_turn") })

    @Test
    fun encodesPlainJsonRpcWithoutClassDiscriminator() {
        for (m in listOf<JsonRpcMessage>(request, notification, response)) {
            val encoded = JsonRpcCodec.encode(m)
            val obj = ACPJson.parseToJsonElement(encoded).jsonObject
            assertFalse("unexpected type key in $encoded", obj.containsKey("type"))
            assertEquals(JsonPrimitive("2.0"), obj["jsonrpc"])
            assertFalse("one message must be one line", encoded.contains('\n'))
        }
    }

    @Test
    fun roundTrips() {
        for (m in listOf<JsonRpcMessage>(request, notification, response)) {
            assertEquals(m, JsonRpcCodec.decode(JsonRpcCodec.encode(m)))
        }
    }

    @Test
    fun decodesMessagesFromOtherImplementations() {
        // 官方 TypeScript 客户端的形状：字段顺序不同、没有多余字段
        val m = JsonRpcCodec.decode("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}""")
        assertTrue(m is JsonRpcRequest)
        // SDK 自带编码带 "type" 字段的形状也能解（忽略未知字段）
        val n = JsonRpcCodec.decode("""{"type":"com.agentclientprotocol.rpc.JsonRpcNotification","method":"session/cancel","jsonrpc":"2.0"}""")
        assertTrue(n is JsonRpcNotification)
    }

    @Test(expected = Exception::class)
    fun rejectsNonJsonRpc() {
        JsonRpcCodec.decode("""{"hello":"world"}""")
    }

    @Test
    fun acpServiceReasons() {
        val e = SecurityException(AcpServiceContract.message(AcpServiceContract.REASON_NOT_OPEN, "third-party apps are not enabled"))
        assertEquals(AcpServiceContract.REASON_NOT_OPEN, AcpServiceContract.reasonOf(e))
        assertNull(AcpServiceContract.reasonOf(SecurityException("Permission Denial")))
        assertNull(AcpServiceContract.reasonOf(SecurityException()))
    }
}
