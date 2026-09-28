package org.agentos.test.acp.inapp

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agentos.channel.ChannelConfig
import org.agentos.test.acp.AcpConn
import org.agentos.test.acp.AcpTarget
import org.agentos.test.acp.PromptRun
import org.agentos.test.acp.ServiceBinding
import org.agentos.test.acp.TestIds
import org.agentos.test.acp.errJson
import org.agentos.test.acp.runPrompt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * IAgentControl 的客户端。本库不能依赖 :app（IAgentControl.aidl 在 app 里），而它只存在于 AgentOS 的 debug 包，
 * 运行时 app 的 dex 里一定有 `org.agentos.internal.IAgentControl`，所以用反射调用。
 */
class ControlClient(private val ctx: Context) {
    suspend fun <T> use(block: (Proxy) -> T): T {
        val b = ServiceBinding(ctx, TestIds.APP_CONTROL)
        check(b.bind()) { "cannot bind AgentControlService" }
        try {
            val binder = b.awaitConnected(15_000) ?: error("AgentControlService not connected")
            return withContext(Dispatchers.IO) { block(Proxy(binder)) }
        } finally {
            b.unbind()
        }
    }

    class Proxy(binder: IBinder) {
        private val iface: Any = Class.forName("org.agentos.internal.IAgentControl\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!

        private fun call(name: String, vararg args: String?): Any? = try {
            iface.javaClass.getMethod(name, *Array(args.size) { String::class.java }).invoke(iface, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }

        fun version(): Int = call("getVersion") as Int
        fun runtimeStatus(): JSONObject = JSONObject(call("getRuntimeStatus") as String)
        fun diagnostics(): JSONObject = JSONObject(call("getDiagnostics") as String)
        fun supervisorStatus(): JSONObject = JSONObject(call("getSupervisorStatus") as String)

        // v2：BYOK
        fun presets(providerId: String?): JSONObject = JSONObject(call("getModelPresets", providerId) as String)
        fun modelSource(): JSONObject = JSONObject(call("getModelSource") as String)
        fun setModelSource(sourceJson: String, apiKey: String?): JSONObject = JSONObject(call("setModelSource", sourceJson, apiKey) as String)
        fun clearModelSource() {
            call("clearModelSource")
        }
    }
}

/** AgentOS 的 :agent（真正的宿主层，A3 起）。只能用默认通道参数；杀进程用同 UID 的 Process.killProcess。 */
class AppTarget(private val ctx: Context) : AcpTarget {
    override val name = "agentos-app"
    override val acpComponent = TestIds.APP_ACP
    override val supportsChannelConfig = false
    override val hostRuntime = true
    private val control = ControlClient(ctx)

    override suspend fun stats(): JSONObject = control.use { it.diagnostics() }.getJSONObject("acp")
    override suspend fun setChannelConfig(cfg: JSONObject?) = require(cfg == null) { "default config only" }
    override suspend fun killServer() {
        val pid = agentPid(ctx) ?: error(":agent is not running")
        Process.killProcess(pid)
    }

    companion object {
        fun agentPid(ctx: Context): Int? = ctx.getSystemService(ActivityManager::class.java).runningAppProcesses
            ?.firstOrNull { it.processName == TestIds.APP_AGENT_PROCESS }?.pid
    }
}

/**
 * AgentService 与监督契约（docs/spikes/S2.md a、b）的用例。都在 AgentOS 自己的 UID 下执行：本执行器的 Activity
 * 在前台，所以 `startForegroundService` 放行。
 */
class AgentServiceScenarios(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val status: (String) -> Unit,
) {
    private val control = ControlClient(ctx)
    private val de = ctx.createDeviceProtectedStorageContext()

    suspend fun run(name: String, args: JSONObject): JSONObject? = when (name) {
        "task-foreground" -> taskForeground(args)
        "supervisor-start" -> supervisorStart(args)
        "restart-exit-info" -> restartExitInfo(args)
        "server-stats-clean" -> serverStatsClean()
        else -> null
    }

    private fun now() = SystemClock.elapsedRealtime()

    private suspend fun killAgentAndWait(): Boolean {
        val pid = AppTarget.agentPid(ctx) ?: return true
        Process.killProcess(pid)
        val deadline = now() + 10_000
        while (AppTarget.agentPid(ctx) == pid && now() < deadline) delay(50)
        return AppTarget.agentPid(ctx) != pid
    }

    /** 让下一次恢复流程多等 [ms] 毫秒（AgentRuntime 只在 debug 包里读这个文件）。 */
    private fun setRecoveryDelay(ms: Long) {
        val f = File(de.filesDir, "test/recovery_delay_ms")
        f.parentFile?.mkdirs()
        f.writeText(ms.toString())
    }

    private fun heartbeat(): JSONObject {
        val f = File(de.filesDir, "supervisor/heartbeat")
        val text = runCatching { f.readText() }.getOrNull() ?: return JSONObject().put("missing", true)
        val out = JSONObject()
        text.lineSequence().forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) out.put(line.substring(0, i), line.substring(i + 1))
        }
        return out.put("bytes", text.length)
    }

