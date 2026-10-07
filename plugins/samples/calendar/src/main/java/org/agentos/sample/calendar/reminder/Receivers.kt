package org.agentos.sample.calendar.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.agentos.sample.calendar.CalendarGraph
import kotlin.concurrent.thread

/** AlarmManager 触发：发通知并排下一条。数据库读取放到后台线程，用 goAsync 撑到完成。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "calendar-reminder") {
            try {
                CalendarGraph.scheduler(context).onAlarm()
            } catch (e: Exception) {
                Log.e(ReminderScheduler.TAG, "reminder alarm failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}

/** 开机、时区变化、系统时间变化、App 升级后：重新排提醒（并补发刚错过的）。 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "calendar-resched") {
            try {
                Log.i(ReminderScheduler.TAG, "system event ${intent.action}: rescheduling reminders")
                CalendarGraph.scheduler(context).reschedule(catchUp = true)
            } catch (e: Exception) {
                Log.e(ReminderScheduler.TAG, "reschedule failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
