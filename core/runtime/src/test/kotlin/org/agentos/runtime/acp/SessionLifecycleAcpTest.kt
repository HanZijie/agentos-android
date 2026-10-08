@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.runtime.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.RuntimeConfig
import org.agentos.runtime.ports.ToolRef
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.agentos.runtime.store.TaskState
import org.agentos.runtime.testing.FakeScripts
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ACP 的会话生命周期，官方 SDK 的 Client ↔ 运行时的 Agent 端：`session/list | load | resume | fork | delete | close`
 * （core/protocol/acp-mapping.md 第 4a 节）。重点是**归属**（别人的会话一律“不存在”）、**重放**（load 返回之前历史已经到齐）和
 * **范围不能变**（load 带的 toolScope 被忽略，fork 只能收窄）。
 */
class SessionLifecycleAcpTest {
    private val alarm = ToolSource("alarm", "main", "alarm_create")
    private val delete = ToolSource("notes", "main", "note_delete")

    private fun test(
        config: RuntimeConfig = TestRuntime.config(),
        block: suspend CoroutineScope.(AcpPair, TestRuntime) -> Unit,
    ): Unit = runBlocking {
        val rt = TestRuntime(FakeScripts.directives(), config = config)
        rt.host.tools.registerSimple("add") { args ->
            ToolResult.text(((args["a"] as kotlinx.serialization.json.JsonPrimitive).content.toInt() + (args["b"] as kotlinx.serialization.json.JsonPrimitive).content.toInt()).toString())
        }
        rt.host.tools.registerSimple("alarm_create", ToolRisk.WRITE, alarm) { ToolResult.text("alarm set") }
        rt.host.tools.registerSimple("note_delete", ToolRisk.HIGH, delete) { ToolResult.text("deleted") }
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

    private fun directive(vararg pairs: Pair<String, JsonElement>): String = buildJsonObject { put("fake", buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }) }.toString()

    private fun num(n: Int) = kotlinx.serialization.json.JsonPrimitive(n)

    private fun str(s: String) = kotlinx.serialization.json.JsonPrimitive(s)

    private fun addCall() = directive(
        "tools" to buildJsonArray { add(buildJsonObject { put("name", "add"); put("arguments", buildJsonObject { put("a", 2); put("b", 3) }) }) },
    )

    private fun slowTurn() = directive("chunks" to num(600), "chunkChars" to num(4), "text" to str("z"), "intervalMs" to num(10))

    private fun List<SessionUpdate>.userTexts() = filterIsInstance<SessionUpdate.UserMessageChunk>().map { (it.content as ContentBlock.Text).text }

    private fun List<SessionUpdate>.agentText() = filterIsInstance<SessionUpdate.AgentMessageChunk>().joinToString("") { (it.content as ContentBlock.Text).text }

    private fun AcpPair.errorCode(): String = lastError()["data"]!!.jsonObject["agentosCode"]!!.jsonPrimitive.content

    private suspend fun TestRuntime.awaitRunning(sessionId: String) =
        until { engine.activeTask(TestRuntime.APP, sessionId)?.state == TaskState.RUNNING }

    // ------------------------------------------------------------------ 能力

    @Test
    fun `initialize declares load, resume, fork, list, delete and close`() = test { pair, _ ->
        val info = pair.initialize()
        assertTrue(info.capabilities.loadSession)
        val caps = info.capabilities.sessionCapabilities
        assertNotNull(caps.fork, "fork")
        assertNotNull(caps.list, "list")
        assertNotNull(caps.resume, "resume")
        assertNotNull(caps.delete, "delete")
        assertNotNull(caps.close, "close")
    }

    // ------------------------------------------------------------------ session/list

