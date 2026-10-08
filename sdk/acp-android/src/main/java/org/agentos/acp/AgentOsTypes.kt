package org.agentos.acp

/**
 * 工具范围里的一项（docs/third-party-acp.md 4.5）：`plugin` 是 plugin.json 的 `name`，`tool` 是服务器报告的**原始**工具名
 * （不用 `mcp__...` 的最终名字：调用方不需要知道后缀规则）。工具范围是调用方自己的选择：[AgentOsConnection.newSession]。
 */
data class ToolRef(val plugin: String, val tool: String)

/**
 * 调用方自带的 MCP 服务器（只支持 **Streamable HTTP**），只对它挂上去的那个会话可见。
 *
 * - [url] 必须是 `https://`，指向公网地址；指向本机、内网、链路本地地址（含云元数据 169.254.169.254）的会被 AgentOS 拒绝；
 * - [headers] 常用来放 token。**它们和 [url] 只在 AgentOS 的内存里**，不写盘、不进日志和事件、不出现在确认框里；
 *   AgentOS 重启后要在 [AgentOsConnection.loadSession] / [AgentOsConnection.resumeSession] 里重新带上来；
 * - 这些工具对用户来说是“这个 App 带来的”，**每次调用都要用户确认**，没有“始终允许”，永远不当作只读工具。
 *
 * [toString] 只写名字：这个对象常常出现在日志语句里。
 */
class McpHttpServer(val name: String, val url: String, val headers: List<Pair<String, String>> = emptyList()) {
    override fun toString() = "McpHttpServer(name=$name)"
}

/** 一个调用方自带的 MCP 服务器挂上去之后的状态，见 [AgentOsSession.mcpServers]。连不上不会让会话失败，只是它的工具这次用不了。 */
data class McpServerStatus(val name: String, val connected: Boolean, val toolCount: Int, val reason: String?)

/** 会话模式（`session/set_mode`）：只能在会话创建时的工具范围之上再收一层，不会放宽。 */
enum class SessionMode(val wire: String) {
    /** 会话范围内的全部工具；写级每次确认。 */
    DEFAULT("default"),

    /** 只放行读级工具。调用方自带的 MCP 服务器的工具（写级起步）因此也不可用。 */
    READ_ONLY("read_only"),

    /** 不用任何工具，只对话。 */
    CHAT("chat"),
    ;

    companion object {
        internal fun of(wire: String): SessionMode? = entries.firstOrNull { it.wire == wire }
    }
}

/** 会话可以选的一个模型（`session/set_model`）：用户在 AgentOS 里配置的那个 key 下可用的模型。 */
data class ModelOption(val id: String, val name: String)

/** [AgentOsConnection.listSessions] 里的一项。 */
data class SessionSummary(
    /** 会话 ID：存下来，以后 [AgentOsConnection.loadSession] 用。形如 `ses_…`，只对创建它的 App 有意义。 */
    val sessionId: String,
    /** 第一条 prompt 的第一行（最多 80 字符）；还没有 prompt 时为 null。 */
    val title: String?,
    /** 最近一次活动，ISO 8601。 */
    val updatedAt: String?,
)

/** 一次工具调用现在的状态。 */
enum class ToolStatus {
    /** 模型要调用它，还没有开始执行：多半是 AgentOS 在等用户在确认框里决定（读级工具、用户设了“始终允许”的不需要确认，很快进入 [RUNNING]）。 */
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
     * @property tool 工具名。能对应上本次 toolScope 里的某一项时是它的原始工具名（[ref] 不为 null），否则（没给 toolScope、不在范围里）
     *   是 AgentOS 给模型的最终名字（`mcp__<插件名>__<服务器>__<工具>`）。
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

    /**
     * Agent 的思考过程，一小段一小段地来（模型支持推理、且用户配置了思考档位时才有）。是中间过程，不是答案：可以显示成“思考中…”，
     * 不要当作最终回复存下来。[AgentOsSession.prompt] 默认不发这个事件（`includeThoughts = true` 才发）；[AgentOsConnection.loadSession] 重放的历史里总是带。
     */
    data class Thought(val chunk: String) : AgentOsEvent

    /**
     * 历史里用户发过的一句话。只在 [AgentOsConnection.loadSession] 重放历史时出现（实时的一轮里，用户消息是调用方自己发的，不回显）。
     */
    data class UserMessage(val text: String) : AgentOsEvent

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

    /** 没有这个会话：从来没有过、已经删除，或者不是这个 App 创建的（AgentOS 对这几种情况的回答完全一样）。 */
    SESSION_NOT_FOUND,

    /**
     * 请求本身不对：不认识的模式或模型、MCP 服务器的地址或头不合规（不是 https、指向内网…），或这个 App 挂的 MCP 服务器总数超限。
     * [AgentOsException.message] 只写错误代码，不含你传入的值。
     */
    INVALID_REQUEST,

    /** AgentOS 这一版不支持这个能力（例如没有接会话级 MCP 服务器，或传了不支持的 MCP 传输）。 */
    UNSUPPORTED,

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

/**
 * 这台手机上的 AgentOS 支持哪些会话能力（[AgentOsConnection.capabilities]）。旧版本的 AgentOS 只有 `newSession`：
 * 调用别的方法会得到 [AgentOsError.UNSUPPORTED]，先看这里可以少走一趟。
 */
data class AgentOsCapabilities(
    /** [AgentOsConnection.loadSession]：重放历史并继续。 */
    val loadSession: Boolean,
    /** [AgentOsConnection.resumeSession]：继续一个会话，不重放历史。 */
    val resumeSession: Boolean,
    val forkSession: Boolean,
    val listSessions: Boolean,
    val deleteSession: Boolean,
    val closeSession: Boolean,
    /** [McpHttpServer]：调用方自带的 Streamable HTTP MCP 服务器。 */
    val mcpHttpServers: Boolean,
)
