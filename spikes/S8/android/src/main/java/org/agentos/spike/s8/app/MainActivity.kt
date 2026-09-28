package org.agentos.spike.s8.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agentos.spike.s8.RealTarget
import org.agentos.spike.s8.S8Runner
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * S8 test app. Launch with:
 *   adb reverse tcp:8787 tcp:8787
 *   adb shell am start -n org.agentos.spike.s8/org.agentos.spike.s8.app.MainActivity --es modes contract,measure
 * Real endpoints (keys come from the caller's environment, passed as extras, never stored):
 *   --es modes real --es minimaxKey "$MINIMAX_API_KEY" [--es compatUrl ... --es compatKey ... --es compatModel ...]
 * Results: logcat tag S8, and files/s8-results.json (adb shell run-as org.agentos.spike.s8 cat files/s8-results.json).
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { setTextIsSelectable(true); textSize = 11f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(text) })
        val extras = intent.extras ?: Bundle()
        val modes = (extras.getString("modes") ?: "contract,measure").split(",").map { it.trim() }.toSet()
        val fakeBase = extras.getString("fakeBase") ?: "http://127.0.0.1:8787"
        val minimaxKey = extras.getString("minimaxKey")
        val compat = Triple(extras.getString("compatUrl"), extras.getString("compatKey"), extras.getString("compatModel"))
        intent.replaceExtras(Bundle()) // do not keep keys around in the intent

        scope.launch {
            val out = withContext(Dispatchers.Default) { run(modes, fakeBase, minimaxKey, compat) }
            val file = File(filesDir, "s8-results.json")
            file.writeText(out.toString(2))
            // Release builds are not debuggable (no run-as), so also dump the JSON to logcat in chunks.
            val json = out.toString()
            json.chunked(3000).forEachIndexed { i, part -> Log.i("S8JSON", "%04d %s".format(i, part)) }
            log("S8DONE ${file.absolutePath}")
        }
    }

    private suspend fun run(modes: Set<String>, fakeBase: String, minimaxKey: String?, compat: Triple<String?, String?, String?>): JSONObject {
        val t0 = System.nanoTime()
        val source = assets.open("pi-agent.js").bufferedReader().use { it.readText() }
        val assetReadMs = (System.nanoTime() - t0) / 1e6
        val catalog = assets.open("model-catalog.json").bufferedReader().use { it.readText() }
        val threadNo = AtomicInteger()
        val runner = S8Runner(
            bundleSource = source,
            catalogJson = catalog,
            fakeBase = fakeBase,
            fakeKey = "sk-fake-s8-not-a-secret",
            newDispatcher = { Executors.newSingleThreadExecutor { r -> Thread(null, r, "pi-js-${threadNo.incrementAndGet()}", 4L * 1024 * 1024) }.asCoroutineDispatcher() },
            log = ::log,
            platformMemory = ::memory,
            sameClock = false,
        )
        val out = JSONObject()
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.joinToString()}")
            .put("fingerprint", Build.FINGERPRINT)
            .put("isEmulator", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.MODEL.contains("sdk_gphone"))
            .put("assetReadMs", assetReadMs)
        log("device: ${out.getString("device")}")
        if ("contract" in modes) out.put("contract", runner.runContract())
        if ("measure" in modes) out.put("measure", runner.runMeasurements())
        if ("real" in modes) {
            val targets = mutableListOf<RealTarget>()
            val skipped = mutableListOf<String>()
            if (!minimaxKey.isNullOrBlank()) {
                targets += RealTarget("minimax", runner.presetModel("minimax", "anthropic-messages"), minimaxKey)
                targets += RealTarget("minimax-cn", runner.presetModel("minimax-cn", "anthropic-messages"), minimaxKey)
            } else skipped += "minimax, minimax-cn: no key"
            val (url, key, model) = compat
            if (!url.isNullOrBlank() && !key.isNullOrBlank() && !model.isNullOrBlank()) targets += RealTarget("openai-compat", runner.customModel("openai-completions", model, url), key)
            else skipped += "openai-compat: not configured"
            out.put("real", runner.runReal(targets).put("skipped", skipped))
        }
        return out
    }

    private fun memory(): JSONObject {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        val rt = Runtime.getRuntime()
        return JSONObject()
            .put("nativeHeapAllocated", Debug.getNativeHeapAllocatedSize())
            .put("javaHeapUsed", rt.totalMemory() - rt.freeMemory())
            .put("totalPssKb", mi.totalPss)
    }

    private fun log(line: String) {
        Log.i("S8", line)
        runOnUiThread { text.append(line + "\n") }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
