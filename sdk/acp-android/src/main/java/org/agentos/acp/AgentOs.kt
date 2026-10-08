package org.agentos.acp

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * 给后装 App 用的 AgentOS 入口（docs/third-party-acp.md 4.7）。**第一个提交是骨架：公开 API 已是最终形状，实现还没有接上**
 * （方法体抛 [NotImplementedError]），上层可以对着它写代码、写假实现。
 *
 * 用法：
 * ```
 * if (!AgentOs.isInstalled(context)) { ...  }
 * val connection = AgentOs.connect(context, onWaiting = { showWaiting() })      // 可能抛 AgentOsException
 * val session = connection.newSession(listOf(ToolRef("alarm", "alarm_create"), ToolRef("calendar", "event_create")))
 * session.prompt(text).collect { event -> ... }
 * connection.close()
 * ```
 *
 * 要求：调用方 App 的 Manifest 里能看见 AgentOS（本库的 Manifest 已带 `<queries>`，合并后自动生效）。
 * 对上层屏蔽 ACP 的类型；不需要 key，不联网，不读 AgentOS 的任何私有数据。
 */
object AgentOs {
    /** 这台手机上有没有 AgentOS，且它提供了第三方接入的入口。 */
    fun isInstalled(context: Context): Boolean = TODO("C8: PackageManager + queryIntentServices(ACTION)")

    /**
     * 连接 AgentOS。第一次使用时 AgentOS 会请用户允许：本函数每秒重试 `open`，最多 [AUTHORIZATION_TIMEOUT_MILLIS]，
     * 等待期间每秒回调 [onWaiting]（见 [Waiting]）。成功后返回已初始化的连接。
     *
     * @throws AgentOsException [AgentOsError.NOT_INSTALLED]、[AgentOsError.AUTHORIZATION_PENDING_TIMEOUT]、[AgentOsError.DENIED]、
     *   [AgentOsError.DISCONNECTED]、[AgentOsError.FAILED]。协程被取消时按取消处理（会放弃等待并释放绑定）。
     */
    suspend fun connect(context: Context, onWaiting: (Waiting) -> Unit = {}): AgentOsConnection =
        TODO("C8: bindService + BinderAcpTransport.connect + retry on authorization_pending + initialize")

    /**
     * 把 AgentOS 待决的授权提示 / 工具确认带到前台。只有调用方 App 在前台时系统才允许这样启动别的 App 的界面；
     * 没有待决时 AgentOS 的入口界面直接结束。AgentOS 没装或系统拒绝时什么也不做。
     */
    fun bringApprovalToFront(context: Context): Unit = TODO("C8: startActivity(ACTION_SHOW_APPROVAL)")

    /** [connect] 等用户决定的上限（毫秒）。 */
    const val AUTHORIZATION_TIMEOUT_MILLIS = 90_000L
}

/** 到 AgentOS 的一条连接。用完要 [close]（解除绑定）。 */
class AgentOsConnection internal constructor() : AutoCloseable {
    /** 连接还活着。AgentOS 进程被杀、授权被撤销后为 false。 */
    val isConnected: Boolean get() = TODO("C8")

    /**
     * 新建一个会话。[toolScope] 是这个会话**最多**能用的工具；只能缩小、不能放大（实际可用 = toolScope 与用户当前启用的工具的交集），
     * 写了不存在的项会被忽略，也不报错。没有 toolScope（空列表）的第三方会话没有任何工具，只能聊天。
     *
     * @throws AgentOsException [AgentOsError.DISCONNECTED]、[AgentOsError.FAILED]
     */
    suspend fun newSession(toolScope: List<ToolRef>): AgentOsSession = TODO("C8: session/new with _meta org.agentos toolScope")

    /** 断开并解除绑定；进行中的 prompt 以 [AgentOsError.DISCONNECTED] 结束。可以重复调用。 */
    override fun close(): Unit = TODO("C8")
}

/** 一个会话。同一个 App 同时只能有一个进行中的 prompt。 */
class AgentOsSession internal constructor() {
    /**
     * 发一轮 prompt，返回这一轮的事件流：[AgentOsEvent.Text]、[AgentOsEvent.ToolCall]（同一个 id 多次）、最后一个 [AgentOsEvent.Done]。
     * 流是冷的：开始收集才发送；取消收集等于 [cancel]。失败时流以 [AgentOsException] 结束：[AgentOsError.NO_MODEL]（AgentOS 没配置模型）、
     * [AgentOsError.BUSY]、[AgentOsError.RATE_LIMITED]、[AgentOsError.TOO_LARGE]、[AgentOsError.DISCONNECTED]、[AgentOsError.FAILED]。
     *
     * 第三方会话里**每一次**工具调用都会让用户在 AgentOS 里确认（[ToolStatus.PENDING_APPROVAL]），用户没有回答会超时按拒绝处理。
     */
    fun prompt(text: String): Flow<AgentOsEvent> = TODO("C8: session/prompt + session/update mapping")

    /** 取消进行中的 prompt（没有进行中的什么也不做）。 */
    suspend fun cancel(): Unit = TODO("C8: session/cancel")
}
