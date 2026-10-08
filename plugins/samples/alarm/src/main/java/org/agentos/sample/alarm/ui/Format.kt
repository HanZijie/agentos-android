package org.agentos.sample.alarm.ui

import android.content.Context
import android.text.format.DateFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.agentos.sample.alarm.R
import org.agentos.sample.alarm.data.EVERY_DAY
import org.agentos.sample.alarm.data.WEEKDAYS
import org.agentos.sample.alarm.data.WEEKEND

/**
 * 大号时间的三段：数字主体，加可选的上午 / 下午标记。标记在数字前还是后由系统按 locale 决定
 * （中文“上午 7:30”，英文“7:30 AM”）；24 小时制没有标记。
 */
data class TimeParts(val main: String, val suffix: String?, val prefix: String? = null)

/**
 * 时间显示所需的系统设置：locale、12 / 24 小时、12 小时制下系统给这个 locale 的最佳时间格式（`getBestDateTimePattern(locale, "hmm")`，
 * 比如中文 `ah:mm`、英文 `h:mm a`）。纯数据，JVM 测试直接构造。
 */
class ClockStyle(val locale: Locale, val is24Hour: Boolean, val pattern12: String) {
    companion object {
        fun of(context: Context): ClockStyle {
            val locale = context.resources.configuration.locales[0]
            return ClockStyle(locale, DateFormat.is24HourFormat(context), DateFormat.getBestDateTimePattern(locale, "hmm"))
        }
    }
}

/** 12 小时制的格式拆开：[numeric] 是去掉 am/pm 标记后的数字部分，[markerFirst] 为 true 表示标记在数字前面，[hasMarker] 为 false 表示格式里没有标记。 */
data class MeridiemSplit(val numeric: String, val markerFirst: Boolean, val hasMarker: Boolean)

private val PATTERN_SPACES = charArrayOf(' ', '\u00A0', '\u202F', '\u2009')

/**
 * 把 `ah:mm` / `h:mm a` / `h:mm\u202Fa` 这类 CLDR 格式里的 am/pm 标记（引号外的 `a`）拆出来，连同它和数字之间的空白一起去掉。
 * 位置由格式本身决定，不用 `locale.language == "zh"` 之类的判断（R4）。
 */
fun splitMeridiem(pattern: String): MeridiemSplit {
    var inQuote = false
    var start = -1
    for ((i, c) in pattern.withIndex()) {
        if (c == '\'') inQuote = !inQuote
        else if (!inQuote && c == 'a') {
            start = i
            break
        }
    }
    if (start < 0) return MeridiemSplit(pattern, markerFirst = false, hasMarker = false)
    var end = start
    while (end < pattern.length && pattern[end] == 'a') end++
    val before = pattern.substring(0, start)
    val after = pattern.substring(end)
    val markerFirst = before.isBlank()
    val numeric = if (markerFirst) after.trimStart(*PATTERN_SPACES) else before.trimEnd(*PATTERN_SPACES) + after
    return MeridiemSplit(numeric, markerFirst, hasMarker = true)
}

fun timeParts(style: ClockStyle, hour: Int, minute: Int): TimeParts {
    if (style.is24Hour) return TimeParts("%02d:%02d".format(hour, minute), null)
    val time = LocalTime.of(hour, minute)
    val split = splitMeridiem(style.pattern12)
    val main = DateTimeFormatter.ofPattern(split.numeric, style.locale).format(time)
    if (!split.hasMarker) return TimeParts(main, null)
    val marker = DateTimeFormatter.ofPattern("a", style.locale).format(time)
    return if (split.markerFirst) TimeParts(main, null, prefix = marker) else TimeParts(main, marker)
}

fun timeParts(context: Context, hour: Int, minute: Int): TimeParts = timeParts(ClockStyle.of(context), hour, minute)

/** 通知、提示里用的一行时间，跟随系统的 12 / 24 小时设置：“上午 7:30” / “7:30 AM” / “07:30”。 */
fun formatClock(style: ClockStyle, hour: Int, minute: Int): String {
    val parts = timeParts(style, hour, minute)
    return listOfNotNull(parts.prefix, parts.main, parts.suffix).joinToString(" ")
}

fun formatClock(context: Context, hour: Int, minute: Int): String = formatClock(ClockStyle.of(context), hour, minute)

/** “6 小时 20 分钟”：超过一天只写天和小时；不足一分钟写“不到 1 分钟”。 */
fun formatDuration(texts: Texts, duration: Duration): String {
    val totalMinutes = duration.toMinutes().coerceAtLeast(0)
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes / 60) % 24
    val minutes = totalMinutes % 60
    val parts = buildList {
        if (days > 0) add(texts.get(R.string.duration_days, days))
        if (hours > 0) add(texts.get(R.string.duration_hours, hours))
        if (minutes > 0 && days == 0L) add(texts.get(R.string.duration_minutes, minutes))
    }
    return if (parts.isEmpty()) texts.get(R.string.duration_less_than_minute) else parts.joinToString(" ")
}

fun formatDuration(context: Context, duration: Duration): String = formatDuration(ContextTexts(context), duration)

/** “6 小时 20 分钟后响铃”。 */
fun formatRingsIn(texts: Texts, now: ZonedDateTime, fireAt: ZonedDateTime): String =
    texts.get(R.string.rings_in, formatDuration(texts, Duration.between(now, fireAt)))

fun formatRingsIn(context: Context, now: ZonedDateTime, fireAt: ZonedDateTime): String =
    formatRingsIn(ContextTexts(context), now, fireAt)

/** 重复日摘要：每天 / 工作日 / 周末 / “周一 周三”；空集合为“仅一次”。 */
fun repeatSummary(texts: Texts, days: Set<DayOfWeek>): String = when {
    days.isEmpty() -> texts.get(R.string.repeat_once)
    days == EVERY_DAY -> texts.get(R.string.repeat_daily)
    days == WEEKDAYS -> texts.get(R.string.repeat_weekdays)
    days == WEEKEND -> texts.get(R.string.repeat_weekends)
    else -> {
        val names = texts.array(R.array.weekday_medium)
        days.sortedBy { it.value }.joinToString(" ") { names[it.value - 1] }
    }
}

fun repeatSummary(context: Context, days: Set<DayOfWeek>): String = repeatSummary(ContextTexts(context), days)

/** “今天 / 明天 / 周四”：下一次响铃落在哪天。 */
fun dayLabel(texts: Texts, now: ZonedDateTime, fireAt: ZonedDateTime): String {
    val daysAway = ChronoUnit.DAYS.between(now.toLocalDate(), fireAt.toLocalDate())
    return when (daysAway) {
        0L -> texts.get(R.string.day_today)
        1L -> texts.get(R.string.day_tomorrow)
        else -> texts.array(R.array.weekday_medium)[fireAt.dayOfWeek.value - 1]
    }
}

fun dayLabel(context: Context, now: ZonedDateTime, fireAt: ZonedDateTime): String = dayLabel(ContextTexts(context), now, fireAt)
