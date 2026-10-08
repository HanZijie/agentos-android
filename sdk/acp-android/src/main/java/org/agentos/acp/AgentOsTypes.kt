package org.agentos.acp

/**
 * 工具范围里的一项（docs/third-party-acp.md 4.5）：`plugin` 是 plugin.json 的 `name`，`tool` 是服务器报告的**原始**工具名
 * （不用 `mcp__...` 的最终名字：调用方不需要知道后缀规则）。
 */
data class ToolRef(val plugin: String, val tool: String)

/** 一次工具调用现在的状态。 */
enum class ToolStatus {
    /** 模型要调用它，AgentOS 正在等用户在确认框里决定（第三方调用方的每次调用都要确认）。 */
    PENDING_APPROVAL,

    /** 用户允许了，工具正在执行。 */
    RUNNING,

    /** 执行成功。[AgentOsEvent.ToolCall.resultJson] 是工具返回的文字。 */
    COMPLETED,

    /** 用户拒绝了（或确认超时），工具没有执行。 */
    DENIED,

    /** 执行失败，或工具不可用（不在范围内、插件被停用…）。[AgentOsEvent.ToolCall.resultJson] 是错误说明。 */
    FAILED,
}

/** 一轮 prompt 里陆续到达的事件。 */
sealed interface AgentOsEvent {
    /** Agent 的文字，一小段一小段地来。 */
    data class Text(val chunk: String) : AgentOsEvent

    /**
     * 一次工具调用的进展；同一个 [id] 会先后出现多次（[ToolStatus.PENDING_APPROVAL] → [ToolStatus.RUNNING] → 终态）。
     *
     * @property tool 工具名。能对应上本次 toolScope 里的某一项时是它的原始工具名（[ref] 不为 null），否则是 AgentOS 给模型的最终名字。
     * @property resultJson 工具结果的文字（不是 ACP 的包装）；还没有结果时为 null。第三方内容，不可信。
     * @property argumentsJson 模型传给工具的参数（JSON 文字）；拿不到时为 null。
     * @property ref 对应的 toolScope 项；对应不上为 null。
     */
    data class ToolCall(
        val id: String,
        val tool: String,
        val status: ToolStatus,
        val resultJson: String?,
        val argumentsJson: String? = null,
        val ref: ToolRef? = null,
    ) : AgentOsEvent

    /** 这一轮结束。[stopReason]：`end_turn`、`cancelled`、`max_tokens`、`max_turn_requests`、`refusal`。 */
    data class Done(val stopReason: String) : AgentOsEvent
}

/** 连接 AgentOS 或使用它时可能出现的失败。 */
enum class AgentOsError {
    /** 手机上没有 AgentOS（或它的版本不支持第三方接入）。 */
    NOT_INSTALLED,

    /** 等了 90 秒，用户没有在 AgentOS 里决定。AgentOS 把它记为一次拒绝（进入 10 分钟冷却）。 */
    AUTHORIZATION_PENDING_TIMEOUT,

    /** 用户拒绝了这个 App，或这个 App 在拒绝后的 10 分钟冷却里。用户可以在 AgentOS 设置的“已授权的应用”里改成允许。 */
    DENIED,

    /** AgentOS 还没有配置模型。提示用户去 AgentOS 里配置。 */
    NO_MODEL,

    /** 这个 App 已经有一个进行中的 prompt（每个 App 同时只能有一个）。 */
    BUSY,

    /** 超过每小时 prompt 次数上限。 */
    RATE_LIMITED,

    /** 文字超过单次上限（16,000 字符）。 */
    TOO_LARGE,

    /** 到 AgentOS 的连接断了（AgentOS 进程被杀、用户撤销了授权…）。 */
    DISCONNECTED,

    /** 其他失败。 */
    FAILED,
}

/** SDK 抛出的异常；[error] 给上层按类别处理，[message] 只用于日志（不含用户文字）。 */
class AgentOsException(val error: AgentOsError, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: error.name, cause)

/**
 * 等用户在 AgentOS 里决定是否允许这个 App 时的进度（[AgentOs.connect] 的 `onWaiting`）：第一次收到授权待决时回调一次，之后每秒一次，
 * 直到用户决定或超时。上层据此显示“请在 AgentOS 的提示里允许”和“打开提示”按钮（[AgentOs.bringApprovalToFront]）。
 */
data class Waiting(val elapsedMillis: Long, val timeoutMillis: Long)
