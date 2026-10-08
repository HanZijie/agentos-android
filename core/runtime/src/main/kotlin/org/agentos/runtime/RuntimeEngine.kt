package org.agentos.runtime

import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.acp.AcpConfig
import org.agentos.runtime.acp.AcpServer
import org.agentos.runtime.broker.BrokerConfig
import org.agentos.runtime.broker.CapabilityBroker
import org.agentos.runtime.broker.DefaultCapabilityBroker
import org.agentos.runtime.errors.AgentOsException
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.events.EventEnvelope
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.events.PendingEvent
import org.agentos.runtime.ports.AgentCoreFactory
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.HostPort
import org.agentos.runtime.ports.OutboundGate
import org.agentos.runtime.quota.Admission
import org.agentos.runtime.quota.CallerQuota
import org.agentos.runtime.quota.CallerQuotaConfig
import org.agentos.runtime.quota.PromptOutcome
import org.agentos.runtime.quota.QuotaReason
import org.agentos.runtime.scheduler.PromptContent
import org.agentos.runtime.store.TaskState
import org.agentos.runtime.ports.ToolRef
import org.agentos.runtime.router.JevProvider
import org.agentos.runtime.router.RouterConfig
import org.agentos.runtime.router.SessionRouter
import org.agentos.runtime.scheduler.CoreSessions
import org.agentos.runtime.scheduler.Recovery
import org.agentos.runtime.scheduler.Scheduler
import org.agentos.runtime.scheduler.SchedulerConfig
import org.agentos.runtime.store.SessionRecord
import org.agentos.runtime.store.Store
import org.agentos.runtime.store.TaskRecord
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 运行时的可调参数。 */
data class RuntimeConfig(
    val scheduler: SchedulerConfig = SchedulerConfig(),
    val broker: BrokerConfig = BrokerConfig(),
    val router: RouterConfig = RouterConfig(),
    /** 自动选会话用的 Jev；null 时自动选会话一律新建会话。 */
    val jev: JevProvider? = null,
    val acp: AcpConfig = AcpConfig(),
    /** 第三方 App 的配额（docs/third-party-acp.md 4.6）：只对 `CallerKind.APP` 生效。 */
    val quota: CallerQuotaConfig = CallerQuotaConfig(),
    val version: String = "0.1.0",
)

object AgentRuntimes {
    /** 构造运行时（还没启动，调用 [RuntimeEngine.start]）。 */
    fun create(host: HostPort, agentCoreFactory: AgentCoreFactory, config: RuntimeConfig = RuntimeConfig()): RuntimeEngine =
        RuntimeEngine(RuntimeDependencies(host, agentCoreFactory), config)
}

/**
 * [AgentRuntime] 的实现：Store、恢复、调度、Broker、Router 的组合，外加 ACP 层（W4）使用的会话操作。
 *
 * 会话隔离（architecture 5.3）：调用方只能看到、续写自己的会话（按 [CallerIdentity.ownerKey]）；AgentOS App 自己（SELF）可以看到全部。
 * 别人的会话一律按“不存在”处理（session_not_found）。
 */
