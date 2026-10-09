package org.agentos.sample.todo.ui

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.agentos.sample.todo.UTC8
import org.agentos.sample.todo.millis
import org.agentos.sample.todo.data.Due
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 截止时间的显示文案（R4 / R8）：同一组输入在中文和英文下各一个用例，**日期写法来自“模式提供者”**（App 里是系统的 getBestDateTimePattern，
 * 这里用两张等价的小表代替），相对词来自“文本提供者”；没有任何 `language == "zh"` 的分支。
 */
class DueFormatterTest {
    private object ZhTexts : DueTexts {
        override val today = "今天"
        override val tomorrow = "明天"
        override val yesterday = "昨天"
    }

    private object EnTexts : DueTexts {
        override val today = "Today"
        override val tomorrow = "Tomorrow"
        override val yesterday = "Yesterday"
    }

    // 骨架 → 模式：与 DateFormat.getBestDateTimePattern 在这两个区域的结果等价
    private val zhPatterns = mapOf("MMMEd" to "M月d日E", "yMMMEd" to "y年M月d日E", "Hm" to "HH:mm", "hm" to "ah:mm")
    private val enPatterns = mapOf("MMMEd" to "E, MMM d", "yMMMEd" to "E, MMM d, y", "Hm" to "HH:mm", "hm" to "h:mm a")

    private fun zh(is24: Boolean = true, zone: ZoneId = UTC8) = DueFormatter(Locale.SIMPLIFIED_CHINESE, zone, is24, ZhTexts) { zhPatterns.getValue(it) }
    private fun en(is24: Boolean = true, zone: ZoneId = UTC8) = DueFormatter(Locale.US, zone, is24, EnTexts) { enPatterns.getValue(it) }

    private val now = millis("2026-10-09T10:00:00+08:00") // 周五
    private fun day(iso: String) = Due.Day(LocalDate.parse(iso))
    private fun at(iso: String) = Due.At(millis(iso))

    @Test fun `all-day dues use the relative words, in Chinese and in English`() {
        assertEquals("今天", zh().due(day("2026-10-09"), now))
        assertEquals("明天", zh().due(day("2026-10-10"), now))
        assertEquals("昨天", zh().due(day("2026-10-08"), now))
        assertEquals("Today", en().due(day("2026-10-09"), now))
        assertEquals("Tomorrow", en().due(day("2026-10-10"), now))
        assertEquals("Yesterday", en().due(day("2026-10-08"), now))
    }

    @Test fun `further dates are written by the locale pattern, with the year only when it differs`() {
        assertEquals("10月12日周一", zh().due(day("2026-10-12"), now))
        assertEquals("Mon, Oct 12", en().due(day("2026-10-12"), now))
        assertEquals("2027年1月4日周一", zh().due(day("2027-01-04"), now))
        assertEquals("Mon, Jan 4, 2027", en().due(day("2027-01-04"), now))
    }

    @Test fun `a timed due adds the time in the 24-hour or the 12-hour style of the system`() {
        assertEquals("今天 17:00", zh().due(at("2026-10-09T17:00:00+08:00"), now))
        assertEquals("Tomorrow 07:30", en().due(at("2026-10-10T07:30:00+08:00"), now))
        // 12 小时制：中文的“上午 / 下午”标记由 Java 的 CLDR 数据给出，不同 JDK 的写法可能略有差别，只核对结构
        val zh12 = zh(is24 = false).due(at("2026-10-09T17:00:00+08:00"), now)
        assertEquals(true, zh12.startsWith("今天 ") && zh12.contains("5:00") && !zh12.contains("17"))
        assertEquals("Today 5:00 PM", en(is24 = false).due(at("2026-10-09T17:00:00+08:00"), now).replace("\u202f", " "))
    }

    @Test fun `the relative day follows the device zone, not UTC`() {
        // 2026-10-09T23:30+08:00 = 15:30Z；2026-10-10T00:30+08:00 在 UTC 里仍是 10 月 9 日
        val late = millis("2026-10-09T23:30:00+08:00")
        val due = at("2026-10-10T00:30:00+08:00")
        assertEquals("明天 00:30", zh(zone = UTC8).due(due, late))
        assertEquals("今天 16:30", zh(zone = java.time.ZoneOffset.UTC).due(due, late))
    }

    @Test fun `the full date for the editor never uses a relative word`() {
        assertEquals("2026年10月9日周五", zh().fullDate(LocalDate.of(2026, 10, 9)))
        assertEquals("Fri, Oct 9, 2026", en().fullDate(LocalDate.of(2026, 10, 9)))
    }

    @Test fun `Chinese and English give different text for the same input`() {
        assertNotEquals(zh().due(day("2026-10-12"), now), en().due(day("2026-10-12"), now))
    }
}
