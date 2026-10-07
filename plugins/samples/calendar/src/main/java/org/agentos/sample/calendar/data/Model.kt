package org.agentos.sample.calendar.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 重复规则：不重复 / 每天 / 每周（同一星期几）/ 每月（同一号，没有该日的月份取月末）/ 每年（同一月日，2 月 29 日在平年取 28 日）。 */
enum class Recurrence(val wire: String) {
    NONE("none"),
    DAILY("daily"),
    WEEKLY("weekly"),
    MONTHLY("monthly"),
    YEARLY("yearly");

    companion object {
        fun parse(text: String?): Recurrence? = entries.firstOrNull { it.wire.equals(text?.trim(), ignoreCase = true) }
    }
}

data class CalendarInfo(
    val id: String,
    val name: String,
    /** ARGB */
    val color: Int,
    val visible: Boolean,
    val isDefault: Boolean,
    val createdAt: Long,
)

/**
 * 一个日程“系列”。不重复的日程就是只有一次的系列；重复日程的每一次出现由 [Occurrences] 按查询区间展开。
 *
 * 时间存法：
 * - 定时日程：[startUtc] / [endUtc] 是 UTC 毫秒，[zoneId] 是日程所在时区（重复日程按这个时区的本地时间展开，夏令时前后保持同一钟点）。
 * - 全天日程：语义由 [startDay] / [endDay]（epoch day，endDay 含当天）决定，是“浮动”的日期，不随设备时区变；
 *   [startUtc] / [endUtc] 只是创建时设备时区下的日界毫秒（便于直接读库排查），[zoneId] 用来解释 [recurrenceUntilUtc]。
 */
data class EventSeries(
    val id: String,
    val calendarId: String,
    val title: String,
    val description: String = "",
    val location: String = "",
    val allDay: Boolean = false,
    val startUtc: Long,
    val endUtc: Long,
    val zoneId: String,
    val startDay: Long = 0,
    val endDay: Long = 0,
    /** ARGB；null 表示跟随日历的颜色。 */
    val color: Int? = null,
    /** 提前多少分钟提醒（去重、升序）。定时日程相对开始时刻；全天日程相对“第一天 09:00”。 */
    val reminders: List<Int> = emptyList(),
    val recurrence: Recurrence = Recurrence.NONE,
    /** 重复截止（含）。定时日程：开始时刻不晚于它的出现才算；全天日程：按 [zoneId] 取本地日期比较。 */
    val recurrenceUntilUtc: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val isRecurring: Boolean get() = recurrence != Recurrence.NONE
    val startDate: LocalDate get() = LocalDate.ofEpochDay(startDay)
    val endDate: LocalDate get() = LocalDate.ofEpochDay(endDay)

    companion object {
        const val MAX_TITLE = 200
        const val MAX_LOCATION = 200
        const val MAX_DESCRIPTION = 4000
        const val MAX_REMINDERS = 5
        const val MAX_REMINDER_MINUTES = 40_320 // 4 周
    }
}

/** 某个系列在某一次的出现。 */
data class Occurrence(
    val series: EventSeries,
    /** 定时日程：开始时刻。全天日程：第一天 00:00（设备时区）。 */
    val startMs: Long,
    /** 定时日程：结束时刻。全天日程：最后一天之后的 00:00（设备时区，不含）。 */
    val endMs: Long,
    /** 在设备时区下占据的第一天与最后一天（含）。 */
    val firstDay: LocalDate,
    val lastDay: LocalDate,
) {
    val allDay: Boolean get() = series.allDay
    val seriesId: String get() = series.id
    val spansDays: Boolean get() = lastDay.isAfter(firstDay)

    /** 同一系列里区分各次出现的稳定后缀：定时 = 开始时刻（UTC，basic ISO），全天 = 日期。 */
    val key: String
        get() = if (series.allDay) firstDay.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) else KEY_FORMAT.format(Instant.ofEpochMilli(startMs))

    /** 不重复的日程就是 series id；重复日程的某一次是 `<series id>@<key>`。 */
    val id: String get() = if (series.isRecurring) "${series.id}@$key" else series.id

    companion object {
        val KEY_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC)
    }
}

/** 时间与时区的来源。测试里可以替换。 */
interface TimeEnv {
    fun zone(): ZoneId
    fun nowMs(): Long

    object Default : TimeEnv {
        override fun zone(): ZoneId = ZoneId.systemDefault()
        override fun nowMs(): Long = System.currentTimeMillis()
    }
}

/** 数据校验失败等可预期的错误；消息直接给用户或模型看。 */
class CalendarException(message: String) : Exception(message)

object Palette {
    /** 日历与日程可选的颜色（ARGB）：朱红、琥珀、橄榄绿、青、蓝、靛、洋红、石板灰。 */
    val colors: List<Int> = listOf(
        0xFFE4572E.toInt(),
        0xFFF2A33A.toInt(),
        0xFF5BA55B.toInt(),
        0xFF2BA0A4.toInt(),
        0xFF4A7BDB.toInt(),
        0xFF6C5CE7.toInt(),
        0xFFC7468F.toInt(),
        0xFF6B7280.toInt(),
    )

    fun format(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /** 接受 `#RRGGBB`、`RRGGBB`、`#AARRGGBB`；其他返回 null。 */
    fun parse(text: String?): Int? {
        val t = text?.trim()?.removePrefix("#") ?: return null
        if (t.length != 6 && t.length != 8) return null
        if (!t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val value = t.toLong(16)
        return if (t.length == 6) (0xFF000000L or value).toInt() else value.toInt()
    }
}
