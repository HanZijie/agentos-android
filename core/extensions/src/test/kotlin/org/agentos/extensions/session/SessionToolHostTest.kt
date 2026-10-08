package org.agentos.extensions.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.extensions.host.ConnectFailed
import org.agentos.extensions.host.ConnectionLost
import org.agentos.extensions.host.McpCallResult
import org.agentos.extensions.host.McpContentPart
import org.agentos.extensions.host.NotSent
import org.agentos.extensions.host.ServerError
import org.agentos.extensions.host.TimedOut
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.CatalogTool
import org.agentos.runtime.ports.ContentPart
import org.agentos.runtime.ports.SessionMcpRejected
import org.agentos.runtime.ports.SessionMcpResult
import org.agentos.runtime.ports.SessionMcpServer
import org.agentos.runtime.ports.ToolInvocation
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolRisk
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionToolHostTest {
    private val app1 = CallerIdentity(10001, CallerKind.APP, "App One", "org.example.one")
    private val app2 = CallerIdentity(10002, CallerKind.APP, "App Two", "org.example.two")

    private class Rig(scope: TestScope, config: SessionToolHostConfig = SessionToolHostConfig()) {
        val connector = FakeConnector()
        val host = SessionToolHost(scope.backgroundScope, config, connector, nowMillis = { scope.testScheduler.currentTime })
    }

    private fun TestScope.rig(config: SessionToolHostConfig = SessionToolHostConfig()) = Rig(this, config)

    private fun invocation(session: String, name: String, timeout: Long = 60_000, caller: CallerIdentity = app1, args: JsonObject = buildJsonObject { put("a", 1) }) =
        ToolInvocation(session, "tsk_1", "call_1", name, args, caller, timeout)

    private fun ToolInvocationResult.text(): String =
        (this as ToolInvocationResult.Completed).result.content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }

    private fun Rig.alpha(tools: List<org.agentos.extensions.host.McpToolInfo> = listOf(mcpTool("t1"), mcpTool("t2"))) = connector.server("alpha", tools)

    private fun SessionMcpResult.ok() = connected && reason == null

    // ------------------------------------------------------------------ attach / tools

    @Test
    fun `attach connects the servers and lists their tools`() = runTest {
        val r = rig()
        r.alpha()
        r.connector.server("beta", listOf(mcpTool("only")))
        val results = r.host.attach("ses_1", app1, listOf(sessionServer("alpha"), sessionServer("beta")))
        assertEquals(listOf(SessionMcpResult("alpha", true, 2), SessionMcpResult("beta", true, 1)), results)
        assertEquals(listOf("ses__alpha__t1", "ses__alpha__t2", "ses__beta__only"), r.host.tools("ses_1").map { it.name })
    }

    @Test
    fun `catalog tools carry provider, no source, description, schema and title`() = runTest {
        val r = rig()
        val schema = objectSchema("q")
        r.connector.server("alpha", listOf(mcpTool("search", description = "Find things", title = "Search", schema = schema)))
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        val tool = r.host.tools("ses_1").single()
        assertEquals("session:alpha", tool.provider)
        assertNull(tool.source)
        assertEquals("Find things", tool.description)
        assertEquals("Search", tool.title)
        assertEquals(schema, tool.inputSchema)
    }

    @Test
    fun `a session without servers has no tools`() = runTest {
        val r = rig()
        assertEquals(emptyList(), r.host.tools("nope"))
        r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        assertEquals(emptyList(), r.host.tools("ses_2"))
    }

    @Test
    fun `the connector receives the url and headers`() = runTest {
        val r = rig()
        r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha", "https://alpha.example.com/x", listOf("Authorization" to "Bearer t"))))
        val seen = r.connector.connected.single()
        assertEquals("https://alpha.example.com/x", seen.url)
        assertEquals(listOf("Authorization" to "Bearer t"), seen.headers)
    }

    @Test
    fun `attach again replaces the previous batch and closes its links`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        val beta = r.connector.server("beta", listOf(mcpTool("b1")))
        val gamma = r.connector.server("gamma", listOf(mcpTool("g1")))
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha"), sessionServer("beta")))
        val results = r.host.attach("ses_1", app1, listOf(sessionServer("gamma")))
        assertEquals(listOf(SessionMcpResult("gamma", true, 1)), results)
        assertEquals(listOf("ses__gamma__g1"), r.host.tools("ses_1").map { it.name })
        assertTrue(alpha.links.single().closed.isCompleted)
        assertTrue(beta.links.single().closed.isCompleted)
        assertFalse(gamma.links.single().closed.isCompleted)
    }

    @Test
    fun `attaching an empty list clears the previous batch`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        assertEquals(emptyList(), r.host.attach("ses_1", app1, emptyList()))
        assertEquals(emptyList(), r.host.tools("ses_1"))
        assertTrue(alpha.links.single().closed.isCompleted)
    }

    @Test
    fun `a rejected batch connects nothing and leaves the previous batch alone`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        val before = r.connector.connected.size
        val e = assertFailsWith<SessionMcpRejected> {
            r.host.attach("ses_1", app1, listOf(sessionServer("alpha"), sessionServer("beta", "https://10.0.0.1/mcp")))
        }
        assertEquals("url_ip", e.reason)
        assertEquals(before, r.connector.connected.size, "nothing was connected")
        assertEquals(listOf("ses__alpha__t1", "ses__alpha__t2"), r.host.tools("ses_1").map { it.name })
        assertFalse(alpha.links.single().closed.isCompleted)
    }

    @Test
    fun `the default config refuses plain http and loopback`() = runTest {
        val host = SessionToolHost(backgroundScope)
        for ((url, reason) in listOf("http://127.0.0.1:8080/mcp" to "url_scheme", "https://localhost/mcp" to "url_host", "https://169.254.169.254/" to "url_ip", "http://example.com/mcp" to "url_scheme")) {
            assertEquals(reason, assertFailsWith<SessionMcpRejected> { host.attach("ses_1", app1, listOf(sessionServer("a", url))) }.reason)
        }
        assertEquals(emptyList(), host.tools("ses_1"))
    }

    @Test
    fun `the per session server limit follows the config`() = runTest {
        val r = rig(SessionToolHostConfig(maxServersPerSession = 2))
        val e = assertFailsWith<SessionMcpRejected> { r.host.attach("ses_1", app1, listOf(sessionServer("a"), sessionServer("b"), sessionServer("c"))) }
        assertEquals("too_many_servers", e.reason)
    }

    // ------------------------------------------------------------------ 配额

    @Test
    fun `servers per owner are limited across sessions`() = runTest {
        val r = rig(SessionToolHostConfig(maxServersPerOwner = 8))
        for (n in listOf("a", "b", "c", "d", "e", "f", "g", "h", "i")) r.connector.server(n, listOf(mcpTool("t")))
        val four1 = listOf("a", "b", "c", "d").map { sessionServer(it) }
        val four2 = listOf("e", "f", "g", "h").map { sessionServer(it) }
        r.host.attach("ses_1", app1, four1)
        r.host.attach("ses_2", app1, four2)
        val e = assertFailsWith<SessionMcpRejected> { r.host.attach("ses_3", app1, listOf(sessionServer("i"))) }
        assertEquals("quota", e.reason)
        assertEquals(emptyList(), r.host.tools("ses_3"))
        assertEquals(4, r.host.tools("ses_1").size, "existing sessions are untouched")
        // replacing a session does not count its own old batch twice
        r.host.attach("ses_1", app1, four1)
        // another caller has its own allowance
        assertEquals(listOf(SessionMcpResult("i", true, 1)), r.host.attach("ses_9", app2, listOf(sessionServer("i"))))
        // detaching frees the allowance
        r.host.detach("ses_2")
        assertEquals(listOf(SessionMcpResult("i", true, 1)), r.host.attach("ses_3", app1, listOf(sessionServer("i"))))
    }

    @Test
    fun `a quota failure leaves the sessions own previous batch in place`() = runTest {
        val r = rig(SessionToolHostConfig(maxServersPerOwner = 3, maxServersPerSession = 4))
        for (n in listOf("a", "b", "c", "d")) r.connector.server(n, listOf(mcpTool("t")))
        r.host.attach("ses_1", app1, listOf(sessionServer("a")))
        val e = assertFailsWith<SessionMcpRejected> { r.host.attach("ses_1", app1, listOf("a", "b", "c", "d").map { sessionServer(it) }) }
        assertEquals("quota", e.reason)
        assertEquals(listOf("ses__a__t"), r.host.tools("ses_1").map { it.name })
        assertFalse(r.connector.servers.getValue("a").links.single().closed.isCompleted)
    }

    @Test
    fun `total servers are limited across callers`() = runTest {
        val r = rig(SessionToolHostConfig(maxServersTotal = 5))
        for (n in listOf("a", "b", "c", "d")) r.connector.server(n, listOf(mcpTool("t")))
        r.host.attach("ses_1", app1, listOf(sessionServer("a"), sessionServer("b"), sessionServer("c")))
        assertEquals("quota", assertFailsWith<SessionMcpRejected> { r.host.attach("ses_2", app2, listOf(sessionServer("a"), sessionServer("b"), sessionServer("d"))) }.reason)
        r.host.attach("ses_2", app2, listOf(sessionServer("a"), sessionServer("b")))
    }

    @Test
    fun `quota messages do not echo caller values`() = runTest {
        val r = rig(SessionToolHostConfig(maxServersTotal = 1))
        val e = assertFailsWith<SessionMcpRejected> { r.host.attach("ses_1", app1, listOf(sessionServer("NAME-MARKER-1"), sessionServer("NAME-MARKER-2"))) }
        assertFalse(e.message!!.contains("MARKER"))
    }

    // ------------------------------------------------------------------ detach

    @Test
    fun `detach closes the links, empties the catalog and is idempotent`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        r.host.detach("ses_1")
        r.host.detach("ses_1")
        r.host.detach("never-attached")
        assertTrue(alpha.links.single().closed.isCompleted)
        assertEquals(emptyList(), r.host.tools("ses_1"))
        assertIs<ToolInvocationResult.NotDispatched>(r.host.invoke(invocation("ses_1", "ses__alpha__t1")))
        // the allowance is free again
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        assertEquals(2, r.host.tools("ses_1").size)
    }

    @Test
    fun `sessions are isolated from each other`() = runTest {
        val r = rig()
        r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        r.host.attach("ses_2", app2, listOf(sessionServer("alpha")))
        r.host.detach("ses_1")
        assertEquals(2, r.host.tools("ses_2").size)
        assertIs<ToolInvocationResult.Completed>(r.host.invoke(invocation("ses_2", "ses__alpha__t1", caller = app2)))
        assertIs<ToolInvocationResult.NotDispatched>(r.host.invoke(invocation("ses_1", "ses__alpha__t1")))
        assertIs<ToolInvocationResult.NotDispatched>(r.host.invoke(invocation("ses_3", "ses__alpha__t1")))
    }

    @Test
    fun `detach while connecting cancels the attempt and leaks no link`() = runTest {
        val r = rig()
        val slow = r.connector.server("slow", listOf(mcpTool("t")))
        slow.listDelayMillis = 5_000
        val attach = async { r.host.attach("ses_1", app1, listOf(sessionServer("slow"))) }
        runCurrent()
        assertEquals(1, slow.connects.get(), "connected, now listing")
        r.host.detach("ses_1")
        val results = attach.await()
        assertEquals(listOf(SessionMcpResult("slow", false, 0, "detached")), results)
        runCurrent()
        assertTrue(slow.links.single().closed.isCompleted, "the half set up link was closed")
        assertEquals(emptyList(), r.host.tools("ses_1"))
    }

    @Test
    fun `replacing while connecting does not adopt the superseded link`() = runTest {
        val r = rig()
        val slow = r.connector.server("slow", listOf(mcpTool("old")))
        slow.listDelayMillis = 5_000
        val fast = r.connector.server("fast", listOf(mcpTool("new")))
        val first = async { r.host.attach("ses_1", app1, listOf(sessionServer("slow"))) }
        runCurrent()
        r.host.attach("ses_1", app1, listOf(sessionServer("fast")))
        assertEquals(false, first.await().single().connected)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("ses__fast__new"), r.host.tools("ses_1").map { it.name })
        assertTrue(slow.links.all { it.closed.isCompleted })
        assertFalse(fast.links.single().closed.isCompleted)
    }

    @Test
    fun `close releases every session`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        r.host.attach("ses_2", app2, listOf(sessionServer("alpha")))
        r.host.close()
        assertTrue(alpha.links.all { it.closed.isCompleted })
        assertEquals(emptyList(), r.host.tools("ses_1"))
    }

    // ------------------------------------------------------------------ 部分失败

    @Test
    fun `a failing server does not affect the others and attach does not throw`() = runTest {
        val r = rig()
        r.alpha()
        r.connector.server("down").connectFailure = ConnectFailed("refused")
        r.connector.server("odd").connectFailure = IllegalStateException("weird")
        r.connector.server("nolist").listFailure = ConnectionLost("reset")
        r.connector.server("badrpc").listFailure = ServerError(-32601, "method_not_found")
        val results = r.host.attach("ses_1", app1, listOf(sessionServer("down"), sessionServer("alpha"), sessionServer("odd"), sessionServer("nolist")))
        assertEquals(
            listOf(
                SessionMcpResult("down", false, 0, "connect_failed"),
                SessionMcpResult("alpha", true, 2),
                SessionMcpResult("odd", false, 0, "connect_failed"),
                SessionMcpResult("nolist", false, 0, "list_failed"),
            ),
            results,
        )
        assertEquals(listOf("ses__alpha__t1", "ses__alpha__t2"), r.host.tools("ses_1").map { it.name })
        assertTrue(r.connector.servers.getValue("nolist").links.single().closed.isCompleted, "a link whose listing failed is closed")
    }

    @Test
    fun `an unknown server in the connector is a connect failure`() = runTest {
        val r = rig()
        assertEquals(listOf(SessionMcpResult("ghost", false, 0, "connect_failed")), r.host.attach("ses_1", app1, listOf(sessionServer("ghost"))))
    }

    @Test
    fun `a connect that takes too long is a timeout`() = runTest {
        val r = rig(SessionToolHostConfig(connectTimeoutMillis = 500))
        r.connector.server("slow").connectDelayMillis = 2_000
        r.alpha()
        val results = r.host.attach("ses_1", app1, listOf(sessionServer("slow"), sessionServer("alpha")))
        assertEquals(listOf(SessionMcpResult("slow", false, 0, "timeout"), SessionMcpResult("alpha", true, 2)), results)
        assertEquals(0, r.connector.servers.getValue("slow").connects.get())
        assertTrue(currentTime in 500..1_000, "connecting runs concurrently: $currentTime")
    }

    @Test
    fun `a listing that takes too long is a timeout and closes the link`() = runTest {
        val r = rig(SessionToolHostConfig(listTimeoutMillis = 500))
        val slow = r.connector.server("slow", listOf(mcpTool("t")))
        slow.listDelayMillis = 2_000
        assertEquals(listOf(SessionMcpResult("slow", false, 0, "timeout")), r.host.attach("ses_1", app1, listOf(sessionServer("slow"))))
        assertTrue(slow.links.single().closed.isCompleted)
    }

    @Test
    fun `a link that reports timed out while listing is a timeout`() = runTest {
        val r = rig()
        r.connector.server("t").listFailure = TimedOut(10_000)
        assertEquals("timeout", r.host.attach("ses_1", app1, listOf(sessionServer("t"))).single().reason)
    }

    @Test
    fun `attach as a whole is bounded by attachTimeoutMillis`() = runTest {
        val r = rig(SessionToolHostConfig(attachTimeoutMillis = 1_000, connectTimeoutMillis = 30_000))
        r.connector.server("slow").connectDelayMillis = 20_000
        r.alpha()
        val results = r.host.attach("ses_1", app1, listOf(sessionServer("slow"), sessionServer("alpha")))
        assertEquals(listOf(SessionMcpResult("slow", false, 0, "timeout"), SessionMcpResult("alpha", true, 2)), results)
        assertTrue(currentTime in 1_000..1_100, "attach took $currentTime ms")
        advanceTimeBy(30_000)
        assertEquals(0, r.connector.servers.getValue("slow").connects.get(), "the unfinished attempt was cancelled")
    }

    // ------------------------------------------------------------------ 工具过滤

    @Test
    fun `more tools than the limit rejects the server`() = runTest {
        val r = rig(SessionToolHostConfig(maxToolsPerServer = 3))
        val four = r.connector.server("four", (1..4).map { mcpTool("t$it") })
        r.connector.server("three", (1..3).map { mcpTool("t$it") })
        val results = r.host.attach("ses_1", app1, listOf(sessionServer("four"), sessionServer("three")))
        assertEquals(listOf(SessionMcpResult("four", false, 0, "too_many_tools"), SessionMcpResult("three", true, 3)), results)
        assertTrue(four.links.single().closed.isCompleted)
        assertEquals(3, r.host.tools("ses_1").size)
    }

    @Test
    fun `tools that are dropped do not count towards the limit`() = runTest {
        val r = rig(SessionToolHostConfig(maxToolsPerServer = 2))
        val schemaless = mcpTool("noschema", schema = JsonObject(emptyMap()))
        r.connector.server("s", listOf(mcpTool("a"), schemaless, mcpTool("b"), mcpTool("a"), schemaless))
        assertEquals(SessionMcpResult("s", true, 2), r.host.attach("ses_1", app1, listOf(sessionServer("s"))).single())
    }

    @Test
    fun `tools whose schema is not an object schema or is too large are dropped`() = runTest {
        val r = rig(SessionToolHostConfig(maxSchemaChars = 300))
        val huge = buildJsonObject { put("type", "object"); put("description", "x".repeat(400)) }
        r.connector.server(
            "s",
            listOf(
                mcpTool("good"),
                mcpTool("string", schema = buildJsonObject { put("type", "string") }),
                mcpTool("untyped", schema = buildJsonObject { put("properties", JsonObject(emptyMap())) }),
                mcpTool("empty", schema = JsonObject(emptyMap())),
                mcpTool("huge", schema = huge),
                mcpTool("array", schema = buildJsonObject { put("type", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("object")) }) }),
            ),
        )
        val result = r.host.attach("ses_1", app1, listOf(sessionServer("s"))).single()
        assertEquals(1, result.toolCount)
        assertEquals(listOf("ses__s__good"), r.host.tools("ses_1").map { it.name })
    }

    @Test
    fun `duplicate tool names within a server keep the first`() = runTest {
        val r = rig()
        r.connector.server("s", listOf(mcpTool("dup", description = "first"), mcpTool("dup", description = "second")))
        r.host.attach("ses_1", app1, listOf(sessionServer("s")))
        assertEquals("first", r.host.tools("ses_1").single().description)
    }

    @Test
    fun `tools with empty or very long original names are dropped`() = runTest {
        val r = rig()
        r.connector.server("s", listOf(mcpTool(""), mcpTool("n".repeat(129)), mcpTool("n".repeat(128))))
        assertEquals(1, r.host.attach("ses_1", app1, listOf(sessionServer("s"))).single().toolCount)
    }

    // ------------------------------------------------------------------ 命名

    @Test
    fun `names use only the model alphabet and map back to the original tool name`() = runTest {
        val r = rig()
        val s = r.connector.server("my.server", listOf(mcpTool("a.b"), mcpTool("note create")))
        r.host.attach("ses_1", app1, listOf(sessionServer("my.server")))
        val names = r.host.tools("ses_1").map { it.name }
        assertEquals(listOf("ses__my_server__a_b", "ses__my_server__note_create"), names)
        r.host.invoke(invocation("ses_1", "ses__my_server__a_b"))
        r.host.invoke(invocation("ses_1", "ses__my_server__note_create"))
        assertEquals(listOf("a.b", "note create"), s.calls.map { it.name }, "the server gets the original names")
    }

    @Test
    fun `colliding names are all suffixed and each routes to its own tool`() = runTest {
        val r = rig()
        val s = r.connector.server("s", listOf(mcpTool("a.b"), mcpTool("a_b"), mcpTool("a b"), mcpTool("plain")))
        r.host.attach("ses_1", app1, listOf(sessionServer("s")))
        val names = r.host.tools("ses_1").map { it.name }
        assertEquals(4, names.toSet().size)
        assertTrue("ses__s__plain" in names)
        val suffixed = names.filter { it != "ses__s__plain" }
        assertTrue(suffixed.all { it.matches(Regex("ses__s__a_b_[0-9a-f]{6}")) }, suffixed.toString())
        for (name in suffixed) r.host.invoke(invocation("ses_1", name))
        assertEquals(setOf("a.b", "a_b", "a b"), s.calls.map { it.name }.toSet())
    }

    @Test
    fun `servers whose sanitized names collide get distinct tool names`() = runTest {
        val r = rig()
        r.connector.server("x.y", listOf(mcpTool("t")))
        r.connector.server("x_y", listOf(mcpTool("t")))
        r.host.attach("ses_1", app1, listOf(sessionServer("x.y"), sessionServer("x_y")))
        assertEquals(2, r.host.tools("ses_1").map { it.name }.toSet().size)
    }

    @Test
    fun `very long names are cut to 64 characters with a hash`() = runTest {
        val r = rig()
        val s = r.connector.server("s", listOf(mcpTool("t".repeat(120)), mcpTool("t".repeat(121))))
        r.host.attach("ses_1", app1, listOf(sessionServer("s")))
        val tools = r.host.tools("ses_1")
        assertEquals(2, tools.map { it.name }.toSet().size)
        for (t in tools) {
            assertEquals(64, t.name.length)
            assertTrue(t.name.matches(Regex("ses__s__t+_[0-9a-f]{6}")), t.name)
        }
        r.host.invoke(invocation("ses_1", tools.first().name))
        assertEquals(1, s.calls.size)
        assertTrue(s.calls.single().name.startsWith("ttt"))
    }

    @Test
    fun `names never start with mcp__ and never equal read_skill`() = runTest {
        val r = rig()
        r.connector.server("mcp", listOf(mcpTool("x"), mcpTool("mcp__x"), mcpTool("read_skill")))
        r.connector.server("read_skill", listOf(mcpTool("read_skill")))
        r.host.attach("ses_1", app1, listOf(sessionServer("mcp"), sessionServer("read_skill")))
        val tools = r.host.tools("ses_1")
        assertEquals(4, tools.size)
        for (t in tools) {
            assertTrue(t.name.startsWith("ses__"), t.name)
            assertFalse(t.name.startsWith("mcp__"))
            assertTrue(t.name != "read_skill")
            assertTrue(t.name.length <= 64)
        }
    }

    @Test
    fun `names do not change when another server drops out`() = runTest {
        val r = rig()
        val a = r.connector.server("a", listOf(mcpTool("t.x"), mcpTool("t_x")))
        r.connector.server("b", listOf(mcpTool("t")))
        r.host.attach("ses_1", app1, listOf(sessionServer("a"), sessionServer("b")))
        val before = r.host.tools("ses_1").filter { it.provider == "session:b" }.map { it.name }
        a.kill()
        assertEquals(before, r.host.tools("ses_1").map { it.name }, "only the dropped server's tools disappear")
    }

    // ------------------------------------------------------------------ 风险

    @Test
    fun `risk defaults to write, destructive raises to high and read only never lowers`() = runTest {
        val r = rig()
        r.connector.server(
            "s",
            listOf(
                mcpTool("plain"),
                mcpTool("reader", readOnly = true),
                mcpTool("deleter", destructive = true),
                mcpTool("liar", readOnly = true, destructive = true),
                mcpTool("safe", readOnly = false, destructive = false),
            ),
        )
        r.host.attach("ses_1", app1, listOf(sessionServer("s")))
        val risks = r.host.tools("ses_1").associate { it.name.removePrefix("ses__s__") to it.risk }
        assertEquals(ToolRisk.WRITE, risks["plain"])
        assertEquals(ToolRisk.WRITE, risks["reader"], "readOnlyHint does not lower the risk")
        assertEquals(ToolRisk.HIGH, risks["deleter"])
        assertEquals(ToolRisk.HIGH, risks["liar"])
        assertEquals(ToolRisk.WRITE, risks["safe"])
        assertTrue(risks.values.none { it == ToolRisk.READ })
    }

    // ------------------------------------------------------------------ 第三方文字

    @Test
    fun `descriptions and titles are cleaned and truncated`() = runTest {
        val r = rig(SessionToolHostConfig(maxDescriptionChars = 40, maxTitleChars = 10))
        r.connector.server(
            "s",
            listOf(
                mcpTool("lines", description = "first line\nsecond\r\n\tthird\u0000 end", title = "Ti\ntle"),
                mcpTool("bidi", description = "pay\u202E evil\u200B text", title = "A\u2028B"),
                mcpTool("long", description = "x".repeat(100), title = "t".repeat(50)),
                mcpTool("emoji", description = "a".repeat(39) + "\uD83D\uDE00" + "b", title = null),
                mcpTool("notitle", description = null, title = "Only a title"),
                mcpTool("nothing", description = null, title = null),
                mcpTool("blank", description = "\n\n", title = "\n"),
            ),
        )
        r.host.attach("ses_1", app1, listOf(sessionServer("s")))
        val byName = r.host.tools("ses_1").associateBy { it.name.removePrefix("ses__s__") }
        assertEquals("first line second third end", byName.getValue("lines").description)
        assertEquals("Ti tle", byName.getValue("lines").title)
        assertEquals("pay evil text", byName.getValue("bidi").description)
        assertEquals("A B", byName.getValue("bidi").title)
        assertEquals(40, byName.getValue("long").description.length)
        assertEquals(10, byName.getValue("long").title!!.length)
        assertEquals(39, byName.getValue("emoji").description.length, "a surrogate pair is not cut in half")
        assertEquals("Only a title", byName.getValue("notitle").description)
        assertEquals("", byName.getValue("nothing").description)
        assertNull(byName.getValue("nothing").title)
        assertNull(byName.getValue("blank").title, "a title that is only whitespace is no title")
    }

    // ------------------------------------------------------------------ prepare

    @Test
    fun `prepare reconnects a dropped server and refreshes its tools`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        runCurrent()
        alpha.kill("peer closed")
        assertEquals(emptyList(), r.host.tools("ses_1"), "a dropped server's tools are not offered")
        assertIs<ToolInvocationResult.NotDispatched>(r.host.invoke(invocation("ses_1", "ses__alpha__t1")))
        alpha.tools = listOf(mcpTool("t1"), mcpTool("t3"))
        r.host.prepare("ses_1", 5_000)
        assertEquals(2, alpha.connects.get())
        assertEquals(listOf("ses__alpha__t1", "ses__alpha__t3"), r.host.tools("ses_1").map { it.name })
        assertIs<ToolInvocationResult.Completed>(r.host.invoke(invocation("ses_1", "ses__alpha__t3")))
    }

    @Test
    fun `prepare does nothing for servers that are connected`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        r.host.prepare("ses_1", 5_000)
        r.host.prepare("unknown", 5_000)
        assertEquals(1, alpha.connects.get())
        assertEquals(1, alpha.listCalls.get())
    }

    @Test
    fun `prepare retries a failed server only after the backoff`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        alpha.connectFailure = ConnectFailed("down")
        assertEquals("connect_failed", r.host.attach("ses_1", app1, listOf(sessionServer("alpha"))).single().reason)
        alpha.connectFailure = null
        r.host.prepare("ses_1", 5_000)
        assertEquals(0, alpha.connects.get(), "inside the backoff")
        advanceTimeBy(29_999)
        r.host.prepare("ses_1", 5_000)
        assertEquals(0, alpha.connects.get(), "one millisecond early")
        advanceTimeBy(1)
        r.host.prepare("ses_1", 5_000)
        assertEquals(1, alpha.connects.get())
        assertEquals(2, r.host.tools("ses_1").size)
    }

    @Test
    fun `a failed retry starts a new backoff`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        alpha.connectFailure = ConnectFailed("down")
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        advanceTimeBy(30_000)
        r.host.prepare("ses_1", 5_000)
        advanceTimeBy(10_000)
        alpha.connectFailure = null
        r.host.prepare("ses_1", 5_000)
        assertEquals(0, alpha.connects.get())
        advanceTimeBy(20_000)
        r.host.prepare("ses_1", 5_000)
        assertEquals(1, alpha.connects.get())
    }

    @Test
    fun `prepare returns at its limit and the reconnect finishes in the background`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        alpha.connectFailure = ConnectFailed("down")
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        alpha.connectFailure = null
        alpha.connectDelayMillis = 5_000
        advanceTimeBy(30_000)
        val start = currentTime
        r.host.prepare("ses_1", 1_000)
        assertEquals(1_000, currentTime - start)
        assertEquals(emptyList(), r.host.tools("ses_1"))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, r.host.tools("ses_1").size)
    }

    @Test
    fun `a session expired link reconnects at once`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        runCurrent()
        alpha.kill("session_expired")
        runCurrent()
        r.host.prepare("ses_1", 5_000)
        assertEquals(2, alpha.connects.get(), "a dropped link is not a failed connection: no backoff")
    }

    @Test
    fun `list_changed refreshes the tools in the background`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        runCurrent()
        alpha.tools = listOf(mcpTool("t1"), mcpTool("t2"), mcpTool("t9"))
        alpha.toolsChanged()
        runCurrent()
        assertEquals(listOf("ses__alpha__t1", "ses__alpha__t2", "ses__alpha__t9"), r.host.tools("ses_1").map { it.name })
        assertIs<ToolInvocationResult.Completed>(r.host.invoke(invocation("ses_1", "ses__alpha__t9")))
    }

    @Test
    fun `a failing refresh keeps the previous tools`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        runCurrent()
        alpha.listFailure = ConnectionLost("reset")
        alpha.toolsChanged()
        runCurrent()
        assertEquals(2, r.host.tools("ses_1").size)
    }

    @Test
    fun `a refresh that returns too many tools drops the server`() = runTest {
        val r = rig(SessionToolHostConfig(maxToolsPerServer = 2))
        val alpha = r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        runCurrent()
        alpha.tools = (1..5).map { mcpTool("n$it") }
        alpha.toolsChanged()
        runCurrent()
        assertEquals(emptyList(), r.host.tools("ses_1"))
        assertTrue(alpha.links.single().closed.isCompleted)
    }

    // ------------------------------------------------------------------ invoke

    @Test
    fun `invoke returns text, image, other and structured content`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        alpha.handler = {
            McpCallResult(
                listOf(
                    McpContentPart.Text("hello"),
                    McpContentPart.Image("QUJD", "image/png"),
                    McpContentPart.Other("audio", buildJsonObject { put("data", "AAAA") }),
                    McpContentPart.Other("we ird\nty<pe>" + "x".repeat(100), JsonObject(emptyMap())),
                ),
                structuredContent = buildJsonObject { put("n", 1) },
                isError = true,
            )
        }
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        val result = (r.host.invoke(invocation("ses_1", "ses__alpha__t1")) as ToolInvocationResult.Completed).result
        assertTrue(result.isError)
        assertEquals(ContentPart.Text("hello"), result.content[0])
        assertEquals(ContentPart.Image("QUJD", "image/png"), result.content[1])
        assertEquals(ContentPart.Text("[agentos: omitted a audio content part]"), result.content[2])
        val weird = (result.content[3] as ContentPart.Text).text
        assertTrue(weird.startsWith("[agentos: omitted a ") && weird.endsWith(" content part]") && '\n' !in weird, weird)
        assertEquals(buildJsonObject { put("n", 1) }, result.details)
    }

    @Test
    fun `structured content stands in for text when there is none`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        alpha.handler = { McpCallResult(emptyList(), structuredContent = buildJsonObject { put("k", "v") }) }
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        assertEquals("""{"k":"v"}""", r.host.invoke(invocation("ses_1", "ses__alpha__t1")).text())
        alpha.handler = { McpCallResult(emptyList()) }
        assertEquals("", r.host.invoke(invocation("ses_1", "ses__alpha__t1")).text())
    }

    @Test
    fun `invoke passes the arguments and the timeout to the link`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        val args = buildJsonObject { put("path", "/x") }
        r.host.invoke(invocation("ses_1", "ses__alpha__t2", timeout = 1234, args = args))
        val call = alpha.calls.single()
        assertEquals("t2", call.name)
        assertEquals(args, call.arguments)
        assertEquals(1234, call.timeoutMillis)
    }

    @Test
    fun `an unknown tool, session or a closed link is not dispatched`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        for (inv in listOf(invocation("ses_1", "ses__alpha__nope"), invocation("ses_1", "mcp__alpha__t1"), invocation("ses_1", "read_skill"), invocation("ses_9", "ses__alpha__t1"))) {
            val result = r.host.invoke(inv)
            assertEquals(ErrorCode.TOOL_NOT_IN_CATALOG, assertIs<ToolInvocationResult.NotDispatched>(result).error.code)
        }
        alpha.kill()
        assertEquals(ErrorCode.TOOL_NOT_IN_CATALOG, assertIs<ToolInvocationResult.NotDispatched>(r.host.invoke(invocation("ses_1", "ses__alpha__t1"))).error.code)
        assertEquals(0, alpha.calls.size)
    }

    @Test
    fun `not sent and connect failures are not dispatched`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        for (failure in listOf(NotSent("link is closed"), ConnectFailed("refused"))) {
            alpha.failNextCall = failure
            val result = assertIs<ToolInvocationResult.NotDispatched>(r.host.invoke(invocation("ses_1", "ses__alpha__t1")))
            assertEquals(ErrorCode.TOOL_UNAVAILABLE, result.error.code)
        }
    }

    @Test
    fun `connection lost and timeouts are unknown and not replayed`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        alpha.failNextCall = ConnectionLost("reset")
        assertEquals(ErrorCode.TOOL_RESULT_UNKNOWN, assertIs<ToolInvocationResult.Unknown>(r.host.invoke(invocation("ses_1", "ses__alpha__t1"))).error.code)
        alpha.failNextCall = TimedOut(777)
        val timeout = assertIs<ToolInvocationResult.Unknown>(r.host.invoke(invocation("ses_1", "ses__alpha__t1")))
        assertEquals(ErrorCode.TOOL_TIMEOUT, timeout.error.code)
        assertTrue("777" in timeout.error.message)
        assertEquals(0, alpha.calls.size, "the host never retries")
    }

    @Test
    fun `a server error is a completed error result with only a short code`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        alpha.failNextCall = ServerError(-32602, "invalid_params")
        val result = (r.host.invoke(invocation("ses_1", "ses__alpha__t1")) as ToolInvocationResult.Completed).result
        assertTrue(result.isError)
        assertEquals("[mcp error -32602] invalid_params", (result.content.single() as ContentPart.Text).text)
        alpha.failNextCall = ServerError(401, "Server said: bad token abc123")
        val other = (r.host.invoke(invocation("ses_1", "ses__alpha__t1")) as ToolInvocationResult.Completed).result
        assertEquals("[mcp error 401]", (other.content.single() as ContentPart.Text).text, "detail that is not a short code is not passed on")
    }

    @Test
    fun `the link closing during a call leaves the result unknown`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        alpha.handler = {
            delay(60_000)
            McpCallResult(listOf(McpContentPart.Text("late")))
        }
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        val call = async { r.host.invoke(invocation("ses_1", "ses__alpha__t1")) }
        runCurrent()
        alpha.kill("peer closed")
        runCurrent()
        assertEquals(ErrorCode.TOOL_RESULT_UNKNOWN, assertIs<ToolInvocationResult.Unknown>(call.await()).error.code)
    }

    @Test
    fun `detach during a call leaves the result unknown`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        alpha.handler = {
            delay(60_000)
            McpCallResult(emptyList())
        }
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        val call = async { r.host.invoke(invocation("ses_1", "ses__alpha__t1")) }
        runCurrent()
        r.host.detach("ses_1")
        runCurrent()
        assertIs<ToolInvocationResult.Unknown>(call.await())
    }

    @Test
    fun `cancelling an invoke cancels the call on the link`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        alpha.handler = { awaitCancellation() }
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        val call = launch { r.host.invoke(invocation("ses_1", "ses__alpha__t1")) }
        runCurrent()
        call.cancelAndJoin()
        assertTrue(call.isCancelled)
        assertEquals(1, alpha.cancelledCalls.get())
    }

    @Test
    fun `long results are truncated with a notice and images are kept`() = runTest {
        val r = rig()
        val alpha = r.alpha()
        alpha.handler = { McpCallResult(listOf(McpContentPart.Text("a".repeat(30_000)), McpContentPart.Image("QUJD", "image/png"), McpContentPart.Text("b".repeat(30_000)))) }
        r.host.attach("ses_1", app1, listOf(sessionServer("alpha")))
        val result = (r.host.invoke(invocation("ses_1", "ses__alpha__t1")) as ToolInvocationResult.Completed).result
        val texts = result.content.filterIsInstance<ContentPart.Text>()
        assertTrue(texts.sumOf { it.text.length } <= 32_768)
        assertTrue(texts.last().text.contains("[agentos:tool_result_too_large]"))
        assertEquals(1, result.content.filterIsInstance<ContentPart.Image>().size)
        // a result that fits is untouched
        alpha.handler = { McpCallResult(listOf(McpContentPart.Text("a".repeat(32_768)))) }
        assertEquals(32_768, r.host.invoke(invocation("ses_1", "ses__alpha__t1")).text().length)
    }

    // ------------------------------------------------------------------ 秘密不外泄

    @Test
    fun `secrets never appear in results, catalog, messages or toString`() = runTest {
        val urlSecret = "URLSECRET-1b2c"
        val headerSecret = "HDRSECRET-9d8e"
        val bodySecret = "BODYSECRET-77f0"
        val r = rig()
        val ok = r.connector.server("ok", listOf(mcpTool("t", description = "plain")))
        r.connector.server("refused").connectFailure = ConnectFailed("cannot reach https://x.example.com/$urlSecret with $headerSecret")
        r.connector.server("weird").connectFailure = IllegalStateException("$urlSecret $headerSecret")
        r.connector.server("nolist").listFailure = ConnectionLost("reset on $urlSecret $headerSecret $bodySecret")
        val servers = listOf("ok", "refused", "weird", "nolist").map {
            sessionServer(it, "https://$it.example.com/$urlSecret?token=$urlSecret", listOf("Authorization" to "Bearer $headerSecret", "X-Key" to headerSecret))
        }
        val outputs = ArrayList<String>()
        val results = r.host.attach("ses_1", app1, servers)
        outputs += results.map { it.toString() }
        outputs += servers.map { it.toString() }
        outputs += r.host.tools("ses_1").map { it.toString() }
        outputs += r.host.toString()
        outputs += r.host.tools("ses_1").flatMap { listOf(it.name, it.description, it.provider, it.title.orEmpty(), it.inputSchema.toString()) }

        val failures = listOf(
            ConnectionLost("lost $urlSecret $headerSecret"), NotSent("not sent $urlSecret"), ConnectFailed("failed $headerSecret"),
            TimedOut(5), ServerError(-32000, "$bodySecret $urlSecret"), ServerError(500, "http_500"),
        )
        for (failure in failures) {
            ok.failNextCall = failure
            outputs += r.host.invoke(invocation("ses_1", "ses__ok__t")).toString()
        }
        outputs += r.host.invoke(invocation("ses_1", "ses__ok__nope")).toString()
        outputs += r.host.invoke(invocation("ses_9", "ses__ok__t")).toString()
        ok.kill()
        outputs += r.host.invoke(invocation("ses_1", "ses__ok__t")).toString()

        // rejection messages
        for (bad in listOf(
            SessionMcpServer("ok", "ftp://$urlSecret.example.com/$urlSecret", listOf("Authorization" to headerSecret)),
            SessionMcpServer("ok", "https://a.example.com/", listOf("Host" to headerSecret)),
            SessionMcpServer("ok", "https://a.example.com/", listOf("X-A" to "$headerSecret\r\nX-Evil: 1")),
            SessionMcpServer("ok", "https://user:$headerSecret@a.example.com/$urlSecret"),
            SessionMcpServer("ok", "http://$urlSecret.example.com/"),
        )) {
            val e = assertFailsWith<SessionMcpRejected> { r.host.attach("ses_2", app1, listOf(bad)) }
            outputs += e.message!!
            outputs += e.reason
            outputs += e.toString()
            outputs += e.stackTraceToString()
        }

        assertTrue(r.connector.connected.size >= servers.size, "the connector did get the real values")
        assertTrue(r.connector.connected.all { it.url.contains(urlSecret) && it.headers.any { h -> h.second.contains(headerSecret) } })
        for (text in outputs) {
            for (secret in listOf(urlSecret, headerSecret, bodySecret, "Bearer", "token=")) {
                assertFalse(text.contains(secret), "'$secret' leaked into: $text")
            }
        }
        assertTrue(outputs.size > 20)
    }

    @Test
    fun `failure results carry short codes only`() = runTest {
        val r = rig()
        r.connector.server("a").connectFailure = ConnectFailed("some detail")
        r.connector.server("b").listFailure = ConnectionLost("some detail")
        r.connector.server("c").connectDelayMillis = 100_000
        val results = r.host.attach("ses_1", app1, listOf("a", "b", "c").map { sessionServer(it) })
        for (result in results) assertTrue(result.reason!!.matches(Regex("[a-z_]{1,24}")), result.reason)
    }

    // ------------------------------------------------------------------ 配置

    @Test
    fun `the config rejects nonsense`() {
        assertFailsWith<IllegalArgumentException> { SessionToolHostConfig(maxServersPerSession = 0) }
        assertFailsWith<IllegalArgumentException> { SessionToolHostConfig(maxToolsPerServer = 0) }
        assertFailsWith<IllegalArgumentException> { SessionToolHostConfig(maxResultChars = 10) }
        val defaults = SessionToolHostConfig()
        assertEquals(4, defaults.maxServersPerSession)
        assertEquals(64, defaults.maxToolsPerServer)
        assertEquals(8, defaults.maxServersPerOwner)
        assertEquals(64, defaults.maxServersTotal)
        assertEquals(10_000, defaults.connectTimeoutMillis)
        assertEquals(10_000, defaults.listTimeoutMillis)
        assertEquals(15_000, defaults.attachTimeoutMillis)
        assertEquals(32_768, defaults.maxResultChars)
        assertEquals(1_024, defaults.maxDescriptionChars)
        assertEquals(128, defaults.maxTitleChars)
        assertEquals(16_384, defaults.maxSchemaChars)
        assertFalse(defaults.allowLoopbackHttp)
    }

    @Test
    fun `the headers the connector receives are a copy`() = runTest {
        val r = rig()
        r.alpha()
        val headers = mutableListOf("X-A" to "1")
        r.host.attach("ses_1", app1, listOf(SessionMcpServer("alpha", "https://alpha.example.com/", headers)))
        headers += "X-B" to "2"
        r.host.prepare("ses_1", 1_000)
        assertEquals(listOf("X-A" to "1"), r.connector.connected.single().headers)
    }

    // ------------------------------------------------------------------ 并发

    @Test
    fun `concurrent attach, invoke, prepare and detach on many sessions neither deadlock nor leak links`() {
        val connector = FakeConnector()
        val names = listOf("a", "b", "c", "d")
        for (n in names) connector.server(n, listOf(mcpTool("t1"), mcpTool("t2"))).also { it.connectDelayMillis = 1 }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val host = SessionToolHost(scope, SessionToolHostConfig(maxServersPerOwner = 10_000, maxServersTotal = 10_000, connectTimeoutMillis = 2_000, attachTimeoutMillis = 4_000), connector)
        val bad = AtomicInteger()
        runBlocking {
            withTimeout(90_000) {
                (0 until 16).map { worker ->
                    launch(Dispatchers.Default) {
                        val random = java.util.Random(worker.toLong())
                        repeat(120) {
                            val session = "ses_${random.nextInt(5)}"
                            when (random.nextInt(6)) {
                                0, 1 -> host.attach(session, app1, names.shuffled(random).take(1 + random.nextInt(3)).map { sessionServer(it) })
                                2 -> host.detach(session)
                                3 -> host.prepare(session, 50)
                                else -> {
                                    val tool = host.tools(session).firstOrNull()
                                    if (tool != null) {
                                        when (host.invoke(invocation(session, tool.name))) {
                                            is ToolInvocationResult.Completed, is ToolInvocationResult.NotDispatched, is ToolInvocationResult.Unknown -> Unit
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.joinAll()
                for (n in 0 until 5) host.detach("ses_$n")
                val deadline = System.nanoTime() + 10_000_000_000L
                while (connector.servers.values.any { s -> s.links.any { !it.closed.isCompleted } } && System.nanoTime() < deadline) delay(20)
            }
        }
        assertEquals(0, bad.get())
        for (n in 0 until 5) assertEquals(emptyList<CatalogTool>(), host.tools("ses_$n"))
        for (server in connector.servers.values) assertTrue(server.links.all { it.closed.isCompleted }, "every link was closed")
        assertTrue(connector.servers.values.sumOf { it.links.size } > 0)
        scope.cancel()
    }
}
