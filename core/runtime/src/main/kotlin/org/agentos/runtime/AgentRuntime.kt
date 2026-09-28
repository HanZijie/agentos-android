package org.agentos.runtime

import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.flow.StateFlow
import org.agentos.runtime.errors.ErrorInfo
import org.agentos.runtime.ports.AgentCoreFactory
import org.agentos.runtime.ports.AgentCoreState
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.HostPort
import org.agentos.runtime.ports.OutboundGate

/**
 * :agent 运行时的入口：W6 的 AgentService / AcpService / DesktopGateway 只通过它使用宿主层。
 * 实现在 W2（Store、调度、恢复）和 W4（ACP Agent 端）完成；构造方式：
 *
 * ```kotlin
 * val runtime: AgentRuntime = AgentRuntimes.create(hostPort, agentCoreFactory)   // W2 提供
 * runtime.start()                                   // 打开 Store、迁移、恢复流程（F8）
 * val conn = runtime.serveAcp(transport, caller, OutboundGate { transport.awaitWritable(16_384) })
 * runtime.runState.collect { … }                    // W6：有任务时前台服务、写心跳
 * ```
 */
interface AgentRuntime {
    /** 运行状态：W6 据此进出前台、写心跳文件（S2 契约：心跳只记录“有没有任务”）。 */
    val runState: StateFlow<RunState>

    /** 打开 Store、执行迁移和恢复流程（F8），启动 Agent core。只调用一次。 */
    suspend fun start()

    /**
     * 在一条已建立的传输上提供 ACP Agent 端。[caller] 来自可信的连接上下文（Binder UID、电脑端配对）。
     * 传输关闭时连接随之结束，挂起的请求一并结束（S3 问题 4）；连接断开不取消任务（F7）。
     */
    fun serveAcp(transport: Transport, caller: CallerIdentity, gate: OutboundGate = OutboundGate.NONE): AcpConnection

    /** 停止接受新任务，关闭所有连接和 Agent core。进行中的任务留给下次启动的恢复流程。 */
    suspend fun shutdown()
}

/** 一条 ACP 连接。 */
interface AcpConnection {
    val caller: CallerIdentity

    /** 主动关闭（例如撤销授权）。不取消这个调用方的任务。 */
    suspend fun close(reason: String)

    /** 等到连接结束（对端关闭、传输出错或 [close]）。 */
    suspend fun awaitClosed()
}

/**
 * @property activeTasks 正在运行（含取消中）的任务数。
 * @property queuedTasks 排队的任务数。
 * @property recoveryPending 标记为需要恢复、等用户决定的任务数。
 * @property core Agent core 的状态。
 * @property lastError 最近一次任务失败或运行时故障（诊断页用，不含敏感信息）。
 */
data class RunState(
    val activeTasks: Int = 0,
    val queuedTasks: Int = 0,
    val recoveryPending: Int = 0,
    val core: AgentCoreState = AgentCoreState.Idle,
    val lastError: ErrorInfo? = null,
) {
    /** 有任务要跑：W6 据此进入前台（architecture F2、F7）。 */
    val busy: Boolean get() = activeTasks > 0 || queuedTasks > 0
}

/** [AgentRuntime] 的构造参数，W2 的实现接收它。 */
data class RuntimeDependencies(
    val host: HostPort,
    val agentCoreFactory: AgentCoreFactory,
)
