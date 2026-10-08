package org.agentos.runtime.store

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.RuntimeConfig
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.agentos.runtime.scheduler.SchedulerConfig
import org.agentos.runtime.testing.FakeHostPort
import org.agentos.runtime.testing.FakeScripts
import org.agentos.runtime.testing.FakeStep
import org.agentos.runtime.testing.FakeTurnContext
import org.agentos.runtime.testing.FakeTurnScript
import org.agentos.runtime.testing.TestRuntime
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The package of a third-party app travels with its task: it is on the confirmation request (so the card can name it), in the audit events,
 * in the database (the dialog is rebuilt from the task record when the task starts, also after a restart), and a database from before the
 * column existed still opens.
 */
class CallerPackageStoreTest {
    private val notes = CallerIdentity(10_321, CallerKind.APP, "Notes", "org.agentos.sample.notes")
    private val source = ToolSource("notes", "main", "note_create")

    private val writeThenDone = FakeTurnScript(
        listOf(listOf(FakeStep.ToolUse("note_create", buildJsonObject { put("title", "x") }, id = "c1")), listOf(FakeStep.Text("done"))),
    )

    private fun runtime(file: File? = null, script: (FakeTurnContext) -> FakeTurnScript = FakeScripts.always(writeThenDone), scheduler: SchedulerConfig = SchedulerConfig(tickMillis = 20)): TestRuntime {
        val config = RuntimeConfig(scheduler = scheduler, quota = TestRuntime.UNLIMITED_QUOTA)
        val rt = if (file == null) TestRuntime(script, config = config) else TestRuntime(script, databaseFile = file, host = FakeHostPort(databaseFile = file), config = config)
        rt.host.tools.registerSimple("note_create", ToolRisk.WRITE, source) { ToolResult.text("created") }
        return rt
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

    private suspend fun TestRuntime.oneTurn(caller: CallerIdentity): Pair<TaskRecord, List<org.agentos.runtime.events.EventEnvelope>> {
        val s = engine.createSession(caller, null)
        val t = engine.awaitTask(engine.submit(caller, s.id, TestRuntime.text("go")).id)
        return t to engine.readEvents(s.id)
    }

    // ------------------------------------------------------------------ the request, the events, the record

    @Test
    fun `the confirmation request of a third-party app carries its package, and the label it gave itself is still the label`() {
        val rt = runtime()
        run(rt) {
            rt.oneTurn(notes)
            val request = rt.host.consent.requests.single()
            assertEquals(notes, request.caller)
            assertEquals("org.agentos.sample.notes", request.caller.packageName)
            assertEquals("Notes", request.caller.label)
        }
        rt.host.deleteDatabase()
    }

    @Test
    fun `the audit events name the real package of a third-party app`() {
        val rt = runtime()
        run(rt) {
            val (_, events) = rt.oneTurn(notes)
            val requested = events.single { it.eventType == EventTypes.CONSENT_REQUESTED }
            assertEquals("org.agentos.sample.notes", requested.payload["callerPackage"]!!.jsonPrimitive.content)
            assertEquals("10321", requested.payload["callerUid"]!!.jsonPrimitive.content, "what was recorded before is still there")
            val queued = events.single { it.eventType == EventTypes.TASK_QUEUED }
            val caller = queued.payload["caller"]!!.jsonObject
            assertEquals("org.agentos.sample.notes", caller["package"]!!.jsonPrimitive.content)
            assertEquals("app", caller["kind"]!!.jsonPrimitive.content)
            assertEquals("10321", caller["uid"]!!.jsonPrimitive.content)
            assertTrue(events.none { "Notes" in it.payload.toString() && it.eventType == EventTypes.CONSENT_REQUESTED }, "the label is not copied into the consent event")
        }
        rt.host.deleteDatabase()
    }

    @Test
    fun `the task record keeps the package, next to the label`() {
        val rt = runtime()
        run(rt) {
            val (task, _) = rt.oneTurn(notes)
            val stored = rt.engine.task(task.id)!!
            assertEquals("org.agentos.sample.notes", stored.callerPackage)
            assertEquals("Notes", stored.callerLabel)
        }
        rt.host.deleteDatabase()
    }

    @Test
    fun `a third-party app without a package has none in the request, the events or the record, as before`() {
        val rt = runtime()
        run(rt) {
            val (task, events) = rt.oneTurn(CallerIdentity(10_322, CallerKind.APP, "Old Style"))
            assertNull(rt.host.consent.requests.single().caller.packageName)
            assertNull(events.single { it.eventType == EventTypes.CONSENT_REQUESTED }.payload["callerPackage"])
            assertNull(events.single { it.eventType == EventTypes.TASK_QUEUED }.payload["caller"]!!.jsonObject["package"])
            assertNull(rt.engine.task(task.id)!!.callerPackage)
        }
        rt.host.deleteDatabase()
    }

    @Test
    fun `contrast - AgentOS itself and the desktop never get a package stored, requested or logged, even if one is set on the identity`() {
        for (kind in listOf(CallerKind.SELF, CallerKind.DESKTOP)) {
            val rt = runtime()
            run(rt) {
                val caller = CallerIdentity(if (kind == CallerKind.SELF) 10_001 else 2_000, kind, "x", "org.attacker.fake")
                val (task, events) = rt.oneTurn(caller)
                assertNull(rt.engine.task(task.id)!!.callerPackage, kind.name)
                assertNull(events.single { it.eventType == EventTypes.CONSENT_REQUESTED }.payload["callerPackage"], kind.name)
                assertNull(events.single { it.eventType == EventTypes.TASK_QUEUED }.payload["caller"]!!.jsonObject["package"], kind.name)
                assertNull(rt.host.consent.requests.single().caller.packageName, "the identity is rebuilt from the record: no package for $kind")
            }
            rt.host.deleteDatabase()
        }
    }

    @Test
    fun `a hostile package is cleaned in the audit events`() {
        val rt = runtime()
        run(rt) {
            val (_, events) = rt.oneTurn(CallerIdentity(10_323, CallerKind.APP, "x", "org.x.\u202Enotes\n\u200B.app"))
            assertEquals("org.x.notes .app", events.single { it.eventType == EventTypes.CONSENT_REQUESTED }.payload["callerPackage"]!!.jsonPrimitive.content)
        }
        rt.host.deleteDatabase()
    }

    @Test
    fun `the package does not change who owns a session`() {
        val rt = runtime()
        run(rt) {
            val sameUidOtherName = CallerIdentity(notes.uid, CallerKind.APP, "Other name", "org.other")
            assertEquals(notes.ownerKey, sameUidOtherName.ownerKey)
            val s = rt.engine.createSession(notes, null)
            assertEquals(s.id, rt.engine.session(sameUidOtherName, s.id).id, "sessions are still isolated by uid")
        }
        rt.host.deleteDatabase()
    }

    // ------------------------------------------------------------------ restart: a queued task still names its app

    @Test
    fun `a task that was queued when the runtime stopped asks with the package of its app after the restart`() {
        val script = { ctx: FakeTurnContext ->
            when (ctx.input.text) {
                "block" -> FakeTurnScript(FakeStep.AwaitAbort)
                else -> writeThenDone
            }
        }
        val first = runtime(script = script, scheduler = SchedulerConfig(maxRunningSessions = 1, tickMillis = 20))
        var queuedId = ""
        run(first) {
            val a = first.engine.createSession(TestRuntime.SELF, null)
            first.engine.submit(TestRuntime.SELF, a.id, TestRuntime.text("block"))
            first.awaitEvent(a.id) { it.eventType == EventTypes.AGENT_START }
            val b = first.engine.createSession(notes, null)
            queuedId = first.engine.submit(notes, b.id, TestRuntime.text("later")).id
            assertEquals(TaskState.QUEUED, first.engine.task(queuedId)!!.state)
        }
        val second = runtime(file = first.databaseFile, script = script)
        run(second) {
            assertEquals(TaskState.COMPLETED, second.engine.awaitTask(queuedId).state)
            val request = second.host.consent.requests.single()
            assertEquals("org.agentos.sample.notes", request.caller.packageName, "the card after a restart still names the package")
            assertEquals("Notes", request.caller.label)
        }
        second.host.deleteDatabase()
    }

    // ------------------------------------------------------------------ migration

    private fun rawSql(file: File, vararg statements: String): Long {
        val conn = BundledSQLiteDriver().open(file.absolutePath)
        try {
            statements.forEach { conn.execSQL(it) }
            return conn.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }
        } finally {
            conn.close()
        }
    }

