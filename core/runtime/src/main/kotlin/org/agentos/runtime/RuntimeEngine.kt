package org.agentos.runtime

import com.agentclientprotocol.transport.Transport
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

/** 运行时的可调参数。 */
data class RuntimeConfig(
    val scheduler: SchedulerConfig = SchedulerConfig(),
    val broker: BrokerConfig = BrokerConfig(),
    val router: RouterConfig = RouterConfig(),
    /** 自动选会话用的 Jev；null 时自动选会话一律新建会话。 */
    val jev: JevProvider? = null,
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
    private val _runState = MutableStateFlow(RunState())
    override val runState: StateFlow<RunState> = _runState.asStateFlow()

    private lateinit var store: Store
    private lateinit var cores: CoreSessions
    private lateinit var scheduler: Scheduler
    private lateinit var router: SessionRouter
    val broker: CapabilityBroker = DefaultCapabilityBroker(host, config.broker)

    @Volatile private var started = false

    /** W4：ACP Agent 端的工厂，由 acp 包在初始化时设置（避免 W2 依赖 W4）。 */
    @Volatile internal var acpServer: ((RuntimeEngine, Transport, CallerIdentity, OutboundGate) -> AcpConnection)? = null

    override suspend fun start() {
        check(!started) { "runtime already started" }
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
        Recovery.run(store)
        cores = CoreSessions(deps.agentCoreFactory, store, host.log)
        scheduler = Scheduler(scope, store, host, cores, broker, config.scheduler)
        router = SessionRouter(store, config.jev, config.router, host.log)
        scheduler.start()
        scope.launch {
            combine(scheduler.counts, cores.state) { c, core ->
                RunState(c.running, c.queued, c.recoveryPending, core, c.lastError)
            }.collect { _runState.value = it }
        }
        started = true
    }

    override fun serveAcp(transport: Transport, caller: CallerIdentity, gate: OutboundGate): AcpConnection {
        checkStarted()
        val server = acpServer ?: throw UnsupportedOperationException("ACP Agent side is not wired (W4)")
        return server(this, transport, caller, gate)
    }

    override suspend fun shutdown() {
        if (!started) return
        scheduler.stop()
        cores.close()
        scope.cancel()
        store.close()
        started = false
    }

    // ------------------------------------------------------------------ 会话操作（ACP 层使用）

    /** 新建会话（ACP session/new）。 */
    suspend fun createSession(caller: CallerIdentity, cwd: String?): SessionRecord {
        checkStarted()
        return store.write { tx -> SessionRouter.createSession(tx, caller, cwd, via = "session/new") }
    }

    /** 自动选会话扩展：在调用方自己的会话里选一个或新建（session-selection.md）。 */
    suspend fun autoSelect(caller: CallerIdentity, query: String, cwd: String?): SessionRouter.Result {
        checkStarted()
        return router.route(caller, query, cwd)
    }

    /** 调用方能访问的会话；不存在或不属于调用方时抛 session_not_found。 */
    suspend fun session(caller: CallerIdentity, sessionId: String): SessionRecord {
        checkStarted()
        val s = store.read { it.sessions.get(sessionId) }
        if (s == null || !canAccess(caller, s)) throw AgentOsException(ErrorCode.SESSION_NOT_FOUND, "no such session")
        return s
    }

    /** 提交一轮输入（持久化后返回任务）。[content] 是 ACP 的 ContentBlock 数组。 */
    suspend fun submit(caller: CallerIdentity, sessionId: String, content: JsonArray, clientRequestId: String? = null): TaskRecord {
        session(caller, sessionId)
        if (content.isEmpty()) throw AgentOsException(ErrorCode.INVALID_PARAMS, "prompt is empty")
        return scheduler.submit(sessionId, caller, content, clientRequestId)
    }

    /** 等任务结束（终态，或结果未知）。 */
    suspend fun awaitTask(taskId: String): TaskRecord = scheduler.awaitSettled(taskId)

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
        checkStarted()
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
        checkStarted()
        return store.read { it.events.read(sessionId, afterSequence, limit) }
    }

    suspend fun task(taskId: String): TaskRecord? = store.read { it.tasks.get(taskId) }

    /** AgentOS App（SELF）看全部会话；其他调用方只看自己的；系统流谁都不能当会话用。 */
    fun canAccess(caller: CallerIdentity, session: SessionRecord): Boolean = when {
        session.id == EventTypes.SYSTEM_STREAM -> false
        caller.kind == CallerKind.SELF -> true
        else -> session.ownerKey == caller.ownerKey
    }

    private fun checkStarted() = check(started) { "runtime is not started" }

    /** 测试与诊断用。 */
    internal val storeForTesting: Store get() = store
}
