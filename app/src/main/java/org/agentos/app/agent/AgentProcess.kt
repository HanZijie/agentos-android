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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.agentos.acp.AcpAndroid
import org.agentos.app.BuildConfig
import org.agentos.app.agent.supervisor.SupervisorStatus
import org.agentos.app.agent.supervisor.SupervisorStatusReceiver
import org.agentos.app.agent.acp.AcpAccessPolicy
import org.agentos.app.agent.acp.AcpDecision
import org.agentos.app.agent.acp.CallerEntry
import org.agentos.app.agent.acp.CallerListener
import org.agentos.app.agent.acp.CallerRegistry
import org.agentos.app.agent.acp.CallerState
import org.agentos.app.agent.acp.PackageCallerResolver
import org.agentos.extensions.registry.FileBackedTextFile
import org.agentos.runtime.AgentRuntime
import org.agentos.runtime.AgentRuntimes
import org.agentos.runtime.RuntimeConfig
import org.agentos.runtime.RuntimeEngine
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.pi.ModelCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * `:agent` 进程的单例：持有宿主层（`org.agentos.runtime.AgentRuntime`），把它的 runState 接到
 * [RuntimeLifecycle]（前台服务、心跳、wake lock），执行每个进程一次的恢复流程（`runtime.start()`），持有 ACP 连接。
 * 由 `:agent` 里任何一个服务第一次创建时初始化（AgentService、AcpService、AgentControlService，谁先谁后都一样）。
 *
 * 宿主层是 A 的 RuntimeEngine（ACP、Store、调度、恢复），HostPort 是 [HostPortImpl]，Agent core 是 B 的 Pi
 * （[PiAgentCores]：PiAdapter + QuickJsEngine，模型请求经 HostFetch，key 来自 [KeystoreSecrets]）。
 */
