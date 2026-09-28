package org.agentos.runtime.router

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.agentos.runtime.RuntimeConfig
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.scheduler.SchedulerConfig
import org.agentos.runtime.store.SessionRecord
import org.agentos.runtime.store.SessionState
import org.agentos.runtime.store.SelectionMetadata
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.testing.TestRuntime
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W2 验收项 3：router 失败时回退到新建会话（session-selection.md 第 4 节）。 */
class SessionRouterTest {

    /** 每个用例一个运行时；[jev] 返回下一次选择的行为。 */
    private fun runtime(jev: JevProvider?) = TestRuntime(
        config = RuntimeConfig(scheduler = SchedulerConfig(tickMillis = 20), jev = jev, router = RouterConfig(timeoutMillis = 300)),
    )

    /** 给调用方建一个有首轮问答的会话。 */
    private suspend fun seed(rt: TestRuntime, caller: CallerIdentity, query: String): String {
        val s = rt.engine.createSession(caller, null)
        val t = rt.engine.submit(caller, s.id, TestRuntime.text(query))
        rt.engine.awaitTask(t.id)
        return s.id
    }

    private fun <T> test(jev: JevProvider?, block: suspend (TestRuntime) -> T) = runBlocking {
        val rt = runtime(jev).start()
        try {
            withTimeout(10_000) { block(rt) }
        } finally {
            rt.stop()
            rt.host.deleteDatabase()
        }
        Unit
    }

    @Test
    fun `no candidates creates a new session without calling Jev`() {
        var calls = 0
        test({ _: JevRequest -> calls++; "x" }) { rt ->
            val r = rt.engine.autoSelect(TestRuntime.APP, "hello", null)
            assertTrue(r.created)
            assertEquals("no_candidates", r.method)
            assertEquals(0, calls)
            val events = rt.engine.readEvents(r.session.id).map { it.eventType }
            assertEquals(listOf(EventTypes.SESSION_CREATED, EventTypes.SESSION_SELECTED), events)
        }
    }

    @Test
    fun `every Jev failure falls back to a new session`() {
        val behaviours = mapOf<String, suspend (JevRequest) -> String>(
            "jev_http_error" to { throw JevException("jev_http_error") },
            "jev_network_error" to { throw JevException("jev_network_error") },
            "jev_error" to { throw IllegalStateException("bad json") },
            "jev_timeout" to { delay(5_000); "never" },
            "jev_invalid_choice" to { "ses_does_not_exist" },
        )
        for ((expected, behaviour) in behaviours) {
            test({ req -> behaviour(req) }) { rt ->
                val existing = seed(rt, TestRuntime.APP, "plan a trip to Kyoto")
                val r = rt.engine.autoSelect(TestRuntime.APP, "what about hotels in Kyoto?", null)
                assertTrue(r.created, expected)
                assertEquals(SessionRouter.METHOD_FALLBACK, r.method)
                assertEquals(expected, r.fallbackReason)
                assertTrue(r.session.id != existing)
                assertEquals(TestRuntime.APP.ownerKey, r.session.ownerKey)
                val selected = rt.engine.readEvents(r.session.id).single { it.eventType == EventTypes.SESSION_SELECTED }
                assertEquals(expected, selected.payload["reason"].toString().trim('"'))
            }
        }
    }

    @Test
    fun `missing Jev configuration falls back`() = test(null) { rt ->
        seed(rt, TestRuntime.APP, "first")
        val r = rt.engine.autoSelect(TestRuntime.APP, "second", null)
        assertTrue(r.created)
        assertEquals("jev_unconfigured", r.fallbackReason)
    }

    @Test
    fun `Jev cannot pick another caller's session`() {
        lateinit var foreign: String
        test({ _: JevRequest -> foreign }) { rt ->
            foreign = seed(rt, TestRuntime.OTHER_APP, "other app's secret project")
            seed(rt, TestRuntime.APP, "my project")
            val r = rt.engine.autoSelect(TestRuntime.APP, "the secret project", null)
            assertTrue(r.created)
            assertEquals("jev_invalid_choice", r.fallbackReason)
        }
    }

