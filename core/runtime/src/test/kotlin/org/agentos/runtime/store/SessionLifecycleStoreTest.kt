package org.agentos.runtime.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.ports.ToolRef
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.agentos.runtime.testing.FakeScripts
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** schema v4（会话的 model_id、mode）、会话删除和分叉在 Store 层的行为（core/contracts/session-scheduling.md）。 */
class SessionLifecycleStoreTest {
    private val app = TestRuntime.APP

    private fun test(block: suspend (TestRuntime) -> Unit) = runBlocking {
        val rt = TestRuntime(FakeScripts.directives())
        rt.host.tools.registerSimple("alarm_create", ToolRisk.WRITE, ToolSource("alarm", "main", "alarm_create")) { ToolResult.text("set") }
        rt.start()
        try {
            withTimeout(30_000) { block(rt) }
        } finally {
            rt.stop()
            rt.host.deleteDatabase()
        }
    }

    private suspend fun TestRuntime.turn(sessionId: String, text: String): TaskRecord {
        val t = engine.submit(app, sessionId, TestRuntime.text(text))
        return engine.awaitTask(t.id)
    }

    private val callAlarm = buildJsonObject {
        put("fake", buildJsonObject { put("tools", buildJsonArray { add(buildJsonObject { put("name", "alarm_create") }) }) })
    }.toString()

    @Test
    fun `a new session has no model of its own and the default mode`() = test { rt ->
        val s = rt.engine.createSession(app, "/x")
        assertNull(s.modelId)
        assertEquals(SessionMode.DEFAULT, s.mode)
    }

    @Test
    fun `model and mode are stored and read back, and null puts the model back to following the settings`() = test { rt ->
        val s = rt.engine.createSession(app, null)
        val store = rt.engine.storeForTesting
        store.write { it.sessions.setModel(s.id, "model-b", it.now); it.sessions.setMode(s.id, SessionMode.READ_ONLY, it.now) }
        val after = rt.engine.session(app, s.id)
        assertEquals("model-b", after.modelId)
        assertEquals(SessionMode.READ_ONLY, after.mode)
        store.write { it.sessions.setModel(s.id, null, it.now) }
        assertNull(rt.engine.session(app, s.id).modelId)
    }

    @Test
    fun `a stored mode that cannot be read is the narrowest one, never the widest`() {
        assertEquals(SessionMode.CHAT, SessionMode.fromStored("root"))
        assertEquals(SessionMode.DEFAULT, SessionMode.fromStored(null))
        assertEquals(SessionMode.DEFAULT, SessionMode.fromStored(" "))
        assertEquals(SessionMode.READ_ONLY, SessionMode.fromStored("read_only"))
        assertNull(SessionMode.parse("root"))
        assertEquals(SessionMode.DEFAULT, SessionMode.parse(""))
    }

    @Test
    fun `deleting a session removes its tasks, tool calls, messages and event log - and only its own`() = test { rt ->
        val a = rt.engine.createSession(app, null)
        val b = rt.engine.createSession(app, null)
        val ta = rt.turn(a.id, callAlarm)
        rt.turn(b.id, "keep me")
        val store = rt.engine.storeForTesting
        assertTrue(store.read { it.tasks.toolCalls(ta.id) }.isNotEmpty(), "the turn dispatched a tool")
        assertNotNull(store.read { it.sessions.loadMessages(a.id) })

        rt.engine.deleteSession(app, a.id)

        assertNull(store.read { it.sessions.get(a.id) })
        assertEquals(emptyList(), store.read { it.tasks.listBySession(a.id) })
        assertEquals(emptyList(), store.read { it.tasks.toolCalls(ta.id) })
        assertNull(store.read { it.sessions.loadMessages(a.id) })
        assertEquals(emptyList(), store.read { it.events.read(a.id, 0, 100) })
        // the other session and the system stream are untouched
        assertNotNull(store.read { it.sessions.get(b.id) })
        assertTrue(store.read { it.events.read(b.id, 0, 100) }.isNotEmpty())
        assertTrue(store.read { it.events.read(EventTypes.SYSTEM_STREAM, 0, 100) }.isNotEmpty())
    }

    @Test
    fun `the system stream cannot be deleted`() = test { rt ->
        assertFailsWith<IllegalArgumentException> { rt.engine.storeForTesting.write { it.sessions.delete(EventTypes.SYSTEM_STREAM) } }
        assertFailsWith<org.agentos.runtime.errors.AgentOsException> { rt.engine.deleteSession(app, EventTypes.SYSTEM_STREAM) }
    }

    @Test
    fun `a fork gets gapless sequences, the original timestamps, no consent events and only finished tasks`() = test { rt ->
        val s = rt.engine.createSession(app, "/x", listOf(ToolRef("alarm", "alarm_create")))
        val done = rt.turn(s.id, callAlarm) // a WRITE tool: the log has consent.requested / consent.resolved
        val store = rt.engine.storeForTesting
        val original = store.read { it.events.read(s.id, 0, 1000) }
        assertTrue(original.any { it.eventType == EventTypes.CONSENT_REQUESTED }, "precondition")

        val f = rt.engine.forkSession(app, s.id)
        val copy = store.read { it.events.read(f.id, 0, 1000) }
        val copied = copy.filter { it.eventType != EventTypes.SESSION_CREATED }

        assertEquals((1L..copy.size).toList(), copy.map { it.sequence }, "sequences in the new session start at 1 and have no gaps")
        assertFalse(copied.any { it.eventType.startsWith("consent.") || it.eventType.startsWith("hook.") })
        assertTrue(copied.all { it.taskId == done.id })
        val expected = original.filter { it.taskId == done.id && !it.eventType.startsWith("consent.") && !it.eventType.startsWith("hook.") }
        assertEquals(expected.map { it.eventType to it.timestamp }, copied.map { it.eventType to it.timestamp }, "same events, same time")
        assertEquals(copy.size.toLong(), store.read { it.sessions.get(f.id) }!!.lastSequence, "the session knows its last sequence")

        // sessions are independent: a new turn in the fork does not show up in the original
        rt.engine.submit(app, f.id, TestRuntime.text("fork only")).let { rt.engine.awaitTask(it.id) }
        assertEquals(original.size, store.read { it.events.read(s.id, 0, 1000) }.size)
        // and watchers of the fork can read from the start
        assertEquals(copy.first().eventType, rt.engine.events(f.id, 0).first().eventType)
    }

    @Test
    fun `a fork inherits the selection data so that auto select can find it`() = test { rt ->
        val s = rt.engine.createSession(app, "/x")
        rt.turn(s.id, "tell me about alarms")
        val f = rt.engine.forkSession(app, s.id)
        val original = rt.engine.session(app, s.id)
        val forked = rt.engine.session(app, f.id)
        assertEquals(original.selection, forked.selection)
        assertEquals("/x", forked.cwd)
    }
}
