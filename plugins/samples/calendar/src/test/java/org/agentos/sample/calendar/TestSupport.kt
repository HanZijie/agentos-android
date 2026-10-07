package org.agentos.sample.calendar

import org.agentos.sample.calendar.data.CalendarInfo
import org.agentos.sample.calendar.data.CalendarRepository
import org.agentos.sample.calendar.data.CalendarStore
import org.agentos.sample.calendar.data.EventSeries
import org.agentos.sample.calendar.data.TimeEnv
import java.time.ZoneId
import java.time.ZonedDateTime

class InMemoryCalendarStore : CalendarStore {
    val calendars = LinkedHashMap<String, CalendarInfo>()
    val events = LinkedHashMap<String, EventSeries>()
    override fun loadCalendars() = calendars.values.toList()
    override fun loadEvents() = events.values.toList()
    override fun upsertCalendar(calendar: CalendarInfo) {
        calendars[calendar.id] = calendar
    }

    override fun deleteCalendar(id: String): Int {
        val doomed = events.values.filter { it.calendarId == id }
        doomed.forEach { events.remove(it.id) }
        calendars.remove(id)
        return doomed.size
    }

    override fun upsertEvent(event: EventSeries) {
        events[event.id] = event
    }

    override fun deleteEvent(id: String) {
        events.remove(id)
    }
}

class FakeTime(var zone: ZoneId = ZoneId.of("Asia/Shanghai"), var now: Long = ms("2026-10-07T08:00:00+08:00")) : TimeEnv {
    override fun zone(): ZoneId = zone
    override fun nowMs(): Long = now
}

/** "2026-10-08T15:00:00+08:00" → UTC 毫秒。 */
fun ms(iso: String): Long = java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()

fun ms(local: String, zone: String): Long = java.time.LocalDateTime.parse(local).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

fun zdt(epochMs: Long, zone: String): ZonedDateTime = java.time.Instant.ofEpochMilli(epochMs).atZone(ZoneId.of(zone))

fun newRepo(time: FakeTime = FakeTime(), store: InMemoryCalendarStore = InMemoryCalendarStore()): CalendarRepository {
    var n = 0
    return CalendarRepository(store, time, defaultCalendarName = "Personal", newId = { "id${++n}" })
}

fun timed(
    id: String = "e",
    title: String = "Event",
    start: String,
    end: String,
    zone: String = "Asia/Shanghai",
    calendarId: String = "id1",
    recurrence: org.agentos.sample.calendar.data.Recurrence = org.agentos.sample.calendar.data.Recurrence.NONE,
    until: Long? = null,
    reminders: List<Int> = emptyList(),
) = EventSeries(
    id = id, calendarId = calendarId, title = title, startUtc = ms(start, zone), endUtc = ms(end, zone), zoneId = zone,
    recurrence = recurrence, recurrenceUntilUtc = until, reminders = reminders,
)

fun allDay(
    id: String = "a",
    title: String = "All day",
    start: String,
    end: String = start,
    zone: String = "Asia/Shanghai",
    calendarId: String = "id1",
    recurrence: org.agentos.sample.calendar.data.Recurrence = org.agentos.sample.calendar.data.Recurrence.NONE,
    until: Long? = null,
) = EventSeries(
    id = id, calendarId = calendarId, title = title, allDay = true,
    startUtc = 0, endUtc = 0, zoneId = zone,
    startDay = java.time.LocalDate.parse(start).toEpochDay(), endDay = java.time.LocalDate.parse(end).toEpochDay(),
    recurrence = recurrence, recurrenceUntilUtc = until,
)
