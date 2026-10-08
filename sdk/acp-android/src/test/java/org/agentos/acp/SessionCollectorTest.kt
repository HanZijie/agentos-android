@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.AcpExpectedError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionCollector]：建立会话那一段里收通知，并等 AgentOS 的收尾标记。
 * 要证明的是：通知和响应并发到达时（甚至迟到），[SessionCollector.finish] 也不会少收；旧版本的 AgentOS（没有标记）不等；
 * 等不到标记有上限、不会卡住；同一个收集器可以反复用于同一个会话的再次 load。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionCollectorTest {
    private fun text(t: String) = ContentBlock.Text(t)

    private fun user(t: String) = SessionUpdate.UserMessageChunk(text(t))

    private fun agent(t: String) = SessionUpdate.AgentMessageChunk(text(t))

    private fun marker(replayed: Int, extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null): SessionUpdate.SessionInfoUpdate =
        SessionUpdate.SessionInfoUpdate(
            _meta = buildJsonObject {
                put("org.agentos", buildJsonObject { put("setup", buildJsonObject { put("replayed", replayed) }); extra?.invoke(this) })
            },
        )

    // ------------------------------------------------------------------ 等标记

    @Test
    fun `finish waits for the marker and for as many updates as it announces, whatever order they arrive in`() = runTest {
        val c = SessionCollector(null)
        c.begin(replay = true, awaitMarker = true)
        val result = async { c.finish(5_000) }
        runCurrent()
        // the response is already here; the marker comes first, two of the three updates after it
        c.notify(marker(3), null)
        runCurrent()
        assertFalse("not done yet: 0 of 3", result.isCompleted)
        c.notify(user("hi"), null)
        c.notify(agent("hello"), null)
        runCurrent()
        assertFalse("not done yet: 2 of 3", result.isCompleted)
        c.notify(agent(" there"), null)
        runCurrent()
        assertTrue(result.isCompleted)
        assertEquals(listOf<AgentOsEvent>(AgentOsEvent.UserMessage("hi"), AgentOsEvent.Text("hello"), AgentOsEvent.Text(" there")), result.await().history)
    }

    @Test
    fun `updates that arrive before the marker are counted too`() = runTest {
        val c = SessionCollector(null)
        c.begin(replay = true, awaitMarker = true)
        c.notify(user("a"), null)
        c.notify(agent("b"), null)
        c.notify(marker(2), null)
        assertEquals(2, c.finish(1_000).history.size)
    }

    @Test
    fun `an empty replay is complete as soon as the marker says zero`() = runTest {
        val c = SessionCollector(null)
        c.begin(replay = false, awaitMarker = true)
        c.notify(marker(0), null)
        val started = testScheduler.currentTime
        assertEquals(emptyList<AgentOsEvent>(), c.finish(5_000).history)
        assertEquals("no waiting", started, testScheduler.currentTime)
    }

    @Test
    fun `a marker that never comes does not hang the caller, it uses what it has after the limit`() = runTest {
        val c = SessionCollector(null)
        c.begin(replay = true, awaitMarker = true)
        c.notify(user("only this"), null)
        val result = async { c.finish(5_000) }
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(4_999)
        assertFalse(result.isCompleted)
        advanceTimeBy(2)
        assertTrue(result.isCompleted)
        assertEquals(listOf<AgentOsEvent>(AgentOsEvent.UserMessage("only this")), result.await().history)
    }

    @Test
    fun `an older AgentOS without the marker is not waited for`() = runTest {
        val c = SessionCollector(null)
        c.begin(replay = true, awaitMarker = false)
        c.notify(user("x"), null)
        val before = testScheduler.currentTime
        c.finish(5_000)
        assertEquals(before, testScheduler.currentTime)
    }

    @Test
    fun `a marker announcing fewer updates than arrive is complete once those are in, extras are kept`() = runTest {
        val c = SessionCollector(null)
        c.begin(replay = true, awaitMarker = true)
        c.notify(marker(1), null)
        c.notify(user("a"), null)
        c.notify(agent("b"), null)
        assertEquals(2, c.finish(1_000).history.size)
    }

    // ------------------------------------------------------------------ 内容

    @Test
    fun `the server states and the running task come from the same notification`() = runTest {
        val c = SessionCollector(null)
        c.begin(replay = false, awaitMarker = true)
        c.notify(
            marker(0) {
                put("mcpServers", buildJsonArray { add(buildJsonObject { put("name", "up"); put("connected", true); put("toolCount", 2) }) })
                put("activeTask", buildJsonObject { put("taskId", "tsk_1"); put("state", "running") })
            },
            null,
        )
        val got = c.finish(1_000)
        assertEquals(listOf(McpServerStatus("up", true, 2, null)), got.servers)
        assertEquals("tsk_1", got.activeTask)
    }

    @Test
    fun `without a replay request the updates are counted but not kept`() = runTest {
        val c = SessionCollector(null)
        c.begin(replay = false, awaitMarker = true)
        c.notify(user("a"), null)
        c.notify(marker(1), null)
        assertEquals(emptyList<AgentOsEvent>(), c.finish(1_000).history)
    }

    @Test
    fun `notifications outside a setup are ignored - a live turn or a mode change must not leak into a later history`() = runTest {
        val c = SessionCollector(null)
        c.notify(user("before anything"), null)
        c.begin(replay = true, awaitMarker = false)
        c.finish(10)
        c.notify(user("after the setup"), null)
        c.begin(replay = true, awaitMarker = false)
        assertEquals(emptyList<AgentOsEvent>(), c.finish(10).history)
    }

    // ------------------------------------------------------------------ 复用

    @Test
    fun `one collector serves repeated loads of the same session, each with its own fresh history`() = runTest {
        val c = SessionCollector(null)
        c.begin(replay = true, awaitMarker = true)
        c.notify(user("one"), null)
        c.notify(marker(1), null)
        val first = c.finish(1_000)

        c.begin(replay = true, awaitMarker = true)
        c.notify(user("one"), null)
        c.notify(agent("two"), null)
        c.notify(marker(2), null)
        val second = c.finish(1_000)

        assertEquals(1, first.history.size)
        assertEquals(2, second.history.size)
        assertNull("the earlier result is a snapshot, it did not change", (first.history.getOrNull(1)))
    }

    @Test
    fun `a leftover marker from an earlier setup does not complete the next one early`() = runTest {
        val c = SessionCollector(null)
        c.begin(replay = true, awaitMarker = true)
        c.notify(marker(5), null) // announces 5 but only 1 arrives: the caller gave up after the limit
        c.notify(user("a"), null)
        val first = async { c.finish(100) }
        advanceTimeBy(200)
        first.await()

        c.begin(replay = true, awaitMarker = true)
        val second = async { c.finish(5_000) }
        runCurrent()
        assertFalse("the new setup waits for its own marker", second.isCompleted)
        c.notify(marker(0), null)
        runCurrent()
        assertTrue(second.isCompleted)
    }

    @Test
    fun `abort lets a waiting finish go and stops collecting`() = runTest {
        val c = SessionCollector(null)
        c.begin(replay = true, awaitMarker = true)
        val result = async { c.finish(5_000) }
        runCurrent()
        c.abort()
        runCurrent()
        assertTrue(result.isCompleted)
        c.notify(user("late"), null)
        c.begin(replay = true, awaitMarker = false)
        assertEquals(0, c.finish(10).history.size)
    }

    @Test
    fun `permission requests are always answered with cancel - the sdk approves nothing for the user`() = runTest {
        val c = SessionCollector(null)
        val r = c.requestPermissions(
            SessionUpdate.ToolCallUpdate(com.agentclientprotocol.model.ToolCallId("c")),
            listOf(),
            null,
        )
        assertEquals(com.agentclientprotocol.model.RequestPermissionOutcome.Cancelled, r.outcome)
    }

    @Test
    fun `concurrent notifications from many coroutines are all counted`() = runTest {
        val c = SessionCollector(null)
        c.begin(replay = true, awaitMarker = true)
        val scope = CoroutineScope(Dispatchers.Default)
        val jobs = List(8) { i -> scope.launch { repeat(50) { j -> c.notify(agent("$i-$j"), null); if (j % 10 == 0) yield() } } }
        jobs.forEach { it.join() }
        c.notify(marker(400), null)
        assertEquals(400, c.finish(1_000).history.size)
        delay(1)
    }

    // ------------------------------------------------------------------ 协商与错误还原

    @Test
    fun `the sdk asks for the setup extension, and only trusts an agentos that announces it`() {
        val meta = AgentOsMapping.initializeMeta()
        assertEquals("sessionSetup", ((((meta["org.agentos"] as JsonObject)["extensions"]) as kotlinx.serialization.json.JsonArray)[0] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue(AgentOsMapping.supportsSetupMarker(buildJsonObject { put("org.agentos", buildJsonObject { put("extensions", buildJsonObject { put("sessionSetup", buildJsonObject { put("version", 1) }) }) }) }))
        assertFalse(AgentOsMapping.supportsSetupMarker(buildJsonObject { put("org.agentos", buildJsonObject { put("extensions", buildJsonObject { put("toolScope", buildJsonObject { put("version", 1) }) }) }) }))
        assertFalse(AgentOsMapping.supportsSetupMarker(null))
    }

    @Test
    fun `the marker is read from the meta, anything else is not a marker`() {
        assertEquals(3, AgentOsMapping.setupReplayed(marker(3)._meta))
        assertEquals(0, AgentOsMapping.setupReplayed(marker(0)._meta))
        assertNull(AgentOsMapping.setupReplayed(null))
        assertNull(AgentOsMapping.setupReplayed(buildJsonObject { put("org.agentos", buildJsonObject { put("selection", buildJsonObject { put("x", 1) }) }) }))
        assertNull(AgentOsMapping.setupReplayed(buildJsonObject { put("org.agentos", buildJsonObject { put("setup", buildJsonObject { put("replayed", -1) }) }) }))
        assertNull(AgentOsMapping.setupReplayed(buildJsonObject { put("org.agentos", buildJsonObject { put("setup", buildJsonObject { put("replayed", "many") }) }) }))
    }

    @Test
    fun `the client library turns -32602 into a bare message, the prefix tells which agentos error it was`() {
        fun kind(message: String) = AgentOsMapping.fromExpected(AcpExpectedError(message)).error
        assertEquals(AgentOsError.INVALID_REQUEST, kind("invalid_params: mcpServers rejected (url_scheme): server #1: url must use https"))
        assertEquals(AgentOsError.INVALID_REQUEST, kind("invalid_params: unknown mode"))
        assertEquals(AgentOsError.UNSUPPORTED, kind("unsupported: mcpServers are not supported on this build"))
        assertEquals(AgentOsError.TOO_LARGE, kind("invalid_params: prompt too_large"))
        assertEquals(AgentOsError.FAILED, kind("something nobody wrote"))
        assertEquals(AgentOsError.FAILED, kind(""))
    }

    @Test
    fun `the exception for such an error carries the code, not the servers text or the callers values`() {
        val e = AgentOsMapping.fromExpected(AcpExpectedError("invalid_params: mcpServers rejected (url_private): https://10.0.0.5/secret?token=ZZZ"))
        assertFalse(e.message!!, "10.0.0.5" in e.message!! || "ZZZ" in e.message!!)
        assertTrue(e.message!!.contains("invalid_params"))
    }
}
