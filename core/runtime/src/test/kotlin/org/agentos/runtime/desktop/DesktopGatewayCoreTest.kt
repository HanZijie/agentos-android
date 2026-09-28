package org.agentos.runtime.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.agentos.runtime.RuntimeConfig
import org.agentos.runtime.acp.BoundedLineReader
import org.agentos.runtime.errors.RpcCodes
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.ConsentDecision
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.scheduler.SchedulerConfig
import org.agentos.runtime.testing.FakeScripts
import org.agentos.runtime.testing.TcpDesktopListenerFactory
import org.agentos.runtime.testing.TestLineCodec
import org.agentos.runtime.testing.TestRuntime
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W9：电脑端网关的平台无关部分（[DesktopGatewayCore]）经真实 TCP 连接 + 完整的运行时（FakeAgentCore）。
 * 客户端在这里直接收发原始的 JSON-RPC 行，才能断言“握手之前一条 ACP 消息都没有被处理”。
 */
class DesktopGatewayCoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rt = TestRuntime(FakeScripts.directives(), config = RuntimeConfig(scheduler = SchedulerConfig(tickMillis = 20)))
    private var listeners = TcpDesktopListenerFactory()
    private lateinit var gateway: DesktopGatewayCore

    private fun start(
        config: DesktopGatewayConfig = DesktopGatewayConfig(),
        peerUid: Int = TcpDesktopListenerFactory.SHELL_UID,
        store: PairingStore = MemoryPairingStore(),
    ) = runBlocking {
        rt.host.tools.register("send_note", ToolRisk.WRITE) { ToolInvocationResult.Completed(ToolResult.text("sent")) }
        rt.start()
        listeners = TcpDesktopListenerFactory(peerUid = peerUid)
        gateway = DesktopGatewayCore(
            runtime = rt.engine,
            pairing = DesktopPairing(store),
            codec = TestLineCodec,
            listenerFactory = listeners,
            parentScope = scope,
            config = config,
            peerAllowed = { it == 0 || it == 2000 },
        )
        gateway.restore()
    }

    @AfterTest
    fun tearDown() = runBlocking {
        if (::gateway.isInitialized) gateway.shutdown()
        rt.stop()
        rt.host.deleteDatabase()
        scope.cancel()
    }

    /** 测试这边的“电脑”：原始的一行一条。 */
    private inner class Raw(port: Int = listeners.port) {
        val socket = Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 10_000 }
        private val input = BoundedLineReader(socket.getInputStream())
        val seen = mutableListOf<JsonObject>()

        fun send(vararg lines: String) {
            socket.getOutputStream().write(lines.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
        }

        /** 下一行；连接已关闭时返回 null。 */
        fun next(): JsonObject? = try {
            input.readLine(1_000_000)?.let { Json.parseToJsonElement(it).jsonObject }?.also { seen += it }
        } catch (e: SocketTimeoutException) {
            throw AssertionError("no line within 10 s")
        } catch (e: IOException) {
            null
        }

        /** 读到 id 为 [id] 的响应为止（中间的通知记进 [seen]）。 */
        fun response(id: Int): JsonObject {
            while (true) {
                val m = next() ?: throw AssertionError("closed before the response to $id")
                if ((m["id"] as? JsonPrimitive)?.content == id.toString() && m["method"] == null) return m
            }
        }

        fun pair(code: String? = null, token: String? = null): JsonObject {
            val p = if (code != null) """"code":"$code","label":"test-laptop"""" else """"token":"$token""""
            send("""{"jsonrpc":"2.0","id":0,"method":"_org.agentos/pair","params":{"version":1,$p}}""")
            return next() ?: throw AssertionError("closed without a handshake response")
        }

        fun close() = socket.close()
    }

    private fun JsonObject.reason() = this["error"]!!.jsonObject["data"]!!.jsonObject["details"]!!.jsonObject["reason"]!!.jsonPrimitive.content
    private fun JsonObject.errorCode() = this["error"]!!.jsonObject["code"]!!.jsonPrimitive.int
    private fun JsonObject.token() = this["result"]!!.jsonObject["token"]!!.jsonPrimitive.content

    private val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}"""
    private val newSession = """{"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":"/sdcard","mcpServers":[]}}"""

    /** 打开开关、配对，返回已完成握手的连接和令牌。 */
    private fun paired(): Pair<Raw, String> {
        gateway.setEnabled(true)
        val code = gateway.newPairingCode()
        val raw = Raw()
        val ok = raw.pair(code = code.code)
        return raw to ok.token()
    }

    @Test
    fun `off by default - nothing listens until the switch is turned on, and turning it off stops listening`() {
        start()
        assertFalse(gateway.enabled)
        assertFalse(gateway.listening)
        gateway.setEnabled(true)
        assertTrue(gateway.listening)
        val port = listeners.port
        Raw(port).close()
        gateway.setEnabled(false)
        assertFalse(gateway.listening)
        assertFailsWith<ConnectException> { Raw(port) }
    }

    @Test
    fun `an ACP message before pairing gets auth_required and the connection closes without processing anything`() {
        start()
        gateway.setEnabled(true)
        gateway.newPairingCode()
        val raw = Raw()
        raw.send(initialize, newSession)
        val reply = raw.next()!!
        assertEquals(1, reply["id"]!!.jsonPrimitive.int, "answers the initialize id so the client fails fast")
        assertEquals(RpcCodes.AUTH_REQUIRED, reply.errorCode())
        assertEquals("auth_required", reply["error"]!!.jsonObject["data"]!!.jsonObject["agentosCode"]!!.jsonPrimitive.content)
        assertEquals("pairing_required", reply.reason())
        assertNull(raw.next(), "closed; session/new was never answered")
        val s = gateway.stats()
        assertEquals(0, s.served)
        assertEquals(1L, s.handshakeFailures["pairing_required"])
        assertEquals(5, s.code!!.attemptsLeft, "a non-pairing message does not burn a code attempt")
    }

    @Test
    fun `a wrong code is refused and the ACP line right behind it is never read as ACP`() {
        start()
        gateway.setEnabled(true)
        val code = gateway.newPairingCode()
        val wrong = ((code.code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        val raw = Raw()
        raw.send("""{"jsonrpc":"2.0","id":0,"method":"_org.agentos/pair","params":{"code":"$wrong"}}""", initialize)
        val reply = raw.next()!!
        assertEquals("invalid_code", reply.reason())
        assertEquals(0, reply["id"]!!.jsonPrimitive.int)
        assertNull(raw.next())
        assertEquals(4, gateway.stats().code!!.attemptsLeft)
    }

    @Test
    fun `the code works once, the token reconnects, the connection speaks plain ACP as the desktop caller`() {
        start()
        val (raw, token) = paired()
        assertEquals(1, gateway.pairing.pairings().size)
        assertEquals("test-laptop", gateway.pairing.pairings().single().label)
        raw.send(initialize)
        val init = raw.response(1)
        assertEquals("agentos", init["result"]!!.jsonObject["agentInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        // 同一个配对码不能再用
        val code = raw.seen.first()
        assertNotNull(code["result"])
        val again = Raw().pair(code = "000000")
        assertEquals("invalid_code", again.reason())
        // 令牌重连，不发新令牌
        val second = Raw()
        val ok = second.pair(token = token)
        assertNull(ok["result"]!!.jsonObject["token"])
        second.send(initialize)
        second.response(1)
        assertEquals(2, gateway.connections().size)
        assertTrue(gateway.connections().all { it.peerUid == 2000 })
        raw.close()
        second.close()
    }

    @Test
    fun `a write tool from the desktop is confirmed on the phone, the client is never asked`() {
        start()
        rt.host.consent.answer = { ConsentDecision.Deny(ConsentDecision.DenyReason.USER) }
        val (raw, _) = paired()
        raw.send(initialize)
        raw.response(1)
        raw.send(newSession)
        val sessionId = raw.response(2)["result"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content
        val prompt = Json.encodeToString(
            JsonObject.serializer(),
            Json.parseToJsonElement(
                """{"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"sessionId":"$sessionId","prompt":[{"type":"text","text":${
                    JsonPrimitive("""{"fake":{"tools":[{"name":"send_note","arguments":{"text":"hi"}}]}}""")
                }}]}}""",
            ).jsonObject,
        )
        raw.send(prompt)
        val done = raw.response(3)
        assertEquals("end_turn", done["result"]!!.jsonObject["stopReason"]!!.jsonPrimitive.content)
        val request = rt.host.consent.requests.single()
        assertEquals(CallerKind.DESKTOP, request.caller.kind)
        assertEquals("desktop", request.caller.ownerKey)
        assertEquals(2000, request.caller.uid)
        assertTrue(raw.seen.none { it["method"]?.jsonPrimitive?.content == "session/request_permission" })
        val failed = raw.seen.mapNotNull { it["params"]?.jsonObject?.get("update")?.jsonObject }
            .last { it["sessionUpdate"]!!.jsonPrimitive.content == "tool_call_update" }
        assertEquals("failed", failed["status"]!!.jsonPrimitive.content)
        val text = failed["content"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.startsWith("[agentos:tool_denied]"), text)
        raw.close()
    }

    @Test
    fun `turning the switch off closes live desktop connections and voids their tokens`() {
        start()
        val (raw, token) = paired()
        raw.send(initialize)
        raw.response(1)
        gateway.setEnabled(false)
        assertNull(raw.next(), "closed by the switch")
        runBlocking { withTimeout(5_000) { while (gateway.connections().isNotEmpty()) delay(10) } }
        gateway.setEnabled(true)
        assertEquals("invalid_token", Raw().pair(token = token).reason())
    }

    @Test
    fun `revoking a pairing closes its connections`() {
        start()
        val (raw, _) = paired()
        val id = gateway.pairing.pairings().single().id
        assertTrue(gateway.revoke(id))
        assertNull(raw.next())
    }

    @Test
    fun `connections from other uids are closed before anything is read`() {
        start(peerUid = 10_123)
        gateway.setEnabled(true)
        val code = gateway.newPairingCode()
        val raw = Raw()
        raw.send("""{"jsonrpc":"2.0","id":0,"method":"_org.agentos/pair","params":{"code":"${code.code}"}}""")
        assertNull(raw.next())
        assertEquals(1, gateway.stats().rejectedPeer)
        assertEquals(5, gateway.stats().code!!.attemptsLeft, "the code was never tried")
        assertTrue(gateway.pairing.pairings().isEmpty())
    }

    @Test
    fun `a silent connection is closed after the handshake timeout`() {
        start(DesktopGatewayConfig(handshakeTimeoutMillis = 300))
        gateway.setEnabled(true)
        val raw = Raw()
        val t0 = System.nanoTime()
        assertNull(raw.next())
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000)
        assertEquals(1, gateway.stats().handshakeTimeouts)
    }

    @Test
    fun `an oversized handshake line or ACP line closes the connection`() {
        start()
        gateway.setEnabled(true)
        gateway.newPairingCode()
        val raw = Raw()
        raw.send("x".repeat(DesktopHandshake.MAX_LINE_CHARS + 1))
        assertNull(raw.next(), "no response to an oversized handshake line")
        assertEquals(1L, gateway.stats().handshakeFailures["line_too_long"])

        val (paired, _) = paired()
        paired.send("""{"jsonrpc":"2.0","method":"x","params":{"p":"${"y".repeat(65_536)}"}}""")
        assertNull(paired.next())
        runBlocking { withTimeout(5_000) { while (gateway.stats().recentCloses.isEmpty()) delay(10) } }
        assertEquals("line_too_long", gateway.stats().recentCloses.single().reason)
    }

    @Test
    fun `too many connections are refused with busy`() {
        start(DesktopGatewayConfig(maxConnections = 1))
        val (raw, token) = paired()
        val extra = Raw()
        val reply = extra.next()!!
        assertEquals(RpcCodes.BUSY, reply.errorCode())
        assertNull(extra.next())
        raw.close()
        runBlocking { withTimeout(5_000) { while (gateway.connections().isNotEmpty()) delay(10) } }
        assertNotNull(Raw().pair(token = token)["result"])
    }
}
