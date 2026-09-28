package org.agentos.runtime.pi

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.agentos.runtime.net.BaseUrlCredentials
import org.agentos.runtime.net.HostFetch
import org.agentos.runtime.net.RetryPolicy
import org.agentos.runtime.ports.Credential
import org.agentos.runtime.pi.desktop.QuickJsJvmEngine
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The generated bundle and catalog, or null when `node build.mjs` has not been run.
 *
 * A bundle built from other sources than the current core/pi-runtime fails loudly instead of
 * producing confusing test failures (for example `Unknown op` after a protocol change): the
 * banner's `source=` hash must match [currentSourceHash]. The Gradle test task normally rebuilds
 * it first (`:app:bundlePiAgent`); the check covers `-Pagentos.skipPiBundle=true` and IDE runs.
 */
object PiAssets {
    val dir: File = File(System.getProperty("agentos.piAssetsDir").orEmpty())
    private val runtimeDir: File? = System.getProperty("agentos.piRuntimeDir")?.let(::File)?.takeIf { File(it, "build.mjs").isFile }

    val bundle: PiBundle?
        get() {
            val source = File(dir, "pi-agent.js").takeIf { it.isFile }?.readText() ?: return null
            val expected = runtimeDir?.let(::currentSourceHash) ?: return PiBundle(source)
            val embedded = SOURCE_HASH.find(source.take(2_000))?.groupValues?.get(1)
            check(embedded == expected) {
                "stale ${File(dir, "pi-agent.js")}: built from source ${embedded ?: "(unknown)"}, core/pi-runtime is now $expected; " +
                    "run `node build.mjs` in core/pi-runtime (or ./gradlew :app:bundlePiAgent)"
            }
            return PiBundle(source)
        }
    val catalog: ModelCatalog? get() = File(dir, "model-catalog.json").takeIf { it.isFile }?.let { ModelCatalog.parse(it.readText()) }
    const val MISSING = "no pi-agent.js: run `npm ci && node build.mjs` in core/pi-runtime"

    private val SOURCE_HASH = Regex("""\bsource=([0-9a-f]{64})\b""")

