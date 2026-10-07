package org.agentos.sample.alarm.ui

import android.content.Context
import android.text.format.DateFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.agentos.sample.alarm.R
import org.agentos.sample.alarm.data.EVERY_DAY
import org.agentos.sample.alarm.data.WEEKDAYS
import org.agentos.sample.alarm.data.WEEKEND

/** 大号时间的两段：数字主体和可选的上午 / 下午后缀（24 小时制没有后缀）。 */
data class TimeParts(val main: String, val suffix: String?)

fun timeParts(context: Context, hour: Int, minute: Int): TimeParts {
    val locale = locale(context)
    return if (DateFormat.is24HourFormat(context)) {
        TimeParts("%02d:%02d".format(hour, minute), null)
    } else {
        val time = LocalTime.of(hour, minute)
        TimeParts(
            DateTimeFormatter.ofPattern("h:mm", locale).format(time),
            DateTimeFormatter.ofPattern("a", locale).format(time),
        )
    }
}

/** 通知、提示里用的一行时间，跟随系统的 12 / 24 小时设置。 */
fun formatClock(context: Context, hour: Int, minute: Int): String {
    val parts = timeParts(context, hour, minute)
    return if (parts.suffix == null) parts.main else "${parts.main} ${parts.suffix}"
}

/** “6 小时 20 分钟”：超过一天只写天和小时；不足一分钟写“不到 1 分钟”。 */
fun formatDuration(context: Context, duration: Duration): String {
    val totalMinutes = duration.toMinutes().coerceAtLeast(0)
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes / 60) % 24
    val minutes = totalMinutes % 60
    val parts = buildList {
        if (days > 0) add(context.getString(R.string.duration_days, days))
        if (hours > 0) add(context.getString(R.string.duration_hours, hours))
        if (minutes > 0 && days == 0L) add(context.getString(R.string.duration_minutes, minutes))
    }
    return if (parts.isEmpty()) context.getString(R.string.duration_less_than_minute) else parts.joinToString(" ")
}

/** “6 小时 20 分钟后响铃”。 */
fun formatRingsIn(context: Context, now: ZonedDateTime, fireAt: ZonedDateTime): String =
    context.getString(R.string.rings_in, formatDuration(context, Duration.between(now, fireAt)))

/** 重复日摘要：每天 / 工作日 / 周末 / “周一 周三”；空集合为“仅一次”。 */
fun repeatSummary(context: Context, days: Set<DayOfWeek>): String = when {
    days.isEmpty() -> context.getString(R.string.repeat_once)
    days == EVERY_DAY -> context.getString(R.string.repeat_daily)
    days == WEEKDAYS -> context.getString(R.string.repeat_weekdays)
    days == WEEKEND -> context.getString(R.string.repeat_weekends)
    else -> {
        val names = context.resources.getStringArray(R.array.weekday_medium)
        days.sortedBy { it.value }.joinToString(" ") { names[it.value - 1] }
    }
}

/** “今天 / 明天 / 周四”：下一次响铃落在哪天。 */
fun dayLabel(context: Context, now: ZonedDateTime, fireAt: ZonedDateTime): String {
    val daysAway = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), fireAt.toLocalDate())
    return when (daysAway) {
        0L -> context.getString(R.string.day_today)
        1L -> context.getString(R.string.day_tomorrow)
        else -> context.resources.getStringArray(R.array.weekday_medium)[fireAt.dayOfWeek.value - 1]
    }
}

private fun locale(context: Context): Locale = context.resources.configuration.locales[0]
