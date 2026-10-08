package org.agentos.sample.notes.ui.schedule

import android.content.Context
import android.text.format.DateUtils
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Formatter
import java.util.Locale
import org.agentos.sample.notes.R
import org.agentos.sample.notes.agentos.AlarmInfo
import org.agentos.sample.notes.agentos.EventInfo
import org.agentos.sample.notes.agentos.ScheduleItems
import org.agentos.sample.notes.agentos.TodoInfo

/** 重复说明用到的几段文字。Android 里由资源提供（[ScheduleFormat.alarmRepeat]），JVM 测试用中、英两个假实现。 */
interface RepeatText {
    val once: String
    val everyDay: String
    val weekdays: String
    val weekends: String

    /** 星期名之间的分隔符（中文 “、”，英文 “, ”）：跟着语言走，不在代码里按语言判断。 */
    val separator: String

    /** “每周一、周三” / “Every Mon, Wed”：[days] 已经用分隔符连好。 */
    fun weekly(days: String): String
}

/** 把工具结果里的时间写成给人看的样子（跟随系统语言和 12 / 24 小时制）。 */
object ScheduleFormat {
    /** “10月9日周五 15:00–16:00”；整天的只写日期；时间认不出来返回 null（界面就不显示，不猜）。 */
    fun eventWhen(context: Context, info: EventInfo): String? {
        val start = ScheduleItems.parseWhen(info.start) ?: return null
        val end = ScheduleItems.parseWhen(info.end)
        var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL
        val allDay = info.allDay || start.dateOnly
        if (!allDay) flags = flags or DateUtils.FORMAT_SHOW_TIME
        val zone = "GMT" + (if (start.offset == "+00:00") "" else start.offset)
        val endMillis = if (end != null && end.millis >= start.millis) end.millis else start.millis
        return DateUtils.formatDateRange(context, Formatter(StringBuilder(), Locale.getDefault()), start.millis, endMillis, flags, zone).toString()
    }

    /** “截止 10月12日周一”；没有截止时间或认不出来返回 null。写法与 [eventWhen] 一致（日期 / 时间都跟系统）。 */
    fun todoDue(context: Context, info: TodoInfo): String? {
        val due = info.due ?: return null
        val text = eventWhen(context, EventInfo(info.title, due, null)) ?: return null
        return context.getString(R.string.agent_todo_due, text)
    }

    /** “07:00 · 每周一、周三”；只响一次的写“仅一次”。 */
    fun alarmWhen(context: Context, info: AlarmInfo): String = "${info.time} · ${alarmRepeat(context, info.days)}"

    fun alarmRepeat(context: Context, days: List<String>): String = repeatText(days, Locale.getDefault(), object : RepeatText {
        override val once get() = context.getString(R.string.agent_alarm_once)
        override val everyDay get() = context.getString(R.string.agent_alarm_every_day)
        override val weekdays get() = context.getString(R.string.agent_alarm_weekdays)
        override val weekends get() = context.getString(R.string.agent_alarm_weekends)
        override val separator get() = context.getString(R.string.agent_day_separator)
        override fun weekly(days: String) = context.getString(R.string.agent_alarm_weekly, days)
    })

    /** 纯函数：重复日 → 一句话。星期名用 [locale] 的短名，顺序固定周一到周日，分隔符由 [text] 给。 */
    fun repeatText(days: List<String>, locale: Locale, text: RepeatText): String {
        val set = days.mapNotNull(::dayOf).toSortedSet()
        return when {
            set.isEmpty() -> text.once
            set.size == 7 -> text.everyDay
            set == WEEKDAYS -> text.weekdays
            set == WEEKEND -> text.weekends
            else -> text.weekly(set.joinToString(text.separator) { it.getDisplayName(TextStyle.SHORT, locale) })
        }
    }

    private val WEEKDAYS = sortedSetOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val WEEKEND = sortedSetOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    private fun dayOf(code: String): DayOfWeek? = when (code.lowercase(Locale.ROOT).take(3)) {
        "mon" -> DayOfWeek.MONDAY
        "tue" -> DayOfWeek.TUESDAY
        "wed" -> DayOfWeek.WEDNESDAY
        "thu" -> DayOfWeek.THURSDAY
        "fri" -> DayOfWeek.FRIDAY
        "sat" -> DayOfWeek.SATURDAY
        "sun" -> DayOfWeek.SUNDAY
        else -> null
    }
}
