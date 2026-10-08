package org.agentos.runtime.scheduler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.RuntimeConfig
import org.agentos.runtime.errors.AgentOsException
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.ConsentRequest
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.agentos.runtime.quota.CallerQuotaConfig
import org.agentos.runtime.quota.PromptOutcome
import org.agentos.runtime.quota.PromptUsage
import org.agentos.runtime.store.TaskState
import org.agentos.runtime.testing.FakeConsentPort
import org.agentos.runtime.testing.FakeHostPort
import org.agentos.runtime.testing.FakeStep
import org.agentos.runtime.testing.FakeTurnContext
import org.agentos.runtime.testing.FakeTurnScript
import org.agentos.runtime.testing.TestRuntime
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `RuntimeEngine.cancelOwner` (revoking a third-party app): closing its channel does not stop its task (F7), so the app's running task would go on
 * holding its only prompt slot and spending the user's model quota. Everything of one owner is cancelled through the same path as `session/cancel`,
 * nobody else is touched, and the slot is free when the call returns.
 */
class CancelOwnerTest {
    private val appA = TestRuntime.APP
    private val appB = TestRuntime.OTHER_APP
    private val self = TestRuntime.SELF
    private val desktop = TestRuntime.DESKTOP

    /** "block" runs until it is aborted; anything else answers at once. */
    private val script = { ctx: FakeTurnContext ->
        if (ctx.input.text == "block") FakeTurnScript(FakeStep.AwaitAbort) else FakeTurnScript(FakeStep.Text("ok"))
    }

    private fun runtime(quota: CallerQuotaConfig = CallerQuotaConfig(), perOwner: Int = 2): TestRuntime =
        TestRuntime(script, config = RuntimeConfig(scheduler = SchedulerConfig(maxRunningSessions = 12, maxRunningPerOwner = perOwner, tickMillis = 20), quota = quota))

    private fun <T> run(rt: TestRuntime, block: suspend (TestRuntime) -> T) = runBlocking {
        rt.start()
        try {
            withTimeout(20_000) { block(rt) }
        } finally {
            rt.stop()
            rt.host.deleteDatabase()
        }
        Unit
    }

    private data class Started(val sessionId: String, val taskId: String)

    /** A session of [caller] with a task that is running (it started and waits to be aborted). */
    private suspend fun TestRuntime.running(caller: CallerIdentity): Started {
        val s = engine.createSession(caller, null)
        val t = engine.submit(caller, s.id, TestRuntime.text("block"))
        awaitEvent(s.id) { it.taskId == t.id && it.eventType == EventTypes.AGENT_START }
        return Started(s.id, t.id)
    }

    private suspend fun TestRuntime.state(taskId: String) = engine.task(taskId)!!.state

    // ------------------------------------------------------------------ only that owner, and the slot is free afterwards

    @Test
    fun `two apps each with a running task - cancelOwner(A) cancels only A's, A's slot is free and A can submit again`() {
        val rt = runtime()
        run(rt) {
            val a = rt.running(appA)
            val b = rt.running(appB)
            val s = rt.running(self)
            val d = rt.running(desktop)

            // before: A has used its one prompt slot
            assertEquals(1, rt.engine.quota.usage(appA).activePrompts)
            val sessionA2 = rt.engine.createSession(appA, null)
            val busy = assertFailsWith<AgentOsException> { rt.engine.submit(appA, sessionA2.id, TestRuntime.text("again")) }
            assertEquals(ErrorCode.QUOTA_EXCEEDED, busy.info.code)

            val cancelled = rt.engine.cancelOwner(appA, "revoked")
            assertEquals(listOf(a.taskId), cancelled)

            // A: cancelled, and its slot is free right now
            assertEquals(TaskState.CANCELLED, rt.state(a.taskId))
            assertEquals(0, rt.engine.quota.usage(appA).activePrompts, "the slot is released when cancelOwner returns")
            // everybody else keeps running
            delay(200)
            for ((who, t) in listOf("B" to b, "SELF" to s, "DESKTOP" to d)) assertEquals(TaskState.RUNNING, rt.state(t.taskId), who)
            assertEquals(1, rt.engine.quota.usage(appB).activePrompts, "B's slot is still taken")

            // A can submit again and it works
            val again = rt.engine.submit(appA, sessionA2.id, TestRuntime.text("again"))
            assertEquals(TaskState.COMPLETED, rt.engine.awaitTask(again.id).state)

            // the log says who asked and why, only on A's task
            val requested = rt.engine.readEvents(a.sessionId).single { it.eventType == EventTypes.TASK_CANCEL_REQUESTED }
            assertEquals("revoked", requested.payload["by"]!!.jsonPrimitive.content)
            for (t in listOf(b, s, d)) assertTrue(rt.engine.readEvents(t.sessionId).none { it.eventType == EventTypes.TASK_CANCEL_REQUESTED }, t.sessionId)

            for (t in listOf(b, s, d)) rt.engine.cancel(self, t.sessionId) // let the others go
        }
    }

