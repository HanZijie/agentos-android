package org.agentos.runtime.store

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.events.PendingEvent
import org.agentos.runtime.ports.PiMessages
import org.agentos.runtime.router.SessionRouter
import org.agentos.runtime.testing.FakeHostPort
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** W2 验收项 1：store 追加写与 sequence（events.md 第 6 节）。 */
class StoreTest {
    private val host = FakeHostPort()

    @AfterTest
    fun cleanup() = host.deleteDatabase()

    private fun ev(session: String, type: String, n: Int) =
        PendingEvent(session, "tsk_x", type, buildJsonObject { put("n", n) })

    @Test
    fun `sequences are per session, start at 1, have no gaps and survive reopening`() = runBlocking {
        val store = Store.open(host.storage, host.clock)
        val (a, b) = store.write { tx ->
            SessionRouter.createSession(tx, TestRuntime.APP, null, "test").id to SessionRouter.createSession(tx, TestRuntime.OTHER_APP, null, "test").id
        }
        // 两个会话交错并发追加
        (1..40).map { i -> async { store.append(ev(if (i % 2 == 0) a else b, "test.event", i)) } }.awaitAll()

        for (s in listOf(a, b)) {
            val events = store.read { it.events.read(s) }
            // 第 1 条是 session.created
            assertEquals((1L..21L).toList(), events.map { it.sequence }, "session $s: contiguous from 1")
            assertEquals(EventTypes.SESSION_CREATED, events.first().eventType)
            assertEquals(21L, store.watch(s).value)
            val tail = store.read { it.events.read(s, afterSequence = 15) }
            assertEquals((16L..21L).toList(), tail.map { it.sequence })
        }
        store.close()

        val reopened = Store.open(host.storage, host.clock)
        assertEquals(21L, reopened.watch(a).value, "watch starts from the committed sequence")
        val next = reopened.append(ev(a, "test.event", 99))
        assertEquals(22L, next.sequence, "sequence continues after reopening, never reused")
        assertEquals(21L, reopened.read { it.events.lastSequence(b) })
        reopened.close()
    }

    @Test
    fun `a rolled back transaction leaves no event and no sequence gap`() = runBlocking {
        val store = Store.open(host.storage, host.clock)
        val s = store.write { tx -> SessionRouter.createSession(tx, TestRuntime.APP, null, "test").id }
        assertFailsWith<IllegalStateException> {
            store.write { tx ->
                tx.events.append(ev(s, "test.event", 1))
                tx.events.append(ev(s, "test.event", 2))
                error("boom")
            }
        }
        assertEquals(1L, store.read { it.events.lastSequence(s) })
        assertEquals(1L, store.watch(s).value, "subscribers are not notified of rolled back events")
        assertEquals(2L, store.append(ev(s, "test.event", 3)).sequence)
        store.close()
    }

    @Test
    fun `the system stream has its own sequence`() = runBlocking {
        val store = Store.open(host.storage, host.clock)
        val s = store.write { tx -> SessionRouter.createSession(tx, TestRuntime.APP, null, "test").id }
        val sys1 = store.append(PendingEvent(EventTypes.SYSTEM_STREAM, null, EventTypes.RUNTIME_STARTED))
        val sys2 = store.append(PendingEvent(EventTypes.SYSTEM_STREAM, null, EventTypes.SUPERVISOR_STATUS))
        assertEquals(listOf(1L, 2L), listOf(sys1.sequence, sys2.sequence))
        assertEquals(1L, store.read { it.events.lastSequence(s) })
        assertTrue(store.read { it.sessions.listAll() }.none { it.id == EventTypes.SYSTEM_STREAM })
        store.close()
    }

    @Test
    fun `payload limits omit image data and truncate oversized fields`() = runBlocking {
        val store = Store.open(host.storage, host.clock)
        val s = store.write { tx -> SessionRouter.createSession(tx, TestRuntime.APP, null, "test").id }
        val huge = "x".repeat(100_000)
        val e = store.append(
            PendingEvent(
                s, "t", EventTypes.MESSAGE_END,
                buildJsonObject {
                    put(
                        "message",
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    add(buildJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data", "A".repeat(5000)) })
                                    add(buildJsonObject { put("type", "text"); put("text", huge) })
                                },
                            )
                        },
                    )
                },
            ),
        )
        val stored = store.read { it.events.read(s, e.sequence - 1) }.single()
        assertTrue(stored.payload.toString().length <= PayloadLimits.MAX_PAYLOAD_CHARS + 200)
        assertEquals(JsonPrimitive(true), stored.payload["truncated"])
        val content = stored.payload["message"]!!.jsonObject["content"]!!.jsonArray
        assertEquals("<omitted: 5000 chars>", content[0].jsonObject["data"]!!.jsonPrimitive.content)
        assertTrue(content[1].jsonObject["text"]!!.jsonPrimitive.content.length < 20_000)
        store.close()
    }

    @Test
    fun `pi messages are stored per session with a stable snapshot for rollback`() = runBlocking {
        val store = Store.open(host.storage, host.clock)
        val s = store.write { tx -> SessionRouter.createSession(tx, TestRuntime.APP, null, "test").id }
        fun msgs(n: Int) = PiMessages(buildJsonArray { repeat(n) { add(buildJsonObject { put("role", "user"); put("i", it) }) } })
        store.write { it.sessions.saveMessages(s, msgs(2), it.now, stable = true) }
        store.write { it.sessions.saveMessages(s, msgs(5), it.now, stable = false) }
        assertEquals(5, store.read { it.sessions.loadMessages(s) }!!.size)
        store.write { it.sessions.rollbackMessagesToStable(s, it.now) }
        assertEquals(msgs(2), store.read { it.sessions.loadMessages(s) })
        store.close()
    }
}
