package org.agentos.sample.calendar

import org.agentos.sample.calendar.data.Recurrence
import org.agentos.sample.calendar.reminder.ReminderPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ReminderPlannerTest {
    private val sh = ZoneId.of("Asia/Shanghai")

    private fun fires(list: List<org.agentos.sample.calendar.reminder.Reminder>) = list.map { zdt(it.fireAtMs, "Asia/Shanghai").toLocalDateTime().toString() }

    @Test fun oneShotReminderFiresBeforeStart() {
        val e = timed(start = "2026-10-07T08:03", end = "2026-10-07T09:00", reminders = listOf(1))
        val now = ms("2026-10-07T08:01:00+08:00")
        val next = ReminderPlanner.next(listOf(e), now, sh)!!
        assertEquals(ms("2026-10-07T08:02:00+08:00"), next.fireAtMs)
        assertEquals(1, next.minutesBefore)
        assertEquals(e.startUtc, next.startMs)
    }

    @Test fun pastRemindersAreNotReplayedAndBoundariesAreOpenClosed() {
        val e = timed(start = "2026-10-07T10:00", end = "2026-10-07T11:00", reminders = listOf(10, 60))
        val at = { t: String -> ms("2026-10-07T$t:00+08:00") }
        assertEquals(listOf("2026-10-07T09:00", "2026-10-07T09:50"), fires(ReminderPlanner.between(listOf(e), at("08:00"), at("12:00"), sh)))
        assertEquals("fire time == after is excluded", listOf("2026-10-07T09:50"), fires(ReminderPlanner.between(listOf(e), at("09:00"), at("12:00"), sh)))
        assertEquals("fire time == until is included", listOf("2026-10-07T09:00"), fires(ReminderPlanner.between(listOf(e), at("08:00"), at("09:00"), sh)))
        assertTrue(ReminderPlanner.between(listOf(e), at("09:51"), at("12:00"), sh).isEmpty())
        assertNull("already past", ReminderPlanner.next(listOf(e), at("09:51"), sh))
    }

    @Test fun eventsWithoutRemindersProduceNothing() {
        assertNull(ReminderPlanner.next(listOf(timed(start = "2026-10-08T10:00", end = "2026-10-08T11:00")), 0, sh))
    }

    @Test fun recurringEventRemindsForEachOccurrenceInOrder() {
        val e = timed(start = "2026-10-01T09:00", end = "2026-10-01T09:30", recurrence = Recurrence.DAILY, reminders = listOf(15))
        val list = ReminderPlanner.between(listOf(e), ms("2026-10-07T08:00:00+08:00"), ms("2026-10-10T00:00:00+08:00"), sh)
        assertEquals(listOf("2026-10-07T08:45", "2026-10-08T08:45", "2026-10-09T08:45"), fires(list))
        assertEquals(3, list.map { it.notificationId }.toSet().size)
        assertEquals("stable ids", list[0].notificationId, ReminderPlanner.between(listOf(e), ms("2026-10-07T08:00:00+08:00"), ms("2026-10-10T00:00:00+08:00"), sh)[0].notificationId)
    }

    @Test fun reminderForAnOccurrenceThatStartsAfterTheWindowStillFiresInsideIt() {
        // 周一 00:30 开始的每周日程，提前 2 小时提醒 → 在周日 22:30 提醒；查询窗口只覆盖周日晚上
        val e = timed(start = "2026-10-05T00:30", end = "2026-10-05T01:00", recurrence = Recurrence.WEEKLY, reminders = listOf(120))
        val list = ReminderPlanner.between(listOf(e), ms("2026-10-11T22:00:00+08:00"), ms("2026-10-11T23:00:00+08:00"), sh)
        assertEquals(listOf("2026-10-11T22:30"), fires(list))
    }

    @Test fun allDayRemindersAreRelativeToNineAmOfTheFirstDay() {
        val e = allDay(start = "2026-10-08", end = "2026-10-09").copy(reminders = listOf(0, 540, 1440))
        val list = ReminderPlanner.between(listOf(e), ms("2026-10-01T00:00:00+08:00"), ms("2026-10-20T00:00:00+08:00"), sh)
        assertEquals(listOf("2026-10-07T09:00", "2026-10-08T00:00", "2026-10-08T09:00"), fires(list))
    }

    @Test fun nextPicksTheEarliestAcrossEvents() {
        val a = timed(id = "a", title = "A", start = "2026-10-09T10:00", end = "2026-10-09T11:00", reminders = listOf(5))
        val b = timed(id = "b", title = "B", start = "2026-10-08T10:00", end = "2026-10-08T11:00", reminders = listOf(5))
        assertEquals("B", ReminderPlanner.next(listOf(a, b), ms("2026-10-07T08:00:00+08:00"), sh)!!.title)
    }

    @Test fun reminderTimesFollowTheEventClockAcrossDst() {
        val e = timed(start = "2026-03-06T09:00", end = "2026-03-06T09:30", zone = "America/New_York", recurrence = Recurrence.DAILY, reminders = listOf(10))
        val ny = ZoneId.of("America/New_York")
        val list = ReminderPlanner.between(listOf(e), ms("2026-03-06T00:00:00Z"), ms("2026-03-10T00:00:00Z"), ny)
        assertEquals(listOf("2026-03-06T14:00", "2026-03-07T14:00", "2026-03-08T13:00", "2026-03-09T13:00").map { java.time.Instant.parse("${it}:00Z").toEpochMilli() - 600_000 }, list.map { it.fireAtMs })
    }
}
