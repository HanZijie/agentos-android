package org.agentos.runtime.store

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.agentos.runtime.errors.ErrorInfo
import org.agentos.runtime.events.EventEnvelope
import org.agentos.runtime.events.PendingEvent
import org.agentos.runtime.events.RuntimeJson

/**
 * 事件日志（core/contracts/events.md）：只追加；sequence 按会话独立、从 1 开始、不留空洞，
 * 在写入事件的同一个事务里分配（sessions.last_sequence + 1）。
 */
class EventLog internal constructor(
    private val db: DbScope,
    private val now: () -> Long,
    private val onAppend: (sessionId: String, sequence: Long) -> Unit,
) {
    fun append(event: PendingEvent): EventEnvelope {
        val last = db.queryOne("SELECT last_sequence FROM sessions WHERE id = ?", event.sessionId) { it.long(0) }
            ?: throw IllegalStateException("event for unknown session ${event.sessionId}")
        val sequence = last + 1
        val payload = PayloadLimits.apply(event.payload)
        val timestamp = now()
        db.exec("UPDATE sessions SET last_sequence = ? WHERE id = ?", sequence, event.sessionId)
        db.exec(
            "INSERT INTO events (session_id, sequence, task_id, event_type, timestamp, payload, error) VALUES (?, ?, ?, ?, ?, ?, ?)",
            event.sessionId, sequence, event.taskId, event.eventType, timestamp, payload.toString(),
            event.error?.let { RuntimeJson.encodeToString(ErrorInfo.serializer(), it) },
        )
        onAppend(event.sessionId, sequence)
        return EventEnvelope(
            sessionId = event.sessionId,
            taskId = event.taskId,
            sequence = sequence,
            eventType = event.eventType,
            timestamp = timestamp,
            payload = payload,
            error = event.error,
        )
    }

    /** `sequence > afterSequence` 的事件，按 sequence 升序。 */
    fun read(sessionId: String, afterSequence: Long = 0, limit: Int = 1_000): List<EventEnvelope> = db.query(
        "SELECT session_id, sequence, task_id, event_type, timestamp, payload, error FROM events " +
            "WHERE session_id = ? AND sequence > ? ORDER BY sequence LIMIT ?",
        sessionId, afterSequence, limit,
    ) { r ->
        EventEnvelope(
            sessionId = r.string(0),
            sequence = r.long(1),
            taskId = r.stringOrNull(2),
            eventType = r.string(3),
            timestamp = r.long(4),
            payload = RuntimeJson.parseToJsonElement(r.string(5)).jsonObject,
            error = r.stringOrNull(6)?.let { RuntimeJson.decodeFromString(ErrorInfo.serializer(), it) },
        )
    }

    /** 某个任务的全部事件，按 sequence 升序。 */
    fun readTask(sessionId: String, taskId: String): List<EventEnvelope> =
        read(sessionId, 0, Int.MAX_VALUE).filter { it.taskId == taskId }

    /**
     * 分叉用（`session/fork`）：把 [fromSession] 里属于 [taskIds] 的事件按原顺序拷到 [toSession]，sequence 重新从 [toSession] 的末尾接着编，
     * 时间戳保持原样。确认（`consent.*`）和 Hook（`hook.*`）事件不拷：它们记的是当时的一次授权，不属于对话内容，也不该因为分叉被带到新会话里。
     * 返回拷了多少条。
     */
    fun copyTasks(fromSession: String, toSession: String, taskIds: Set<String>): Int {
        if (taskIds.isEmpty()) return 0
        var copied = 0
        var cursor = 0L
        var sequence = lastSequence(toSession)
        while (true) {
            val batch = read(fromSession, cursor, COPY_BATCH)
            if (batch.isEmpty()) break
            for (e in batch) {
                cursor = e.sequence
                if (e.taskId !in taskIds) continue
                if (e.eventType.startsWith("consent.") || e.eventType.startsWith("hook.")) continue
                sequence++
                db.exec(
                    "INSERT INTO events (session_id, sequence, task_id, event_type, timestamp, payload, error) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    toSession, sequence, e.taskId, e.eventType, e.timestamp, e.payload.toString(),
                    e.error?.let { RuntimeJson.encodeToString(ErrorInfo.serializer(), it) },
                )
                copied++
            }
        }
        if (copied > 0) {
            db.exec("UPDATE sessions SET last_sequence = ? WHERE id = ?", sequence, toSession)
            onAppend(toSession, sequence)
        }
        return copied
    }

    fun lastSequence(sessionId: String): Long =
        db.queryOne("SELECT last_sequence FROM sessions WHERE id = ?", sessionId) { it.long(0) } ?: 0

    private companion object {
        const val COPY_BATCH = 500
    }

    /** 某个任务最近一次 [eventType] 事件的时间（毫秒）；没有时 null。走 events_task_idx。 */
    fun lastTimestamp(taskId: String, eventType: String): Long? =
        db.queryOne("SELECT MAX(timestamp) FROM events WHERE task_id = ? AND event_type = ?", taskId, eventType) { it.longOrNull(0) }
}

/**
 * events.md 第 5 节：图片数据不进日志；payload 序列化后超过 65,536 字符时，把最长的字符串截断到 16,384 字符，
 * 并在根上标记 `"truncated": true`。
 */
internal object PayloadLimits {
    const val MAX_PAYLOAD_CHARS = 65_536
    const val TRUNCATED_FIELD_CHARS = 16_384

    fun apply(payload: JsonObject): JsonObject {
        val withoutImages = omitImages(payload) as JsonObject
        if (withoutImages.toString().length <= MAX_PAYLOAD_CHARS) return withoutImages
        var current = withoutImages
        var guard = 0
        while (current.toString().length > MAX_PAYLOAD_CHARS && guard++ < 64) {
            val longest = longestString(current) ?: break
            if (longest.length <= TRUNCATED_FIELD_CHARS) break
            current = replaceString(current, longest) { it.take(TRUNCATED_FIELD_CHARS) + "…[truncated ${it.length} chars]" } as JsonObject
        }
        return JsonObject(current + ("truncated" to JsonPrimitive(true)))
    }

    private fun omitImages(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> {
            val isImage = (e["type"] as? JsonPrimitive)?.contentOrNull == "image"
            JsonObject(
                e.mapValues { (k, v) ->
                    if (isImage && k == "data" && v is JsonPrimitive && v.isString) {
                        JsonPrimitive("<omitted: ${v.content.length} chars>")
                    } else {
                        omitImages(v)
                    }
                },
            )
        }
        is JsonArray -> JsonArray(e.map { omitImages(it) })
        else -> e
    }

    private fun longestString(e: JsonElement): String? = when (e) {
        is JsonObject -> e.values.mapNotNull { longestString(it) }.maxByOrNull { it.length }
        is JsonArray -> e.mapNotNull { longestString(it) }.maxByOrNull { it.length }
        is JsonPrimitive -> if (e.isString) e.content else null
        JsonNull -> null
    }

    private fun replaceString(e: JsonElement, target: String, f: (String) -> String): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (_, v) -> replaceString(v, target, f) })
        is JsonArray -> JsonArray(e.map { replaceString(it, target, f) })
        is JsonPrimitive -> if (e.isString && e.content == target) JsonPrimitive(f(e.content)) else e
    }
}
