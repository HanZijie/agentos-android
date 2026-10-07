package org.agentos.sample.alarm.ring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.agentos.sample.alarm.AlarmGraph
import org.agentos.sample.alarm.schedule.SystemAlarmScheduler

/** 系统闹钟到点：持一小段 CPU 唤醒锁，交给前台服务响铃。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SystemAlarmScheduler.ACTION_FIRE) return
        val id = intent.data?.lastPathSegment ?: return
        Log.i(SystemAlarmScheduler.TAG, "alarm $id fired, starting ring service")
        // 服务接手前不让 CPU 睡着；服务自己随后会持有更长的唤醒锁
        context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agentos-alarm:fire")
            .acquire(15_000)
        ContextCompat.startForegroundService(context, AlarmRingService.fireIntent(context, id))
    }
}

/**
 * 开机 / 升级 / 时间或时区变化后，重新向系统登记所有闹钟。
 * 开机和升级时还会识别“关机期间错过的闹钟”并发通知；时间 / 时区变化只重算（系统已把到点的闹钟投递出去）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val detectMissed = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> true
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                // 让进程内的默认时区跟上系统，否则下一次响铃时刻还按旧时区算
                java.util.TimeZone.setDefault(null)
                false
            }
            else -> return
        }
        val missed = AlarmGraph.get(context).repository.rescheduleAll(detectMissed)
        Log.i(SystemAlarmScheduler.TAG, "${intent.action}: rescheduled all alarms, missed=${missed.size}")
        AlarmNotifications.postMissed(context, missed)
    }
}
