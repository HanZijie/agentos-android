package org.agentos.test.acp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.Executors

/** 设备测试里用到的包名和组件名。 */
object TestIds {
    /** SDK 回归用的测试 Agent App。 */
    const val TEST_AGENT_PKG = "org.agentos.test.acp.agent"
    val TEST_AGENT_ACP = ComponentName(TEST_AGENT_PKG, "$TEST_AGENT_PKG.AcpService")
    val TEST_AGENT_PROBE = ComponentName(TEST_AGENT_PKG, "$TEST_AGENT_PKG.ProbeService")

    /** 真正的 AgentOS App。 */
    const val APP_PKG = "org.agentos.app"
    val APP_ACP = ComponentName(APP_PKG, "$APP_PKG.agent.AcpService")
    val APP_CONTROL = ComponentName(APP_PKG, "$APP_PKG.agent.AgentControlService")
    val APP_AGENT_SERVICE = ComponentName(APP_PKG, "$APP_PKG.agent.AgentService")
    const val APP_AGENT_PROCESS = "$APP_PKG:agent"

    /** 结果写到 logcat 的 tag。 */
    const val RESULT_TAG = "ACPTEST"
}

private val callbackExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "service-conn") }

/** bindService 的协程封装；回调不在主线程。 */
class ServiceBinding(private val ctx: Context, private val component: ComponentName) : ServiceConnection {
    private val connected = Channel<IBinder>(Channel.CONFLATED)
    @Volatile var binder: IBinder? = null
        private set
    @Volatile var connectedAtNs = 0L
        private set
    @Volatile var disconnectedAtNs = 0L
        private set
    @Volatile var connectCount = 0
        private set
    private var bound = false

    fun bind(): Boolean {
        val intent = Intent().setComponent(component)
        bound = ctx.bindService(intent, Context.BIND_AUTO_CREATE, callbackExecutor, this)
        return bound
    }

    /** 等下一次 onServiceConnected（包括进程死亡后系统重建服务的那一次）。 */
    suspend fun awaitConnected(timeoutMs: Long): IBinder? = withTimeoutOrNull(timeoutMs) { connected.receive() }

    fun unbind() {
        if (bound) runCatching { ctx.unbindService(this) }
        bound = false
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder) {
        binder = service
        connectedAtNs = SystemClock.elapsedRealtimeNanos()
        connectCount++
        connected.trySend(service)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        binder = null
        disconnectedAtNs = SystemClock.elapsedRealtimeNanos()
    }
}

/** 主线程心跳：每 [periodMs] 投递一次，记录实际执行比预期晚了多少。用来判断主线程有没有被阻塞。 */
class MainThreadWatchdog(private val periodMs: Long = 10) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var expectedNs = 0L
    private var ticks = 0L
    private var maxLateNs = 0L
    private var over16 = 0L
    private var over50 = 0L
    private var over100 = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtimeNanos()
            val late = now - expectedNs
            if (late > maxLateNs) maxLateNs = late
            if (late > 16_000_000) over16++
            if (late > 50_000_000) over50++
            if (late > 100_000_000) over100++
            ticks++
            expectedNs = now + periodMs * 1_000_000
            handler.postDelayed(this, periodMs)
        }
    }

    fun start() {
        handler.post {
            running = true
            expectedNs = SystemClock.elapsedRealtimeNanos()
            tick.run()
        }
    }

    fun stop(): JSONObject {
        running = false
        handler.removeCallbacks(tick)
        return JSONObject().put("ticks", ticks).put("maxLateMs", maxLateNs / 1e6)
            .put("lateOver16ms", over16).put("lateOver50ms", over50).put("lateOver100ms", over100)
    }
}

/** 结果写到 logcat（tag [TestIds.RESULT_TAG]），按 3000 字符分片，主机端脚本拼回完整 JSON。 */
object Results {
    private const val PART = 3000

    fun emit(runId: String, json: JSONObject) {
        val s = json.toString()
        val parts = (s.length + PART - 1) / PART
        for (i in 0 until parts) {
            Log.i(TestIds.RESULT_TAG, "$runId ${i + 1}/$parts ${s.substring(i * PART, minOf(s.length, (i + 1) * PART))}")
        }
    }
}

/** 延迟样本（纳秒）的统计。 */
class LatencyStats {
    private var data = LongArray(1024)
    var size = 0
        private set

    fun add(ns: Long) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = ns
    }

    fun summary(): JSONObject {
        if (size == 0) return JSONObject().put("n", 0)
        val raw = data.copyOf(size)
        val sorted = raw.sortedArray()
        fun p(q: Double) = sorted[minOf(size - 1, (q * size).toInt())] / 1e6
        val tenth = maxOf(1, size / 10)
        return JSONObject().put("n", size)
            .put("p50ms", p(0.5)).put("p90ms", p(0.9)).put("p99ms", p(0.99))
            .put("maxms", sorted.last() / 1e6).put("meanms", raw.average() / 1e6)
            .put("first10pctMeanMs", raw.take(tenth).average() / 1e6)
            .put("last10pctMeanMs", raw.takeLast(tenth).average() / 1e6)
    }
}

fun errJson(e: Throwable?): Any =
    e?.let { JSONObject().put("class", it.javaClass.name).put("message", it.message ?: "") } ?: JSONObject.NULL

fun threadCount(): Int = runCatching {
    java.io.File("/proc/self/status").readLines().first { it.startsWith("Threads:") }.substringAfter(":").trim().toInt()
}.getOrDefault(-1)
