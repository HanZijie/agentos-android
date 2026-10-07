package org.agentos.sample.calendar.reminder

import org.agentos.sample.calendar.data.EventSeries
import org.agentos.sample.calendar.data.Occurrence
import org.agentos.sample.calendar.data.Occurrences
import java.time.ZoneId

/** 一次具体的提醒：某个日程的某一次出现，在 [fireAtMs] 提醒。 */
data class Reminder(
    val seriesId: String,
    val occurrenceId: String,
    val title: String,
    val location: String,
    val allDay: Boolean,
    val startMs: Long,
    val endMs: Long,
    val minutesBefore: Int,
    val fireAtMs: Long,
) {
    /** 同一次出现的同一条提醒始终得到同一个通知 id，重复触发只会刷新通知，不会叠加。 */
    val notificationId: Int get() = "$occurrenceId/$minutesBefore".hashCode()
}

/**
 * 提醒的计算（纯函数）。定时日程的提醒相对开始时刻；全天日程相对“第一天 09:00（设备时区）”。
 * 区间约定为 (afterMs, untilMs]：已经过去的提醒不会补发，刚好在 afterMs 的也不算。
 */
object ReminderPlanner {
    private const val DAY_MS = 86_400_000L
    const val ALL_DAY_ANCHOR_HOUR = 9

    fun anchor(o: Occurrence, zone: ZoneId): Long =
        if (o.allDay) o.firstDay.atTime(ALL_DAY_ANCHOR_HOUR, 0).atZone(zone).toInstant().toEpochMilli() else o.startMs

    fun between(events: Iterable<EventSeries>, afterMs: Long, untilMs: Long, zone: ZoneId): List<Reminder> {
        if (untilMs <= afterMs) return emptyList()
        val out = ArrayList<Reminder>()
        for (s in events) {
            if (s.reminders.isEmpty()) continue
            val maxLead = s.reminders.max() * 60_000L
            // 一次出现的锚点不会早于它的开始、不会晚于开始后一天（全天日程 09:00），所以把区间两头各放宽一天
            for (o in Occurrences.expand(s, afterMs - DAY_MS, untilMs + maxLead + DAY_MS, zone)) {
                val anchor = anchor(o, zone)
                for (m in s.reminders) {
                    val fire = anchor - m * 60_000L
                    if (fire > afterMs && fire <= untilMs) {
                        out += Reminder(s.id, o.id, s.title, s.location, s.allDay, o.startMs, o.endMs, m, fire)
                    }
                }
            }
        }
        return out.sortedWith(compareBy({ it.fireAtMs }, { it.title }, { it.minutesBefore }))
    }

    /** 之后最早的一条提醒；[horizonDays] 天内没有则返回 null。 */
    fun next(events: Iterable<EventSeries>, afterMs: Long, zone: ZoneId, horizonDays: Long = 400): Reminder? =
        between(events, afterMs, afterMs + horizonDays * DAY_MS, zone).firstOrNull()
}
