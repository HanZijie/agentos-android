package org.agentos.sample.calendar.debug

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.agentos.sample.calendar.data.EventSeries
import org.agentos.sample.calendar.data.Recurrence
import org.agentos.sample.calendar.ms
import org.agentos.sample.calendar.newRepo
import org.agentos.sample.calendar.tools.CalendarTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugDumpTest {
    private val repo = newRepo()
    private val tools = CalendarTools(repo, Dispatchers.Unconfined)

    private fun add(title: String, start: String, desc: String = "", cal: String = repo.defaultCalendar.id, rec: Recurrence = Recurrence.NONE, reminders: List<Int> = emptyList()) =
        repo.saveEvent(
            EventSeries(
                id = "", calendarId = cal, title = title, description = desc,
                startUtc = ms(start, "Asia/Shanghai"), endUtc = ms(start, "Asia/Shanghai") + 3_600_000, zoneId = "Asia/Shanghai",
                recurrence = rec, reminders = reminders,
            ),
        )

    @Test fun emptyStateDumpsEmptyListsAndNoScheduledReminders() {
        val d = DebugDump.build(repo, tools, null, 0, 50)
        assertEquals(0, d["events"]!!.jsonArray.size)
        assertEquals(0, d["reminders_scheduled"]!!.jsonArray.size)
        assertEquals(0, d["total"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, d["next_offset"])
        assertEquals("the default calendar always exists", 1, d["calendars"]!!.jsonArray.size)
    }

    @Test fun rowsUseTheSameFieldsAsTheMcpToolsPlusHiddenAndTimestamps() {
        val work = repo.createCalendar("Work")
        val e = add("Standup", "2026-10-08T09:00", cal = work.id, rec = Recurrence.DAILY, reminders = listOf(10))
        repo.updateCalendar(work.id, visible = false)
        val d = DebugDump.build(repo, tools, null, 0, 50)
        val row = d["events"]!!.jsonArray.single().jsonObject
        assertEquals(e.id, row["series_id"]!!.jsonPrimitive.content)
        assertEquals("daily", row["recurrence"]!!.jsonPrimitive.content)
        assertEquals(10, row["reminder_minutes"]!!.jsonArray.single().jsonPrimitive.int)
        assertEquals("2026-10-08T09:00:00+08:00", row["start"]!!.jsonPrimitive.content)
        assertEquals("true", row["hidden"]!!.jsonPrimitive.content)
        assertTrue(row.containsKey("created_at") && row.containsKey("updated_at"))
        val viaTool = Json.parseToJsonElement(tools.eventJson(org.agentos.sample.calendar.data.Occurrences.first(e, repo.zone)).toString()).jsonObject
        for ((k, v) in viaTool) assertEquals("field $k must match the MCP serializer", v, row[k])
        val cal = d["calendars"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == work.id }
        assertEquals("false", cal["visible"]!!.jsonPrimitive.content)
    }

    @Test fun pagingCoversEverythingOnceAndReportsNextOffset() {
        for (i in 0 until 25) add("E$i", "2026-10-${(i % 20) + 1}T10:00".replace("-1T", "-01T").replace(Regex("-(\\d)T"), "-0$1T"))
        val titles = ArrayList<String>()
        var offset = 0
        var pages = 0
        while (true) {
            val d = DebugDump.build(repo, tools, null, offset, 10)
            assertEquals(25, d["total"]!!.jsonPrimitive.int)
            titles += d["events"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }
            pages++
            val next = d["next_offset"]!!
            if (next is JsonNull) break
            assertEquals(offset + d["count"]!!.jsonPrimitive.int, next.jsonPrimitive.int)
            offset = next.jsonPrimitive.int
        }
        assertEquals(3, pages)
        assertEquals(25, titles.toSet().size)
    }

    @Test fun oversizedPagesAreCutOnAnElementBoundaryAndStillAdvance() {
        val big = "x".repeat(3900)
        for (i in 0 until 120) add("Big$i", "2026-10-08T10:00", desc = big)
        val d = DebugDump.build(repo, tools, null, 0, 200)
        val text = d.toString()
        assertTrue("page has ${text.length} chars", text.length < DebugDump.MAX_EVENTS_CHARS + 20_000)
        val count = d["count"]!!.jsonPrimitive.int
        assertTrue(count in 1..119)
        assertEquals(count, d["next_offset"]!!.jsonPrimitive.int)
        assertEquals("output is valid JSON", d, Json.parseToJsonElement(text))
    }

    @Test fun scheduledReminderCarriesRegisteredFlagAndFireTime() {
        val e = add("Dentist", "2026-10-08T15:00", reminders = listOf(30))
        val fire = ms("2026-10-08T14:30", "Asia/Shanghai")
        val registered = DebugDump.build(repo, tools, ArmedReminder(fire, e.id, 30, true), 0, 50)["reminders_scheduled"]!!.jsonArray.single().jsonObject
        assertEquals(e.id, registered["id"]!!.jsonPrimitive.content)
        assertEquals("Dentist", registered["title"]!!.jsonPrimitive.content)
        assertEquals("2026-10-08T14:30:00+08:00", registered["fire_at"]!!.jsonPrimitive.content)
        assertEquals("true", registered["registered"]!!.jsonPrimitive.content)
        val lost = DebugDump.build(repo, tools, ArmedReminder(fire, e.id, 30, false), 0, 50)["reminders_scheduled"]!!.jsonArray.single().jsonObject
        assertFalse(lost["registered"]!!.jsonPrimitive.content.toBoolean())
    }
}