    @Test
    fun `list shows only the callers own sessions, newest first, titled by the first prompt`() = test { pair, rt ->
        pair.initialize()
        val a = pair.newSession()
        pair.prompt(a, "first question about alarms")
        rt.host.clock.advance(60_000)
        val b = pair.newSession()
        pair.prompt(b, "second question\nwith a second line")
        val other = AcpPair(rt, TestRuntime.OTHER_APP)
        other.initialize()
        other.newSession()
        try {
            val mine = pair.listSessions()
            assertEquals(listOf(b.sessionId.value, a.sessionId.value), mine.map { it.sessionId.value }, "newest activity first")
            assertEquals("first question about alarms", mine.last().title)
            assertEquals("second question", mine.first().title, "only the first line, trimmed")
            assertEquals("/sdcard", mine.first().cwd)
            assertNotNull(java.time.Instant.parse(mine.first().updatedAt), "ISO 8601")
            assertEquals(1, other.listSessions().size, "another app sees only its own")
            assertEquals(emptyList(), pair.listSessions(cwd = "/somewhere/else"), "cwd filters")

            val self = AcpPair(rt, TestRuntime.SELF)
            self.initialize()
            assertEquals(3, self.listSessions().size, "AgentOS itself sees every session")
            self.close()
        } finally {
            other.close()
        }
    }

    // ------------------------------------------------------------------ 归属

    @Test
    fun `another app can neither load, resume, fork nor delete a session, and the answer is the same as for one that does not exist`() = test { pair, rt ->
        pair.initialize()
        val mine = pair.newSession()
        pair.prompt(mine, "private")
        val other = AcpPair(rt, TestRuntime.OTHER_APP)
        other.initialize()
        try {
            val attempts: List<suspend (String) -> Unit> = listOf(
                { id -> other.loadSession(id) },
                { id -> other.resumeSession(id) },
                { id -> other.forkSession(id) },
                { id -> other.client.deleteSession(SessionId(id)) },
            )
            for (attempt in attempts) {
                assertFailsWith<Exception> { attempt(mine.sessionId.value) }
                val code = other.errorCode()
                val message = other.lastError()["message"]!!.jsonPrimitive.content
                assertFailsWith<Exception> { attempt("ses_00000000000000000000000000") }
                assertEquals("session_not_found", code)
                assertEquals(code, other.errorCode())
                assertEquals(message, other.lastError()["message"]!!.jsonPrimitive.content, "no way to tell 'not yours' from 'not there'")
            }
            // and it is all still there
            assertEquals(1, rt.engine.listSessions(TestRuntime.APP).size)
            assertTrue(rt.engine.readEvents(mine.sessionId.value).isNotEmpty())
        } finally {
            other.close()
        }
    }

    // ------------------------------------------------------------------ session/load

    @Test
    fun `load replays the whole conversation in order before it returns, and the session keeps working`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        pair.prompt(s, "hello one")
        pair.prompt(s, addCall())

        val second = AcpPair(rt) // a new connection of the same app
        second.initialize()
        val loaded = second.loadSession(s.sessionId.value)
        val replay = second.strayUpdates()
        val users = replay.userTexts()
        assertEquals(2, users.size)
        assertEquals("hello one", users[0])
        assertEquals(addCall(), users[1])
        assertTrue("echo: hello one" in replay.agentText(), "the first answer is replayed")
        assertTrue(replay.agentText().endsWith("(fake) done"), "and the last one")

        val firstUser = replay.indexOfFirst { it is SessionUpdate.UserMessageChunk }
        val firstAgent = replay.indexOfFirst { it is SessionUpdate.AgentMessageChunk }
        val secondUser = replay.indexOfLast { it is SessionUpdate.UserMessageChunk }
        val toolCall = replay.indexOfFirst { it is SessionUpdate.ToolCall }
        assertTrue(firstUser < firstAgent && firstAgent < secondUser && secondUser < toolCall, "history keeps its order")
        val call = replay.filterIsInstance<SessionUpdate.ToolCall>().single()
        assertEquals("add", call.title)
        val statuses = replay.filterIsInstance<SessionUpdate.ToolCallUpdate>().filter { it.toolCallId == call.toolCallId }.map { it.status }
        assertEquals(listOf(ToolCallStatus.IN_PROGRESS, ToolCallStatus.COMPLETED), statuses)

