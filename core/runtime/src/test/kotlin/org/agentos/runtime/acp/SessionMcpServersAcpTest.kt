@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.runtime.acp

import com.agentclientprotocol.model.HttpHeader
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.RuntimeConfig
import org.agentos.runtime.ports.ConsentDecision
import org.agentos.runtime.ports.SessionMcpRejected
import org.agentos.runtime.ports.SessionToolPort
import org.agentos.runtime.ports.ToolRef
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.agentos.runtime.testing.FakeHostPort
import org.agentos.runtime.testing.FakeScripts
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ACP 的 `mcpServers`（会话级自带工具，core/protocol/acp-mapping.md 第 4c 节）：只收 Streamable HTTP，工具只对这个会话可见，
 * 永远要用户确认且没有“始终允许”，凭据（URL、头）只在内存里、不进事件、日志和线上其他地方。
 * 这里的 `SessionToolPort` 是假实现（FakeSessionToolPort）；真实的 Streamable HTTP 客户端在 core:extensions，有自己的测试。
 */
class SessionMcpServersAcpTest {
    private val alarm = ToolSource("alarm", "main", "alarm_create")

    private val secretToken = "Bearer SECRET-TOKEN-0f3a9c"
    private val secretUrl = "https://tools.private-host.example/mcp/v1"

    private fun http(name: String = "tools", url: String = secretUrl) = McpServer.Http(name, url, listOf(HttpHeader("Authorization", secretToken)))

    private fun test(
        host: FakeHostPort = FakeHostPort(),
        config: RuntimeConfig = TestRuntime.config(),
        block: suspend CoroutineScope.(AcpPair, TestRuntime) -> Unit,
    ): Unit = runBlocking {
        val rt = TestRuntime(FakeScripts.directives(), host = host, config = config)
        rt.host.tools.registerSimple("alarm_create", ToolRisk.WRITE, alarm) { ToolResult.text("alarm set") }
        rt.start()
        val pair = AcpPair(rt)
        try {
            withTimeout(60_000) { block(this, pair, rt) }
        } finally {
            pair.close()
            rt.stop()
            rt.host.deleteDatabase()
        }
    }

    private fun AcpPair.errorCode(): String = lastError()["data"]!!.jsonObject["agentosCode"]!!.jsonPrimitive.content

    private fun call(vararg names: String) = buildJsonObject {
        put("fake", buildJsonObject { put("tools", buildJsonArray { names.forEach { n -> add(buildJsonObject { put("name", n) }) } }) })
    }.toString()

    private fun TestRuntime.offered(): List<String> = core!!.configs.last().tools.map { it.name }.sorted()

    // ------------------------------------------------------------------ 能力

    @Test
    fun `initialize declares http servers only, and nothing when the build has no session tools`() {
        test { pair, _ ->
            val info = pair.initialize()
            assertTrue(info.capabilities.mcpCapabilities.http)
            assertFalse(info.capabilities.mcpCapabilities.sse)
        }
        test(host = FakeHostPort(sessionTools = SessionToolPort.NONE)) { pair, rt ->
            val info = pair.initialize()
            assertFalse(info.capabilities.mcpCapabilities.http)
            assertFailsWith<Exception> { pair.newSession(mcpServers = listOf(http())) }
            assertEquals("unsupported", pair.errorCode())
            assertEquals(emptyList(), rt.engine.listSessions(TestRuntime.APP), "and no session was left behind")
            // an empty list is fine on such a build
            assertEquals(StopReason.END_TURN, pair.prompt(pair.newSession(), "hi").response().stopReason)
        }
    }

    // ------------------------------------------------------------------ 挂载

