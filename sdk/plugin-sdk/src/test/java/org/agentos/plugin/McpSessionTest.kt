package org.agentos.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.agentos.plugin.internal.McpPipe
import org.agentos.plugin.internal.McpServerSession
import org.agentos.plugin.internal.SendResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** 内存里的一端：send 直接进对端的 inbox。 */
private class MemoryPipe(override val maxMessageChars: Int = 65_536) : McpPipe {
    lateinit var peer: MemoryPipe
    private val inbox = Channel<String>(Channel.UNLIMITED)
    @Volatile private var reason: String? = null
    val sent = CopyOnWriteArrayList<String>()

    override val incoming: ReceiveChannel<String> get() = inbox
    override fun consumed(length: Int) = Unit

    override fun send(text: String): SendResult {
        if (reason != null) return SendResult.CLOSED
        if (text.length > maxMessageChars) return SendResult.TOO_LARGE
        sent += text
        peer.inbox.trySend(text)
        return SendResult.OK
    }

    override fun close(reason: String) {
        if (this.reason != null) return
        this.reason = "local($reason)"
        inbox.close()
        peer.remoteClosed(reason)
    }

    fun remoteClosed(r: String) {
        if (reason != null) return
        reason = "remote($r)"
        inbox.close()
    }

    override fun closeReason(): String? = reason

    companion object {
        fun pair(max: Int = 65_536): Pair<MemoryPipe, MemoryPipe> {
            val a = MemoryPipe(max)
            val b = MemoryPipe(max)
            a.peer = b
            b.peer = a
            return a to b
        }
    }
}

class McpSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private val schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("a") { put("type", "integer") } }
    }

    private class Fixture(val client: McpBinderClient, val clientPipe: MemoryPipe, val serverPipe: MemoryPipe, val server: McpServerSession)

    private fun fixture(max: Int = 65_536, register: (McpToolRegistry) -> Unit): Fixture {
        val (c, s) = MemoryPipe.pair(max)
        var table = ToolTable.build(register)
        val server = McpServerSession(s, "test-server", "9.9", { table }, scope, Dispatchers.Default)
        server.start()
        return Fixture(McpBinderClient(c, scope, "test-client"), c, s, server)
    }

    @Test
    fun initialize_list_call() = runBlocking<Unit> {
        val f = fixture { r ->
            r.tool("add", "Adds a and b", schema, McpToolAnnotations(readOnlyHint = true, idempotentHint = true), title = "Add") { args ->
                val a = args["a"]?.jsonPrimitive?.int ?: return@tool McpToolResult.error("missing a")
                val b = args["b"]?.jsonPrimitive?.int ?: 0
                McpToolResult.json(buildJsonObject { put("sum", a + b) })
            }
            r.tool("hello", "Says hello", buildJsonObject { put("type", "object") }) { McpToolResult.text("hello") }
        }
        val info = f.client.initialize()
        assertEquals("test-server", info.name)
        assertEquals("9.9", info.version)
        assertEquals("2025-06-18", info.protocolVersion)
        assertTrue(info.toolsListChanged)

        val tools = f.client.listTools()
        assertEquals(listOf("add", "hello"), tools.map { it.name })
        assertEquals("Add", tools[0].title)
        assertEquals(true, tools[0].annotations.readOnlyHint)
        assertEquals(true, tools[0].annotations.idempotentHint)
        assertNull(tools[0].annotations.destructiveHint)
        assertEquals("object", (tools[0].inputSchema["type"] as JsonPrimitive).content)

        val r = f.client.callTool("add", buildJsonObject { put("a", 2); put("b", 3) })
        assertFalse(r.isError)
        assertEquals("{\"sum\":5}", r.text)
        assertEquals(5, r.structuredContent!!["sum"]!!.jsonPrimitive.int)

        val missing = f.client.callTool("add", JsonObject(emptyMap()))
        assertTrue(missing.isError)
        assertEquals("missing a", missing.text)

        assertEquals("hello", f.client.callTool("hello").text)
        assertNull(f.client.callTool("hello").structuredContent)
        f.client.ping()
    }

    @Test
    fun protocolErrors() = runBlocking<Unit> {
        val f = fixture { r -> r.tool("x", "x", buildJsonObject { put("type", "object") }) { McpToolResult.text("x") } }
        // initialize 之前不接受 tools/*
        try {
            f.client.listTools()
            fail("expected an error before initialize")
        } catch (e: McpRpcException) {
            assertEquals(-32600, e.code)
        }
        f.client.initialize()
        try {
            f.client.callTool("nope")
            fail("expected unknown tool")
        } catch (e: McpRpcException) {
            assertEquals(-32602, e.code)
            assertTrue(e.message!!.contains("Unknown tool"))
        }
    }

    @Test
    fun handlerExceptionBecomesErrorResult() = runBlocking<Unit> {
        val f = fixture { r -> r.tool("boom", "throws", buildJsonObject { put("type", "object") }) { throw IllegalStateException("database is locked") } }
        f.client.initialize()
        val r = f.client.callTool("boom")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("IllegalStateException") && r.text.contains("database is locked"))
        // 服务还活着
        f.client.ping()
    }

    @Test
    fun cancellationReachesTheHandler_andNoResponseIsSent() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        val f = fixture { r ->
            r.tool("slow", "never finishes", buildJsonObject { put("type", "object") }) {
                started.complete(Unit)
                try {
                    delay(60_000)
                    McpToolResult.text("late")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelled.set(true)
                    throw e
                }
            }
        }
        f.client.initialize()
        val call = async { f.client.callTool("slow") }
        withTimeout(5_000) { started.await() }
        call.cancel()
        withTimeout(5_000) { while (!cancelled.get()) delay(10) }
        delay(200)
        assertTrue("client sent notifications/cancelled", f.clientPipe.sent.any { it.contains("notifications/cancelled") })
        assertFalse("server must not answer a cancelled call", f.serverPipe.sent.any { it.contains("late") })
        f.client.ping()
    }

    @Test
    fun timeoutCancelsRemotely() = runBlocking<Unit> {
        val cancelled = AtomicBoolean(false)
        val f = fixture { r ->
            r.tool("slow", "slow", buildJsonObject { put("type", "object") }) {
                try {
                    delay(60_000)
                    McpToolResult.text("late")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelled.set(true)
                    throw e
                }
            }
        }
        f.client.initialize()
        try {
            f.client.callTool("slow", timeoutMillis = 300)
            fail("expected timeout")
        } catch (e: McpTimeoutException) {
            assertEquals("tools/call", e.method)
        }
        withTimeout(5_000) { while (!cancelled.get()) delay(10) }
    }

    @Test
    fun listChanged() = runBlocking<Unit> {
        val (c, s) = MemoryPipe.pair()
        var extra = false
        var table = ToolTable.build { r -> r.tool("a", "a", buildJsonObject { put("type", "object") }) { McpToolResult.text("a") } }
        val server = McpServerSession(s, "srv", "1", { table }, scope, Dispatchers.Default)
        server.start()
        val client = McpBinderClient(c, scope)
        client.initialize()
        assertEquals(1, client.listTools().size)
        val changed = async { client.toolsChanged.first() }
        delay(50)
        extra = true
        table = ToolTable.build { r ->
            r.tool("a", "a", buildJsonObject { put("type", "object") }) { McpToolResult.text("a") }
            if (extra) r.tool("b", "b", buildJsonObject { put("type", "object") }) { McpToolResult.text("b") }
        }
        server.notifyToolsChanged()
        withTimeout(5_000) { changed.await() }
        assertEquals(listOf("a", "b"), client.listTools().map { it.name })
    }

    @Test
    fun closeWhileCalling_isDispatchedUnknown_andLaterCallsAreNotDispatched() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val f = fixture { r ->
            r.tool("slow", "slow", buildJsonObject { put("type", "object") }) {
                started.complete(Unit)
                delay(60_000)
                McpToolResult.text("late")
            }
        }
        f.client.initialize()
        val call = async { runCatching { f.client.callTool("slow") } }
        withTimeout(5_000) { started.await() }
        f.serverPipe.close("server process died") // 对端关闭
        val e = call.await().exceptionOrNull()
        assertTrue("got $e", e is McpClosedException && e.dispatched)
        withTimeout(5_000) { f.client.closed.await() }
        assertFalse(f.client.isOpen)
        try {
            f.client.callTool("slow")
            fail("expected closed")
        } catch (e2: McpClosedException) {
            assertFalse(e2.dispatched)
        }
        withTimeout(5_000) { f.server.done.await() }
    }

    @Test
    fun tooLargeResultBecomesErrorResult_andTooLargeRequestIsNotSent() = runBlocking<Unit> {
        val f = fixture(max = 2_000) { r ->
            r.tool("big", "big", buildJsonObject { put("type", "object") }) { McpToolResult.text("x".repeat(5_000)) }
            r.tool("echo", "echo", buildJsonObject { put("type", "object") }) { McpToolResult.text("ok") }
        }
        f.client.initialize()
        val r = f.client.callTool("big")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("too large"))
        try {
            f.client.callTool("echo", buildJsonObject { put("blob", "y".repeat(3_000)) })
            fail("expected too large")
        } catch (e: McpRequestTooLargeException) {
            assertEquals(2_000, e.limit)
        }
        assertEquals("ok", f.client.callTool("echo").text)
    }

    @Test
    fun registryValidation() {
        val ok = buildJsonObject { put("type", "object") }
        fun expectFailure(block: (McpToolRegistry) -> Unit) {
            try {
                ToolTable.build(block)
                fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        expectFailure { it.tool("has space", "", ok) { McpToolResult.text("") } }
        expectFailure { it.tool("", "", ok) { McpToolResult.text("") } }
        expectFailure { it.tool("n".repeat(129), "", ok) { McpToolResult.text("") } }
        expectFailure { it.tool("arr", "", buildJsonObject { put("type", "array") }) { McpToolResult.text("") } }
        expectFailure {
            it.tool("dup", "", ok) { McpToolResult.text("") }
            it.tool("dup", "", ok) { McpToolResult.text("") }
        }
        assertEquals(2, ToolTable.build {
            it.tool("note_list", "", ok) { McpToolResult.text("") }
            it.tool("a.b-c_9", "", ok) { McpToolResult.text("") }
        }.tools.size)
    }

    @Test
    fun resultJsonShapes() {
        val obj = McpToolResult.json(buildJsonObject { put("id", "1") })
        assertEquals("{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"id\\\":\\\"1\\\"}\"}],\"structuredContent\":{\"id\":\"1\"}}", obj.toJson().toString())
        val arr = McpToolResult.json(kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(1)) })
        assertNull("arrays are not structuredContent", arr.structuredContent)
        assertEquals("[1]", arr.text)
        assertEquals("{\"content\":[{\"type\":\"text\",\"text\":\"no\"}],\"isError\":true}", McpToolResult.error("no").toJson().toString())
    }
}
