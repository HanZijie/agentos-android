package org.agentos.sample.calendar.debug

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.sample.calendar.data.CalendarRepository
import org.agentos.sample.calendar.data.Occurrences
import org.agentos.sample.calendar.tools.CalendarTools
import org.agentos.sample.calendar.tools.IsoTime

/** The one reminder alarm that was handed to AlarmManager (the app keeps exactly one armed at a time). */
data class ArmedReminder(val fireAtMs: Long, val occurrenceId: String, val minutesBefore: Int, val registered: Boolean)

/**
 * Read-only state dump for adb verification (debug builds only). Pure function: no Android classes, so it is unit tested.
 * Events and calendars use the same serializers as the MCP tools, so field names and formats match what AgentOS sees.
 *
 * `events` lists only the series this app created: every event of the local (app-owned) calendars plus the events in the system
 * calendar database that carry this app's `CUSTOM_APP_PACKAGE` tag. Other apps' and synced events are never listed (they may be real
 * data on a real phone); `other_events` only counts them. `calendars` lists every calendar (names and account labels, no events).
 */
object DebugDump {
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 200

    /** Broadcast result data must stay far below the ~1 MB Binder limit (a Java string costs ~2 bytes per char). */
    const val MAX_EVENTS_CHARS = 200_000

    fun build(repo: CalendarRepository, tools: CalendarTools, armed: ArmedReminder?, offset: Int, limit: Int): JsonObject {
        val zone = repo.zone
        val calendars = repo.calendars.value
        val hidden = calendars.filter { !it.visible }.map { it.id }.toSet()
        val series = repo.ownedEvents().sortedWith(compareBy({ it.startUtc }, { it.id }))
        val defaultWriteId = repo.defaultWriteCalendar().id
        val total = series.size
        val page = series.drop(offset).take(limit)
        val rows = page.map { s ->
            val base = tools.eventJson(Occurrences.first(s, zone))
            buildJsonObject {
                for ((k, v) in base) put(k, v)
                put("hidden", s.calendarId in hidden)
                // the system calendar database has no created / updated stamps for us
                put("created_at", if (s.createdAt > 0) JsonPrimitive(IsoTime.format(s.createdAt, zone)) else JsonNull)
                put("updated_at", if (s.updatedAt > 0) JsonPrimitive(IsoTime.format(s.updatedAt, zone)) else JsonNull)
            }
        }
        // Cut at an array-element boundary if the page is too big for broadcast result data.
        var kept = rows.size
        var chars = 0
        for ((i, r) in rows.withIndex()) {
            chars += r.toString().length + 1
            if (chars > MAX_EVENTS_CHARS && i > 0) {
                kept = i
                break
            }
        }
        val shown = rows.take(kept)
        val next = offset + shown.size
        return buildJsonObject {
            put("calendars", JsonArray(calendars.map { tools.calendarJson(it, defaultWriteId) }))
            put("events", JsonArray(shown))
            put(
                "reminders_scheduled",
                JsonArray(
                    listOfNotNull(armed).map { a ->
                        val title = repo.event(a.occurrenceId)?.title
                        buildJsonObject {
                            put("id", a.occurrenceId)
                            put("title", title)
                            put("minutes_before", a.minutesBefore)
                            put("fire_at", IsoTime.format(a.fireAtMs, zone))
                            put("registered", a.registered)
                        }
                    },
                ),
            )
            put("timezone", zone.id)
            put("system_access", repo.systemAccess.value)
            put("other_events", repo.foreignEventCount())
            put("total", total)
            put("offset", offset)
            put("limit", limit)
            put("count", shown.size)
            put("next_offset", if (next < total) JsonPrimitive(next) else JsonNull)
        }
    }
}