    @Test
    fun `an http server is attached to the session of the caller, exactly as given`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession(mcpServers = listOf(http()))
        val attached = rt.host.fakeSessionTools.attached.getValue(s.sessionId.value)
        assertEquals(TestRuntime.APP.uid, attached.owner.uid)
        assertEquals(TestRuntime.APP.ownerKey, attached.owner.ownerKey)
        val server = attached.servers.single()
        assertEquals("tools", server.name)
        assertEquals(secretUrl, server.url)
        assertEquals(listOf("Authorization" to secretToken), server.headers)
    }

    @Test
    fun `its tools are offered to this session only`() = test { pair, rt ->
        pair.initialize()
        val withTools = pair.newSession(mcpServers = listOf(http()))
        val without = pair.newSession()
        pair.prompt(withTools, "go")
        assertEquals(listOf("alarm_create", "ses__tools__ping"), rt.offered())
        pair.prompt(without, "go")
        assertEquals(listOf("alarm_create"), rt.offered(), "another session of the same app does not see them")

        // and a model that calls one anyway, from the wrong session, gets 'not available'
        rt.host.fakeSessionTools.invocations.clear()
        pair.prompt(without, call("ses__tools__ping"))
        assertEquals(emptyList(), rt.host.fakeSessionTools.invocations)
    }

    @Test
    fun `a session tool is confirmed every time and never offers 'always allow'`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession(mcpServers = listOf(http()))
        val events = pair.prompt(s, call("ses__tools__ping"))
        assertEquals(StopReason.END_TURN, events.response().stopReason)

        val ask = rt.host.consent.requests.single()
        assertEquals("ses__tools__ping", ask.toolName)
        assertEquals(ToolRisk.WRITE, ask.risk, "never a read tool")
        assertFalse(ask.alwaysAllowOffered, "the key of an always-allow would be a name the app chose")
        assertNull(ask.source, "it belongs to no plugin of the user")
        assertEquals(TestRuntime.APP.uid, ask.caller.uid)

        val invocation = rt.host.fakeSessionTools.invocations.single()
        assertEquals(s.sessionId.value, invocation.sessionId)
        val done = events.updates().filterIsInstance<SessionUpdate.ToolCallUpdate>().last()
        assertEquals(ToolCallStatus.COMPLETED, done.status)
        assertTrue("pong:tools" in done.content.toString())
    }

    @Test
    fun `a declined session tool call is not executed`() = test { pair, rt ->
        rt.host.consent.answer = { ConsentDecision.Deny(ConsentDecision.DenyReason.USER) }
        pair.initialize()
        val s = pair.newSession(mcpServers = listOf(http()))
        pair.prompt(s, call("ses__tools__ping"))
        assertEquals(emptyList(), rt.host.fakeSessionTools.invocations)
        assertEquals(1, rt.host.consent.requests.size)
    }

    // ------------------------------------------------------------------ 拒绝

    @Test
    fun `stdio and sse servers are refused as unsupported, without echoing them, and no session is left`() = test { pair, rt ->
        pair.initialize()
        val stdio = McpServer.Stdio("fs", "/data/local/tmp/secret-binary", listOf("--token=XYZZY"), emptyList())
        assertFailsWith<Exception> { pair.newSession(mcpServers = listOf(stdio)) }
        assertEquals("unsupported", pair.errorCode())
        val stdioMessage = pair.lastError()["message"]!!.jsonPrimitive.content
        assertTrue("secret-binary" !in stdioMessage && "XYZZY" !in stdioMessage, stdioMessage)

        val sse = McpServer.Sse("events", secretUrl, listOf(HttpHeader("Authorization", secretToken)))
        assertFailsWith<Exception> { pair.newSession(mcpServers = listOf(http("ok"), sse)) }
        assertEquals("unsupported", pair.errorCode())
        val sseMessage = pair.lastError()["message"]!!.jsonPrimitive.content
        assertTrue("mcpServers[1]" in sseMessage && "private-host" !in sseMessage && "SECRET" !in sseMessage, sseMessage)

        assertEquals(emptyList(), rt.engine.listSessions(TestRuntime.APP))
        assertTrue(rt.host.fakeSessionTools.attached.isEmpty(), "the valid first server was not attached either")
    }

    @Test
    fun `a list the port rejects fails the request with invalid_params and leaves no session behind`() = test { pair, rt ->
        pair.initialize()
        rt.host.fakeSessionTools.reject = SessionMcpRejected("bad_url", "mcpServers[0]: the url must use https")
        assertFailsWith<Exception> { pair.newSession(mcpServers = listOf(http())) }
        assertEquals("invalid_params", pair.errorCode())
        assertTrue("bad_url" in pair.lastError()["message"]!!.jsonPrimitive.content)
        assertEquals(emptyList(), rt.engine.listSessions(TestRuntime.APP), "the empty session that was created for it is gone again")

        rt.host.fakeSessionTools.reject = SessionMcpRejected("quota", "too many servers for this app")
        assertFailsWith<Exception> { pair.newSession(mcpServers = listOf(http())) }
        assertEquals("quota_exceeded", pair.errorCode())
        assertEquals(emptyList(), rt.engine.listSessions(TestRuntime.APP))
    }

    @Test
    fun `a server that cannot be reached does not fail the session, and the client is told which one`() = test { pair, rt ->
        pair.initialize()
        rt.host.fakeSessionTools.unreachable = setOf("down")
        val s = pair.newSession(mcpServers = listOf(http("up"), http("down", "https://down.private-host.example/mcp")))
        // the outcome of each server is a separate session_info_update notification, not part of the session/new response: on a slow machine
        // it can arrive after newSession() has returned (seen on a CI runner), so wait for it instead of reading the list at once
        rt.until { pair.infoMetas().any { "mcpServers" in it } }
        val servers = pair.infoMetas().last { "mcpServers" in it }["mcpServers"]!!.jsonArray
        val byName = servers.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals("true", byName.getValue("up").jsonObject["connected"]!!.jsonPrimitive.content)
        assertEquals("1", byName.getValue("up").jsonObject["toolCount"]!!.jsonPrimitive.content)
        assertEquals("false", byName.getValue("down").jsonObject["connected"]!!.jsonPrimitive.content)
        assertEquals("connect_failed", byName.getValue("down").jsonObject["reason"]!!.jsonPrimitive.content)

        pair.prompt(s, "go")
        assertEquals(listOf("alarm_create", "ses__up__ping"), rt.offered(), "only the reachable server's tools")
    }

    // ------------------------------------------------------------------ 可见性与模式

    @Test
    fun `read only and chat hide session tools, a toolScope does not`() = test { pair, rt ->
        pair.initialize()
        // a scope names plugin tools; the app's own tools are not part of what a scope narrows
        val s = pair.newSession(toolScopeMeta(ToolRef("alarm", "alarm_create")), listOf(http()))
        pair.prompt(s, "go")
        assertEquals(listOf("alarm_create", "ses__tools__ping"), rt.offered())

        s.setMode(SessionModeId("read_only"))
        pair.prompt(s, "go")
        assertEquals(emptyList(), rt.offered(), "both are write level")

        s.setMode(SessionModeId("chat"))
        pair.prompt(s, "go")
        assertEquals(emptyList(), rt.offered())

        s.setMode(SessionModeId("default"))
        pair.prompt(s, "go")
        assertEquals(listOf("alarm_create", "ses__tools__ping"), rt.offered())
    }

    // ------------------------------------------------------------------ 凭据不外泄

    @Test
    fun `the url and the header values appear nowhere but in memory`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession(mcpServers = listOf(http()))
        pair.prompt(s, call("ses__tools__ping"))
        val second = AcpPair(rt)
        second.initialize()
        second.loadSession(s.sessionId.value, mcpServers = listOf(http()))

        val everything = buildString {
            append(rt.engine.readEvents(s.sessionId.value).joinToString("\n") { it.payload.toString() })
            append(pair.agentLines.toList().joinToString("\n"))
            append(second.agentLines.toList().joinToString("\n"))
            append(rt.host.log.lines.toList().joinToString("\n"))
            append(rt.host.consent.requests.toList().joinToString("\n") { it.toString() })
        }
        assertFalse("SECRET-TOKEN" in everything, "header value")
        assertFalse("private-host" in everything, "url")
        assertTrue("ses__tools__ping" in everything, "sanity: the tool call itself is there")
        second.close()
    }

    // ------------------------------------------------------------------ load / resume / fork / close

    @Test
    fun `load and resume replace the servers of the session with the ones they bring - an empty list takes them away`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession(mcpServers = listOf(http("first")))
        val id = s.sessionId.value
        val second = AcpPair(rt)
        second.initialize()

        val loaded = second.loadSession(id, mcpServers = listOf(http("second")))
        assertEquals(listOf("second"), rt.host.fakeSessionTools.attached.getValue(id).servers.map { it.name })
        second.prompt(loaded, "go")
        assertEquals(listOf("alarm_create", "ses__second__ping"), rt.offered())

        second.resumeSession(id, mcpServers = emptyList())
        assertEquals(emptyList(), rt.host.fakeSessionTools.attached.getValue(id).servers, "resume without servers = none")
        assertEquals(emptyList(), rt.host.fakeSessionTools.tools(id))
        second.close()
    }

    @Test
    fun `a fork brings its own servers and does not inherit the originals`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession(mcpServers = listOf(http("original")))
        val plain = pair.forkSession(s.sessionId.value)
        assertNull(rt.host.fakeSessionTools.attached[plain.sessionId.value], "their urls and headers are the original's secret")
        val own = pair.forkSession(s.sessionId.value, mcpServers = listOf(http("mine")))
        assertEquals(listOf("mine"), rt.host.fakeSessionTools.attached.getValue(own.sessionId.value).servers.map { it.name })
    }

    @Test
    fun `close and delete release the servers`() = test { pair, rt ->
        pair.initialize()
        val a = pair.newSession(mcpServers = listOf(http("a")))
        val b = pair.newSession(mcpServers = listOf(http("b")))
        a.close()
        assertTrue(a.sessionId.value in rt.host.fakeSessionTools.detached)
        pair.client.deleteSession(com.agentclientprotocol.model.SessionId(b.sessionId.value))
        assertTrue(b.sessionId.value in rt.host.fakeSessionTools.detached)
        assertTrue(rt.host.fakeSessionTools.attached.isEmpty())
    }

    @Suppress("unused")
    private fun JsonArray.names() = map { it.jsonObject["name"]!!.jsonPrimitive.content }
}
