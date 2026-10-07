package org.agentos.sample.calendar

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.agentos.sample.calendar.data.CalendarException
import org.agentos.sample.calendar.data.FreeSlots
import org.agentos.sample.calendar.data.Palette
import org.agentos.sample.calendar.data.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CalendarRepositoryTest {
    private fun expectError(contains: String, block: () -> Unit) {
        try {
            block()
            fail("expected CalendarException containing '$contains'")
        } catch (e: CalendarException) {
            assertTrue("message was: ${e.message}", e.message!!.contains(contains, ignoreCase = true))
        }
    }

    @Test fun firstRunCreatesTheDefaultCalendarOnlyOnce() {
        val store = InMemoryCalendarStore()
        val repo = newRepo(store = store)
        assertEquals(1, repo.calendars.value.size)
        assertTrue(repo.defaultCalendar.isDefault)
        assertEquals("Personal", repo.defaultCalendar.name)
        val again = newRepo(store = store)
        assertEquals(1, again.calendars.value.size)
        assertEquals(repo.defaultCalendar.id, again.defaultCalendar.id)
    }

    @Test fun calendarCrud() {
        val repo = newRepo()
        val work = repo.createCalendar("  Work ", Palette.colors[4])
        assertEquals("Work", work.name)
        assertEquals(Palette.colors[4], work.color)
        assertTrue(work.visible)
        assertFalse(work.isDefault)
        val renamed = repo.updateCalendar(work.id, name = "Office", color = Palette.colors[1], visible = false)
        assertEquals("Office", renamed.name)
        assertFalse(renamed.visible)
        assertEquals(renamed, repo.calendar(work.id))
        expectError("already exists") { repo.createCalendar("office") }
        expectError("must not be empty") { repo.createCalendar("   ") }
        expectError("too long") { repo.createCalendar("x".repeat(61)) }
        expectError("not found") { repo.updateCalendar("nope", name = "x") }
        // 改名成自己（大小写不同）是允许的
        assertEquals("OFFICE", repo.updateCalendar(work.id, name = "OFFICE").name)
    }

    @Test fun deletingACalendarRemovesItsEventsAndReportsTheCount() {
        val repo = newRepo()
        val work = repo.createCalendar("Work")
        repo.saveEvent(timed(id = "", title = "A", start = "2026-10-08T10:00", end = "2026-10-08T11:00", calendarId = work.id))
        repo.saveEvent(timed(id = "", title = "B", start = "2026-10-09T10:00", end = "2026-10-09T11:00", calendarId = work.id))
        repo.saveEvent(timed(id = "", title = "C", start = "2026-10-09T10:00", end = "2026-10-09T11:00", calendarId = repo.defaultCalendar.id))
        assertEquals(2, repo.deleteCalendar(work.id))
        assertEquals(listOf("C"), repo.events.value.map { it.title })
        assertNull(repo.calendar(work.id))
        expectError("default calendar cannot be deleted") { repo.deleteCalendar(repo.defaultCalendar.id) }
        expectError("not found") { repo.deleteCalendar("nope") }
    }

    @Test fun saveEventAssignsIdsAndTimestampsAndPublishesState() = runBlocking {
        val time = FakeTime(now = 1_000)
        val repo = newRepo(time)
        val created = repo.saveEvent(timed(id = "", title = "  Dentist ", start = "2026-10-08T10:00", end = "2026-10-08T11:00", reminders = listOf(30, 10, 10)))
        assertTrue(created.id.isNotEmpty())
        assertEquals("Dentist", created.title)
        assertEquals(listOf(10, 30), created.reminders)
        assertEquals(1_000, created.createdAt)
        assertEquals(created, repo.events.first().single())
        time.now = 5_000
        val edited = repo.saveEvent(created.copy(title = "Dentist!"))
        assertEquals(created.id, edited.id)
        assertEquals(1_000, edited.createdAt)
        assertEquals(5_000, edited.updatedAt)
        assertEquals(1, repo.events.value.size)
        assertEquals("Dentist!", repo.event(created.id)!!.title)
    }

    @Test fun saveEventValidatesInput() {
        val repo = newRepo()
        val ok = timed(id = "", start = "2026-10-08T10:00", end = "2026-10-08T11:00")
        expectError("title") { repo.saveEvent(ok.copy(title = "   ")) }
        expectError("title is too long") { repo.saveEvent(ok.copy(title = "x".repeat(201))) }
        expectError("end must not be before start") { repo.saveEvent(ok.copy(endUtc = ok.startUtc - 1)) }
        expectError("calendar not found") { repo.saveEvent(ok.copy(calendarId = "nope")) }
        expectError("time zone") { repo.saveEvent(ok.copy(zoneId = "Mars/Base")) }
        expectError("at most 5 reminders") { repo.saveEvent(ok.copy(reminders = listOf(1, 2, 3, 4, 5, 6))) }
        expectError("reminder_minutes") { repo.saveEvent(ok.copy(reminders = listOf(-1))) }
        expectError("reminder_minutes") { repo.saveEvent(ok.copy(reminders = listOf(40_321))) }
        expectError("recurrence_until") { repo.saveEvent(ok.copy(recurrence = Recurrence.DAILY, recurrenceUntilUtc = ok.startUtc - 1)) }
        val day = allDay(id = "", start = "2026-10-08")
        expectError("end date") { repo.saveEvent(day.copy(endDay = day.startDay - 1)) }
        expectError("recurrence_until") { repo.saveEvent(day.copy(recurrence = Recurrence.WEEKLY, recurrenceUntilUtc = ms("2026-10-01T00:00:00+08:00"))) }
        assertTrue(repo.events.value.isEmpty())
    }

    @Test fun untilIsDroppedWhenNotRecurring() {
        val repo = newRepo()
        val e = repo.saveEvent(timed(id = "", start = "2026-10-08T10:00", end = "2026-10-08T11:00", until = ms("2026-12-01T00:00:00+08:00")))
        assertNull(e.recurrenceUntilUtc)
    }

    @Test fun allDayEventsStoreDayBoundsInTheirZone() {
        val repo = newRepo()
        val e = repo.saveEvent(allDay(id = "", start = "2026-10-08", end = "2026-10-09"))
        assertEquals(ms("2026-10-08T00:00:00+08:00"), e.startUtc)
        assertEquals(ms("2026-10-10T00:00:00+08:00"), e.endUtc)
    }

    @Test fun deleteAndRestoreEvent() {
        val repo = newRepo()
        val e = repo.saveEvent(timed(id = "", title = "Gone soon", start = "2026-10-08T10:00", end = "2026-10-08T11:00", recurrence = Recurrence.DAILY))
        val removed = repo.deleteEvent("${e.id}@20261009T020000Z") // 用某一次出现的 id 删除 = 删整个系列
        assertEquals(e, removed)
        assertTrue(repo.events.value.isEmpty())
        expectError("not found") { repo.deleteEvent(e.id) }
        repo.restoreEvent(removed)
        assertEquals(e, repo.event(e.id))
        repo.deleteCalendar(repo.createCalendar("Tmp").id)
        expectError("calendar not found") { repo.restoreEvent(removed.copy(calendarId = "gone")) }
    }

    @Test fun occurrencesFilterByCalendarVisibilityAndText() {
        val repo = newRepo()
        val work = repo.createCalendar("Work")
        repo.saveEvent(timed(id = "", title = "Standup", start = "2026-10-08T09:00", end = "2026-10-08T09:15", calendarId = work.id))
        repo.saveEvent(timed(id = "", title = "Gym", start = "2026-10-08T18:00", end = "2026-10-08T19:00"))
        val from = ms("2026-10-08T00:00:00+08:00")
        val to = ms("2026-10-09T00:00:00+08:00")
        assertEquals(listOf("Standup", "Gym"), repo.occurrences(from, to).map { it.series.title })
        assertEquals(listOf("Standup"), repo.occurrences(from, to, calendarId = work.id).map { it.series.title })
        repo.updateCalendar(work.id, visible = false)
        assertEquals(listOf("Gym"), repo.occurrences(from, to, onlyVisible = true).map { it.series.title })
        assertEquals(2, repo.occurrences(from, to, onlyVisible = false).size)
        assertEquals(listOf("Gym"), repo.occurrences(from, to, query = "gy").map { it.series.title })
    }

    @Test fun searchReturnsOneHitPerSeriesUpcomingFirst() {
        val time = FakeTime(now = ms("2026-10-07T08:00:00+08:00"))
        val repo = newRepo(time)
        repo.saveEvent(timed(id = "", title = "Weekly sync", start = "2026-09-01T10:00", end = "2026-09-01T11:00", recurrence = Recurrence.WEEKLY)) // Tuesday
        repo.saveEvent(timed(id = "", title = "Sync with Wang", start = "2026-09-20T10:00", end = "2026-09-20T11:00"))
        repo.saveEvent(timed(id = "", title = "Offsite", start = "2026-10-20T10:00", end = "2026-10-20T11:00", reminders = emptyList()).copy(description = "agenda: SYNC topics"))
        repo.saveEvent(timed(id = "", title = "Lunch", start = "2026-10-08T12:00", end = "2026-10-08T13:00"))
        val hits = repo.search("sync")
        assertEquals(listOf("Weekly sync", "Offsite", "Sync with Wang"), hits.map { it.series.title })
        assertEquals("2026-10-13", hits[0].firstDay.toString()) // 下一个周二
        assertTrue(repo.search("   ").isEmpty())
        assertEquals(1, repo.search("sync", limit = 1).size)
    }

    @Test fun occurrenceLookupByIdAndKey() {
        val repo = newRepo()
        val e = repo.saveEvent(timed(id = "", start = "2026-10-08T10:00", end = "2026-10-08T11:00", recurrence = Recurrence.DAILY))
        assertEquals(e.startUtc, repo.occurrence(e.id)!!.startMs)
        val second = repo.occurrence("${e.id}@20261009T020000Z")
        assertNotNull(second)
        assertEquals(e.startUtc + 86_400_000, second!!.startMs)
        assertNull(repo.occurrence("${e.id}@20261009T030000Z"))
        assertNull(repo.occurrence("nope"))
    }
}