    /** AgentService 是否在运行、是否前台（getRunningServices 对本 App 自己的服务仍然可用）。 */
    @Suppress("DEPRECATION")
    private fun serviceState(): JSONObject {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val s = am.getRunningServices(200).firstOrNull { it.service == TestIds.APP_AGENT_SERVICE }
        return JSONObject().put("running", s != null && s.started).put("foreground", s?.foreground == true)
    }

    private class Sample(val tMs: Long, val status: JSONObject, val service: JSONObject, val hb: JSONObject) {
        fun json() = JSONObject().put("t", tMs).put("status", status).put("service", service).put("hb", hb)
    }

    /** 在 [ControlClient.use] 里调用（已在 IO 线程）。 */
    private fun sample(t0: Long, proxy: ControlClient.Proxy): Sample =
        Sample(now() - t0, proxy.runtimeStatus(), serviceState(), heartbeat())

    /**
     * 一轮 prompt 就是一个任务：期间 AgentService 在前台、心跳 tasks=1 fg=1 state=busy；结束后退出前台并停止，
     * 心跳 tasks=0 fg=0 state=idle。
     *
     * cold=true 时先杀掉 :agent，并让恢复流程多等 recoveryDelayMs：客户端 bind 拉起冷进程，prompt 的任务在恢复
     * 结束之前就已登记。这正是 S2 整合时发现的缺陷的先后顺序（恢复结束的空闲判断先于任务登记，服务退出前台，
     * 心跳成了 tasks=1 fg=0）。通过标准要求恢复结束后服务一直在前台，直到任务结束。
     */
    private suspend fun taskForeground(args: JSONObject): JSONObject {
        val cold = args.optBoolean("cold", true)
        val recoveryDelayMs = args.optLong("recoveryDelayMs", if (cold) 1500 else 0)
        val streamMs = args.optLong("streamMs", 3000)
        if (cold) {
            check(killAgentAndWait()) { "could not kill :agent" }
            if (recoveryDelayMs > 0) setRecoveryDelay(recoveryDelayMs)
        } else {
            // 先把 :agent 拉起、恢复结束
            control.use { it.runtimeStatus() }
        }
        val before = if (cold) null else control.use { it.diagnostics() }
        val c = AcpConn(ctx, TestIds.APP_ACP, ChannelConfig.DEFAULT, scope, "task-fg")
        val samples = JSONArray()
        val sampleList = java.util.Collections.synchronizedList(mutableListOf<Sample>())
        val run = PromptRun()
        val t0 = now()
        val promptDone = java.util.concurrent.atomic.AtomicBoolean(false)
        // 从连接之前就开始采样：宿主层从 session/new 起计入任务，而 session/new 要等恢复结束才返回（A3），
        // 恢复期间登记的任务只有这样才看得到
        val sampler = scope.launch {
            control.use { proxy ->
                while (!promptDone.get()) {
                    sampleList += sample(t0, proxy)
                    Thread.sleep(100)
                }
            }
        }
        try {
            c.connect()
            val session = c.newSession()
            val chunks = (streamMs / 20).toInt()
            try {
                runPrompt(session, JSONObject().put("chunks", chunks).put("intervalMs", 20).toString(), run, status)
            } finally {
                promptDone.set(true)
            }
            sampler.join()
            val tEnd = now()
            // 结束后：空闲宽限期（2 s）过后退出前台、服务停止
            var after: Sample? = null
            control.use { proxy ->
                val deadline = now() + 8_000
                while (now() < deadline) {
                    val s = sample(t0, proxy)
                    after = s
                    if (s.status.optInt("tasks") == 0 && !s.status.optBoolean("serviceRunning") && !s.service.optBoolean("running")) break
                    Thread.sleep(100)
                }
            }
            val diag = control.use { it.diagnostics() }
            c.closeAndWait()
            sampleList.forEach { samples.put(it.json()) }

            // 判定
            val sawRecovering = sampleList.any { it.status.optString("phase") == "RECOVERING" && it.status.optInt("tasks") >= 1 }
            val ready = sampleList.filter { it.status.optString("phase") == "READY" && it.status.optInt("tasks") >= 1 }
            // 进前台需要一次 startForegroundService → onStartCommand 往返，恢复结束后的样本都应在前台。
            // 宿主层从收到请求起计数、调度器接手时有一瞬可能多计 1（session-scheduling.md 7.1），所以心跳 tasks 只要求 ≥ 1
            val firstFg = ready.indexOfFirst { it.status.optBoolean("foreground") }
            val lostFg = if (firstFg < 0) ready else ready.drop(firstFg).filterNot {
                it.status.optBoolean("foreground") && it.service.optBoolean("foreground") &&
                    it.hb.optString("fg") == "1" && (it.hb.optString("tasks").toIntOrNull() ?: 0) >= 1 && it.hb.optString("state") == "busy"
            }
            val readyNotFgAtAll = ready.isNotEmpty() && firstFg < 0
            val a = after
            val idleOk = a != null && a.status.optInt("tasks") == 0 && !a.status.optBoolean("foreground") &&
                !a.status.optBoolean("serviceRunning") && !a.service.optBoolean("running") &&
                a.hb.optString("tasks") == "0" && a.hb.optString("fg") == "0" && a.hb.optString("state") == "idle"
            val runtime = diag.getJSONObject("runtime")
            val idleStopsDelta = runtime.optInt("idleStops") - (before?.getJSONObject("runtime")?.optInt("idleStops") ?: 0)
            val ok = run.stopReason == "END_TURN" && ready.isNotEmpty() && !readyNotFgAtAll && lostFg.isEmpty() && idleOk &&
                idleStopsDelta == 1 && (!cold || recoveryDelayMs == 0L || (sawRecovering && runtime.optLong("recoveryMs") >= recoveryDelayMs))
            return JSONObject()
                .put("ok", ok)
                .put("summary", "samples=${sampleList.size} recovering=$sawRecovering readyBusy=${ready.size} lostFg=${lostFg.size} idleOk=$idleOk idleStops+=$idleStopsDelta")
                .put("cold", cold).put("recoveryDelayMs", recoveryDelayMs)
                .put("prompt", run.json())
                .put("promptEndMs", tEnd - t0)
                .put("sawRecoveringWithTask", sawRecovering)
                .put("readyBusySamples", ready.size)
                .put("lostForegroundSamples", JSONArray(lostFg.take(5).map { it.json() }))
                .put("after", a?.json() ?: JSONObject.NULL)
                .put("idleStopsDelta", idleStopsDelta)
                .put("runtime", runtime)
                .put("startCommands", diag.optJSONArray("startCommands"))
                .put("samples", JSONArray((0 until samples.length()).filter { it % 3 == 0 }.map { samples.get(it) }))
        } finally {
            c.dispose()
        }
    }