    @Test
    fun `an owner with several sessions, running and queued tasks - all of them are cancelled and the sessions go back to created`() {
        val rt = runtime(quota = TestRuntime.UNLIMITED_QUOTA, perOwner = 2)
        run(rt) {
            val s1 = rt.running(appA)
            val q1 = rt.engine.submit(appA, s1.sessionId, TestRuntime.text("queued behind the running one"))
            val s2 = rt.running(appA)
            val s3 = rt.engine.createSession(appA, null)
            val q3 = rt.engine.submit(appA, s3.id, TestRuntime.text("block")) // two of A's sessions are running already: this one waits
            val other = rt.running(appB)
            assertEquals(TaskState.QUEUED, rt.state(q1.id))
            assertEquals(TaskState.QUEUED, rt.state(q3.id))

            val cancelled = rt.engine.cancelOwner(appA, "revoked")

            assertEquals(setOf(s1.taskId, q1.id, s2.taskId, q3.id), cancelled.toSet())
            assertEquals(4, cancelled.size, "each once")
            for (id in cancelled) assertEquals(TaskState.CANCELLED, rt.state(id), id)
            assertEquals(emptyList(), rt.engine.cancelOwner(appA, "revoked"), "nothing left")
            for (sid in listOf(s1.sessionId, s2.sessionId, s3.id)) {
                assertEquals(org.agentos.runtime.store.SessionState.CREATED, rt.engine.session(appA, sid).state, sid)
                val by = rt.engine.readEvents(sid).filter { it.eventType == EventTypes.TASK_CANCEL_REQUESTED }.map { it.payload["by"]!!.jsonPrimitive.content }
                assertTrue(by.isNotEmpty() && by.all { it == "revoked" }, "$sid: $by")
            }
            // another owner is untouched, and the cancelled owner can start a fresh session
            assertEquals(TaskState.RUNNING, rt.state(other.taskId))
            val fresh = rt.engine.createSession(appA, null)
            assertEquals(TaskState.COMPLETED, rt.engine.awaitTask(rt.engine.submit(appA, fresh.id, TestRuntime.text("hello")).id).state)
            rt.engine.cancel(self, other.sessionId)
        }
    }

    @Test
    fun `AgentOS itself and the desktop are not touched by cancelling an app, and an app identity cannot reach their sessions`() {
        val rt = runtime(quota = TestRuntime.UNLIMITED_QUOTA)
        run(rt) {
            val s = rt.running(self)
            val d = rt.running(desktop)
            val a = rt.running(appA)

            // an app that shares nothing with them: its owner key is its own
            assertTrue(appA.ownerKey != self.ownerKey && appA.ownerKey != desktop.ownerKey)
            assertEquals(listOf(a.taskId), rt.engine.cancelOwner(appA, "revoked"))
            delay(200)
            assertEquals(TaskState.RUNNING, rt.state(s.taskId))
            assertEquals(TaskState.RUNNING, rt.state(d.taskId))

            // the package and the label do not matter, only the owner (the uid): a different app with the same uid is the same owner
            val sameUid = CallerIdentity(appA.uid, CallerKind.APP, "Another name", "org.other")
            assertEquals(appA.ownerKey, sameUid.ownerKey)

            rt.engine.cancel(self, s.sessionId)
            rt.engine.cancel(self, d.sessionId)
        }
    }

    // ------------------------------------------------------------------ idempotent

    @Test
    fun `nothing running - an empty list, any number of times, also for an owner the runtime has never seen`() {
        val rt = runtime()
        run(rt) {
            assertEquals(emptyList(), rt.engine.cancelOwner(appA, "revoked"))
            assertEquals(emptyList(), rt.engine.cancelOwner(CallerIdentity(10_999, CallerKind.APP, "Never seen", "org.never"), "revoked"))
            val s = rt.engine.createSession(appA, null)
            val done = rt.engine.submit(appA, s.id, TestRuntime.text("hello"))
            assertEquals(TaskState.COMPLETED, rt.engine.awaitTask(done.id).state)
            assertEquals(emptyList(), rt.engine.cancelOwner(appA, "revoked"), "a finished task is not touched")
            assertEquals(TaskState.COMPLETED, rt.state(done.id))
            assertTrue(rt.engine.readEvents(s.id).none { it.eventType == EventTypes.TASK_CANCEL_REQUESTED })
        }
    }

    @Test
    fun `cancelling twice in a row - the second call finds nothing, and does not report the task again`() {
        val rt = runtime()
        run(rt) {
            val a = rt.running(appA)
            assertEquals(listOf(a.taskId), rt.engine.cancelOwner(appA, "revoked"))
            assertEquals(emptyList(), rt.engine.cancelOwner(appA, "revoked"))
            assertEquals(1, rt.engine.readEvents(a.sessionId).count { it.eventType == EventTypes.TASK_CANCEL_REQUESTED })
            assertEquals(1, rt.engine.readEvents(a.sessionId).count { it.eventType == EventTypes.TASK_CANCELLED })
        }
    }

