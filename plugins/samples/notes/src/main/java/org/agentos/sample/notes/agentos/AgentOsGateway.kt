package org.agentos.sample.notes.agentos

import kotlinx.coroutines.flow.Flow

/*
 * 备忘录和 AgentOS 之间唯一的接口（docs/third-party-acp.md 4.7、5）。界面、状态机、提示词、汇总都只认这里的类型，
 * 不碰 SDK：真实现 RealAgentOsGateway 是一层薄适配（把 sdk:acp-android 的 AgentOs 映射到这里），
 * 测试和 debug 包里还有一个可脚本化的 FakeAgentOsGateway。类型与 4.7 的草案一一对应。
 */

/** 一个工具：`plugin` 是 plugin.json 的 name，`tool` 是原始工具名（不是 mcp__ 开头的最终名字）。 */
data class ToolRef(val plugin: String, val tool: String)

/** 工具调用的状态，对应 ACP `tool_call` / `tool_call_update`。 */
enum class ToolStatus { PENDING_APPROVAL, RUNNING, COMPLETED, DENIED, FAILED }

/** 连不上、被拒绝、被限流等原因。 */
enum class AgentOsError {
    NOT_INSTALLED,
    AUTHORIZATION_PENDING_TIMEOUT,
    DENIED,
    NO_MODEL,
    BUSY,
    RATE_LIMITED,
    TOO_LARGE,
    DISCONNECTED,
    FAILED,
}

/** 网关抛出的失败，[error] 是界面要按它给出人话的原因。 */
class AgentOsException(val error: AgentOsError, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: error.name, cause)

/** [AgentOsGateway.connect] 期间正在等什么（目前只有一种：等用户在 AgentOS 里允许这个 App）。 */
enum class Waiting { AUTHORIZATION }

sealed interface GatewayEvent {
    /** Agent 的文字（流式，一段一段来）。 */
    data class Text(val chunk: String) : GatewayEvent

    /**
     * 一次工具调用的最新状态；同一个 [id] 会来多次（待确认 → 运行 → 完成 / 被拒绝 / 失败）。
     * [resultJson] 是工具结果的文字（不是 ACP 的包装）；失败时可能是一句错误说明。
     * [argumentsJson] 是模型传给工具的参数（还没有结果时界面用它预览这一项，**不当作已创建的事实**）；[ref] 是对应的 toolScope 项，
     * 对得上时按它认种类（比工具名可靠）。
     *
     * 注意：不保证每一项都有 PENDING_APPROVAL——用户在 AgentOS 里为工具设了“始终允许”就直接 RUNNING → COMPLETED，甚至只有结果。
     */
    data class ToolCall(
        val id: String,
        val tool: String,
        val status: ToolStatus,
        val resultJson: String?,
        val argumentsJson: String? = null,
        val ref: ToolRef? = null,
    ) : GatewayEvent

    /** 这一轮结束。[stopReason] 例如 end_turn / cancelled。 */
    data class Done(val stopReason: String) : GatewayEvent
}

/**
 * 一次“让 AgentOS 安排”用一个网关实例：isAvailable → connect → newSession → prompt → close。
 * 所有失败都以 [AgentOsException] 抛出（带 [AgentOsError]），其余异常按 FAILED 处理。
 */
interface AgentOsGateway {
    /** AgentOS 是否已安装（没装就不用连了）。 */
    fun isAvailable(): Boolean

    /**
     * 连接 AgentOS。第一次使用时 AgentOS 会弹授权提示，期间 [onWaiting] 会被调用（可能在任意线程），
     * 用户允许后返回；拒绝或超时抛 [AgentOsException]（DENIED / AUTHORIZATION_PENDING_TIMEOUT）。
     */
    suspend fun connect(onWaiting: (Waiting) -> Unit)

    /** 开一个只能用 [toolScope] 里那几个工具的会话。 */
    suspend fun newSession(toolScope: List<ToolRef>)

    /** 发出文字并流式收事件；Flow 在 [GatewayEvent.Done] 之后结束。取消收集等于放弃这一轮。 */
    fun prompt(text: String): Flow<GatewayEvent>

    /** 通知 AgentOS 取消进行中的这一轮（不抛异常；没有进行中的就什么也不做）。 */
    suspend fun cancel()

    /** 把 AgentOS 里待决的授权 / 确认提示带到前台（只有前台 App 能做到）。 */
    fun bringApprovalToFront()

    /** 关闭连接，释放资源；可重复调用。 */
    fun close()
}

/**
 * 备忘录**自己选择**的最小工具范围（docs/third-party-acp.md 5）：只让 AgentOS 用这两个工具，所以备忘文字里的注入指令碰不到别的工具。
 * 这不是 AgentOS 的要求（别的第三方 App 可以不带 toolScope），只是备忘录对自己的约束，不要去掉。
 */
val NotesToolScope: List<ToolRef> = listOf(
    ToolRef("alarm", "alarm_create"),
    ToolRef("calendar", "event_create"),
)
