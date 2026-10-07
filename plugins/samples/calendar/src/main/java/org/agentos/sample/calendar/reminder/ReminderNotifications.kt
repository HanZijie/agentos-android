package org.agentos.sample.calendar.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import org.agentos.sample.calendar.MainActivity
import org.agentos.sample.calendar.R
import java.time.Instant
import java.time.ZoneId

object ReminderNotifications {
    const val CHANNEL_ID = "event_reminders"
    const val EXTRA_EVENT_ID = "event_id"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_reminders_desc)
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(context: Context, r: Reminder, zone: ZoneId) {
        if (!canPost(context)) return
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_EVENT_ID, r.occurrenceId)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, r.notificationId, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val fmt = ReminderText(context)
        val whenText = fmt.timeRange(r, zone)
        val body = if (r.location.isBlank()) whenText else "$whenText · ${r.location}"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_event)
            .setContentTitle(r.title)
            .setContentText(body)
            .setSubText(fmt.lead(r))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFFE4572E.toInt())
            .setWhen(if (r.allDay) r.fireAtMs else r.startMs)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(r.notificationId, notification)
    }
}

/** 通知里用的文字。 */
class ReminderText(private val context: Context) {
    fun lead(r: Reminder): String = when {
        r.allDay -> context.getString(R.string.reminder_all_day)
        r.minutesBefore == 0 -> context.getString(R.string.reminder_now)
        r.minutesBefore % (7 * 1440) == 0 -> context.resources.getQuantityString(R.plurals.reminder_in_weeks, r.minutesBefore / (7 * 1440), r.minutesBefore / (7 * 1440))
        r.minutesBefore % 1440 == 0 -> context.resources.getQuantityString(R.plurals.reminder_in_days, r.minutesBefore / 1440, r.minutesBefore / 1440)
        r.minutesBefore % 60 == 0 -> context.resources.getQuantityString(R.plurals.reminder_in_hours, r.minutesBefore / 60, r.minutesBefore / 60)
        else -> context.resources.getQuantityString(R.plurals.reminder_in_minutes, r.minutesBefore, r.minutesBefore)
    }

    fun timeRange(r: Reminder, zone: ZoneId): String {
        if (r.allDay) return context.getString(R.string.all_day)
        val locale = context.resources.configuration.locales[0]
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, if (android.text.format.DateFormat.is24HourFormat(context)) "Hm" else "jm")
        val f = java.time.format.DateTimeFormatter.ofPattern(pattern, locale)
        val start = Instant.ofEpochMilli(r.startMs).atZone(zone)
        val end = Instant.ofEpochMilli(r.endMs).atZone(zone)
        return if (r.endMs > r.startMs) "${f.format(start)}–${f.format(end)}" else f.format(start)
    }
}