class AgentProcess private constructor(val app: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("agent-process"))
    val heartbeat = HeartbeatWriter(app)
    val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val startedAtElapsed = SystemClock.elapsedRealtime()

    // ------------------------------------------------------------------ HostPort（W6）

    val store = AndroidStore(app)

    /** 运行时空闲时换下来的 key 立即丢弃；有任务时留到任务数归零（见 setBusy）。 */
    val secrets = KeystoreSecrets(AndroidKeystoreCipher()) { runtime.runState.value.busy.not() }
    private val runtimeLog = AndroidRuntimeLog(secrets::redact)
    val models = ModelSources(
        dir = File(app.filesDir, BYOK_DIR),
        secrets = secrets,
        catalogSource = { app.assets.open(MODEL_CATALOG_ASSET).bufferedReader().use { ModelCatalog.parse(it.readText()) } },
        log = runtimeLog,
    )
    /** 自动选会话的 Jev endpoint 和 key（自己的 Keystore 主密钥）；没有 key 时路由回退为新建会话（jev_unconfigured）。 */
    val jev = JevSources(File(app.filesDir, JEV_DIR), AndroidKeystoreCipher(JevSources.JEV_ALIAS), secrets, runtimeLog)
    val environment = AndroidEnvironment()

    /** Extension Host（`:ext`）的代理：工具目录、调用、用户策略的镜像（C7b，docs/extensions.md 第 9 节）。 */
    val extensions = ExtensionClient(app, runtimeLog)
    /**
     * 工具调用的用户确认（D5.2，architecture F5）：[ConsentCoordinator]（排队、60 秒超时、选项校验、“始终允许”写回）+ [consentBridge]
     * （前台把待确认推给主进程的对话框，后台发带“允许 / 拒绝”的通知）。release 和 debug 都生效。
     * debug 构建再用 A 的 [AutoConsentResponder] 包住界面：ConsentDebugReceiver 设置 mode 后无人值守地回答；mode=off 时和 release 一样交给界面。
     */
    val consentBridge = org.agentos.app.agent.consent.ConsentBridge(app)
    val autoConsent: org.agentos.runtime.consent.AutoConsentResponder? =
        if (debuggable) org.agentos.runtime.consent.AutoConsentResponder(consentBridge) else null
    val consent = org.agentos.runtime.consent.ConsentCoordinator(
        autoConsent ?: consentBridge, extensions.approvalWriter, scope, log = runtimeLog,
    ).also { c ->
        consentBridge.attach(c)
        autoConsent?.attach(c)
    }
    val hostPort = HostPortImpl(
        store, models, secrets, environment, runtimeLog,
        tools = extensions, approvals = extensions.approvals, skills = extensions.skills, consent = consent,
    )

    /** 宿主层。B2 之后 factory 换成 PiAdapter 的。 */
    val engine: RuntimeEngine = AgentRuntimes.create(
        hostPort, PiAgentCores.create(app, hostPort),
        RuntimeConfig(jev = ConfiguredJevProvider(jev, secrets)),
    )
    val runtime: AgentRuntime = engine
    @Volatile private var engineStarted = false

    @Volatile private var service: AgentService? = null
    private val startCommands = ConcurrentLinkedDeque<JSONObject>()
    @Volatile private var lastExit: JSONObject? = null
    @Volatile private var userStopped = false
    @Volatile private var recoveryMs = -1L
    @Volatile private var recoveryError: String? = null

    /**
     * 电脑端接入（W9，A lane 的 DesktopGateway.kt）：开关默认关闭，打开后监听抽象 socket agentos-acp。
     * 开关打开期间 :agent 留在前台（architecture F11 第 4 点，[RuntimeLifecycle] 规则 6）：放在 [lifecycle] 之前构造，
     * 它的 Port 现读开关状态。
     */
    val desktop = DesktopGateway(this)

    private val wakeLock: PowerManager.WakeLock =
        app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agentos:runtime-task")
            .apply { setReferenceCounted(false) }
    @Volatile private var ticker: Job? = null
    @Volatile private var idleCheck: Job? = null
    @Volatile private var noticeRefresh: Job? = null

    val lifecycle: RuntimeLifecycle = RuntimeLifecycle(object : RuntimeLifecycle.Port {
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

        override fun uptimeMillis(): Long = SystemClock.elapsedRealtime()

        override fun scheduleIdleCheck(delayMillis: Long) {
            idleCheck?.cancel()
            idleCheck = scope.launch(CoroutineName("idle-grace")) {
                delay(delayMillis)
                lifecycle.onIdleCheck()
            }
        }

        // 开关状态在 DesktopPairing 的内存里（构造时从 DE 存储读出），不做 I/O；进程启动时恢复结束的那次判断就会用到它
        override fun holdForeground(): Boolean = desktop.isEnabled()
    }, idleGraceMillis = IDLE_GRACE_MS)

    val acp = AcpConnections(this)

    // ------------------------------------------------------------------ 第三方 App 接入 ACP（docs/third-party-acp.md 4.1）

    /** 第三方 App 的授权记录：files/acp/callers.json（原子写，备份 + 损坏时 fail closed）。 */
    val callers = CallerRegistry(
        primary = FileBackedTextFile(File(app.filesDir, "$ACP_DIR/callers.json")),
        backup = FileBackedTextFile(File(app.filesDir, "$ACP_DIR/callers.backup.json")),
        quarantine = FileBackedTextFile(File(app.filesDir, "$ACP_DIR/callers.json.corrupt")),
    )
    private val callerResolver = PackageCallerResolver(app)

    /** IAcpService.open 的准入：AgentOS 自己 SELF，其他 UID 按 [AcpAccessPolicy] 查授权记录，不阻塞。 */
    fun admitAcp(callerUid: Int): AcpDecision = AcpAccessPolicy.decide(callerUid, Process.myUid(), callerResolver, callers)

    /** IAgentControl.listAcpCallers：每项带这个 App 现在开着的通道数。 */
    fun listCallersJson(): JSONArray {
        val arr = JSONArray()
        for (e in callers.entries()) arr.put(JSONObject(callers.toJson(e, acp.channelsOf(e.packageName), activeTasksOf(e.packageName)).toString()))
        return arr
    }

    /** IAgentControl.setAcpCaller。denied / removed 时 [CallerListener.onRevoked] 已经关了它的通道。 */
    fun setCaller(packageName: String?, state: String?): JSONObject? =
        callers.set(packageName, state)?.let { JSONObject(callers.toJson(it, acp.channelsOf(it.packageName), activeTasksOf(it.packageName)).toString()) }

    /** IAgentControl.answerAuthorization。 */
    fun answerAuthorization(requestId: String?, allow: Boolean): Boolean = callers.decide(requestId, allow)

    /** 这个 App 进行中的任务数（等 A 给出按调用方统计的接口之前为 0）。 */
    private fun activeTasksOf(@Suppress("UNUSED_PARAMETER") packageName: String): Int = 0

    private val callerListener = object : CallerListener {
        override fun onPending(entry: CallerEntry) {
            Log.i(TAG, "authorization requested by ${entry.packageName} (request ${entry.requestId?.take(8)})")
        }

        override fun onResolved(requestId: String, state: CallerState, timedOut: Boolean) {
            Log.i(TAG, "authorization ${requestId.take(8)} resolved: ${state.wire}${if (timedOut) " (timed out)" else ""}")
        }

        override fun onRevoked(packageName: String, signingDigest: String) {
            // 立即关它现有的通道；进行中的任务的取消见 cancelTasksOf
            val closed = acp.closeFor(packageName, signingDigest, "authorization revoked")
            Log.i(TAG, "authorization of $packageName revoked: closed $closed channel(s)")
            cancelTasksOf(packageName)
        }
    }

    /** 撤销时取消这个 App 进行中的任务。需要运行时按调用方取消的接口（见报告），接上之前只关通道。 */
    private fun cancelTasksOf(@Suppress("UNUSED_PARAMETER") packageName: String) = Unit

    init {
        callers.setListener(callerListener)
        AcpAndroid.ensureInitialized()
        installCrashHandler()
        // BIND_AUTO_CREATE：:ext 随 :agent 存活；目录和策略到了之后才有第三方工具（之前 fail closed）
        extensions.start()
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

    /** 电脑端接入的开关变了（DesktopGateway.setEnabled 之后调用，不在主线程）：前台理由、通知。 */
    internal fun onDesktopAccessChanged() {
        lifecycle.onHoldChanged()
        service?.refreshNotification(desktop.isEnabled(), lifecycle.status().tasks)
    }

    // ------------------------------------------------------------------ 恢复

    private fun startRecovery() {
        lifecycle.onRecoveryStarted()
        scope.launch(Dispatchers.IO + CoroutineName("recovery")) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val exit = readLastAgentExit()
                userStopped = RecoveryPolicy.userStopped(exit?.optInt("reason"))
                if (userStopped) Log.i(TAG, "previous :agent was stopped by the user; queued tasks will not be resumed")
                // 宿主层在 start() 里读一次：用户主动停止过 → 恢复出来的排队任务取消（S2 契约 a 第 6 条）
                environment.previousExitStoppedByUser = userStopped
                testRecoveryDelay()
                // BYOK 模型来源和 key（Keystore 解密）。失败只影响模型可用性，不挡住恢复
                try {
                    models.ensureLoaded()
                } catch (e: Exception) {
                    Log.w(TAG, "model source not loaded: ${e.javaClass.simpleName}")
                }
                // 打开 Store、迁移、恢复（F8），启动调度器
                runtime.start()
                engineStarted = true
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

    /** 只在带测试入口的构建里生效（debug、releaseTest，BuildConfig.TEST_HOOKS）：设备用例用它把恢复流程拉长，复现“恢复中登记任务”的先后顺序。 */
    private suspend fun testRecoveryDelay() {
        if (!BuildConfig.TEST_HOOKS) return
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
            // 进行中的任务都结束了：设置里换下来的旧 key 不再需要（KeystoreSecrets.activate）
            secrets.retire()
        }
        // 电脑端接入打开时，通知的副标题跟着忙闲变（“正在运行任务”）
        scheduleNoticeRefresh()
    }

    /**
     * 合并 [NOTICE_DEBOUNCE_MS] 内的忙闲变化，只按最后的状态更新一次通知。系统对每个 App 的通知更新限速（约每秒 5 次），
     * 超出的直接丢掉；session/new 与 prompt 前后忙闲切换很密，不合并的话最后一次（真实状态）可能被丢。
     */
    private fun scheduleNoticeRefresh() {
        noticeRefresh?.cancel()
        noticeRefresh = scope.launch(CoroutineName("notice-refresh")) {
            delay(NOTICE_DEBOUNCE_MS)
            if (desktop.isEnabled()) service?.refreshNotification(true, if (runtime.runState.value.busy) 1 else 0)
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

    fun runtimeStatus(): JSONObject = synchronized(lifecycle) {
        // 同一把锁里一次取完（@Synchronized 锁的就是 lifecycle）：分开取会在空闲停服的瞬间拼出 foreground=true、serviceRunning=false
        val s = lifecycle.status()
        JSONObject()
            .put("pid", Process.myPid())
            .put("phase", lifecycle.phase().name)
            .put("tasks", s.tasks)
            .put("foreground", s.foreground)
            .put("state", s.state)
            .put("serviceRunning", lifecycle.isServiceRunning())
            // 没有任务时仍留在前台的理由（RuntimeLifecycle 规则 6）
            .put("foregroundHold", if (lifecycle.holdingForeground()) "desktop_access" else JSONObject.NULL)
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
                .put("agentCore", "pi")
                .put("testHooks", BuildConfig.TEST_HOOKS)
                .put("engineStarted", engineStarted)
                .put("idleGraceMs", IDLE_GRACE_MS)
                .put("runState", JSONObject().put("activeTasks", rs.activeTasks).put("queuedTasks", rs.queuedTasks)
                    .put("recoveryPending", rs.recoveryPending).put("core", rs.core.toString())
                    .put("lastError", rs.lastError?.code?.wire ?: JSONObject.NULL)))
            .put("extensions", extensions.stats())
            .put("startCommands", JSONArray(startCommands.toList()))
            .put("lastExit", lastExit ?: JSONObject.NULL)
            .put("heartbeat", JSONObject()
                .put("path", heartbeat.file.absolutePath)
                .put("fields", hbText?.let { JSONObject(HeartbeatFormat.parse(it) as Map<*, *>) } ?: JSONObject.NULL)
                .put("writes", heartbeat.writes)
                .put("lastError", heartbeat.lastError ?: JSONObject.NULL))
            .put("store", runBlocking(Dispatchers.IO) { withTimeoutOrNull(3_000) { storeStats() } }
                ?: JSONObject().put("error", "timeout"))
            .put("byok", runCatching { JSONObject(models.status().toString()).put("keystore", JSONObject(secrets.keystoreStatus())) }
                .getOrElse { JSONObject().put("error", it.javaClass.simpleName) })
            .put("supervisorMissing", SupervisorStatus.supervisorMissing(
                supervisorStatus(), heartbeat.bootCount, SystemClock.elapsedRealtime() - startedAtElapsed))
            .put("acp", acp.stats())
            .put("desktop", desktop.stats())
    }

    /** 监督进程最近一次状态（D 的 W7：主进程的 SupervisorStatusReceiver 写在 DE 的 files/supervisor/status）。 */
    fun supervisorStatus(): SupervisorStatus? = SupervisorStatusReceiver.store(app).read()

    private val storeStatsLock = Mutex()
    private var systemCursor = 0L
    private var systemEvents = 0
    private val systemCounts = HashMap<String, Int>()
    private var lastRecovered: String? = null

    /** Store 的摘要：位置（CE）、是否本次新建、大小、系统流里的事件（增量读取）与最近一次恢复的结果。 */
    private suspend fun storeStats(): JSONObject = storeStatsLock.withLock {
        val path = store.databasePath
        val out = JSONObject()
            .put("path", path)
            .put("storage", if (path.startsWith(app.dataDir.absolutePath + "/")) "ce" else "other")
            .put("existedAtStart", store.existedAtStart)
            .put("sizeBytes", store.sizeBytes())
            .put("opened", engineStarted)
        if (!engineStarted) return@withLock out.put("error", recoveryError ?: "not_started")
        while (true) {
            val batch = engine.readEvents(EventTypes.SYSTEM_STREAM, systemCursor, 500)
            for (e in batch) {
                systemCounts.merge(e.eventType, 1, Int::plus)
                systemEvents++
                systemCursor = e.sequence
                if (e.eventType == EventTypes.RUNTIME_RECOVERED) lastRecovered = e.payload.toString()
            }
            if (batch.size < 500) break
        }
        out.put("system", JSONObject()
            .put("lastSequence", systemCursor)
            .put("events", systemEvents)
            .put("runtimeStarted", systemCounts[EventTypes.RUNTIME_STARTED] ?: 0)
            .put("runtimeRecovered", systemCounts[EventTypes.RUNTIME_RECOVERED] ?: 0)
            // requeued、recoveryRequired、interrupted、userStopped、cancelled（Recovery 写的计数，不含内容）
            .put("lastRecovered", lastRecovered?.let { JSONObject(it) } ?: JSONObject.NULL))
    }

    companion object {
        private const val TAG = "AgentProcess"
        const val HEARTBEAT_REFRESH_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L

        /** 任务数归零后等这么久才退出前台（RuntimeLifecycle 规则 5：接住 session/new 与 prompt 之间的空档）。 */
        const val IDLE_GRACE_MS = 2_000L

        /** 通知跟着忙闲变时合并这么久内的变化（系统对通知更新限速）。 */
        const val NOTICE_DEBOUNCE_MS = 500L

        /** BYOK 模型来源（明文部分 + key 的密文），CE 存储 files/ 下。 */
        const val BYOK_DIR = "byok"
        const val JEV_DIR = "jev"
        const val ACP_DIR = "acp"

        /** B 的 core/pi-runtime/build.mjs 生成的厂商预设。 */
        const val MODEL_CATALOG_ASSET = "model-catalog.json"

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
