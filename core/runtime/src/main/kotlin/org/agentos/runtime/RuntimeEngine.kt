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
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.acp.AcpConfig
import org.agentos.runtime.acp.AcpServer
import org.agentos.runtime.broker.BrokerConfig
import org.agentos.runtime.broker.CallerPolicy
import org.agentos.runtime.broker.CapabilityBroker
import org.agentos.runtime.broker.DefaultCapabilityBroker
import org.agentos.runtime.broker.OpenCallerPolicy
import org.agentos.runtime.errors.AgentOsException
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.events.EventEnvelope
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.events.PendingEvent
import org.agentos.runtime.ports.AgentCoreFactory
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.HostPort
import org.agentos.runtime.ports.ModelChoice
import org.agentos.runtime.ports.OutboundGate
import org.agentos.runtime.ports.SessionMcpResult
import org.agentos.runtime.ports.SessionMcpServer
import org.agentos.runtime.ports.SessionToolPort
import org.agentos.runtime.ports.ToolScope
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
import org.agentos.runtime.store.SessionMode
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
    /**
     * 范围和确认里取决于“谁在调用”的规则（docs/third-party-acp.md 4.4）。**默认 [OpenCallerPolicy]**：所有调用方一视同仁；
     * 换成 [StrictCallerPolicy]（或 `CallerPolicy.named("strict")`）就是第三方 App 没有 toolScope 没工具、每次确认、没有“始终允许”。
     */
    val callerPolicy: CallerPolicy = OpenCallerPolicy,
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
    val broker: CapabilityBroker = DefaultCapabilityBroker(host, config.broker, config.callerPolicy)

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

    /**
     * 取消 [caller] 名下（`ownerKey` 相同）所有会话里没结束的任务，并返回被请求取消的任务 ID。撤销第三方 App 的授权时用：关通道不会取消任务（F7），
     * 不取消的话任务继续占着这个 App 的“同时一个 prompt”名额、继续花用户的模型额度。
     *
     * - 走调度器的取消路径，和 `session/cancel` 一样：排队的立即取消，运行中的请 Agent core abort，等确认的工具调用撤回确认；
     *   `task.cancel_requested` 事件里记下 [by]（例如 `"revoked"`）；
     * - **只动 [caller].ownerKey 名下的会话**：别的 App、AgentOS 自己（SELF）、电脑端不受影响；
     * - 幂等：没有未结束的任务时返回空列表，什么也不做；已经在取消中的任务不重复取消（也不在返回值里），但会等它；
     * - 返回之前最多等 [waitMillis]（默认 [AcpConfig.cancelWaitMillis]）让这些任务停下，然后释放它们占的配额名额
     *   （[CallerQuota] 的“同时一个”）并回调用量；所以返回后这个 App 立即可以再提交（如果它还有授权）。传 0 就不等。
     *   任务在等待期内没停下时名额继续占着，直到任务真的结束（它还在花额度）。
     */
    suspend fun cancelOwner(caller: CallerIdentity, by: String, waitMillis: Long = config.acp.cancelWaitMillis): List<String> {
        awaitReady()
        val requested = scheduler.cancelOwner(caller.ownerKey, by)
        if (waitMillis > 0) {
            val pending = scheduler.unfinishedTasksOf(caller.ownerKey)
            withTimeoutOrNull(waitMillis) { pending.forEach { scheduler.awaitSettled(it) } }
        }
        // 任务已经结束的，名额现在就释放，不等后台协程跑到
        reapSettled(caller)
        return requested
    }

    /** 放弃一个结果未知的任务（W10 会加上重试）。 */
    suspend fun abandonRecovery(caller: CallerIdentity, sessionId: String, taskId: String) {
        session(caller, sessionId)
        scheduler.abandon(sessionId, taskId, by = caller.kind.name.lowercase())
    }

    /**
     * 会话里 `sequence > afterSequence` 的已提交事件，按 sequence 顺序；之后提交的事件陆续发出，直到收集方取消。
     * 先写日志再通知（events.md 6.2），所以不会漏事件，也不会看到未提交的事件。
     *
     * 会话被删（session/delete 会删掉它的事件日志）时流正常结束：通知过的事件读不到，只可能是日志被删了，
     * 以后也不会再有新事件。不结束的话，正在等这个会话终态事件的 prompt 会一直挂着（收集方在通知之后、读日志之前
     * 被删除抢先时，终态事件就丢了）。
     */
    fun events(sessionId: String, afterSequence: Long = 0): Flow<EventEnvelope> = flow {
        awaitReady()
        var cursor = afterSequence
        var gone = false
        store.watch(sessionId).transformWhile { latest -> emit(latest); !gone }.collect { latest ->
            while (cursor < latest) {
                val batch = store.read { it.events.read(sessionId, cursor, 500) }
                if (batch.isEmpty()) {
                    gone = true
                    break
                }
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

    // ------------------------------------------------------------------ 会话生命周期（session/list | load | resume | fork | delete | close）

    /**
     * 调用方能看到的会话（ACP `session/list`），最近活动在前；[cwd] 非 null 时只要 cwd 相同的。
     * 范围就是 [canAccess]：第三方 App 只看到自己的，AgentOS 自己（SELF）看到全部（不含系统流）。最多 [limit] 个。
     */
    suspend fun listSessions(caller: CallerIdentity, cwd: String? = null, limit: Int = MAX_LISTED_SESSIONS): List<SessionRecord> {
        awaitReady()
        val all = store.read { tx ->
            if (caller.kind == CallerKind.SELF) tx.sessions.listAll().sortedByDescending { it.lastActivityAt } else tx.sessions.listByOwner(caller.ownerKey, limit)
        }
        return all.filter { canAccess(caller, it) && (cwd == null || it.cwd == cwd) }.take(limit)
    }

    /**
     * 分叉（ACP `session/fork`）：从 [sessionId] 新建一个会话，带着它到目前为止**已经结束的**对话（事件日志里已结束任务的部分，
     * 加上最近一次稳定的 Pi messages）。进行中的一轮不带过去。新会话属于 [caller]。
     *
     * [toolScope]：分叉请求自己带的范围，**只能收窄**——新会话的范围是原会话的范围与它的交集（原会话没带范围就用它）；
     * 不带就原样继承。模型和模式继承原会话；之后各自独立。
     */
    suspend fun forkSession(caller: CallerIdentity, sessionId: String, toolScope: List<ToolRef>? = null): SessionRecord = intake {
        awaitReady()
        val forked = store.write { tx ->
            val source = tx.sessions.get(sessionId)?.takeIf { canAccess(caller, it) } ?: throw AgentOsException(ErrorCode.SESSION_NOT_FOUND, "no such session")
            val scope = narrowedScope(source.toolScope, toolScope)
            val created = tx.sessions.fork(source, Ids.session(tx.now), caller, scope?.let { ToolScope.normalize(it) }, tx.now)
            tx.events.append(
                PendingEvent(
                    created.id, null, EventTypes.SESSION_CREATED,
                    buildJsonObject {
                        put("ownerKey", caller.ownerKey)
                        put("callerKind", caller.kind.name.lowercase())
                        put("callerUid", caller.uid)
                        put("via", "session/fork")
                        put("forkedFrom", source.id)
                        created.toolScope?.let { put("toolScope", ToolScope.toJson(it)) }
                    },
                ),
            )
            // 已经结束的任务的事件按原样拷过去（历史重放读它们）；没结束的、结果未知的不带
            val finished = tx.tasks.listBySession(source.id).filter { it.state.terminal }.mapTo(HashSet()) { it.id }
            tx.events.copyTasks(source.id, created.id, finished)
            created
        }
        forked
    }

    /**
     * 删除会话和它的一切（ACP `session/delete`）：先取消没结束的任务并等它们停下（最多 [AcpConfig.cancelWaitMillis]，
     * 没停下就拒绝，会话原样保留），再释放 Pi 会话和会话级工具，最后删 Store 里的会话、任务、事件、messages。
     */
    suspend fun deleteSession(caller: CallerIdentity, sessionId: String) {
        session(caller, sessionId)
        if (!stopSession(sessionId, by = "delete")) {
            throw AgentOsException(ErrorCode.BUSY, "the session is still stopping; try again in a moment")
        }
        releaseSession(sessionId)
        store.write { it.sessions.delete(sessionId) }
    }

    /**
     * 关闭会话（ACP `session/close`）：取消没结束的任务并等它们停下，释放这个会话占的内存（Pi 会话、会话级工具的连接、
     * “本会话内不再询问”的记忆）。**会话本身和它的历史保留**，以后可以 `session/load | resume` 回来。
     * 任务没在 [AcpConfig.cancelWaitMillis] 内停下时不抛错（取消已经发出），只是不释放。
     */
    suspend fun closeSession(caller: CallerIdentity, sessionId: String) {
        session(caller, sessionId)
        if (stopSession(sessionId, by = "close")) releaseSession(sessionId)
    }

    /** 请求取消会话里没结束的任务并等它们停下；全部停下返回 true。 */
    private suspend fun stopSession(sessionId: String, by: String): Boolean {
        scheduler.cancel(sessionId, by = by)
        val pending = store.read { tx -> tx.tasks.listBySession(sessionId).filter { !it.state.terminal && it.state != TaskState.UNKNOWN }.map { it.id } }
        if (pending.isEmpty()) return true
        return withTimeoutOrNull(config.acp.cancelWaitMillis) { pending.forEach { scheduler.awaitSettled(it) }; true } ?: false
    }

    private suspend fun releaseSession(sessionId: String) {
        cores.discard(sessionId)
        broker.forgetSession(sessionId)
        host.sessionTools.detach(sessionId)
    }

    /** 两个范围收窄：`requested` 没带就是 [parent]；[parent] 没带就是 `requested`；都带了取交集。 */
    private fun narrowedScope(parent: List<ToolRef>?, requested: List<ToolRef>?): List<ToolRef>? = when {
        requested == null -> parent
        parent == null -> requested
        else -> parent.toSet().intersect(requested.toSet()).toList()
    }

    // ------------------------------------------------------------------ 会话的模型、模式、会话级工具

    /** 会话可以选的模型（ACP `session/set_model`、配置项 `model`）：同一个 key 下的模型，见 `ModelConfigPort.choices`。 */
    fun availableModels(): List<ModelChoice> = host.models.choices.value

    /**
     * 会话现在用的模型 id：会话选了而且还可选就是它，否则是用户在设置里选的那个（它在可选列表里的话）；都没有时 null。
     * 只在 [availableModels] 非空时有意义。
     */
    fun currentModelId(session: SessionRecord): String? {
        val choices = host.models.choices.value
        session.modelId?.takeIf { id -> choices.any { it.id == id } }?.let { return it }
        val activeId = host.models.activeModel.value?.id
        return choices.firstOrNull { it.id == activeId }?.id ?: choices.firstOrNull()?.id
    }

    /**
     * 给会话选模型。[modelId] 必须在 [availableModels] 里（否则 invalid_params，不回显传进来的值）；null = 回到跟随用户的设置。
     * 下一个任务起生效，进行中的任务不受影响。返回更新后的会话。
     */
    suspend fun setSessionModel(caller: CallerIdentity, sessionId: String, modelId: String?): SessionRecord {
        session(caller, sessionId)
        if (modelId != null && host.models.choices.value.none { it.id == modelId }) {
            throw AgentOsException(ErrorCode.INVALID_PARAMS, "unknown model for this session")
        }
        store.write { it.sessions.setModel(sessionId, modelId, it.now) }
        return session(caller, sessionId)
    }

    /** 给会话设模式（[SessionMode]）。只在会话的工具范围之上再收一层，永远不会放宽。下一个任务起生效。返回更新后的会话。 */
    suspend fun setSessionMode(caller: CallerIdentity, sessionId: String, mode: SessionMode): SessionRecord {
        session(caller, sessionId)
        store.write { it.sessions.setMode(sessionId, mode, it.now) }
        return session(caller, sessionId)
    }

    /**
     * `session/load` 重放历史时要重放的任务：会话的任务不超过 [maxTurns] 个返回 null（全部重放），否则是最近 [maxTurns] 个的 ID。
     */
    suspend fun replayTaskIds(caller: CallerIdentity, sessionId: String, maxTurns: Int): Set<String>? {
        session(caller, sessionId)
        val tasks = store.read { tx -> tx.tasks.listBySession(sessionId) }
        return if (tasks.size <= maxTurns) null else tasks.takeLast(maxTurns).mapTo(HashSet()) { it.id }
    }

    /** 这个构建接了会话级工具的实现（`HostPort.sessionTools` 不是 [SessionToolPort.NONE]）：ACP 的 `mcpCapabilities.http` 据此声明。 */
    val supportsSessionTools: Boolean get() = host.sessionTools !== SessionToolPort.NONE

    /** 会话里还没结束的任务（排队、运行、取消中）里最新的一个；没有返回 null。`session/load | resume` 告诉客户端有一轮还在跑。 */
    suspend fun activeTask(caller: CallerIdentity, sessionId: String): TaskRecord? {
        session(caller, sessionId)
        return store.read { tx -> tx.tasks.listBySession(sessionId).lastOrNull { !it.state.terminal && it.state != TaskState.UNKNOWN } }
    }

    /**
     * 把调用方自带的 MCP 服务器挂到会话上（ACP `mcpServers`），替换原来挂的那批；见 `SessionToolPort.attach`。
     * 形状或限制不对抛 `SessionMcpRejected`（调用方映射成 invalid_params）。会话必须是 [caller] 能访问的。
     */
    suspend fun attachSessionTools(caller: CallerIdentity, sessionId: String, servers: List<SessionMcpServer>): List<SessionMcpResult> {
        session(caller, sessionId)
        return host.sessionTools.attach(sessionId, caller, servers)
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

    companion object {
        /** [listSessions] 一次最多返回多少个会话。 */
        const val MAX_LISTED_SESSIONS = 200
    }
}