        // the replay went through the outbound gate, like a live turn
        assertTrue(second.gateCalls.get() >= replay.size)

        // and the loaded session takes the next prompt, in the same session
        val events = second.prompt(loaded, "after the load")
        assertEquals(StopReason.END_TURN, events.response().stopReason)
        assertTrue("echo: after the load" in events.text())
        assertEquals(s.sessionId.value, loaded.sessionId.value)
        second.close()
    }

    @Test
    fun `an empty session loads with an empty history`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        val second = AcpPair(rt)
        second.initialize()
        second.loadSession(s.sessionId.value)
        assertEquals(emptyList(), second.strayUpdates())
        second.close()
    }

    @Test
    fun `resume restores the session without replaying anything`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        pair.prompt(s, "hello")
        val second = AcpPair(rt)
        second.initialize()
        val resumed = second.resumeSession(s.sessionId.value)
        assertEquals(emptyList(), second.strayUpdates())
        assertEquals(StopReason.END_TURN, second.prompt(resumed, "again").response().stopReason)
        second.close()
    }

    @Test
    fun `load ignores a toolScope in the request - the scope belongs to the session and cannot be changed`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession(toolScopeMeta(ToolRef("alarm", "alarm_create")))
        val second = AcpPair(rt)
        second.initialize()
        // an app that tries to widen the scope on load: both a different tool and "no scope at all" must not matter
        val wider = second.loadSession(s.sessionId.value, toolScopeMeta(ToolRef("notes", "note_delete"), ToolRef("alarm", "alarm_create")))
        assertEquals(listOf(ToolRef("alarm", "alarm_create")), rt.engine.session(TestRuntime.APP, s.sessionId.value).toolScope)
        val events = second.prompt(wider, directive("tools" to buildJsonArray {
            add(buildJsonObject { put("name", "note_delete") })
            add(buildJsonObject { put("name", "alarm_create") })
        }))
        assertEquals(StopReason.END_TURN, events.response().stopReason)
        assertEquals(listOf("alarm_create"), rt.host.tools.invocations.map { it.name }, "note_delete stayed out of the session")
        assertEquals(listOf("alarm_create"), rt.core!!.configs.last().tools.map { it.name })
        // a malformed scope on load is not even looked at
        second.loadSession(s.sessionId.value, buildJsonObject { put(ProfileExtensions.META_KEY, buildJsonObject { put("toolScope", str("nonsense")) }) })
        second.close()
    }

    @Test
    fun `load tells the client about a turn that is still running, with its task id`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        val running = async { pair.prompt(s, slowTurn()) }
        rt.awaitRunning(s.sessionId.value)
        val active = rt.engine.activeTask(TestRuntime.APP, s.sessionId.value)!!

        val second = AcpPair(rt)
        second.initialize()
        second.loadSession(s.sessionId.value)
        val info = second.infoMetas().last()["activeTask"]!!.jsonObject
        assertEquals(active.id, info["taskId"]!!.jsonPrimitive.content)
        assertEquals("running", info["state"]!!.jsonPrimitive.content)
        assertTrue(second.strayUpdates().userTexts().isNotEmpty(), "the prompt of the running turn is already part of the history")

        s.cancel()
        assertEquals(StopReason.CANCELLED, running.await().response().stopReason)
        second.close()
    }

    @Test
    fun `replay is limited to the most recent turns`() = test(TestRuntime.config().copy(acp = AcpConfig(maxReplayTurns = 2))) { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        listOf("q1", "q2", "q3", "q4").forEach { pair.prompt(s, it) }
        val second = AcpPair(rt)
        second.initialize()
        second.loadSession(s.sessionId.value)
        assertEquals(listOf("q3", "q4"), second.strayUpdates().userTexts())
        second.close()
    }

    @Test
    fun `a session of a failed turn loads, and its history says what it asked`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        assertFailsWith<Exception> { pair.prompt(s, directive("chunks" to num(2), "fail" to str("model_unavailable"))) }
        val second = AcpPair(rt)
        second.initialize()
        second.loadSession(s.sessionId.value)
        assertEquals(1, second.strayUpdates().userTexts().size)
        second.close()
    }

    // ------------------------------------------------------------------ session/fork

    @Test
    fun `fork copies the finished turns into an independent session`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession(toolScopeMeta(ToolRef("alarm", "alarm_create")))
        pair.prompt(s, "one")
        pair.prompt(s, "two")

        val fork = AcpPair(rt)
        fork.initialize()
        val f = fork.forkSession(s.sessionId.value)
        assertNotEquals(s.sessionId.value, f.sessionId.value)
        assertEquals(emptyList(), fork.strayUpdates(), "fork does not replay; load does")
        assertEquals(2, pair.listSessions().size)

        // the scope is inherited and fixed
        assertEquals(listOf(ToolRef("alarm", "alarm_create")), rt.engine.session(TestRuntime.APP, f.sessionId.value).toolScope)
        // the context (Pi messages) was copied: the stable messages of the original
        val originalMessages = rt.engine.storeForTesting.read { it.sessions.loadMessages(s.sessionId.value) }
        val forkMessages = rt.engine.storeForTesting.read { it.sessions.loadMessages(f.sessionId.value) }
        assertNotNull(originalMessages)
        assertEquals(originalMessages.json, forkMessages?.json)

        // the history of the fork is the history so far
        val viewer = AcpPair(rt)
        viewer.initialize()
        viewer.loadSession(f.sessionId.value)
        assertEquals(listOf("one", "two"), viewer.strayUpdates().userTexts())

        // from here on they are independent
        fork.prompt(f, "only in the fork")
        val viewOriginal = AcpPair(rt)
        viewOriginal.initialize()
        viewOriginal.loadSession(s.sessionId.value)
        assertEquals(listOf("one", "two"), viewOriginal.strayUpdates().userTexts())

        // the audit trail says where it came from
        val created = rt.engine.readEvents(f.sessionId.value).first { it.eventType == org.agentos.runtime.events.EventTypes.SESSION_CREATED }
        assertEquals(s.sessionId.value, created.payload["forkedFrom"]!!.jsonPrimitive.content)
        fork.close(); viewer.close(); viewOriginal.close()
    }

    @Test
    fun `a fork can only narrow the scope of the original`() = test { pair, rt ->
        pair.initialize()
        val both = pair.newSession(toolScopeMeta(ToolRef("alarm", "alarm_create"), ToolRef("notes", "note_delete")))
        val narrowed = pair.forkSession(both.sessionId.value, toolScopeMeta(ToolRef("alarm", "alarm_create"), ToolRef("other", "anything")))
        assertEquals(listOf(ToolRef("alarm", "alarm_create")), rt.engine.session(TestRuntime.APP, narrowed.sessionId.value).toolScope, "intersection, unknown entries add nothing")
        val none = pair.forkSession(both.sessionId.value, toolScopeMeta(ToolRef("other", "anything")))
        assertEquals(emptyList(), rt.engine.session(TestRuntime.APP, none.sessionId.value).toolScope, "an empty intersection is 'no tools', never 'no restriction'")

        val narrow = pair.newSession(toolScopeMeta(ToolRef("alarm", "alarm_create")))
        val widened = pair.forkSession(narrow.sessionId.value, toolScopeMeta(ToolRef("alarm", "alarm_create"), ToolRef("notes", "note_delete")))
        assertEquals(listOf(ToolRef("alarm", "alarm_create")), rt.engine.session(TestRuntime.APP, widened.sessionId.value).toolScope, "asking for more gets nothing more")

        // a session without any scope: the fork may bring one
        val open = pair.newSession()
        val scoped = pair.forkSession(open.sessionId.value, toolScopeMeta(ToolRef("alarm", "alarm_create")))
        assertEquals(listOf(ToolRef("alarm", "alarm_create")), rt.engine.session(TestRuntime.APP, scoped.sessionId.value).toolScope)
        val inherited = pair.forkSession(open.sessionId.value)
        assertNull(rt.engine.session(TestRuntime.APP, inherited.sessionId.value).toolScope, "no scope stays no scope")

        assertFailsWith<Exception> { pair.forkSession(open.sessionId.value, buildJsonObject { put(ProfileExtensions.META_KEY, buildJsonObject { put("toolScope", str("nonsense")) }) }) }
        assertEquals("invalid_params", pair.errorCode())
    }

    @Test
    fun `a fork does not take the turn that is still running`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        pair.prompt(s, "finished")
        val running = async { pair.prompt(s, slowTurn()) }
        rt.awaitRunning(s.sessionId.value)

        val f = pair.forkSession(s.sessionId.value)
        val forkTasks = rt.engine.readEvents(f.sessionId.value).mapNotNull { it.taskId }.toSet()
        assertEquals(1, forkTasks.size, "only the finished turn")
        s.cancel()
        running.await()
    }

    // ------------------------------------------------------------------ session/delete, session/close

    @Test
    fun `delete removes the session with its events, tasks and messages`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        val id = s.sessionId.value
        pair.prompt(s, addCall())
        assertTrue(rt.engine.readEvents(id).isNotEmpty())

        pair.client.deleteSession(SessionId(id))

        assertEquals(emptyList(), pair.listSessions())
        assertFailsWith<org.agentos.runtime.errors.AgentOsException> { rt.engine.session(TestRuntime.APP, id) }
        assertEquals(emptyList(), rt.engine.readEvents(id), "the event log is not left behind")
        val store = rt.engine.storeForTesting
        assertEquals(emptyList(), store.read { it.tasks.listBySession(id) })
        assertNull(store.read { it.sessions.loadMessages(id) })
        assertFailsWith<Exception> { pair.loadSession(id) }
        assertEquals("session_not_found", pair.errorCode())
        assertFalse(rt.host.fakeSessionTools.attached.containsKey(id))
        assertTrue(id in rt.host.fakeSessionTools.detached, "its session tools were released")
    }

    @Test
    fun `delete stops a running turn first`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        val running = async { runCatching { pair.prompt(s, slowTurn()) } }
        rt.awaitRunning(s.sessionId.value)

        pair.client.deleteSession(SessionId(s.sessionId.value))

        withTimeout(10_000) { running.await() } // the prompt ended (cancelled or failed - the session is gone)
        assertEquals(emptyList(), pair.listSessions())
        assertEquals(0, rt.engine.storeForTesting.read { it.tasks.listByState(TaskState.RUNNING, TaskState.QUEUED, TaskState.CANCELLING).size })
    }

    @Test
    fun `close stops a running turn and frees the session but keeps it and its history`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        pair.prompt(s, "kept")
        val running = async { pair.prompt(s, slowTurn()) }
        rt.awaitRunning(s.sessionId.value)

        s.close()

        assertEquals(StopReason.CANCELLED, running.await().response().stopReason)
        assertTrue(s.sessionId.value in rt.host.fakeSessionTools.detached, "session tools were released")
        assertEquals(1, pair.listSessions().size, "the session is still there")
        val second = AcpPair(rt)
        second.initialize()
        val back = second.loadSession(s.sessionId.value)
        assertEquals("kept", second.strayUpdates().userTexts().first())
        assertEquals(StopReason.END_TURN, second.prompt(back, "welcome back").response().stopReason)
        second.close()
    }
}
