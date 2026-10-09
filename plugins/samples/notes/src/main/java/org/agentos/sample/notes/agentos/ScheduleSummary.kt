package org.agentos.sample.notes.agentos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * 从事件流里的 ToolCall 事件和结果 JSON 得出“创建了哪些日程 / 闹钟”（纯函数）。不看模型说了什么：
 * 创建与否只取决于工具调用的状态和工具自己返回的结果。
 */

enum class ItemKind { EVENT, ALARM, TODO, OTHER }

/**
 * 一项的状态（界面上的五种说法加一个“已取消”：用户点了停止、或这一轮结束时它还没有结果）。
 */
enum class ItemStatus {
    AWAITING_APPROVAL, CREATING, CREATED, DENIED, FAILED, CANCELLED;

    val isFinal: Boolean get() = this == CREATED || this == DENIED || this == FAILED || this == CANCELLED
}

/** `event_create` 的结果里我们要的字段（title / start / end，start 与 end 是带时区偏移的 ISO-8601；all_day 为 true 时只显示日期）。 */
data class EventInfo(val title: String, val start: String?, val end: String?, val allDay: Boolean = false)

/** 一个 ISO-8601 时间：[millis] 是时间点，[offset] 是它自带的偏移（如 "+08:00"，没有偏移时用设备时区），[dateOnly] 表示只有日期。 */
data class IsoWhen(val millis: Long, val offset: String, val dateOnly: Boolean)

/** `alarm_create` 的结果里我们要的三个字段（time 是 "HH:mm"，label，days 是 mon..sun；空表示只响一次）。 */
data class AlarmInfo(val time: String, val label: String, val days: List<String>)

/** `todo_create` 的结果里我们要的两个字段（title；due 是日期 "2026-10-12"，或带偏移的 ISO-8601 时间，没有截止时间时缺省）。 */
data class TodoInfo(val title: String, val due: String?)

data class ScheduleItem(
    val id: String,
    val kind: ItemKind,
    /** 事件里带来的原始工具名，仅用于 [ItemKind.OTHER] 时显示。 */
    val tool: String,
    val status: ItemStatus,
    val event: EventInfo? = null,
    val alarm: AlarmInfo? = null,
    val todo: TodoInfo? = null,
    /** 被拒绝 / 失败时工具说的一句话（可能没有）。 */
    val message: String? = null,
)

/** 汇总：创建了几个日程、几个待办、几个闹钟，以及没成的。 */
data class ScheduleSummary(val items: List<ScheduleItem>) {
    val createdEvents: List<ScheduleItem> get() = items.filter { it.kind == ItemKind.EVENT && it.status == ItemStatus.CREATED }
    val createdAlarms: List<ScheduleItem> get() = items.filter { it.kind == ItemKind.ALARM && it.status == ItemStatus.CREATED }
    val createdTodos: List<ScheduleItem> get() = items.filter { it.kind == ItemKind.TODO && it.status == ItemStatus.CREATED }
    val eventCount: Int get() = createdEvents.size
    val alarmCount: Int get() = createdAlarms.size
    val todoCount: Int get() = createdTodos.size
    val createdCount: Int get() = items.count { it.status == ItemStatus.CREATED }
    val deniedCount: Int get() = items.count { it.status == ItemStatus.DENIED }
    val failedCount: Int get() = items.count { it.status == ItemStatus.FAILED }
    val isEmpty: Boolean get() = items.isEmpty()
}

object ScheduleItems {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val MAX_MESSAGE_CHARS = 200

    /** 种类：有 toolScope 的对应项（[ref]）就按它的原始工具名；否则按工具名，允许 `mcp__<插件>__` 这样的前缀。 */
    fun kindOf(tool: String, ref: ToolRef? = null): ItemKind {
        val name = ref?.tool ?: tool
        return when {
            name == "event_create" || (ref == null && name.endsWith("__event_create")) -> ItemKind.EVENT
            name == "alarm_create" || (ref == null && name.endsWith("__alarm_create")) -> ItemKind.ALARM
            name == "todo_create" || (ref == null && name.endsWith("__todo_create")) -> ItemKind.TODO
            else -> ItemKind.OTHER
        }
    }

    fun statusOf(status: ToolStatus): ItemStatus = when (status) {
        ToolStatus.PENDING_APPROVAL -> ItemStatus.AWAITING_APPROVAL
        ToolStatus.RUNNING -> ItemStatus.CREATING
        ToolStatus.COMPLETED -> ItemStatus.CREATED
        ToolStatus.DENIED -> ItemStatus.DENIED
        ToolStatus.FAILED -> ItemStatus.FAILED
    }

