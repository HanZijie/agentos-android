package org.agentos.spike.s3.api

import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** 原始 oneway 接收端：只计数；可以人为放慢每条的处理，模拟接收方忙。 */
class BenchSinkImpl(private val handlerDelayMicros: Int) : IBenchSink.Stub() {
    private val count = AtomicLong()
    private val units = AtomicLong()

    val received: Long get() = count.get()

    override fun msg(s: String?) {
        work()
        units.addAndGet((s?.length ?: 0).toLong())
        count.incrementAndGet()
    }

    override fun bytes(b: ByteArray?) {
        work()
        units.addAndGet((b?.size ?: 0).toLong())
        count.incrementAndGet()
    }

    private fun work() {
        if (handlerDelayMicros <= 0) return
        val end = SystemClock.elapsedRealtimeNanos() + handlerDelayMicros * 1000L
        while (true) {
            val left = end - SystemClock.elapsedRealtimeNanos()
            if (left <= 0) break
            LockSupport.parkNanos(left)
        }
    }

    fun stats(): JSONObject = JSONObject()
        .put("count", count.get())
        .put("units", units.get())
        .put("handlerDelayMicros", handlerDelayMicros)
}
