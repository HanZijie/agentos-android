package org.agentos.runtime.store

import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.RuntimeConfig
import org.agentos.runtime.broker.CallerPolicy
import org.agentos.runtime.broker.OpenCallerPolicy
import org.agentos.runtime.broker.StrictCallerPolicy
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.ToolRef
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolScope
import org.agentos.runtime.ports.ToolSource
import org.agentos.runtime.router.JevProvider
import org.agentos.runtime.router.JevRequest
import org.agentos.runtime.scheduler.SchedulerConfig
import org.agentos.runtime.testing.FakeHostPort
import org.agentos.runtime.testing.FakeScripts
import org.agentos.runtime.testing.FakeStep
import org.agentos.runtime.testing.FakeTurnScript
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/third-party-acp.md 4.5: the scope belongs to the session. It is written when the session is created, it survives a restart,
 * it cannot be changed afterwards (there is no way to), a database from before the column existed still opens, and picking an
 * existing session by auto-select never gives a caller a session with a different scope.
 */
class ToolScopeStoreTest {
    private val alarm = ToolSource("alarm", "main", "alarm_create")
    private val event = ToolSource("calendar", "main", "event_create")
    private val delete = ToolSource("notes", "main", "note_delete")
    private val memoScope = listOf(ToolRef("alarm", "alarm_create"), ToolRef("calendar", "event_create"))

    private val script = FakeTurnScript(
        listOf(listOf(FakeStep.ToolUse("alarm_create", buildJsonObject { put("n", 1) }, id = "c1"), FakeStep.ToolUse("note_delete", buildJsonObject { }, id = "c2")), listOf(FakeStep.Text("done"))),
    )

    private fun register(rt: TestRuntime) {
        rt.host.tools.registerSimple("alarm_create", ToolRisk.WRITE, alarm) { ToolResult.text("alarm set") }
        rt.host.tools.registerSimple("event_create", ToolRisk.WRITE, event) { ToolResult.text("event created") }
        rt.host.tools.registerSimple("note_delete", ToolRisk.HIGH, delete) { ToolResult.text("deleted") }
    }

    private fun <T> run(rt: TestRuntime, block: suspend (TestRuntime) -> T) = runBlocking {
        rt.start()
        try {
            withTimeout(15_000) { block(rt) }
        } finally {
            rt.stop()
        }
        Unit
    }

    private fun runtime(jev: JevProvider? = null, file: java.io.File? = null, policy: CallerPolicy = OpenCallerPolicy): TestRuntime {
        val config = RuntimeConfig(scheduler = SchedulerConfig(tickMillis = 20), jev = jev, quota = TestRuntime.UNLIMITED_QUOTA, callerPolicy = policy)
        val rt = if (file == null) TestRuntime(FakeScripts.always(script), config = config)
        else TestRuntime(FakeScripts.always(script), databaseFile = file, host = FakeHostPort(databaseFile = file), config = config)
        register(rt)
        return rt
    }

    // ------------------------------------------------------------------ 随会话持久化

    @Test
    fun `the scope is stored with the session, sorted and distinct, and the creation event says so`() {
        val rt = runtime()
        run(rt) {
            val s = rt.engine.createSession(TestRuntime.APP, null, listOf(ToolRef("calendar", "event_create"), ToolRef("alarm", "alarm_create"), ToolRef("alarm", "alarm_create")))
            assertEquals(ToolScope.normalize(memoScope), s.toolScope)
            val created = rt.engine.readEvents(s.id).single { it.eventType == EventTypes.SESSION_CREATED }
            val logged = created.payload["toolScope"]!!.jsonArray.map { it.jsonObject["plugin"]!!.jsonPrimitive.content + "/" + it.jsonObject["tool"]!!.jsonPrimitive.content }
            assertEquals(listOf("alarm/alarm_create", "calendar/event_create"), logged)
        }
        rt.host.deleteDatabase()
    }

    @Test
    fun `no scope is stored as no scope, and an empty scope as an empty one - they are not the same thing`() {
        val rt = runtime()
        run(rt) {
            val none = rt.engine.createSession(TestRuntime.SELF, null)
            val empty = rt.engine.createSession(TestRuntime.SELF, null, emptyList())
            assertNull(none.toolScope)
            assertEquals(emptyList(), empty.toolScope)
            assertEquals(ToolScope.ALL, none.scope)
            assertEquals(ToolScope.NONE, empty.scope)
            assertTrue(rt.engine.readEvents(none.id).single { it.eventType == EventTypes.SESSION_CREATED }.payload["toolScope"] == null)
        }
        rt.host.deleteDatabase()
    }

