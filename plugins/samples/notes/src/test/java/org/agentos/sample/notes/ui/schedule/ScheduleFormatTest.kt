package org.agentos.sample.notes.ui.schedule

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** 重复说明的纯函数（R4 / R8）：星期名跟着 locale，分隔符由文本提供者（资源）给，代码里不判断语言。 */
class ScheduleFormatTest {
    private val zh = Locale.forLanguageTag("zh-CN")
    private val en = Locale.US

    private class FakeText(override val separator: String, private val weekly: String, private val tag: String) : RepeatText {
        override val once = "$tag:once"
        override val everyDay = "$tag:everyDay"
        override val weekdays = "$tag:weekdays"
        override val weekends = "$tag:weekends"
        override fun weekly(days: String) = weekly.format(days)
    }

    private val zhText = FakeText("、", "每%s", "zh")
    private val enText = FakeText(", ", "Every %s", "en")

    @Test fun `a few weekdays are joined with the separator of the language`() {
        assertEquals("每周一、周三", ScheduleFormat.repeatText(listOf("mon", "wed"), zh, zhText))
        assertEquals("Every Mon, Wed", ScheduleFormat.repeatText(listOf("mon", "wed"), en, enText))
    }

    @Test fun `days come out Monday first whatever order they were given in, and codes are case-insensitive`() {
        assertEquals("Every Mon, Wed, Sun", ScheduleFormat.repeatText(listOf("SUN", "Wed", "mon", "wed"), en, enText))
        assertEquals("每周一、周三、周日", ScheduleFormat.repeatText(listOf("sun", "wed", "mon"), zh, zhText))
    }

    @Test fun `the language comes from the locale and the text provider, not from the code`() {
        // 同样的日子、不同的 locale + 提供者：结果各自成句；换一个分隔符就跟着换，说明代码里没有写死 “、”
        val semi = FakeText("; ", "Every %s", "en")
        assertEquals("Every Tue; Thu", ScheduleFormat.repeatText(listOf("tue", "thu"), en, semi))
        assertEquals("每周二、周四", ScheduleFormat.repeatText(listOf("tue", "thu"), zh, zhText))
    }

    @Test fun `the special sets get their own words in both languages`() {
        val all = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        assertEquals("zh:everyDay", ScheduleFormat.repeatText(all, zh, zhText))
        assertEquals("en:everyDay", ScheduleFormat.repeatText(all, en, enText))
        assertEquals("zh:weekdays", ScheduleFormat.repeatText(listOf("mon", "tue", "wed", "thu", "fri"), zh, zhText))
        assertEquals("en:weekends", ScheduleFormat.repeatText(listOf("sat", "sun"), en, enText))
        assertEquals("zh:once", ScheduleFormat.repeatText(emptyList(), zh, zhText))
        assertEquals("en:once", ScheduleFormat.repeatText(listOf("someday", ""), en, enText))
    }
}
