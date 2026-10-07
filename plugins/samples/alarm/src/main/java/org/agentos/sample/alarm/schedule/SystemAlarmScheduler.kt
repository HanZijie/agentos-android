package org.agentos.sample.alarm.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import org.agentos.sample.alarm.ring.AlarmReceiver
import org.agentos.sample.alarm.ui.MainActivity

/**
 * 用 [AlarmManager.setAlarmClock] 登记闹钟：精确、会在 Doze 里唤醒设备、状态栏显示闹钟图标，
 * 并且（对持有 USE_EXACT_ALARM 的闹钟类 App）到点可以在后台启动前台服务。
 * 每个闹钟一个 PendingIntent，用 data URI（agentos-alarm://alarm/<id>）区分，重复登记会替换旧的。
 */
class SystemAlarmScheduler(context: Context) : AlarmScheduler {
    private val context = context.applicationContext
    private val alarmManager = this.context.getSystemService(AlarmManager::class.java)

    override fun schedule(alarmId: String, triggerAtMillis: Long) {
        val showIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent), firePendingIntent(alarmId))
            Log.i(TAG, "scheduled alarm $alarmId at $triggerAtMillis")
        } catch (e: SecurityException) {
            // USE_EXACT_ALARM 对闹钟类 App 是自动授予的；走到这里说明被系统策略收回，记日志，不让界面 / MCP 调用崩溃
            Log.e(TAG, "setAlarmClock denied for alarm $alarmId", e)
        }
    }

    override fun cancel(alarmId: String) {
        val pending = firePendingIntent(alarmId)
        alarmManager.cancel(pending)
        pending.cancel()
        Log.i(TAG, "cancelled alarm $alarmId")
    }

    private fun firePendingIntent(alarmId: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData("$SCHEME://alarm/$alarmId".toUri())
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val TAG = "AlarmSample"
        const val ACTION_FIRE = "org.agentos.sample.alarm.action.FIRE"
        const val SCHEME = "agentos-alarm"
    }
}
