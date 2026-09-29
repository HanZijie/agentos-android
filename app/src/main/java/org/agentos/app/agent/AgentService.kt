package org.agentos.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.util.Log
import org.agentos.app.settings.Desktop
import org.agentos.app.settings.SettingsActivity

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
 *
 * 电脑端接入打开期间（architecture F11 第 4 点）没有任务也留在前台，通知换成“电脑端接入已开启”，点通知打开设置页，
 * 通知上的“关闭”经 [DesktopAccessOffReceiver] 关掉开关（与设置页的关闭相同：断开连接、作废配对），随后照常退出前台。
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
        startForeground(
            NOTIFICATION_ID,
            buildNotification(runtime.desktop.isEnabled(), runtime.runtime.runState.value.busy),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground failed: ${e.javaClass.simpleName}")
        false
    }

    /**
     * 电脑端接入的开关或忙闲变了：换通知。关掉且没有任务时服务马上要停（宽限期后），不再换成“正在运行任务”。
     * 可以在任何线程调用。
     */
    internal fun refreshNotification(desktopOn: Boolean, tasks: Int) {
        if (!desktopOn && tasks == 0) return
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(desktopOn, tasks > 0)) }
            .onFailure { Log.w(TAG, "notification update failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 只有任务：“AgentOS 正在运行任务”。电脑端接入打开时标题始终是“电脑端接入已开启”（用户一眼看得出它开着），
     * 同时有任务时副标题加“正在运行任务”（两种前台理由并存）。
     */
    private fun buildNotification(desktopOn: Boolean, busy: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (!desktopOn) {
            ensureChannel(nm, CHANNEL, "Agent 运行时")
            return Notification.Builder(this, CHANNEL)
                .setContentTitle("AgentOS 正在运行任务")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()
        }
        // 用词与设置页（D，SettingsActivity 的“电脑端接入”一节和关闭确认）一致
        ensureChannel(nm, CHANNEL_DESKTOP, DESKTOP_CHANNEL_NAME)
        val settings = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        val off = PendingIntent.getBroadcast(
            this, 0, Intent(this, DesktopAccessOffReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_DESKTOP)
            .setContentTitle(DESKTOP_TITLE)
            .setContentText(DESKTOP_TEXT)
            .setSubText(if (busy) DESKTOP_BUSY else null)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(settings)
            .addAction(Notification.Action.Builder(null as Icon?, DESKTOP_OFF, off).build())
            .build()
    }

    private fun ensureChannel(nm: NotificationManager, id: String, name: String) {
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW))
        }
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

        /**
         * 电脑端接入打开期间的通知（F11 第 4 点）。标题和按钮用设置页（D，Desktop.kt）的常量，开关旁的说明引用的就是它们；
         * 正文沿用设置页的开关文案（“允许电脑经 adb 连接”）和关闭确认框的说法。
         */
        private const val CHANNEL_DESKTOP = "desktop_access"
        const val DESKTOP_CHANNEL_NAME = "电脑端接入"
        const val DESKTOP_TITLE = Desktop.NOTIFICATION_TITLE
        const val DESKTOP_TEXT = "允许电脑经 adb 连接。关闭会断开连接，并作废已配对的电脑。"
        const val DESKTOP_OFF = Desktop.NOTIFICATION_ACTION
        const val DESKTOP_BUSY = "正在运行任务"
    }
}