    @Test
    fun `a version 2 database opens, moves up to the current version, and its old tasks have no package`() {
        val first = runtime()
        var oldTask = ""
        run(first) { oldTask = first.oneTurn(notes).first.id }
        assertEquals(4L, rawSql(first.databaseFile), "a new database is created at the current version")
        rawSql(first.databaseFile, "ALTER TABLE tasks DROP COLUMN caller_package", "ALTER TABLE sessions DROP COLUMN model_id", "ALTER TABLE sessions DROP COLUMN mode", "PRAGMA user_version = 2")
        assertEquals(2L, rawSql(first.databaseFile))

        val second = runtime(file = first.databaseFile)
        run(second) {
            val old = assertNotNull(second.engine.task(oldTask))
            assertNull(old.callerPackage, "queued before v3: no package known")
            assertEquals("Notes", old.callerLabel)
            val (fresh, _) = second.oneTurn(notes)
            assertEquals("org.agentos.sample.notes", second.engine.task(fresh.id)!!.callerPackage)
        }
        assertEquals(4L, rawSql(second.databaseFile))
        second.host.deleteDatabase()
    }

    @Test
    fun `a version 1 database goes through both steps`() {
        val first = runtime()
        run(first) { first.oneTurn(TestRuntime.SELF) }
        rawSql(first.databaseFile, "ALTER TABLE tasks DROP COLUMN caller_package", "ALTER TABLE sessions DROP COLUMN tool_scope", "ALTER TABLE sessions DROP COLUMN model_id", "ALTER TABLE sessions DROP COLUMN mode", "PRAGMA user_version = 1")
        val second = runtime(file = first.databaseFile)
        run(second) {
            val (task, _) = second.oneTurn(notes)
            assertEquals("org.agentos.sample.notes", second.engine.task(task.id)!!.callerPackage)
            assertNull(second.engine.session(notes, second.engine.storeForTesting.read { it.tasks.get(task.id)!!.sessionId }).toolScope)
        }
        assertEquals(4L, rawSql(second.databaseFile))
        second.host.deleteDatabase()
    }
}
