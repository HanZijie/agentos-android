package org.agentos.app.agent

import android.app.Application
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.agentos.runtime.pi.PiAdapter
import org.agentos.runtime.pi.PiStartup
import org.agentos.runtime.ports.AgentSessionConfig
import org.agentos.runtime.testing.FakeHostPort
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cold start of the production factory ([PiAgentCores]) in the `:agent` process: reading the
 * bundle from assets, creating the QuickJS runtime and evaluating pi-agent.js until the pump is
 * ready. Run it alone in a fresh process for the first-start number (`am instrument -e class`);
 * results go to logcat under [Device.TAG] (numbers only).
 */
class PiColdStartTest {

    private class Sample(val wallMs: Double, val startup: PiStartup)

    @Test
    fun coldStartInTheAgentProcess() = runBlocking<Unit> {
        assertEquals(Device.AGENT_PROCESS, Application.getProcessName())
        val context = Device.context
        val cacheDir = File(context.codeCacheDir, "pi")
        cacheDir.deleteRecursively()

        val t0 = System.nanoTime()
        val bundle = PiAgentCores.loadBundle(context)
        val loadMs = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime()
        bundle.sha256
        val hashMs = (System.nanoTime() - t1) / 1e6

        val factory = PiAgentCores.create(context, FakeHostPort(credentials = emptyMap()))
        val model = PiAgentCores.loadCatalog(context).model("minimax-cn", "MiniMax-M2.7")!!.toModelSpec()

        /** PSS and native heap after a full GC, in KB. */
        fun memory(): Pair<Long, Long> {
            repeat(2) {
                Runtime.getRuntime().gc()
                System.runFinalization()
            }
            return Debug.getPss() to Debug.getNativeHeapAllocatedSize() / 1024
        }

        var live: Pair<Long, Long>? = null

        suspend fun startOnce(awaitCache: Boolean = false): Sample {
            val start = System.nanoTime()
            val core = factory.create() as PiAdapter
            try {
                core.start()
                val wall = (System.nanoTime() - start) / 1e6
                core.openSession("cold-start", AgentSessionConfig(model, systemPrompt = "x", tools = emptyList())).dispose()
                // The cache is compiled in the background of a running core; closing it cancels that.
                if (awaitCache) {
                    withTimeout(30_000) {
                        while (cacheDir.listFiles().orEmpty().none { it.name.endsWith(".qjsbc") }) delay(20)
                    }
                    delay(200) // let the temporary compile engine close
                    live = memory()
                }
                return Sample(wall, core.startup!!)
            } finally {
                core.close()
            }
        }

        val before = memory()
        val first = startOnce(awaitCache = true)
        assertFalse(first.startup.fromBytecode, "no bytecode cache on the first start")
        val cacheBytes = cacheDir.listFiles()!!.single { it.name.endsWith(".qjsbc") }.length()

        val warm = (1..5).map { startOnce() }
        assertTrue(warm.all { it.startup.fromBytecode }, "later starts load the bytecode cache")
        val after = memory()

        fun median(values: List<Double>) = values.sorted()[values.size / 2]
        fun fmt(v: Double) = "%.1f".format(v)
        Log.i(Device.TAG, "cold-start bundle: ${bundle.source.length} chars, assets read ${fmt(loadMs)} ms, sha256 ${fmt(hashMs)} ms")
        Log.i(Device.TAG, "cold-start first (source, first in process): wall ${fmt(first.wallMs)} ms, ${first.startup}")
        Log.i(
            Device.TAG,
            "cold-start bytecode x${warm.size}: wall median ${fmt(median(warm.map { it.wallMs }))} ms " +
                "(create ${fmt(median(warm.map { it.startup.createMs }))}, eval ${fmt(median(warm.map { it.startup.evalMs }))}, " +
                "ready ${fmt(median(warm.map { it.startup.readyMs }))}); all ${warm.joinToString { fmt(it.wallMs) }}",
        )
        Log.i(
            Device.TAG,
            "cold-start bytecode cache $cacheBytes bytes; after GC, PSS / native heap KB: before ${before.first} / ${before.second}, " +
                "one core alive ${live?.first} / ${live?.second}, all closed ${after.first} / ${after.second}",
        )
    }
}