    @Test
    fun `the scope is still there after the runtime restarts, and it still limits the session`() {
        val first = runtime()
        var sessionId = ""
        run(first) {
            sessionId = first.engine.createSession(TestRuntime.APP, null, memoScope).id
        }
        val second = runtime(file = first.databaseFile)
        run(second) {
            val s = second.engine.session(TestRuntime.APP, sessionId)
            assertEquals(ToolScope.normalize(memoScope), s.toolScope)
            val t = second.engine.submit(TestRuntime.APP, sessionId, TestRuntime.text("go"))
            second.engine.awaitTask(t.id)
            assertEquals(listOf("alarm_create", "event_create"), second.core!!.configs.last().tools.map { it.name }.sorted())
            assertEquals(listOf("alarm_create"), second.host.tools.invocations.map { it.name }, "note_delete was asked for and refused")
            val settled = second.engine.readEvents(sessionId).filter { it.eventType == EventTypes.TOOL_SETTLED }
            assertEquals(listOf(null, ErrorCode.TOOL_NOT_IN_CATALOG), settled.map { it.error?.code })
        }
        second.host.deleteDatabase()
    }

    @Test
    fun `a third-party session that was created without a scope has the whole catalog after a restart`() {
        val first = runtime()
        var sessionId = ""
        run(first) { sessionId = first.engine.createSession(TestRuntime.APP, null).id }
        val second = runtime(file = first.databaseFile)
        run(second) {
            val t = second.engine.submit(TestRuntime.APP, sessionId, TestRuntime.text("go"))
            second.engine.awaitTask(t.id)
            assertEquals(listOf("alarm_create", "event_create", "note_delete"), second.core!!.configs.last().tools.map { it.name }.sorted())
        }
        second.host.deleteDatabase()
    }

    @Test
    fun `strict policy - the same session has no tools, and the policy is read at use time so a restart with another policy changes it`() {
        val first = runtime(policy = StrictCallerPolicy)
        var sessionId = ""
        run(first) {
            sessionId = first.engine.createSession(TestRuntime.APP, null).id
            val t = first.engine.submit(TestRuntime.APP, sessionId, TestRuntime.text("go"))
            first.engine.awaitTask(t.id)
            assertEquals(emptyList(), first.core!!.configs.last().tools)
            assertTrue(first.host.tools.invocations.isEmpty())
        }
        val second = runtime(file = first.databaseFile, policy = OpenCallerPolicy)
        run(second) {
            val t = second.engine.submit(TestRuntime.APP, sessionId, TestRuntime.text("go"))
            second.engine.awaitTask(t.id)
            assertEquals(3, second.core!!.configs.last().tools.size)
        }
        second.host.deleteDatabase()
    }

