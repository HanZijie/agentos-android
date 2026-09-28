@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.runtime

import com.agentclientprotocol.model.StopReason
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.agentos.runtime.acp.AcpPair
import org.agentos.runtime.acp.response
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.scheduler.SchedulerConfig
import org.agentos.runtime.store.TaskState
import org.agentos.runtime.testing.FakeHostPort
import org.agentos.runtime.testing.FakeStep
import org.agentos.runtime.testing.FakeTurnContext
import org.agentos.runtime.testing.FakeTurnScript
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 启动与恢复期间的行为（W6 接线的三条要求，session-scheduling.md 第 7 节）：
 * 1. 上一个进程被用户主动停止时，恢复出来的任务不继续；
 * 2. 恢复期间收到的 prompt，一收到就计入 runState；
 * 3. serveAcp 立即返回，可以在 start() 完成之前调用。
 */
class RuntimeStartTest {
    private val script = { ctx: FakeTurnContext ->
        if (ctx.input.text == "block") FakeTurnScript(FakeStep.AwaitAbort) else FakeTurnScript(FakeStep.Text("ok"))
    }

    @Test
    fun `a prompt received before recovery finishes counts in runState immediately`() = runBlocking {
        withTimeout(15_000) {
            // 先准备一个已有会话的数据库
            val first = TestRuntime(script).start()
            val s = first.engine.createSession(TestRuntime.APP, null)
            first.stop()

            val second = TestRuntime(script, databaseFile = first.databaseFile, host = FakeHostPort(databaseFile = first.databaseFile))
            assertFalse(second.engine.runState.value.busy)
            // 运行时还没 start（等价于恢复还没结束）：prompt 到达，提交在等就绪
            val submit = async(start = CoroutineStart.UNDISPATCHED) {
                second.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("hello"))
            }
            assertEquals(1, second.engine.runState.value.queuedTasks, "counted at receipt, before recovery ends")
            assertTrue(second.engine.runState.value.busy)

            second.start()
            val task = submit.await()
            assertEquals(TaskState.COMPLETED, second.engine.awaitTask(task.id).state)
            second.until { !second.engine.runState.value.busy }
            second.stop()
            first.host.deleteDatabase()
        }
    }

    @Test
    fun `runState never drops to idle between receiving a prompt and the scheduler taking it`() = runBlocking {
        withTimeout(15_000) {
            val rt = TestRuntime(script).start()
            val s = rt.engine.createSession(TestRuntime.APP, null)
            val samples = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
            val watcher = launchWatcher(rt, samples)
            val t = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("block"))
            rt.until { rt.engine.runState.value.activeTasks == 1 }
            // 从提交开始到任务运行，runState 一直是 busy
            val firstBusy = samples.indexOfFirst { it }
            assertTrue(firstBusy >= 0)
            assertTrue(samples.drop(firstBusy).all { it }, "no idle gap after the prompt was received")
            watcher.cancel()
            rt.engine.cancel(TestRuntime.APP, s.id)
            rt.engine.awaitTask(t.id)
            rt.stop()
            rt.host.deleteDatabase()
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.launchWatcher(rt: TestRuntime, samples: MutableList<Boolean>) =
        launch(start = CoroutineStart.UNDISPATCHED) {
            rt.engine.runState.collect { samples += it.busy }
        }

    @Test
    fun `after a user stop, recovered tasks are not continued and do not keep the runtime busy`() = runBlocking {
        withTimeout(15_000) {
            val first = TestRuntime(script, schedulerConfig = SchedulerConfig(maxRunningSessions = 1, tickMillis = 20)).start()
            val a = first.engine.createSession(TestRuntime.APP, null)
            val b = first.engine.createSession(TestRuntime.OTHER_APP, null)
            val running = first.engine.submit(TestRuntime.APP, a.id, TestRuntime.text("block"))
            first.awaitEvent(a.id) { it.eventType == EventTypes.AGENT_START }
            val queued = first.engine.submit(TestRuntime.OTHER_APP, b.id, TestRuntime.text("later"))
            first.stop()

            val second = TestRuntime(script, databaseFile = first.databaseFile, host = FakeHostPort(databaseFile = first.databaseFile))
            second.host.stoppedByUser = true
            second.start()
            delay(200)
            assertEquals(0, second.turnsStarted(), "nothing recovered is continued")
            assertEquals(TaskState.CANCELLED, second.engine.task(queued.id)!!.state)
            assertEquals(TaskState.UNKNOWN, second.engine.task(running.id)!!.state, "the interrupted one is fenced, never replayed")
            assertFalse(second.engine.runState.value.busy, "tasks=0: W6 leaves the foreground")
            val cancel = second.engine.readEvents(b.id).single { it.eventType == EventTypes.TASK_CANCEL_REQUESTED }
            assertEquals("user_stop", cancel.payload["by"]!!.jsonPrimitive.content)
            val recovered = second.engine.readEvents(EventTypes.SYSTEM_STREAM).last { it.eventType == EventTypes.RUNTIME_RECOVERED }
            assertEquals("true", recovered.payload["userStopped"]!!.jsonPrimitive.content)
            // 新的 prompt 照常执行
            val fresh = second.engine.submit(TestRuntime.OTHER_APP, b.id, TestRuntime.text("new"))
            assertEquals(TaskState.COMPLETED, second.engine.awaitTask(fresh.id).state)
            second.stop()
            first.host.deleteDatabase()
        }
    }

    @Test
    fun `serveAcp returns immediately before start and the connection works once the runtime is ready`() = runBlocking {
        withTimeout(15_000) {
            val rt = TestRuntime(script)
            val t0 = System.nanoTime()
            val pair = AcpPair(rt) // 内部调用 serveAcp；此时还没 start，Store 都没打开
            val serveMillis = (System.nanoTime() - t0) / 1_000_000
            assertTrue(serveMillis < 500, "serveAcp took $serveMillis ms")
            val init = async { pair.initialize() }
            val session = async { init.await(); pair.newSession() }
            delay(100)
            assertFalse(session.isCompleted, "session/new waits for recovery")
            assertTrue(rt.engine.runState.value.busy, "a request waiting for recovery counts")
            rt.start()
            val events = pair.prompt(session.await(), "hi")
            assertEquals(StopReason.END_TURN, events.response().stopReason)
            pair.close()
            rt.stop()
            rt.host.deleteDatabase()
        }
    }
}
