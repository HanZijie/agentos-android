package org.agentos.sample.alarm.ring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.agentos.sample.alarm.R
import org.agentos.sample.alarm.data.Alarm
import org.agentos.sample.alarm.ui.MainActivity
import org.agentos.sample.alarm.ui.RingActivity
import org.agentos.sample.alarm.ui.formatClock

/** 通知渠道与响铃 / 错过闹钟的通知。 */
object AlarmNotifications {
    const val CHANNEL_RINGING = "ringing"
    const val CHANNEL_MISSED = "missed"
    const val ID_RINGING = 1001
    const val ID_MISSED = 1002

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RINGING,
                context.getString(R.string.channel_ringing),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_ringing_desc)
                // 铃声和振动由响铃服务自己播放（可循环、渐强），通知本身静音
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MISSED,
                context.getString(R.string.channel_missed),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_missed_desc) },
        )
    }

    /** 响铃中的前台服务通知：全屏意图（锁屏上直接弹响铃页）+ 关闭 / 贪睡两个动作。 */
    fun ringing(context: Context, alarm: Alarm): Notification {
        val ringIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = alarm.label.ifBlank { context.getString(R.string.alarm_default_title) }
        return NotificationCompat.Builder(context, CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(formatClock(context, alarm.hour, alarm.minute))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(ringIntent)
            .setFullScreenIntent(ringIntent, true)
            .addAction(0, context.getString(R.string.action_snooze), serviceAction(context, AlarmRingService.ACTION_SNOOZE, 2))
            .addAction(0, context.getString(R.string.action_dismiss), serviceAction(context, AlarmRingService.ACTION_DISMISS, 3))
            .build()
    }

    /** 过渡用：服务被启动但闹钟已不存在时，仍需先 startForeground 才能安全退出。 */
    fun placeholder(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(context.getString(R.string.alarm_default_title))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    /** 关机期间或响铃超时没被处理的闹钟。 */
    fun postMissed(context: Context, alarms: List<Alarm>) {
        if (alarms.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val lines = alarms.map { a ->
            val time = formatClock(context, a.hour, a.minute)
            if (a.label.isBlank()) time else "$time  ${a.label}"
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val style = NotificationCompat.InboxStyle().also { s -> lines.forEach { s.addLine(it) } }
        manager.notify(
            ID_MISSED,
            NotificationCompat.Builder(context, CHANNEL_MISSED)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(context.getString(R.string.missed_title))
                .setContentText(lines.first())
                .setStyle(style)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun serviceAction(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, AlarmRingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
