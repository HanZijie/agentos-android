package org.agentos.runtime.store

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.events.RuntimeJson
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.PiMessages
import org.agentos.runtime.ports.ToolRef
import org.agentos.runtime.ports.ToolScope

/** 会话表与各会话的 Pi messages。只在 Store 的事务里使用。 */
class SessionStore internal constructor(private val db: DbScope) {

    fun create(id: String, caller: CallerIdentity, cwd: String?, now: Long, toolScope: List<ToolRef>? = null): SessionRecord {
        db.exec(
            "INSERT INTO sessions (id, owner_key, caller_kind, caller_uid, state, cwd, created_at, last_activity_at, ready_since, tool_scope) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, caller.ownerKey, caller.kind, caller.uid, SessionState.CREATED.wire, cwd, now, now, now,
            toolScope?.let { ToolScope.toJson(it).toString() },
        )
        return requireNotNull(get(id))
    }

    fun get(id: String): SessionRecord? = db.queryOne("$SELECT WHERE id = ?", id, map = ::toRecord)

    /** 调用方自己的会话（不含系统流），按最近活动倒序。 */
    fun listByOwner(ownerKey: String, limit: Int = 1_000): List<SessionRecord> =
        db.query("$SELECT WHERE owner_key = ? AND state != ? ORDER BY last_activity_at DESC, id LIMIT ?", ownerKey, SessionState.SYSTEM.wire, limit, map = ::toRecord)

    fun listByState(state: SessionState): List<SessionRecord> =
        db.query("$SELECT WHERE state = ? ORDER BY ready_since, id", state.wire, map = ::toRecord)

    fun listAll(): List<SessionRecord> =
        db.query("$SELECT WHERE id != ? ORDER BY created_at, id", EventTypes.SYSTEM_STREAM, map = ::toRecord)

    /** 改状态；进入 queued 时刷新 ready_since（排到轮转队尾）。返回旧状态。 */
    fun setState(id: String, state: SessionState, now: Long, pauseReason: String? = null): SessionState {
        val old = requireNotNull(get(id)) { "no session $id" }.state
        if (state == SessionState.QUEUED && old != SessionState.QUEUED) {
            db.exec("UPDATE sessions SET state = ?, pause_reason = ?, ready_since = ? WHERE id = ?", state.wire, pauseReason, now, id)
        } else {
            db.exec("UPDATE sessions SET state = ?, pause_reason = ? WHERE id = ?", state.wire, pauseReason, id)
        }
        return old
    }

    fun touch(id: String, now: Long) {
        db.exec("UPDATE sessions SET last_activity_at = ? WHERE id = ?", now, id)
    }

    /** 会话模型（[org.agentos.runtime.ports.ModelChoice.id]）；null = 跟随用户在设置里选的模型。 */
    fun setModel(id: String, modelId: String?, now: Long) {
        db.exec("UPDATE sessions SET model_id = ?, last_activity_at = ? WHERE id = ?", modelId, now, id)
    }

    /** 会话模式。 */
    fun setMode(id: String, mode: SessionMode, now: Long) {
        db.exec("UPDATE sessions SET mode = ?, last_activity_at = ? WHERE id = ?", mode.wire, now, id)
    }

    /**
     * 删除一个会话和它的一切：任务、工具调用、Pi messages（外键级联）以及**事件日志**（events 没有外键，要自己删）。
     * 调用方先确认没有未结束的任务。系统流不能删。
     */
    fun delete(id: String) {
        require(id != EventTypes.SYSTEM_STREAM) { "the system stream cannot be deleted" }
        db.exec("DELETE FROM events WHERE session_id = ?", id)
        db.exec("DELETE FROM sessions WHERE id = ?", id)
    }

    /**
     * 分叉（ACP `session/fork`）：新会话 [newId] 带着 [source] 的 cwd、选择元数据、模型和模式，以及它**最近一次稳定的** Pi messages
     * （最近一个正常结束的任务之后的上下文；进行中的一轮不带过去）。事件日志由 [EventLog.copyTasks] 另外拷。
     * 新会话属于 [caller]；[toolScope] 由调用方（引擎）按“只能收窄”算好。
     */
    fun fork(source: SessionRecord, newId: String, caller: CallerIdentity, toolScope: List<ToolRef>?, now: Long): SessionRecord {
        create(newId, caller, source.cwd, now, toolScope)
        db.exec(
            "UPDATE sessions SET first_query = ?, first_answer = ?, latest_answer = ?, recent_turns = ?, model_id = ?, mode = ? WHERE id = ?",
            source.selection.firstQuery, source.selection.firstAnswer, source.selection.latestAnswer,
            RuntimeJson.encodeToString(TURNS, source.selection.recentTurns), source.modelId, source.mode.wire.takeIf { source.mode != SessionMode.DEFAULT }, newId,
        )
        db.exec(
            "INSERT INTO pi_messages (session_id, messages, stable_messages, updated_at) " +
                "SELECT ?, stable_messages, stable_messages, ? FROM pi_messages WHERE session_id = ? AND stable_messages IS NOT NULL",
            newId, now, source.id,
        )
        return requireNotNull(get(newId))
    }

