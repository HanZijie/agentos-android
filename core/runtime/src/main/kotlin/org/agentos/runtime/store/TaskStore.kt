package org.agentos.runtime.store

import kotlinx.serialization.json.JsonArray
import org.agentos.runtime.errors.ErrorInfo
import org.agentos.runtime.events.RuntimeJson
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import java.security.MessageDigest

/** 任务表与工具调用表。只在 Store 的事务里使用。 */
class TaskStore internal constructor(private val db: DbScope) {

    fun create(
        id: String,
        sessionId: String,
        input: JsonArray,
        caller: CallerIdentity,
        clientRequestId: String?,
        now: Long,
        queueDeadline: Long?,
    ): TaskRecord {
        val position = (db.queryOne("SELECT MAX(position) FROM tasks WHERE session_id = ?", sessionId) { it.longOrNull(0) } ?: 0) + 1
        db.exec(
            "INSERT INTO tasks (id, session_id, position, state, input, input_hash, client_request_id, caller_kind, caller_uid, caller_label, created_at, queue_deadline) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, sessionId, position, TaskState.QUEUED.wire, input.toString(), hash(input), clientRequestId,
            caller.kind, caller.uid, caller.label, now, queueDeadline,
        )
        return requireNotNull(get(id))
    }

    fun get(id: String): TaskRecord? = db.queryOne("$SELECT WHERE id = ?", id, map = ::toRecord)

    fun findByClientRequest(sessionId: String, clientRequestId: String): TaskRecord? =
        db.queryOne("$SELECT WHERE session_id = ? AND client_request_id = ?", sessionId, clientRequestId, map = ::toRecord)

    /** 与 [input] 内容是否相同（幂等提交用）。 */
    fun sameInput(task: TaskRecord, input: JsonArray): Boolean =
        db.queryOne("SELECT input_hash FROM tasks WHERE id = ?", task.id) { it.string(0) } == hash(input)

    fun nextQueued(sessionId: String): TaskRecord? =
        db.queryOne("$SELECT WHERE session_id = ? AND state = ? ORDER BY position LIMIT 1", sessionId, TaskState.QUEUED.wire, map = ::toRecord)

    fun listBySession(sessionId: String): List<TaskRecord> =
        db.query("$SELECT WHERE session_id = ? ORDER BY position", sessionId, map = ::toRecord)

    fun listByState(vararg states: TaskState): List<TaskRecord> {
        val marks = states.joinToString(",") { "?" }
        return db.query("$SELECT WHERE state IN ($marks) ORDER BY created_at, position", *states.map { it.wire }.toTypedArray(), map = ::toRecord)
    }

    fun countQueued(sessionId: String): Int =
        db.queryOne("SELECT COUNT(*) FROM tasks WHERE session_id = ? AND state = ?", sessionId, TaskState.QUEUED.wire) { it.int(0) } ?: 0

    fun markStarted(id: String, now: Long, executionDeadline: Long?): TaskRecord {
        db.exec(
            "UPDATE tasks SET state = ?, attempt = attempt + 1, started_at = ?, execution_deadline = ?, cancel_reason = NULL WHERE id = ?",
            TaskState.RUNNING.wire, now, executionDeadline, id,
        )
        return requireNotNull(get(id))
    }

    fun setState(id: String, state: TaskState) {
        db.exec("UPDATE tasks SET state = ? WHERE id = ?", state.wire, id)
    }

    fun markCancelling(id: String, reason: String) {
        db.exec("UPDATE tasks SET state = ?, cancel_reason = ? WHERE id = ?", TaskState.CANCELLING.wire, reason, id)
    }

    fun finish(id: String, state: TaskState, now: Long, stopReason: String?, error: ErrorInfo?) {
        db.exec(
            "UPDATE tasks SET state = ?, finished_at = ?, stop_reason = ?, error = ? WHERE id = ?",
            state.wire, now, stopReason, error?.let { RuntimeJson.encodeToString(ErrorInfo.serializer(), it) }, id,
        )
    }

    fun markUnknown(id: String, error: ErrorInfo) {
        db.exec(
            "UPDATE tasks SET state = ?, error = ? WHERE id = ?",
            TaskState.UNKNOWN.wire, RuntimeJson.encodeToString(ErrorInfo.serializer(), error), id,
        )
    }

    // ---- 工具调用 ----

    fun recordDispatched(taskId: String, sessionId: String, toolCallId: String, name: String, provider: String?, now: Long) {
        db.exec(
            "INSERT INTO tool_calls (task_id, tool_call_id, session_id, name, provider, state, dispatched_at) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(task_id, tool_call_id) DO UPDATE SET state = excluded.state, dispatched_at = excluded.dispatched_at",
            taskId, toolCallId, sessionId, name, provider, ToolCallState.DISPATCHED.wire, now,
        )
    }

    fun recordSettled(taskId: String, sessionId: String, toolCallId: String, name: String, state: ToolCallState, now: Long) {
        db.exec(
            "INSERT INTO tool_calls (task_id, tool_call_id, session_id, name, state, settled_at) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(task_id, tool_call_id) DO UPDATE SET state = excluded.state, settled_at = excluded.settled_at",
            taskId, toolCallId, sessionId, name, state.wire, now,
        )
    }

    fun toolCalls(taskId: String): List<ToolCallRecord> = db.query(
        "SELECT task_id, tool_call_id, session_id, name, provider, state FROM tool_calls WHERE task_id = ? ORDER BY dispatched_at, tool_call_id",
        taskId,
    ) { r -> ToolCallRecord(r.string(0), r.string(1), r.string(2), r.string(3), r.stringOrNull(4), ToolCallState.of(r.string(5))) }

    /** 恢复时：所有“已派发、没有结果”的调用改为结果未知。返回受影响的调用。 */
    fun fenceDispatched(taskId: String): List<ToolCallRecord> {
        val pending = toolCalls(taskId).filter { it.state == ToolCallState.DISPATCHED }
        db.exec("UPDATE tool_calls SET state = ? WHERE task_id = ? AND state = ?", ToolCallState.UNKNOWN.wire, taskId, ToolCallState.DISPATCHED.wire)
        return pending.map { it.copy(state = ToolCallState.UNKNOWN) }
    }

    private fun toRecord(r: Row) = TaskRecord(
        id = r.string(0),
        sessionId = r.string(1),
        position = r.long(2),
        state = TaskState.of(r.string(3)),
        input = RuntimeJson.parseToJsonElement(r.string(4)) as JsonArray,
        clientRequestId = r.stringOrNull(5),
        callerKind = CallerKind.valueOf(r.string(6).uppercase()),
        callerUid = r.int(7),
        callerLabel = r.stringOrNull(17),
        attempt = r.int(8),
        createdAt = r.long(9),
        startedAt = r.longOrNull(10),
        finishedAt = r.longOrNull(11),
        executionDeadline = r.longOrNull(12),
        queueDeadline = r.longOrNull(13),
        cancelReason = r.stringOrNull(14),
        stopReason = r.stringOrNull(15),
        error = r.stringOrNull(16)?.let { RuntimeJson.decodeFromString(ErrorInfo.serializer(), it) },
    )

    companion object {
        private const val SELECT = "SELECT id, session_id, position, state, input, client_request_id, caller_kind, caller_uid, attempt, " +
            "created_at, started_at, finished_at, execution_deadline, queue_deadline, cancel_reason, stop_reason, error, caller_label FROM tasks"

        fun hash(input: JsonArray): String =
            MessageDigest.getInstance("SHA-256").digest(input.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
