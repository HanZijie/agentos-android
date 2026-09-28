package org.agentos.runtime.scheduler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.agentos.runtime.errors.AgentOsException
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.store.SessionState
import org.agentos.runtime.store.TaskState
import org.agentos.runtime.testing.FakeStep
import org.agentos.runtime.testing.FakeTurnContext
import org.agentos.runtime.testing.FakeTurnScript
import org.agentos.runtime.testing.TestRuntime
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchedulerTest {
    private companion object {
        val HUGE = ("a".repeat(8_191) + "\uD83D\uDE00").repeat(9) + "tail"
    }

    /** "block" 挂起到取消；"slow" 流 20 段；其他回显。 */
    private val scripts = { ctx: FakeTurnContext ->
        when (ctx.input.text) {
            "block" -> FakeTurnScript(FakeStep.Text("working"), FakeStep.AwaitAbort)
            "slow" -> FakeTurnScript(FakeStep.Text("y".repeat(40), chunkChars = 2, intervalMs = 5))
            // 一次到达的超长增量：73,741 字符，代理对正好跨在 8,192 的切分点上
            "huge" -> FakeTurnScript(FakeStep.Text(HUGE, chunkChars = HUGE.length))
            "crash" -> FakeTurnScript(FakeStep.Text("x"), FakeStep.CrashCore())
            else -> FakeTurnScript(FakeStep.Text("ok: ${ctx.input.text}"))
        }
    }

    private fun test(config: SchedulerConfig = SchedulerConfig(tickMillis = 20), block: suspend CoroutineScope.(TestRuntime) -> Unit) = runBlocking {
        val rt = TestRuntime(scripts, schedulerConfig = config).start()
        try {
            withTimeout(15_000) { block(this, rt) }
        } finally {
            rt.stop()
            rt.host.deleteDatabase()
        }
    }

    @Test
    fun `a full task writes events in contract order and completes with end_turn`() = test { rt ->
        val s = rt.engine.createSession(TestRuntime.APP, "/sdcard")
        val t = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("hello"))
        val done = rt.engine.awaitTask(t.id)
        assertEquals(TaskState.COMPLETED, done.state)
        assertEquals("end_turn", done.stopReason)
        val types = rt.engine.readEvents(s.id).filter { it.taskId == t.id }.map { it.eventType }
        assertEquals(EventTypes.TASK_QUEUED, types.first())
        assertEquals(EventTypes.TASK_COMPLETED, types.last())
        val order = listOf(EventTypes.TASK_QUEUED, EventTypes.TASK_STARTED, EventTypes.AGENT_START, EventTypes.AGENT_END, EventTypes.TASK_COMPLETED)
        assertEquals(order, types.filter { it in order })
        // 会话回到 created，选择元数据记录了首轮问答
        val after = rt.engine.session(TestRuntime.APP, s.id)
        assertEquals(SessionState.CREATED, after.state)
        assertEquals("hello", after.selection.firstQuery)
        assertEquals("ok: hello", after.selection.firstAnswer)
    }

    @Test
    fun `prompts in one session run one at a time in submission order`() = test { rt ->
        val starts = Collections.synchronizedList(mutableListOf<String>())
        val s = rt.engine.createSession(TestRuntime.APP, null)
        val tasks = listOf("a", "b", "c").map { rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text(it)) }
        tasks.forEach { rt.engine.awaitTask(it.id) }
        rt.engine.readEvents(s.id).filter { it.eventType == EventTypes.TASK_STARTED }.forEach { starts += it.taskId!! }
        assertEquals(tasks.map { it.id }, starts)
        // 不重叠：每个 task.started 之前，上一个任务已经结束
        val types = rt.engine.readEvents(s.id).map { it.eventType to it.taskId }
        for (i in 1 until tasks.size) {
            val prevEnd = types.indexOf(EventTypes.TASK_COMPLETED to tasks[i - 1].id)
            val nextStart = types.indexOf(EventTypes.TASK_STARTED to tasks[i].id)
            assertTrue(prevEnd < nextStart)
        }
    }

    @Test
    fun `global and per-caller limits are respected`() = test(SchedulerConfig(maxRunningSessions = 2, maxRunningPerOwner = 1, tickMillis = 20)) { rt ->
        val mine = (1..2).map { rt.engine.createSession(TestRuntime.APP, null) }
        val theirs = rt.engine.createSession(TestRuntime.OTHER_APP, null)
        val t1 = rt.engine.submit(TestRuntime.APP, mine[0].id, TestRuntime.text("block"))
        val t2 = rt.engine.submit(TestRuntime.APP, mine[1].id, TestRuntime.text("block"))
        val t3 = rt.engine.submit(TestRuntime.OTHER_APP, theirs.id, TestRuntime.text("block"))
        rt.until { rt.engine.runState.value.activeTasks == 2 }
        delay(100)
        assertEquals(TaskState.RUNNING, rt.engine.task(t1.id)!!.state)
        assertEquals(TaskState.QUEUED, rt.engine.task(t2.id)!!.state, "per-caller limit 1")
        assertEquals(TaskState.RUNNING, rt.engine.task(t3.id)!!.state)
        rt.engine.cancel(TestRuntime.APP, mine[0].id)
        assertEquals(TaskState.CANCELLED, rt.engine.awaitTask(t1.id).state)
        rt.until { rt.engine.task(t2.id)!!.state == TaskState.RUNNING }
        rt.engine.cancel(TestRuntime.APP, mine[1].id)
        rt.engine.cancel(TestRuntime.OTHER_APP, theirs.id)
    }

    @Test
    fun `cancel stops a running prompt and removes queued ones`() = test { rt ->
        val s = rt.engine.createSession(TestRuntime.APP, null)
        val running = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("block"))
        val queued = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("next"))
        rt.awaitEvent(s.id) { it.eventType == EventTypes.AGENT_START }
        val cancelled = rt.engine.cancel(TestRuntime.APP, s.id)
        assertEquals(setOf(running.id, queued.id), cancelled.toSet())
        val r = rt.engine.awaitTask(running.id)
        assertEquals(TaskState.CANCELLED, r.state)
        assertEquals("cancelled", r.stopReason)
        assertEquals(TaskState.CANCELLED, rt.engine.task(queued.id)!!.state)
        assertEquals(1, rt.turnsStarted(), "the queued prompt never reached the agent")
        val types = rt.engine.readEvents(s.id).filter { it.taskId == running.id }.map { it.eventType }
        assertTrue(types.indexOf(EventTypes.TASK_CANCEL_REQUESTED) < types.indexOf(EventTypes.AGENT_END))
        assertEquals(EventTypes.TASK_CANCELLED, types.last())
        assertEquals(SessionState.CREATED, rt.engine.session(TestRuntime.APP, s.id).state)
        // 取消后会话照常可用
        val again = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("again"))
        assertEquals(TaskState.COMPLETED, rt.engine.awaitTask(again.id).state)
    }

    @Test
    fun `no configured model fails the task without starting the agent`() = test { rt ->
        rt.host.activeModel.value = null
        val s = rt.engine.createSession(TestRuntime.APP, null)
        val t = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("hi"))
        val done = rt.engine.awaitTask(t.id)
        assertEquals(TaskState.FAILED, done.state)
        assertEquals(ErrorCode.MODEL_NOT_CONFIGURED, done.error!!.code)
        assertEquals(0, rt.turnsStarted())
        val failed = rt.engine.readEvents(s.id).single { it.eventType == EventTypes.TASK_FAILED }
        assertEquals("not_started", failed.payload["attemptState"]!!.jsonPrimitive.content)
    }

    @Test
    fun `execution deadline cancels the turn and fails it with execution_timeout`() = test(SchedulerConfig(executionTimeoutMillis = 200, tickMillis = 20)) { rt ->
        val s = rt.engine.createSession(TestRuntime.APP, null)
        val t = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("block"))
        rt.awaitEvent(s.id) { it.eventType == EventTypes.AGENT_START }
        rt.host.clock.advance(250) // FakeHostPort 用手动时钟：deadline 按 HostPort.clock 计算
        val done = rt.engine.awaitTask(t.id)
        assertEquals(TaskState.FAILED, done.state)
        assertEquals(ErrorCode.EXECUTION_TIMEOUT, done.error!!.code)
        val requested = rt.engine.readEvents(s.id).single { it.eventType == EventTypes.TASK_CANCEL_REQUESTED }
        assertEquals("timeout", requested.payload["by"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a pump failure fences the task and the next prompt runs on a new core restored from saved messages`() = test { rt ->
        val s = rt.engine.createSession(TestRuntime.APP, null)
        val first = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("remember 7"))
        rt.engine.awaitTask(first.id)
        val crash = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("crash"))
        val lost = rt.engine.awaitTask(crash.id)
        assertEquals(TaskState.UNKNOWN, lost.state)
        assertEquals(ErrorCode.AGENT_CORE_FAILED, lost.error!!.code)
        val sys = rt.engine.readEvents(org.agentos.runtime.events.EventTypes.SYSTEM_STREAM).map { it.eventType }
        assertTrue(EventTypes.AGENT_CORE_FAILED in sys)

        rt.engine.abandonRecovery(TestRuntime.APP, s.id, crash.id)
        val next = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("what number?"))
        assertEquals(TaskState.COMPLETED, rt.engine.awaitTask(next.id).state)
        assertEquals(2, rt.cores.size, "a new core instance was created")
        val restored = rt.cores.last().openSessionIds()
        assertTrue(s.id in restored)
        assertTrue(EventTypes.AGENT_CORE_RESTARTED in rt.engine.readEvents(EventTypes.SYSTEM_STREAM).map { it.eventType })
    }

    @Test
    fun `text deltas are coalesced and still concatenate to the full answer`() = test { rt ->
        val s = rt.engine.createSession(TestRuntime.APP, null)
        val t = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("slow"))
        rt.engine.awaitTask(t.id)
        val deltas = rt.engine.readEvents(s.id)
            .filter { it.eventType == EventTypes.MESSAGE_UPDATE }
            .map { org.agentos.runtime.events.AgentEvent.decode(kotlinx.serialization.json.JsonObject(it.payload + ("type" to kotlinx.serialization.json.JsonPrimitive("message_update")))) }
            .filterIsInstance<org.agentos.runtime.events.AgentEvent.MessageUpdate>()
            .filter { it.update.kind == "text_delta" }
        assertEquals("y".repeat(40), deltas.joinToString("") { it.update.delta.orEmpty() })
        assertTrue(deltas.size < 20, "20 raw deltas were merged into ${deltas.size} events")
        // 不写入的增量类型没有进日志
        val kinds = rt.engine.readEvents(s.id).filter { it.eventType == EventTypes.MESSAGE_UPDATE }
            .map { it.payload["update"].toString() }
        assertTrue(kinds.none { "text_start" in it || "toolcall_delta" in it })
    }

    @Test
    fun `one oversized delta is split into log entries of at most 8,192 characters and nothing is truncated`() = test { rt ->
        val s = rt.engine.createSession(TestRuntime.APP, null)
        val t = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("huge"))
        rt.engine.awaitTask(t.id)
        val entries = rt.engine.readEvents(s.id).filter { it.eventType == EventTypes.MESSAGE_UPDATE }
        val deltas = entries
            .map { org.agentos.runtime.events.AgentEvent.decode(kotlinx.serialization.json.JsonObject(it.payload + ("type" to kotlinx.serialization.json.JsonPrimitive("message_update")))) }
            .filterIsInstance<org.agentos.runtime.events.AgentEvent.MessageUpdate>()
            .filter { it.update.kind == "text_delta" }
            .map { it.update.delta.orEmpty() }
        assertEquals(HUGE, deltas.joinToString(""))
        assertTrue(deltas.size >= 9 && deltas.all { it.length <= 8_192 }, deltas.map { it.length }.toString())
        assertTrue(deltas.none { Character.isHighSurrogate(it.last()) }, "no surrogate pair is cut")
        assertTrue(entries.none { it.payload["truncated"] != null })
    }

    @Test
    fun `callers cannot see or use each other's sessions, SELF sees all`() = test { rt ->
        val s = rt.engine.createSession(TestRuntime.APP, null)
        val e1 = assertFailsWith<AgentOsException> { rt.engine.submit(TestRuntime.OTHER_APP, s.id, TestRuntime.text("hijack")) }
        assertEquals(ErrorCode.SESSION_NOT_FOUND, e1.info.code)
        assertFailsWith<AgentOsException> { rt.engine.cancel(TestRuntime.OTHER_APP, s.id) }
        assertEquals(s.id, rt.engine.session(TestRuntime.SELF, s.id).id)
        assertFailsWith<AgentOsException> { rt.engine.session(TestRuntime.SELF, EventTypes.SYSTEM_STREAM) }
    }

    @Test
    fun `resubmitting the same client request id is idempotent`() = test { rt ->
        val s = rt.engine.createSession(TestRuntime.APP, null)
        val a = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("once"), clientRequestId = "req-1")
        val b = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("once"), clientRequestId = "req-1")
        assertEquals(a.id, b.id)
        val conflict = assertFailsWith<AgentOsException> { rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("different"), clientRequestId = "req-1") }
        assertEquals(ErrorCode.REQUEST_CONFLICT, conflict.info.code)
        rt.engine.awaitTask(a.id)
        assertEquals(1, rt.turnsStarted())
    }

    @Test
    fun `events flow delivers committed events live and in order`() = test { rt ->
        val s = rt.engine.createSession(TestRuntime.APP, null)
        val collected = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            rt.engine.events(s.id)
                .transformWhile { e -> emit(e); e.eventType != EventTypes.TASK_COMPLETED }
                .toList()
                .map { it.sequence }
        }
        val t = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("slow"))
        val seen = collected.await()
        assertEquals((1L..seen.last()).toList(), seen, "no gaps, no duplicates")
        assertEquals(TaskState.COMPLETED, rt.engine.task(t.id)!!.state)
    }
}
