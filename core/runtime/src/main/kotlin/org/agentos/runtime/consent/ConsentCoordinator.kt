package org.agentos.runtime.consent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.agentos.runtime.ports.Clock
import org.agentos.runtime.ports.ConsentDecision
import org.agentos.runtime.ports.ConsentPort
import org.agentos.runtime.ports.ConsentRequest
import org.agentos.runtime.ports.RuntimeLog
import org.agentos.runtime.ports.info
import org.agentos.runtime.ports.warn

/**
 * 确认协调器（`HostPort.consent` 的实现，architecture F5）：Broker 的每次“要用户确认”都经 [request]。**纯 JVM，不依赖 Android**；
 * 界面经 [ConsentSurface]，“始终允许”的写回经 [ApprovalWriter]。
 *
 * ## 排队与超时
 * - 待确认的请求按到达顺序排队（[pending]，队首在前）。多个会话、一个任务里并行的工具调用都可以同时待确认；界面先显示队首，
 *   任何一条都可以按 requestId 回答（[respond]）。
 * - **每个请求自己的超时（[ConsentRequest.timeoutMillis]，默认 60 秒）从请求创建（进入 [request]）时起算，排队的时间计入**：
 *   到时按 [ConsentDecision.Deny]（TIMEOUT）处理并从 [pending] 移除，界面撤回对话框和通知。
 * - 调用方协程被取消（任务取消）时同样移除并让界面撤回（[ConsentEnd.CANCELLED]）。
 * - 重复的 requestId 直接拒绝（UNAVAILABLE，已有的那条不受影响）；同时待确认超过 [ConsentConfig.maxPending] 条时，新的直接拒绝（UNAVAILABLE）。
 *
 * ## 回答
 * [respond] 对已经超时、已经回答、不存在的 requestId 返回 false（不抛）。选择必须在这条请求允许的选项里
 * （[ConsentView.options]：高风险没有“本会话不再询问”和“始终允许”，没有来源插件的工具没有“始终允许”）；不一致按**拒绝**处理并记日志。
 *
 * ## 始终允许
 * 选了 [ConsentChoice.ALWAYS_ALLOW]：先调 [ApprovalWriter.setAlways]（最多 [ConsentConfig.writeTimeoutMillis]），**成功后才**返回允许；
 * 失败（策略文件坏了、fail closed、IPC 失败、超时）→ 这一次仍然允许（用户点了允许），但**不假装已经保存**：
 * [ConsentResolution.notice] 告诉用户“没能保存，这次已允许”。
 *
 * ## 界面回调
 * [ConsentSurface] 的两个回调在单独的协程里按发生顺序依次调用，不阻塞发起确认的任务。
 */
