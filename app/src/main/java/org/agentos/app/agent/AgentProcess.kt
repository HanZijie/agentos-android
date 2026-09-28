package org.agentos.app.agent

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.agentos.acp.AcpAndroid
import org.agentos.runtime.AgentRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * `:agent` 进程的单例：持有宿主层（`org.agentos.runtime.AgentRuntime`），把它的 runState 接到
 * [RuntimeLifecycle]（前台服务、心跳、wake lock），执行每个进程一次的恢复流程（`runtime.start()`），持有 ACP 连接。
 * 由 `:agent` 里任何一个服务第一次创建时初始化（AgentService、AcpService、AgentControlService，谁先谁后都一样）。
 *
 * W6 骨架：宿主层是 [PlaceholderAgentRuntime]（假 Agent，没有 Store）；A3 进 main 后换成
 * `AgentRuntimes.create(RuntimeDependencies(HostPortImpl(…), agentCoreFactory))`，其余接线不变。
 */
class AgentProcess private constructor(val app: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("agent-process"))
    val heartbeat = HeartbeatWriter(app)
    val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val startedAtElapsed = SystemClock.elapsedRealtime()

    /** 宿主层。A3 之后换成真实现（见类注释）。 */
    val runtime: AgentRuntime = PlaceholderAgentRuntime(beforeStart = { testRecoveryDelay() })

    @Volatile private var service: AgentService? = null
    private val startCommands = ConcurrentLinkedDeque<JSONObject>()
    @Volatile private var lastExit: JSONObject? = null
    @Volatile private var userStopped = false
    @Volatile private var recoveryMs = -1L
    @Volatile private var recoveryError: String? = null

    private val wakeLock: PowerManager.WakeLock =
        app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agentos:runtime-task")
            .apply { setReferenceCounted(false) }
    @Volatile private var ticker: Job? = null

    val lifecycle = RuntimeLifecycle(object : RuntimeLifecycle.Port {
        override fun taskCount(): Int = runtime.runState.value.let { it.activeTasks + it.queuedTasks }

        override fun requestServiceStart(): Boolean = try {
            app.startForegroundService(Intent(app, AgentService::class.java).setAction(AgentService.ACTION_RUNTIME_TASK))
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // 预期的情况（后台、未豁免电池优化）：不重试，心跳写 tasks>0 fg=0，由 root 监督进程 promote
            Log.i(TAG, "startForegroundService not allowed; leaving it to the supervisor (promote)")
            false
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService failed: ${e.javaClass.simpleName}")
            false
        }

        override fun stopService(startId: Int): Boolean = service?.stopForRuntime(startId) ?: true

        override fun publish(status: RuntimeLifecycle.Status) = heartbeat.write(status)

        override fun onBusyChanged(busy: Boolean) = setBusy(busy)
    })

    val acp = AcpConnections(this)

    /** 电脑端接入（W9，A lane 的 DesktopGateway.kt）：开关默认关闭，打开后监听抽象 socket agentos-acp。 */
    val desktop = DesktopGateway(this)

    init {
        AcpAndroid.ensureInitialized()
        installCrashHandler()
        // 先订阅任务数，再开始恢复：恢复期间登记的任务不会漏掉
        scope.launch(CoroutineName("run-state")) {
            runtime.runState.collect { lifecycle.onTasksChanged() }
        }
        startRecovery()
    }

    // ------------------------------------------------------------------ AgentService 回调

    internal fun attachService(s: AgentService) {
        service = s
    }

    internal fun onServiceCommand(s: AgentService, startId: Int, foregroundOk: Boolean, action: String?, reason: String?, attempt: Int) {
        service = s
        startCommands.addLast(
            JSONObject().put("action", action ?: JSONObject.NULL).put("reason", reason ?: JSONObject.NULL)
                .put("attempt", attempt).put("startId", startId).put("foregroundOk", foregroundOk)
                .put("atMs", SystemClock.elapsedRealtime())
        )
        while (startCommands.size > 20) startCommands.pollFirst()
        lifecycle.onServiceCommand(startId, foregroundOk)
    }

    internal fun onServiceDestroyed(s: AgentService) {
        if (service === s) service = null
        lifecycle.onServiceDestroyed()
    }

    // ------------------------------------------------------------------ 恢复

    private fun startRecovery() {
        lifecycle.onRecoveryStarted()
        scope.launch(Dispatchers.IO + CoroutineName("recovery")) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val exit = readLastAgentExit()
                userStopped = RecoveryPolicy.userStopped(exit?.optInt("reason"))
                if (userStopped) Log.i(TAG, "previous :agent was stopped by the user")
                // 打开 Store、迁移、恢复（F8）。“用户主动停止过就不继续任务”还没有入口交给宿主层，见 RecoveryPolicy。
                runtime.start()
            } catch (e: Exception) {
                recoveryError = e.javaClass.simpleName
                Log.e(TAG, "recovery failed: ${e.javaClass.simpleName}")
            } finally {
                recoveryMs = SystemClock.elapsedRealtime() - t0
                lifecycle.onRecoveryFinished()
            }
        }
    }

    private fun readLastAgentExit(): JSONObject? {
        val am = app.getSystemService(ActivityManager::class.java)
        val name = agentProcessName(app)
        val info = am.getHistoricalProcessExitReasons(app.packageName, 0, 16)
            .filter { it.processName == name && it.pid != Process.myPid() }
            .maxByOrNull { it.timestamp } ?: return null
        // 不带 description：崩溃时里面可能有异常信息
        return JSONObject().put("pid", info.pid).put("reason", info.reason).put("reasonName", exitReasonName(info.reason))
            .put("status", info.status).put("importance", info.importance).put("timestamp", info.timestamp)
            .put("pssKb", info.pss).put("rssKb", info.rss)
            .also { lastExit = it }
    }

    /** 只在 debug 包里生效：设备用例用它把恢复流程拉长，复现“恢复中登记任务”的先后顺序。 */
    private suspend fun testRecoveryDelay() {
        if (!debuggable) return
        val f = File(app.createDeviceProtectedStorageContext().filesDir, TEST_RECOVERY_DELAY_FILE)
        val ms = runCatching { f.readText().trim().toLong() }.getOrNull() ?: return
        runCatching { f.delete() }
        delay(ms.coerceIn(0, 10_000))
    }

    // ------------------------------------------------------------------ 忙闲

    private fun setBusy(busy: Boolean) {
        if (busy) {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            if (ticker?.isActive != true) {
                ticker = scope.launch(CoroutineName("heartbeat-refresh")) {
                    while (isActive) {
                        delay(HEARTBEAT_REFRESH_MS)
                        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                        lifecycle.refresh()
                    }
                }
            }
        } else {
            ticker?.cancel()
            ticker = null
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // 不进 lifecycle 的锁（崩溃的线程可能正拿着它），用最后写出的状态
            runCatching {
                heartbeat.write(heartbeat.lastStatus ?: RuntimeLifecycle.Status(0, false, "starting"), "crashed")
            }
            previous?.uncaughtException(t, e)
        }
    }

    // ------------------------------------------------------------------ 诊断

    fun runtimeStatus(): JSONObject {
        val s = lifecycle.status()
        return JSONObject()
            .put("pid", Process.myPid())
            .put("phase", lifecycle.phase().name)
            .put("tasks", s.tasks)
            .put("foreground", s.foreground)
            .put("state", s.state)
            .put("serviceRunning", lifecycle.isServiceRunning())
            .put("foregroundDenied", lifecycle.foregroundDenied)
            .put("uptimeMs", SystemClock.elapsedRealtime() - startedAtElapsed)
    }

    /** 诊断信息，不含 key、prompt 和会话内容。 */
    fun diagnostics(): JSONObject {
        val hbText = heartbeat.read()
        val rs = runtime.runState.value
        return JSONObject()
            .put("runtime", runtimeStatus()
                .put("foregroundDeniedCount", lifecycle.foregroundDeniedCount)
                .put("idleStops", lifecycle.idleStops)
                .put("recoveryMs", recoveryMs)
                .put("recoveryError", recoveryError ?: JSONObject.NULL)
                .put("userStopped", userStopped)
                .put("wakeLockHeld", wakeLock.isHeld)
                .put("debuggable", debuggable)
                .put("implementation", runtime.javaClass.simpleName)
                .put("runState", JSONObject().put("activeTasks", rs.activeTasks).put("queuedTasks", rs.queuedTasks)
                    .put("recoveryPending", rs.recoveryPending).put("core", rs.core.toString())
                    .put("lastError", rs.lastError?.code?.wire ?: JSONObject.NULL)))
            .put("startCommands", JSONArray(startCommands.toList()))
            .put("lastExit", lastExit ?: JSONObject.NULL)
            .put("heartbeat", JSONObject()
                .put("path", heartbeat.file.absolutePath)
                .put("fields", hbText?.let { JSONObject(HeartbeatFormat.parse(it) as Map<*, *>) } ?: JSONObject.NULL)
                .put("writes", heartbeat.writes)
                .put("lastError", heartbeat.lastError ?: JSONObject.NULL))
            .put("acp", acp.stats())
            .put("desktop", desktop.stats())
    }

    companion object {
        private const val TAG = "AgentProcess"
        const val HEARTBEAT_REFRESH_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L

        /** debug 包专用：DE 存储 files/ 下这个文件里的毫秒数，会在下一次恢复时作为额外延迟（读后删除）。 */
        const val TEST_RECOVERY_DELAY_FILE = "test/recovery_delay_ms"

        // 只持有 applicationContext，不会泄漏 Activity / Service
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: AgentProcess? = null

        fun agentProcessName(context: Context) = "${context.packageName}:agent"

        /** 只能在 `:agent` 进程里调用。 */
        fun get(context: Context): AgentProcess {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val proc = Application.getProcessName()
                check(proc == agentProcessName(context)) { "AgentProcess must run in the :agent process, not $proc" }
                return AgentProcess(context.applicationContext).also { instance = it }
            }
        }

        fun exitReasonName(r: Int): String = when (r) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
            else -> "code_$r"
        }
    }
}
