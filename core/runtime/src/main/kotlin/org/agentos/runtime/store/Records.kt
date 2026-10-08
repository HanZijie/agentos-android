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