class ConsentCoordinator(
    private val surface: ConsentSurface,
    private val approvals: ApprovalWriter,
    parentScope: CoroutineScope,
    private val clock: Clock = Clock.SYSTEM,
    private val config: ConsentConfig = ConsentConfig(),
    private val log: RuntimeLog = RuntimeLog.NONE,
) : ConsentPort, AutoCloseable {

    private sealed interface Answer {
        data class Chosen(val choice: ConsentChoice) : Answer

        data object Closed : Answer

        data object TimedOut : Answer
    }

    private class Entry(val request: ConsentRequest, val allowed: List<ConsentChoice>, val base: ConsentView) {
        val answer = CompletableDeferred<Answer>()
    }

    private sealed interface SurfaceEvent {
        class Requested(val view: ConsentView) : SurfaceEvent

        class Resolved(val requestId: String, val resolution: ConsentResolution) : SurfaceEvent
    }

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + CoroutineName("consent"))
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private val pendingFlow = MutableStateFlow<List<ConsentView>>(emptyList())
    private val events = Channel<SurfaceEvent>(Channel.UNLIMITED)

    @Volatile private var closed = false

    /** 现在待确认的请求，队首在前。前台的界面渲染它；用户在后台点通知、之后打开 App 看到的也是同一份。 */
    val pending: StateFlow<List<ConsentView>> = pendingFlow.asStateFlow()

    init {
        scope.launch(CoroutineName("consent-surface")) {
            for (event in events) {
                try {
                    when (event) {
                        is SurfaceEvent.Requested -> surface.requested(event.view)
                        is SurfaceEvent.Resolved -> surface.resolved(event.requestId, event.resolution)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(TAG, "the consent surface failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    override suspend fun request(request: ConsentRequest): ConsentDecision {
        val entry = synchronized(lock) {
            when {
                closed -> return ConsentDecision.Deny(ConsentDecision.DenyReason.UNAVAILABLE)
                entries.containsKey(request.requestId) -> {
                    log.warn(TAG, "duplicate consent request ${request.requestId} refused")
                    return ConsentDecision.Deny(ConsentDecision.DenyReason.UNAVAILABLE)
                }
                entries.size >= config.maxPending -> {
                    log.warn(TAG, "too many pending confirmations (${entries.size}): refused ${request.requestId}")
                    return ConsentDecision.Deny(ConsentDecision.DenyReason.UNAVAILABLE)
                }
            }
            val allowed = ConsentText.allowedChoices(request, approvals.available)
            val e = Entry(request, allowed, view(request, allowed))
            entries[request.requestId] = e
            publish()
            e
        }
        val shown = synchronized(lock) { pendingFlow.value.first { it.requestId == request.requestId } }
        events.trySend(SurfaceEvent.Requested(shown))

        var resolution: ConsentResolution? = null
        try {
            val first = withTimeoutOrNull(request.timeoutMillis) { entry.answer.await() }
            // 到点：原子地把“超时”记为这条请求的结局；同一刻先到的回答保留，之后的 respond 一律返回 false
            val answer = first ?: run {
                entry.answer.complete(Answer.TimedOut)
                entry.answer.await()
            }
            val decision: ConsentDecision
            when {
                answer is Answer.TimedOut -> {
                    resolution = ConsentResolution(ConsentEnd.TIMED_OUT)
                    decision = ConsentDecision.Deny(ConsentDecision.DenyReason.TIMEOUT)
                }
                answer is Answer.Closed -> {
                    resolution = ConsentResolution(ConsentEnd.CLOSED)
                    decision = ConsentDecision.Deny(ConsentDecision.DenyReason.UNAVAILABLE)
                }
                else -> {
                    val choice = (answer as Answer.Chosen).choice
                    when (choice) {
                        ConsentChoice.DENY -> decision = ConsentDecision.Deny(ConsentDecision.DenyReason.USER)
                        ConsentChoice.ALLOW_ONCE -> decision = ConsentDecision.Allow()
                        ConsentChoice.ALLOW_FOR_SESSION -> decision = ConsentDecision.Allow(rememberForSession = true)
                        ConsentChoice.ALWAYS_ALLOW -> {
                            val saved = saveAlways(request)
                            decision = ConsentDecision.Allow()
                            resolution = ConsentResolution(
                                ConsentEnd.ANSWERED, choice,
                                notice = if (saved == null) null else "没能保存“始终允许”（$saved），这次已允许，下次还会询问。",
                            )
                        }
                    }
                    if (resolution == null) resolution = ConsentResolution(ConsentEnd.ANSWERED, choice)
                }
            }
            return decision
        } catch (e: CancellationException) {
            resolution = ConsentResolution(ConsentEnd.CANCELLED)
            throw e
        } finally {
            val r = resolution ?: ConsentResolution(ConsentEnd.CANCELLED)
            entry.answer.complete(Answer.Closed) // 已经结案：此后的 respond 返回 false
            withContext(NonCancellable) { finish(request.requestId, r) }
        }
    }

    /**
     * 回答一条待确认的请求（界面、通知上的按钮、调试用的自动应答调用）。
     * @return 回答被采纳返回 true；已经超时、已经回答、不存在返回 false。选择不在这条请求允许的选项里时按拒绝处理（返回 true）。
     */
    fun respond(requestId: String, choice: ConsentChoice): Boolean {
        val entry = synchronized(lock) { entries[requestId] } ?: return false
        val effective = if (choice in entry.allowed) {
            choice
        } else {
            log.warn(TAG, "choice $choice is not allowed for request $requestId: treated as deny")
            ConsentChoice.DENY
        }
        return entry.answer.complete(Answer.Chosen(effective))
    }

    /** 关闭：所有待确认的请求按拒绝（UNAVAILABLE）结束，不再接受新的。 */
    override fun close() {
        val all = synchronized(lock) {
            closed = true
            entries.values.toList()
        }
        all.forEach { it.answer.complete(Answer.Closed) }
    }

    // ------------------------------------------------------------------ 内部

    /** 写回“始终允许”；成功返回 null，失败返回给用户看的原因（不含参数）。 */
    private suspend fun saveAlways(request: ConsentRequest): String? {
        val source = request.source ?: return "没有来源插件"
        val result = try {
            withTimeoutOrNull(config.writeTimeoutMillis) { approvals.setAlways(source, request.risk) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "saving always-allow failed: ${e.javaClass.simpleName}")
            return "保存时出错"
        } ?: return "保存超时"
        return when (result) {
            ApprovalWriteResult.Saved -> null
            is ApprovalWriteResult.Failed -> {
                log.warn(TAG, "saving always-allow failed: ${result.reason.take(80)}")
                "策略暂时不可写"
            }
        }
    }

    private fun finish(requestId: String, resolution: ConsentResolution) {
        val removed = synchronized(lock) {
            val e = entries.remove(requestId)
            if (e != null) publish()
            e != null
        }
        if (removed) events.trySend(SurfaceEvent.Resolved(requestId, resolution))
    }

    /** 重算 [pending]（持有 [lock]）：队首是 0。 */
    private fun publish() {
        val size = entries.size
        pendingFlow.value = entries.values.mapIndexed { i, e -> e.base.copy(queuePosition = i, queueSize = size) }
    }

    private fun view(request: ConsentRequest, allowed: List<ConsentChoice>): ConsentView {
        val now = clock.nowMillis()
        // 显示名：title 优先；没有就用插件自己的工具名（`note_create`，比 `mcp__notes__notes__note_create` 好认）；最后才是模型调用的名字
        val display = ConsentText.singleLine(request.toolTitle, config.maxDisplayNameChars)
            .ifEmpty { ConsentText.singleLine(request.source?.tool, config.maxDisplayNameChars) }
            .ifEmpty { ConsentText.singleLine(request.toolName, config.maxDisplayNameChars) }
        val (args, cutByUs) = ConsentText.block(request.argumentsPreview, config.maxArgumentChars)
        val source = request.source
        val caller = request.caller
        return ConsentView(
            requestId = request.requestId,
            sessionId = request.sessionId,
            taskId = request.taskId,
            title = ConsentText.title(display),
            initiatorLine = ConsentText.initiatorLine(caller, config.maxDisplayNameChars),
            caller = ConsentCaller(caller.kind, caller.uid, if (caller.kind == org.agentos.runtime.ports.CallerKind.APP) caller.label?.let { ConsentText.singleLine(it, 128) } else null),
            sourceLine = source?.let { ConsentText.sourceLine(it.plugin, it.server, config.maxSourceChars) },
            source = source,
            toolDisplayName = display,
            toolName = ConsentText.singleLine(request.toolName, config.maxDisplayNameChars),
            argumentsPreview = args,
            argumentsTruncated = cutByUs || request.argumentsTruncated,
            risk = request.risk,
            severity = ConsentText.severity(request.risk),
            riskLabel = ConsentText.riskLabel(request.risk),
            riskDescription = ConsentText.riskDescription(request.risk),
            options = allowed.map { ConsentOption(it, ConsentText.optionLabel(it), destructive = it == ConsentChoice.DENY) },
            createdAtMillis = now,
            deadlineMillis = now + request.timeoutMillis,
            timeoutMillis = request.timeoutMillis,
            queuePosition = 0,
            queueSize = 1,
        )
    }

    private companion object {
        const val TAG = "Consent"
    }
}
