package org.agentos.sample.calendar.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.agentos.sample.calendar.data.CalendarException
import org.agentos.sample.calendar.data.CalendarInfo
import org.agentos.sample.calendar.data.CalendarRepository
import org.agentos.sample.calendar.data.EventSeries
import org.agentos.sample.calendar.data.FreeSlots
import org.agentos.sample.calendar.data.Occurrence
import org.agentos.sample.calendar.data.Occurrences
import org.agentos.sample.calendar.data.Palette
import org.agentos.sample.calendar.data.Recurrence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 日历的 MCP 工具（docs/sample-apps.md 4.2）。与 SDK 无关，由 CalendarMcpService 逐个注册。 */
class CalendarTools(
    private val repo: CalendarRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    fun all(): List<ToolDef> = listOf(
        calendarList, calendarCreate, calendarUpdate, calendarDelete,
        eventList, eventGet, eventCreate, eventUpdate, eventDelete, eventSearch,
        agendaToday, freeSlots,
    )

    private val readOnly = ToolAnnotations(readOnlyHint = true, openWorldHint = false)
    private val create = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false)
    private val update = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false)
    private val delete = ToolAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false)

    private fun tool(name: String, description: String, schema: JsonObject, annotations: ToolAnnotations, block: (Args) -> JsonObject): ToolDef =
        ToolDef(name, description, schema, annotations) { raw -> run(raw, block) }

    private suspend fun run(raw: JsonObject, block: (Args) -> JsonObject): ToolOutput = withContext(io) {
        try {
            ToolOutput.json(block(Args(raw)))
        } catch (e: ToolError) {
            ToolOutput.error(e.message ?: "Invalid arguments")
        } catch (e: CalendarException) {
            ToolOutput.error(e.message ?: "Operation failed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutput.error("Internal error: ${e.javaClass.simpleName}")
        }
    }

    private val zone: ZoneId get() = repo.zone

    // ---- 日历 ----

    private val calendarList = tool(
        "calendar_list",
        "List all calendars: id, name, color (#RRGGBB), visible flag (whether the app UI shows it), is_default and event_count.",
        objectSchema(properties = emptyList()),
        readOnly,
    ) {
        buildJsonObject { putJsonArray("calendars") { repo.calendars.value.forEach { add(calendarJson(it)) } } }
    }

    private val calendarCreate = tool(
        "calendar_create",
        "Create a new calendar. `name` must be unique (case-insensitive). `color` is an optional '#RRGGBB' value; a palette color is picked when omitted. Returns the new calendar.",
        objectSchema(
            listOf("name"),
            listOf("name" to stringProp("Calendar name, max 60 characters."), "color" to stringProp("Color as #RRGGBB.")),
        ),
        create,
    ) { a ->
        val name = a.string("name", required = true)!!
        val color = a.string("color")?.let { parseColor(it) }
        calendarJson(repo.createCalendar(name, color))
    }

    private val calendarUpdate = tool(
        "calendar_update",
        "Rename a calendar, change its color, or show/hide it in the app UI (hidden calendars stay readable through these tools). Only the given fields change. Returns the calendar.",
        objectSchema(
            listOf("id"),
            listOf(
                "id" to stringProp("Calendar id from calendar_list."),
                "name" to stringProp("New unique name."),
                "color" to stringProp("New color as #RRGGBB."),
                "visible" to boolProp("Whether the app UI shows this calendar."),
            ),
        ),
        update,
    ) { a ->
        val id = a.string("id", required = true)!!
        if (!a.has("name") && !a.has("color") && !a.has("visible")) throw ToolError("Nothing to update: give at least one of name, color, visible")
        calendarJson(repo.updateCalendar(id, a.string("name"), a.string("color")?.let { parseColor(it) }, a.bool("visible")))
    }

    private val calendarDelete = tool(
        "calendar_delete",
        "Delete a calendar together with ALL of its events. This cannot be undone. Returns the number of events deleted. The default calendar cannot be deleted.",
        objectSchema(listOf("id"), listOf("id" to stringProp("Calendar id from calendar_list."))),
        delete,
    ) { a ->
        val id = a.string("id", required = true)!!
        val removed = repo.deleteCalendar(id)
        buildJsonObject {
            put("deleted", true)
            put("id", id)
            put("deleted_events", removed)
        }
    }

    // ---- 日程 ----

    private val eventList = tool(
        "event_list",
        "List events overlapping a time range, ordered by start time. Recurring events are expanded: each occurrence is one item with its own `id` ('<series_id>@<key>'), the shared `series_id`, and that occurrence's start/end. " +
            "All times are ISO-8601 with UTC offset; input without an offset is read in the device time zone (see `timezone` in the result). " +
            "`from` defaults to the start of today and `to` to from + 30 days; a date-only `to` includes that whole day. " +
            "All-day events start at 00:00 of their first day and end at 23:59:59 of their last day. Events of hidden calendars are included. " +
            "`query` keeps only events whose title, location or description contains it (case-insensitive).",
        objectSchema(
            properties = listOf(
                "from" to stringProp("Range start, ISO-8601 (e.g. 2026-10-08T00:00:00+08:00) or a date."),
                "to" to stringProp("Range end (exclusive), ISO-8601 or a date (inclusive of that day)."),
                "calendar_id" to stringProp("Only events of this calendar."),
                "query" to stringProp("Substring filter."),
                "limit" to intProp("Max items to return, default 50, max 200.", 1, 200),
            ),
        ),
        readOnly,
    ) { a ->
        val from = a.string("from")?.let { boundary(it, "from", isEnd = false) } ?: startOfToday()
        val to = a.string("to")?.let { boundary(it, "to", isEnd = true) } ?: Instant.ofEpochMilli(from).atZone(zone).plusDays(30).toInstant().toEpochMilli()
        if (to <= from) throw ToolError("'to' must be after 'from'")
        val calendarId = a.string("calendar_id")?.also { requireCalendar(it) }
        val limit = limit(a)
        val all = repo.occurrences(from, to, calendarId = calendarId, query = a.string("query"))
        eventsResult(all, limit) {
            put("from", IsoTime.format(from, zone))
            put("to", IsoTime.format(to, zone))
        }
    }

    private val eventGet = tool(
        "event_get",
        "Get one event by id. Accepts a series id (returns the first occurrence) or an occurrence id '<series_id>@<key>' from event_list.",
        objectSchema(listOf("id"), listOf("id" to stringProp("Event id or occurrence id."))),
        readOnly,
    ) { a ->
        val id = a.string("id", required = true)!!
        eventJson(repo.occurrence(id) ?: throw ToolError("Event not found: $id"))
    }

    private val eventCreate = tool(
        "event_create",
        "Create a calendar event and return it. Times are ISO-8601 with UTC offset (e.g. 2026-10-08T15:00:00+08:00); a time without an offset is read in the device time zone. " +
            "`end` defaults to start + 1 hour. All-day events: set all_day=true (or pass a date-only start); only the date part counts, `end` is the last day (inclusive; an end at exactly 00:00 of a later day is treated as exclusive); default one day. " +
            "reminder_minutes = minutes before the start to notify (all-day: minutes before 09:00 of the first day), e.g. [10, 60]. " +
            "recurrence repeats the event (weekly = same weekday, monthly = same day of month, clamped to month end) until `recurrence_until` (date or ISO-8601, inclusive; default: forever). " +
            "Check event_list for conflicts first when scheduling a meeting. Uses the default calendar unless calendar_id is given.",
        eventSchema(requireTitleStart = true),
        create,
    ) { a ->
        val zone = zone
        val title = a.string("title", required = true)!!
        val startP = IsoTime.parse(a.string("start", required = true)!!, zone, "start")
        val endP = a.string("end")?.let { IsoTime.parse(it, zone, "end") }
        val allDay = a.bool("all_day") ?: startP.dateOnly
        val calendarId = a.string("calendar_id")?.also { requireCalendar(it) } ?: repo.defaultCalendar.id
        val recurrence = a.string("recurrence")?.let { parseRecurrence(it) } ?: Recurrence.NONE
        val base = EventSeries(
            id = "", calendarId = calendarId, title = title,
            description = a.string("description") ?: "", location = a.string("location") ?: "",
            allDay = allDay, startUtc = 0, endUtc = 0, zoneId = zone.id,
            color = a.string("color")?.let { parseColor(it) },
            reminders = a.intList("reminder_minutes") ?: emptyList(),
            recurrence = recurrence,
        )
        val timed = if (allDay) {
            val sd = startP.localDate
            val ed = endP?.let { allDayEnd(it, sd) } ?: sd
            base.copy(startDay = sd.toEpochDay(), endDay = ed.toEpochDay())
        } else {
            val st = startP.instant.toEpochMilli()
            base.copy(startUtc = st, endUtc = endP?.instant?.toEpochMilli() ?: (st + 3_600_000L), zoneId = IsoTime.eventZone(startP, zone).id)
        }
        val until = a.string("recurrence_until")?.let { parseUntil(it, ZoneId.of(timed.zoneId)) }
        eventJson(repo.firstOccurrence(repo.saveEvent(timed.copy(recurrenceUntilUtc = until))))
    }

    private val eventUpdate = tool(
        "event_update",
        "Update an event; only the given fields change. For a recurring event the WHOLE series is changed (an occurrence id from event_list also targets the series). " +
            "Same field formats as event_create. Changing `start` without `end` keeps the duration. Pass recurrence_until or color as null to clear them; recurrence='none' makes the event non-recurring. Returns the updated event.",
        eventSchema(requireTitleStart = false),
        update,
    ) { a ->
        val id = a.string("id", required = true)!!
        val s = repo.event(id) ?: throw ToolError("Event not found: $id")
        val zone = zone
        val fieldNames = listOf(
            "title", "start", "end", "all_day", "location", "description", "calendar_id",
            "reminder_minutes", "recurrence", "recurrence_until", "color",
        )
        if (fieldNames.none { a.has(it) }) throw ToolError("Nothing to update: give at least one field besides id")
        val startP = a.string("start")?.let { IsoTime.parse(it, zone, "start") }
        val endP = a.string("end")?.let { IsoTime.parse(it, zone, "end") }
        val allDay = a.bool("all_day") ?: s.allDay
        val seriesZone = if (s.allDay) zone else ZoneId.of(s.zoneId)
        var next = s.copy(
            title = a.string("title") ?: s.title,
            description = a.string("description") ?: s.description,
            location = a.string("location") ?: s.location,
            calendarId = a.string("calendar_id")?.also { requireCalendar(it) } ?: s.calendarId,
            reminders = a.intList("reminder_minutes") ?: s.reminders,
            recurrence = a.string("recurrence")?.let { parseRecurrence(it) } ?: s.recurrence,
            color = if (a.isNull("color")) null else a.string("color")?.let { parseColor(it) } ?: s.color,
        )
        next = if (allDay) {
            val oldStart = if (s.allDay) s.startDate else Instant.ofEpochMilli(s.startUtc).atZone(seriesZone).toLocalDate()
            val oldEnd = if (s.allDay) s.endDate else Instant.ofEpochMilli(if (s.endUtc > s.startUtc) s.endUtc - 1 else s.startUtc).atZone(seriesZone).toLocalDate()
            val sd = startP?.localDate ?: oldStart
            val ed = endP?.let { allDayEnd(it, sd) } ?: if (startP != null) sd.plusDays(ChronoUnit.DAYS.between(oldStart, oldEnd)) else oldEnd
            next.copy(allDay = true, startDay = sd.toEpochDay(), endDay = ed.toEpochDay(), zoneId = if (s.allDay) s.zoneId else zone.id)
        } else {
            val oldStart = if (s.allDay) s.startDate.atTime(9, 0).atZone(zone).toInstant().toEpochMilli() else s.startUtc
            val oldEnd = if (s.allDay) oldStart + 3_600_000L else s.endUtc
            val st = startP?.instant?.toEpochMilli() ?: oldStart
            val en = endP?.instant?.toEpochMilli() ?: if (startP != null) st + (oldEnd - oldStart) else oldEnd
            val evZone = startP?.let { IsoTime.eventZone(it, zone).id } ?: if (s.allDay) zone.id else s.zoneId
            next.copy(allDay = false, startUtc = st, endUtc = en, zoneId = evZone, startDay = 0, endDay = 0)
        }
        val untilZone = ZoneId.of(next.zoneId)
        next = when {
            a.isNull("recurrence_until") -> next.copy(recurrenceUntilUtc = null)
            a.has("recurrence_until") -> next.copy(recurrenceUntilUtc = parseUntil(a.string("recurrence_until")!!, untilZone))
            else -> next
        }
        val saved = repo.saveEvent(next)
        eventJson(if (id.contains('@')) repo.occurrence(id) ?: repo.firstOccurrence(saved) else repo.firstOccurrence(saved))
    }

    private val eventDelete = tool(
        "event_delete",
        "Delete an event permanently. For a recurring event the WHOLE series is deleted (an occurrence id also targets the series). Returns what was deleted.",
        objectSchema(listOf("id"), listOf("id" to stringProp("Event id or occurrence id."))),
        delete,
    ) { a ->
        val id = a.string("id", required = true)!!
        val removed = repo.deleteEvent(id)
        buildJsonObject {
            put("deleted", true)
            put("id", id)
            put("series_id", removed.id)
            put("title", removed.title)
            put("was_recurring", removed.isRecurring)
        }
    }

    private val eventSearch = tool(
        "event_search",
        "Search events by case-insensitive substring in title, location or description, across all time. One result per event series: the next upcoming occurrence, or the last one if none is upcoming. Upcoming results come first (soonest first), then past ones (most recent first).",
        objectSchema(
            listOf("query"),
            listOf("query" to stringProp("Text to look for."), "limit" to intProp("Max items to return, default 50, max 200.", 1, 200)),
        ),
        readOnly,
    ) { a ->
        val q = a.string("query", required = true)!!
        val limit = limit(a)
        val found = repo.search(q, limit = Int.MAX_VALUE)
        eventsResult(found, limit) { put("query", q) }
    }

    private val agendaToday = tool(
        "agenda_today",
        "Today's events in the device time zone, ordered by start time (all-day events first). Recurring events are expanded. Includes today's date and time zone in the result.",
        objectSchema(properties = listOf("calendar_id" to stringProp("Only events of this calendar."))),
        readOnly,
    ) { a ->
        val calendarId = a.string("calendar_id")?.also { requireCalendar(it) }
        val today = Instant.ofEpochMilli(repo.time.nowMs()).atZone(zone).toLocalDate()
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val list = repo.occurrences(from, to, calendarId = calendarId)
        eventsResult(list, Int.MAX_VALUE) { put("date", today.toString()) }
    }

    private val freeSlots = tool(
        "free_slots",
        "Find free time slots on one day. `date` is YYYY-MM-DD in the device time zone; the working window is day_start..day_end (HH:mm, default 09:00..18:00; day_end may be 24:00). " +
            "Busy time comes from all timed events of all calendars, including expanded recurring events and events crossing midnight; all-day events are ignored unless include_all_day=true. " +
            "Returns the slots that are at least duration_minutes long, plus the busy intervals that were considered.",
        objectSchema(
            listOf("date", "duration_minutes"),
            listOf(
                "date" to stringProp("Day to check, YYYY-MM-DD."),
                "duration_minutes" to intProp("Minimum slot length in minutes (1-1440).", 1, 1440),
                "day_start" to stringProp("Window start, HH:mm, default 09:00."),
                "day_end" to stringProp("Window end, HH:mm, default 18:00 (24:00 allowed)."),
                "calendar_id" to stringProp("Only consider events of this calendar."),
                "include_all_day" to boolProp("Treat all-day events as busy all day. Default false."),
            ),
        ),
        readOnly,
    ) { a ->
        val zone = zone
        val date = IsoTime.parse(a.string("date", required = true)!!, zone, "date").localDate
        val minutes = a.int("duration_minutes", required = true)!!
        if (minutes < 1 || minutes > 1440) throw ToolError("duration_minutes must be between 1 and 1440")
        val ds = parseClock(a.string("day_start") ?: "09:00", "day_start")
        val de = parseClock(a.string("day_end") ?: "18:00", "day_end")
        if (de <= ds) throw ToolError("day_end must be after day_start")
        val calendarId = a.string("calendar_id")?.also { requireCalendar(it) }
        val includeAllDay = a.bool("include_all_day") ?: false
        val winStart = atClock(date, ds, zone)
        val winEnd = atClock(date, de, zone)
        if (winEnd <= winStart) throw ToolError("day_end must be after day_start (the window is empty on this day)")
        val busyOcc = repo.occurrences(winStart, winEnd, calendarId = calendarId).filter { includeAllDay || !it.allDay }
        val spans = busyOcc.map { FreeSlots.Span(it.startMs, it.endMs) }
        val slots = FreeSlots.compute(spans, winStart, winEnd, minutes * 60_000L)
        buildJsonObject {
            put("date", date.toString())
            put("timezone", zone.id)
            put("duration_minutes", minutes)
            putJsonObject("window") {
                put("start", IsoTime.format(winStart, zone))
                put("end", IsoTime.format(winEnd, zone))
            }
            putJsonArray("slots") {
                slots.forEach {
                    add(
                        buildJsonObject {
                            put("start", IsoTime.format(it.start, zone))
                            put("end", IsoTime.format(it.end, zone))
                            put("minutes", ((it.end - it.start) / 60_000L).toInt())
                        },
                    )
                }
            }
            putJsonArray("busy") {
                busyOcc.forEach {
                    add(
                        buildJsonObject {
                            put("id", it.id)
                            put("title", it.series.title)
                            put("start", IsoTime.format(it.startMs, zone))
                            put("end", IsoTime.format(it.endMs, zone))
                        },
                    )
                }
            }
        }
    }

    // ---- 辅助 ----

    private fun startOfToday(): Long = Instant.ofEpochMilli(repo.time.nowMs()).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** 某天的 HH:mm（本地钟点，夏令时空档会顺延）；1440 表示次日 00:00。 */
    private fun atClock(date: LocalDate, minutesOfDay: Int, zone: ZoneId): Long =
        date.atStartOfDay().plusMinutes(minutesOfDay.toLong()).atZone(zone).toInstant().toEpochMilli()

    private fun parseClock(text: String, param: String): Int {
        val t = text.trim()
        if (t == "24:00") return 24 * 60
        val lt = runCatching { LocalTime.parse(t) }.getOrNull() ?: throw ToolError("Invalid time for '$param': \"$text\". Use HH:mm, e.g. 09:00")
        return lt.hour * 60 + lt.minute
    }

    private fun limit(a: Args): Int {
        val l = a.int("limit") ?: 50
        if (l < 1) throw ToolError("limit must be at least 1")
        return minOf(l, 200)
    }

    private fun boundary(text: String, param: String, isEnd: Boolean): Long {
        val p = IsoTime.parse(text, zone, param)
        return if (p.dateOnly && isEnd) p.localDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() else p.instant.toEpochMilli()
    }

    /** 全天日程的结束：只取日期；恰好 00:00 且晚于开始日的 datetime 按“不含”处理。 */
    private fun allDayEnd(end: ParsedTime, startDate: LocalDate): LocalDate {
        if (end.dateOnly) return end.localDate
        val asWritten = end.instant.atZone(end.explicitZone ?: zone)
        return if (asWritten.toLocalTime() == LocalTime.MIDNIGHT && asWritten.toLocalDate().isAfter(startDate)) asWritten.toLocalDate().minusDays(1) else asWritten.toLocalDate()
    }

    private fun parseUntil(text: String, evZone: ZoneId): Long {
        val p = IsoTime.parse(text, evZone, "recurrence_until")
        return if (p.dateOnly) p.localDate.plusDays(1).atStartOfDay(evZone).toInstant().toEpochMilli() - 1 else p.instant.toEpochMilli()
    }

    private fun parseRecurrence(text: String): Recurrence =
        Recurrence.parse(text) ?: throw ToolError("recurrence must be one of: none, daily, weekly, monthly, yearly (got \"$text\")")

    private fun parseColor(text: String): Int = Palette.parse(text) ?: throw ToolError("Invalid color \"$text\": use #RRGGBB")

    private fun requireCalendar(id: String) {
        if (repo.calendar(id) == null) throw ToolError("Calendar not found: $id")
    }

    private fun calendarJson(c: CalendarInfo): JsonObject = buildJsonObject {
        put("id", c.id)
        put("name", c.name)
        put("color", Palette.format(c.color))
        put("visible", c.visible)
        put("is_default", c.isDefault)
        put("event_count", repo.eventCount(c.id))
    }

    private fun eventsResult(all: List<Occurrence>, limit: Int, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        extra()
        put("timezone", zone.id)
        val shown = all.take(limit)
        put("count", shown.size)
        put("truncated", all.size > shown.size)
        put("events", JsonArray(shown.map { eventJson(it) }))
    }

    internal fun eventJson(o: Occurrence): JsonObject {
        val s = o.series
        val cal = repo.calendar(s.calendarId)
        return buildJsonObject {
            put("id", o.id)
            put("series_id", s.id)
            put("calendar_id", s.calendarId)
            put("calendar_name", cal?.name)
            put("title", s.title)
            if (s.allDay) {
                put("start", IsoTime.formatLocal(o.firstDay.atStartOfDay(), zone))
                put("end", IsoTime.formatLocal(o.lastDay.atTime(23, 59, 59), zone))
            } else {
                put("start", IsoTime.format(o.startMs, zone))
                put("end", IsoTime.format(o.endMs, zone))
            }
            put("all_day", s.allDay)
            put("location", s.location)
            put("description", s.description)
            put("color", Palette.format(s.color ?: cal?.color ?: Palette.colors[0]))
            put("reminder_minutes", JsonArray(s.reminders.map { JsonPrimitive(it) }))
            put("recurrence", s.recurrence.wire)
            put("recurrence_until", s.recurrenceUntilUtc?.let { IsoTime.format(it, zone) })
            put("timezone", if (s.allDay) zone.id else s.zoneId)
        }
    }

    private fun eventSchema(requireTitleStart: Boolean): JsonObject = objectSchema(
        required = if (requireTitleStart) listOf("title", "start") else listOf("id"),
        properties = buildList {
            if (!requireTitleStart) add("id" to stringProp("Event id or occurrence id from event_list."))
            add("title" to stringProp("Event title."))
            add("start" to stringProp("Start, ISO-8601 with UTC offset (e.g. 2026-10-08T15:00:00+08:00), or a date for all-day events."))
            add("end" to stringProp("End, same format. Default: start + 1 hour (all-day: same day)."))
            add("all_day" to boolProp("All-day event (only dates matter)."))
            add("location" to stringProp("Place, free text."))
            add("description" to stringProp("Notes, free text."))
            add("calendar_id" to stringProp("Calendar id from calendar_list. Default: the default calendar."))
            add("reminder_minutes" to intArrayProp("Notify this many minutes before the start; up to 5 values, each 0-40320. Example: [10, 60]."))
            add("recurrence" to stringProp("Repeat rule.", listOf("none", "daily", "weekly", "monthly", "yearly")))
            add("recurrence_until" to stringProp("Last day of the repetition (inclusive), date or ISO-8601. Only with recurrence."))
            add("color" to stringProp("Event color as #RRGGBB; default follows the calendar."))
        },
    )
}
