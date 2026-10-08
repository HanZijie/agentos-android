package org.agentos.sample.alarm.tools

import java.time.DayOfWeek
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.sample.alarm.data.Alarm
import org.agentos.sample.alarm.data.AlarmDraft
import org.agentos.sample.alarm.data.AlarmException
import org.agentos.sample.alarm.data.AlarmPatch
import org.agentos.sample.alarm.data.AlarmRepository
import org.agentos.sample.alarm.data.EVERY_DAY
import org.agentos.sample.alarm.data.WEEKDAYS
import org.agentos.sample.alarm.data.WEEKEND
import org.agentos.sample.alarm.schedule.SystemAlarmInfo

/**
 * 闹钟的 MCP 工具（docs/sample-apps.md 4.1 节）。名字和必填参数是契约，不能改。
 * 和界面共用同一个 [AlarmRepository]：写操作同步重排 / 取消系统闹钟，界面经 StateFlow 立即刷新。
 *
 * 约定：时间一律 ISO-8601 带偏移；闹钟的“时刻”用本地 "HH:mm"；id 是字符串；
 * 失败返回 isError + 一句话原因，不抛异常。
 */
class AlarmTools(
    private val repository: AlarmRepository,
    private val ring: RingControl,
    private val systemAlarms: SystemAlarmInfo,
) {
    val tools: List<ToolDef> = listOf(
        alarmList(),
        alarmGet(),
        alarmCreate(),
        alarmUpdate(),
        alarmSetEnabled(),
        alarmDelete(),
        alarmNext(),
        alarmDismiss(),
        alarmSnooze(),
        alarmSystemNext(),
    )

    fun find(name: String): ToolDef? = tools.firstOrNull { it.name == name }

    /** 一个闹钟在工具结果里的 JSON 形态（字段 snake_case）。debug 的读状态命令复用它，保证与 MCP 返回一致。 */
    fun describe(alarm: Alarm): JsonObject = alarm.toJson()

    // ---- 工具定义 ----

    private fun alarmList() = tool(
        name = "alarm_list",
        title = "List alarms",
        description = "List all alarms on this phone, ordered by when they will ring next (switched-off alarms last). " +
            "Each alarm has id, time (local HH:mm), label, days, enabled, next_fire_at (ISO-8601 with offset, null when off). " +
            "Set enabled_only=true to leave out switched-off alarms.",
        schema = schema {
            put("enabled_only", boolProp("Only return alarms that are switched on (default false)."))
        },
        annotations = ToolAnnotations(readOnlyHint = true),
    ) { args ->
        val enabledOnly = args.optBoolean("enabled_only") ?: false
        val alarms = repository.list(enabledOnly)
        ToolOutput.json(
            buildJsonObject {
                put("count", alarms.size)
                put("alarms", buildJsonArray { alarms.forEach { add(it.toJson()) } })
            },
        )
    }

    private fun alarmGet() = tool(
        name = "alarm_get",
        title = "Get an alarm",
        description = "Get one alarm by id, including its next_fire_at and whether it is ringing right now.",
        schema = schema(required = listOf("id")) { put("id", idProp()) },
        annotations = ToolAnnotations(readOnlyHint = true),
    ) { args ->
        ToolOutput.json(repository.require(args.requireId()).toJson())
    }

    private fun alarmCreate() = tool(
        name = "alarm_create",
        title = "Create an alarm",
        description = "Create an alarm that really rings (system alarm clock, wakes the screen). " +
            "time is local 24-hour \"HH:mm\" (e.g. \"07:30\"). days is a list of mon,tue,wed,thu,fri,sat,sun; " +
            "empty or omitted means ring once at the next occurrence of that time, then switch off. " +
            "Returns the new alarm with next_fire_at (ISO-8601 with offset).",
        schema = schema(required = listOf("time")) {
            put("time", timeProp())
            put("label", stringProp("Short name shown on the alarm, e.g. \"Wake up\" (max ${Alarm.MAX_LABEL_LENGTH} chars)."))
            put("days", daysProp())
            put("enabled", boolProp("Switch the alarm on right away (default true)."))
            put("vibrate", boolProp("Vibrate when ringing (default true)."))
            put("snooze_minutes", snoozeProp())
        },
        annotations = ToolAnnotations(),
    ) { args ->
        val (hour, minute) = args.requireTime()
        val draft = AlarmDraft(
            hour = hour,
            minute = minute,
            label = args.optString("label") ?: "",
            days = args.optDays() ?: emptySet(),
            enabled = args.optBoolean("enabled") ?: true,
            vibrate = args.optBoolean("vibrate") ?: true,
            snoozeMinutes = args.optInt("snooze_minutes") ?: Alarm.DEFAULT_SNOOZE_MINUTES,
        )
        ToolOutput.json(repository.create(draft).toJson())
    }

    private fun alarmUpdate() = tool(
        name = "alarm_update",
        title = "Update an alarm",
        description = "Change an existing alarm; only the fields you pass are changed. " +
            "time is \"HH:mm\"; days replaces the whole repeat set (empty list = ring once); label \"\" clears the label. " +
            "The system alarm is rescheduled. Returns the updated alarm.",
        schema = schema(required = listOf("id")) {
            put("id", idProp())
            put("time", timeProp())
            put("label", stringProp("New label; empty string clears it."))
            put("days", daysProp())
            put("enabled", boolProp("Switch the alarm on or off."))
            put("vibrate", boolProp("Vibrate when ringing."))
            put("snooze_minutes", snoozeProp())
        },
        annotations = ToolAnnotations(idempotentHint = true),
    ) { args ->
        val id = args.requireId()
        val time = args.optTime()
        val patch = AlarmPatch(
            hour = time?.first,
            minute = time?.second,
            label = args.optString("label"),
            days = args.optDays(),
            enabled = args.optBoolean("enabled"),
            vibrate = args.optBoolean("vibrate"),
            snoozeMinutes = args.optInt("snooze_minutes"),
        )
        if (patch.isEmpty) {
            throw AlarmException("Nothing to update: pass at least one of time, label, days, enabled, vibrate, snooze_minutes")
        }
        ToolOutput.json(repository.update(id, patch).toJson())
    }

    private fun alarmSetEnabled() = tool(
        name = "alarm_set_enabled",
        title = "Switch an alarm on or off",
        description = "Switch an alarm on or off. Switching on schedules the next ring (returned as next_fire_at); " +
            "switching off cancels it (and any snooze).",
        schema = schema(required = listOf("id", "enabled")) {
            put("id", idProp())
            put("enabled", boolProp("true to switch on, false to switch off."))
        },
        annotations = ToolAnnotations(idempotentHint = true),
    ) { args ->
        val id = args.requireId()
        val enabled = args.optBoolean("enabled") ?: throw AlarmException("Missing required parameter 'enabled' (boolean)")
        ToolOutput.json(repository.setEnabled(id, enabled).toJson())
    }

    private fun alarmDelete() = tool(
        name = "alarm_delete",
        title = "Delete an alarm",
        description = "Permanently delete an alarm by id and cancel its system alarm. Returns the deleted alarm.",
        schema = schema(required = listOf("id")) { put("id", idProp()) },
        annotations = ToolAnnotations(destructiveHint = true),
    ) { args ->
        val deleted = repository.delete(args.requireId())
        ToolOutput.json(
            buildJsonObject {
                put("deleted", true)
                put("alarm", deleted.toJson(withNext = false))
            },
        )
    }

    private fun alarmNext() = tool(
        name = "alarm_next",
        title = "Next alarm to ring",
        description = "Get the alarm that will ring next and when (next_fire_at ISO-8601 with offset, fires_in_minutes). " +
            "Returns JSON null when no alarm is switched on.",
        schema = schema {},
        annotations = ToolAnnotations(readOnlyHint = true),
    ) { _ ->
        val next = repository.nextAlarm()
        if (next == null) {
            ToolOutput.jsonNull()
        } else {
            val now = repository.now()
            val at = next.fireAtMillis.toZoned(now)
            ToolOutput.json(
                buildJsonObject {
                    put("alarm", next.alarm.toJson())
                    put("next_fire_at", at.iso())
                    put("fires_in_minutes", Duration.between(now, at).toMinutes())
                },
            )
        }
    }

    private fun alarmSystemNext() = tool(
        name = "alarm_system_next",
        title = "Next alarm on the phone (any app)",
        description = "Get the next alarm of ANY alarm app on this phone (the system's next alarm clock, the one the status bar shows), " +
            "not only this app's: next_fire_at (ISO-8601 with offset), fires_in_minutes and owned_by_this_app " +
            "(true when this Alarm app set it, false when another app such as the Clock app did). " +
            "Returns JSON null when no alarm is set anywhere. Read-only; use alarm_next for this app's own next alarm.",
        schema = schema {},
        annotations = ToolAnnotations(readOnlyHint = true),
    ) { _ ->
        val next = systemAlarms.next()
        if (next == null) {
            ToolOutput.jsonNull()
        } else {
            val now = repository.now()
            val at = next.triggerAtMillis.toZoned(now)
            ToolOutput.json(
                buildJsonObject {
                    put("next_fire_at", at.iso())
                    put("fires_in_minutes", Duration.between(now, at).toMinutes())
                    put("owned_by_this_app", next.isOwnedBy(systemAlarms.ownPackage))
                },
            )
        }
    }

    private fun alarmDismiss() = tool(
        name = "alarm_dismiss",
        title = "Dismiss the ringing alarm",
        description = "Stop the alarm that is ringing right now (like tapping Dismiss). " +
            "id is optional; when given it must be the ringing alarm. " +
            "Returns an error if nothing is ringing. A repeating alarm stays on for its next day; a one-time alarm switches off.",
        schema = schema { put("id", idProp()) },
        annotations = ToolAnnotations(),
    ) { args ->
        val id = args.optString("id")
        val current = ring.ringing.value ?: throw AlarmException("No alarm is ringing right now")
        if (id != null && id != current.id) {
            throw AlarmException("Alarm '$id' is not ringing; the ringing alarm is '${current.id}'")
        }
        val dismissed = ring.dismiss() ?: throw AlarmException("No alarm is ringing right now")
        ToolOutput.json(
            buildJsonObject {
                put("dismissed", true)
                put("alarm", (repository.get(dismissed.id) ?: dismissed).toJson(ringing = false))
            },
        )
    }

    private fun alarmSnooze() = tool(
        name = "alarm_snooze",
        title = "Snooze the ringing alarm",
        description = "Silence the alarm that is ringing right now and make it ring again after its snooze_minutes. " +
            "Returns an error if nothing is ringing.",
        schema = schema { put("id", idProp()) },
        annotations = ToolAnnotations(),
    ) { args ->
        val id = args.optString("id")
        val current = ring.ringing.value ?: throw AlarmException("No alarm is ringing right now")
        if (id != null && id != current.id) {
            throw AlarmException("Alarm '$id' is not ringing; the ringing alarm is '${current.id}'")
        }
        val snoozed = ring.snooze() ?: throw AlarmException("No alarm is ringing right now")
        ToolOutput.json(
            buildJsonObject {
                put("snoozed", true)
                put("alarm", (repository.get(snoozed.id) ?: snoozed).toJson(ringing = false))
            },
        )
    }

    // ---- 序列化 ----

    private fun Alarm.toJson(withNext: Boolean = true, ringing: Boolean = ring.ringing.value?.id == id): JsonObject {
        val now = repository.now()
        val next = if (withNext) repository.nextFireOf(this) else null
        return buildJsonObject {
            put("id", id)
            put("time", "%02d:%02d".format(hour, minute))
            put("label", label)
            put("days", buildJsonArray { days.sortedBy { it.value }.forEach { add(JsonPrimitive(it.code())) } })
            put("repeat", repeatKind(days))
            put("enabled", enabled)
            put("vibrate", vibrate)
            put("snooze_minutes", snoozeMinutes)
            put("snoozed_until", snoozedUntil?.takeIf { it > now.toInstant().toEpochMilli() }?.toZoned(now)?.iso()?.let(::JsonPrimitive) ?: JsonNull)
            put("next_fire_at", next?.iso()?.let(::JsonPrimitive) ?: JsonNull)
            put("ringing", ringing)
        }
    }

    // ---- 工具构造与错误转换 ----

    private fun tool(
        name: String,
        title: String,
        description: String,
        schema: JsonObject,
        annotations: ToolAnnotations,
        body: suspend (JsonObject) -> ToolOutput,
    ) = ToolDef(
        name = name,
        description = description,
        inputSchema = schema,
        annotations = annotations,
        title = title,
        handler = { args ->
            try {
                body(args)
            } catch (e: AlarmException) {
                ToolOutput.error(e.message ?: "Invalid request")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolOutput.error("Unexpected error: ${e.message ?: e.javaClass.simpleName}")
            }
        },
    )

    companion object {
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        private val DAY_CODES = mapOf(
            DayOfWeek.MONDAY to "mon", DayOfWeek.TUESDAY to "tue", DayOfWeek.WEDNESDAY to "wed",
            DayOfWeek.THURSDAY to "thu", DayOfWeek.FRIDAY to "fri", DayOfWeek.SATURDAY to "sat",
            DayOfWeek.SUNDAY to "sun",
        )
        private val CODE_TO_DAY = DAY_CODES.entries.associate { it.value to it.key }
        private val TIME_REGEX = Regex("^(\\d{1,2}):(\\d{2})$")

        fun DayOfWeek.code(): String = DAY_CODES.getValue(this)

        fun repeatKind(days: Set<DayOfWeek>): String = when (days) {
            emptySet<DayOfWeek>() -> "once"
            EVERY_DAY -> "daily"
            WEEKDAYS -> "weekdays"
            WEEKEND -> "weekends"
            else -> "custom"
        }

        private fun ZonedDateTime.iso(): String = truncatedTo(ChronoUnit.SECONDS).format(ISO)

        private fun Long.toZoned(like: ZonedDateTime): ZonedDateTime =
            ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(this), like.zone)

        // ---- 参数解析（失败抛 AlarmException，由 tool() 转成 isError） ----

        private fun JsonObject.opt(name: String): JsonElement? = this[name]?.takeUnless { it is JsonNull }

        private fun JsonObject.optString(name: String): String? {
            val element = opt(name) ?: return null
            val p = element as? JsonPrimitive
            if (p == null || !p.isString) throw AlarmException("Parameter '$name' must be a string")
            return p.content
        }

        private fun JsonObject.optBoolean(name: String): Boolean? {
            val element = opt(name) ?: return null
            val p = element as? JsonPrimitive
            return p?.takeIf { !it.isString }?.booleanOrNull
                ?: throw AlarmException("Parameter '$name' must be a boolean (true or false)")
        }

        private fun JsonObject.optInt(name: String): Int? {
            val element = opt(name) ?: return null
            val p = element as? JsonPrimitive
            val d = p?.takeIf { !it.isString }?.content?.toDoubleOrNull()
            if (d == null || d != Math.floor(d) || d.isInfinite()) throw AlarmException("Parameter '$name' must be an integer")
            return d.toInt()
        }

        private fun JsonObject.requireId(): String {
            val p = opt("id") as? JsonPrimitive
                ?: throw AlarmException("Missing required parameter 'id' (alarm id string)")
            val id = p.content.trim()
            if (id.isEmpty()) throw AlarmException("Missing required parameter 'id' (alarm id string)")
            return id
        }

        private fun parseTime(text: String): Pair<Int, Int> {
            val m = TIME_REGEX.matchEntire(text.trim())
                ?: throw AlarmException("Invalid time '$text': use 24-hour \"HH:mm\", e.g. \"07:30\"")
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            if (hour !in 0..23 || minute !in 0..59) {
                throw AlarmException("Invalid time '$text': hour must be 00-23 and minute 00-59")
            }
            return hour to minute
        }

        private fun JsonObject.requireTime(): Pair<Int, Int> =
            parseTime(optString("time") ?: throw AlarmException("Missing required parameter 'time' (\"HH:mm\")"))

        private fun JsonObject.optTime(): Pair<Int, Int>? = optString("time")?.let(::parseTime)

        private fun JsonObject.optDays(): Set<DayOfWeek>? {
            val element = opt("days") ?: return null
            val array = element as? JsonArray ?: throw AlarmException("Parameter 'days' must be an array like [\"mon\",\"wed\"]")
            return array.map { item ->
                val p = item as? JsonPrimitive
                if (p == null || !p.isString) throw AlarmException("Parameter 'days' must contain strings: mon, tue, wed, thu, fri, sat, sun")
                val code = p.content.trim().lowercase().take(3)
                CODE_TO_DAY[code]
                    ?: throw AlarmException("Invalid day '${p.content}': use mon, tue, wed, thu, fri, sat, sun")
            }.toSet()
        }

        // ---- JSON Schema 片段 ----

        private fun schema(required: List<String> = emptyList(), props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject(props))
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                put("additionalProperties", false)
            }

        private fun idProp() = buildJsonObject {
            put("type", "string")
            put("description", "Alarm id, as returned by alarm_list / alarm_create.")
        }

        private fun timeProp() = buildJsonObject {
            put("type", "string")
            put("description", "Local time of day, 24-hour \"HH:mm\" (e.g. \"06:45\", \"18:00\").")
            put("pattern", "^([01]?\\d|2[0-3]):[0-5]\\d$")
        }

        private fun stringProp(description: String) = buildJsonObject {
            put("type", "string")
            put("description", description)
        }

        private fun boolProp(description: String) = buildJsonObject {
            put("type", "boolean")
            put("description", description)
        }

        private fun snoozeProp() = buildJsonObject {
            put("type", "integer")
            put("description", "Minutes until the alarm rings again after Snooze (1-${Alarm.MAX_SNOOZE_MINUTES}, default ${Alarm.DEFAULT_SNOOZE_MINUTES}).")
            put("minimum", 1)
            put("maximum", Alarm.MAX_SNOOZE_MINUTES)
        }

        private fun daysProp() = buildJsonObject {
            put("type", "array")
            put("description", "Days of the week the alarm repeats on. Empty = ring once. Weekdays = [\"mon\",\"tue\",\"wed\",\"thu\",\"fri\"].")
            put(
                "items",
                buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { DAY_CODES.values.forEach { add(JsonPrimitive(it)) } })
                },
            )
        }
    }
}
