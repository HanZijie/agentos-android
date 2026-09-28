package org.agentos.runtime.scheduler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.Ids
import org.agentos.runtime.broker.CapabilityBroker
import org.agentos.runtime.errors.AgentOsException
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.errors.ErrorInfo
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.events.PendingEvent
import org.agentos.runtime.ports.AgentSessionConfig
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.FinishReason
import org.agentos.runtime.ports.HostPort
import org.agentos.runtime.ports.TurnOutcome
import org.agentos.runtime.ports.warn
import org.agentos.runtime.store.SessionState
import org.agentos.runtime.store.Store
import org.agentos.runtime.store.StoreTx
import org.agentos.runtime.store.TaskRecord
import org.agentos.runtime.store.TaskState
import org.agentos.runtime.store.ToolCallState
import java.util.concurrent.ConcurrentHashMap

/**
 * 调度器（core/contracts/session-scheduling.md）：
 *
 * - 同一会话串行、按提交顺序执行；不同会话并行，受全局和每个调用方的上限约束；就绪的会话按就绪先后轮转；
 * - 取消：排队的直接取消；运行中的请 Agent core abort，宽限期内没停下就标记为结果未知（不假装已取消）；
 * - 执行 deadline 到了按取消处理，任务以 execution_timeout 失败；
 * - 泵故障：进行中的任务标记为结果未知、会话暂停（等用户决定），不自动重放；
 * - safe mode：不接受新任务，也不启动排队的任务。
 *
 * 所有状态转换与对应事件在同一个事务里提交。
 */
