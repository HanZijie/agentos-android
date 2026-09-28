package org.agentos.spike.s8

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.TimeUnit

/** A model target for real-endpoint runs. The key is read from the environment by the caller. */
class RealTarget(val name: String, val model: JSONObject, val key: String)

/**
 * The S8 scenarios, shared by the JVM desktop runner and the Android test app.
 * Mirrors bundle/test/contract.mjs so all three hosts answer the same questions.
 */
class S8Runner(
    private val bundleSource: String,
    catalogJson: String,
    private val fakeBase: String,
    private val fakeKey: String,
    private val newDispatcher: () -> CoroutineDispatcher,
    private val log: (String) -> Unit,
    private val platformMemory: () -> JSONObject = { JSONObject() },
    /** True when the fake server and this process share a clock (desktop). */
    private val sameClock: Boolean = false,
) {
    private val catalog = JSONObject(catalogJson)
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    val results = JSONArray()

    class Ev(val sid: String, val e: JSONObject, val at: Long)

    // ------------------------------------------------------------------ helpers
    fun presetModel(provider: String, api: String): JSONObject {
        val providers = catalog.getJSONArray("providers")
        for (i in 0 until providers.length()) {
            val p = providers.getJSONObject(i)
            if (p.getString("id") != provider) continue
            val models = p.getJSONArray("models")
            for (j in 0 until models.length()) if (models.getJSONObject(j).getString("api") == api) return JSONObject(models.getJSONObject(j).toString())
            return JSONObject(models.getJSONObject(0).toString())
        }
        error("preset $provider missing")
    }

    fun customModel(api: String, id: String, baseUrl: String): JSONObject =
        JSONObject(catalog.getJSONObject("customTemplates").getJSONObject(api).toString())
            .put("id", id).put("name", id).put("baseUrl", baseUrl)

    private suspend fun check(name: String, block: suspend () -> JSONObject?) {
        val t0 = System.nanoTime()
        val entry = JSONObject().put("name", name)
        try {
            val info = block()
            entry.put("ok", true).put("ms", (System.nanoTime() - t0) / 1_000_000)
            if (info != null) entry.put("info", info)
            log("PASS $name ${info ?: ""}")
        } catch (e: Throwable) {
            entry.put("ok", false).put("ms", (System.nanoTime() - t0) / 1_000_000).put("error", e.toString())
            log("FAIL $name: $e")
        }
        results.put(entry)
    }

    private inline fun assertThat(cond: Boolean, msg: () -> String) {
        if (!cond) throw AssertionError(msg())
    }

    private suspend fun fakeLog(): JSONArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$fakeBase/__log").build()).execute().use { JSONArray(it.body!!.string()) }
    }

    private fun JSONArray.objects(from: Int = 0): List<JSONObject> = (from until length()).map { getJSONObject(it) }

    inner class Harness(val engine: PiJsEngine, val events: MutableList<Ev>, val violations: MutableList<String>) {
        private var seq = 0
        val tools = JSONArray().put(
            JSONObject().put("name", "add").put("description", "Add two integers and return the sum.")
                .put("parameters", JSONObject("""{"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}},"required":["a","b"]}""")),
        )

        suspend fun newSession(model: JSONObject, messages: JSONArray? = null, prefix: String = "s"): String {
            val sid = "$prefix${++seq}"
            val payload = JSONObject().put("sid", sid).put("model", model).put("systemPrompt", "You are a concise test agent.").put("tools", tools)
            if (messages != null) payload.put("messages", messages)
            engine.request("create", payload)
            return sid
        }

        suspend fun prompt(sid: String, text: String): JSONObject = engine.requestObject("prompt", JSONObject().put("sid", sid).put("text", text))
        fun eventsFor(sid: String): List<Ev> = synchronized(events) { events.filter { it.sid == sid } }
        fun deltas(sid: String) = eventsFor(sid).filter { it.e.optString("type") == "message_update" && it.e.optJSONObject("update")?.optString("type") == "text_delta" }
    }

    fun newHarness(bundle: JsBundle, credentials: CredentialProvider, keysToGuard: List<String>): Harness {
        val events = Collections.synchronizedList(mutableListOf<Ev>())
        val violations = Collections.synchronizedList(mutableListOf<String>())
        val fetch = HostFetch(credentials)
        val guarded = keysToGuard.map { it.toByteArray() }
        fetch.ingressAudit = { bytes -> for (k in guarded) if (indexOf(bytes, k) >= 0) violations.add("key bytes flowed into JS") }
        val host = object : HostCalls {
            override suspend fun call(method: String, payload: JSONObject): JSONObject? = when (method) {
                "tool" -> {
                    val args = payload.getJSONObject("args")
                    if (payload.getString("name") == "add") JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", (args.getLong("a") + args.getLong("b")).toString())))
                    else JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "unknown tool")))
                }
                "beforeToolCall" -> JSONObject()
                else -> error("unknown host call $method")
            }
        }
        val engine = PiJsEngine(bundle, fetch, host, { sid, e -> events.add(Ev(sid, e, System.currentTimeMillis())) }, newDispatcher()) { level, msg ->
            if (level == "error" || level == "warn") log("[js $level] $msg")
        }
        return Harness(engine, events, violations)
    }

    // ------------------------------------------------------------------ contract (fake endpoint)
    suspend fun runContract(bundle: JsBundle = JsBundle.Source(bundleSource)): JSONObject {
        val families = listOf(
            "anthropic-messages" to presetModel("minimax", "anthropic-messages").put("baseUrl", "$fakeBase/anthropic"),
            "openai-completions" to presetModel("deepseek", "openai-completions").put("baseUrl", "$fakeBase/openai"),
        )
        val h = newHarness(bundle, PrefixCredentials(families.map { it.second.getString("baseUrl") to fakeKey }), listOf(fakeKey))
        val startup = h.engine.start()
        log("startup $startup")
        check("ping") { h.engine.requestObject("ping") }

        for ((tag, model) in families) {
            check("$tag: streaming text arrives chunk by chunk") {
                val sid = h.newSession(model)
                val n0 = fakeLog().length()
                val t0 = System.currentTimeMillis()
                val r = h.prompt(sid, "[echo] hello-$tag")
                val tEnd = System.currentTimeMillis()
                val d = h.deltas(sid)
                val req = fakeLog().objects(n0).first()
                assertThat(r.optString("stopReason") == "stop") { "stopReason ${r.optString("stopReason")} ${r.optString("errorMessage")}" }
                assertThat(r.getString("text").startsWith("echo:[echo] hello-$tag")) { "text ${r.getString("text")}" }
                assertThat(d.size >= 4) { "deltas ${d.size}" }
                val spread = d.last().at - d.first().at
                assertThat(spread >= 100) { "deltas arrived together (spread ${spread}ms): not streamed" }
                if (sameClock) assertThat(d.first().at < req.getJSONArray("chunkTimes").getLong(req.getJSONArray("chunkTimes").length() - 1)) { "first delta after last server chunk" }
                assertThat(req.getBoolean("stream") && req.getString("presentedKeyKind") == "real") { "server request ${req.optString("presentedKeyKind")}" }
                val types = h.eventsFor(sid).map { it.e.getString("type") }.toSet()
                for (t in listOf("agent_start", "turn_start", "message_start", "message_update", "message_end", "turn_end", "agent_end")) assertThat(t in types) { "missing $t" }
                JSONObject().put("deltas", d.size).put("deltaSpreadMs", spread).put("firstDeltaBeforeEndMs", tEnd - d.first().at).put("turnMs", tEnd - t0)
            }

            check("$tag: tool call round trip") {
                val sid = h.newSession(model)
                val n0 = fakeLog().length()
                val r = h.prompt(sid, "[tool:add 2 3] please add")
                val ev = h.eventsFor(sid).map { it.e }
                val start = ev.firstOrNull { it.getString("type") == "tool_execution_start" }
                val end = ev.firstOrNull { it.getString("type") == "tool_execution_end" }
                assertThat(start != null && start.getString("toolName") == "add" && start.getJSONObject("args").getInt("a") == 2) { "tool start $start" }
                assertThat(end != null && !end.getBoolean("isError") && end.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text") == "5") { "tool end $end" }
                assertThat(fakeLog().length() - n0 == 2) { "requests ${fakeLog().length() - n0}" }
                assertThat(r.getString("text") == "sum=5") { "text ${r.getString("text")}" }
                null
            }

            check("$tag: abort mid-stream ends as aborted with no request left") {
                val sid = h.newSession(model)
                val n0 = fakeLog().length()
                val r = coroutineScope {
                    val p = async { h.prompt(sid, "[slow] long answer") }
                    var i = 0
                    while (h.deltas(sid).isEmpty() && i++ < 200) delay(20)
                    val tAbort = System.currentTimeMillis()
                    h.engine.request("abort", JSONObject().put("sid", sid))
                    val res = p.await()
                    res.put("abortToReplyMs", System.currentTimeMillis() - tAbort)
                }
                delay(300)
                val req = fakeLog().objects(n0).first()
                val stats = h.engine.requestObject("stats")
                assertThat(r.optString("stopReason") == "aborted") { "stopReason ${r.optString("stopReason")}" }
                assertThat(req.getBoolean("closedEarly")) { "server did not see the connection close" }
                assertThat(h.engine.fetch.inflight == 0 && stats.getInt("inflightFetches") == 0) { "inflight host=${h.engine.fetch.inflight} js=${stats.getInt("inflightFetches")}" }
                JSONObject().put("abortToReplyMs", r.getLong("abortToReplyMs"))
            }

            for (status in listOf(500, 429)) check("$tag: HTTP $status is not retried") {
                val sid = h.newSession(model)
                val n0 = fakeLog().length()
                val r = h.prompt(sid, "[fail$status]")
                delay(1500)
                assertThat(r.optString("stopReason") == "error") { "stopReason ${r.optString("stopReason")}" }
                assertThat(fakeLog().length() - n0 == 1) { "requests ${fakeLog().length() - n0}" }
                JSONObject().put("errorMessage", r.optString("errorMessage").take(80))
            }

            check("$tag: rebuilt Agent from saved messages sends identical context") {
                val a = h.newSession(model)
                h.prompt(a, "[echo] first turn")
                val history = h.engine.request("history", JSONObject().put("sid", a)) as JSONArray
                val b = h.newSession(model, JSONArray(history.toString()))
                val n0 = fakeLog().length()
                val ra = h.prompt(a, "[echo] second turn")
                val rb = h.prompt(b, "[echo] second turn")
                val (qa, qb) = fakeLog().objects(n0)
                fun strip(o: JSONObject): String { val b0 = o.getJSONObject("body"); return JSONObject().put("system", b0.opt("system")).put("messages", b0.opt("messages")).put("tools", b0.opt("tools")).toString() }
                assertThat(strip(qa) == strip(qb)) { "context differs" }
                assertThat(ra.getString("text") == rb.getString("text")) { "${ra.getString("text")} vs ${rb.getString("text")}" }
                JSONObject().put("historyMessages", history.length())
            }

            check("$tag: 3 concurrent sessions in one runtime do not cross-talk") {
                val sids = (0 until 3).map { h.newSession(model) }
                val replies = coroutineScope { sids.mapIndexed { i, sid -> async { h.prompt(sid, "[echo] marker-$tag-$i") } }.awaitAll() }
                replies.forEachIndexed { i, r ->
                    val streamed = h.deltas(sids[i]).joinToString("") { it.e.getJSONObject("update").getString("delta") }
                    assertThat(r.getString("text").contains("marker-$tag-$i")) { "reply $i ${r.getString("text")}" }
                    assertThat(streamed == r.getString("text")) { "streamed text of ${sids[i]} differs" }
                }
                val spans = sids.map { sid -> h.eventsFor(sid).let { it.first().at to it.last().at } }
                val overlap = spans.minOf { it.second } - spans.maxOf { it.first }
                assertThat(overlap > 0) { "sessions did not overlap" }
                JSONObject().put("overlapMs", overlap)
            }
        }

        check("key never enters JS; placeholder never reaches the server") {
            assertThat(h.violations.isEmpty()) { "violations ${h.violations.size}" }
            val kinds = fakeLog().objects().map { it.getString("presentedKeyKind") }.toSet()
            assertThat(kinds == setOf("real")) { "server saw $kinds" }
            null
        }
        check("no host requests left over") {
            delay(200)
            val stats = h.engine.requestObject("stats")
            assertThat(h.engine.fetch.inflight == 0 && stats.getInt("inflightFetches") == 0) { "inflight" }
            JSONObject().put("jsTimers", stats.getInt("activeTimers")).put("hostTimers", h.engine.activeHostTimers)
                .put("fetches", h.engine.fetch.started.get()).put("aborts", h.engine.fetch.aborted.get())
        }
        val pumpAliveBeforeFault = h.engine.mainError == null
        check("unhandled rejection kills the pump: fault is detected, a rebuilt runtime continues the session") {
            val model = families[0].second
            val sid = h.newSession(model)
            h.prompt(sid, "[echo] before fault")
            val history = h.engine.request("history", JSONObject().put("sid", sid)) as JSONArray
            runCatching { kotlinx.coroutines.withTimeout(5_000) { h.engine.requestObject("debugUnhandledRejection") } }
            delay(200)
            val died = h.engine.mainError != null
            val tPing = System.nanoTime()
            val ping = runCatching { kotlinx.coroutines.withTimeout(5_000) { h.engine.requestObject("ping") } }
            val pingFailMs = (System.nanoTime() - tPing) / 1e6
            // Recovery = the F8 path: new runtime, Agents rebuilt from the saved messages, no replay.
            val t0 = System.nanoTime()
            val h2 = newHarness(bundle, PrefixCredentials(families.map { it.second.getString("baseUrl") to fakeKey }), listOf(fakeKey))
            h2.engine.start()
            val sid2 = h2.newSession(model, JSONArray(history.toString()))
            val recoveryMs = (System.nanoTime() - t0) / 1e6
            val r = h2.prompt(sid2, "[echo] after fault")
            h2.engine.stop()
            assertThat(died && ping.isFailure && pingFailMs < 100) { "fault not detected quickly (died=$died ping=${ping.isSuccess} ${pingFailMs}ms)" }
            val n = Regex("n=(\\d+)").find(r.optString("text"))?.groupValues?.get(1)?.toInt() ?: -1
            assertThat(r.optString("stopReason") == "stop" && r.getString("text").contains("after fault") && n >= 3) { "rebuilt session reply ${r.optString("text")}" }
            JSONObject().put("pumpDied", died).put("laterRequestFailsInMs", pingFailMs).put("rebuildRuntimeAndSessionMs", recoveryMs)
                .put("mainError", h.engine.mainError?.toString()?.lineSequence()?.firstOrNull())
        }
        h.engine.stop()
        val failed = (0 until results.length()).count { !results.getJSONObject(it).getBoolean("ok") }
        return JSONObject().put("startup", JSONObject().put("createMs", startup.createMs).put("evalMs", startup.evalMs).put("readyMs", startup.readyMs))
            .put("pumpAliveBeforeFaultInjection", pumpAliveBeforeFault).put("passed", results.length() - failed).put("total", results.length()).put("results", results)
    }

    // ------------------------------------------------------------------ measurements
    suspend fun runMeasurements(): JSONObject {
        val out = JSONObject()
        val anthropicFake = presetModel("minimax", "anthropic-messages").put("baseUrl", "$fakeBase/anthropic")
        val creds = PrefixCredentials(listOf("$fakeBase/" to fakeKey))
        out.put("bundleBytes", bundleSource.toByteArray().size)

        // 1. Cold start from source, fresh runtime each time.
        val source = JSONArray()
        repeat(5) {
            val h = newHarness(JsBundle.Source(bundleSource), creds, emptyList())
            val t = h.engine.start()
            source.put(JSONObject().put("createMs", t.createMs).put("evalMs", t.evalMs).put("readyMs", t.readyMs).put("totalMs", t.totalMs))
            h.engine.stop()
        }
        out.put("startupFromSource", source)

        // 2. Bytecode: compile once, then start from bytecode.
        val tc = System.nanoTime()
        val bytecode = PiJsEngine.compile(bundleSource, newDispatcher())
        out.put("compileMs", (System.nanoTime() - tc) / 1e6).put("bytecodeBytes", bytecode.size)
        val bc = JSONArray()
        repeat(5) {
            val h = newHarness(JsBundle.Bytecode(bytecode), creds, emptyList())
            val t = h.engine.start()
            bc.put(JSONObject().put("createMs", t.createMs).put("evalMs", t.evalMs).put("readyMs", t.readyMs).put("totalMs", t.totalMs))
            h.engine.stop()
        }
        out.put("startupFromBytecode", bc)

        // 3. Memory: one runtime, sessions added one by one (each runs one turn).
        run {
            val platform0 = platformMemory()
            val h = newHarness(JsBundle.Source(bundleSource), creds, emptyList())
            h.engine.start()
            suspend fun mem(): JSONObject = withContext(Dispatchers.Default) {
                h.engine.quickJs.gc()
                val m = h.engine.quickJs.memoryUsage
                JSONObject().put("memoryUsedSize", m.memoryUsedSize).put("mallocSize", m.mallocSize).put("objCount", m.objCount)
            }
            val base = mem()
            val platformLoaded = platformMemory()
            val steps = JSONArray().put(base)
            val sids = mutableListOf<String>()
            for (i in 1..10) {
                val sid = h.newSession(anthropicFake)
                sids += sid
                h.prompt(sid, "[echo] memory probe $i with some extra words to make a realistic turn")
                steps.put(mem())
            }
            val after10 = steps.getJSONObject(10)
            for (sid in sids) h.engine.request("dispose", JSONObject().put("sid", sid))
            val afterDispose = mem()
            out.put("memoryOneRuntime", JSONObject()
                .put("afterLoad", base)
                .put("steps", steps)
                .put("perSessionAvgBytes", (after10.getLong("memoryUsedSize") - base.getLong("memoryUsedSize")) / 10)
                .put("afterDispose", afterDispose)
                .put("platformBeforeLoad", platform0)
                .put("platformAfterLoad", platformLoaded))

            // 4. Per-turn overhead: turn through Pi vs. the same streamed response fetched directly.
            val sid = h.newSession(anthropicFake)
            val turns = mutableListOf<Double>()
            val firstDelta = mutableListOf<Double>()
            val direct = mutableListOf<Double>()
            val directFirstByte = mutableListOf<Double>()
            repeat(12) { i ->
                val before = h.deltas(sid).size
                val t0 = System.nanoTime()
                val t0Wall = System.currentTimeMillis()
                h.prompt(sid, "[echo] warm $i")
                turns += (System.nanoTime() - t0) / 1e6
                h.deltas(sid).getOrNull(before)?.let { firstDelta += (it.at - t0Wall).toDouble() }
                val body = """{"model":"x","stream":true,"messages":[{"role":"user","content":"[echo] warm $i"}]}"""
                val t1 = System.nanoTime()
                withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url("$fakeBase/anthropic/v1/messages").header("x-api-key", fakeKey)
                        .post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { res ->
                        val src = res.body!!.source()
                        src.require(1)
                        directFirstByte += (System.nanoTime() - t1) / 1e6
                        src.readByteArray()
                    }
                }
                direct += (System.nanoTime() - t1) / 1e6
            }
            fun med(l: List<Double>) = l.drop(2).sorted()[(l.size - 2) / 2]
            out.put("perTurn", JSONObject()
                .put("turnMedianMs", med(turns)).put("directMedianMs", med(direct))
                .put("overheadMedianMs", med(turns) - med(direct))
                .put("firstDeltaMedianMs", med(firstDelta)).put("directFirstByteMedianMs", med(directFirstByte))
                .put("turnsMs", JSONArray(turns)).put("directMs", JSONArray(direct))
                .put("note", "fake endpoint streams 8 chunks 30 ms apart; first 2 iterations dropped as warm-up"))

            // 5. Concurrency: 10 sessions in one runtime at once.
            val many = (0 until 10).map { h.newSession(anthropicFake, prefix = "c") }
            val t0 = System.nanoTime()
            val replies = coroutineScope { many.mapIndexed { i, s -> async { h.prompt(s, "[echo] conc-$i") } }.awaitAll() }
            val ok = replies.withIndex().all { (i, r) -> r.getString("text").contains("conc-$i") }
            out.put("concurrent10", JSONObject().put("wallMs", (System.nanoTime() - t0) / 1e6).put("allCorrect", ok).put("singleTurnMedianMs", med(turns)))
            h.engine.stop()
        }

        // 6. Many runtimes: what a runtime-per-session layout costs.
        run {
            val platform0 = platformMemory()
            val hs = (0 until 4).map { newHarness(JsBundle.Source(bundleSource), creds, emptyList()) }
            val perRuntime = JSONArray()
            for (h in hs) {
                h.engine.start()
                val sid = h.newSession(anthropicFake)
                h.prompt(sid, "[echo] runtime probe")
                val m = withContext(Dispatchers.Default) { h.engine.quickJs.gc(); h.engine.quickJs.memoryUsage }
                perRuntime.put(JSONObject().put("memoryUsedSize", m.memoryUsedSize).put("mallocSize", m.mallocSize))
            }
            val platform1 = platformMemory()
            hs.forEach { it.engine.stop() }
            out.put("memoryPerRuntime", JSONObject().put("runtimes", perRuntime).put("platformBefore", platform0).put("platformWith4Runtimes", platform1))
        }
        return out
    }

    // ------------------------------------------------------------------ real endpoints
    suspend fun runReal(targets: List<RealTarget>): JSONObject {
        val out = JSONObject()
        for (t in targets) {
            val h = newHarness(JsBundle.Source(bundleSource), PrefixCredentials(listOf(t.model.getString("baseUrl") to t.key)), listOf(t.key))
            h.engine.start()
            val entry = JSONObject().put("model", t.model.getString("id")).put("baseUrl", t.model.getString("baseUrl"))
            runCatching {
                val sid = h.newSession(t.model)
                val t0 = System.currentTimeMillis()
                val r = h.prompt(sid, "Reply with the single word: pong")
                val d = h.deltas(sid)
                entry.put("chat", JSONObject().put("stopReason", r.optString("stopReason")).put("error", r.optString("errorMessage"))
                    .put("text", r.optString("text").take(120)).put("deltas", d.size).put("ms", System.currentTimeMillis() - t0)
                    .put("firstDeltaMs", d.firstOrNull()?.let { it.at - t0 }))
            }.onFailure { entry.put("chat", JSONObject().put("exception", it.toString())) }
            runCatching {
                val sid = h.newSession(t.model)
                val r = h.prompt(sid, "Use the add tool to add 2 and 3. Then reply with only the result.")
                val tools = h.eventsFor(sid).map { it.e }.filter { it.getString("type") == "tool_execution_end" }
                entry.put("tool", JSONObject().put("stopReason", r.optString("stopReason")).put("error", r.optString("errorMessage"))
                    .put("toolCalls", tools.size).put("toolResult", tools.firstOrNull()?.optJSONObject("result")?.optJSONArray("content")?.optJSONObject(0)?.optString("text"))
                    .put("text", r.optString("text").take(120)))
            }.onFailure { entry.put("tool", JSONObject().put("exception", it.toString())) }
            runCatching {
                val sid = h.newSession(t.model)
                val r = coroutineScope {
                    val p = async { h.prompt(sid, "Count from 1 to 300, one number per line, no other text.") }
                    var i = 0
                    while (h.deltas(sid).isEmpty() && i++ < 1500) delay(20)
                    h.engine.request("abort", JSONObject().put("sid", sid))
                    p.await()
                }
                delay(300)
                entry.put("abort", JSONObject().put("stopReason", r.optString("stopReason")).put("inflightAfter", h.engine.fetch.inflight).put("deltasBeforeAbort", h.deltas(sid).size))
            }.onFailure { entry.put("abort", JSONObject().put("exception", it.toString())) }
            entry.put("keyViolations", h.violations.size).put("httpRequests", h.engine.fetch.started.get())
            h.engine.stop()
            out.put(t.name, entry)
            log("REAL ${t.name}: $entry")
        }
        return out
    }

    companion object {
        fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || haystack.size < needle.size) return -1
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
