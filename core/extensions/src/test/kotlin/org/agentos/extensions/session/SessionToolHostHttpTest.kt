package org.agentos.extensions.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.agentos.extensions.session.McpTestServer.Companion.error
import org.agentos.extensions.session.McpTestServer.Companion.json
import org.agentos.extensions.session.McpTestServer.Companion.textContent
import org.agentos.extensions.session.McpTestServer.Companion.toolJson
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.ContentPart
import org.agentos.runtime.ports.SessionMcpResult
import org.agentos.runtime.ports.ToolInvocation
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolRisk
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [SessionToolHost] 接真实的 [StreamableHttpLink]（MockWebServer，回环地址上的 http）。 */
class SessionToolHostHttpTest {
    private val app = CallerIdentity(10001, CallerKind.APP, "App One", "org.example.one")
    private val servers = ArrayList<McpTestServer>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val host = SessionToolHost(scope, SessionToolHostConfig(allowLoopbackHttp = true, connectTimeoutMillis = 3_000, listTimeoutMillis = 3_000, attachTimeoutMillis = 8_000))

    private fun newServer() = McpTestServer().also {
        servers += it
        it.tools = buildJsonArray {
            add(toolJson("echo", "Echoes", schema = objectSchema("text")))
            add(toolJson("rm", "Deletes", annotations = buildJsonObject { put("destructiveHint", true) }))
            add(toolJson("peek", "Reads", annotations = buildJsonObject { put("readOnlyHint", true) }))
        }
    }

    @AfterTest
    fun tearDown() {
        host.close()
        scope.cancel()
        servers.forEach { it.close() }
    }

    private fun blocking(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(60_000) { block() } }

    private fun invocation(name: String, session: String = "ses_1") =
        ToolInvocation(session, "tsk_1", "call_1", name, buildJsonObject { put("text", "hi") }, app, 5_000)

    @Test
    fun `attach, list, invoke and detach over real http`() = blocking {
        val good = newServer()
        good.callHandler = { rpc -> json(rpc.id, textContent("echo:" + ((rpc.params["arguments"] as kotlinx.serialization.json.JsonObject)["text"] as JsonPrimitive).content)) }
        val dead = newServer().also { it.close() }
        val results = host.attach("ses_1", app, listOf(sessionServer("good", good.url, listOf("Authorization" to "Bearer abc")), sessionServer("dead", dead.url)))
        assertEquals(SessionMcpResult("good", true, 3), results[0])
        assertEquals(SessionMcpResult("dead", false, 0, "connect_failed"), results[1])

        val tools = host.tools("ses_1")
        assertEquals(listOf("ses__good__echo", "ses__good__peek", "ses__good__rm"), tools.map { it.name })
        assertEquals(ToolRisk.WRITE, tools.first { it.name.endsWith("peek") }.risk)
        assertEquals(ToolRisk.HIGH, tools.first { it.name.endsWith("rm") }.risk)

        val result = host.invoke(invocation("ses__good__echo"))
        assertEquals("echo:hi", ((result as ToolInvocationResult.Completed).result.content.single() as ContentPart.Text).text)
        assertTrue(good.log.all { it.header("Authorization") == "Bearer abc" }, "the caller's header went on every request")

        host.detach("ses_1")
        good.awaitSeen { it.httpMethod == "DELETE" }
        assertIs<ToolInvocationResult.NotDispatched>(host.invoke(invocation("ses__good__echo")))
        assertEquals(emptyList(), host.tools("ses_1"))
    }

