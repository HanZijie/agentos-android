package org.agentos.runtime.scheduler

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.errors.ErrorInfo
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.events.PendingEvent
import org.agentos.runtime.ports.AgentCore
import org.agentos.runtime.ports.AgentCoreFactory
import org.agentos.runtime.ports.AgentCoreSession
import org.agentos.runtime.ports.AgentCoreState
import org.agentos.runtime.ports.AgentSessionConfig
import org.agentos.runtime.ports.RuntimeLog
import org.agentos.runtime.ports.warn
import org.agentos.runtime.store.Store

/**
 * 管理 Agent core 实例与各会话的 Pi `Agent`（S8：一个运行时承载全部会话）。
 *
 * - 第一次用到时 [AgentCoreFactory.create] + start；会话第一次用到时用 Store 里保存的 messages 重建（没有就新建）。
 * - 泵故障（[onCoreLost]）：写 `agent_core.failed`，丢掉整个实例和所有会话句柄；下次 [acquire] 新建实例，
 *   写 `agent_core.restarted`，会话按 Store 里的 messages 重建（F8）。
 */
internal class CoreSessions(
    private val factory: AgentCoreFactory,
    private val store: Store,
    private val log: RuntimeLog,
) {
    private val mutex = Mutex()
    private var core: AgentCore? = null
    private var restartsPending = false
    private val open = HashMap<String, Opened>()
    private val _state = MutableStateFlow<AgentCoreState>(AgentCoreState.Idle)
    val state: StateFlow<AgentCoreState> = _state.asStateFlow()

    private data class Opened(val session: AgentCoreSession, val config: AgentSessionConfig, val core: AgentCore)

    /** 取会话对应的 Pi `Agent`，必要时新建或按新配置 reconfigure。只在这个会话没有进行中的一轮时调用。 */
    suspend fun acquire(sessionId: String, config: AgentSessionConfig): AgentCoreSession = mutex.withLock {
        val c = ensureCore()
        val existing = open[sessionId]
        if (existing != null && existing.core === c) {
            if (existing.config != config) {
                existing.session.reconfigure(config)
                open[sessionId] = existing.copy(config = config)
            }
            return existing.session
        }
        val restore = store.read { it.sessions.loadMessages(sessionId) }
        val session = c.openSession(sessionId, config, restore)
        open[sessionId] = Opened(session, config, c)
        session
    }

    /** 当前打开的会话句柄（abort 用）；没有就返回 null。不加锁：abort 不能排在 acquire 后面等。 */
    fun peek(sessionId: String): AgentCoreSession? = open[sessionId]?.session

    /** 丢掉一个会话的 Pi `Agent`（结果未知的轮次之后，下次按 Store 重建）。 */
    suspend fun discard(sessionId: String) {
        val removed = mutex.withLock { open.remove(sessionId) }
        runCatching { removed?.session?.dispose() }
    }

    /** 某一轮报告泵故障。同一个实例只处理一次。 */
    suspend fun onCoreLost(error: ErrorInfo, runningTasks: Int) {
        val lost = mutex.withLock {
            val c = core ?: return
            if (c.state.value !is AgentCoreState.Failed && c.state.value != AgentCoreState.Closed) return@withLock null
            core = null
            open.clear()
            restartsPending = true
            _state.value = AgentCoreState.Failed(error)
            c
        } ?: return
        runCatching { lost.close() }
        store.append(
            PendingEvent(
                EventTypes.SYSTEM_STREAM, null, EventTypes.AGENT_CORE_FAILED,
                buildJsonObject { put("runningTasks", runningTasks) }, error,
            ),
        )
    }

    suspend fun close() = mutex.withLock {
        val c = core
        core = null
        open.clear()
        runCatching { c?.close() }
        _state.value = AgentCoreState.Closed
    }

    private suspend fun ensureCore(): AgentCore {
        core?.let { c ->
            if (c.state.value == AgentCoreState.Ready) return c
            // 实例已失效但还没人报告：按泵故障处理
            core = null
            open.clear()
            restartsPending = true
            runCatching { c.close() }
        }
        _state.value = AgentCoreState.Starting
        val c = factory.create()
        try {
            c.start()
        } catch (e: Exception) {
            log.warn(TAG, "agent core failed to start: ${e.javaClass.simpleName}")
            _state.value = AgentCoreState.Failed(ErrorCode.AGENT_CORE_FAILED.info("agent core failed to start"))
            runCatching { c.close() }
            throw e
        }
        core = c
        _state.value = AgentCoreState.Ready
        if (restartsPending) {
            restartsPending = false
            store.append(PendingEvent(EventTypes.SYSTEM_STREAM, null, EventTypes.AGENT_CORE_RESTARTED))
        }
        return c
    }

    private companion object {
        const val TAG = "CoreSessions"
    }
}
