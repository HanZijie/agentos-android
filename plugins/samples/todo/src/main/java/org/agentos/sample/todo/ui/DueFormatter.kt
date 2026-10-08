package org.agentos.sample.todo.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.agentos.sample.todo.data.Due

/** “今天 / 明天 / 昨天”这三个相对词（来自字符串资源；JVM 测试里换成中英文的假实现）。 */
interface DueTexts {
    val today: String
    val tomorrow: String
    val yesterday: String
}

/**
 * 截止时间的显示文案（R4 / R8）：日期和时间的**写法**交给 [bestPattern]（App 里是 `DateFormat.getBestDateTimePattern(locale, skeleton)`，
 * 所以“10月12日周一”“Mon, Oct 12”、12 / 24 小时都由系统和语言决定），这里不手拼单位字，也不判断 `locale.language`。
 * 相对词（今天 / 明天 / 昨天）来自 [texts]。纯函数，JVM 可测。
 *
 * @param bestPattern 骨架 → 本地化的 `java.time` 模式。本类用到的骨架：`MMMEd`、`yMMMEd`、`MMMd`、`Hm`（24 小时）、`hm`（12 小时）。
 */
class DueFormatter(
    private val locale: Locale,
    private val zone: ZoneId,
    private val is24Hour: Boolean,
    private val texts: DueTexts,
    private val bestPattern: (skeleton: String) -> String,
) {
    private fun fmt(skeleton: String): DateTimeFormatter = DateTimeFormatter.ofPattern(bestPattern(skeleton), locale)

    /** 今天 / 明天 / 昨天，其余是带星期的日期（跨年才带年份）。 */
    fun date(date: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, date)) {
        0L -> texts.today
        1L -> texts.tomorrow
        -1L -> texts.yesterday
        else -> fmt(if (date.year == today.year) "MMMEd" else "yMMMEd").format(date)
    }

    /** 编辑页里的完整日期（不用相对词）。 */
    fun fullDate(date: LocalDate): String = fmt("yMMMEd").format(date)

    fun time(epochMillis: Long): String = fmt(if (is24Hour) "Hm" else "hm").format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** 列表里的截止时间：全天只有日期，带时刻的“日期 时间”。 */
    fun due(due: Due, nowMillis: Long): String {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return when (due) {
            is Due.Day -> date(due.date, today)
            is Due.At -> date(Instant.ofEpochMilli(due.epochMillis).atZone(zone).toLocalDate(), today) + " " + time(due.epochMillis)
        }
    }

    /** 完成时间（编辑页脚注）：“今天 17:00”这类，跟 [due] 同一套写法。 */
    fun moment(epochMillis: Long, nowMillis: Long): String = due(Due.At(epochMillis), nowMillis)
}
