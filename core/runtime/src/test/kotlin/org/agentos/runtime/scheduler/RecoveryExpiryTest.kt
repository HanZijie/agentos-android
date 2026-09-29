package org.agentos.runtime.scheduler

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.store.SessionState
import org.agentos.runtime.store.Store
import org.agentos.runtime.store.TaskRecord
import org.agentos.runtime.store.TaskState
import org.agentos.runtime.testing.FakeHostPort
import org.agentos.runtime.testing.FakeStep
import org.agentos.runtime.testing.FakeTurnScript
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * architecture F8 的过渡期限（整合人 2026-09-29 决定）：需要恢复的任务满 24 小时、或超过 50 条时，
 * 运行时启动时按“放弃”结束（task.recovery_resolved { reason: recovery_expired } → task.failed），不重放。
 */
class RecoveryExpiryTest {
    private val host = FakeHostPort()
    private val hour = 3_600_000L

    @AfterTest
    fun cleanup() = host.deleteDatabase()

    /** 造一个“上次运行中途中断、已标记为需要恢复”的任务，各自一个会话。 */
    private suspend fun Store.interrupted(n: Int): TaskRecord = write { tx ->
        val s = tx.sessions.create("ses_$n", TestRuntime.APP, null, tx.now)
        val t = tx.tasks.create("tsk_$n", s.id, TestRuntime.text("job $n"), TestRuntime.APP, null, tx.now, null)
        val started = tx.tasks.markStarted(t.id, tx.now, null)
        Scheduler.markRecoveryRequired(tx, started, "runtime_restarted", ErrorCode.TOOL_RESULT_UNKNOWN.info("interrupted"))
        tx.tasks.get(t.id)!!
    }

    private suspend fun Store.state(id: String) = read { it.tasks.get(id)!! }

    private suspend fun Store.sessionState(id: String) = read { it.sessions.get(id)!!.state }

    private suspend fun Store.events(task: TaskRecord) = read { it.events.readTask(task.sessionId, task.id) }

    @Test
    fun `a task waiting for recovery for 24 hours is abandoned at startup, a younger one is kept`() = runBlocking {
        val store = Store.open(host.storage, host.clock)
        val old = store.interrupted(1)
        host.clock.advance(23 * hour)
        val young = store.interrupted(2)
        host.clock.advance(1 * hour) // old：正好 24 小时；young：1 小时

        val result = Recovery.run(store, config = SchedulerConfig())
        assertEquals(1, result.expired)
        assertEquals(1, result.recoveryRequired)

        val gone = store.state(old.id)
        assertEquals(TaskState.FAILED, gone.state)
        assertEquals(ErrorCode.RECOVERY_EXPIRED, gone.error!!.code)
        assertEquals("age", gone.error!!.details!!["rule"]!!.jsonPrimitive.content)
        val types = store.events(old).map { it.eventType }
        assertEquals(listOf(EventTypes.TASK_RECOVERY_RESOLVED, EventTypes.TASK_FAILED), types.takeLast(2), "resolved, then the terminal event")
        val resolved = store.events(old).single { it.eventType == EventTypes.TASK_RECOVERY_RESOLVED }.payload
        assertEquals("abandon", resolved["decision"]!!.jsonPrimitive.content)
        assertEquals("system", resolved["by"]!!.jsonPrimitive.content)
        assertEquals("recovery_expired", resolved["reason"]!!.jsonPrimitive.content)
        assertEquals(SessionState.CREATED, store.sessionState(old.sessionId), "nothing left to decide: the session takes prompts again")

        assertEquals(TaskState.UNKNOWN, store.state(young.id).state)
        assertEquals(SessionState.PAUSED, store.sessionState(young.sessionId))
        assertTrue(store.events(young).none { it.eventType == EventTypes.TASK_RECOVERY_RESOLVED })

        val recovered = store.read { it.events.read(EventTypes.SYSTEM_STREAM) }.last { it.eventType == EventTypes.RUNTIME_RECOVERED }.payload
        assertEquals("1", recovered["expired"]!!.jsonPrimitive.content)
        assertEquals("1", recovered["recoveryRequired"]!!.jsonPrimitive.content)
    }

