package org.agentos.sample.alarm.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 当前时间的来源。生产用系统时钟（每次读取当时的默认时区，时区变化后自动跟上）；测试用固定时间。 */
interface TimeSource {
    fun now(): ZonedDateTime
}

object SystemTimeSource : TimeSource {
    override fun now(): ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())
}

/** 测试用：时间由测试推进。 */
class FixedTimeSource(var current: ZonedDateTime) : TimeSource {
    override fun now(): ZonedDateTime = current
}

/**
 * 下一次响铃时刻的计算。纯函数，不碰系统：
 * - 取“下一个严格晚于 now 的 本地 HH:mm”；重复闹钟再要求星期在 [Alarm.days] 里。
 * - 用 [ZonedDateTime.of]（本地日期 + 时间 + 时区）构造，DST 间隙里不存在的时刻自动顺延，重叠时刻取较早的偏移。
 * - 贪睡中的闹钟：贪睡到点时刻与正常的下一次取较早者。
 */
object NextFire {
    fun compute(alarm: Alarm, now: ZonedDateTime): ZonedDateTime? {
        val zone = now.zone
        val regular = if (alarm.enabled) nextOccurrence(alarm.hour, alarm.minute, alarm.days, now) else null
        val snooze = alarm.snoozedUntil
            ?.takeIf { it > now.toInstant().toEpochMilli() }
            ?.let { ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
        return when {
            regular == null -> snooze
            snooze == null -> regular
            else -> if (snooze.isBefore(regular)) snooze else regular
        }
    }

    fun nextOccurrence(hour: Int, minute: Int, days: Set<DayOfWeek>, now: ZonedDateTime): ZonedDateTime {
        val zone = now.zone
        val today = now.toLocalDate()
        // 重复闹钟最多往后看 7 天就一定能命中；只响一次的最多看 1 天
        for (offset in 0..7) {
            val date = today.plusDays(offset.toLong())
            if (days.isNotEmpty() && date.dayOfWeek !in days) continue
            val candidate = ZonedDateTime.of(date, LocalTime.of(hour, minute), zone)
            if (candidate.isAfter(now)) return candidate
        }
        // 不可达（days 非空时 8 天内必有一天命中）；兜底给明天
        return ZonedDateTime.of(today.plusDays(1), LocalTime.of(hour, minute), zone)
    }
}
