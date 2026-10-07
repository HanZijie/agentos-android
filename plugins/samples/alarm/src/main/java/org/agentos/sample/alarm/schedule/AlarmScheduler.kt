package org.agentos.sample.alarm.schedule

/**
 * 向系统登记 / 取消“某个闹钟的下一次响铃”。每个闹钟同一时刻只有一个待响时刻（含贪睡）。
 * 生产实现是 [SystemAlarmScheduler]（AlarmManager.setAlarmClock）；JVM 测试用假实现记录调用。
 */
interface AlarmScheduler {
    fun schedule(alarmId: String, triggerAtMillis: Long)

    fun cancel(alarmId: String)
}
