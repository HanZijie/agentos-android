package org.agentos.spike.s3.client

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

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

/** 结果写到 logcat（tag S3RESULT），按 3000 字符分片，主机端脚本拼回完整 JSON。 */
object Results {
    private const val TAG = "S3RESULT"
    private const val PART = 3000

    fun emit(runId: String, json: JSONObject) {
        val s = json.toString()
        val parts = (s.length + PART - 1) / PART
        for (i in 0 until parts) {
            Log.i(TAG, "$runId ${i + 1}/$parts ${s.substring(i * PART, minOf(s.length, (i + 1) * PART))}")
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
        val firstMean = raw.take(tenth).average() / 1e6
        val lastMean = raw.takeLast(tenth).average() / 1e6
        return JSONObject().put("n", size)
            .put("p50ms", p(0.5)).put("p90ms", p(0.9)).put("p99ms", p(0.99))
            .put("maxms", sorted.last() / 1e6).put("meanms", raw.average() / 1e6)
            .put("first10pctMeanMs", firstMean).put("last10pctMeanMs", lastMean)
    }
}
