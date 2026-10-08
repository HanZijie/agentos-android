package org.agentos.runtime.scheduler

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.errors.AgentOsException
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.ports.SafeModeState
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.store.SessionState
import org.agentos.runtime.store.TaskState
import org.agentos.runtime.store.ToolCallState
import org.agentos.runtime.testing.FakeHostPort
import org.agentos.runtime.testing.FakeStep
import org.agentos.runtime.testing.FakeTurnScript
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** W2 验收项 2：恢复时不重放结果未知的调用（architecture F8，session-scheduling.md 第 6 节）。 */
class RecoveryTest {

    private val chargeScript = FakeTurnScript(
        listOf(
            listOf(FakeStep.Text("charging"), FakeStep.ToolUse("charge_card", buildJsonObject { put("amount", 42) }, id = "call_charge")),
            listOf(FakeStep.Text("charged")),
        ),
    )

    private fun scripts() = { ctx: org.agentos.runtime.testing.FakeTurnContext ->
        if (ctx.input.text.startsWith("pay")) chargeScript else FakeTurnScript(FakeStep.Text("ok: ${ctx.input.text}"))
    }

    @Test
    fun `a task interrupted during a tool call is fenced as unknown and never replayed`() = runBlocking {
        withTimeout(20_000) {
            // ---- 第一次运行：工具已派发、还没返回时，进程“死掉”
            val invoked = CompletableDeferred<Unit>()
            val first = TestRuntime(scripts(), schedulerConfig = SchedulerConfig(maxRunningSessions = 1, tickMillis = 20))
            first.host.tools.register("charge_card", ToolRisk.READ) {
                invoked.complete(Unit)
                delay(Long.MAX_VALUE)
                error("unreachable")
            }
            first.start()
            val e = first.engine
            val paying = e.createSession(TestRuntime.APP, null)
            val other = e.createSession(TestRuntime.OTHER_APP, null)
            val payTask = e.submit(TestRuntime.APP, paying.id, TestRuntime.text("pay 42"))
            val followUp = e.submit(TestRuntime.APP, paying.id, TestRuntime.text("thanks"))
            val otherTask = e.submit(TestRuntime.OTHER_APP, other.id, TestRuntime.text("hello")) // maxRunningSessions = 1：排队
            invoked.await()
            first.awaitEvent(paying.id) { it.eventType == EventTypes.TOOL_DISPATCHED }
            assertEquals(1, first.host.tools.invocations.size)
            first.stop()

            // ---- 第二次运行：同一个数据库，新的进程
            val second = TestRuntime(scripts(), databaseFile = first.databaseFile, host = FakeHostPort(databaseFile = first.databaseFile))
            var replays = 0
            second.host.tools.register("charge_card", ToolRisk.READ) {
                replays++
                ToolInvocationResult.Completed(ToolResult.text("charged again"))
            }
            second.start()
            val e2 = second.engine

            val fenced = e2.task(payTask.id)!!
            assertEquals(TaskState.UNKNOWN, fenced.state)
            val recovery = e2.readEvents(paying.id).single { it.eventType == EventTypes.TASK_RECOVERY_REQUIRED }
            assertEquals("runtime_restarted", recovery.payload["reason"]!!.jsonPrimitive.content)
            val unknownCalls = recovery.payload["unknownToolCalls"]!!.jsonArray.map { it.jsonObject["toolCallId"]!!.jsonPrimitive.content }
            assertEquals(listOf("call_charge"), unknownCalls)
            assertEquals(SessionState.PAUSED, e2.session(TestRuntime.APP, paying.id).state)

            // 另一个会话排队的任务照常执行
            assertEquals(TaskState.COMPLETED, e2.awaitTask(otherTask.id).state)
            delay(200)
            assertEquals(0, replays, "the unknown tool call is not replayed")
            assertEquals(1, second.turnsStarted(), "only the other session's queued task ran")
            assertEquals(TaskState.QUEUED, e2.task(followUp.id)!!.state, "the paused session does not run its queue")
            val err = assertFailsWith<AgentOsException> { e2.submit(TestRuntime.APP, paying.id, TestRuntime.text("again")) }
            assertEquals(ErrorCode.RECOVERY_REQUIRED, err.info.code)
            val sys = e2.readEvents(EventTypes.SYSTEM_STREAM).last { it.eventType == EventTypes.RUNTIME_RECOVERED }
            assertEquals(1, sys.payload["interrupted"]!!.jsonPrimitive.content.toInt())

            // ---- 用户放弃：任务失败（abandoned），会话恢复，排队的任务继续；工具仍然没有被重放
            e2.abandonRecovery(TestRuntime.APP, paying.id, payTask.id)
            val abandoned = e2.task(payTask.id)!!
            assertEquals(TaskState.FAILED, abandoned.state)
            assertEquals(ErrorCode.ABANDONED, abandoned.error!!.code)
            assertEquals(TaskState.COMPLETED, e2.awaitTask(followUp.id).state)
            assertEquals(0, replays)
            val events = e2.readEvents(paying.id).map { it.eventType }
            assertTrue(events.indexOf(EventTypes.TASK_RECOVERY_RESOLVED) < events.lastIndexOf(EventTypes.TASK_STARTED))
            val toolStates = e2.storeForTesting.read { it.tasks.toolCalls(payTask.id) }.map { it.state }
            assertEquals(listOf(ToolCallState.UNKNOWN), toolStates)
            second.stop()
            first.host.deleteDatabase()
        }
    }

    @Test
    fun `after a restart in safe mode nothing runs until safe mode ends, then queued tasks run once`() = runBlocking {
        withTimeout(20_000) {
            val script = { ctx: org.agentos.runtime.testing.FakeTurnContext ->
                if (ctx.input.text == "block") FakeTurnScript(FakeStep.AwaitAbort) else FakeTurnScript(FakeStep.Text("ok"))
            }
            val first = TestRuntime(script, schedulerConfig = SchedulerConfig(maxRunningSessions = 1, tickMillis = 20))
            first.start()
            val a = first.engine.createSession(TestRuntime.APP, null)
            val b = first.engine.createSession(TestRuntime.OTHER_APP, null)
            val blocked = first.engine.submit(TestRuntime.APP, a.id, TestRuntime.text("block"))
            first.awaitEvent(a.id) { it.eventType == EventTypes.AGENT_START }
            val queued = first.engine.submit(TestRuntime.OTHER_APP, b.id, TestRuntime.text("later"))
            first.stop()

            val second = TestRuntime(script, databaseFile = first.databaseFile, host = FakeHostPort(databaseFile = first.databaseFile))
            second.host.safeMode.value = SafeModeState(true, "crash_loop")
            second.start()
            delay(200)
            assertEquals(0, second.turnsStarted(), "safe mode: recovered tasks are not continued")
            assertEquals(TaskState.QUEUED, second.engine.task(queued.id)!!.state)
            assertEquals(TaskState.UNKNOWN, second.engine.task(blocked.id)!!.state)
            val err = assertFailsWith<AgentOsException> { second.engine.submit(TestRuntime.OTHER_APP, b.id, TestRuntime.text("x")) }
            assertEquals(ErrorCode.SAFE_MODE, err.info.code)

            second.host.safeMode.value = SafeModeState.OFF
            assertEquals(TaskState.COMPLETED, second.engine.awaitTask(queued.id).state)
            delay(100)
            assertEquals(1, second.turnsStarted(), "the queued task ran exactly once; the unknown one was not replayed")
            second.stop()
            first.host.deleteDatabase()
        }
    }
}