internal class Scheduler(
    private val scope: CoroutineScope,
    private val store: Store,
    private val host: HostPort,
    private val cores: CoreSessions,
    private val broker: CapabilityBroker,
    private val config: SchedulerConfig,
) {
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val running = ConcurrentHashMap<String, Running>()
    private val waiters = ConcurrentHashMap<String, MutableList<CompletableDeferred<TaskRecord>>>()
    private val _counts = MutableStateFlow(Counts())
    val counts: StateFlow<Counts> = _counts.asStateFlow()
    private var loopJob: Job? = null

    data class Counts(val running: Int = 0, val queued: Int = 0, val recoveryPending: Int = 0, val lastError: ErrorInfo? = null)

    /** 一个正在运行（含启动中）的任务。启动前就登记，保证并发上限和取消都看得到它。 */
    private class Running(val taskId: String, val ownerKey: String, val executionDeadline: Long?) {
        @Volatile var runner: TaskRunner? = null
        @Volatile var job: Job? = null
        @Volatile var cancelReason: String? = null
        @Volatile var cancelDeadline: Long? = null

        /** 标记为结果未知；没有 runner 时用自己的标志。返回是否是第一次标记。 */
        private val fencedFlag = java.util.concurrent.atomic.AtomicBoolean(false)

        fun fence(): Boolean = fencedFlag.compareAndSet(false, true).also { if (it) runner?.fence() }

        val fenced: Boolean get() = fencedFlag.get()
    }

    fun start() {
        check(loopJob == null)
        loopJob = scope.launch {
            launch { while (isActive) { wakeups.receive(); dispatchReady() } }
            launch { while (isActive) { delay(config.tickMillis); tick() } }
        }
        wake()
        scope.launch { refreshCounts() }
        // safe mode 结束时恢复调度
        scope.launch { host.environment.safeMode.collect { if (!it.active) wake() } }
    }

    fun wake() {
        wakeups.trySend(Unit)
    }

    // ------------------------------------------------------------------ 提交

    /**
     * 把一轮输入写进会话的队列（持久化后返回）。[clientRequestId] 相同且内容相同时返回原任务（幂等），内容不同返回 request_conflict。
     */
    suspend fun submit(sessionId: String, caller: CallerIdentity, input: JsonArray, clientRequestId: String? = null): TaskRecord {
        if (host.environment.safeMode.value.active) throw AgentOsException(ErrorCode.SAFE_MODE, "AgentOS is in safe mode")
        val task = store.write { tx ->
            val s = tx.sessions.get(sessionId) ?: throw AgentOsException(ErrorCode.SESSION_NOT_FOUND, "no such session")
            if (clientRequestId != null) {
                tx.tasks.findByClientRequest(sessionId, clientRequestId)?.let { existing ->
                    if (!tx.tasks.sameInput(existing, input)) throw AgentOsException(ErrorCode.REQUEST_CONFLICT, "request id reused with different content")
                    return@write existing
                }
            }
            when {
                s.state.terminal -> throw AgentOsException(ErrorCode.SESSION_TERMINAL, "session is ${s.state.wire}")
                s.state == SessionState.CANCELLING -> throw AgentOsException(ErrorCode.INVALID_STATE, "session is cancelling")
                s.pauseReason == PAUSE_RECOVERY -> throw AgentOsException(ErrorCode.RECOVERY_REQUIRED, "a previous task needs a recovery decision")
            }
            if (tx.tasks.countQueued(sessionId) >= config.maxQueuedPerSession) throw AgentOsException(ErrorCode.BUSY, "too many queued prompts")
            val now = tx.now
            val t = tx.tasks.create(
                Ids.task(now), sessionId, input, caller, clientRequestId, now,
                if (config.queueTimeoutMillis > 0) now + config.queueTimeoutMillis else null,
            )
            tx.sessions.setFirstQueryIfAbsent(sessionId, PromptContent.plainText(input))
            tx.sessions.touch(sessionId, now)
            tx.events.append(
                PendingEvent(
                    sessionId, t.id, EventTypes.TASK_QUEUED,
                    buildJsonObject {
                        put("input", input)
                        put("caller", buildJsonObject { put("uid", caller.uid); put("kind", caller.kind.name.lowercase()) })
                        put("position", t.position)
                    },
                ),
            )
            if (s.state == SessionState.CREATED) changeState(tx, sessionId, SessionState.QUEUED, "task_queued")
            t
        }
        refreshCounts()
        wake()
        return task
    }

    /** 等任务到达终态，或变成结果未知（需要恢复）。 */
    suspend fun awaitSettled(taskId: String): TaskRecord {
        val waiter = CompletableDeferred<TaskRecord>()
        waiters.getOrPut(taskId) { java.util.Collections.synchronizedList(mutableListOf()) }.add(waiter)
        val current = store.read { it.tasks.get(taskId) } ?: throw AgentOsException(ErrorCode.TASK_NOT_FOUND, "no such task")
        if (current.state.terminal || current.state == TaskState.UNKNOWN) return current
        return waiter.await()
    }

    private suspend fun notifySettled(taskId: String) {
        val list = waiters.remove(taskId) ?: return
        val record = store.read { it.tasks.get(taskId) } ?: return
        synchronized(list) { list.forEach { it.complete(record) } }
    }

    // ------------------------------------------------------------------ 取消

    /**
     * 取消会话里所有未结束的任务（ACP session/cancel）。返回被请求取消的任务 ID。
     * [taskId] 不为 null 时只取消这一个。
     */
    suspend fun cancel(sessionId: String, by: String, taskId: String? = null): List<String> {
        val runner = running[sessionId]?.runner
        val (cancelledQueued, requestedRunning) = store.write { tx ->
            val tasks = tx.tasks.listBySession(sessionId).filter { taskId == null || it.id == taskId }
            val queued = mutableListOf<String>()
            var runningId: String? = null
            for (t in tasks) {
                when (t.state) {
                    TaskState.QUEUED -> {
                        tx.events.append(PendingEvent(sessionId, t.id, EventTypes.TASK_CANCEL_REQUESTED, phase(by, "queued")))
                        tx.tasks.finish(t.id, TaskState.CANCELLED, tx.now, "cancelled", null)
                        tx.events.append(
                            PendingEvent(sessionId, t.id, EventTypes.TASK_CANCELLED, buildJsonObject { put("phase", "queued"); put("unknownToolCalls", JsonArray(emptyList())) }),
                        )
                        queued += t.id
                    }
                    TaskState.RUNNING -> {
                        val phase = if (runner?.taskId == t.id && runner.inTool) "tool" else "model"
                        tx.tasks.markCancelling(t.id, by)
                        tx.events.append(PendingEvent(sessionId, t.id, EventTypes.TASK_CANCEL_REQUESTED, phase(by, phase)))
                        changeState(tx, sessionId, SessionState.CANCELLING, "cancel_requested")
                        runningId = t.id
                    }
                    else -> Unit
                }
            }
            if (runningId == null && queued.isNotEmpty()) {
                val s = tx.sessions.get(sessionId)
                if (s != null && s.state == SessionState.QUEUED && tx.tasks.countQueued(sessionId) == 0) {
                    changeState(tx, sessionId, SessionState.CREATED, "queue_empty")
                }
            }
            queued to runningId
        }
        cancelledQueued.forEach { notifySettled(it) }
        if (requestedRunning != null) {
            val r = running[sessionId]
            if (r != null && r.taskId == requestedRunning) {
                r.cancelReason = by
                r.cancelDeadline = host.clock.nowMillis() + config.cancelGraceMillis
                if (r.runner != null) runCatching { cores.peek(sessionId)?.abort() }
            }
        }
        refreshCounts()
        return cancelledQueued + listOfNotNull(requestedRunning)
    }

    private fun phase(by: String, phase: String): JsonObject = buildJsonObject {
        put("by", by)
        put("phase", phase)
    }

    // ------------------------------------------------------------------ 调度

    private suspend fun dispatchReady() {
        if (host.environment.safeMode.value.active) return
        val ready = store.read { it.sessions.listByState(SessionState.QUEUED) }
        for (s in ready) {
            if (running.size >= config.maxRunningSessions) break
            if (running.containsKey(s.id)) continue
            if (running.values.count { it.ownerKey == s.ownerKey } >= config.maxRunningPerOwner) continue
            startNext(s.id, s.ownerKey)
        }
    }

    private suspend fun startNext(sessionId: String, ownerKey: String) {
        val model = host.models.activeModel.value
        val started = store.write { tx ->
            val t = tx.tasks.nextQueued(sessionId)
            if (t == null) {
                changeState(tx, sessionId, SessionState.CREATED, "queue_empty")
                return@write null
            }
            if (model == null) {
                failTask(tx, t, ErrorCode.MODEL_NOT_CONFIGURED.info("No model is configured. Choose a model and enter its key in AgentOS settings."), "not_started")
                afterTask(tx, sessionId)
                return@write t.copy(state = TaskState.FAILED)
            }
            val deadline = if (config.executionTimeoutMillis > 0) tx.now + config.executionTimeoutMillis else null
            val rec = tx.tasks.markStarted(t.id, tx.now, deadline)
            changeState(tx, sessionId, SessionState.RUNNING, "task_started")
            tx.events.append(PendingEvent(sessionId, t.id, EventTypes.TASK_STARTED, buildJsonObject { put("attempt", rec.attempt) }))
            rec
        } ?: return
        if (started.state == TaskState.FAILED) {
            notifySettled(started.id)
            refreshCounts()
            wake()
            return
        }
        launchRunner(started, ownerKey, model!!)
        refreshCounts()
    }

    private fun launchRunner(task: TaskRecord, ownerKey: String, model: org.agentos.runtime.ports.ModelSpec) {
        val caller = CallerIdentity(task.callerUid, task.callerKind, task.callerLabel)
        val sessionConfig = AgentSessionConfig(model, config.systemPrompt, broker.declarations(), config.maxToolRounds)
        val entry = Running(task.id, ownerKey, task.executionDeadline)
        running[task.sessionId] = entry
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val outcome: TurnOutcome = try {
                val coreSession = cores.acquire(task.sessionId, sessionConfig)
                val runner = TaskRunner(task.sessionId, task.id, ownerKey, caller, coreSession, broker, store, config)
                entry.runner = runner
                if (entry.fenced) runner.fence()
                if (entry.cancelReason == null) cancelledBeforeStart(task)?.let { entry.cancelReason = it }
                if (entry.cancelReason != null) TurnOutcome.Aborted else runner.run(PromptContent.toTurnInput(task.input))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                host.log.warn(TAG, "task ${task.id} could not start: ${e.javaClass.simpleName}")
                TurnOutcome.Failed(ErrorCode.AGENT_CORE_FAILED.info("The agent core could not start."))
            }
            withContext(NonCancellable) { finish(task, entry, outcome) }
        }
        entry.job = job
        job.start()
    }

    /** 启动前已经被请求取消（取消与启动交错）：返回原因。 */
    private suspend fun cancelledBeforeStart(task: TaskRecord): String? =
        store.read { it.tasks.get(task.id) }?.takeIf { it.state == TaskState.CANCELLING }?.cancelReason

    private suspend fun finish(task: TaskRecord, entry: Running, outcome: TurnOutcome) {
        val runner = entry.runner
        if (!entry.fence()) {
            // 已经按结果未知处理过（取消宽限期超时），这一轮迟到的结局一律丢弃
            running.remove(task.sessionId, entry)
            wake()
            return
        }
        if (outcome is TurnOutcome.CoreLost) {
            // 先记下原因（系统流 agent_core.failed），再把这次执行标记为结果未知
            cores.onCoreLost(outcome.error, running.size)
            loseTask(task, "agent_core_failed", outcome.error)
            running.remove(task.sessionId, entry)
            refreshCounts()
            wake()
            return
        }
        val messages = runCatching { cores.peek(task.sessionId)?.messages() }.getOrNull()
        store.write { tx ->
            val current = tx.tasks.get(task.id) ?: return@write
            if (current.state.terminal || current.state == TaskState.UNKNOWN) return@write
            messages?.let { tx.sessions.saveMessages(task.sessionId, it, tx.now, stable = true) }
            val reason = entry.cancelReason ?: current.cancelReason
            when (outcome) {
                is TurnOutcome.Finished -> {
                    val stop = when (outcome.reason) {
                        FinishReason.END_TURN -> "end_turn"
                        FinishReason.MAX_TOKENS -> "max_tokens"
                        FinishReason.REFUSAL -> "refusal"
                    }
                    complete(tx, current, stop, outcome.usage)
                    tx.sessions.recordCompletedTurn(task.sessionId, PromptContent.plainText(task.input), runner?.finalAnswer.orEmpty())
                }
                is TurnOutcome.ToolRoundLimit -> {
                    tx.events.append(
                        PendingEvent(task.sessionId, task.id, EventTypes.TOOL_ROUND_LIMIT, buildJsonObject { put("rounds", outcome.rounds); put("limit", outcome.limit) }),
                    )
                    complete(tx, current, "max_turn_requests", null)
                }
                TurnOutcome.Aborted -> when (reason) {
                    CANCEL_TIMEOUT -> failTask(tx, current, ErrorCode.EXECUTION_TIMEOUT.info("The task exceeded its deadline."), "failed")
                    else -> {
                        val unknown = tx.tasks.toolCalls(task.id).filter { it.state == ToolCallState.CANCELLED || it.state == ToolCallState.UNKNOWN }
                        tx.tasks.finish(task.id, TaskState.CANCELLED, tx.now, "cancelled", null)
                        tx.events.append(
                            PendingEvent(
                                task.sessionId, task.id, EventTypes.TASK_CANCELLED,
                                buildJsonObject {
                                    put("phase", if (unknown.isEmpty()) "model" else "tool")
                                    put("unknownToolCalls", buildJsonArray { unknown.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.toolCallId)) } })
                                },
                            ),
                        )
                    }
                }
                is TurnOutcome.Failed -> failTask(tx, current, outcome.error, "failed")
                is TurnOutcome.CoreLost -> Unit
            }
            tx.sessions.touch(task.sessionId, tx.now)
            afterTask(tx, task.sessionId)
        }
        running.remove(task.sessionId, entry)
        if (outcome is TurnOutcome.Failed) _counts.value = _counts.value.copy(lastError = outcome.error)
        notifySettled(task.id)
        refreshCounts()
        wake()
    }

    private fun complete(tx: StoreTx, task: TaskRecord, stopReason: String, usage: JsonObject?) {
        tx.tasks.finish(task.id, TaskState.COMPLETED, tx.now, stopReason, null)
        tx.events.append(
            PendingEvent(task.sessionId, task.id, EventTypes.TASK_COMPLETED, buildJsonObject { put("stopReason", stopReason); usage?.let { put("usage", it) } }),
        )
    }

    private fun failTask(tx: StoreTx, task: TaskRecord, error: ErrorInfo, attemptState: String) {
        tx.tasks.finish(task.id, TaskState.FAILED, tx.now, null, error)
        tx.events.append(
            PendingEvent(task.sessionId, task.id, EventTypes.TASK_FAILED, buildJsonObject { put("attempt", task.attempt); put("attemptState", attemptState) }, error),
        )
    }

    /** 一个任务结束后会话的去向：还有排队的 → queued（排到轮转队尾），否则 created；暂停和终态不动。 */
    private fun afterTask(tx: StoreTx, sessionId: String) {
        val s = tx.sessions.get(sessionId) ?: return
        if (s.state.terminal || s.state == SessionState.PAUSED) return
        changeState(tx, sessionId, if (tx.tasks.countQueued(sessionId) > 0) SessionState.QUEUED else SessionState.CREATED, "task_finished")
    }

    /** 这次执行的结果未知：任务 unknown、已派发未返回的工具调用 unknown、会话暂停等用户决定。不重放。 */
    private suspend fun loseTask(task: TaskRecord, reason: String, error: ErrorInfo) {
        store.write { tx ->
            val current = tx.tasks.get(task.id) ?: return@write
            if (current.state.terminal || current.state == TaskState.UNKNOWN) return@write
            markRecoveryRequired(tx, current, reason, error)
        }
        cores.discard(task.sessionId)
        notifySettled(task.id)
    }

    // ------------------------------------------------------------------ deadline

    private suspend fun tick() {
        val now = host.clock.nowMillis()
        for ((sessionId, r) in running) {
            val cancelDeadline = r.cancelDeadline
            if (cancelDeadline != null && now >= cancelDeadline) {
                if (r.fence()) {
                    host.log.warn(TAG, "task ${r.taskId} did not stop within the cancel grace period")
                    val task = store.read { it.tasks.get(r.taskId) }
                    if (task != null) loseTask(task, "cancel_grace_exceeded", ErrorCode.TOOL_RESULT_UNKNOWN.info("The task did not stop after cancellation."))
                    r.job?.cancel()
                    running.remove(sessionId, r)
                    refreshCounts()
                    wake()
                }
                continue
            }
            val deadline = r.executionDeadline
            if (deadline != null && now >= deadline && r.cancelReason == null) {
                cancel(sessionId, CANCEL_TIMEOUT, r.taskId)
            }
        }
        if (config.queueTimeoutMillis > 0) expireQueued(now)
    }

    private suspend fun expireQueued(now: Long) {
        val expired = store.write { tx ->
            tx.tasks.listByState(TaskState.QUEUED).filter { it.queueDeadline != null && it.queueDeadline <= now }.onEach { t ->
                failTask(tx, t, ErrorCode.QUEUE_TIMEOUT.info("The prompt waited too long in the queue."), "not_started")
                val s = tx.sessions.get(t.sessionId)
                if (s != null && s.state == SessionState.QUEUED && tx.tasks.countQueued(t.sessionId) == 0) changeState(tx, t.sessionId, SessionState.CREATED, "queue_empty")
            }
        }
        expired.forEach { notifySettled(it.id) }
        if (expired.isNotEmpty()) refreshCounts()
    }

    // ------------------------------------------------------------------ 恢复决定

    /** 用户放弃一个结果未知的任务（W10 会加上“重试”）。 */
    suspend fun abandon(sessionId: String, taskId: String, by: String) {
        store.write { tx ->
            val t = tx.tasks.get(taskId)
            if (t == null || t.sessionId != sessionId) throw AgentOsException(ErrorCode.TASK_NOT_FOUND, "no such task")
            if (t.state != TaskState.UNKNOWN) throw AgentOsException(ErrorCode.INVALID_STATE, "task is ${t.state.wire}")
            tx.events.append(PendingEvent(sessionId, taskId, EventTypes.TASK_RECOVERY_RESOLVED, buildJsonObject { put("decision", "abandon"); put("by", by) }))
            failTask(tx, t, ErrorCode.ABANDONED.info("The user abandoned this task after an interruption."), "unknown")
            tx.sessions.rollbackMessagesToStable(sessionId, tx.now)
            if (tx.tasks.listBySession(sessionId).none { it.state == TaskState.UNKNOWN }) {
                val next = if (tx.tasks.countQueued(sessionId) > 0) SessionState.QUEUED else SessionState.CREATED
                changeState(tx, sessionId, next, "recovery_resolved")
            }
        }
        cores.discard(sessionId)
        notifySettled(taskId)
        refreshCounts()
        wake()
    }

    suspend fun refreshCounts() {
        val (queued, unknown) = store.read { tx ->
            tx.tasks.listByState(TaskState.QUEUED).size to tx.tasks.listByState(TaskState.UNKNOWN).size
        }
        _counts.value = _counts.value.copy(running = running.size, queued = queued, recoveryPending = unknown)
    }

    fun stop() {
        loopJob?.cancel()
        running.values.forEach { it.job?.cancel() }
        running.clear()
    }

    internal companion object {
        const val TAG = "Scheduler"
        const val PAUSE_RECOVERY = "recovery_required"
        const val CANCEL_TIMEOUT = "timeout"

        fun changeState(tx: StoreTx, sessionId: String, to: SessionState, reason: String?) {
            val from = tx.sessions.setState(sessionId, to, tx.now, if (to == SessionState.PAUSED) reason else null)
            if (from != to) {
                tx.events.append(
                    PendingEvent(sessionId, null, EventTypes.SESSION_STATE_CHANGED, buildJsonObject { put("from", from.wire); put("to", to.wire); reason?.let { put("reason", it) } }),
                )
            }
        }

        /** 标记结果未知并写 task.recovery_required（运行时重启、泵故障、取消宽限期超时共用）。 */
        fun markRecoveryRequired(tx: StoreTx, task: TaskRecord, reason: String, error: ErrorInfo) {
            val unknownCalls = tx.tasks.fenceDispatched(task.id) +
                tx.tasks.toolCalls(task.id).filter { it.state == ToolCallState.CANCELLED || it.state == ToolCallState.UNKNOWN }
            tx.tasks.markUnknown(task.id, error)
            tx.events.append(
                PendingEvent(
                    task.sessionId, task.id, EventTypes.TASK_RECOVERY_REQUIRED,
                    buildJsonObject {
                        put("attempt", task.attempt)
                        put("reason", reason)
                        put(
                            "unknownToolCalls",
                            buildJsonArray {
                                unknownCalls.distinctBy { it.toolCallId }.forEach { c -> add(buildJsonObject { put("toolCallId", c.toolCallId); put("name", c.name) }) }
                            },
                        )
                    },
                ),
            )
            changeState(tx, task.sessionId, SessionState.PAUSED, PAUSE_RECOVERY)
        }
    }
}
