package org.agentos.sample.calendar.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import org.agentos.sample.calendar.R
import org.agentos.sample.calendar.data.CalendarInfo
import org.agentos.sample.calendar.data.CalendarSource
import org.agentos.sample.calendar.data.EventSeries
import org.agentos.sample.calendar.data.MonthGrid
import org.agentos.sample.calendar.data.Occurrence
import org.agentos.sample.calendar.data.Occurrences
import org.agentos.sample.calendar.data.Recurrence
import org.agentos.sample.calendar.data.WindowSnapshot
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * 界面一次渲染用的数据快照：仓库的日历列表 + 当前视窗里的出现（[WindowSnapshot]）+ 时区 + 今天。
 * 视窗只覆盖界面报告过的区间（见 [WindowRange]）；区间外的日期此刻没有数据，不代表那天没有日程——用 [covers] 判断。
 */
@Immutable
class CalendarData(
    val calendars: List<CalendarInfo>,
    val snapshot: WindowSnapshot,
    val zone: ZoneId,
    val today: LocalDate,
    val firstDayOfWeek: DayOfWeek,
    val systemAccess: Boolean,
    /** 当前生效的默认写入日历 id。 */
    val defaultWriteId: String = "",
) {
    private val byId: Map<String, CalendarInfo> = calendars.associateBy { it.id }

    fun calendar(id: String): CalendarInfo? = byId[id]

    fun colorOf(s: EventSeries): Color = Color(s.color ?: byId[s.calendarId]?.color ?: 0xFFE4572E.toInt())

    /** 视窗是否已经包含 [from, to]（含）这些日期。 */
    fun covers(from: LocalDate, to: LocalDate): Boolean {
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return snapshot.fromMs <= fromMs && snapshot.toMs >= toMs
    }

    /** 视窗里是否有本机日历的日程带提醒（只有这些由本 App 发通知，系统日历的提醒归系统日历 App）。 */
    val hasLocalReminders: Boolean
        get() = snapshot.occurrences.any { it.series.reminders.isNotEmpty() && byId[it.series.calendarId]?.system == false }

    /** [from, to]（含）范围内、可见日历里的日程，按设备时区的日期归并；跨日日程出现在占据的每一天。 */
    fun byDay(from: LocalDate, to: LocalDate): Map<LocalDate, List<Occurrence>> {
        val list = snapshot.occurrences
            .filter { !it.firstDay.isAfter(to) && !it.lastDay.isBefore(from) && byId[it.series.calendarId]?.visible == true }
            .sortedWith(Occurrences.displayOrder)
        return Occurrences.bucketByDay(list, from, to)
    }

    companion object {
        fun systemFirstDayOfWeek(): DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    }
}

/** 各个首页标签页需要的视窗区间（纯函数）：当前页前后各留一页，滑动时邻页不会是空的。 */
object WindowRange {
    /** [from, to]（含）的日期范围。 */
    fun dates(tab: HomeTab, selected: LocalDate, today: LocalDate, agendaDays: Int, firstDayOfWeek: DayOfWeek): Pair<LocalDate, LocalDate> = when (tab) {
        HomeTab.Month -> {
            val m = YearMonth.from(selected)
            MonthGrid.firstCell(m.minusMonths(1), firstDayOfWeek) to MonthGrid.firstCell(m.plusMonths(1), firstDayOfWeek).plusDays(41)
        }
        HomeTab.Week -> {
            val w = weekStartOf(selected, firstDayOfWeek)
            w.minusWeeks(2) to w.plusWeeks(3).minusDays(1)
        }
        HomeTab.Agenda -> today to today.plusDays(agendaDays.toLong() - 1)
    }

    fun millis(range: Pair<LocalDate, LocalDate>, zone: ZoneId): Pair<Long, Long> =
        range.first.atStartOfDay(zone).toInstant().toEpochMilli() to range.second.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

/** 本地化的日期、时间文字。模式由系统按区域生成（中文“10月8日 周四”，英文“Thu, Oct 8”）。 */
class Fmt(val context: Context, val locale: Locale, private val is24: Boolean) {
    private fun f(skeleton: String): DateTimeFormatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

    private val monthF = f("MMMM")
    private val yearF = f("y")
    private val monthYearF = f("yMMMM")
    private val dayLongF = f("MMMEEEd")
    private val dayMediumF = f("yMMMEEEd")
    private val monthDayF = f("MMMd")
    private val timeF = f(if (is24) "Hm" else "hm")
    private val hourF = f(if (is24) "H" else "ha")