class RuntimeEngine internal constructor(
    private val deps: RuntimeDependencies,
    private val config: RuntimeConfig,
) : AgentRuntime {
    private val host: HostPort = deps.host
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var store: Store
    private lateinit var cores: CoreSessions
    private lateinit var scheduler: Scheduler
    private lateinit var router: SessionRouter
    val broker: CapabilityBroker = DefaultCapabilityBroker(host, config.broker)

    /**
     * 第三方 App 的配额与用量（docs/third-party-acp.md 4.6）。`:agent` 接线时用 [CallerQuota.addListener] 订阅“一次 prompt 结束”
     * （每次一回调，给 CallerRegistry 记用量），用 [CallerQuota.usage] 读当前数字。
     */
    val quota: CallerQuota = CallerQuota(config.quota, host.clock)

    /** start() 已被调用（只能调用一次）。 */
    private val startCalled = AtomicBoolean(false)

    /** 恢复流程结束、调度器已启动；会话操作都先等它（恢复期间到达的请求在这里排队）。 */
    private val ready = CompletableDeferred<Unit>()

    @Volatile private var started = false

    /**
     * 已经收到、还没交给调度器的请求数（session/new、自动选会话、session/prompt）。计入 runState.queuedTasks：
     * 恢复期间、或提交正在写 Store 时收到的 prompt，一收到就算“有任务”，W6 据此保持前台（session-scheduling.md 第 7 节）。
     */
    private val intake = AtomicInteger(0)
    private val runStateLock = Any()
    private val _runState = MutableStateFlow(RunState())
    override val runState: StateFlow<RunState> = _runState.asStateFlow()

    /** 从调度器计数、Agent core 状态和受理中的请求数同步算出 runState。 */
    private fun publishRunState() = synchronized(runStateLock) {
        val counts = if (started) scheduler.counts.value else Scheduler.Counts()
        val core = if (started) cores.state.value else org.agentos.runtime.ports.AgentCoreState.Idle
        _runState.value = RunState(counts.running, counts.queued + intake.get(), counts.recoveryPending, core, counts.lastError)
    }

    override suspend fun start() {
        check(startCalled.compareAndSet(false, true)) { "runtime already started" }
        try {
            store = Store.open(host.storage, host.clock)
            store.append(
                PendingEvent(
                    EventTypes.SYSTEM_STREAM, null, EventTypes.RUNTIME_STARTED,
                    buildJsonObject {
                        put("version", config.version)
                        put("schemaVersion", org.agentos.runtime.store.Schema.VERSION)
                    },
                ),
            )
            Recovery.run(store, userStopped = host.environment.previousExitStoppedByUser, config = config.scheduler)
            cores = CoreSessions(deps.agentCoreFactory, store, host.log)
            scheduler = Scheduler(scope, store, host, cores, broker, config.scheduler)
            router = SessionRouter(store, config.jev, config.router, host.log)
            scheduler.refreshCounts()
            started = true
            scheduler.start()
            scope.launch { combine(scheduler.counts, cores.state) { _, _ -> }.collect { publishRunState() } }
            publishRunState()
            ready.complete(Unit)
        } catch (e: Throwable) {
            ready.completeExceptionally(e)
            throw e
        }
    }

    /**
     * 在一条传输上提供 ACP Agent 端。**立即返回，不挂起、不做 I/O**（W6 在 Binder 线程上、`IAcpService.open` 里同步调用）：
     * 只创建 Protocol 和 SDK 的 Agent、启动它们的协程。可以在 [start] 完成之前调用；需要 Store 的请求在协程里等运行时就绪。
     */
    override fun serveAcp(transport: Transport, caller: CallerIdentity, gate: OutboundGate): AcpConnection =
        AcpServer.serve(this, transport, caller, gate, scope, config.acp, config.version)

    override suspend fun shutdown() {
        if (!started) {
            scope.cancel()
            return
        }
        scheduler.stop()
        cores.close()
        scope.cancel()
        store.close()
        started = false
    }

    // ------------------------------------------------------------------ 会话操作（ACP 层使用）

    /**
     * 新建会话（ACP session/new）。恢复期间收到的，等恢复结束再建；等待期间计入 runState。
     * [toolScope]：这个会话能用的工具（docs/third-party-acp.md 4.5），随会话存下、之后不能改；null = 没带（第三方 App 的会话那就没有任何工具）。
     */
    suspend fun createSession(caller: CallerIdentity, cwd: String?, toolScope: List<ToolRef>? = null): SessionRecord = intake {
        awaitReady()
        store.write { tx -> SessionRouter.createSession(tx, caller, cwd, via = "session/new", toolScope = toolScope) }
    }

    /** 自动选会话扩展：在调用方自己的会话里选一个或新建（session-selection.md）。只会选到 toolScope 与 [toolScope] 相同的会话。 */
    suspend fun autoSelect(caller: CallerIdentity, query: String, cwd: String?, toolScope: List<ToolRef>? = null): SessionRouter.Result = intake {
        awaitReady()
        router.route(caller, query, cwd, toolScope)
    }

    /** 调用方能访问的会话；不存在或不属于调用方时抛 session_not_found。 */
    suspend fun session(caller: CallerIdentity, sessionId: String): SessionRecord {
        awaitReady()
        val s = store.read { it.sessions.get(sessionId) }
        if (s == null || !canAccess(caller, s)) throw AgentOsException(ErrorCode.SESSION_NOT_FOUND, "no such session")
        return s
    }

    /** 提交一轮输入（持久化后返回任务）。[content] 是 ACP 的 ContentBlock 数组。 */
    suspend fun submit(caller: CallerIdentity, sessionId: String, content: JsonArray, clientRequestId: String? = null): TaskRecord =
        submitWithCursor(caller, sessionId, content, clientRequestId).second

    /**
     * 提交一轮输入，同时返回提交前会话的最新 sequence（读这一轮的事件从它之后开始）。
     * 从收到起就计入 runState.queuedTasks，直到调度器接手（恢复期间收到的也一样）。
     */
    suspend fun submitWithCursor(
        caller: CallerIdentity,
        sessionId: String,
        content: JsonArray,
        clientRequestId: String? = null,
    ): Pair<Long, TaskRecord> = intake {
        val s = session(caller, sessionId)
        if (content.isEmpty()) throw AgentOsException(ErrorCode.INVALID_PARAMS, "prompt is empty")
        // 第三方 App 的配额（4.6）：文字上限、同时一个、每小时上限。被拒绝的不计数；其他调用方原样通过
        val admitted = admitPrompt(caller, content)
        val task = try {
            scheduler.submit(sessionId, caller, content, clientRequestId)
        } catch (e: Throwable) {
            quota.cancelAdmission(admitted)
            throw e
        }
        if (admitted.counted) releaseWhenSettled(task.id, admitted)
        s.lastSequence to task
    }

    /** 已经放行、任务还没结束的第三方 prompt：taskId → 放行记录。任务结束（不是连接断开：断开不取消任务）时释放名额并回调用量。 */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Admission.Admitted>()

    private suspend fun admitPrompt(caller: CallerIdentity, content: JsonArray): Admission.Admitted {
        val chars = PromptContent.textChars(content)
        var decision = quota.admit(caller, chars)
        // “同时一个”：客户端看到上一轮结束再发下一轮时，释放名额的协程可能还没跑到。拒绝之前先按 Store 里任务的状态核对一次，
        // 已经结束的立刻释放，再试一次——这样一个看到上一轮结束的客户端不会被误判为忙
        if (decision is Admission.Rejected && decision.reason == QuotaReason.BUSY && reapSettled(caller)) decision = quota.admit(caller, chars)
        return when (decision) {
            is Admission.Rejected -> throw AgentOsException(decision.toError())
            is Admission.Admitted -> decision
        }
    }

    private fun releaseWhenSettled(taskId: String, admitted: Admission.Admitted) {
        inFlight[taskId] = admitted
        scope.launch {
            val outcome = try {
                outcomeOf(scheduler.awaitSettled(taskId).state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PromptOutcome.UNKNOWN
            }
            inFlight.remove(taskId)
            quota.release(admitted, outcome)
        }
    }

    /** 这个调用方在途的 prompt 里，任务其实已经结束的：释放。返回是否释放了至少一个。 */
    private suspend fun reapSettled(caller: CallerIdentity): Boolean {
        var freed = false
        for ((taskId, admitted) in inFlight.entries.toList()) {
            if (admitted.caller.ownerKey != caller.ownerKey) continue
            val state = store.read { it.tasks.get(taskId)?.state }
            if (state != null && state != TaskState.QUEUED && state != TaskState.RUNNING && state != TaskState.CANCELLING) {
                inFlight.remove(taskId)
                quota.release(admitted, outcomeOf(state))
                freed = true
            }
        }
        return freed
    }

    private fun outcomeOf(state: TaskState): PromptOutcome = when (state) {
        TaskState.COMPLETED -> PromptOutcome.COMPLETED
        TaskState.CANCELLED -> PromptOutcome.CANCELLED
        TaskState.FAILED -> PromptOutcome.FAILED
        else -> PromptOutcome.UNKNOWN
    }

    /** 等任务结束（终态，或结果未知）。 */
    suspend fun awaitTask(taskId: String): TaskRecord {
        awaitReady()
        return scheduler.awaitSettled(taskId)
    }

    /** 取消会话里所有未结束的任务（ACP session/cancel）。 */
    suspend fun cancel(caller: CallerIdentity, sessionId: String): List<String> {
        session(caller, sessionId)
        return scheduler.cancel(sessionId, by = "client")
    }

    /** 放弃一个结果未知的任务（W10 会加上重试）。 */
    suspend fun abandonRecovery(caller: CallerIdentity, sessionId: String, taskId: String) {
        session(caller, sessionId)
        scheduler.abandon(sessionId, taskId, by = caller.kind.name.lowercase())
    }

    /**
     * 会话里 `sequence > afterSequence` 的已提交事件，按 sequence 顺序；之后提交的事件陆续发出，直到收集方取消。
     * 先写日志再通知（events.md 6.2），所以不会漏事件，也不会看到未提交的事件。
     */
    fun events(sessionId: String, afterSequence: Long = 0): Flow<EventEnvelope> = flow {
        awaitReady()
        var cursor = afterSequence
        store.watch(sessionId).collect { latest ->
            while (cursor < latest) {
                val batch = store.read { it.events.read(sessionId, cursor, 500) }
                if (batch.isEmpty()) break
                for (e in batch) emit(e)
                cursor = batch.last().sequence
            }
        }
    }

    /** 读取已提交的事件（不等待）。 */
    suspend fun readEvents(sessionId: String, afterSequence: Long = 0, limit: Int = 1_000): List<EventEnvelope> {
        awaitReady()
        return store.read { it.events.read(sessionId, afterSequence, limit) }
    }

    suspend fun task(taskId: String): TaskRecord? {
        awaitReady()
        return store.read { it.tasks.get(taskId) }
    }

    /** AgentOS App（SELF）看全部会话；其他调用方只看自己的；系统流谁都不能当会话用。 */
    fun canAccess(caller: CallerIdentity, session: SessionRecord): Boolean = when {
        session.id == EventTypes.SYSTEM_STREAM -> false
        caller.kind == CallerKind.SELF -> true
        else -> session.ownerKey == caller.ownerKey
    }

    /** 等 [start] 完成；start 失败时抛出同样的异常。 */
    private suspend fun awaitReady() = ready.await()

    private suspend fun <T> intake(block: suspend () -> T): T {
        intake.incrementAndGet()
        publishRunState()
        try {
            return block()
        } finally {
            intake.decrementAndGet()
            // 调度器的计数在 submit 返回前已更新，这里同步重算：只会多计一瞬，不会少计
            publishRunState()
        }
    }

    /** 测试与诊断用。 */
    internal val storeForTesting: Store get() = store
}
