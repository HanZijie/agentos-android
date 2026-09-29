package org.agentos.runtime.scheduler

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.errors.ErrorInfo
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.events.PendingEvent
import org.agentos.runtime.store.SessionState
import org.agentos.runtime.store.Store
import org.agentos.runtime.store.StoreTx
import org.agentos.runtime.store.TaskRecord
import org.agentos.runtime.store.TaskState

/**
 * 启动恢复流程（architecture F8，session-scheduling.md 第 6 节）。运行时每次启动、调度器开始工作之前执行一次：
 *
 * - 运行中、取消中的任务：没有终态记录，说明上次执行在进程死亡时丢失——标记为结果未知（unknown），
 *   已派发、没有结果的工具调用一并标记为 unknown，会话暂停，写 `task.recovery_required`。**不自动重放。**
 * - 排队的任务：保持排队，由调度器重新调度（safe mode 下不调度）。上一个进程被用户主动停止时（[userStopped]，S2 契约 a 第 6 条）
 *   改为取消（`task.cancelled { by: user_stop }`），不继续。
 * - **需要恢复的任务的过渡期限**（F8，整合人 2026-09-29 决定；W10 之后保留作兜底）：需要恢复满
 *   [SchedulerConfig.recoveryExpiryMillis]（24 小时）的任务按“放弃”结束；剩下的超过 [SchedulerConfig.maxRecoveryPending]（50 条）时，
 *   从最旧的开始同样放弃。放弃 = `task.recovery_resolved { decision: abandon, by: system, reason: recovery_expired, rule }`
 *   → 终态 `task.failed`（`recovery_expired`），会话的 messages 回到上一个稳定点。不重放。
 *   “需要恢复的时间”取这个任务最近一次 `task.recovery_required` 事件的时间。
 * - 会话状态按任务重新归位：有结果未知的任务 → paused；有排队的 → queued；否则 created。
 * - 系统流写 `runtime.recovered`。
 */
internal object Recovery {

    data class Result(val requeued: Int, val recoveryRequired: Int, val sessions: Int, val cancelled: Int, val expired: Int = 0)

    suspend fun run(store: Store, userStopped: Boolean = false, config: SchedulerConfig = SchedulerConfig()): Result = store.write { tx ->
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
        val expired = expireRecoveryPending(tx, config)
        var requeued = 0
        val sessions = tx.sessions.listAll()
        for (s in sessions) {
            if (s.state.terminal) continue
            val tasks = tx.tasks.listBySession(s.id)
            val queued = tasks.count { it.state == TaskState.QUEUED }
            requeued += queued
            val target = when {
                tasks.any { it.state == TaskState.UNKNOWN } -> SessionState.PAUSED
                // 因为恢复而暂停、但已经没有需要恢复的任务（全被放弃）：恢复调度；其他原因的暂停保持
                s.state == SessionState.PAUSED && s.pauseReason != Scheduler.PAUSE_RECOVERY -> SessionState.PAUSED
                queued > 0 -> SessionState.QUEUED
                else -> SessionState.CREATED
            }
            if (target != s.state) {
                Scheduler.changeState(
                    tx, s.id, target,
                    when {
                        target == SessionState.PAUSED -> Scheduler.PAUSE_RECOVERY
                        s.state == SessionState.PAUSED -> "recovery_resolved"
                        else -> "runtime_restarted"
                    },
                )
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
                    put("expired", expired)
                },
            ),
        )
        Result(requeued, unknown, sessions.size, cancelled, expired)
    }

    /** 过渡期限（见类注释）：返回放弃的任务数。 */
    private fun expireRecoveryPending(tx: StoreTx, config: SchedulerConfig): Int {
        if (config.recoveryExpiryMillis == 0L && config.maxRecoveryPending == 0) return 0
        val pending = tx.tasks.listByState(TaskState.UNKNOWN).map { t ->
            // 需要恢复的时间：最近一次 task.recovery_required；万一没有（不应发生），退回开始 / 创建时间
            Pending(t, tx.events.lastTimestamp(t.id, EventTypes.TASK_RECOVERY_REQUIRED) ?: t.startedAt ?: t.createdAt)
        }
        val (byAge, left) = pending.partition { config.recoveryExpiryMillis > 0 && tx.now - it.since >= config.recoveryExpiryMillis }
        val oldestFirst = left.sortedWith(compareBy<Pending>({ it.since }, { it.task.createdAt }, { it.task.position }))
        val byLimit = if (config.maxRecoveryPending > 0 && oldestFirst.size > config.maxRecoveryPending) {
            oldestFirst.take(oldestFirst.size - config.maxRecoveryPending)
        } else {
            emptyList()
        }
        byAge.forEach { abandon(tx, it, RULE_AGE, config) }
        byLimit.forEach { abandon(tx, it, RULE_LIMIT, config) }
        return byAge.size + byLimit.size
    }

    private class Pending(val task: TaskRecord, val since: Long)

    private fun abandon(tx: StoreTx, p: Pending, rule: String, config: SchedulerConfig) {
        val t = p.task
        tx.events.append(
            PendingEvent(
                t.sessionId, t.id, EventTypes.TASK_RECOVERY_RESOLVED,
                buildJsonObject {
                    put("decision", "abandon")
                    put("by", "system")
                    put("reason", ErrorCode.RECOVERY_EXPIRED.wire)
                    put("rule", rule)
                    put("pendingSince", p.since)
                },
            ),
        )
        val message = when (rule) {
            RULE_AGE -> "Nobody decided whether to retry this interrupted task before the recovery deadline; it was abandoned."
            else -> "More than ${config.maxRecoveryPending} interrupted tasks were waiting for a decision; the oldest were abandoned."
        }
        Scheduler.failTask(tx, t, ErrorInfo(ErrorCode.RECOVERY_EXPIRED, message, details = buildJsonObject { put("rule", rule) }), "unknown")
        // 与用户放弃相同：丢掉中断的那一轮，会话的 messages 回到上一个稳定点
        tx.sessions.rollbackMessagesToStable(t.sessionId, tx.now)
    }

    /** 满期限放弃。 */
    const val RULE_AGE = "age"

    /** 超过保留上限放弃。 */
    const val RULE_LIMIT = "limit"
}
