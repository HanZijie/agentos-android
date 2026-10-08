@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.runtime.acp

import com.agentclientprotocol.model.HttpHeader
import com.agentclientprotocol.model.McpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.agentos.runtime.testing.FakeScripts
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `sessionSetup`（core/protocol/acp-mapping.md 4d）：协商了的客户端，每次建立会话（new / load / resume / fork）的**最后**、响应之前，
 * 收到一条 `session_info_update`，`_meta."org.agentos".setup.replayed = N`，N 是前面重放的 `session/update` 条数。
 * SDK 靠它确定历史和服务器状态收齐了；通知在客户端是并发处理的，只看“响应回来了”不够。
 */
class SessionSetupMarkerAcpTest {
    private fun test(block: suspend CoroutineScope.(AcpPair, TestRuntime) -> Unit): Unit = runBlocking {
        val rt = TestRuntime(FakeScripts.directives(), config = TestRuntime.config())
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

    private fun AcpPair.setups(): List<JsonObject> = infoMetas().mapNotNull { it["setup"] as? JsonObject }

    private fun AcpPair.replayedCounts(): List<Int> = setups().map { it["replayed"]!!.jsonPrimitive.content.toInt() }

    @Test
    fun `a client that did not negotiate it never sees the marker, new or loaded`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        pair.prompt(s, "hi")
        val other = AcpPair(rt)
        other.initialize()
        other.loadSession(s.sessionId.value)
        other.resumeSession(s.sessionId.value)
        other.forkSession(s.sessionId.value)
        assertEquals(emptyList(), pair.setups())
        assertEquals(emptyList(), other.setups())
        assertEquals(emptyList(), other.infoMetas(), "and no other info update either, as before")
        other.close()
    }

    @Test
    fun `initialize declares the extension`() = test { pair, _ ->
        val info = pair.initialize()
        val ext = info._meta!!.jsonObject[ProfileExtensions.META_KEY]!!.jsonObject["extensions"]!!.jsonObject
        assertNotNull(ext[ProfileExtensions.SESSION_SETUP])
    }

    @Test
    fun `a new session and a fork get a marker with nothing replayed`() = test { pair, _ ->
        pair.initialize(listOf(ProfileExtensions.SESSION_SETUP))
        val s = pair.newSession()
        assertEquals(listOf(0), pair.replayedCounts())
        pair.forkSession(s.sessionId.value)
        assertEquals(listOf(0, 0), pair.replayedCounts())
    }

    @Test
    fun `resume gets the marker with zero, load gets exactly the number of updates it replayed before it`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        pair.prompt(s, "one")
        pair.prompt(s, "two")

        val c = AcpPair(rt)
        c.initialize(listOf(ProfileExtensions.SESSION_SETUP))
        c.resumeSession(s.sessionId.value)
        assertEquals(listOf(0), c.replayedCounts())
        val before = synchronized(c.agentLines) { c.agentLines.size }
        c.loadSession(s.sessionId.value)
        val n = c.replayedCounts().last()
        assertTrue(n >= 4, "two user messages and two answers at least, was $n")

        val loadLines = synchronized(c.agentLines) { c.agentLines.drop(before) }
        val markerAt = loadLines.indexOfFirst { "\"replayed\"" in it }
        val responseAt = loadLines.indexOfFirst { "\"result\"" in it }
        assertTrue(markerAt >= 0 && responseAt > markerAt, "the marker is the last notification, then comes the response (marker=$markerAt, response=$responseAt)")
        val replayLines = loadLines.subList(0, markerAt).count { "\"session/update\"" in it && "session_info_update" !in it }
        assertEquals(n, replayLines, "N is exactly the number of session/update lines of the replay")
        c.close()
    }

    @Test
    fun `the marker carries the server states in the same last notification`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession(mcpServers = listOf(McpServer.Http("tools", "https://x.example/mcp", listOf(HttpHeader("A", "b")))))
        val c = AcpPair(rt)
        c.initialize(listOf(ProfileExtensions.SESSION_SETUP))
        c.loadSession(s.sessionId.value, mcpServers = listOf(McpServer.Http("tools", "https://x.example/mcp", emptyList())))
        val last = c.infoMetas().last()
        assertNotNull(last["setup"])
        assertEquals("tools", (last["mcpServers"] as JsonArray)[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertNull(last["activeTask"], "nothing was running")
        c.close()
    }
}
