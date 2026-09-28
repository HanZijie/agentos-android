package org.agentos.runtime.events

/**
 * 内部事件名（eventType）。权威定义与 payload 字段见 core/contracts/events.md 第 3、4 节；
 * 对客户端的可见性见 core/protocol/acp-mapping.md（W4）。
 *
 * 命名：Pi 的生命周期事件沿用 Pi 的蛇形名（agent_start …）；宿主层自己的事件用 `分类.动作`。
 */
object EventTypes {
    // ---- Pi 生命周期（由 Pi 适配层经 AgentCore 发出）----
    const val AGENT_START = "agent_start"
    const val AGENT_END = "agent_end"
    const val TURN_START = "turn_start"
    const val TURN_END = "turn_end"
    const val MESSAGE_START = "message_start"
    const val MESSAGE_UPDATE = "message_update"
    const val MESSAGE_END = "message_end"
    const val TOOL_EXECUTION_START = "tool_execution_start"
    const val TOOL_EXECUTION_UPDATE = "tool_execution_update"
    const val TOOL_EXECUTION_END = "tool_execution_end"

    // ---- 会话 ----
    const val SESSION_CREATED = "session.created"
    const val SESSION_SELECTED = "session.selected"
    const val SESSION_STATE_CHANGED = "session.state_changed"
    const val SESSION_CLOSED = "session.closed"

    // ---- 任务（一次 session/prompt 对应一个任务）----
    const val TASK_QUEUED = "task.queued"
    const val TASK_STARTED = "task.started"
    const val TASK_COMPLETED = "task.completed"
    const val TASK_CANCEL_REQUESTED = "task.cancel_requested"
    const val TASK_CANCELLED = "task.cancelled"
    const val TASK_FAILED = "task.failed"
    const val TASK_RECOVERY_REQUIRED = "task.recovery_required"
    const val TASK_RECOVERY_RESOLVED = "task.recovery_resolved"

    /** 工具轮次超过上限（12 轮），本轮以 max_turn_requests 结束。 */
    const val TOOL_ROUND_LIMIT = "tool_round_limit"

    // ---- 工具派发（宿主层 Broker；用于“结果未知”判定）----
    const val TOOL_DISPATCHED = "tool.dispatched"
    const val TOOL_SETTLED = "tool.settled"

    // ---- 确认 ----
    const val CONSENT_REQUESTED = "consent.requested"
    const val CONSENT_RESOLVED = "consent.resolved"

    // ---- Hook ----
    const val HOOK_DISPATCHED = "hook.dispatched"
    const val HOOK_DECIDED = "hook.decided"
    const val HOOK_FAILED = "hook.failed"

    // ---- 系统流（sessionId = SYSTEM_STREAM）----
    const val SUPERVISOR_STATUS = "supervisor.status"
    const val RUNTIME_STARTED = "runtime.started"
    const val RUNTIME_RECOVERED = "runtime.recovered"
    const val AGENT_CORE_FAILED = "agent_core.failed"
    const val AGENT_CORE_RESTARTED = "agent_core.restarted"
    const val EXTENSION_STATUS = "extension.status"

    /** 系统流的 sessionId：运行时级别的事件（监督状态、Agent core 故障等）写在这里，有自己的 sequence，不对 ACP 客户端可见。 */
    const val SYSTEM_STREAM = "_system"

    /** Pi 的生命周期事件名。 */
    val PI_LIFECYCLE: Set<String> = setOf(
        AGENT_START, AGENT_END, TURN_START, TURN_END, MESSAGE_START, MESSAGE_UPDATE, MESSAGE_END,
        TOOL_EXECUTION_START, TOOL_EXECUTION_UPDATE, TOOL_EXECUTION_END,
    )

    /** 宿主层的事件名。 */
    val HOST: Set<String> = setOf(
        SESSION_CREATED, SESSION_SELECTED, SESSION_STATE_CHANGED, SESSION_CLOSED,
        TASK_QUEUED, TASK_STARTED, TASK_COMPLETED, TASK_CANCEL_REQUESTED, TASK_CANCELLED, TASK_FAILED,
        TASK_RECOVERY_REQUIRED, TASK_RECOVERY_RESOLVED, TOOL_ROUND_LIMIT, TOOL_DISPATCHED, TOOL_SETTLED,
        CONSENT_REQUESTED, CONSENT_RESOLVED, HOOK_DISPATCHED, HOOK_DECIDED, HOOK_FAILED,
        SUPERVISOR_STATUS, RUNTIME_STARTED, RUNTIME_RECOVERED, AGENT_CORE_FAILED, AGENT_CORE_RESTARTED, EXTENSION_STATUS,
    )

    /** 任务的终态事件：一个任务的事件序列以其中之一结束（recovery_required 之后可以再有 recovery_resolved 与终态）。 */
    val TASK_TERMINAL: Set<String> = setOf(TASK_COMPLETED, TASK_CANCELLED, TASK_FAILED)
}