    @Test
    fun `an expired session is dropped and prepare opens a new one`() = blocking {
        val server = newServer()
        var sessions = 0
        server.hook = { rpc ->
            if (rpc.method == "initialize") {
                server.sessionId = "sess-${++sessions}"
            }
            null
        }
        host.attach("ses_1", app, listOf(sessionServer("s", server.url)))
        server.callHandler = { MockResponse().setResponseCode(404) }
        val lost = host.invoke(invocation("ses__s__echo"))
        assertEquals(ErrorCode.TOOL_RESULT_UNKNOWN, assertIs<ToolInvocationResult.Unknown>(lost).error.code)
        assertEquals(emptyList(), host.tools("ses_1").map { it.name }, "the dropped server's tools are hidden")

        server.callHandler = { json(it.id, textContent("back")) }
        host.prepare("ses_1", 5_000)
        assertEquals(3, host.tools("ses_1").size)
        assertEquals("sess-2", server.requests("tools/list").last().header("Mcp-Session-Id"))
        val result = host.invoke(invocation("ses__s__echo"))
        assertEquals("back", ((result as ToolInvocationResult.Completed).result.content.single() as ContentPart.Text).text)
    }

    @Test
    fun `invoke outcomes over real http`() = blocking {
        val server = newServer()
        host.attach("ses_1", app, listOf(sessionServer("s", server.url)))

        server.callHandler = { error(it.id, -32602, "private details") }
        val failed = (host.invoke(invocation("ses__s__echo")) as ToolInvocationResult.Completed).result
        assertTrue(failed.isError)
        assertEquals("[mcp error -32602] invalid_params", (failed.content.single() as ContentPart.Text).text)

        server.callHandler = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        assertEquals(ErrorCode.TOOL_RESULT_UNKNOWN, assertIs<ToolInvocationResult.Unknown>(host.invoke(invocation("ses__s__echo"))).error.code)

        server.callHandler = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val timeout = host.invoke(ToolInvocation("ses_1", "tsk_1", "call_2", "ses__s__echo", buildJsonObject { }, app, 300))
        assertEquals(ErrorCode.TOOL_TIMEOUT, assertIs<ToolInvocationResult.Unknown>(timeout).error.code)

        server.callHandler = { MockResponse().setResponseCode(302).addHeader("Location", "http://127.0.0.1:1/") }
        val redirected = (host.invoke(invocation("ses__s__echo")) as ToolInvocationResult.Completed).result
        assertTrue(redirected.isError)
        assertEquals("[mcp error 302] http_302", (redirected.content.single() as ContentPart.Text).text)
    }

    @Test
    fun `a real attach is bounded and does not throw when the server hangs`() = blocking {
        val hung = newServer()
        hung.hook = { if (it.method == "initialize") MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else null }
        val fine = newServer()
        val started = System.nanoTime()
        val results = host.attach("ses_1", app, listOf(sessionServer("hung", hung.url), sessionServer("fine", fine.url)))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 7_000)
        assertFalse(results[0].connected)
        assertTrue(results[0].reason == "timeout" || results[0].reason == "connect_failed", results[0].reason)
        assertTrue(results[1].connected)
    }

    @Test
    fun `parallel invokes over one link`() = blocking {
        val server = newServer()
        val counter = AtomicInteger()
        server.callHandler = { json(it.id, textContent("n${counter.incrementAndGet()}")) }
        host.attach("ses_1", app, listOf(sessionServer("s", server.url)))
        val results = (1..10).map { async(Dispatchers.IO) { host.invoke(invocation("ses__s__echo")) } }.awaitAll()
        assertTrue(results.all { it is ToolInvocationResult.Completed })
        assertEquals(10, counter.get())
    }

    @Test
    fun `the default constructor wiring works`() = blocking {
        // SessionToolHost(scope, SessionToolHostConfig(), connector = default): the default connector enforces the default policy
        val production = SessionToolHost(scope, SessionToolHostConfig(), connector = SessionMcpConnector.streamableHttp())
        val e = org.junit.Assert.assertThrows(org.agentos.runtime.ports.SessionMcpRejected::class.java) {
            runBlocking { production.attach("ses_1", app, listOf(sessionServer("s", newServer().url))) }
        }
        assertEquals("url_scheme", e.reason)
        production.close()
    }
}
