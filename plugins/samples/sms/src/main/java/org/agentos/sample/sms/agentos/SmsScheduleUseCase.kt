package org.agentos.sample.sms.agentos

import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.agentos.sample.sms.data.SmsRecord

/** 面板的状态机：Ready → Checking → WaitingAuthorization → Running → Done / Error（Idle 表示面板没开）。 */
sealed interface ScheduleState {
    val source: ScheduleSource

    data object Idle : ScheduleState {
        override val source: ScheduleSource get() = ScheduleSource.NONE
    }

    /** 预览将要发送的短信和任务说明，等用户点“开始”。 */
    data class Ready(override val source: ScheduleSource) : ScheduleState

    /** 检查 AgentOS 是否可用并连接。 */
    data class Checking(override val source: ScheduleSource) : ScheduleState

    /** 第一次使用：等用户在 AgentOS 的提示里允许短信 App。 */
    data class WaitingAuthorization(override val source: ScheduleSource) : ScheduleState

    /** Agent 在工作：[text] 是它流式说出来的话，[items] 是工具卡片。 */
    data class Running(override val source: ScheduleSource, val items: List<ScheduleItem>, val text: String) : ScheduleState {
        val awaitingApproval: Boolean get() = items.any { it.status == ItemStatus.AWAITING_APPROVAL }
    }

    /** 一轮正常结束（或被停止 / 被 AgentOS 取消：[stopped]）。 */
    data class Done(override val source: ScheduleSource, val summary: ScheduleSummary, val text: String, val stopped: Boolean) : ScheduleState

    /**
     * 出错。[summary] 是出错之前已经有结果的项（可能已经创建了一部分）；[interrupted] 表示进程被系统回收，上次的任务丢了。
     */
    data class Error(
        override val source: ScheduleSource,
        val error: AgentOsError,
        val detail: String? = null,
        val summary: ScheduleSummary = ScheduleSummary(emptyList()),
        val interrupted: Boolean = false,
    ) : ScheduleState

    val inFlight: Boolean get() = this is Checking || this is WaitingAuthorization || this is Running
    val isTerminal: Boolean get() = this is Done || this is Error
}

enum class OpenResult { OPENED, EMPTY, TOO_LONG, BUSY }

/** 进程被杀时留下的标记：开始一轮时记下，结束时清掉；进程重建后还在，说明上一轮没有走完。 */
interface RunMarker {
    fun set()
    fun clear()
    fun isSet(): Boolean

    object None : RunMarker {
        override fun set() = Unit
        override fun clear() = Unit
        override fun isSet() = false
    }
}

/**
 * “让 AgentOS 处理短信”的用例：会话页的按钮和 debug 的 `ask_agent` 走的都是它。
 *
 * - 来源是**一个会话里还没处理过的短信原文**（[open]）；面板里可以改任务说明（[setInstructions]）、把已处理过的也带上（[setIncludeProcessed]）；
 * - 同一时间只有一轮（运行中再点开始 / 再打开面板都不会再发），迟到的旧事件按轮次丢弃；
 * - 一轮**正常结束**（没被停止、没出错）后，发出去的那几条记入 [ledger]（已处理）；停止和出错不记，可以再试；
 * - 停止 = 通知 AgentOS 取消 + 取消协程 + 关闭连接（连接总是在 finally 里关）；
 * - 进程重建：[restoreInterrupted] 发现上一轮的标记还在，就进入 Error(DISCONNECTED, interrupted=true) 并说明；
 * - 整个对象是进程内单例（SmsGraph），旋转、切页签都不影响进行中的一轮。
 */
