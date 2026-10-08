package org.agentos.sample.alarm.ui

import java.time.DayOfWeek
import java.time.Duration
import java.util.Locale
import org.agentos.sample.alarm.data.at
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 产生用户文案的纯函数（R8）：同一组输入，中文和英文各断言一遍；12 小时制的上午 / 下午位置由系统格式决定（R4）。 */
class FormatTest {
    private val zh = ResourceTexts.zh
    private val en = ResourceTexts.en

    @Test
    fun durationReadsNaturallyInBothLanguages() {
        assertEquals("6 小时 20 分钟", formatDuration(zh, Duration.ofMinutes(6 * 60 + 20)))
        assertEquals("6 hr 20 min", formatDuration(en, Duration.ofMinutes(6 * 60 + 20)))
        // 超过一天只写天和小时
        assertEquals("2 天 3 小时", formatDuration(zh, Duration.ofHours(51) + Duration.ofMinutes(10)))
        assertEquals("2 d 3 hr", formatDuration(en, Duration.ofHours(51) + Duration.ofMinutes(10)))
        assertEquals("不到 1 分钟", formatDuration(zh, Duration.ofSeconds(30)))
        assertEquals("less than 1 min", formatDuration(en, Duration.ofSeconds(30)))
        assertEquals("不到 1 分钟", formatDuration(zh, Duration.ofMinutes(-5))) // 负数夹到 0
    }

    @Test
    fun ringsInSentence() {
        val now = at(8, 0)
        val fire = at(14, 20)
        assertEquals("6 小时 20 分钟后响铃", formatRingsIn(zh, now, fire))
        assertEquals("Rings in 6 hr 20 min", formatRingsIn(en, now, fire))
    }

    @Test
    fun repeatSummaryAndDayLabel() {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        assertEquals("仅一次", repeatSummary(zh, emptySet()))
        assertEquals("Once", repeatSummary(en, emptySet()))
        assertEquals("工作日", repeatSummary(zh, weekdays))
        assertEquals("Weekdays", repeatSummary(en, weekdays))
        assertEquals("周一 周三", repeatSummary(zh, setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY)))
        assertEquals("Mon Wed", repeatSummary(en, setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY)))

        val now = at(8, 0) // 2026-10-07 周三
        assertEquals("今天", dayLabel(zh, now, at(9, 0)))
        assertEquals("Today", dayLabel(en, now, at(9, 0)))
        assertEquals("明天", dayLabel(zh, now, at(9, 0, day = 8)))
        assertEquals("Tomorrow", dayLabel(en, now, at(9, 0, day = 8)))
        assertEquals("周六", dayLabel(zh, now, at(9, 0, day = 10)))
        assertEquals("Sat", dayLabel(en, now, at(9, 0, day = 10)))
    }

    // ---- 12 / 24 小时制 ----

    @Test
    fun splitMeridiemFindsTheMarkerPositionFromThePattern() {
        // CLDR 给中文的 12 小时格式：标记在前；英文：标记在后（新版 CLDR 用窄不换行空格 U+202F 隔开）
        assertEquals(MeridiemSplit("h:mm", markerFirst = true, hasMarker = true), splitMeridiem("ah:mm"))
        assertEquals(MeridiemSplit("h:mm", markerFirst = false, hasMarker = true), splitMeridiem("h:mm a"))
        assertEquals(MeridiemSplit("h:mm", markerFirst = false, hasMarker = true), splitMeridiem("h:mm\u202Fa"))
        assertEquals(MeridiemSplit("h:mm", markerFirst = true, hasMarker = true), splitMeridiem("a h:mm"))
        // 引号里的 a 是字面文字，不是标记
        assertEquals(MeridiemSplit("h:mm 'at' x", markerFirst = false, hasMarker = false), splitMeridiem("h:mm 'at' x"))
        assertFalse(splitMeridiem("HH:mm").hasMarker)
    }

    @Test
    fun chineseTwelveHourPutsTheMarkerBeforeTheTime() {
        val style = ClockStyle(Locale.CHINA, is24Hour = false, pattern12 = "ah:mm")
        assertEquals(TimeParts("7:30", suffix = null, prefix = "上午"), timeParts(style, 7, 30))
        assertEquals(TimeParts("7:30", suffix = null, prefix = "下午"), timeParts(style, 19, 30))
        assertEquals("上午 7:30", formatClock(style, 7, 30))
        assertEquals("下午 12:05", formatClock(style, 12, 5))
        assertEquals("上午 12:00", formatClock(style, 0, 0))
    }

    @Test
    fun englishTwelveHourPutsTheMarkerAfterTheTime() {
        val style = ClockStyle(Locale.US, is24Hour = false, pattern12 = "h:mm\u202Fa")
        assertEquals(TimeParts("7:30", suffix = "AM"), timeParts(style, 7, 30))
        assertEquals(TimeParts("7:30", suffix = "PM"), timeParts(style, 19, 30))
        assertEquals("7:30 AM", formatClock(style, 7, 30))
        assertEquals("12:05 PM", formatClock(style, 12, 5))
    }

    @Test
    fun twentyFourHourHasNoMarkerInEitherLanguage() {
        for (locale in listOf(Locale.CHINA, Locale.US)) {
            val style = ClockStyle(locale, is24Hour = true, pattern12 = "h:mm a")
            assertEquals(TimeParts("07:30", null), timeParts(style, 7, 30))
            assertEquals("19:05", formatClock(style, 19, 5))
        }
        val parts = timeParts(ClockStyle(Locale.US, true, "h:mm a"), 7, 30)
        assertNull(parts.prefix)
        assertTrue(parts.suffix == null)
    }
}