    @Test
    fun `a selected session that became unavailable falls back`() {
        lateinit var chosen: String
        lateinit var rtRef: TestRuntime
        test({ _: JevRequest ->
            // 在 Jev 返回之前，这个会话进入了等恢复决定的状态
            rtRef.engine.storeForTesting.write { tx -> tx.sessions.setState(chosen, SessionState.PAUSED, tx.now, "recovery_required") }
            chosen
        }) { rt ->
            rtRef = rt
            chosen = seed(rt, TestRuntime.APP, "groceries")
            val r = rt.engine.autoSelect(TestRuntime.APP, "add milk", null)
            assertTrue(r.created)
            assertEquals("selected_session_unavailable", r.fallbackReason)
        }
    }

    @Test
    fun `a valid choice selects the caller's existing session and only offers own sessions`() {
        val requests = Collections.synchronizedList(mutableListOf<JevRequest>())
        lateinit var mine: String
        test({ req -> requests += req; mine }) { rt ->
            seed(rt, TestRuntime.OTHER_APP, "not mine")
            mine = seed(rt, TestRuntime.APP, "plan a trip to Kyoto")
            val r = rt.engine.autoSelect(TestRuntime.APP, "hotels in Kyoto?", null)
            assertFalse(r.created)
            assertEquals(mine, r.session.id)
            assertEquals(SessionRouter.METHOD_JEV, r.method)
            assertNull(r.fallbackReason)
            val req = requests.single()
            assertEquals(listOf(mine, JevProvider.NEW_SESSION), req.choices.map { it.id }, "own sessions only, new_session last")
            assertTrue("plan a trip to Kyoto" in req.choices.first().brief)
            assertTrue("fake-key" !in req.toString(), "no secrets in the Jev request")
            val selected = rt.engine.readEvents(mine).last()
            assertEquals(EventTypes.SESSION_SELECTED, selected.eventType)
        }
    }

    @Test
    fun `candidate pool honours the 30 minute window, the stale fill and the 254 cap`() {
        val host = org.agentos.runtime.testing.FakeHostPort()
        val store = runBlocking { org.agentos.runtime.store.Store.open(host.storage, host.clock) }
        val router = SessionRouter(store, jev = null) // candidates / buildRequest 不访问 Store
        val now = 10_000_000_000L
        fun rec(i: Int, ageMin: Long) = SessionRecord(
            id = "ses_%04d".format(i), ownerKey = "uid:1", callerKind = CallerKind.APP, callerUid = 1, state = SessionState.CREATED,
            pauseReason = null, cwd = null, createdAt = 0, lastActivityAt = now - ageMin * 60_000, lastSequence = 1,
            selection = SelectionMetadata(firstQuery = "q$i"),
        )
        // 5 个活跃 + 30 个过期：补足到 20
        val few = router.candidates((0 until 5).map { rec(it, 1) } + (5 until 35).map { rec(it, 60) }, now)
        assertEquals(20, few.size)
        assertEquals(5, few.count { !it.stale })
        // 300 个活跃：只取最近的 254
        val many = router.candidates((0 until 300).map { rec(it, (it % 29).toLong()) }, now)
        assertEquals(254, many.size)
        assertTrue(many.none { it.stale })
        // 没有首轮问题、已关闭的会话不参与
        val filtered = router.candidates(listOf(rec(1, 1).copy(selection = SelectionMetadata()), rec(2, 1).copy(state = SessionState.CLOSED)), now)
        assertTrue(filtered.isEmpty())
        // 超预算时缩短 brief，但 new_session 永远在
        val big = router.candidates((0 until 254).map { rec(it, 1).copy(selection = SelectionMetadata(firstQuery = "x".repeat(8_000))) }, now)
        val request = router.buildRequest("q", big, now)
        assertEquals(255, request.choices.size)
        assertEquals(JevProvider.NEW_SESSION, request.choices.last().id)
        assertTrue(request.choices.sumOf { it.brief.length } <= RouterConfig().maxInputChars)
        runBlocking { store.close() }
        host.deleteDatabase()
    }
}