    /**
     * 监督进程的启动命令（`-a org.agentos.action.SUPERVISOR_START --es org.agentos.extra.REASON boot`）：没有任务时，
     * 服务先进前台（每次 onStartCommand 先 startForeground），恢复期间不做空闲判断，恢复结束后退出前台并停止。
     */
    private suspend fun supervisorStart(args: JSONObject): JSONObject {
        val reason = args.optString("reason", "boot")
        val recoveryDelayMs = args.optLong("recoveryDelayMs", 1000)
        check(killAgentAndWait()) { "could not kill :agent" }
        if (recoveryDelayMs > 0) setRecoveryDelay(recoveryDelayMs)
        val t0 = now()
        ctx.startForegroundService(
            Intent().setComponent(TestIds.APP_AGENT_SERVICE)
                .setAction("org.agentos.action.SUPERVISOR_START")
                .putExtra("org.agentos.extra.REASON", reason)
                .putExtra("org.agentos.extra.ATTEMPT", 1)
        )
        val timeline = mutableListOf<Sample>()
        var stopped: Sample? = null
        control.use { proxy ->
            val deadline = now() + recoveryDelayMs + 8_000
            while (now() < deadline) {
                val s = sample(t0, proxy)
                timeline += s
                if (s.status.optString("phase") == "READY" && !s.status.optBoolean("serviceRunning") && !s.service.optBoolean("running")) {
                    stopped = s
                    break
                }
                Thread.sleep(100)
            }
        }
        val diag = control.use { it.diagnostics() }
        val cmds = diag.optJSONArray("startCommands") ?: JSONArray()
        val cmd = (0 until cmds.length()).map { cmds.getJSONObject(it) }.lastOrNull { it.optString("reason") == reason }
        val fgDuringRecovery = timeline.any { it.status.optString("phase") == "RECOVERING" && it.service.optBoolean("foreground") }
        val stoppedDuringRecovery = timeline.any { it.status.optString("phase") == "RECOVERING" && !it.service.optBoolean("running") && it.tMs > 500 }
        val st = stopped
        val ok = cmd != null && cmd.optBoolean("foregroundOk") && fgDuringRecovery && !stoppedDuringRecovery && st != null &&
            st.hb.optString("state") == "idle" && st.hb.optString("tasks") == "0" && st.hb.optString("fg") == "0" &&
            diag.getJSONObject("runtime").optLong("recoveryMs") >= recoveryDelayMs
        return JSONObject()
            .put("ok", ok)
            .put("summary", "cmd=${cmd != null} fgDuringRecovery=$fgDuringRecovery stoppedDuringRecovery=$stoppedDuringRecovery stoppedAt=${st?.tMs}")
            .put("startCommand", cmd ?: JSONObject.NULL)
            .put("stoppedAtMs", st?.tMs ?: -1)
            .put("final", st?.json() ?: JSONObject.NULL)
            .put("runtime", diag.getJSONObject("runtime"))
            .put("timeline", JSONArray(timeline.filterIndexed { i, _ -> i % 2 == 0 }.map { it.json() }))
    }