class SmsScheduleUseCase(
    private val scope: CoroutineScope,
    private val gatewayFactory: () -> AgentOsGateway,
    private val ledger: ProcessedLedger,
    private val prompt: PromptSettings,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val locale: () -> Locale = { Locale.getDefault() },
    private val marker: RunMarker = RunMarker.None,
    /** 把来源变成发给 AgentOS 的提示词。默认是 [SmsSchedulePrompt.build]；只有 debug 包能换成别的（见 GatewayProvider）。 */
    private val promptFor: (ScheduleSource, ZonedDateTime, Locale) -> String = { source, now, locale -> SmsSchedulePrompt.build(source, now, locale) },
) {
    private val lock = Any()
    private val _state = MutableStateFlow<ScheduleState>(ScheduleState.Idle)
    val state: StateFlow<ScheduleState> = _state.asStateFlow()

    /** 最近一轮是不是因为 [runToEnd] 的硬超时被停止的。 */
    @Volatile
    var lastRunTimedOut: Boolean = false
        private set

    private var generation = 0
    private var job: Job? = null
    private var gateway: AgentOsGateway? = null

    /** 最近一轮的状态（含进行中、被停止、进程重建后的“中断”）；debug 的 ask_agent_status 读它。 */
    @Volatile
    var lastRun: ScheduleState? = null
        private set

    /** 当前生效的任务说明（用户改过的，或默认的）。 */
    val instructions: String get() = prompt.effective
    val defaultInstructions: String get() = prompt.defaultText
    val instructionsCustomized: Boolean get() = prompt.isCustom

    /** 用 [records]（这个会话的短信，任意顺序）建一份来源：默认只带还没处理过的。 */
    fun sourceFor(address: String, records: List<SmsRecord>, maskCodes: Boolean, includeProcessed: Boolean = false): ScheduleSource =
        ScheduleSource(address, SmsLine.from(records, maskCodes, ledger.ids.value), includeProcessed, prompt.effective)

    /** 打开面板预览。正在跑的时候不换内容（返回 BUSY）；这个会话里一条短信都没有返回 EMPTY。 */
    fun open(source: ScheduleSource): OpenResult = synchronized(lock) {
        if (_state.value.inFlight) return OpenResult.BUSY
        if (source.candidates.isEmpty()) return OpenResult.EMPTY
        if (!SmsSchedulePrompt.fits(source, clock(), locale())) return OpenResult.TOO_LONG
        _state.value = ScheduleState.Ready(source)
        OpenResult.OPENED
    }

    /** 面板上“包含已处理过的短信”开关。只在预览（Ready）时有效。 */
    fun setIncludeProcessed(on: Boolean) {
        synchronized(lock) {
            val current = _state.value as? ScheduleState.Ready ?: return
            if (current.source.includeProcessed == on) return
            _state.value = ScheduleState.Ready(current.source.copy(includeProcessed = on))
        }
    }

    /**
     * 面板上改任务说明：保存（和默认一样或空白就恢复默认），预览里立刻用新的。只在预览（Ready）时有效。
     * 返回实际生效的文字（界面据此更新编辑框）。
     */
    fun setInstructions(text: String): String {
        synchronized(lock) {
            val applied = prompt.save(text)
            val current = _state.value as? ScheduleState.Ready ?: return applied
            _state.value = ScheduleState.Ready(current.source.copy(instructions = applied))
            return applied
        }
    }

    /** 恢复默认任务说明；返回默认文字。 */
    fun resetInstructions(): String = setInstructions(prompt.defaultText)

    /** 点“开始”（或出错后“重试”）。已经在跑、或没有要发送的短信返回 false，什么也不做。 */
    fun start(): Boolean {
        val run: Int
        val source: ScheduleSource
        val gw: AgentOsGateway
        synchronized(lock) {
            val current = _state.value
            if (current.inFlight) return false
            source = when (current) {
                is ScheduleState.Ready -> current.source
                is ScheduleState.Error -> current.source
                else -> return false
            }
            if (!source.hasText) return false
            run = ++generation
            lastRunTimedOut = false
            gw = gatewayFactory()
            gateway = gw
            marker.set()
            _state.value = ScheduleState.Checking(source)
            job = scope.launch { execute(run, source, gw) }
        }
        return true
    }

    /** 点“停止 / 取消”：通知 AgentOS 取消，关闭连接。已经创建的项会留在汇总里；这一轮的短信不记为已处理。 */
    fun stop() {
        val gw: AgentOsGateway
        val running: Job?
        synchronized(lock) {
            val current = _state.value
            if (!current.inFlight) return
            gw = gateway ?: return
            generation++ // 让这一轮之后的事件作废
            running = job
            val stopped = stoppedDone(current)
            lastRun = stopped // 先记下 lastRun 再发布状态：别的线程一看到状态变了，读到的 lastRun 就是新的
            _state.value = if (stopped.summary.isEmpty) ScheduleState.Idle else stopped
            marker.clear()
        }
        scope.launch(NonCancellable) {
            try {
                withTimeoutOrNull(CANCEL_TIMEOUT_MS) { gw.cancel() }
            } catch (_: Exception) {
            }
            running?.cancel()
            gw.close()
        }
    }

    /** 关闭面板。运行中不处理（要先 [stop]）。 */
    fun dismiss() {
        synchronized(lock) {
            if (_state.value.inFlight) return
            _state.value = ScheduleState.Idle
        }
    }

    /** 把 AgentOS 里待决的授权 / 确认提示带到前台。 */
    fun bringApprovalToFront() {
        val gw = synchronized(lock) { gateway } ?: return
        gw.bringApprovalToFront()
    }

    private var restoreChecked = false

    /**
     * 进程启动后第一次调用才有效（旋转屏幕等同进程内的重复调用什么也不做）：上一轮还没走完进程就没了（标记还在）时，
     * 进入 Error(DISCONNECTED, interrupted)，面板会说明“上次的任务被中断了，可能已经创建了一部分”。
     */
    fun restoreInterrupted() {
        synchronized(lock) {
            if (restoreChecked) return
            restoreChecked = true
            if (!marker.isSet()) return
            marker.clear()
            if (_state.value != ScheduleState.Idle) return
            val lost = ScheduleState.Error(ScheduleSource.NONE, AgentOsError.DISCONNECTED, interrupted = true)
            lastRun = lost
            _state.value = lost
        }
    }

    /**
     * debug 用：和按钮同一条路径（open → start），然后最多等 [waitMs] 毫秒看这一轮是否走到 Done / Error。
     * 这一轮自己有 [timeoutMs] 的硬超时：到点还没完就停止（[lastRunTimedOut] 为 true），哪怕调用方早就不等了。
     * 没等到结束返回 `pending = true`，调用方稍后读 [lastRun] / [state]。
     * （广播接收器的后台广播超时是 60 秒，所以 debug 入口的 [waitMs] 取 45 秒，更长的轮次让调用方轮询。）
     */
    suspend fun runToEnd(source: ScheduleSource, timeoutMs: Long, waitMs: Long = timeoutMs + 2_000): RunOutcome {
        when (open(source)) {
            OpenResult.OPENED -> Unit
            OpenResult.TOO_LONG -> return RunOutcome(ScheduleState.Error(source, AgentOsError.TOO_LARGE), timedOut = false, pending = false)
            OpenResult.EMPTY -> return RunOutcome(ScheduleState.Error(source, AgentOsError.FAILED, "nothing to send"), timedOut = false, pending = false)
            OpenResult.BUSY -> return RunOutcome(ScheduleState.Error(source, AgentOsError.BUSY, "another run is in progress"), timedOut = false, pending = false)
        }
        if (!start()) {
            dismiss()
            return RunOutcome(ScheduleState.Error(source, AgentOsError.FAILED, "nothing to send"), timedOut = false, pending = false)
        }
        val run = synchronized(lock) { generation }
        scope.launch {
            delay(timeoutMs)
            val expired = synchronized(lock) { (generation == run && _state.value.inFlight).also { if (it) lastRunTimedOut = true } }
            if (expired) stop()
        }
        val ended = withTimeoutOrNull(waitMs) { _state.first { !it.inFlight } }
        if (ended == null) return RunOutcome(lastRun ?: _state.value, timedOut = false, pending = true)
        return RunOutcome(lastRun ?: ended, timedOut = lastRunTimedOut, pending = false)
    }

    data class RunOutcome(val state: ScheduleState, val timedOut: Boolean, val pending: Boolean = false)

    // ---------------------------------------------------------------- 一轮的执行

    private suspend fun execute(run: Int, source: ScheduleSource, gw: AgentOsGateway) {
        var items = emptyList<ScheduleItem>()
        val text = StringBuilder()
        var finished = false
        var cancelledByAgentOs = false
        try {
            if (!gw.isAvailable()) throw AgentOsException(AgentOsError.NOT_INSTALLED)
            gw.connect { waiting ->
                if (waiting == Waiting.AUTHORIZATION) transition(run) { if (it is ScheduleState.Checking) ScheduleState.WaitingAuthorization(source) else it }
            }
            gw.newSession(SmsToolScope)
            transition(run) { if (it.inFlight) ScheduleState.Running(source, emptyList(), "") else it }
            gw.prompt(promptFor(source, clock(), locale())).collect { event ->
                when (event) {
                    is GatewayEvent.Text -> {
                        text.append(event.chunk)
                        if (text.length > MAX_TEXT_CHARS) text.delete(0, text.length - MAX_TEXT_CHARS)
                        transition(run) { if (it is ScheduleState.Running) it.copy(text = text.toString()) else it }
                    }
                    is GatewayEvent.ToolCall -> {
                        items = ScheduleItems.apply(items, event)
                        transition(run) { if (it is ScheduleState.Running) it.copy(items = items) else it }
                    }
                    is GatewayEvent.Done -> {
                        finished = true
                        cancelledByAgentOs = event.stopReason == STOP_CANCELLED
                        val summary = ScheduleSummary(ScheduleItems.finalize(items))
                        finish(run, ScheduleState.Done(source, summary, text.toString().trim(), stopped = cancelledByAgentOs))
                    }
                }
            }
            if (!finished) {
                finish(run, errorState(source, AgentOsException(AgentOsError.DISCONNECTED, "the stream ended without a result"), items))
            } else if (!cancelledByAgentOs) {
                // 流干净地走完（Done 之后没有再出错）、没有被停止、没有被 AgentOS 取消：发出去的这几条记为已处理
                markProcessed(run, source)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finish(run, errorState(source, e, items))
        } finally {
            withContext(NonCancellable) {
                try {
                    gw.close()
                } catch (_: Exception) {
                }
                synchronized(lock) {
                    if (gateway === gw) gateway = null
                    if (run == generation) marker.clear()
                }
            }
        }
    }

    private fun errorState(source: ScheduleSource, e: Exception, items: List<ScheduleItem>): ScheduleState.Error {
        val error = (e as? AgentOsException)?.error ?: AgentOsError.FAILED
        val detail = if (e is AgentOsException) e.message?.takeIf { it != error.name } else (e.message ?: e.javaClass.simpleName)
        return ScheduleState.Error(source, error, detail?.take(MAX_DETAIL_CHARS), ScheduleSummary(ScheduleItems.finalize(items)))
    }

    private fun transition(run: Int, change: (ScheduleState) -> ScheduleState) {
        synchronized(lock) {
            if (run != generation) return
            val next = change(_state.value)
            lastRun = next
            _state.value = next
        }
    }

    private fun finish(run: Int, terminal: ScheduleState) {
        synchronized(lock) {
            if (run != generation) return
            lastRun = terminal
            _state.value = terminal
        }
    }

    /** 只有这一轮还是当前一轮（没被停止、没被后来的一轮顶掉）才记账。 */
    private fun markProcessed(run: Int, source: ScheduleSource) {
        synchronized(lock) {
            if (run != generation || _state.value !is ScheduleState.Done) return
            ledger.mark(source.lines.map { it.id })
        }
    }

    /** 停止时的结果：有结果的项留着，没有结果的记为“已取消”。界面上一项都没有就直接关面板，但 [lastRun] 仍记着“已停止”。 */
    private fun stoppedDone(current: ScheduleState): ScheduleState.Done {
        val running = current as? ScheduleState.Running
        return ScheduleState.Done(current.source, ScheduleSummary(ScheduleItems.finalize(running?.items ?: emptyList())), running?.text?.trim() ?: "", stopped = true)
    }

    private companion object {
        const val STOP_CANCELLED = "cancelled"
        const val MAX_TEXT_CHARS = 4_000
        const val MAX_DETAIL_CHARS = 300
        const val CANCEL_TIMEOUT_MS = 3_000L
    }
}
