package org.agentos.sample.calendar

import org.agentos.sample.calendar.data.MonthGrid
import org.agentos.sample.calendar.data.Occurrences
import org.agentos.sample.calendar.data.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class OccurrencesTest {
    private val sh = ZoneId.of("Asia/Shanghai")
    private val ny = ZoneId.of("America/New_York")

    private fun starts(list: List<org.agentos.sample.calendar.data.Occurrence>, zone: String) = list.map { zdt(it.startMs, zone).toLocalDateTime().toString() }

    // ---- 不重复 ----

    @Test fun nonRecurringOverlapIsEndExclusive() {
        val e = timed(start = "2026-10-08T10:00", end = "2026-10-08T11:00")
        val at = { h: String -> ms("2026-10-08T$h:00+08:00") }
        assertEquals(1, Occurrences.expand(e, at("10:59"), at("12:00"), sh).size)
        assertEquals("ends exactly at range start → not included", 0, Occurrences.expand(e, at("11:00"), at("12:00"), sh).size)
        assertEquals("starts exactly at range end → not included", 0, Occurrences.expand(e, at("09:00"), at("10:00"), sh).size)
        assertEquals(1, Occurrences.expand(e, at("10:00"), at("11:00"), sh).size)
    }

    @Test fun zeroLengthEventIsFoundOnlyInsideItsInstant() {
        val e = timed(start = "2026-10-08T10:00", end = "2026-10-08T10:00")
        val t = ms("2026-10-08T10:00:00+08:00")
        assertEquals(1, Occurrences.expand(e, t, t + 60_000, sh).size)
        assertEquals(0, Occurrences.expand(e, t - 60_000, t, sh).size)
        assertEquals(0, Occurrences.expand(e, t + 1, t + 60_000, sh).size)
    }

    // ---- 重复规则 ----

    @Test fun dailyWithUntilIsInclusive() {
        val from = ms("2026-10-01T00:00:00+08:00")
        val to = ms("2026-11-01T00:00:00+08:00")
        val inclusive = timed(start = "2026-10-01T09:00", end = "2026-10-01T10:00", recurrence = Recurrence.DAILY, until = ms("2026-10-05T09:00:00+08:00"))
        assertEquals(5, Occurrences.expand(inclusive, from, to, sh).size)
        val exclusive = inclusive.copy(recurrenceUntilUtc = ms("2026-10-05T08:59:00+08:00"))
        assertEquals(4, Occurrences.expand(exclusive, from, to, sh).size)
        val forever = inclusive.copy(recurrenceUntilUtc = null)
        assertEquals(31, Occurrences.expand(forever, from, to, sh).size)
    }

    @Test fun weeklyKeepsTheWeekday() {
        val e = timed(start = "2026-10-01T09:00", end = "2026-10-01T10:00", recurrence = Recurrence.WEEKLY)
        val list = Occurrences.expand(e, ms("2026-10-01T00:00:00+08:00"), ms("2026-11-01T00:00:00+08:00"), sh)
        assertEquals(listOf(1, 8, 15, 22, 29), list.map { it.firstDay.dayOfMonth })
        assertTrue(list.all { it.firstDay.dayOfWeek == DayOfWeek.THURSDAY })
    }

    @Test fun monthlyClampsToMonthEndWithoutDrifting() {
        val e = timed(start = "2026-01-31T10:00", end = "2026-01-31T11:00", recurrence = Recurrence.MONTHLY)
        val list = Occurrences.expand(e, ms("2026-01-01T00:00:00+08:00"), ms("2026-06-01T00:00:00+08:00"), sh)
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31"), list.map { it.firstDay.toString() })
        val leap = timed(start = "2028-01-31T10:00", end = "2028-01-31T11:00", recurrence = Recurrence.MONTHLY)
        val second = Occurrences.expand(leap, ms("2028-02-01T00:00:00+08:00"), ms("2028-03-01T00:00:00+08:00"), sh)
        assertEquals("2028-02-29", second.single().firstDay.toString())
    }

    @Test fun yearlyOnLeapDayFallsBackInCommonYears() {
        val e = timed(start = "2024-02-29T09:00", end = "2024-02-29T10:00", recurrence = Recurrence.YEARLY)
        val list = Occurrences.expand(e, ms("2024-01-01T00:00:00+08:00"), ms("2029-01-01T00:00:00+08:00"), sh)
        assertEquals(listOf("2024-02-29", "2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29"), list.map { it.firstDay.toString() })
    }

    @Test fun farFutureWindowsAreReachedDirectly() {
        val daily = timed(start = "2020-01-01T09:00", end = "2020-01-01T10:00", recurrence = Recurrence.DAILY)
        val week = Occurrences.expand(daily, ms("2035-06-01T00:00:00+08:00"), ms("2035-06-08T00:00:00+08:00"), sh)
        assertEquals((1..7).map { LocalDate.of(2035, 6, it) }, week.map { it.firstDay })
        val monthly = timed(start = "2020-01-31T09:00", end = "2020-01-31T10:00", recurrence = Recurrence.MONTHLY)
        val march = Occurrences.expand(monthly, ms("2030-03-01T00:00:00+08:00"), ms("2030-04-01T00:00:00+08:00"), sh)
        assertEquals("2030-03-31", march.single().firstDay.toString())
        val weekly = timed(start = "2020-01-01T09:00", end = "2020-01-01T10:00", recurrence = Recurrence.WEEKLY) // Wednesday
        val w = Occurrences.expand(weekly, ms("2040-01-01T00:00:00+08:00"), ms("2040-02-01T00:00:00+08:00"), sh)
        assertTrue(w.isNotEmpty() && w.all { it.firstDay.dayOfWeek == DayOfWeek.WEDNESDAY })
        val yearly = timed(start = "2000-05-20T09:00", end = "2000-05-20T10:00", recurrence = Recurrence.YEARLY)
        assertEquals("2087-05-20", Occurrences.expand(yearly, ms("2087-01-01T00:00:00+08:00"), ms("2088-01-01T00:00:00+08:00"), sh).single().firstDay.toString())
    }

    @Test fun expansionIsCappedPerSeries() {
        val daily = timed(start = "2020-01-01T09:00", end = "2020-01-01T10:00", recurrence = Recurrence.DAILY)
        val list = Occurrences.expand(daily, ms("2020-01-01T00:00:00+08:00"), ms("2120-01-01T00:00:00+08:00"), sh)
        assertEquals(Occurrences.MAX_PER_SERIES, list.size)
    }

    @Test fun longRecurringEventStartedBeforeTheWindowIsIncluded() {
        // 周一 10:00 到周三 10:00，每周；查询周二一整天，应命中周一开始的那次
        val e = timed(start = "2026-10-05T10:00", end = "2026-10-07T10:00", recurrence = Recurrence.WEEKLY)
        val list = Occurrences.expand(e, ms("2026-10-13T00:00:00+08:00"), ms("2026-10-14T00:00:00+08:00"), sh)
        assertEquals(1, list.size)
        assertEquals("2026-10-12", list.single().firstDay.toString())
        assertEquals("2026-10-14", list.single().lastDay.toString())
    }

    // ---- 夏令时 ----

    @Test fun dailyEventKeepsLocalClockAcrossSpringForward() {
        val e = timed(start = "2026-03-06T09:00", end = "2026-03-06T10:00", zone = "America/New_York", recurrence = Recurrence.DAILY)
        val list = Occurrences.expand(e, ms("2026-03-06T00:00:00Z"), ms("2026-03-10T00:00:00Z"), ny)
        assertEquals(listOf("2026-03-06T14:00", "2026-03-07T14:00", "2026-03-08T13:00", "2026-03-09T13:00"), starts(list, "UTC"))
        assertTrue("duration stays 1h", list.all { it.endMs - it.startMs == 3_600_000L })
        assertEquals(listOf("09:00", "09:00", "09:00", "09:00"), list.map { zdt(it.startMs, "America/New_York").toLocalTime().toString() })
    }

    @Test fun dailyEventKeepsLocalClockAcrossFallBack() {
        val e = timed(start = "2026-10-30T09:00", end = "2026-10-30T10:00", zone = "America/New_York", recurrence = Recurrence.DAILY)
        val list = Occurrences.expand(e, ms("2026-10-30T00:00:00Z"), ms("2026-11-03T00:00:00Z"), ny)
        assertEquals(listOf("2026-10-30T13:00", "2026-10-31T13:00", "2026-11-01T14:00", "2026-11-02T14:00"), starts(list, "UTC"))
    }

    @Test fun eventInsideTheSpringForwardGapShiftsForwardOnce() {
        val e = timed(start = "2026-03-07T02:30", end = "2026-03-07T03:00", zone = "America/New_York", recurrence = Recurrence.DAILY)
        val list = Occurrences.expand(e, ms("2026-03-07T00:00:00Z"), ms("2026-03-10T00:00:00Z"), ny)
        assertEquals(listOf("2026-03-07T02:30", "2026-03-08T03:30", "2026-03-09T02:30"), list.map { zdt(it.startMs, "America/New_York").toLocalDateTime().toString() })
    }

    @Test fun weeklyAcrossEuropeanDstKeepsLocalClock() {
        val e = timed(start = "2026-10-22T18:00", end = "2026-10-22T19:00", zone = "Europe/Berlin", recurrence = Recurrence.WEEKLY)
        val list = Occurrences.expand(e, ms("2026-10-22T00:00:00Z"), ms("2026-11-06T00:00:00Z"), ny)
        // 欧洲夏令时 10 月 25 日结束：22 日是 UTC+2（16:00Z），29 日和 11 月 5 日是 UTC+1（17:00Z）
        assertEquals(listOf("2026-10-22T16:00", "2026-10-29T17:00", "2026-11-05T17:00"), starts(list, "UTC"))
    }

    // ---- 跨日、时区差异 ----

    @Test fun eventStartedYesterdayAndEndingTodayIsFoundOnBothDays() {
        val e = timed(start = "2026-10-01T22:00", end = "2026-10-02T02:00", recurrence = Recurrence.DAILY)
        val night = Occurrences.expand(e, ms("2026-10-03T00:00:00+08:00"), ms("2026-10-03T03:00:00+08:00"), sh)
        assertEquals(listOf("2026-10-02T22:00"), starts(night, "Asia/Shanghai"))
        assertEquals("2026-10-02", night.single().firstDay.toString())
        assertEquals("2026-10-03", night.single().lastDay.toString())
        val evening = Occurrences.expand(e, ms("2026-10-03T12:00:00+08:00"), ms("2026-10-04T00:00:00+08:00"), sh)
        assertEquals(listOf("2026-10-03T22:00"), starts(evening, "Asia/Shanghai"))
    }

    @Test fun eventEndingExactlyAtMidnightDoesNotSpillIntoNextDay() {
        val e = timed(start = "2026-10-01T22:00", end = "2026-10-02T00:00")
        val o = Occurrences.expand(e, 0, Long.MAX_VALUE / 4, sh).single()
        assertEquals(o.firstDay, o.lastDay)
    }

    @Test fun daysAreComputedInTheDeviceZone() {
        // 纽约 3 月 7 日 20:00 = 上海 3 月 8 日 09:00
        val e = timed(start = "2026-03-07T20:00", end = "2026-03-07T21:00", zone = "America/New_York")
        val inShanghai = Occurrences.expand(e, 0, Long.MAX_VALUE / 4, sh).single()
        val inNewYork = Occurrences.expand(e, 0, Long.MAX_VALUE / 4, ny).single()
        assertEquals("2026-03-08", inShanghai.firstDay.toString())
        assertEquals("2026-03-07", inNewYork.firstDay.toString())
    }

    // ---- 全天、跨月 ----

    @Test fun multiDayAllDayEventAcrossMonthsAppearsInBothMonths() {
        val e = allDay(start = "2026-10-30", end = "2026-11-02")
        val oct = Occurrences.expand(e, ms("2026-10-01T00:00:00+08:00"), ms("2026-11-01T00:00:00+08:00"), sh)
        val nov = Occurrences.expand(e, ms("2026-11-01T00:00:00+08:00"), ms("2026-12-01T00:00:00+08:00"), sh)
        val dec = Occurrences.expand(e, ms("2026-12-01T00:00:00+08:00"), ms("2027-01-01T00:00:00+08:00"), sh)
        assertEquals(1, oct.size)
        assertEquals(1, nov.size)
        assertEquals(0, dec.size)
        val days = Occurrences.bucketByDay(oct + nov, LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 7)).keys.sorted().map { it.toString() }
        assertEquals(listOf("2026-10-30", "2026-10-31", "2026-11-01", "2026-11-02"), days)
    }

    @Test fun allDayEventsAreFloatingAcrossDeviceZones() {
        val e = allDay(start = "2026-10-08")
        val la = ZoneId.of("America/Los_Angeles")
        val a = Occurrences.expand(e, ms("2026-10-01T00:00:00+08:00"), ms("2026-11-01T00:00:00+08:00"), sh).single()
        val b = Occurrences.expand(e, ms("2026-10-01T00:00:00-07:00"), ms("2026-11-01T00:00:00-07:00"), la).single()
        assertEquals(a.firstDay, b.firstDay)
        assertEquals(LocalDate.of(2026, 10, 8), b.lastDay)
        assertEquals("2026-10-08", b.key.let { LocalDate.parse(it, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).toString() })
    }

    @Test fun yearlyAllDayAndUntil() {
        val e = allDay(start = "2024-10-08", recurrence = Recurrence.YEARLY, until = ms("2026-10-08T00:00:00+08:00"))
        val inRange = Occurrences.expand(e, ms("2024-01-01T00:00:00+08:00"), ms("2030-01-01T00:00:00+08:00"), sh)
        assertEquals(listOf("2024-10-08", "2025-10-08", "2026-10-08"), inRange.map { it.firstDay.toString() })
        val weekly = allDay(start = "2026-10-01", end = "2026-10-02", recurrence = Recurrence.WEEKLY)
        val w = Occurrences.expand(weekly, ms("2026-10-07T00:00:00+08:00"), ms("2026-10-16T00:00:00+08:00"), sh)
        assertEquals(listOf("2026-10-08", "2026-10-15"), w.map { it.firstDay.toString() })
        assertEquals("2026-10-09", w.first().lastDay.toString())
    }

    // ---- id / key / 辅助 ----

    @Test fun occurrenceIdsAndKeysRoundTrip() {
        val e = timed(id = "s1", start = "2026-10-01T09:00", end = "2026-10-01T10:00", recurrence = Recurrence.DAILY)
        val third = Occurrences.expand(e, ms("2026-10-03T00:00:00+08:00"), ms("2026-10-04T00:00:00+08:00"), sh).single()
        assertEquals("s1@20261003T010000Z", third.id)
        assertEquals("s1" to "20261003T010000Z", Occurrences.splitId(third.id))
        assertEquals(third.startMs, Occurrences.findByKey(e, third.key, sh)!!.startMs)
        assertNull(Occurrences.findByKey(e, "20261003T020000Z", sh))
        assertNull(Occurrences.findByKey(e, "garbage", sh))
        val plain = timed(id = "p1", start = "2026-10-01T09:00", end = "2026-10-01T10:00")
        assertEquals("p1", Occurrences.first(plain, sh).id)
        val a = allDay(id = "a1", start = "2026-10-08", recurrence = Recurrence.WEEKLY)
        val second = Occurrences.expand(a, ms("2026-10-15T00:00:00+08:00"), ms("2026-10-16T00:00:00+08:00"), sh).single()
        assertEquals("a1@20261015", second.id)
        assertNotNull(Occurrences.findByKey(a, second.key, sh))
    }

    @Test fun nextAndLastOccurrence() {
        val e = timed(start = "2026-10-01T09:00", end = "2026-10-01T10:00", recurrence = Recurrence.WEEKLY, until = ms("2026-10-20T00:00:00+08:00"))
        val now = ms("2026-10-10T00:00:00+08:00")
        assertEquals("2026-10-15", Occurrences.nextAtOrAfter(e, now, sh)!!.firstDay.toString())
        assertEquals("2026-10-08", Occurrences.lastBefore(e, now, sh)!!.firstDay.toString())
        assertNull(Occurrences.nextAtOrAfter(e, ms("2026-10-30T00:00:00+08:00"), sh))
    }

    // ---- 月网格 ----

    @Test fun monthGridFollowsFirstDayOfWeek() {
        val oct = YearMonth.of(2026, 10) // 10 月 1 日是周四
        assertEquals(LocalDate.of(2026, 9, 28), MonthGrid.firstCell(oct, DayOfWeek.MONDAY))
        assertEquals(LocalDate.of(2026, 9, 27), MonthGrid.firstCell(oct, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2026, 9, 26), MonthGrid.firstCell(oct, DayOfWeek.SATURDAY))
        assertEquals(42, MonthGrid.cells(oct, DayOfWeek.MONDAY).size)
        assertTrue(MonthGrid.cells(oct, DayOfWeek.MONDAY).contains(LocalDate.of(2026, 10, 31)))
        assertEquals(DayOfWeek.SUNDAY, MonthGrid.weekdayHeaders(DayOfWeek.SUNDAY).first())
        assertEquals(DayOfWeek.SATURDAY, MonthGrid.weekdayHeaders(DayOfWeek.SUNDAY).last())
        // 1 号恰好是一周的第一天时，不多退一周
        assertEquals(LocalDate.of(2026, 6, 1), MonthGrid.firstCell(YearMonth.of(2026, 6), DayOfWeek.MONDAY))
    }
}
