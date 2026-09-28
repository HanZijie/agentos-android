package org.agentos.runtime.pi.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake model endpoint speaking both API families pi-ai uses in AgentOS, with SSE streaming.
 * Kotlin port of core/pi-runtime/test/fake-llm.mjs, for JUnit and desktop runs.
 *
 *   POST {anthropicBaseUrl}/v1/messages      Anthropic Messages
 *   POST {openaiBaseUrl}/chat/completions    OpenAI Chat Completions
 *
 * Behaviour is scripted by directives in the latest user message:
 *   [tool:NAME A B]  first call -> tool_use NAME{a:A, b:B}; after the tool result -> "sum=<result text>"
 *   [slow]           40 text chunks, 100 ms apart (for abort)
 *   [fail500] / [fail429]   HTTP 500 / HTTP 429 with retry-after: 1
 *   otherwise        streams "echo:<user text> n=<message count>" in ~30 ms chunks
 * and, before any directive, by [failNext] (for host retry tests).
 *
 * Requests with a wrong key get 401. Every request is recorded in [requests].
 */
class FakeModelServer(val key: String = DEFAULT_KEY) : AutoCloseable {

    class Recorded(
        val id: Int,
        val api: String,
        val path: String,
        /** "real" (the configured key), "placeholder" (the unreplaced JS placeholder), "other", "none". */
        val presentedKeyKind: String,
        val headers: Map<String, List<String>>,
        val body: JsonObject,
        val startedAt: Long,
    ) {
        val stream: Boolean get() = body["stream"]?.jsonPrimitive?.booleanOrNull == true
        @Volatile var plan: String = ""
        @Volatile var status: Int = 0
        val chunkTimes: MutableList<Long> = CopyOnWriteArrayList()
        @Volatile var finished: Boolean = false
        @Volatile var closedEarly: Boolean = false
        @Volatile var closedAt: Long = 0
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    private class Failure(val status: Int, val retryAfter: String?)

    private val json = Json { ignoreUnknownKeys = true }
    private val executor: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "fake-llm").apply { isDaemon = true } }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val log = CopyOnWriteArrayList<Recorded>()
    private val seq = AtomicInteger()
    private val failures = ConcurrentLinkedQueue<Failure>()

    val baseUrl: String
    val anthropicBaseUrl: String get() = "$baseUrl/anthropic"
    val openaiBaseUrl: String get() = "$baseUrl/openai"
    val requests: List<Recorded> get() = log.toList()

    init {
        server.executor = executor
        server.createContext("/") { ex ->
            try {
                handle(ex)
            } catch (_: IOException) {
                // client went away
            } finally {
                runCatching { ex.close() }
            }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    /** The next [times] model requests answer [status] (with an optional `retry-after`) regardless of content. */
    fun failNext(status: Int, times: Int = 1, retryAfter: String? = null) {
        repeat(times) { failures.add(Failure(status, retryAfter)) }
    }

    fun reset() {
        log.clear()
        failures.clear()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    // ------------------------------------------------------------------ handling

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        val api = when {
            path.startsWith("/anthropic/") -> "anthropic"
            path.startsWith("/openai/") -> "openai"
            else -> null
        }
        if (api == null || ex.requestMethod != "POST") {
            ex.sendResponseHeaders(404, -1)
            return
        }
        val raw = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val body = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        val presented = if (api == "anthropic") {
            ex.requestHeaders.getFirst("x-api-key")
        } else {
            ex.requestHeaders.getFirst("authorization")?.removePrefix("Bearer ")?.removePrefix("bearer ")
        }
        val kind = when {
            presented == key -> "real"
            presented.isNullOrEmpty() -> "none"
            presented.contains("agentos-host-injected") -> "placeholder"
            else -> "other"
        }
        val rec = Recorded(seq.incrementAndGet(), api, path, kind, ex.requestHeaders.toMap(), body, System.currentTimeMillis())
        log.add(rec)

        if (kind != "real") {
            rec.plan = "auth"
            val err = if (api == "anthropic") {
                """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
            } else {
                """{"error":{"message":"Incorrect API key","type":"invalid_request_error"}}"""
            }
            sendJson(ex, rec, 401, err)
            return
        }
        failures.poll()?.let { f ->
            rec.plan = "fail${f.status}"
            f.retryAfter?.let { ex.responseHeaders.add("retry-after", it) }
            sendJson(ex, rec, f.status, errorBody(api, f.status))
            return
        }
        val conv = conversation(api, body)
        val plan = plan(conv)
        rec.plan = plan.kind
        if (plan.kind == "fail") {
            if (plan.status == 429) ex.responseHeaders.add("retry-after", "1")
            sendJson(ex, rec, plan.status, errorBody(api, plan.status))
            return
        }
        ex.responseHeaders.add("content-type", "text/event-stream")
        ex.responseHeaders.add("cache-control", "no-cache")
        rec.status = 200
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody
        fun send(s: String) {
            try {
                out.write(s.toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (e: IOException) {
                if (!rec.closedEarly) {
                    rec.closedEarly = true
                    rec.closedAt = System.currentTimeMillis()
                }
                throw e
            }
        }
        val model = body["model"]?.jsonPrimitive?.contentOrNull ?: "fake"
        try {
            if (api == "anthropic") streamAnthropic(rec, conv, plan, model, ::send) else streamOpenAi(rec, conv, plan, model, ::send)
            rec.finished = true
            out.close()
        } catch (e: IOException) {
            // client closed the connection (abort)
        }
    }

    private fun sendJson(ex: HttpExchange, rec: Recorded, status: Int, text: String) {
        rec.status = status
        rec.finished = true
        val bytes = text.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("content-type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun errorBody(api: String, status: Int): String = if (api == "anthropic") {
        """{"type":"error","error":{"type":"${if (status == 429) "rate_limit_error" else "api_error"}","message":"fake $status"}}"""
    } else {
        """{"error":{"message":"fake $status","type":"server_error"}}"""
    }

    private class Conversation(val lastKind: String, val lastText: String, val userText: String, val count: Int)

    private class Plan(val kind: String, val chunks: List<String> = emptyList(), val gapMs: Long = 0, val tool: String = "", val input: JsonObject = JsonObject(emptyMap()), val status: Int = 0)

    private fun textOf(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.joinToString("") { c ->
            val o = c as? JsonObject ?: return@joinToString ""
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> o["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                "tool_result" -> textOf(o["content"])
                else -> ""
            }
        }
        else -> ""
    }

    private fun isToolResultMessage(m: JsonObject): Boolean =
        (m["content"] as? JsonArray)?.any { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "tool_result" } == true

    private fun conversation(api: String, body: JsonObject): Conversation {
        val msgs = body["messages"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val last = msgs.lastOrNull() ?: JsonObject(emptyMap())
        val lastIsTool = if (api == "anthropic") isToolResultMessage(last) else last["role"]?.jsonPrimitive?.contentOrNull == "tool"
        val lastText = if (api == "anthropic" && lastIsTool) {
            (last["content"] as JsonArray).filter { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "tool_result" }
                .joinToString("") { textOf(it.jsonObject["content"]) }
        } else {
            textOf(last["content"])
        }
        var userText = lastText
        if (lastIsTool) {
            userText = msgs.lastOrNull { it["role"]?.jsonPrimitive?.contentOrNull == "user" && !isToolResultMessage(it) }
                ?.let { textOf(it["content"]) }.orEmpty()
        }
        return Conversation(if (lastIsTool) "tool" else "user", lastText, userText, msgs.size)
    }

    private fun plan(c: Conversation): Plan {
        val tool = TOOL_DIRECTIVE.find(c.userText)
        if (tool != null && c.lastKind == "user") {
            val input = buildJsonObject { put("a", tool.groupValues[2].toLong()); put("b", tool.groupValues[3].toLong()) }
            return Plan("tool", tool = tool.groupValues[1], input = input)
        }
        if (tool != null) return Plan("text", chunk("sum=${c.lastText}", 4), 20)
        if ("[fail500]" in c.userText) return Plan("fail", status = 500)
        if ("[fail429]" in c.userText) return Plan("fail", status = 429)
        if ("[slow]" in c.userText) return Plan("text", (0 until 40).map { "tick$it " }, 100)
        return Plan("text", chunk("echo:${c.userText} n=${c.count}", 8), 30)
    }

    private fun streamAnthropic(rec: Recorded, c: Conversation, p: Plan, model: String, send: (String) -> Unit) {
        fun ev(type: String, data: JsonObject) = send("event: $type\ndata: ${JsonObject(mapOf("type" to JsonPrimitive(type)) + data)}\n\n")
        ev("message_start", buildJsonObject {
            putJsonObject("message") {
                put("id", "msg_${rec.id}"); put("type", "message"); put("role", "assistant"); put("model", model)
                putJsonArray("content") {}
                put("stop_reason", null as String?); put("stop_sequence", null as String?)
                putJsonObject("usage") { put("input_tokens", 10 + c.count); put("output_tokens", 1) }
            }
        })
        if (p.kind == "tool") {
            ev("content_block_start", buildJsonObject {
                put("index", 0)
                putJsonObject("content_block") { put("type", "tool_use"); put("id", "toolu_${rec.id}"); put("name", p.tool); putJsonObject("input") {} }
            })
            for (part in chunk(p.input.toString(), 3)) {
                Thread.sleep(10)
                ev("content_block_delta", buildJsonObject { put("index", 0); putJsonObject("delta") { put("type", "input_json_delta"); put("partial_json", part) } })
            }
            ev("content_block_stop", buildJsonObject { put("index", 0) })
            ev("message_delta", buildJsonObject { putJsonObject("delta") { put("stop_reason", "tool_use"); put("stop_sequence", null as String?) }; putJsonObject("usage") { put("output_tokens", 12) } })
        } else {
            ev("content_block_start", buildJsonObject { put("index", 0); putJsonObject("content_block") { put("type", "text"); put("text", "") } })
            for (part in p.chunks) {
                Thread.sleep(p.gapMs)
                rec.chunkTimes.add(System.currentTimeMillis())
                ev("content_block_delta", buildJsonObject { put("index", 0); putJsonObject("delta") { put("type", "text_delta"); put("text", part) } })
            }
            ev("content_block_stop", buildJsonObject { put("index", 0) })
            ev("message_delta", buildJsonObject { putJsonObject("delta") { put("stop_reason", "end_turn"); put("stop_sequence", null as String?) }; putJsonObject("usage") { put("output_tokens", p.chunks.size) } })
        }
        ev("message_stop", JsonObject(emptyMap()))
    }

    private fun streamOpenAi(rec: Recorded, c: Conversation, p: Plan, model: String, send: (String) -> Unit) {
        val created = System.currentTimeMillis() / 1000
        fun data(choices: JsonArray, extra: Map<String, JsonElement> = emptyMap()) {
            val o = JsonObject(
                mapOf(
                    "id" to JsonPrimitive("chatcmpl-${rec.id}"),
                    "object" to JsonPrimitive("chat.completion.chunk"),
                    "created" to JsonPrimitive(created),
                    "model" to JsonPrimitive(model),
                    "choices" to choices,
                ) + extra,
            )
            send("data: $o\n\n")
        }
        fun choice(delta: JsonObject, finish: String?) = buildJsonArray {
            add(buildJsonObject { put("index", 0); put("delta", delta); put("finish_reason", finish) })
        }
        if (p.kind == "tool") {
            data(choice(buildJsonObject {
                put("role", "assistant")
                putJsonArray("tool_calls") {
                    add(buildJsonObject { put("index", 0); put("id", "call_${rec.id}"); put("type", "function"); putJsonObject("function") { put("name", p.tool); put("arguments", "") } })
                }
            }, null))
            for (part in chunk(p.input.toString(), 3)) {
                Thread.sleep(10)
                data(choice(buildJsonObject {
                    putJsonArray("tool_calls") { add(buildJsonObject { put("index", 0); putJsonObject("function") { put("arguments", part) } }) }
                }, null))
            }
            data(choice(JsonObject(emptyMap()), "tool_calls"))
        } else {
            data(choice(buildJsonObject { put("role", "assistant"); put("content", "") }, null))
            for (part in p.chunks) {
                Thread.sleep(p.gapMs)
                rec.chunkTimes.add(System.currentTimeMillis())
                data(choice(buildJsonObject { put("content", part) }, null))
            }
            data(choice(JsonObject(emptyMap()), "stop"))
        }
        data(JsonArray(emptyList()), mapOf("usage" to buildJsonObject { put("prompt_tokens", 10 + c.count); put("completion_tokens", 5); put("total_tokens", 15 + c.count) }))
        send("data: [DONE]\n\n")
    }

    companion object {
        const val DEFAULT_KEY: String = "sk-fake-s8-not-a-secret"
        private val TOOL_DIRECTIVE = Regex("""\[tool:([A-Za-z_][\w-]*)\s+(-?\d+)\s+(-?\d+)]""")

        /** Splits by code points: real servers never cut a surrogate pair across SSE events. */
        fun chunk(text: String, parts: Int): List<String> {
            val cps = text.codePoints().toArray()
            val size = maxOf(1, (cps.size + parts - 1) / parts)
            return (cps.indices step size).map { i -> String(cps, i, minOf(size, cps.size - i)) }
        }
    }
}
