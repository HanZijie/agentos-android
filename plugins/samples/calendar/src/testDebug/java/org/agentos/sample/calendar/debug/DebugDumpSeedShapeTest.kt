package org.agentos.sample.calendar.debug

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.agentos.sample.calendar.data.EventSeries
import org.agentos.sample.calendar.data.Recurrence
import org.agentos.sample.calendar.newRepo
import org.agentos.sample.calendar.tools.CalendarTools
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Same shape of data as DebugReceiver.seed (daily / weekly / monthly / yearly, all-day, cross-midnight), paged to the end. */
class DebugDumpSeedShapeTest {
    private val repo = newRepo()
    private val tools = CalendarTools(repo, Dispatchers.Unconfined)
    private val zone: ZoneId = repo.zone
    private val today: LocalDate = LocalDate.of(2026, 10, 7)

    private fun at(day: Int, hm: String) = today.plusDays(day.toLong()).atTime(LocalTime.parse(hm)).atZone(zone).toInstant().toEpochMilli()

    private fun timed(title: String, day: Int, from: String, to: String, rec: Recurrence = Recurrence.NONE, endDay: Int = day, rem: List<Int> = listOf(10)) =
        repo.saveEvent(EventSeries(id = "", calendarId = repo.defaultCalendar.id, title = title, startUtc = at(day, from), endUtc = at(endDay, to), zoneId = zone.id, recurrence = rec, reminders = rem))

    private fun allDay(title: String, day: Int, span: Int, rec: Recurrence = Recurrence.NONE) =
        repo.saveEvent(
            EventSeries(
                id = "", calendarId = repo.defaultCalendar.id, title = title, allDay = true, startUtc = 0, endUtc = 0, zoneId = zone.id,
                startDay = today.plusDays(day.toLong()).toEpochDay(), endDay = today.plusDays((day + span).toLong()).toEpochDay(), recurrence = rec, reminders = listOf(0),
            ),
        )

    @Test(timeout = 10_000) fun everyPageOfSeedShapedDataTerminates() {
        timed("morning run", 0, "07:00", "07:45", Recurrence.DAILY, rem = listOf(15))
        timed("review", 0, "10:00", "11:30", rem = listOf(10, 60))
        timed("lunch", 0, "12:30", "13:30")
        timed("weekly report", 0, "16:00", "17:00")
        timed("call parents", 0, "20:30", "21:00", Recurrence.WEEKLY)
        timed("standup", 1, "10:00", "10:15", Recurrence.DAILY, rem = listOf(5))
        timed("dentist", 1, "15:00", "16:00", rem = listOf(30, 1440))
        timed("client call", 1, "11:00", "12:00")
        timed("design walkthrough", 1, "11:30", "12:30")
        timed("yoga", 2, "19:00", "20:15", Recurrence.WEEKLY)
        allDay("trip", 2, 2)
        allDay("birthday", 3, 0, Recurrence.YEARLY)
        timed("movie", 3, "19:30", "22:00")
        timed("dinner", 5, "18:00", "20:00", rem = listOf(60))
        timed("quarterly review", 6, "14:00", "16:00")
        allDay("rent due", 9, 0, Recurrence.MONTHLY)
        timed("retro", -2, "14:00", "15:00")
        timed("release window", 4, "22:00", "02:00", endDay = 5)

        var offset = 0
        var seen = 0
        while (true) {
            val d = DebugDump.build(repo, tools, null, offset, 5)
            seen += d["count"]!!.jsonPrimitive.int
            val next = d["next_offset"]!!
            if (next is JsonNull) break
            offset = next.jsonPrimitive.int
        }
        assertEquals(18, seen)
    }
}
