package org.agentos.sample.sms.agentos

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
import org.agentos.sample.sms.data.SmsBox
import org.agentos.sample.sms.data.SmsRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 状态机、取消、“已处理”记账、提示词编辑：全部对着假网关的各种脚本，虚拟时间。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmsScheduleUseCaseTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 10, 9, 16, 30, 0, 0, zone)
    private val t0 = ZonedDateTime.of(2026, 10, 8, 9, 30, 0, 0, zone).toInstant().toEpochMilli()

    private fun record(id: Int, body: String, box: SmsBox = SmsBox.INBOX, address: String = "95555") =
        SmsRecord(id.toString(), 1, address, t0 + id * 60_000L, box, true, body)

    private val records = listOf(
        record(1, "明天下午 3 点和王总开会，3 号会议室"),
        record(2, "您的快递已到驿站，请在 10 月 12 日前取件"),
        record(3, "验证码 482915，5 分钟内有效"),
    )

    private class Marker : RunMarker {
        var on = false
        override fun set() { on = true }
        override fun clear() { on = false }
        override fun isSet() = on
    }

    private class Env(scope: kotlinx.coroutines.CoroutineScope, now: ZonedDateTime, script: () -> FakeScript, val marker: Marker = Marker()) {
        val gateways = mutableListOf<FakeAgentOsGateway>()
        val ledgerStore = InMemoryProcessedStore()
        val ledger = ProcessedLedger(ledgerStore)
        val instructionStore = InMemoryInstructionStore()
        val useCase = SmsScheduleUseCase(
            scope = scope,
            gatewayFactory = { FakeAgentOsGateway(script()).also { gateways += it } },
            ledger = ledger,
            prompt = PromptSettings(instructionStore) { DEFAULT },
            clock = { now },
            locale = { Locale.SIMPLIFIED_CHINESE },
            marker = marker,
        )
        val seen = mutableListOf<ScheduleState>()
    }

    private fun TestScope.env(name: String): Env = env(FakeScripts.byName(name, now) ?: error("no script $name"))

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

    private fun Env.source(maskCodes: Boolean = false, includeProcessed: Boolean = false) =
        useCase.sourceFor("95555", records, maskCodes, includeProcessed)

    /** 读短信、点开始、走完。 */
    private fun TestScope.runIt(e: Env, source: ScheduleSource = e.source()): ScheduleState {
        assertEquals(OpenResult.OPENED, e.useCase.open(source))
        assertTrue(e.useCase.start())
        settle()
        return e.useCase.state.value
    }

    private fun Env.assertPath(vararg kinds: Class<out ScheduleState>) {
        var i = 0
        for (s in seen) if (i < kinds.size && kinds[i].isInstance(s)) i++
        assertEquals("path ${seen.map { it.javaClass.simpleName }}", kinds.size, i)
    }

    // ---------------------------------------------------------------- 正常路径

    @Test fun `success_todo creates an event, a to-do and an alarm, each counted on its own`() = runTest {
        val e = env("success_todo")
        val end = runIt(e) as ScheduleState.Done
        assertEquals(1, end.summary.eventCount)
        assertEquals(1, end.summary.todoCount)
        assertEquals(1, end.summary.alarmCount)
        assertEquals(3, end.summary.createdCount)
        assertNotNull("the to-do result carries a due date", end.summary.createdTodos.single().todo!!.due)
        assertEquals(listOf(ItemKind.EVENT, ItemKind.TODO, ItemKind.ALARM), end.summary.items.map { it.kind })
    }

    @Test fun `a normal run walks Ready Checking Running Done with exactly the three creation tools in scope`() = runTest {
        val e = env("success")
        val end = runIt(e) as ScheduleState.Done
        e.assertPath(ScheduleState.Idle::class.java, ScheduleState.Ready::class.java, ScheduleState.Checking::class.java, ScheduleState.Running::class.java, ScheduleState.Done::class.java)
        assertFalse(end.stopped)
        val gw = e.gateways.single()
        assertEquals(listOf(ToolRef("alarm", "alarm_create"), ToolRef("calendar", "event_create"), ToolRef("todo", "todo_create")), gw.scope)
        assertEquals("no tool that reads or sends messages is ever in scope", SmsToolScope, gw.scope)
        assertEquals(1, gw.closeCount)
        assertFalse("the marker is cleared at the end", e.marker.on)
    }

    @Test fun `the prompt sent is the built one for exactly the selected messages`() = runTest {
        val e = env("success")
        val source = e.source()
        runIt(e, source)
        assertEquals(SmsSchedulePrompt.build(source, now, Locale.SIMPLIFIED_CHINESE), e.gateways.single().promptedText)
        val sent = e.gateways.single().promptedText!!
        assertTrue("明天下午 3 点和王总开会" in sent && "快递已到驿站" in sent)
    }

    @Test fun `masking is applied before anything is sent`() = runTest {
        val e = env("success")
        runIt(e, e.source(maskCodes = true))
        val sent = e.gateways.single().promptedText!!
        assertFalse("the verification code never reaches AgentOS", "482915" in sent)
    }

    @Test fun `the first use waits for authorization, then goes on`() = runTest {
        val e = env("first_run")
        e.useCase.open(e.source()); e.useCase.start()
        runCurrent()
        assertTrue(e.useCase.state.value is ScheduleState.WaitingAuthorization)
        e.useCase.bringApprovalToFront()
        assertEquals(1, e.gateways.single().frontCount)
        settle()
        e.assertPath(ScheduleState.Ready::class.java, ScheduleState.Checking::class.java, ScheduleState.WaitingAuthorization::class.java, ScheduleState.Running::class.java, ScheduleState.Done::class.java)
    }

    // ---------------------------------------------------------------- “已处理”

    @Test fun `a finished run marks exactly the messages it sent as processed`() = runTest {
        val e = env("success")
        runIt(e)
        assertEquals(setOf("1", "2", "3"), e.ledger.ids.value)
        assertEquals(setOf("1", "2", "3"), e.ledgerStore.load().toSet())
    }

    @Test fun `a run that finds nothing to schedule still counts as processed`() = runTest {
        val e = env("no_time")
        val end = runIt(e) as ScheduleState.Done
        assertTrue(end.summary.isEmpty)
        assertEquals(setOf("1", "2", "3"), e.ledger.ids.value)
    }

    @Test fun `items the user declined are still processed, the user has decided`() = runTest {
        val e = env("reject")
        runIt(e)
        assertEquals(3, e.ledger.ids.value.size)
    }

    @Test fun `the next round only brings the new messages`() = runTest {
        val e = env("success")
        runIt(e)
        val next = e.useCase.sourceFor("95555", records + record(4, "下周三上午 10 点去银行办卡"), maskCodes = false)
        assertEquals(listOf("4"), next.lines.map { it.id })
        assertEquals(1, next.unprocessedCount)
        assertEquals(3, next.selection.skippedProcessed)
    }

    @Test fun `include processed runs them again`() = runTest {
        val e = env("success")
        runIt(e)
        e.useCase.dismiss()
        val all = e.source(includeProcessed = true)
        assertEquals(listOf("1", "2", "3"), all.lines.map { it.id })
        assertEquals(OpenResult.OPENED, e.useCase.open(e.source()))
        assertFalse("nothing to send until the switch is turned on", (e.useCase.state.value as ScheduleState.Ready).source.hasText)
        assertFalse("start refuses to send nothing", e.useCase.start())
        e.useCase.setIncludeProcessed(true)
        val ready = e.useCase.state.value as ScheduleState.Ready
        assertEquals(listOf("1", "2", "3"), ready.source.lines.map { it.id })
        assertTrue(e.useCase.start())
        settle()
        assertEquals(2, e.gateways.size)
        assertTrue(e.useCase.state.value is ScheduleState.Done)
    }

    @Test fun `an error leaves the messages unprocessed so you can try again`() = runTest {
        val e = env("busy")
        runIt(e)
        assertTrue(e.ledger.ids.value.isEmpty())
        assertTrue(e.useCase.start())
        settle()
        assertTrue(e.ledger.ids.value.isEmpty())
    }

    @Test fun `a lost connection half way leaves them unprocessed too`() = runTest {
        val e = env("disconnect")
        val end = runIt(e) as ScheduleState.Error
        assertEquals(1, end.summary.eventCount)
        assertTrue(e.ledger.ids.value.isEmpty())
    }

    @Test fun `stopping leaves the messages unprocessed`() = runTest {
        val e = env("hold_approval")
        e.useCase.open(e.source()); e.useCase.start(); advanceTimeBy(1_000)
        e.useCase.stop()
        settle()
        assertTrue((e.useCase.state.value as ScheduleState.Done).stopped)
        assertTrue(e.ledger.ids.value.isEmpty())
    }

    @Test fun `a round cancelled by AgentOS itself is not processed either`() = runTest {
        val e = env(FakeScript(prompt = listOf(FakeStep.Emit(GatewayEvent.Done("cancelled")))))
        val end = runIt(e) as ScheduleState.Done
        assertTrue(end.stopped)
        assertTrue(e.ledger.ids.value.isEmpty())
    }

    @Test fun `only a processed ledger of the sent lines grows, a dropped-for-budget message stays unprocessed`() = runTest {
        val e = env("success")
        val big = (1..20).map { record(it, "q".repeat(900)) }
        val source = e.useCase.sourceFor("95555", big, maskCodes = false)
        assertTrue(source.selection.droppedOlder > 0)
        runIt(e, source)
        val sentIds = source.lines.map { it.id }.toSet()
        assertEquals(sentIds, e.ledger.ids.value)
        val again = e.useCase.sourceFor("95555", big, maskCodes = false)
        assertTrue("the older ones are picked up by the next round", again.lines.isNotEmpty() && again.lines.none { it.id in sentIds })
    }

    // ---------------------------------------------------------------- 提示词编辑

    @Test fun `the default instructions are used until the user edits them`() = runTest {
        val e = env("success")
        assertEquals(DEFAULT, e.useCase.instructions)
        assertFalse(e.useCase.instructionsCustomized)
        assertEquals(DEFAULT, e.source().instructions)
    }

    @Test fun `an edit made in the panel is what the next round sends, and it is kept`() = runTest {
        val e = env("success")
        e.useCase.open(e.source())
        val applied = e.useCase.setInstructions("$DEFAULT\n只安排工作相关的事，待办优先级一律设为高。")
        assertTrue("只安排工作相关的事" in applied)
        assertTrue((e.useCase.state.value as ScheduleState.Ready).source.instructions.endsWith("优先级一律设为高。"))
        assertTrue(e.useCase.start())
        settle()
        val sent = e.gateways.single().promptedText!!
        assertTrue("只安排工作相关的事，待办优先级一律设为高。" in sent)
        assertTrue("the default part is still there", DEFAULT in sent)
        assertTrue(e.useCase.instructionsCustomized)
        assertTrue("it is remembered for the next time", e.instructionStore.load()!!.contains("只安排工作相关的事"))
        assertTrue("and the next source starts from it", e.source().instructions.contains("只安排工作相关的事"))
    }

    @Test fun `restoring the default drops the customization`() = runTest {
        val e = env("success")
        e.useCase.open(e.source())
        e.useCase.setInstructions("custom")
        assertTrue(e.useCase.instructionsCustomized)
        assertEquals(DEFAULT, e.useCase.resetInstructions())
        assertFalse(e.useCase.instructionsCustomized)
        assertEquals(DEFAULT, (e.useCase.state.value as ScheduleState.Ready).source.instructions)
    }

    @Test fun `editing outside the preview still saves but changes no running round`() = runTest {
        val e = env("hold_running")
        e.useCase.open(e.source()); e.useCase.start(); advanceTimeBy(1_000)
        val before = e.useCase.state.value.source
        e.useCase.setInstructions("changed while running")
        assertEquals("the running round keeps what it started with", before, e.useCase.state.value.source)
        assertTrue(e.useCase.state.value is ScheduleState.Running)
    }

    @Test fun `the include switch does nothing outside the preview`() = runTest {
        val e = env("hold_running")
        e.useCase.open(e.source()); e.useCase.start(); advanceTimeBy(1_000)
        val before = e.useCase.state.value
        e.useCase.setIncludeProcessed(true)
        assertEquals(before, e.useCase.state.value)
    }

    // ---------------------------------------------------------------- 错误

    private fun TestScope.errorOf(name: String): ScheduleState.Error { val e = env(name); return runIt(e) as ScheduleState.Error }

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

    @Test fun `a stream that ends without Done is a disconnect`() = runTest {
        val e = env(FakeScript(prompt = listOf(FakeStep.Emit(GatewayEvent.Text("..")))))
        assertEquals(AgentOsError.DISCONNECTED, (runIt(e) as ScheduleState.Error).error)
    }

    @Test fun `an unexpected exception becomes FAILED and the connection is closed`() = runTest {
        var closed = 0
        val bomb = object : AgentOsGateway by FakeAgentOsGateway(FakeScript()) {
            override suspend fun connect(onWaiting: (Waiting) -> Unit) { throw IllegalStateException("boom") }
            override fun close() { closed++ }
        }
        val ledger = ProcessedLedger(InMemoryProcessedStore())
        val uc = SmsScheduleUseCase(backgroundScope, { bomb }, ledger, PromptSettings(InMemoryInstructionStore()) { DEFAULT }, { now }, { Locale.US })
        uc.open(uc.sourceFor("95555", records, false)); uc.start(); settle()
        val end = uc.state.value as ScheduleState.Error
        assertEquals(AgentOsError.FAILED, end.error)
        assertEquals("boom", end.detail)
        assertEquals("close() was called", 1, closed)
        assertTrue(ledger.ids.value.isEmpty())
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
        e.useCase.open(e.source()); e.useCase.start(); runCurrent(); advanceTimeBy(500)
        assertTrue(e.useCase.state.value is ScheduleState.WaitingAuthorization)
        e.useCase.stop()
        settle()
        assertEquals(ScheduleState.Idle, e.useCase.state.value)
        val gw = e.gateways.single()
        assertEquals(1, gw.cancelCount)
        assertTrue(gw.closeCount >= 1)
        assertFalse(e.marker.on)
    }

    @Test fun `stop while running keeps what was created and marks the rest cancelled`() = runTest {
        val e = env("hold_approval")
        e.useCase.open(e.source()); e.useCase.start(); advanceTimeBy(1_000)
        assertTrue((e.useCase.state.value as ScheduleState.Running).awaitingApproval)
        e.useCase.stop()
        settle()
        val end = e.useCase.state.value as ScheduleState.Done
        assertTrue(end.stopped)
        assertEquals(listOf(ItemStatus.CREATED, ItemStatus.CANCELLED), end.summary.items.map { it.status })
    }

    @Test fun `late events from a stopped round are ignored`() = runTest {
        val e = env("success")
        e.useCase.open(e.source()); e.useCase.start(); advanceTimeBy(1_000)
        e.useCase.stop()
        val afterStop = e.useCase.state.value
        settle()
        assertEquals(afterStop, e.useCase.state.value)
        assertTrue("a stopped round never gets recorded as processed, even when its events arrive late", e.ledger.ids.value.isEmpty())
    }

    @Test fun `a second start while running is ignored`() = runTest {
        val e = env("success")
        e.useCase.open(e.source())
        assertTrue(e.useCase.start())
        assertFalse(e.useCase.start())
        runCurrent()
        assertFalse(e.useCase.start())
        settle()
        assertEquals("only one round was ever started", 1, e.gateways.size)
        assertEquals(1, e.gateways.single().promptCount)
    }

    @Test fun `the panel cannot be reopened or closed while a round is running`() = runTest {
        val e = env("hold_running")
        val source = e.source()
        e.useCase.open(source); e.useCase.start(); advanceTimeBy(1_000)
        assertEquals(OpenResult.BUSY, e.useCase.open(e.useCase.sourceFor("10086", listOf(record(9, "x", address = "10086")), false)))
        e.useCase.dismiss()
        assertTrue(e.useCase.state.value is ScheduleState.Running)
        assertEquals(source, e.useCase.state.value.source)
    }

    @Test fun `start does nothing without a Ready or Error state`() = runTest {
        val e = env("success")
        assertFalse(e.useCase.start())
        runIt(e)
        assertFalse("Done is not startable", e.useCase.start())
        assertEquals(1, e.gateways.size)
    }

    @Test fun `a conversation without any text message cannot be opened`() = runTest {
        val e = env("success")
        val onlyDraft = e.useCase.sourceFor("95555", listOf(record(1, "d", box = SmsBox.DRAFT), record(2, "  ")), false)
        assertEquals(OpenResult.EMPTY, e.useCase.open(onlyDraft))
        assertEquals(ScheduleState.Idle, e.useCase.state.value)
        assertTrue(e.gateways.isEmpty())
    }

    // ---------------------------------------------------------------- 进程重建

    @Test fun `a marker left behind means the last round was lost`() = runTest {
        val e = Env(backgroundScope, now, { FakeScript() }, Marker().apply { on = true })
        e.useCase.restoreInterrupted()
        val lost = e.useCase.state.value as ScheduleState.Error
        assertTrue(lost.interrupted)
        assertEquals(AgentOsError.DISCONNECTED, lost.error)
        assertFalse("the marker is consumed", e.marker.on)
        assertTrue("an interrupted round is not processed", e.ledger.ids.value.isEmpty())
        e.useCase.dismiss()
        e.useCase.restoreInterrupted()
        assertEquals("a second call in the same process does nothing", ScheduleState.Idle, e.useCase.state.value)
    }

    // ---------------------------------------------------------------- debug 入口用的 runToEnd

    @Test fun `runToEnd walks the same path as the button and reports the outcome`() = runTest {
        val e = env("success")
        val outcome = e.useCase.runToEnd(e.source(), timeoutMs = 120_000, waitMs = 60_000)
        assertFalse(outcome.pending)
        assertTrue(outcome.state is ScheduleState.Done)
        assertEquals(3, e.ledger.ids.value.size)
    }

    @Test fun `runToEnd stops a round that never ends at the hard timeout`() = runTest {
        val e = env("hold_forever")
        val outcome = e.useCase.runToEnd(e.source(), timeoutMs = 10_000, waitMs = 30_000)
        assertTrue(outcome.timedOut)
        assertTrue((outcome.state as ScheduleState.Done).stopped)
        assertTrue(e.ledger.ids.value.isEmpty())
    }

    @Test fun `runToEnd with nothing unprocessed reports it without sending anything`() = runTest {
        val e = env("success")
        runIt(e)
        e.useCase.dismiss()
        val outcome = e.useCase.runToEnd(e.source(), timeoutMs = 10_000, waitMs = 10_000)
        assertTrue(outcome.state is ScheduleState.Error)
        assertEquals("nothing to send", (outcome.state as ScheduleState.Error).detail)
        assertEquals("no second round was started", 1, e.gateways.size)
        assertEquals(ScheduleState.Idle, e.useCase.state.value)
    }

    private companion object {
        const val DEFAULT = "DEFAULT-INSTRUCTIONS: schedule what the messages ask for."
    }
}