    /**
     * 把一次 ToolCall 事件并进列表：同一个 id 更新原来那一项（位置不变），新 id 追加到末尾。
     * 已经有结果的一项不会被迟到的“进行中”事件改回去；后来的事件没带结果时保留之前解析出来的内容。
     */
    fun apply(items: List<ScheduleItem>, call: GatewayEvent.ToolCall): List<ScheduleItem> {
        val index = items.indexOfFirst { it.id == call.id }
        val old = items.getOrNull(index)
        if (old != null && old.status.isFinal && !statusOf(call.status).isFinal) return items
        val status = statusOf(call.status)
        val kind = kindOf(call.tool, call.ref)
        val base = old ?: ScheduleItem(call.id, kind, call.ref?.tool ?: call.tool, status)
        // 内容：创建成功时以工具返回的结果为准；在那之前（等确认、创建中）用模型传的参数预览，让用户看得出这一项是什么。
        // 预览只用于显示，计数只看 status == CREATED。
        val fromResult = if (status == ItemStatus.CREATED) call.resultJson else null
        val updated = base.copy(
            status = status,
            event = if (kind == ItemKind.EVENT) parseEvent(fromResult) ?: base.event ?: parseEvent(call.argumentsJson) else base.event,
            alarm = if (kind == ItemKind.ALARM) parseAlarm(fromResult) ?: base.alarm ?: parseAlarm(call.argumentsJson) else base.alarm,
            todo = if (kind == ItemKind.TODO) parseTodo(fromResult) ?: base.todo ?: parseTodo(call.argumentsJson) else base.todo,
            message = when (status) {
                ItemStatus.DENIED, ItemStatus.FAILED -> messageOf(call.resultJson) ?: base.message
                else -> base.message
            },
        )
        return if (index < 0) items + updated else items.toMutableList().also { it[index] = updated }
    }

    /** 这一轮结束（或被停止）时，还没有结果的项一律记为“已取消”。 */
    fun finalize(items: List<ScheduleItem>): List<ScheduleItem> =
        items.map { if (it.status.isFinal) it else it.copy(status = ItemStatus.CANCELLED) }

    fun parseEvent(resultJson: String?): EventInfo? {
        val obj = asObject(resultJson) ?: return null
        val title = obj.string("title") ?: return null
        val allDay = (obj["all_day"] as? JsonPrimitive)?.booleanOrNull ?: false
        return EventInfo(title, obj.string("start"), obj.string("end"), allDay)
    }

    /**
     * 解析工具结果里的时间：带偏移的 ISO-8601（`2026-10-09T15:00:00+08:00`）、不带偏移的本地时间、只有日期三种都认；
     * 认不出来返回 null（界面就不显示时间，不猜）。
     */
    fun parseWhen(iso: String?, deviceZone: java.time.ZoneId = java.time.ZoneId.systemDefault()): IsoWhen? {
        val text = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        try {
            val odt = java.time.OffsetDateTime.parse(text)
            return IsoWhen(odt.toInstant().toEpochMilli(), odt.offset.id.let { if (it == "Z") "+00:00" else it }, dateOnly = false)
        } catch (_: java.time.format.DateTimeParseException) {
        }
        try {
            val zoned = java.time.LocalDateTime.parse(text).atZone(deviceZone)
            return IsoWhen(zoned.toInstant().toEpochMilli(), zoned.offset.id.let { if (it == "Z") "+00:00" else it }, dateOnly = false)
        } catch (_: java.time.format.DateTimeParseException) {
        }
        try {
            val zoned = java.time.LocalDate.parse(text).atStartOfDay(deviceZone)
            return IsoWhen(zoned.toInstant().toEpochMilli(), zoned.offset.id.let { if (it == "Z") "+00:00" else it }, dateOnly = true)
        } catch (_: java.time.format.DateTimeParseException) {
        }
        return null
    }

    fun parseAlarm(resultJson: String?): AlarmInfo? {
        val obj = asObject(resultJson) ?: return null
        val time = obj.string("time") ?: return null
        val days = (obj["days"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        return AlarmInfo(time, obj.string("label") ?: "", days)
    }

    fun parseTodo(resultJson: String?): TodoInfo? {
        val obj = asObject(resultJson) ?: return null
        val title = obj.string("title") ?: return null
        return TodoInfo(title, obj.string("due"))
    }

    /** 被拒绝 / 失败时的一句话：结果是 JSON 就取 error / message，否则取原文；最多 200 字符。 */
    fun messageOf(resultJson: String?): String? {
        val raw = resultJson?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val fromJson = asObject(raw)?.let { it.string("error") ?: it.string("message") }
        return (fromJson ?: raw).take(MAX_MESSAGE_CHARS)
    }

    private fun asObject(text: String?): JsonObject? {
        val raw = text?.trim()?.takeIf { it.startsWith("{") } ?: return null
        return try {
            json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}
