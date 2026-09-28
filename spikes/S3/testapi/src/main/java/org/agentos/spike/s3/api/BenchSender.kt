package org.agentos.spike.s3.api

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 原始 oneway 压测的发送端。不做任何流控，用来测：
 * - 单条 oneway 事务在接收方空闲时最大能多大（singleMax）；
 * - 接收方处理慢时，不加背压连续发送多久会失败、失败时在途多少（burst）；
 * - 接收方不慢时的原始吞吐（throughput）。
 *
 * 长度单位：String 为 UTF-16 code unit，byte[] 为字节。parcelBytes 估算的是 Parcel 里的载荷字节数。
 */
object BenchSender {
    enum class Kind { STRING, STRING_CJK, BYTES }

    class Payload(val kind: Kind, val units: Int) {
        val s: String? = when (kind) {
            Kind.STRING -> "a".repeat(units)
            Kind.STRING_CJK -> "中".repeat(units)
            Kind.BYTES -> null
        }
        val b: ByteArray? = if (kind == Kind.BYTES) ByteArray(units) { 'a'.code.toByte() } else null
    }

    fun parcelBytes(kind: Kind, units: Int): Long = when (kind) {
        Kind.BYTES -> 4L + pad4(units.toLong())
        else -> 4L + pad4((units + 1L) * 2)
    }

    private fun pad4(n: Long) = (n + 3) / 4 * 4

    fun send(sink: IBenchSink, p: Payload) {
        if (p.s != null) sink.msg(p.s) else sink.bytes(p.b)
    }

    private fun describe(e: Throwable): JSONObject = JSONObject()
        .put("class", e.javaClass.name)
        .put("message", e.message ?: "")

    /**
     * 先按阶梯找到第一个失败的大小，再二分到 1024 单位的精度。
     * [awaitCount] 在每次成功后等待接收端的累计条数达到给定值，保证下一次发送时接收方是空闲的。
     */
    fun singleMax(sink: IBenchSink, kind: Kind, awaitCount: (Long) -> Boolean): JSONObject {
        val ladder = intArrayOf(
            16_384, 32_768, 65_536, 98_304, 131_072, 163_840, 196_608, 229_376, 262_144,
            327_680, 393_216, 458_752, 524_288, 786_432, 1_048_576,
        )
        var okCount = 0L
        var lo = 0
        var hi = -1
        var firstFailure: JSONObject? = null
        val attempts = JSONArray()
        fun attempt(units: Int): Boolean {
            val p = Payload(kind, units)
            val t0 = SystemClock.elapsedRealtimeNanos()
            return try {
                send(sink, p)
                okCount++
                val delivered = awaitCount(okCount)
                attempts.put(JSONObject().put("units", units).put("ok", true).put("delivered", delivered)
                    .put("ms", (SystemClock.elapsedRealtimeNanos() - t0) / 1e6))
                delivered
            } catch (e: Exception) {
                val d = describe(e).put("units", units).put("ok", false)
                    .put("aliveAfter", sink.asBinder().isBinderAlive)
                attempts.put(d)
                if (firstFailure == null) firstFailure = d
                false
            }
        }
        for (u in ladder) {
            if (attempt(u)) lo = u else { hi = u; break }
        }
        if (hi > 0) {
            while (hi - lo > 1024) {
                val mid = (lo + hi) / 2
                if (attempt(mid)) lo = mid else hi = mid
            }
        }
        return JSONObject()
            .put("kind", kind.name)
            .put("maxOkUnits", lo)
            .put("maxOkParcelBytes", parcelBytes(kind, lo))
            .put("minFailUnits", hi)
            .put("minFailParcelBytes", if (hi > 0) parcelBytes(kind, hi) else -1)
            .put("firstFailure", firstFailure ?: JSONObject.NULL)
            .put("attempts", attempts)
    }

    /**
     * 连续发送 [n] 条，遇到第一次失败就停。[sinkCount] 返回接收端已处理的条数（拿不到时返回 -1）。
     * 返回失败位置、异常、失败瞬间估算的在途条数和字节数。
     */
    fun burst(sink: IBenchSink, kind: Kind, units: Int, n: Int, sinkCount: () -> Long): JSONObject {
        val p = Payload(kind, units)
        val t0 = SystemClock.elapsedRealtimeNanos()
        var failAt = -1
        var err: Throwable? = null
        for (i in 0 until n) {
            try {
                send(sink, p)
            } catch (e: Exception) {
                failAt = i
                err = e
                break
            }
        }
        val sendMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        val processed = sinkCount()
        val accepted = if (failAt >= 0) failAt else n
        val inFlight = if (processed >= 0) accepted - processed else -1
        val out = JSONObject()
            .put("kind", kind.name).put("units", units).put("n", n)
            .put("parcelBytesEach", parcelBytes(kind, units))
            .put("failAt", failAt)
            .put("error", err?.let { describe(it) } ?: JSONObject.NULL)
            .put("aliveAfter", sink.asBinder().isBinderAlive)
            .put("sendMs", sendMs)
            .put("processedAtStop", processed)
            .put("inFlightAtStop", inFlight)
            .put("inFlightBytesAtStop", if (inFlight >= 0) inFlight * parcelBytes(kind, units) else -1)
        if (processed >= 0) {
            val deadline = SystemClock.elapsedRealtime() + 120_000
            while (sinkCount() < accepted && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            out.put("drainedMs", (SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
                .put("drained", sinkCount() >= accepted)
        }
        return out
    }

    /** 接收端不慢时的原始吞吐：发 [n] 条，等全部处理完。 */
    fun throughput(sink: IBenchSink, kind: Kind, units: Int, n: Int, sinkCount: () -> Long): JSONObject {
        val p = Payload(kind, units)
        val base = sinkCount()
        val t0 = SystemClock.elapsedRealtimeNanos()
        var failAt = -1
        var err: Throwable? = null
        for (i in 0 until n) {
            try {
                send(sink, p)
            } catch (e: Exception) {
                failAt = i
                err = e
                break
            }
        }
        val sendNs = SystemClock.elapsedRealtimeNanos() - t0
        val accepted = if (failAt >= 0) failAt else n
        if (base >= 0) {
            val deadline = SystemClock.elapsedRealtime() + 120_000
            while (sinkCount() - base < accepted && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(1)
            }
        }
        val allNs = SystemClock.elapsedRealtimeNanos() - t0
        val bytes = accepted * parcelBytes(kind, units)
        return JSONObject()
            .put("kind", kind.name).put("units", units).put("n", n)
            .put("failAt", failAt)
            .put("error", err?.let { describe(it) } ?: JSONObject.NULL)
            .put("sendMs", sendNs / 1e6)
            .put("totalMs", allNs / 1e6)
            .put("msgsPerSec", accepted / (allNs / 1e9))
            .put("mbPerSec", bytes / (allNs / 1e9) / 1e6)
    }

    /** IBench.blast 的实现：按 JSON 配置向对方接收端发送。对方的处理计数拿不到，由调用方自己读。 */
    fun blast(target: IBenchSink, cfg: JSONObject): JSONObject {
        val kind = Kind.valueOf(cfg.optString("kind", "STRING"))
        val units = cfg.optInt("units", 1024)
        val n = cfg.optInt("n", 1000)
        return when (cfg.optString("mode", "burst")) {
            "throughput" -> throughput(target, kind, units, n) { -1 }
            else -> burst(target, kind, units, n) { -1 }
        }
    }
}