    /** Same rule as sourceHash() in core/pi-runtime/build.mjs. */
    fun currentSourceHash(runtimeDir: File): String {
        val files = listOf("build.mjs", "package.json", "package-lock.json") +
            File(runtimeDir, "src").walkTopDown().filter { it.isFile }.map { it.relativeTo(runtimeDir).invariantSeparatorsPath }
        val digest = MessageDigest.getInstance("SHA-256")
        for (rel in files.sorted()) {
            val fileHash = MessageDigest.getInstance("SHA-256").digest(File(runtimeDir, rel).readBytes()).joinToString("") { "%02x".format(it) }
            digest.update("$rel\u0000$fileHash\n".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * PiRuntime on the desktop QuickJS engine with a recording listener, three test tools and a
 * key-leak detector on everything that flows into JS.
 */
class PiHarness(
    keys: List<Pair<String, String>>,
    retry: RetryPolicy = RetryPolicy.NONE,
) : PiRuntimeListener, PiHost {

    class Ev(val sid: String, val e: JsonObject, val at: Long) {
        val type: String get() = e["type"]!!.jsonPrimitive.content
        val update: JsonObject? get() = e["update"] as? JsonObject
    }

    val events = CopyOnWriteArrayList<Ev>()
    val hostCalls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    val cancelledTools = CopyOnWriteArrayList<String>()
    /** Tool call ids whose host execution has begun (JS has registered its abort listener by then). */
    val startedTools = CopyOnWriteArrayList<String>()
    val outcomes = CopyOnWriteArrayList<ModelRequestOutcome>()
    val keyLeaks = CopyOnWriteArrayList<String>()
    val fetch = HostFetch(BaseUrlCredentials(keys.map { (base, key) -> BaseUrlCredentials.Entry(base, Credential(key)) }), retry = retry)
    val runtime = PiRuntime(QuickJsJvmEngine.factory, fetch, this, this)
    private val guarded = keys.map { it.second.toByteArray() }

    init {
        runtime.ingressObserver = { bytes -> if (guarded.any { indexOf(bytes, it) >= 0 }) keyLeaks += "key bytes flowed into JS" }
    }

    private var seq = 0

    suspend fun session(model: CatalogModel, messages: JsonArray? = null, tools: JsonArray = TOOLS, thinkingLevel: String? = null): String {
        val sid = "s${++seq}"
        runtime.createSession(sid, model.json, "You are a concise test agent.", tools, messages, thinkingLevel)
        return sid
    }

    fun eventsFor(sid: String): List<Ev> = events.filter { it.sid == sid }
    fun deltas(sid: String, kind: String = "text_delta"): List<Ev> = eventsFor(sid).filter { it.type == "message_update" && it.update?.get("type")?.jsonPrimitive?.content == kind }
    fun streamedText(sid: String): String = deltas(sid).joinToString("") { it.update!!["delta"]!!.jsonPrimitive.content }

    suspend fun awaitDelta(sid: String, timeoutMs: Long = 30_000) {
        val until = System.currentTimeMillis() + timeoutMs
        while (deltas(sid).isEmpty() && deltas(sid, "thinking_delta").isEmpty() && System.currentTimeMillis() < until) delay(10)
    }

    // PiRuntimeListener
    override fun onEvent(sid: String, event: JsonObject) { events += Ev(sid, event, System.currentTimeMillis()) }
    override fun onModelRequest(outcome: ModelRequestOutcome) { outcomes += outcome }

    // PiHost
    override suspend fun executeTool(sid: String, toolCallId: String, name: String, args: JsonElement): JsonObject {
        hostCalls += "tool" to buildJsonObject { put("name", name) }
        startedTools += toolCallId
        val a = args.jsonObject
        return when (name) {
            "add" -> textResult((a["a"]!!.jsonPrimitive.long + a["b"]!!.jsonPrimitive.long).toString())
            "secret" -> textResult("raw-secret-output")
            "slow" -> { delay(3_000); textResult("slow done") }
            else -> buildJsonObject { put("isError", true); put("content", buildJsonArray { add(text("unknown tool $name")) }) }
        }
    }

    override suspend fun beforeToolCall(sid: String, toolCallId: String, name: String, args: JsonElement): JsonObject? {
        hostCalls += "before" to buildJsonObject { put("name", name) }
        return if (name == "forbidden") buildJsonObject { put("block", true); put("reason", "blocked by policy") } else null
    }

    override suspend fun afterToolCall(sid: String, toolCallId: String, name: String, args: JsonElement, result: JsonElement, isError: Boolean): JsonObject? {
        hostCalls += "after" to buildJsonObject { put("name", name); put("result", result); put("isError", isError) }
        return if (name == "secret") buildJsonObject { put("content", buildJsonArray { add(text("redacted")) }) } else null
    }

    override fun onToolCancelled(sid: String, toolCallId: String) { cancelledTools += toolCallId }

    companion object {
        private fun text(t: String) = buildJsonObject { put("type", "text"); put("text", t) }
        private fun textResult(t: String) = buildJsonObject { put("content", buildJsonArray { add(text(t)) }) }

        private val INT_PARAMS = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("a", buildJsonObject { put("type", "integer") })
                put("b", buildJsonObject { put("type", "integer") })
            })
            put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("a")); add(kotlinx.serialization.json.JsonPrimitive("b")) })
        }

        private fun tool(name: String, description: String) = buildJsonObject {
            put("name", name); put("description", description); put("parameters", INT_PARAMS)
        }

        val TOOLS: JsonArray = buildJsonArray {
            add(tool("add", "Add two integers and return the sum."))
            add(tool("forbidden", "Always blocked by the host."))
            add(tool("secret", "Output rewritten by afterToolCall."))
            add(tool("slow", "Takes a few seconds."))
        }

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