    @Test
    fun `nothing in the runtime changes a session's scope after creation`() {
        val rt = runtime()
        run(rt) {
            val s = rt.engine.createSession(TestRuntime.APP, null, memoScope)
            repeat(2) {
                val t = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("go"))
                rt.engine.awaitTask(t.id)
            }
            rt.engine.cancel(TestRuntime.APP, s.id)
            assertEquals(ToolScope.normalize(memoScope), rt.engine.session(TestRuntime.APP, s.id).toolScope)
        }
        rt.host.deleteDatabase()
    }

    @Test
    fun `a damaged stored scope reads as no tools, not as no restriction - for every kind of caller and every policy`() {
        for (policy in listOf(OpenCallerPolicy, StrictCallerPolicy)) {
            val first = runtime(policy = policy)
            val ids = mutableMapOf<String, String>()
            run(first) {
                ids["app"] = first.engine.createSession(TestRuntime.APP, null, memoScope).id
                ids["self"] = first.engine.createSession(TestRuntime.SELF, null, memoScope).id
            }
            rawSql(first.databaseFile, "UPDATE sessions SET tool_scope = 'garbage'")
            val second = runtime(file = first.databaseFile, policy = policy)
            run(second) {
                for ((who, caller) in listOf("app" to TestRuntime.APP, "self" to TestRuntime.SELF)) {
                    val id = ids.getValue(who)
                    assertEquals(emptyList(), second.engine.session(caller, id).toolScope, "$who $policy")
                    val t = second.engine.submit(caller, id, TestRuntime.text("go"))
                    second.engine.awaitTask(t.id)
                    assertEquals(emptyList(), second.core!!.configs.last().tools, "$who $policy")
                }
                assertTrue(second.host.tools.invocations.isEmpty())
            }
            second.host.deleteDatabase()
        }
    }

    // ------------------------------------------------------------------ 迁移

    private fun rawSql(file: java.io.File, vararg statements: String): Long {
        val conn = BundledSQLiteDriver().open(file.absolutePath)
        try {
            statements.forEach { conn.execSQL(it) }
            return conn.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }
        } finally {
            conn.close()
        }
    }

    private fun userVersion(file: java.io.File): Long = rawSql(file)

    @Test
    fun `a version 1 database opens, moves up to the current version and its sessions keep working`() {
        val first = runtime()
        var selfSession = ""
        var appSession = ""
        run(first) {
            selfSession = first.engine.createSession(TestRuntime.SELF, null).id
            appSession = first.engine.createSession(TestRuntime.APP, null).id
        }
        assertEquals(3L, userVersion(first.databaseFile), "a new database is created at the current version")
        // take it back to what a version 1 phone has: no tool_scope column, no caller_package column, user_version 1
        rawSql(first.databaseFile, "ALTER TABLE sessions DROP COLUMN tool_scope", "ALTER TABLE tasks DROP COLUMN caller_package", "PRAGMA user_version = 1")
        assertEquals(1L, userVersion(first.databaseFile))

        val second = runtime(file = first.databaseFile)
        run(second) {
            assertNull(second.engine.session(TestRuntime.SELF, selfSession).toolScope)
            assertNull(second.engine.session(TestRuntime.APP, appSession).toolScope)
            val own = second.engine.submit(TestRuntime.SELF, selfSession, TestRuntime.text("go"))
            second.engine.awaitTask(own.id)
            assertEquals(listOf("alarm_create", "event_create", "note_delete"), second.core!!.configs.last().tools.map { it.name }.sorted(), "AgentOS's own old session keeps every tool")
            val app = second.engine.submit(TestRuntime.APP, appSession, TestRuntime.text("go"))
            second.engine.awaitTask(app.id)
            assertEquals(listOf("alarm_create", "event_create", "note_delete"), second.core!!.configs.last().tools.map { it.name }.sorted(), "an old third-party session has the whole catalog, like any session with no scope")
            // and new sessions can carry a scope
            assertEquals(ToolScope.normalize(memoScope), second.engine.createSession(TestRuntime.APP, null, memoScope).toolScope)
        }
        assertEquals(3L, userVersion(second.databaseFile))
        second.host.deleteDatabase()
    }

    @Test
    fun `migration runs once - opening a current database again changes nothing`() {
        val first = runtime()
        run(first) { first.engine.createSession(TestRuntime.APP, null, memoScope) }
        val second = runtime(file = first.databaseFile)
        run(second) { assertEquals(1, second.engine.storeForTesting.read { it.sessions.listByOwner(TestRuntime.APP.ownerKey) }.size) }
        assertEquals(3L, userVersion(second.databaseFile))
        second.host.deleteDatabase()
    }

    // ------------------------------------------------------------------ 自动选会话

    /** Jev that always picks the first existing session it is offered (never a new one). */
    private val pickFirst = JevProvider { req: JevRequest -> req.choices.first { it.id != JevProvider.NEW_SESSION }.id }

    private suspend fun seed(rt: TestRuntime, caller: CallerIdentity, scope: List<ToolRef>?): String {
        val s = rt.engine.createSession(caller, null, scope)
        rt.engine.awaitTask(rt.engine.submit(caller, s.id, TestRuntime.text("plan a trip to Kyoto")).id)
        return s.id
    }

    @Test
    fun `auto-select picks an existing session only when its scope is the same as the one asked for`() {
        val rt = runtime(pickFirst)
        run(rt) {
            val withScope = seed(rt, TestRuntime.APP, memoScope)

            val same = rt.engine.autoSelect(TestRuntime.APP, "what about hotels in Kyoto?", null, memoScope.reversed())
            assertEquals(false, same.created)
            assertEquals(withScope, same.session.id)

            val none = rt.engine.autoSelect(TestRuntime.APP, "what about hotels in Kyoto?", null, null)
            assertEquals(true, none.created, "asked for no scope: not the session that has one")
            assertNotEquals(withScope, none.session.id)
            assertNull(none.session.toolScope)

            val wider = rt.engine.autoSelect(TestRuntime.APP, "what about hotels in Kyoto?", null, memoScope + ToolRef("notes", "note_delete"))
            assertEquals(true, wider.created, "asked for a wider scope: a new session, never the narrower one with more rights")
            assertEquals(ToolScope.normalize(memoScope + ToolRef("notes", "note_delete")), wider.session.toolScope)

            val narrower = rt.engine.autoSelect(TestRuntime.APP, "what about hotels in Kyoto?", null, listOf(ToolRef("alarm", "alarm_create")))
            assertEquals(true, narrower.created)
            assertEquals(listOf(ToolRef("alarm", "alarm_create")), narrower.session.toolScope)
        }
        rt.host.deleteDatabase()
    }

    @Test
    fun `auto-select with no scope never lands in a session that has one, and the other way round`() {
        val rt = runtime(pickFirst)
        run(rt) {
            val plain = seed(rt, TestRuntime.SELF, null)
            val scoped = rt.engine.autoSelect(TestRuntime.SELF, "plan a trip", null, memoScope)
            assertEquals(true, scoped.created)
            assertNotEquals(plain, scoped.session.id)
            val again = rt.engine.autoSelect(TestRuntime.SELF, "plan a trip", null, null)
            assertEquals(plain, again.session.id, "no scope asked: the session with no scope")
        }
        rt.host.deleteDatabase()
    }
}
