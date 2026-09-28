package org.agentos.runtime.scheduler

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.events.PendingEvent
import org.agentos.runtime.store.SessionState
import org.agentos.runtime.store.Store
import org.agentos.runtime.store.TaskState

/**
 * 启动恢复流程（architecture F8，session-scheduling.md 第 6 节）。运行时每次启动、调度器开始工作之前执行一次：
 *
 * - 运行中、取消中的任务：没有终态记录，说明上次执行在进程死亡时丢失——标记为结果未知（unknown），
 *   已派发、没有结果的工具调用一并标记为 unknown，会话暂停，写 `task.recovery_required`。**不自动重放。**
 * - 排队的任务：保持排队，由调度器重新调度（safe mode 下不调度）。上一个进程被用户主动停止时（[userStopped]，S2 契约 a 第 6 条）
 *   改为取消（`task.cancelled { by: user_stop }`），不继续。
 * - 会话状态按任务重新归位：有结果未知的任务 → paused；有排队的 → queued；否则 created。
 * - 系统流写 `runtime.recovered`。
 */
internal object Recovery {

    data class Result(val requeued: Int, val recoveryRequired: Int, val sessions: Int, val cancelled: Int)

    suspend fun run(store: Store, userStopped: Boolean = false): Result = store.write { tx ->
        val interrupted = tx.tasks.listByState(TaskState.RUNNING, TaskState.CANCELLING)
        for (t in interrupted) {
            Scheduler.markRecoveryRequired(
                tx, t, "runtime_restarted",
                ErrorCode.TOOL_RESULT_UNKNOWN.info("The runtime stopped before this task finished; its side effects are unknown."),
            )
        }
        var cancelled = 0
        if (userStopped) {
            for (t in tx.tasks.listByState(TaskState.QUEUED)) {
                tx.events.append(PendingEvent(t.sessionId, t.id, EventTypes.TASK_CANCEL_REQUESTED, buildJsonObject { put("by", "user_stop"); put("phase", "queued") }))
                tx.tasks.finish(t.id, TaskState.CANCELLED, tx.now, "cancelled", null)
                tx.events.append(
                    PendingEvent(t.sessionId, t.id, EventTypes.TASK_CANCELLED, buildJsonObject { put("phase", "queued"); put("unknownToolCalls", kotlinx.serialization.json.JsonArray(emptyList())) }),
                )
                cancelled++
            }
        }
        var requeued = 0
        val sessions = tx.sessions.listAll()
        for (s in sessions) {
            if (s.state.terminal) continue
            val tasks = tx.tasks.listBySession(s.id)
            val queued = tasks.count { it.state == TaskState.QUEUED }
            requeued += queued
            val target = when {
                tasks.any { it.state == TaskState.UNKNOWN } -> SessionState.PAUSED
                s.state == SessionState.PAUSED -> SessionState.PAUSED
                queued > 0 -> SessionState.QUEUED
                else -> SessionState.CREATED
            }
            if (target != s.state) {
                Scheduler.changeState(tx, s.id, target, if (target == SessionState.PAUSED) Scheduler.PAUSE_RECOVERY else "runtime_restarted")
            }
        }
        val unknown = tx.tasks.listByState(TaskState.UNKNOWN).size
        tx.events.append(
            PendingEvent(
                EventTypes.SYSTEM_STREAM, null, EventTypes.RUNTIME_RECOVERED,
                buildJsonObject {
                    put("requeued", requeued)
                    put("recoveryRequired", unknown)
                    put("interrupted", interrupted.size)
                    put("userStopped", userStopped)
                    put("cancelled", cancelled)
                },
            ),
        )
        Result(requeued, unknown, sessions.size, cancelled)
    }
}
