package org.agentos.sample.notes.agentos

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 状态机、取消、防抖、进程重建：全部对着假网关的各种脚本，虚拟时间。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentScheduleUseCaseTest {
    private val now = ZonedDateTime.of(2026, 10, 8, 9, 30, 0, 0, ZoneId.of("Asia/Shanghai"))
    private val note = ScheduleSource.of("n1", "周五的事", "周五下午 3 点和王总开会\n每周一早上 7 点跑步", null)

    private class Marker : RunMarker {
        var on = false
        override fun set() { on = true }
        override fun clear() { on = false }
        override fun isSet() = on
    }

    private class Env(scope: kotlinx.coroutines.CoroutineScope, now: ZonedDateTime, script: () -> FakeScript, val marker: Marker = Marker()) {
        val gateways = mutableListOf<FakeAgentOsGateway>()
        val useCase = AgentScheduleUseCase(
            scope = scope,
            gatewayFactory = { FakeAgentOsGateway(script()).also { gateways += it } },
            clock = { now },
            locale = { Locale.SIMPLIFIED_CHINESE },
            marker = marker,
        )
        val seen = mutableListOf<ScheduleState>()
    }

    private fun TestScope.env(name: String): Env =
        env(FakeScripts.byName(name, now) ?: error("no script $name"))

    private fun TestScope.env(script: FakeScript): Env {
        val e = Env(backgroundScope, now, { script })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { e.useCase.state.collect { e.seen += it } }
        return e
    }

    // backgroundScope 里的协程不算“前台”任务，advanceUntilIdle 不会去跑它们：用 advanceTimeBy 把虚拟时间拨过去
    private fun TestScope.settle(ms: Long = 120_000) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** 读文字、点开始、走完。 */
    private suspend fun TestScope.runIt(e: Env, source: ScheduleSource = note): ScheduleState {
        assertEquals(OpenResult.OPENED, e.useCase.open(source))
        assertTrue(e.useCase.start())
        settle()
        return e.useCase.state.value
    }

    /** 状态历史里按顺序出现了这些类（中间可以夹别的）。 */
    private fun Env.assertPath(vararg kinds: Class<out ScheduleState>) {
        var i = 0
        for (s in seen) if (i < kinds.size && kinds[i].isInstance(s)) i++
        assertEquals("path ${seen.map { it.javaClass.simpleName }}", kinds.size, i)
    }

    // ---------------------------------------------------------------- 正常路径

    @Test fun `success creates an event and an alarm, walking Ready Checking Running Done`() = runTest {
        val e = env("success")
        val end = runIt(e) as ScheduleState.Done
        e.assertPath(ScheduleState.Idle::class.java, ScheduleState.Ready::class.java, ScheduleState.Checking::class.java, ScheduleState.Running::class.java, ScheduleState.Done::class.java)
        assertFalse("first use of an already authorised app never waits", e.seen.any { it is ScheduleState.WaitingAuthorization })
        assertFalse(end.stopped)
        assertEquals(1, end.summary.eventCount)
        assertEquals(1, end.summary.alarmCount)
        assertEquals("和王总开会", end.summary.createdEvents.single().event!!.title)
        assertEquals("跑步", end.summary.createdAlarms.single().alarm!!.label)
        assertTrue(end.text.contains("处理好了"))
        val gw = e.gateways.single()
        assertEquals(NotesToolScope, gw.scope)
        assertEquals(listOf(ToolRef("alarm", "alarm_create"), ToolRef("calendar", "event_create")), gw.scope)
        assertEquals("the connection is closed after a normal end", 1, gw.closeCount)
        assertFalse("the marker is cleared at the end", e.marker.on)
    }

    @Test fun `the prompt sent is the built one for exactly the source text`() = runTest {
        val e = env("success")
        runIt(e)
        val sent = e.gateways.single().promptedText!!
        assertEquals(NoteSchedulePrompt.build(note.text, now, Locale.SIMPLIFIED_CHINESE), sent)
    }

    @Test fun `tool cards show every stage while running`() = runTest {
        val e = env("success")
        runIt(e)
        val stages = e.seen.filterIsInstance<ScheduleState.Running>().flatMap { it.items }.filter { it.id == "e1" }.map { it.status }.distinct()
        assertEquals(listOf(ItemStatus.AWAITING_APPROVAL, ItemStatus.CREATING, ItemStatus.CREATED), stages)
        assertTrue("approval is flagged while a card waits", e.seen.filterIsInstance<ScheduleState.Running>().any { it.awaitingApproval })
    }

    @Test fun `streamed text accumulates`() = runTest {
        val e = env(FakeScript(prompt = listOf(
            FakeStep.Emit(GatewayEvent.Text("你")), FakeStep.Emit(GatewayEvent.Text("好")), FakeStep.Emit(GatewayEvent.Done("end_turn")),
        )))
        val end = runIt(e) as ScheduleState.Done
        assertEquals("你好", end.text)
        assertTrue(end.summary.isEmpty)
    }

    @Test fun `first run waits for authorization in AgentOS, then goes on`() = runTest {
        val e = env("first_run")
        e.useCase.open(note); e.useCase.start()
        runCurrent()
        assertTrue(e.useCase.state.value is ScheduleState.WaitingAuthorization)
        e.useCase.bringApprovalToFront()
        assertEquals(1, e.gateways.single().frontCount)
        settle()
        e.assertPath(ScheduleState.Ready::class.java, ScheduleState.Checking::class.java, ScheduleState.WaitingAuthorization::class.java, ScheduleState.Running::class.java, ScheduleState.Done::class.java)
    }

    @Test fun `nothing to schedule ends in Done with the agent's explanation`() = runTest {
        val e = env("no_time")
        val end = runIt(e) as ScheduleState.Done
        assertTrue(end.summary.isEmpty)
        assertTrue(end.text.contains("没有明确的日期"))
    }

    @Test fun `a refused item is shown as refused and not counted`() = runTest {
        val e = env("reject")
        val end = runIt(e) as ScheduleState.Done
        assertEquals(1, end.summary.eventCount)
        assertEquals(0, end.summary.alarmCount)
        assertEquals(1, end.summary.deniedCount)
        assertEquals(ItemStatus.DENIED, end.summary.items.single { it.id == "a1" }.status)
    }

    // ---------------------------------------------------------------- 错误

    private suspend fun TestScope.errorOf(name: String): ScheduleState.Error = runIt(env(name)) as ScheduleState.Error

    @Test fun `every failure maps to its error`() = runTest {
        assertEquals(AgentOsError.NO_MODEL, errorOf("no_model").error)
        assertEquals(AgentOsError.AUTHORIZATION_PENDING_TIMEOUT, errorOf("auth_timeout").error)
        assertEquals(AgentOsError.DENIED, errorOf("denied").error)
        assertEquals(AgentOsError.BUSY, errorOf("busy").error)
        assertEquals(AgentOsError.RATE_LIMITED, errorOf("rate_limited").error)
        assertEquals(AgentOsError.TOO_LARGE, errorOf("too_large").error)
        assertEquals(AgentOsError.DISCONNECTED, errorOf("disconnect").error)
        val failed = errorOf("failed")
        assertEquals(AgentOsError.FAILED, failed.error)
        assertEquals("the agent crashed", failed.detail)
    }

    @Test fun `not installed never connects`() = runTest {
        val e = env("not_installed")
        val end = runIt(e) as ScheduleState.Error
        assertEquals(AgentOsError.NOT_INSTALLED, end.error)
        assertEquals(0, e.gateways.single().connectCount)
        assertEquals(0, e.gateways.single().promptCount)
        assertEquals(1, e.gateways.single().closeCount)
    }

    @Test fun `authorization timeout passes through WaitingAuthorization`() = runTest {
        val e = env("auth_timeout")
        runIt(e)
        e.assertPath(ScheduleState.Checking::class.java, ScheduleState.WaitingAuthorization::class.java, ScheduleState.Error::class.java)
    }

    @Test fun `a connection lost half way keeps what was already created`() = runTest {
        val e = env("disconnect")
        val end = runIt(e) as ScheduleState.Error
        assertEquals(AgentOsError.DISCONNECTED, end.error)
        assertEquals("the event created before the drop is still reported", 1, end.summary.eventCount)
    }

    @Test fun `a stream that ends without Done is a disconnect`() = runTest {
        val e = env(FakeScript(prompt = listOf(FakeStep.Emit(GatewayEvent.Text("..")))))
        val end = runIt(e) as ScheduleState.Error
        assertEquals(AgentOsError.DISCONNECTED, end.error)
    }

    @Test fun `an unexpected exception becomes FAILED and the connection is closed`() = runTest {
        var closed = 0
        val bomb = object : AgentOsGateway by FakeAgentOsGateway(FakeScript()) {
            override suspend fun connect(onWaiting: (Waiting) -> Unit) { throw IllegalStateException("boom") }
            override fun close() { closed++ }
        }
        val uc = AgentScheduleUseCase(backgroundScope, { bomb }, { now }, { Locale.US })
        uc.open(note); uc.start(); settle()
        val end = uc.state.value as ScheduleState.Error
        assertEquals(AgentOsError.FAILED, end.error)
        assertEquals("boom", end.detail)
        assertEquals("close() was called", 1, closed)
    }

    @Test fun `retry after an error starts a new round with the same text`() = runTest {
        val e = env("busy")
        runIt(e)
        assertTrue(e.useCase.start())
        settle()
        assertEquals(2, e.gateways.size)
        assertEquals(e.gateways[0].promptedText, e.gateways[1].promptedText)
    }

    // ---------------------------------------------------------------- 取消、防抖

    @Test fun `stop while waiting for the first approval closes the panel and the connection`() = runTest {
        val e = env("hold_auth")
        e.useCase.open(note); e.useCase.start(); runCurrent(); advanceTimeBy(500)
        assertTrue(e.useCase.state.value is ScheduleState.WaitingAuthorization)
        e.useCase.stop()
        settle()
        assertEquals(ScheduleState.Idle, e.useCase.state.value)
        val gw = e.gateways.single()
        assertEquals(1, gw.cancelCount)
        assertTrue("connection closed", gw.closeCount >= 1)
        assertFalse(e.marker.on)
    }

    @Test fun `stop while running keeps what was created and marks the rest cancelled`() = runTest {
        val e = env("hold_approval")
        e.useCase.open(note); e.useCase.start(); advanceTimeBy(1_000)
        val running = e.useCase.state.value as ScheduleState.Running
        assertTrue(running.awaitingApproval)
        e.useCase.stop()
        settle()
        val end = e.useCase.state.value as ScheduleState.Done
        assertTrue(end.stopped)
        assertEquals(listOf(ItemStatus.CREATED, ItemStatus.CANCELLED), end.summary.items.map { it.status })
        assertEquals(1, end.summary.eventCount)
        assertEquals(1, e.gateways.single().cancelCount)
        assertTrue(e.gateways.single().closeCount >= 1)
    }

    @Test fun `stop with nothing yet closes the panel but remembers the stopped run`() = runTest {
        val e = env("hold_running")
        e.useCase.open(note); e.useCase.start(); advanceTimeBy(200)
        e.useCase.stop()
        // hold_running 已经有一个“创建中”的项；换成一个还没有任何项的脚本再测一次空的
        val e2 = env(FakeScript(prompt = listOf(FakeStep.Hang)))
        e2.useCase.open(note); e2.useCase.start(); runCurrent()
        e2.useCase.stop()
        settle()
        assertEquals(ScheduleState.Idle, e2.useCase.state.value)
        val last = e2.useCase.lastRun as ScheduleState.Done
        assertTrue(last.stopped)
        assertTrue(last.summary.isEmpty)
    }

    @Test fun `late events from a stopped round are ignored`() = runTest {
        val e = env("success")
        e.useCase.open(note); e.useCase.start(); advanceTimeBy(1_000)
        e.useCase.stop()
        val afterStop = e.useCase.state.value
        settle()
        assertEquals(afterStop, e.useCase.state.value)
    }

    @Test fun `a second start while running is ignored`() = runTest {
        val e = env("success")
        e.useCase.open(note)
        assertTrue(e.useCase.start())
        assertFalse(e.useCase.start())
        runCurrent()
        assertFalse(e.useCase.start())
        advanceTimeBy(1_000)
        assertFalse("still running", e.useCase.start())
        settle()
        assertEquals("only one round was ever started", 1, e.gateways.size)
        assertEquals(1, e.gateways.single().promptCount)
    }

    @Test fun `the panel cannot be reopened or closed while a round is running`() = runTest {
        val e = env("hold_running")
        e.useCase.open(note); e.useCase.start(); advanceTimeBy(1_000)
        assertEquals(OpenResult.BUSY, e.useCase.open(ScheduleSource.of("n2", "x", "other text", null)))
        e.useCase.dismiss()
        assertTrue(e.useCase.state.value is ScheduleState.Running)
        assertEquals(note, e.useCase.state.value.source)
    }

    @Test fun `start does nothing without a Ready or Error state`() = runTest {
        val e = env("success")
        assertFalse(e.useCase.start())
        runIt(e)
        assertFalse("Done is not startable", e.useCase.start())
        assertEquals(1, e.gateways.size)
    }

    // ---------------------------------------------------------------- 字数、空文字

    @Test fun `too long or blank text is not accepted and nothing is sent`() = runTest {
        val e = env("success")
        val room = NoteSchedulePrompt.MAX_PROMPT_CHARS - NoteSchedulePrompt.build("", now, Locale.SIMPLIFIED_CHINESE).length
        assertEquals(OpenResult.OPENED, e.useCase.open(ScheduleSource.of(null, "", "字".repeat(room), null)))
        e.useCase.dismiss()
        assertEquals(OpenResult.TOO_LONG, e.useCase.open(ScheduleSource.of(null, "", "字".repeat(room + 1), null)))
        assertEquals(ScheduleState.Idle, e.useCase.state.value)
        assertEquals(OpenResult.EMPTY, e.useCase.open(ScheduleSource.of(null, "  ", "\n ", null)))
        assertFalse(e.useCase.start())
        assertTrue(e.gateways.isEmpty())
    }

    @Test fun `16001 characters of plain text are always rejected`() = runTest {
        val e = env("success")
        assertEquals(OpenResult.TOO_LONG, e.useCase.open(ScheduleSource.of(null, "", "a".repeat(16_001), null)))
    }

    @Test fun `a selection wins over the whole note, a blank selection does not`() {
        val whole = ScheduleSource.of("n1", "标题", "正文", null)
        assertEquals("标题\n\n正文", whole.text)
        assertFalse(whole.fromSelection)
        val sel = ScheduleSource.of("n1", "标题", "正文", "只要这句")
        assertEquals("只要这句", sel.text)
        assertTrue(sel.fromSelection)
        assertEquals("标题\n\n正文", ScheduleSource.of("n1", "标题", "正文", "  \n").text)
        assertEquals("正文", ScheduleSource.of("n1", "", "正文", null).text)
        assertEquals("标题", ScheduleSource.of("n1", "标题", " ", null).text)
    }

    // ---------------------------------------------------------------- 进程重建、debug 入口

    @Test fun `a round lost with the process shows as DISCONNECTED once`() = runTest {
        val marker = Marker().also { it.on = true }
        val uc = AgentScheduleUseCase(backgroundScope, { FakeAgentOsGateway(FakeScript()) }, { now }, { Locale.US }, marker)
        uc.restoreInterrupted()
        val s = uc.state.value as ScheduleState.Error
        assertEquals(AgentOsError.DISCONNECTED, s.error)
        assertTrue(s.interrupted)
        assertFalse("marker consumed", marker.on)
        uc.dismiss()
        uc.restoreInterrupted() // 旋转屏幕等：同一个进程里再调不会再弹
        assertEquals(ScheduleState.Idle, uc.state.value)
        assertEquals(s, uc.lastRun)
    }

    @Test fun `no marker means no message after a restart`() = runTest {
        val uc = AgentScheduleUseCase(backgroundScope, { FakeAgentOsGateway(FakeScript()) }, { now }, { Locale.US }, Marker())
        uc.restoreInterrupted()
        assertEquals(ScheduleState.Idle, uc.state.value)
        assertNull(uc.lastRun)
    }

    @Test fun `the marker is set while running`() = runTest {
        val e = env("hold_running")
        e.useCase.open(note); e.useCase.start(); advanceTimeBy(500)
        assertTrue(e.marker.on)
        e.useCase.stop(); settle()
        assertFalse(e.marker.on)
    }

    @Test fun `runToEnd goes through the same path and reports the end`() = runTest {
        val e = env("success")
        val out = e.useCase.runToEnd(note, 150_000)
        assertFalse(out.timedOut)
        val done = out.state as ScheduleState.Done
        assertEquals(1, done.summary.eventCount)
        assertEquals(done, e.useCase.lastRun)
        e.assertPath(ScheduleState.Ready::class.java, ScheduleState.Checking::class.java, ScheduleState.Running::class.java, ScheduleState.Done::class.java)
    }

    @Test fun `runToEnd times out, stops the round and says so`() = runTest {
        val e = env("hold_approval")
        val out = e.useCase.runToEnd(note, 20_000)
        assertTrue(out.timedOut)
        val done = out.state as ScheduleState.Done
        assertTrue(done.stopped)
        assertEquals(ItemStatus.CANCELLED, done.summary.items.last().status)
        settle()
        assertTrue(e.gateways.single().closeCount >= 1)
    }

    @Test fun `runToEnd with a short wait returns pending and the hard timeout still stops the round later`() = runTest {
        val e = env("hold_approval")
        var out: AgentScheduleUseCase.RunOutcome? = null
        backgroundScope.launch { out = e.useCase.runToEnd(note, 20_000, 5_000) }
        advanceTimeBy(6_000); runCurrent()
        assertTrue("the caller stopped waiting", out!!.pending)
        assertFalse(out!!.timedOut)
        assertTrue("but the round goes on", e.useCase.state.value.inFlight)
        assertFalse(e.useCase.lastRunTimedOut)
        advanceTimeBy(20_000); runCurrent()
        assertFalse(e.useCase.state.value.inFlight)
        assertTrue(e.useCase.lastRunTimedOut)
        assertTrue((e.useCase.lastRun as ScheduleState.Done).stopped)
        assertTrue(e.gateways.single().closeCount >= 1)
    }

    @Test fun `a round that finished in time is never marked timed out by its leftover watchdog`() = runTest {
        val e = env("success")
        val out = e.useCase.runToEnd(note, 150_000)
        assertFalse(out.pending)
        settle(200_000)
        assertFalse(e.useCase.lastRunTimedOut)
        assertTrue(e.useCase.lastRun is ScheduleState.Done)
        assertFalse((e.useCase.lastRun as ScheduleState.Done).stopped)
    }

    @Test fun `runToEnd rejects too long, empty and busy without sending`() = runTest {
        val e = env("hold_running")
        assertEquals(AgentOsError.TOO_LARGE, (e.useCase.runToEnd(ScheduleSource.of(null, "", "a".repeat(20_000), null), 1_000).state as ScheduleState.Error).error)
        assertEquals(AgentOsError.FAILED, (e.useCase.runToEnd(ScheduleSource.of(null, "", " ", null), 1_000).state as ScheduleState.Error).error)
        e.useCase.open(note); e.useCase.start(); advanceTimeBy(500)
        assertEquals(AgentOsError.BUSY, (e.useCase.runToEnd(note, 1_000).state as ScheduleState.Error).error)
        assertEquals(1, e.gateways.size)
        assertNotNull(e.useCase.lastRun)
    }
}
