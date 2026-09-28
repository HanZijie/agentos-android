package org.agentos.runtime.acp

import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.rpc.RequestId
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.testing.TestLineCodec
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W9：电脑端网关和电脑上的 stdio Agent 共用的按行传输。 */
class LineTransportTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    // ------------------------------------------------------------------ BoundedLineReader

    private fun reader(text: String, buffer: Int = 4) = BoundedLineReader(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), buffer)

    @Test
    fun `lines are split across buffer boundaries, CRLF is accepted, and the last line may lack a newline`() {
        val r = reader("ab\r\ncdefghij\n\n中文字符\nend")
        assertEquals("ab", r.readLine(100))
        assertEquals("cdefghij", r.readLine(100))
        assertEquals("", r.readLine(100))
        assertEquals("中文字符", r.readLine(100))
        assertEquals("end", r.readLine(100))
        assertNull(r.readLine(100))
    }

    @Test
    fun `a line longer than the limit throws without reading the rest of it`() {
        assertEquals("x".repeat(10), reader("x".repeat(10) + "\n").readLine(10))
        assertEquals("x".repeat(10), reader("x".repeat(10) + "\r\n").readLine(10))
        assertFailsWith<LineTooLongException> { reader("x".repeat(11) + "\n").readLine(10) }
        assertFailsWith<LineTooLongException> { reader("x".repeat(11)).readLine(10) }
        // 超长的判断不等换行符出现
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 'x'.code
        }
        assertFailsWith<LineTooLongException> { BoundedLineReader(endless).readLine(65_536) }
    }

    // ------------------------------------------------------------------ LineTransport

    /** 一对连着的 TCP socket：transport 在 agent 端，测试在 peer 端直接读写字节。 */
    private inner class Wire(maxLineChars: Int = LineTransport.MAX_LINE_CHARS) {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val peer = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val agent: Socket = server.accept().also { server.close() }
        val received: MutableList<JsonRpcMessage> = Collections.synchronizedList(mutableListOf())
        val closed = CompletableDeferred<Unit>()
        val transport = LineTransport(
            reader = BoundedLineReader(agent.getInputStream()),
            output = agent.getOutputStream(),
            parentScope = scope,
            codec = TestLineCodec,
            name = "test",
            maxLineChars = maxLineChars,
            closeStreams = { agent.close() },
            drainMillis = 500,
        ).apply {
            onMessage { received += it }
            onClose { closed.complete(Unit) }
            start()
        }
        val peerIn = BoundedLineReader(peer.getInputStream())

        fun send(line: String) {
            peer.getOutputStream().write((line + "\n").toByteArray(Charsets.UTF_8))
            peer.getOutputStream().flush()
        }

        fun readLine(): String? {
            peer.soTimeout = 5_000
            return peerIn.readLine(1_000_000)
        }
    }

    private fun notification(text: String) = JsonRpcNotification(
        method = MethodName("session/update"),
        params = buildJsonObject { put("text", text) },
    )

    @Test
    fun `messages are one line each, with no SDK class discriminator`() = runBlocking {
        val w = Wire()
        w.send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}""")
        withTimeout(5_000) { while (w.received.isEmpty()) kotlinx.coroutines.delay(5) }
        assertIs<JsonRpcRequest>(w.received.single())
        w.transport.send(JsonRpcResponse(id = RequestId.create(1), result = buildJsonObject { put("ok", true) }))
        w.transport.send(notification("a\nb"))
        val first = kotlinx.serialization.json.Json.parseToJsonElement(w.readLine()!!).jsonObject
        assertEquals(setOf("jsonrpc", "id", "result"), first.keys)
        val second = kotlinx.serialization.json.Json.parseToJsonElement(w.readLine()!!).jsonObject
        assertEquals(setOf("jsonrpc", "method", "params"), second.keys)
        assertEquals("a\nb", (second["params"]!!.jsonObject["text"] as JsonPrimitive).content)
        w.transport.close()
    }

    @Test
    fun `an inbound line over the limit closes the connection`() = runBlocking {
        val w = Wire(maxLineChars = 1_000)
        w.send("""{"jsonrpc":"2.0","method":"x","params":{"p":"${"y".repeat(1_000)}"}}""")
        withTimeout(5_000) { w.closed.await() }
        assertEquals("line_too_long", w.transport.closeReason)
        assertEquals(Transport.State.CLOSED, w.transport.state.value)
        assertNull(w.readLine(), "the peer sees EOF")
        assertTrue(w.received.isEmpty())
    }

    @Test
    fun `undecodable lines are skipped and counted`() = runBlocking {
        val w = Wire()
        w.send("not json")
        w.send("""{"jsonrpc":"2.0","method":"ok"}""")
        withTimeout(5_000) { while (w.received.isEmpty()) kotlinx.coroutines.delay(5) }
        assertEquals(1, w.transport.stats().decodeErrors)
        w.transport.close()
    }

    @Test
    fun `outbound messages over the limit are never written`() = runBlocking {
        val w = Wire(maxLineChars = 1_000)
        val big = buildJsonObject { put("text", "z".repeat(2_000)) }
        // 通知：丢弃
        w.transport.send(JsonRpcNotification(method = MethodName("session/update"), params = big))
        // 响应：改发错误响应
        w.transport.send(JsonRpcResponse(id = RequestId.create(5), result = big))
        val line = w.readLine()!!
        assertTrue(line.length <= 1_000)
        val error = kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject
        assertEquals(JsonPrimitive(5), error["id"])
        assertTrue((error["error"]!!.jsonObject["message"] as JsonPrimitive).content.startsWith(LineTransport.TOO_LARGE_PREFIX))
        // 请求：本地合成一个错误响应
        w.transport.send(JsonRpcRequest(id = RequestId.create(9), method = MethodName("fs/read_text_file"), params = big))
        withTimeout(5_000) { while (w.received.isEmpty()) kotlinx.coroutines.delay(5) }
        val synthetic = assertIs<JsonRpcResponse>(w.received.single())
        assertEquals(RequestId.create(9), synthetic.id)
        val s = w.transport.stats()
        assertEquals(1, s.droppedTooLarge)
        assertEquals(2, s.syntheticErrors)
        assertEquals(1, s.linesOut)
        w.transport.close()
    }

    @Test
    fun `peer close ends the transport, and close drains what was queued`() = runBlocking {
        val w = Wire()
        w.peer.close()
        withTimeout(5_000) { w.closed.await() }
        assertEquals("peer_closed", w.transport.closeReason)

        val w2 = Wire()
        repeat(50) { w2.transport.send(notification("n$it")) }
        w2.transport.close()
        val lines = generateSequence { w2.readLine() }.toList()
        assertEquals(50, lines.size, "queued messages are written before the connection closes")
        withTimeout(5_000) { w2.closed.await() }
        assertEquals("closed", w2.transport.closeReason)
        w2.transport.awaitWritable(0) // 关闭后不再挂起
    }

    @Test
    fun `abort closes at once`() = runBlocking {
        val w = Wire()
        w.transport.abort("switch off")
        withTimeout(5_000) { w.closed.await() }
        assertEquals("aborted: switch off", w.transport.closeReason)
        assertNull(w.readLine())
    }
}
