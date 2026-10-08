package org.agentos.sample.todo.data

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * 截止时间的解析、格式化、比较。纯函数，不碰 Android。
 *
 * 口径（与 SKILL.md、工具描述一致）：
 * - 输入：`YYYY-MM-DD`（全天）或带偏移的完整 ISO-8601 时间（`2026-10-12T17:00:00+08:00`、`...Z`）。没有偏移的本地时间（`2026-10-12T17:00`）
 *   和别的写法一律报错——不猜时区。
 * - 输出：带设备本地偏移的 ISO-8601（秒精度），全天的只有日期。
 * - 逾期：带时刻的，截止时刻早于现在；全天的，截止日早于本地今天（当天整天都不算逾期）。
 */
object DueTime {
    private val DATE_ONLY = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private const val MIN_YEAR = 1970
    private const val MAX_YEAR = 2200

    const val FORMAT_HINT = "due must be a date (YYYY-MM-DD) or a date-time with a UTC offset like 2026-10-12T17:00:00+08:00"

    /**
     * @param allDay `true`：把完整时间当成全天，取它**自己偏移下**的日期（写 `2026-10-12T17:00:00+08:00` 就是 10 月 12 日）；
     * `false`：必须带时刻，只给日期报错；`null`：看格式（只有日期 = 全天）。
     */
    fun parse(text: String, allDay: Boolean? = null): Due {
        val s = text.trim()
        val due = try {
            if (DATE_ONLY.matches(s)) {
                if (allDay == false) {
                    throw TodoException(TodoException.Kind.INVALID, "due_all_day is false but due \"${text.take(40)}\" has no time: $FORMAT_HINT.")
                }
                Due.Day(LocalDate.parse(s))
            } else {
                val at = OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                if (allDay == true) {
                    Due.Day(at.toLocalDate())
                } else {
                    // 秒级精度：丢掉毫秒，排序、比较、输出都一致
                    Due.At(at.toEpochSecond() * 1000)
                }
            }
        } catch (e: DateTimeParseException) {
            throw TodoException(TodoException.Kind.INVALID, "Invalid due \"${text.take(40)}\": $FORMAT_HINT.")
        } catch (e: ArithmeticException) {
            throw TodoException(TodoException.Kind.INVALID, "Invalid due \"${text.take(40)}\": $FORMAT_HINT.")
        }
        val year = when (due) {
            is Due.Day -> due.date.year
            is Due.At -> Instant.ofEpochMilli(due.epochMillis).atZone(ZoneId.of("UTC")).year
        }
        if (year !in MIN_YEAR..MAX_YEAR) {
            throw TodoException(TodoException.Kind.INVALID, "Invalid due \"${text.take(40)}\": the year must be between $MIN_YEAR and $MAX_YEAR.")
        }
        return due
    }

    fun localDate(due: Due, zone: ZoneId): LocalDate = when (due) {
        is Due.Day -> due.date
        is Due.At -> Instant.ofEpochMilli(due.epochMillis).atZone(zone).toLocalDate()
    }

    /** 排序用的时刻：全天的取当天 0 点（本地）。 */
    fun sortMillis(due: Due, zone: ZoneId): Long = when (due) {
        is Due.At -> due.epochMillis
        is Due.Day -> due.date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun isOverdue(due: Due, nowMillis: Long, zone: ZoneId): Boolean = when (due) {
        is Due.At -> due.epochMillis < nowMillis
        is Due.Day -> due.date.isBefore(Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate())
    }

    /** 带设备本地偏移的 ISO-8601（秒精度）；全天的只写日期。 */
    fun iso(due: Due, zone: ZoneId): String = when (due) {
        is Due.Day -> due.date.toString()
        is Due.At -> iso(due.epochMillis, zone)
    }

    fun iso(epochMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /**
     * `due_before` / `due_after` 的比较：[bound] 与待办的 [due] 都是带时刻的就比绝对时刻；只要有一边是全天（日期），
     * 两边都落到设备本地日期再比。返回负数 / 0 / 正数（due 相对 bound）。
     */
    fun compare(due: Due, bound: Due, zone: ZoneId): Int =
        if (due is Due.At && bound is Due.At) {
            due.epochMillis.compareTo(bound.epochMillis)
        } else {
            localDate(due, zone).compareTo(localDate(bound, zone))
        }
}
