package org.agentos.sample.alarm.data

import java.time.ZoneId
import java.time.ZonedDateTime
import org.agentos.sample.alarm.schedule.AlarmScheduler

/** 记录每次登记 / 取消的假调度器。 */
class FakeScheduler : AlarmScheduler {
    val scheduled = LinkedHashMap<String, Long>()
    val cancelled = mutableListOf<String>()

    override fun schedule(alarmId: String, triggerAtMillis: Long) {
        scheduled[alarmId] = triggerAtMillis
    }

    override fun cancel(alarmId: String) {
        scheduled.remove(alarmId)
        cancelled += alarmId
    }
}

val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")

/** 2026-10-07 是星期三。 */
fun at(hour: Int, minute: Int, zone: ZoneId = SHANGHAI, day: Int = 7, month: Int = 10, second: Int = 0): ZonedDateTime =
    ZonedDateTime.of(2026, month, day, hour, minute, second, 0, zone)

class TestEnv(start: ZonedDateTime = at(8, 0)) {
    val time = FixedTimeSource(start)
    val scheduler = FakeScheduler()
    val store = InMemoryAlarmStore()
    val repository = AlarmRepository(store, scheduler, time)

    fun millis(t: ZonedDateTime): Long = t.toInstant().toEpochMilli()
}
