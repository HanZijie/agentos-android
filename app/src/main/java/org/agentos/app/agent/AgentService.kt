package org.agentos.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * 运行时的前台服务（监督契约 S2 a）：`org.agentos.app/.agent.AgentService`，`:agent` 进程，不导出，
 * 前台服务类型 specialUse。有任务时在前台，没有任务时退出前台并停止；进程交给系统管理。
 *
 * 由谁启动：
 * - root 监督进程：`am start-foreground-service … -a org.agentos.action.SUPERVISOR_START --es org.agentos.extra.REASON <reason>`
 *   （boot、restart、promote、safe_mode_exit、upgrade）；
 * - 运行时自己：有任务要进前台时，[AgentProcess] 用 [ACTION_RUNTIME_TASK] 调 `startForegroundService`。
 *
 * 每次 onStartCommand 先 `startForeground`（不管有没有任务），再把命令交给 [RuntimeLifecycle]，
 * 空闲判断在那之后、并且只在恢复流程结束后做。返回 START_NOT_STICKY：死后由谁拉起只由监督进程决定。
 */
class AgentService : Service() {
    private lateinit var runtime: AgentProcess

    override fun onCreate() {
        super.onCreate()
        runtime = AgentProcess.get(this)
        runtime.attachService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val foregroundOk = goForeground()
        val reason = intent?.getStringExtra(EXTRA_REASON)
        val attempt = intent?.getIntExtra(EXTRA_ATTEMPT, 0) ?: 0
        Log.i(TAG, "start: action=${intent?.action} reason=$reason attempt=$attempt startId=$startId fg=$foregroundOk")
        runtime.onServiceCommand(this, startId, foregroundOk, intent?.action, reason, attempt)
        return START_NOT_STICKY
    }

    /**
     * 空闲时由运行时调用。先 `stopSelfResult(startId)`：已经有更新的启动请求时返回 false，服务继续；
     * 成功后退出前台（服务随后销毁）。可以在任何线程调用。
     */
    internal fun stopForRuntime(startId: Int): Boolean {
        if (!stopSelfResult(startId)) return false
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "idle: stopped (startId=$startId)")
        return true
    }

    private fun goForeground(): Boolean = try {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Agent 运行时", NotificationManager.IMPORTANCE_LOW))
        }
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("AgentOS 正在运行任务")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground failed: ${e.javaClass.simpleName}")
        false
    }

    override fun onDestroy() {
        runtime.onServiceDestroyed(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AgentService"

        /** 监督契约 S2 a：root 监督进程启动时带的 action 和 extras。 */
        const val ACTION_SUPERVISOR_START = "org.agentos.action.SUPERVISOR_START"
        const val EXTRA_REASON = "org.agentos.extra.REASON"
        const val EXTRA_ATTEMPT = "org.agentos.extra.ATTEMPT"

        /** 运行时自己有任务、要进前台时用的 action（只在本 App 内部使用）。 */
        const val ACTION_RUNTIME_TASK = "org.agentos.app.action.RUNTIME_TASK"

        private const val CHANNEL = "runtime"
        private const val NOTIFICATION_ID = 1
    }
}
