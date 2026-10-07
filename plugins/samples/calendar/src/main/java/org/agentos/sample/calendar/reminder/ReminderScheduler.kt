package org.agentos.sample.calendar.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import org.agentos.sample.calendar.data.CalendarRepository

/**
 * 用 AlarmManager 排“下一条提醒”：始终只挂一个精确闹钟，指向全部日程里最早的未触发提醒；
 * 触发时发出所有到点的通知，再排下一条。数据变化、开机、时区或系统时间变化、升级后都会重新排。
 */
class ReminderScheduler(private val context: Context, private val repo: CalendarRepository) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    /**
     * @param catchUp 为 true 时先补发“上次处理之后、现在之前”错过的提醒（最多回看 [MAX_CATCH_UP_MS]）。
     *                开机、进程冷启动、时间变化时用；日程被改动时不补发（改出来的过去提醒不该弹）。
     */
    @Synchronized
    fun reschedule(catchUp: Boolean = false) {
        val now = repo.time.nowMs()
        if (catchUp) fireDue(now)
        prefs.edit().putLong(KEY_LAST_PROCESSED, now).apply()
        val next = ReminderPlanner.next(repo.events.value, now, repo.zone)
        val pending = pendingIntent()
        if (next == null) {
            alarmManager.cancel(pending)
            pending.cancel() // 连 PendingIntent 一起作废：isAlarmRegistered() 才能如实反映“没有挂闹钟”
            prefs.edit().remove(KEY_ARMED_AT).remove(KEY_ARMED_ID).remove(KEY_ARMED_LEAD).apply()
            Log.i(TAG, "no upcoming reminders; alarm cancelled")
            return
        }
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.fireAtMs, pending)
        } catch (e: SecurityException) {
            // 没有精确闹钟权限时退化为非精确（USE_EXACT_ALARM 通常是安装时授予的）
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.fireAtMs, pending)
        }
        prefs.edit().putLong(KEY_ARMED_AT, next.fireAtMs).putString(KEY_ARMED_ID, next.occurrenceId).putInt(KEY_ARMED_LEAD, next.minutesBefore).apply()
        Log.i(TAG, "next reminder '${next.title}' (-${next.minutesBefore} min) at ${next.fireAtMs}")
    }

    /** 系统里是否挂着我们的提醒闹钟（AlarmManager 没有查询接口，用 FLAG_NO_CREATE 看 PendingIntent 是否存在）。 */
    fun isAlarmRegistered(): Boolean {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMINDER)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null
    }

    /**
     * 最近一次交给 AlarmManager 的那条提醒（触发时刻、日程出现的 id、提前分钟）；没有记录则为 null。仅供调试读取。
     * 记录和系统里是否真的挂着是两回事：用 [isAlarmRegistered] 单独核对。
     */
    fun armed(): Triple<Long, String, Int>? {
        val at = prefs.getLong(KEY_ARMED_AT, -1L)
        val id = prefs.getString(KEY_ARMED_ID, null)
        if (at < 0 || id == null) return null
        return Triple(at, id, prefs.getInt(KEY_ARMED_LEAD, 0))
    }

    /** 闹钟触发：发出到点的提醒，然后排下一条。 */
    @Synchronized
    fun onAlarm() {
        fireDue(repo.time.nowMs())
        reschedule(catchUp = false)
    }

    private fun fireDue(now: Long) {
        val last = prefs.getLong(KEY_LAST_PROCESSED, now)
        val from = maxOf(last, now - MAX_CATCH_UP_MS)
        val due = ReminderPlanner.between(repo.events.value, from, now, repo.zone)
        for (r in due) {
            ReminderNotifications.post(context, r, repo.zone)
            Log.i(TAG, "reminder posted: '${r.title}' (-${r.minutesBefore} min)")
        }
        prefs.edit().putLong(KEY_LAST_PROCESSED, now).apply()
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMINDER)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val TAG = "CalendarReminder"
        const val ACTION_REMINDER = "org.agentos.sample.calendar.action.REMINDER"
        private const val KEY_LAST_PROCESSED = "last_processed_ms"
        private const val KEY_ARMED_AT = "armed_at_ms"
        private const val KEY_ARMED_ID = "armed_occurrence_id"
        private const val KEY_ARMED_LEAD = "armed_minutes_before"
        private const val MAX_CATCH_UP_MS = 30 * 60_000L
    }
}