    fun setFirstQueryIfAbsent(id: String, query: String) {
        if (query.isBlank()) return
        db.exec("UPDATE sessions SET first_query = ? WHERE id = ? AND first_query IS NULL", query.take(SELECTION_TEXT_LIMIT), id)
    }

    /** 一轮正常完成后更新选择元数据：首轮回答、最近回答、最近两轮。 */
    fun recordCompletedTurn(id: String, query: String, answer: String) {
        val s = get(id) ?: return
        val q = query.take(SELECTION_TEXT_LIMIT)
        val a = answer.take(SELECTION_TEXT_LIMIT)
        val recent = (s.selection.recentTurns + (q to a)).takeLast(2)
        db.exec(
            "UPDATE sessions SET first_answer = COALESCE(first_answer, ?), latest_answer = ?, recent_turns = ? WHERE id = ?",
            a.ifBlank { null }, a.ifBlank { s.selection.latestAnswer }, RuntimeJson.encodeToString(TURNS, recent), id,
        )
    }

    // ---- Pi messages ----

    fun saveMessages(id: String, messages: PiMessages, now: Long, stable: Boolean) {
        val json = messages.json.toString()
        db.exec(
            "INSERT INTO pi_messages (session_id, messages, stable_messages, updated_at) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(session_id) DO UPDATE SET messages = excluded.messages, updated_at = excluded.updated_at" +
                if (stable) ", stable_messages = excluded.messages" else "",
            id, json, if (stable) json else null, now,
        )
    }

    fun loadMessages(id: String): PiMessages? =
        db.queryOne("SELECT messages FROM pi_messages WHERE session_id = ?", id) { PiMessages(RuntimeJson.parseToJsonElement(it.string(0)) as JsonArray) }

    /** 放弃结果未知的任务时，把 messages 退回最近一次任务结束时的状态（没有就清空，重建时从 system 消息开始）。 */
    fun rollbackMessagesToStable(id: String, now: Long) {
        val updated = db.update(
            "UPDATE pi_messages SET messages = stable_messages, updated_at = ? WHERE session_id = ? AND stable_messages IS NOT NULL",
            now, id,
        )
        if (updated == 0) db.exec("DELETE FROM pi_messages WHERE session_id = ?", id)
    }

    private fun toRecord(r: Row) = SessionRecord(
        id = r.string(0),
        ownerKey = r.string(1),
        callerKind = CallerKind.valueOf(r.string(2).uppercase()),
        callerUid = r.int(3),
        state = SessionState.of(r.string(4)),
        pauseReason = r.stringOrNull(5),
        cwd = r.stringOrNull(6),
        createdAt = r.long(7),
        lastActivityAt = r.long(8),
        lastSequence = r.long(9),
        selection = SelectionMetadata(
            firstQuery = r.stringOrNull(10),
            firstAnswer = r.stringOrNull(11),
            latestAnswer = r.stringOrNull(12),
            recentTurns = RuntimeJson.decodeFromString(TURNS, r.string(13)),
        ),
        toolScope = ToolScope.fromJson(r.stringOrNull(14)),
        modelId = r.stringOrNull(15),
        mode = SessionMode.fromStored(r.stringOrNull(16)),
    )

    companion object {
        /** session-selection.md：选择元数据每段最多保留的字符数。 */
        const val SELECTION_TEXT_LIMIT = 8_000

        private val TURNS = ListSerializer(PairSerializer(String.serializer(), String.serializer()))

        private const val SELECT = "SELECT id, owner_key, caller_kind, caller_uid, state, pause_reason, cwd, created_at, " +
            "last_activity_at, last_sequence, first_query, first_answer, latest_answer, recent_turns, tool_scope, model_id, mode FROM sessions"
    }
}
