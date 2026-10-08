package org.agentos.sample.notes.agentos

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * debug 的 `ask_agent` / `ask_agent_status` 返回的汇总（JSON，字段 snake_case）。只在 debug 包里。
 * 不含任何密钥，也不含备忘全文：只有 note_id、发送的字数、Agent 的回复（最多 [MAX_AGENT_TEXT] 字）和创建项。
 */
object ScheduleReport {
    const val MAX_AGENT_TEXT = 1_000

    fun stateName(state: ScheduleState): String = when (state) {
        is ScheduleState.Idle -> "idle"
        is ScheduleState.Ready -> "ready"
        is ScheduleState.Checking -> "checking"
        is ScheduleState.WaitingAuthorization -> "waiting_authorization"
        is ScheduleState.Running -> "running"
        is ScheduleState.Done -> "done"
        is ScheduleState.Error -> "error"
    }

    /** 一轮的汇总。[timedOut]：超过硬超时被停止；[pending]：这一轮还在进行，稍后用 ask_agent_status 再读。 */
    fun of(state: ScheduleState, timedOut: Boolean = false, gateway: String? = null, pending: Boolean = false): JsonObject = buildJsonObject {
        put("state", stateName(state))
        put("pending", pending)
        put("timed_out", timedOut)
        if (gateway != null) put("gateway", gateway)
        put("note_id", state.source.noteId?.let(::JsonPrimitive) ?: JsonNull)
        put("text_chars", state.source.text.length)
        val summary: ScheduleSummary?
        var agentText = ""
        when (state) {
            is ScheduleState.Done -> {
                summary = state.summary
                agentText = state.text
                put("stopped", state.stopped)
                put("error", JsonNull)
            }
            is ScheduleState.Error -> {
                summary = state.summary
                put("stopped", false)
                put("error", state.error.name)
                put("error_detail", state.detail?.let(::JsonPrimitive) ?: JsonNull)
                put("interrupted", state.interrupted)
            }
            is ScheduleState.Running -> {
                summary = ScheduleSummary(state.items)
                agentText = state.text
                put("stopped", false)
                put("error", JsonNull)
            }
            else -> {
                summary = null
                put("stopped", false)
                put("error", JsonNull)
            }
        }
        put("created", buildJsonObject {
            put("events", summary?.eventCount ?: 0)
            put("alarms", summary?.alarmCount ?: 0)
            put("denied", summary?.deniedCount ?: 0)
            put("failed", summary?.failedCount ?: 0)
        })
        put("items", JsonArray((summary?.items ?: emptyList()).map(::item)))
        put("agent_text", agentText.trim().take(MAX_AGENT_TEXT))
    }

    /** `ask_agent_status`：还没跑过时 `state` 是 `none`；否则是最近一轮的汇总，再加上当前面板状态 `current_state`。 */
    fun status(last: ScheduleState?, current: ScheduleState, timedOut: Boolean = false, gateway: String? = null): JsonObject =
        if (last == null) buildJsonObject {
            put("state", "none")
            put("pending", current.inFlight)
            put("current_state", stateName(current))
        } else JsonObject(of(last, timedOut, gateway, pending = current.inFlight) + ("current_state" to JsonPrimitive(stateName(current))))

    private fun item(item: ScheduleItem): JsonElement = buildJsonObject {
        put("id", item.id)
        put("kind", item.kind.name.lowercase())
        put("tool", item.tool)
        put("status", item.status.name.lowercase())
        item.event?.let {
            put("title", it.title)
            put("start", it.start?.let(::JsonPrimitive) ?: JsonNull)
            put("end", it.end?.let(::JsonPrimitive) ?: JsonNull)
        }
        item.alarm?.let {
            put("time", it.time)
            put("label", it.label)
            put("days", buildJsonArray { it.days.forEach { d -> add(JsonPrimitive(d)) } })
        }
        put("message", item.message?.let(::JsonPrimitive) ?: JsonNull)
    }
}