class FreeSlotsTest {
    private fun s(a: Long, b: Long) = FreeSlots.Span(a, b)
    private val min = 60_000L

    @Test fun noBusyMeansWholeWindow() {
        assertEquals(listOf(s(0, 600 * min)), FreeSlots.compute(emptyList(), 0, 600 * min, 30 * min))
    }

    @Test fun gapsShorterThanTheDurationAreDropped() {
        val busy = listOf(s(60 * min, 120 * min), s(150 * min, 300 * min))
        assertEquals(listOf(s(0, 60 * min), s(120 * min, 150 * min), s(300 * min, 600 * min)), FreeSlots.compute(busy, 0, 600 * min, 30 * min))
        assertEquals(listOf(s(0, 60 * min), s(300 * min, 600 * min)), FreeSlots.compute(busy, 0, 600 * min, 31 * min))
    }

    @Test fun overlappingAndAdjacentBusyIntervalsMergeAndAreClipped() {
        val busy = listOf(s(-100 * min, 30 * min), s(100 * min, 200 * min), s(150 * min, 250 * min), s(250 * min, 300 * min), s(590 * min, 900 * min))
        assertEquals(listOf(s(0, 30 * min), s(100 * min, 300 * min), s(590 * min, 600 * min)), FreeSlots.mergeBusy(busy, 0, 600 * min))
        assertEquals(listOf(s(30 * min, 100 * min), s(300 * min, 590 * min)), FreeSlots.compute(busy, 0, 600 * min, 10 * min))
    }

    @Test fun fullyBusyOrEmptyWindow() {
        assertTrue(FreeSlots.compute(listOf(s(-10, 1000)), 0, 600, 1).isEmpty())
        assertTrue(FreeSlots.compute(emptyList(), 100, 100, 1).isEmpty())
        assertTrue(FreeSlots.compute(emptyList(), 0, 10 * min, 11 * min).isEmpty())
    }
}
