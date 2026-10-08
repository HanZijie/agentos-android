package org.agentos.sample.todo.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.agentos.sample.todo.UTC8
import org.agentos.sample.todo.millis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DueTimeTest {
    private fun invalid(text: String, allDay: Boolean? = null): String {
        try {
            DueTime.parse(text, allDay)
        } catch (e: TodoException) {
            assertEquals(TodoException.Kind.INVALID, e.kind)
            assertTrue("one line: ${e.message}", !e.message!!.contains('\n'))
            return e.message!!
        }
        fail("expected \"$text\" to be rejected")
        error("unreachable")
    }

    @Test fun `a plain date is an all-day due`() {
        assertEquals(Due.Day(LocalDate.of(2026, 10, 12)), DueTime.parse("2026-10-12"))
        assertEquals(Due.Day(LocalDate.of(2026, 10, 12)), DueTime.parse("  2026-10-12 "))
    }

    @Test fun `a date-time with an offset is an instant, seconds optional, Z accepted`() {
        val expected = millis("2026-10-12T17:00:00+08:00")
        assertEquals(Due.At(expected), DueTime.parse("2026-10-12T17:00:00+08:00"))
        assertEquals(Due.At(expected), DueTime.parse("2026-10-12T17:00+08:00"))
        assertEquals(Due.At(expected), DueTime.parse("2026-10-12T09:00:00Z"))
        assertEquals(Due.At(expected), DueTime.parse("2026-10-12T04:00:00-05:00"))
    }

    @Test fun `milliseconds are dropped so every comparison is at second precision`() {
        val at = DueTime.parse("2026-10-12T17:00:00.789+08:00") as Due.At
        assertEquals(0L, at.epochMillis % 1000)
    }

    @Test fun `values without an offset or in other shapes are rejected with one sentence`() {
        for (bad in listOf("", "   ", "tomorrow", "2026-10-12T17:00", "2026-10-12 17:00:00+08:00", "2026-1-2", "12/10/2026", "2026-10-12T25:00:00+08:00", "20261012")) {
            val message = invalid(bad)
            assertTrue(message, message.contains("YYYY-MM-DD"))
        }
    }

    @Test fun `impossible calendar dates and absurd years are rejected`() {
        invalid("2026-02-30")
        invalid("2026-13-01")
        assertTrue(invalid("1969-12-31").contains("year"))
        assertTrue(invalid("2201-01-01").contains("year"))
    }

    @Test fun `due_all_day true takes the date in the offset the caller wrote, not the UTC date`() {
        // 23:30 在 -05:00 是 UTC 的次日 04:30；“全天”要的是调用方写的那一天
        assertEquals(Due.Day(LocalDate.of(2026, 10, 9)), DueTime.parse("2026-10-09T23:30:00-05:00", allDay = true))
        assertEquals(Due.Day(LocalDate.of(2026, 10, 9)), DueTime.parse("2026-10-09", allDay = true))
    }

    @Test fun `due_all_day false needs a time`() {
        assertTrue(invalid("2026-10-09", allDay = false).contains("due_all_day"))
        assertEquals(Due.At(millis("2026-10-09T10:00:00+08:00")), DueTime.parse("2026-10-09T10:00:00+08:00", allDay = false))
    }

    @Test fun `iso output uses the device offset for instants and just the date for all-day`() {
        val at = Due.At(millis("2026-10-12T09:00:00Z"))
        assertEquals("2026-10-12T17:00:00+08:00", DueTime.iso(at, UTC8))
        assertEquals("2026-10-12T04:00:00-05:00", DueTime.iso(at, ZoneOffset.ofHours(-5)))
        assertEquals("2026-10-12", DueTime.iso(Due.Day(LocalDate.of(2026, 10, 12)), UTC8))
    }

    @Test fun `an instant is overdue once its time has passed`() {
        val due = Due.At(millis("2026-10-09T10:00:00+08:00"))
        assertEquals(false, DueTime.isOverdue(due, millis("2026-10-09T10:00:00+08:00"), UTC8))
        assertEquals(true, DueTime.isOverdue(due, millis("2026-10-09T10:00:01+08:00"), UTC8))
    }

    @Test fun `an all-day due is not overdue during its own day and is overdue from the next local day`() {
        val due = Due.Day(LocalDate.of(2026, 10, 9))
        assertEquals(false, DueTime.isOverdue(due, millis("2026-10-09T00:00:00+08:00"), UTC8))
        assertEquals(false, DueTime.isOverdue(due, millis("2026-10-09T23:59:59+08:00"), UTC8))
        assertEquals(true, DueTime.isOverdue(due, millis("2026-10-10T00:00:00+08:00"), UTC8))
        // 同一时刻换一个时区，“当天”就不同：+08:00 的 10 月 10 日 00:00 在 UTC-5 还是 10 月 9 日
        assertEquals(false, DueTime.isOverdue(due, millis("2026-10-10T00:00:00+08:00"), ZoneOffset.ofHours(-5)))
    }

    @Test fun `compare uses instants for two instants and local dates as soon as one side is a date`() {
        val zone: ZoneId = UTC8
        val at = Due.At(millis("2026-10-09T23:30:00+08:00"))
        val later = Due.At(millis("2026-10-10T00:30:00+08:00"))
        assertTrue(DueTime.compare(at, later, zone) < 0)
        // 全天对时刻：看日期
        val day9 = Due.Day(LocalDate.of(2026, 10, 9))
        assertEquals(0, DueTime.compare(day9, at, zone))
        assertTrue(DueTime.compare(day9, later, zone) < 0)
        // 时刻对日期：时刻先换成本地日期
        assertEquals(0, DueTime.compare(at, day9, zone))
        assertTrue(DueTime.compare(later, day9, zone) > 0)
        // 同一个时刻在 UTC 里是另一天
        assertEquals(0, DueTime.compare(later, day9, ZoneOffset.UTC))
    }
}
