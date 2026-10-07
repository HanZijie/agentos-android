package org.agentos.sample.alarm.data

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextFireTest {
    private fun alarm(hour: Int, minute: Int, days: Set<DayOfWeek> = emptySet(), enabled: Boolean = true, snoozedUntil: Long? = null) =
        Alarm(id = "1", hour = hour, minute = minute, days = days, enabled = enabled, snoozedUntil = snoozedUntil)

    @Test
    fun laterToday() {
        assertEquals(at(9, 30), NextFire.compute(alarm(9, 30), at(8, 0)))
    }

    @Test
    fun alreadyPassedGoesToTomorrow() {
        assertEquals(at(7, 0, day = 8), NextFire.compute(alarm(7, 0), at(8, 0)))
    }

    @Test
    fun exactlyNowIsNotAfterNowSoGoesToTomorrow() {
        assertEquals(at(8, 0, day = 8), NextFire.compute(alarm(8, 0), at(8, 0)))
    }

    @Test
    fun secondsPastTheMinuteStillTomorrow() {
        assertEquals(at(8, 0, day = 8), NextFire.compute(alarm(8, 0), at(8, 0, second = 30)))
    }

    @Test
    fun crossMidnightIntoNextCalendarDay() {
        assertEquals(at(0, 10, day = 8), NextFire.compute(alarm(0, 10), at(23, 50)))
    }

    @Test
    fun crossMidnightAtMonthEnd() {
        val now = ZonedDateTime.of(2026, 10, 31, 23, 59, 0, 0, SHANGHAI)
        assertEquals(ZonedDateTime.of(2026, 11, 1, 6, 0, 0, 0, SHANGHAI), NextFire.compute(alarm(6, 0), now))
    }

    @Test
    fun repeatSkipsToNextMatchingDay() {
        // 周三 08:00，只在周一响 → 下周一 10-12
        val next = NextFire.compute(alarm(7, 0, setOf(DayOfWeek.MONDAY)), at(8, 0))
        assertEquals(at(7, 0, day = 12), next)
        assertEquals(DayOfWeek.MONDAY, next!!.dayOfWeek)
    }

    @Test
    fun repeatTodayWhenStillAhead() {
        assertEquals(at(18, 0), NextFire.compute(alarm(18, 0, setOf(DayOfWeek.WEDNESDAY)), at(8, 0)))
    }

    @Test
    fun repeatTodayButPassedWaitsAWeek() {
        assertEquals(at(7, 0, day = 14), NextFire.compute(alarm(7, 0, setOf(DayOfWeek.WEDNESDAY)), at(8, 0)))
    }

    @Test
    fun weekdaysFromFridayEveningLandOnMonday() {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val friday = at(20, 0, day = 9)
        assertEquals(DayOfWeek.FRIDAY, friday.dayOfWeek)
        assertEquals(at(7, 30, day = 12), NextFire.compute(alarm(7, 30, weekdays), friday))
    }

    @Test
    fun disabledAlarmHasNoNextFire() {
        assertNull(NextFire.compute(alarm(9, 0, enabled = false), at(8, 0)))
    }

    @Test
    fun timeZoneChangesTheInstant() {
        val alarm = alarm(7, 0)
        val shanghai = NextFire.compute(alarm, at(1, 0, SHANGHAI))!!
        val newYork = NextFire.compute(alarm, at(1, 0, ZoneId.of("America/New_York")))!!
        assertEquals(7, shanghai.hour)
        assertEquals(7, newYork.hour)
        // 同一个“本地 07:00”，两地对应不同的绝对时刻
        assertNotEquals(shanghai.toInstant(), newYork.toInstant())
    }

    @Test
    fun sameInstantReinterpretedInOtherZoneStaysLocalWallClock() {
        val instant = at(23, 0, SHANGHAI).toInstant()
        val tokyoNow = instant.atZone(ZoneId.of("Asia/Tokyo")) // 东京 00:00，已是次日
        val next = NextFire.compute(alarm(6, 30), tokyoNow)!!
        assertEquals(ZonedDateTime.of(2026, 10, 8, 6, 30, 0, 0, ZoneId.of("Asia/Tokyo")), next)
    }

    @Test
    fun dstSpringForwardGapIsPushedForward() {
        // 美东 2026-03-08 02:00 → 03:00，02:30 不存在，顺延到 03:30
        val ny = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 3, 8, 0, 30, 0, 0, ny)
        val next = NextFire.compute(alarm(2, 30), now)!!
        assertEquals(ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, ny), next)
    }

    @Test
    fun dstFallBackPicksEarlierOffset() {
        // 美东 2026-11-01 01:30 出现两次，取先到的（夏令时，-04:00）
        val ny = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ny)
        val next = NextFire.compute(alarm(1, 30), now)!!
        assertEquals(-4 * 3600, next.offset.totalSeconds)
        assertEquals(1, next.hour)
    }

    @Test
    fun snoozeEarlierThanRegularWins() {
        val snoozeAt = at(8, 10).toInstant().toEpochMilli()
        val next = NextFire.compute(alarm(7, 0, snoozedUntil = snoozeAt), at(8, 0))
        assertEquals(at(8, 10).toInstant(), next!!.toInstant())
    }

    @Test
    fun snoozeOnDisabledAlarmStillCounts() {
        val snoozeAt = at(8, 10).toInstant().toEpochMilli()
        val next = NextFire.compute(alarm(7, 0, enabled = false, snoozedUntil = snoozeAt), at(8, 0))
        assertEquals(at(8, 10).toInstant(), next!!.toInstant())
    }

    @Test
    fun expiredSnoozeIsIgnored() {
        val snoozeAt = at(7, 50).toInstant().toEpochMilli()
        assertNull(NextFire.compute(alarm(7, 0, enabled = false, snoozedUntil = snoozeAt), at(8, 0)))
    }

    @Test
    fun maskRoundTrip() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY)
        assertEquals(days, days.toMask().toDays())
        assertEquals(0, emptySet<DayOfWeek>().toMask())
        assertEquals(127, EVERY_DAY.toMask())
    }
}