    /**
     * `REASON=restart`：运行时查上一个 :agent 的 ApplicationExitInfo（监督契约 S2 a 第 6 条）。
     * 被 SIGKILL 的进程记为 SIGNALED，不是用户主动停止，所以 userStopped=false。
     */
    private suspend fun restartExitInfo(args: JSONObject): JSONObject {
        control.use { it.runtimeStatus() } // 确保有一个 :agent 可杀
        val killedPid = AppTarget.agentPid(ctx)
        check(killAgentAndWait()) { "could not kill :agent" }
        delay(300)
        ctx.startForegroundService(
            Intent().setComponent(TestIds.APP_AGENT_SERVICE)
                .setAction("org.agentos.action.SUPERVISOR_START")
                .putExtra("org.agentos.extra.REASON", "restart")
                .putExtra("org.agentos.extra.ATTEMPT", 1)
        )
        var diag = JSONObject()
        control.use { proxy ->
            val deadline = now() + 8_000
            while (now() < deadline) {
                diag = proxy.diagnostics()
                val rt = diag.getJSONObject("runtime")
                if (rt.optString("phase") == "READY" && !rt.optBoolean("serviceRunning")) break
                Thread.sleep(100)
            }
        }
        val last = diag.optJSONObject("lastExit")
        val rt = diag.getJSONObject("runtime")
        val ok = last != null && last.optInt("pid") == (killedPid ?: -1) && last.optString("reasonName") == "SIGNALED" &&
            !rt.optBoolean("userStopped") && !rt.optBoolean("serviceRunning") && rt.optInt("tasks") == 0
        return JSONObject()
            .put("ok", ok)
            .put("summary", "lastExit=${last?.optString("reasonName")} pidMatch=${last?.optInt("pid") == killedPid} userStopped=${rt.optBoolean("userStopped")}")
            .put("killedPid", killedPid ?: -1)
            .put("lastExit", last ?: JSONObject.NULL)
            .put("runtime", rt)
            .put("startCommands", diag.optJSONArray("startCommands"))
    }

    /** 第三方 App 被拒之后：:agent 没有留下任何连接或通道，并且记下了一次拒绝。 */
    private suspend fun serverStatsClean(): JSONObject {
        val acp = control.use { it.diagnostics() }.getJSONObject("acp")
        val ok = acp.optInt("connectionsOpen") == 0 && acp.optInt("liveChannels") == 0 && acp.optLong("rejectedOpens") >= 1
        return JSONObject().put("ok", ok)
            .put("summary", "open=${acp.optInt("connectionsOpen")} live=${acp.optInt("liveChannels")} rejected=${acp.optLong("rejectedOpens")}")
            .put("acp", acp)
    }
}
