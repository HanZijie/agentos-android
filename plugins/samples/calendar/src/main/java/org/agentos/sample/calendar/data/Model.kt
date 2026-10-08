package org.agentos.sample.calendar.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 重复规则：不重复 / 每天 / 每周（同一星期几）/ 每月（同一号，没有该日的月份取月末）/ 每年（同一月日，2 月 29 日在平年取 28 日）。
 * [CUSTOM] 只会从系统日历读出（RRULE 里有这几种说不清的东西，比如每月第二个周二、间隔大于 1、COUNT）：它不能写入，
 * 也不能被改动——改动会破坏原来的规则，见 [EventSeries.rrule]。
 */
enum class Recurrence(val wire: String) {
    NONE("none"),
    DAILY("daily"),
    WEEKLY("weekly"),
    MONTHLY("monthly"),
    YEARLY("yearly"),
    CUSTOM("custom");

    companion object {
        /** 可以写入的取值（工具参数枚举、编辑界面的选项）。 */
        val settable: List<Recurrence> = listOf(NONE, DAILY, WEEKLY, MONTHLY, YEARLY)

        /** 只认可写入的五种；"custom" 返回 null。 */
        fun parse(text: String?): Recurrence? = settable.firstOrNull { it.wire.equals(text?.trim(), ignoreCase = true) }
    }
}

/** 日历来自哪一类账号。[wire] 是工具里 `source` 字段的取值。 */
enum class CalendarSource(val wire: String) {
    /** 不同步到任何账号：本 App 自己的 SQLite 日历，或系统日历里账号类型为 LOCAL 的日历。 */
    LOCAL("local"),
    GOOGLE("google"),
    CALDAV("caldav"),

    /** 认不出的账号类型（如 Exchange、厂商账号）；原始类型见 [CalendarInfo.accountType]。 */
    OTHER("other"),
}

data class CalendarInfo(
    /** 带来源前缀的 id，见 [CalendarIds]。 */
    val id: String,
    val name: String,
    /** ARGB */
    val color: Int,
    val visible: Boolean,
    /** 本机日历里“永远存在、不能删”的那一个；账号日历永远是 false。工具里的 `is_default` 指“默认写入日历”，由仓库另算。 */
    val isDefault: Boolean,
    val createdAt: Long,
    val account: String = "",
    val accountType: String = "",
    val source: CalendarSource = CalendarSource.LOCAL,
    val writable: Boolean = true,
    /** true = 来自系统日历数据库（CalendarContract）；false = 本 App 自己的 SQLite。 */
    val system: Boolean = false,
    /** 非 null：名字是系统生成的默认名，显示时按当前语言翻译，不把某种语言的文字冻结进数据库（R3）。 */
    val nameKey: String? = null,
) {
    /** 账号日历（会同步到云端的那类）：来自系统日历库、且不是 LOCAL 账号。 */
    val isAccountCalendar: Boolean get() = system && source != CalendarSource.LOCAL

    companion object {
        const val NAME_KEY_DEFAULT = "default"
    }
}

/**
 * 一个日程“系列”。不重复的日程就是只有一次的系列；重复日程的每一次出现：本机日历由 [Occurrences] 按查询区间展开，
 * 系统日历由 Provider 的 Instances 展开。
 *
 * 时间存法：
 * - 定时日程：[startUtc] / [endUtc] 是 UTC 毫秒，[zoneId] 是日程所在时区（重复日程按这个时区的本地时间展开，夏令时前后保持同一钟点）。
 * - 全天日程：语义由 [startDay] / [endDay]（epoch day，endDay 含当天）决定，是“浮动”的日期，不随设备时区变；
 *   [startUtc] / [endUtc] 只是创建时设备时区下的日界毫秒（便于直接读库排查），[zoneId] 用来解释 [recurrenceUntilUtc]。
 */
data class EventSeries(
    /** 带来源前缀的 id，见 [CalendarIds]；新建时传空串。 */
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
    /**
     * 提前多少分钟提醒（去重、升序）。定时日程相对开始时刻；全天日程相对“第一天 09:00”
     * （写入系统日历时换算成相对第一天 00:00，读出时换回来）。
     */
    val reminders: List<Int> = emptyList(),
    val recurrence: Recurrence = Recurrence.NONE,
    /** 重复截止（含）。定时日程：开始时刻不晚于它的出现才算；全天日程：按 [zoneId] 取本地日期比较。 */
    val recurrenceUntilUtc: Long? = null,
    /** [recurrence] 为 [Recurrence.CUSTOM] 时的 RRULE 原文（不含 `RRULE:` 前缀）；其他情况为 null。 */
    val rrule: String? = null,
    /** false = 空闲（“显示为有空”）或已拒绝：不占用 free_slots 里的忙碌时间。只有系统日历的日程可能是 false。 */
    val busy: Boolean = true,
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

/** 系统生成的文字（R3/R8）：数据层不依赖 Android 资源，由调用方按当前语言提供。 */
interface CalendarTexts {
    /** 默认本机日历的名字（用户没改过名时显示）。 */
    fun defaultCalendarName(): String
}

/** 数据校验失败等可预期的错误；消息直接给用户或模型看（英文，R2）。 */
open class CalendarException(message: String) : Exception(message)

/** 没有日历权限：消息固定，工具原样返回给模型。 */
class CalendarPermissionException : CalendarException(MESSAGE) {
    companion object {
        const val MESSAGE = "Calendar permission not granted; ask the user to grant it in the Calendar app"
    }
}

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