    @Test
    fun `beyond 50 waiting tasks the oldest are abandoned`() = runBlocking {
        val store = Store.open(host.storage, host.clock)
        val tasks = (1..53).map { n -> store.interrupted(n).also { host.clock.advance(60_000) } }

        val result = Recovery.run(store, config = SchedulerConfig())
        assertEquals(3, result.expired)
        assertEquals(50, result.recoveryRequired)
        tasks.take(3).forEach { t ->
            val r = store.state(t.id)
            assertEquals(TaskState.FAILED, r.state, "${t.id} is among the oldest")
            assertEquals(ErrorCode.RECOVERY_EXPIRED, r.error!!.code)
            assertEquals("limit", r.error!!.details!!["rule"]!!.jsonPrimitive.content)
        }
        tasks.drop(3).forEach { t -> assertEquals(TaskState.UNKNOWN, store.state(t.id).state, "${t.id} is kept") }

        // 上限可以注入
        val small = Recovery.run(store, config = SchedulerConfig(maxRecoveryPending = 10))
        assertEquals(40, small.expired)
        assertEquals(10, small.recoveryRequired)
        assertEquals(tasks.takeLast(10).map { it.id }.toSet(), store.read { it.tasks.listByState(TaskState.UNKNOWN) }.map { it.id }.toSet())
    }

    @Test
    fun `tasks within the deadline and the limit are left alone, startup after startup`() = runBlocking {
        val store = Store.open(host.storage, host.clock)
        val tasks = (1..5).map { store.interrupted(it) }
        host.clock.advance(24 * hour - 1)

        repeat(2) {
            val result = Recovery.run(store, config = SchedulerConfig())
            assertEquals(0, result.expired)
            assertEquals(5, result.recoveryRequired)
        }
        tasks.forEach { t ->
            assertEquals(TaskState.UNKNOWN, store.state(t.id).state)
            assertEquals(SessionState.PAUSED, store.sessionState(t.sessionId))
            assertTrue(store.events(t).none { it.eventType == EventTypes.TASK_RECOVERY_RESOLVED })
        }
        // 两个参数都设为 0：规则关闭
        host.clock.advance(365 * 24 * hour)
        assertEquals(0, Recovery.run(store, config = SchedulerConfig(recoveryExpiryMillis = 0, maxRecoveryPending = 0)).expired)
        assertEquals(5, store.read { it.tasks.listByState(TaskState.UNKNOWN) }.size)
    }

    @Test
    fun `end to end - an interrupted task expires on a later startup and its session works again`() = runBlocking {
        withTimeout(20_000) {
            val script = { ctx: org.agentos.runtime.testing.FakeTurnContext ->
                if (ctx.input.text == "block") FakeTurnScript(FakeStep.AwaitAbort) else FakeTurnScript(FakeStep.Text("ok"))
            }
            val first = TestRuntime(script).start()
            val s = first.engine.createSession(TestRuntime.APP, null)
            val blocked = first.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("block"))
            first.awaitEvent(s.id) { it.eventType == EventTypes.AGENT_START }
            first.stop()

            // 第二次启动：还没到期限，任务等决定，会话暂停
            val config = SchedulerConfig(tickMillis = 20, recoveryExpiryMillis = 10 * 60_000L)
            val second = TestRuntime(script, databaseFile = first.databaseFile, host = FakeHostPort(databaseFile = first.databaseFile), schedulerConfig = config).start()
            assertEquals(TaskState.UNKNOWN, second.engine.task(blocked.id)!!.state)
            assertEquals(1, second.engine.runState.value.recoveryPending)
            second.stop()

            // 第三次启动：过了注入的期限（10 分钟），按放弃结束，会话恢复，新 prompt 照常执行；中断的那一轮不重放
            val thirdHost = FakeHostPort(databaseFile = first.databaseFile).apply { clock.advance(10 * 60_000L) }
            val third = TestRuntime(script, databaseFile = first.databaseFile, host = thirdHost, schedulerConfig = config).start()
            val expired = third.engine.task(blocked.id)!!
            assertEquals(TaskState.FAILED, expired.state)
            assertEquals(ErrorCode.RECOVERY_EXPIRED, expired.error!!.code)
            assertEquals(0, third.engine.runState.value.recoveryPending)
            val next = third.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("hello again"))
            assertEquals(TaskState.COMPLETED, third.engine.awaitTask(next.id).state)
            assertEquals(1, third.turnsStarted(), "only the new prompt ran")
            third.stop()
            first.host.deleteDatabase()
        }
    }
}
