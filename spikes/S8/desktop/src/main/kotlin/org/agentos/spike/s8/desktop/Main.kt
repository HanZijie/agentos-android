package org.agentos.spike.s8.desktop

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.agentos.spike.s8.RealTarget
import org.agentos.spike.s8.S8Runner
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the S8 bundle on QuickJS in the JVM (quickjs-kt-jvm), with the same Kotlin
 * host code (HostFetch / PiJsEngine) as the Android test app.
 *
 *   node bundle/test/fake-llm.mjs --port 8787 &
 *   ./gradlew :desktop:run --args="contract measure real"
 *
 * Real endpoints use MINIMAX_API_KEY (minimax + minimax-cn presets) and
 * OPENAI_COMPAT_BASE_URL / OPENAI_COMPAT_API_KEY / OPENAI_COMPAT_MODEL; missing
 * variables mean the target is skipped (and reported as skipped).
 */
fun main(args: Array<String>) {
    val code = runBlocking { runAll(args) }
    kotlin.system.exitProcess(code)
}

private suspend fun runAll(args: Array<String>): Int {
    val modes = args.toSet().ifEmpty { setOf("contract", "measure") }
    val root = File(System.getProperty("user.dir"))
    val dist = File(root, "bundle/dist")
    val fakeBase = System.getenv("FAKE_LLM_URL") ?: "http://127.0.0.1:8787"
    val fakeKey = System.getenv("FAKE_LLM_KEY") ?: "sk-fake-s8-not-a-secret"
    val threadNo = AtomicInteger()
    val runner = S8Runner(
        bundleSource = File(dist, "pi-agent.js").readText(),
        catalogJson = File(dist, "model-catalog.json").readText(),
        fakeBase = fakeBase,
        fakeKey = fakeKey,
        newDispatcher = { Executors.newSingleThreadExecutor { r -> Thread(r, "pi-js-${threadNo.incrementAndGet()}").apply { isDaemon = true } }.asCoroutineDispatcher() },
        log = { println(it) },
        platformMemory = {
            val rt = Runtime.getRuntime()
            JSONObject().put("jvmUsedBytes", rt.totalMemory() - rt.freeMemory())
        },
        sameClock = true,
    )
    val out = JSONObject()
        .put("host", "quickjs-kt-jvm on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, JDK ${System.getProperty("java.version")}")
    if ("contract" in modes) out.put("contract", runner.runContract())
    if ("measure" in modes) out.put("measure", runner.runMeasurements())
    if ("real" in modes) {
        val targets = mutableListOf<RealTarget>()
        val skipped = mutableListOf<String>()
        val minimaxKey = System.getenv("MINIMAX_API_KEY")
        if (!minimaxKey.isNullOrBlank()) {
            targets += RealTarget("minimax", runner.presetModel("minimax", "anthropic-messages"), minimaxKey)
            targets += RealTarget("minimax-cn", runner.presetModel("minimax-cn", "anthropic-messages"), minimaxKey)
        } else skipped += "minimax, minimax-cn: MINIMAX_API_KEY not set"
        val compatUrl = System.getenv("OPENAI_COMPAT_BASE_URL")
        val compatKey = System.getenv("OPENAI_COMPAT_API_KEY")
        val compatModel = System.getenv("OPENAI_COMPAT_MODEL")
        if (!compatUrl.isNullOrBlank() && !compatKey.isNullOrBlank() && !compatModel.isNullOrBlank()) {
            targets += RealTarget("openai-compat", runner.customModel("openai-completions", compatModel, compatUrl), compatKey)
        } else skipped += "openai-compat: OPENAI_COMPAT_BASE_URL / OPENAI_COMPAT_API_KEY / OPENAI_COMPAT_MODEL not set"
        out.put("real", runner.runReal(targets).put("skipped", skipped))
    }
    if ("probe" in modes) {
        // No key needed: an invalid key against the real MiniMax endpoints exercises OkHttp/TLS,
        // the SDK's handling of a real error response, and "no retry" — but is not a model call.
        val invalid = "invalid-key-s8-probe"
        out.put("probe", runner.runReal(listOf(
            RealTarget("minimax (invalid key)", runner.presetModel("minimax", "anthropic-messages"), invalid),
            RealTarget("minimax-cn (invalid key)", runner.presetModel("minimax-cn", "anthropic-messages"), invalid),
        )))
    }
    val file = File(root, "desktop/build/s8-desktop-results.json")
    file.parentFile.mkdirs()
    file.writeText(out.toString(2))
    println("results -> ${file.path}")
    val contract = out.optJSONObject("contract")
    if (contract != null) println("contract: ${contract.getInt("passed")}/${contract.getInt("total")} passed, pump alive before fault injection: ${contract.getBoolean("pumpAliveBeforeFaultInjection")}")
    return if (contract != null && contract.getInt("passed") != contract.getInt("total")) 1 else 0
}