    // ------------------------------------------------------------------ the quota slot and the usage count

    @Test
    fun `the usage listener hears once about the cancelled prompt, as cancelled`() {
        val rt = runtime()
        val heard = Collections.synchronizedList(mutableListOf<PromptUsage>())
        rt.engine.quota.addListener { heard += it }
        run(rt) {
            val a = rt.running(appA)
            rt.engine.cancelOwner(appA, "revoked")
            rt.until { heard.size == 1 }
            delay(100)
            assertEquals(1, heard.size, "exactly once")
            assertEquals(PromptOutcome.CANCELLED, heard.single().outcome)
            assertEquals(appA, heard.single().caller)
            assertEquals(TaskState.CANCELLED, rt.state(a.taskId))
        }
    }

    @Test
    fun `with waitMillis = 0 the call returns at once, and the slot is free as soon as the task has really ended`() {
        val rt = runtime()
        run(rt) {
            val a = rt.running(appA)
            assertEquals(listOf(a.taskId), rt.engine.cancelOwner(appA, "revoked", waitMillis = 0))
            assertEquals(TaskState.CANCELLED, rt.engine.awaitTask(a.taskId).state)
            rt.until { rt.engine.quota.usage(appA).activePrompts == 0 }
            val s = rt.engine.createSession(appA, null)
            assertEquals(TaskState.COMPLETED, rt.engine.awaitTask(rt.engine.submit(appA, s.id, TestRuntime.text("hello")).id).state)
        }
    }

    @Test
    fun `a task that does not stop within the wait keeps its slot - it is still spending the quota`() {
        val stuck = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val host = FakeHostPort()
        host.tools.register("stuck_tool", ToolRisk.READ) {
            stuck.complete(Unit)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
            org.agentos.runtime.ports.ToolInvocationResult.Completed(ToolResult.text("late"))
        }
        val rt = TestRuntime(
            { FakeTurnScript(listOf(listOf(FakeStep.ToolUse("stuck_tool", buildJsonObject { }, id = "c1")), listOf(FakeStep.Text("done")))) },
            host = host,
            config = RuntimeConfig(scheduler = SchedulerConfig(tickMillis = 20, cancelGraceMillis = 30_000)),
        )
        run(rt) {
            val s = rt.engine.createSession(appA, null)
            val t = rt.engine.submit(appA, s.id, TestRuntime.text("go"))
            stuck.await()
            assertEquals(listOf(t.id), rt.engine.cancelOwner(appA, "revoked", waitMillis = 300))
            assertEquals(1, rt.engine.quota.usage(appA).activePrompts, "the tool call cannot be interrupted: the task is still running")
            release.complete(Unit)
            assertEquals(TaskState.CANCELLED, rt.engine.awaitTask(t.id).state)
            rt.until { rt.engine.quota.usage(appA).activePrompts == 0 }
        }
    }

    // ------------------------------------------------------------------ a confirmation that is open

    @Test
    fun `a cancel during a pending consent withdraws the card, the tool is not called, the task is cancelled`() {
        val withdrawn = CompletableDeferred<Unit>()
        val asked = CompletableDeferred<ConsentRequest>()
        val consent = FakeConsentPort {
            asked.complete(it)
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                withdrawn.complete(Unit)
                throw e
            }
        }
        val host = FakeHostPort(consent = consent)
        host.tools.registerSimple("write_note", ToolRisk.WRITE, ToolSource("notes", "main", "write_note")) { ToolResult.text("saved") }
        val rt = TestRuntime(
            { FakeTurnScript(listOf(listOf(FakeStep.ToolUse("write_note", buildJsonObject { put("t", "x") }, id = "call_1")), listOf(FakeStep.Text("done")))) },
            host = host,
        )
        run(rt) {
            val s = rt.engine.createSession(appA, null)
            val t = rt.engine.submit(appA, s.id, TestRuntime.text("go"))
            val card = asked.await()
            assertEquals(appA, card.caller)

            assertEquals(listOf(t.id), rt.engine.cancelOwner(appA, "revoked"))

            withdrawn.await()
            assertEquals(TaskState.CANCELLED, rt.state(t.id))
            assertTrue(host.tools.invocations.isEmpty(), "the tool was never called")
            val events = rt.engine.readEvents(s.id)
            val resolved = events.single { it.eventType == EventTypes.CONSENT_RESOLVED }
            assertEquals("client", resolved.payload["reason"]!!.jsonPrimitive.content, "the same path as session/cancel")
            assertEquals("revoked", events.single { it.eventType == EventTypes.TASK_CANCEL_REQUESTED }.payload["by"]!!.jsonPrimitive.content)
            assertTrue(events.none { it.eventType == EventTypes.TOOL_DISPATCHED })
            assertEquals(0, rt.engine.quota.usage(appA).activePrompts)
        }
    }
}
