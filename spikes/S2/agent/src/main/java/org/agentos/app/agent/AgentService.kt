package org.agentos.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.agentos.app.BuildConfig
import org.agentos.app.spike.SpikeLog

/**
 * Contract item a: the runtime service. Foreground (specialUse) only while there are tasks.
 *
 * Every start goes through startForegroundService (root supervisor or the App itself), so
 * onStartCommand always calls startForeground first, then drops foreground and stops itself
 * when no task is left.
 */
class AgentService : Service() {
    companion object {
        const val ACTION_SUPERVISOR_START = "org.agentos.action.SUPERVISOR_START"
        const val EXTRA_REASON = "org.agentos.extra.REASON"
        const val EXTRA_ATTEMPT = "org.agentos.extra.ATTEMPT"

        const val ACTION_SYNC = "org.agentos.spike.action.SYNC"
        const val ACTION_START_TASK = "org.agentos.spike.action.START_TASK"
        const val ACTION_STOP_TASKS = "org.agentos.spike.action.STOP_TASKS"
        const val ACTION_CRASH = "org.agentos.spike.action.CRASH"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_BALLAST_MB = "ballast_mb"

        private const val CHANNEL = "runtime"
        private const val NOTIFICATION_ID = 1
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Runtime.service = this
        SpikeLog.i(this, "SERVICE_CREATE sticky=${BuildConfig.STICKY}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val reason = intent?.getStringExtra(EXTRA_REASON)
        val attempt = intent?.getIntExtra(EXTRA_ATTEMPT, 0) ?: 0
        SpikeLog.i(this, "START_COMMAND action=$action reason=$reason attempt=$attempt flags=$flags startId=$startId")

        goForeground()

        when (action) {
            ACTION_SUPERVISOR_START -> Runtime.ensureLoaded("supervisor:$reason")
            ACTION_START_TASK -> Runtime.add(
                intent.getLongExtra(EXTRA_DURATION_MS, 120_000L),
                intent.getIntExtra(EXTRA_BALLAST_MB, 0)
            )
            ACTION_STOP_TASKS -> Runtime.clear()
            ACTION_CRASH -> handler.post { throw IllegalStateException("S2 spike: requested crash") }
            null -> Runtime.ensureLoaded("system_restart")
            else -> Runtime.ensureLoaded(action)
        }
        Runtime.onChanged()
        return if (BuildConfig.STICKY) START_STICKY else START_NOT_STICKY
    }

    private fun goForeground() {
        if (Runtime.foreground) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Agent runtime", NotificationManager.IMPORTANCE_LOW)
            )
            val n = Notification.Builder(this, CHANNEL)
                .setContentTitle("S2: runtime has a task")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            Runtime.foreground = true
            Runtime.lastFgError = ""
            SpikeLog.i(this, "FOREGROUND ok")
        } catch (e: Exception) {
            Runtime.lastFgError = e.javaClass.simpleName
            SpikeLog.i(this, "FOREGROUND failed: $e")
        }
        Runtime.writeHeartbeat()
    }

    /** Called by Runtime when the last task is gone. */
    fun onIdle() {
        if (Runtime.foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            Runtime.foreground = false
            SpikeLog.i(this, "FOREGROUND stopped (idle)")
        }
        Runtime.writeHeartbeat()
        stopSelf()
    }

    override fun onDestroy() {
        SpikeLog.i(this, "SERVICE_DESTROY")
        Runtime.foreground = false
        Runtime.service = null
        Runtime.writeHeartbeat()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