    fun monthName(m: YearMonth): String = monthF.format(m.atDay(1))
    fun yearText(m: YearMonth): String = yearF.format(m.atDay(1))
    fun monthYear(m: YearMonth): String = monthYearF.format(m.atDay(1))
    fun dayLong(d: LocalDate): String = dayLongF.format(d)
    fun dayMedium(d: LocalDate): String = dayMediumF.format(d)
    fun monthDay(d: LocalDate): String = monthDayF.format(d)
    fun weekdayShort(d: DayOfWeek): String = d.getDisplayName(TextStyle.SHORT, locale)
    fun weekdayNarrow(d: DayOfWeek): String = d.getDisplayName(TextStyle.NARROW, locale)
    fun weekdayLong(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    fun hourLabel(hour: Int): String = hourF.format(java.time.LocalTime.of(hour % 24, 0))
    fun time(ms: Long, zone: ZoneId): String = timeF.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun time(t: java.time.LocalTime): String = timeF.format(t)

    /**
     * 周范围（“10月5日至11日” / “Oct 5 – 11”）：交给 ICU 的 [android.icu.text.DateIntervalFormat] 按区域决定怎么写
     * （同月只写一次月份、日期的单位字），不在代码里按语言拼（R4）。
     */
    fun weekRange(start: LocalDate): String {
        val ulocale = android.icu.util.ULocale.forLocale(locale)
        fun cal(d: LocalDate) = android.icu.util.Calendar.getInstance(ulocale).apply { clear(); set(d.year, d.monthValue - 1, d.dayOfMonth) }
        return android.icu.text.DateIntervalFormat.getInstance("MMMd", ulocale)
            .format(cal(start), cal(start.plusDays(6)), StringBuffer(), java.text.FieldPosition(0)).toString()
    }

    fun relativeDay(d: LocalDate, today: LocalDate): String? = when (d) {
        today -> context.getString(R.string.today)
        today.plusDays(1) -> context.getString(R.string.tomorrow)
        today.minusDays(1) -> context.getString(R.string.yesterday)
        else -> null
    }

    /** 详情页的完整时间描述。 */
    fun rangeText(o: Occurrence, zone: ZoneId): String {
        val s = o.series
        if (s.allDay) {
            return if (o.spansDays) "${dayMedium(o.firstDay)} – ${dayMedium(o.lastDay)}" else "${dayMedium(o.firstDay)} · ${context.getString(R.string.all_day)}"
        }
        val start = time(o.startMs, zone)
        val end = time(o.endMs, zone)
        return when {
            o.endMs == o.startMs -> "${dayMedium(o.firstDay)} · $start"
            !o.spansDays -> "${dayMedium(o.firstDay)} · $start – $end"
            else -> "${dayMedium(o.firstDay)} $start – ${dayMedium(Instant.ofEpochMilli(o.endMs).atZone(zone).toLocalDate())} $end"
        }
    }

    /** 列表行左侧的时间两行：在 [day] 这一天里的开始和结束。 */
    fun rowTimes(o: Occurrence, day: LocalDate, zone: ZoneId): Pair<String, String?> {
        if (o.allDay) return context.getString(R.string.all_day) to null
        val start = if (o.firstDay.isBefore(day)) "…" else time(o.startMs, zone)
        val end = when {
            o.lastDay.isAfter(day) -> "…"
            o.endMs == o.startMs -> null
            else -> time(o.endMs, zone)
        }
        return start to end
    }

    fun reminderLabel(minutes: Int, allDay: Boolean): String {
        val r = context.resources
        if (allDay) {
            if (minutes == 0) return context.getString(R.string.reminder_allday_same_day)
            if (minutes % 1440 == 0) return r.getQuantityString(R.plurals.reminder_allday_days_before, minutes / 1440, minutes / 1440)
        }
        return when {
            minutes == 0 -> context.getString(R.string.reminder_at_time)
            minutes % (7 * 1440) == 0 -> r.getQuantityString(R.plurals.reminder_weeks_before, minutes / (7 * 1440), minutes / (7 * 1440))
            minutes % 1440 == 0 -> r.getQuantityString(R.plurals.reminder_days_before, minutes / 1440, minutes / 1440)
            minutes % 60 == 0 -> r.getQuantityString(R.plurals.reminder_hours_before, minutes / 60, minutes / 60)
            else -> r.getQuantityString(R.plurals.reminder_minutes_before, minutes, minutes)
        }
    }

    fun recurrenceLabel(r: Recurrence): String = context.getString(
        when (r) {
            Recurrence.NONE -> R.string.repeat_none
            Recurrence.DAILY -> R.string.repeat_daily
            Recurrence.WEEKLY -> R.string.repeat_weekly
            Recurrence.MONTHLY -> R.string.repeat_monthly
            Recurrence.YEARLY -> R.string.repeat_yearly
            Recurrence.CUSTOM -> R.string.repeat_custom
        },
    )

    /** 日历的来源标签（“Google”“CalDAV”“其他账号”“本机”）。 */
    fun sourceLabel(c: CalendarInfo): String = context.getString(
        when (c.source) {
            CalendarSource.GOOGLE -> R.string.source_google
            CalendarSource.CALDAV -> R.string.source_caldav
            CalendarSource.OTHER -> R.string.source_other
            CalendarSource.LOCAL -> R.string.source_local
        },
    )

    /** 日历的出处一行：账号日历 “Google · name@example.com”；本机（含系统库里的 LOCAL 账号）只写“本机”。 */
    fun originLabel(c: CalendarInfo): String =
        if (c.isAccountCalendar) listOf(sourceLabel(c), c.account).filter { it.isNotBlank() }.joinToString(" · ") else context.getString(R.string.source_local)
}

@Composable
fun rememberFmt(): Fmt {
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val is24 = DateFormat.is24HourFormat(ctx)
    return remember(ctx, locale, is24) { Fmt(ctx, locale, is24) }
}
