package org.agentos.runtime.store

import kotlinx.serialization.json.JsonArray
import org.agentos.runtime.errors.ErrorInfo
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.ToolRef
import org.agentos.runtime.ports.ToolScope

/** 会话状态（core/contracts/session-scheduling.md 第 2 节）。 */
enum class SessionState(val wire: String) {
    CREATED("created"),
    QUEUED("queued"),
    RUNNING("running"),
    CANCELLING("cancelling"),
    PAUSED("paused"),
    CLOSED("closed"),
    FAILED("failed"),

    /** 系统流 `_system` 专用，不是会话。 */
    SYSTEM("system"),
    ;

    val terminal: Boolean get() = this == CLOSED || this == FAILED

    companion object {
        fun of(wire: String): SessionState = entries.first { it.wire == wire }
    }
}

/** 任务状态（session-scheduling.md 第 3 节）。 */
enum class TaskState(val wire: String) {
    QUEUED("queued"),
    RUNNING("running"),
    CANCELLING("cancelling"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    FAILED("failed"),

    /** 运行时在没有终态记录时丢失了这次执行（重启、泵故障、取消宽限期超时）：结果未知，不自动重放。 */
    UNKNOWN("unknown"),
    ;

    val terminal: Boolean get() = this == COMPLETED || this == CANCELLED || this == FAILED

    companion object {
        fun of(wire: String): TaskState = entries.first { it.wire == wire }
    }
}

/** 工具调用的派发状态（events.md 4.3 的 tool.settled.outcome）。 */
enum class ToolCallState(val wire: String) {
    DISPATCHED("dispatched"),
    COMPLETED("completed"),
    NOT_DISPATCHED("not_dispatched"),
    UNKNOWN("unknown"),
    CANCELLED("cancelled"),
    REJECTED("rejected"),
    ;

    companion object {
        fun of(wire: String): ToolCallState = entries.first { it.wire == wire }
    }
}

/**
 * 会话模式（ACP `session/set_mode`，也是配置项 `mode`）。**只能收窄，不能放宽**：模式在会话创建时定下的工具范围（toolScope）里再收一层，
 * 所以任何模式都不会让会话用到 toolScope 以外的工具；调用方在三者之间随便切，最多回到 [DEFAULT]，也就是创建时的上限。
 */
enum class SessionMode(val wire: String, val title: String, val description: String) {
    /** 会话的工具范围里的全部工具；写级每次确认，高风险每次确认（用户策略照常生效）。 */
    DEFAULT("default", "Default", "Use the tools this session is allowed to use. Writes are confirmed as usual."),

    /** 只放行读级工具：写级和高风险工具不交给模型。会话级自带工具（写级起步）因此也不可用。 */
    READ_ONLY("read_only", "Read only", "Only use tools that read. Nothing that changes data is offered to the model."),

    /** 不用任何工具（也没有 Skill 目录），只对话。 */
    CHAT("chat", "Chat", "No tools. Just talk."),
    ;

    companion object {
        /** 未知的 wire 名返回 null（调用方据此报 invalid_params）；NULL / 空白按 [DEFAULT]。 */
        fun parse(wire: String?): SessionMode? = if (wire.isNullOrBlank()) DEFAULT else entries.firstOrNull { it.wire == wire }

        /** 存储里读出来的值：读不懂时当 [CHAT]（最窄）而不是 [DEFAULT]，一个损坏的值不能放宽会话。 */
        fun fromStored(wire: String?): SessionMode = if (wire.isNullOrBlank()) DEFAULT else entries.firstOrNull { it.wire == wire } ?: CHAT
    }
}

data class SessionRecord(
    val id: String,
    val ownerKey: String,
    val callerKind: CallerKind,
    val callerUid: Int,
    val state: SessionState,
    val pauseReason: String?,
    val cwd: String?,
    val createdAt: Long,
    val lastActivityAt: Long,
    val lastSequence: Long,
    val selection: SelectionMetadata,
    /**
     * The tools this session may use, as given to `session/new` (docs/third-party-acp.md 4.5); null = no scope was given. Fixed when the session
     * is created; a damaged stored value reads as an empty list (= no tools), never as null.
     */
    val toolScope: List<ToolRef>? = null,
    /** 会话选的模型（[org.agentos.runtime.ports.ModelChoice.id]）；null = 跟随用户在设置里选的模型。 */
    val modelId: String? = null,
    /** 会话模式；创建时是 [SessionMode.DEFAULT]。 */
    val mode: SessionMode = SessionMode.DEFAULT,
) {
    /** What the session may use, before the caller kind is taken into account ([ToolScope.forCaller]). */
    val scope: ToolScope get() = toolScope?.let { ToolScope.only(it) } ?: ToolScope.ALL
}

/** 自动选会话（session-selection.md）用的元数据：只来自用户输入和模型的最终回答。 */
data class SelectionMetadata(
    val firstQuery: String? = null,
    val firstAnswer: String? = null,
    val latestAnswer: String? = null,
    val recentTurns: List<Pair<String, String>> = emptyList(),
)

data class TaskRecord(
    val id: String,
    val sessionId: String,
    val position: Long,
    val state: TaskState,
    /** ACP 的 ContentBlock 数组，原样保存。 */
    val input: JsonArray,
    val clientRequestId: String?,
    val callerKind: CallerKind,
    val callerUid: Int,
    /** 调用方的显示名（宿主层按 UID 解析，不来自客户端），确认界面要写明发起请求的 App。 */
    val callerLabel: String?,
    /** 第三方 App 的包名（宿主层按 UID 解析）；其他调用方、以及 schema v3 之前的任务为 null。重建调用方身份（确认框）时要用，所以随任务保存。 */
    val callerPackage: String? = null,
    val attempt: Int,
    val createdAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val executionDeadline: Long?,
    val queueDeadline: Long?,
    val cancelReason: String?,
    /** ACP 的 stopReason：end_turn / max_tokens / max_turn_requests / refusal / cancelled。 */
    val stopReason: String?,
    val error: ErrorInfo?,
)

data class ToolCallRecord(
    val taskId: String,
    val toolCallId: String,
    val sessionId: String,
    val name: String,
    val provider: String?,
    val state: ToolCallState,
)
