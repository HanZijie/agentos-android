package org.agentos.runtime.pi.testing

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
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.testing.FakeScripts
import org.agentos.runtime.testing.FakeStep
import org.agentos.runtime.testing.FakeTurnScript
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake model endpoint speaking both API families pi-ai uses in AgentOS, with SSE streaming.
 *
 *   POST {anthropicBaseUrl}/v1/messages      Anthropic Messages
 *   POST {openaiBaseUrl}/chat/completions    OpenAI Chat Completions
 *
 * What a request gets is decided from the latest real user message of the conversation:
 *
 * 1. A script registered with [script] for exactly that text, or A's directive JSON
 *    (`{"fake": {...}}`, see org.agentos.runtime.testing.FakeScripts.directives) — the same
 *    [FakeTurnScript] model FakeAgentCore plays, so one prompt drives both. A script is a list of
 *    model round trips; round `n` is served to the request that already has `n` assistant
 *    messages after that user message. Steps map to the wire like a real provider would:
 *    Text / Thinking / ToolUse stream as content blocks, AwaitAbort keeps the stream open until
 *    the client disconnects, MaxTokens ends with max_tokens / length, and Fail becomes an HTTP
 *    status before the first byte (429, 401, 402, 400, 413, 503, 408), a dropped connection
 *    (model_network, model_stream_interrupted) or an in-stream error event after output.
 * 2. Otherwise the short directives of core/pi-runtime/test/fake-llm.mjs:
 *    `[tool:NAME A B]` (tool_use NAME{a,b}, then "sum=<result text>"), `[slow]` (40 chunks,
 *    100 ms apart), `[fail500]`, `[fail429]`, and by default "echo:<user text> n=<message count>".
 *
 * [failNext] makes the next requests fail regardless of content (host retry tests). Requests
 * with a wrong key get 401. Every request is recorded in [requests].
 *
 * Portable: a small HTTP/1.1 server on a loopback [ServerSocket] (one request per connection,
 * chunked responses), so the same endpoint serves the JVM tests and the instrumented tests in
 * the app process on a device. A dropped connection is a socket closed without the terminating
 * chunk, which the client sees as an unexpected end of stream.
 */
class FakeModelServer(
    val key: String = DEFAULT_KEY,
    /** Loopback host to listen on and to put in the URLs: "127.0.0.1" (default), "localhost" or "::1". */
    host: String = "127.0.0.1",
) : AutoCloseable {

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

    /** Thrown inside the handler to drop the connection without finishing the HTTP response. */
    private class DropConnection : RuntimeException(null, null, false, false)

    private val json = Json { ignoreUnknownKeys = true }
    private val executor: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "fake-llm").apply { isDaemon = true } }
    // Bound to exactly the address the URLs name: on Android InetAddress.getLoopbackAddress() is ::1,
    // and "localhost" resolves to 127.0.0.1 there, so never mix the two.
    private val server = ServerSocket(0, 50, InetAddress.getByName(host))
    private val open = ConcurrentHashMap.newKeySet<Socket>()
    private val log = CopyOnWriteArrayList<Recorded>()
    private val seq = AtomicInteger()
    private val failures = ConcurrentLinkedQueue<Failure>()
    private val scripts = ConcurrentHashMap<String, FakeTurnScript>()

    val baseUrl: String = "http://${if (':' in host) "[$host]" else host}:${server.localPort}"
    val anthropicBaseUrl: String get() = "$baseUrl/anthropic"
    val openaiBaseUrl: String get() = "$baseUrl/openai"
    val requests: List<Recorded> get() = log.toList()

    init {
        Thread({
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                open += socket
                executor.execute { serve(socket) }
            }
        }, "fake-llm-accept").apply { isDaemon = true }.start()
    }

    private fun serve(socket: Socket) {
        var drop = false
        try {
            val ex = Exchange.read(socket) ?: return
            try {
                handle(ex)
            } catch (_: DropConnection) {
                drop = true
            } catch (_: IOException) {
                // client went away
            }
            if (!drop) runCatching { ex.finish() }
        } catch (_: IOException) {
            // malformed request or client went away
        } catch (_: InterruptedException) {
            // server closed
        } finally {
            // Closing without the terminating chunk is what makes a drop visible to the client.
            runCatching { socket.close() }
            open -= socket
        }
    }

    /** Requests whose latest user message is exactly [prompt] play [script]. */
    fun script(prompt: String, script: FakeTurnScript) {
        scripts[prompt] = script
    }

    /** The next [times] model requests answer [status] (with an optional `retry-after`) regardless of content. */
    fun failNext(status: Int, times: Int = 1, retryAfter: String? = null) {
        repeat(times) { failures.add(Failure(status, retryAfter)) }
    }

    fun reset() {
        log.clear()
        failures.clear()
        scripts.clear()
    }

    override fun close() {
        runCatching { server.close() }
        open.forEach { runCatching { it.close() } }
        executor.shutdownNow()
    }

    // ------------------------------------------------------------------ handling

    private fun handle(ex: Exchange) {
        val path = ex.path
        val api = when {
            path.startsWith("/anthropic/") -> "anthropic"
            path.startsWith("/openai/") -> "openai"
            // lane A（A6）：根路径的 Anthropic Messages。设备上的测试模型来源（C 的 ensureTestModel）的 baseUrl 是
            // http://127.0.0.1:18787，没有 /anthropic 前缀；经 adb reverse 映射到这里（FakeModelServerMain）
            path == "/v1/messages" || path.startsWith("/v1/messages?") -> "anthropic"
            else -> null
        }
        if (api == null || ex.method != "POST") {
            ex.sendResponseHeaders(404, -1)
            return
        }
        val raw = ex.body.toString(Charsets.UTF_8)
        val body = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        val presented = if (api == "anthropic") {
            ex.header("x-api-key")
        } else {
            ex.header("authorization")?.removePrefix("Bearer ")?.removePrefix("bearer ")
        }
        val kind = when {
            presented == key -> "real"
            presented.isNullOrEmpty() -> "none"
            presented.contains("agentos-host-injected") -> "placeholder"
            else -> "other"
        }
        val rec = Recorded(seq.incrementAndGet(), api, path, kind, ex.requestHeaders, body, System.currentTimeMillis())
        log.add(rec)

        if (kind != "real") {
            rec.plan = "auth"
            sendJson(ex, rec, 401, errorBody(api, 401, "invalid api key"))
            return
        }
        failures.poll()?.let { f ->
            rec.plan = "fail${f.status}"
            f.retryAfter?.let { ex.addResponseHeader("retry-after", it) }
            sendJson(ex, rec, f.status, errorBody(api, f.status, "fake ${f.status}"))
            return
        }
        val conv = conversation(api, body)
        val script = scripts[conv.userText] ?: FakeScripts.parseDirective(conv.userText)
        val steps = if (script != null) {
            rec.plan = "script#${conv.assistantsSinceUser}"
            script.rounds.getOrNull(conv.assistantsSinceUser) ?: listOf(FakeStep.Text(FakeTurnScript.FINAL_TEXT))
        } else {
            legacyPlan(conv, rec)
        }
        play(ex, rec, api, body, steps)
    }

    /** Streams one assistant message for [steps], or fails like a real provider would. */
    private fun play(ex: Exchange, rec: Recorded, api: String, body: JsonObject, steps: List<FakeStep>) {
        val firstOutput = steps.indexOfFirst { it is FakeStep.Text || it is FakeStep.Thinking || it is FakeStep.ToolUse }
        val failAt = steps.indexOfFirst { it is FakeStep.Fail }
        if (failAt >= 0 && (firstOutput < 0 || failAt < firstOutput)) {
            val code = (steps[failAt] as FakeStep.Fail).error.code
            if (code == ErrorCode.MODEL_NETWORK) {
                rec.plan += ":drop"
                throw DropConnection()
            }
            if (code != ErrorCode.MODEL_STREAM_INTERRUPTED && code != ErrorCode.MODEL_PROTOCOL) {
                val status = statusFor(code)
                if (status == 429) ex.addResponseHeader("retry-after", "1")
                sendJson(ex, rec, status, errorBody(api, status, "fake ${code.wire}"))
                return
            }
        }
        ex.addResponseHeader("content-type", "text/event-stream")
        ex.addResponseHeader("cache-control", "no-cache")
        rec.status = 200
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody
        val send: (String) -> Unit = { s ->
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
        val w: Writer = if (api == "anthropic") AnthropicWriter(rec, model, body, send) else OpenAiWriter(rec, model, body, send)
        w.start()
        var stop = if (steps.any { it is FakeStep.ToolUse }) "tool" else "end"
        var toolIndex = 0
        for (step in steps) {
            when (step) {
                is FakeStep.Text -> w.text(step.text, step.chunkChars, step.intervalMs)
                is FakeStep.Thinking -> w.thinking(step.text, step.chunkChars, step.intervalMs)
                is FakeStep.ToolUse -> w.toolUse(step.id ?: "call_${rec.id}_${toolIndex++}", step.name, step.arguments)
                is FakeStep.Delay -> Thread.sleep(step.millis)
                FakeStep.AwaitAbort -> {
                    // Keep the stream open until the client goes away (a write then fails).
                    val until = System.currentTimeMillis() + 120_000
                    while (System.currentTimeMillis() < until) {
                        Thread.sleep(50)
                        send(": waiting\n\n")
                    }
                }
                is FakeStep.Fail -> {
                    val code = step.error.code
                    if (code == ErrorCode.MODEL_STREAM_INTERRUPTED || code == ErrorCode.MODEL_NETWORK) {
                        rec.plan += ":cut"
                        throw DropConnection()
                    }
                    if (code == ErrorCode.MODEL_PROTOCOL) {
                        send("data: {not json\n\n")
                    } else {
                        w.error(code)
                    }
                    rec.finished = true
                    out.close()
                    return
                }
                FakeStep.MaxTokens -> stop = "max"
                is FakeStep.CrashCore -> Unit // a model endpoint cannot crash the JS runtime
            }
        }
        w.finish(stop)
        rec.finished = true
        out.close()
    }

    private fun statusFor(code: ErrorCode): Int = when (code) {
        ErrorCode.MODEL_RATE_LIMITED -> 429
        ErrorCode.MODEL_AUTH_FAILED -> 401
        ErrorCode.MODEL_QUOTA_EXHAUSTED -> 402
        ErrorCode.MODEL_REQUEST_TOO_LARGE -> 413
        ErrorCode.MODEL_TIMEOUT -> 408
        ErrorCode.MODEL_UNAVAILABLE -> 503
        else -> 400
    }

    private fun sendJson(ex: Exchange, rec: Recorded, status: Int, text: String) {
        rec.status = status
        rec.finished = true
        val bytes = text.toByteArray(Charsets.UTF_8)
        ex.addResponseHeader("content-type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun errorBody(api: String, status: Int, message: String): String {
        val type = when (status) {
            401 -> "authentication_error"
            429 -> "rate_limit_error"
            in 500..599 -> "api_error"
            else -> "invalid_request_error"
        }
        return if (api == "anthropic") {
            buildJsonObject { put("type", "error"); putJsonObject("error") { put("type", type); put("message", message) } }.toString()
        } else {
            buildJsonObject { putJsonObject("error") { put("message", message); put("type", type) } }.toString()
        }
    }

    // ------------------------------------------------------------------ conversation

    private class Conversation(
        val lastKind: String,
        val lastText: String,
        val userText: String,
        val count: Int,
        /** Assistant messages after the latest real user message: which round trip this is. */
        val assistantsSinceUser: Int,
    )

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
        val userIndex = msgs.indexOfLast { it["role"]?.jsonPrimitive?.contentOrNull == "user" && !isToolResultMessage(it) }
        val userText = if (userIndex >= 0) textOf(msgs[userIndex]["content"]) else lastText
        val assistants = if (userIndex < 0) 0 else msgs.drop(userIndex + 1).count { it["role"]?.jsonPrimitive?.contentOrNull == "assistant" }
        return Conversation(if (lastIsTool) "tool" else "user", lastText, userText, msgs.size, assistants)
    }

    /** The short directives shared with fake-llm.mjs. */
    private fun legacyPlan(c: Conversation, rec: Recorded): List<FakeStep> {
        val tool = TOOL_DIRECTIVE.find(c.userText)
        return when {
            tool != null && c.lastKind == "user" -> {
                rec.plan = "tool"
                listOf(FakeStep.ToolUse(tool.groupValues[1], buildJsonObject { put("a", tool.groupValues[2].toLong()); put("b", tool.groupValues[3].toLong()) }, "toolu_${rec.id}"))
            }
            tool != null -> {
                rec.plan = "text"
                listOf(FakeStep.Text("sum=${c.lastText}", chunkChars = chunkSize("sum=${c.lastText}", 4), intervalMs = 20))
            }
            "[fail500]" in c.userText -> { rec.plan = "fail"; listOf(FakeStep.Fail(ErrorCode.MODEL_UNAVAILABLE.info("fake 500"))) }
            "[fail429]" in c.userText -> { rec.plan = "fail"; listOf(FakeStep.Fail(ErrorCode.MODEL_RATE_LIMITED.info("fake 429"))) }
            "[slow]" in c.userText -> { rec.plan = "text"; listOf(FakeStep.Text((0 until 40).joinToString("") { "tick$it " }, chunkChars = 7, intervalMs = 100)) }
            else -> {
                rec.plan = "text"
                val text = "echo:${c.userText} n=${c.count}"
                listOf(FakeStep.Text(text, chunkChars = chunkSize(text, 8), intervalMs = 30))
            }
        }
    }

    private fun chunkSize(text: String, parts: Int): Int = maxOf(1, (text.codePointCount(0, text.length) + parts - 1) / parts)

    // ------------------------------------------------------------------ wire formats

    private interface Writer {
        fun start()
        fun text(text: String, chunkChars: Int, intervalMs: Long)
        fun thinking(text: String, chunkChars: Int, intervalMs: Long)
        fun toolUse(id: String, name: String, args: JsonObject)
        fun error(code: ErrorCode)
        /** [stop]: "end", "tool" or "max". */
        fun finish(stop: String)
    }

    private fun streamChunks(rec: Recorded, text: String, chunkChars: Int, intervalMs: Long, emit: (String) -> Unit) {
        for (part in chunkByCodePoints(text, chunkChars)) {
            if (intervalMs > 0) Thread.sleep(intervalMs)
            rec.chunkTimes.add(System.currentTimeMillis())
            emit(part)
        }
    }

    private inner class AnthropicWriter(
        private val rec: Recorded,
        private val model: String,
        body: JsonObject,
        private val send: (String) -> Unit,
    ) : Writer {
        private val inputTokens = 10 + (body["messages"] as? JsonArray)?.size.let { it ?: 0 }
        private var index = 0
        private var outputTokens = 0

        private fun ev(type: String, data: JsonObject) = send("event: $type\ndata: ${JsonObject(mapOf("type" to JsonPrimitive(type)) + data)}\n\n")

        override fun start() = ev("message_start", buildJsonObject {
            putJsonObject("message") {
                put("id", "msg_${rec.id}"); put("type", "message"); put("role", "assistant"); put("model", model)
                putJsonArray("content") {}
                put("stop_reason", null as String?); put("stop_sequence", null as String?)
                putJsonObject("usage") { put("input_tokens", inputTokens); put("output_tokens", 1) }
            }
        })

        override fun text(text: String, chunkChars: Int, intervalMs: Long) {
            val i = index++
            ev("content_block_start", buildJsonObject { put("index", i); putJsonObject("content_block") { put("type", "text"); put("text", "") } })
            streamChunks(rec, text, chunkChars, intervalMs) { part ->
                outputTokens++
                ev("content_block_delta", buildJsonObject { put("index", i); putJsonObject("delta") { put("type", "text_delta"); put("text", part) } })
            }
            ev("content_block_stop", buildJsonObject { put("index", i) })
        }

        override fun thinking(text: String, chunkChars: Int, intervalMs: Long) {
            val i = index++
            ev("content_block_start", buildJsonObject { put("index", i); putJsonObject("content_block") { put("type", "thinking"); put("thinking", ""); put("signature", "") } })
            streamChunks(rec, text, chunkChars, intervalMs) { part ->
                ev("content_block_delta", buildJsonObject { put("index", i); putJsonObject("delta") { put("type", "thinking_delta"); put("thinking", part) } })
            }
            ev("content_block_delta", buildJsonObject { put("index", i); putJsonObject("delta") { put("type", "signature_delta"); put("signature", "fake-signature-${rec.id}-$i") } })
            ev("content_block_stop", buildJsonObject { put("index", i) })
        }

        override fun toolUse(id: String, name: String, args: JsonObject) {
            val i = index++
            ev("content_block_start", buildJsonObject { put("index", i); putJsonObject("content_block") { put("type", "tool_use"); put("id", id); put("name", name); putJsonObject("input") {} } })
            for (part in chunkByCodePoints(args.toString(), maxOf(1, args.toString().length / 3))) {
                Thread.sleep(10)
                ev("content_block_delta", buildJsonObject { put("index", i); putJsonObject("delta") { put("type", "input_json_delta"); put("partial_json", part) } })
            }
            ev("content_block_stop", buildJsonObject { put("index", i) })
        }

        override fun error(code: ErrorCode) {
            val type = when (code) {
                ErrorCode.MODEL_RATE_LIMITED -> "rate_limit_error"
                ErrorCode.MODEL_UNAVAILABLE -> "overloaded_error"
                ErrorCode.MODEL_AUTH_FAILED -> "authentication_error"
                else -> "api_error"
            }
            send("event: error\ndata: ${buildJsonObject { put("type", "error"); putJsonObject("error") { put("type", type); put("message", "fake ${code.wire}") } }}\n\n")
        }

        override fun finish(stop: String) {
            val reason = when (stop) { "tool" -> "tool_use"; "max" -> "max_tokens"; else -> "end_turn" }
            ev("message_delta", buildJsonObject { putJsonObject("delta") { put("stop_reason", reason); put("stop_sequence", null as String?) }; putJsonObject("usage") { put("output_tokens", maxOf(1, outputTokens)) } })
            ev("message_stop", JsonObject(emptyMap()))
        }
    }

    private inner class OpenAiWriter(
        private val rec: Recorded,
        private val model: String,
        body: JsonObject,
        private val send: (String) -> Unit,
    ) : Writer {
        private val promptTokens = 10 + ((body["messages"] as? JsonArray)?.size ?: 0)
        private val created = System.currentTimeMillis() / 1000
        private var toolIndex = 0

        private fun data(choices: JsonArray, extra: Map<String, JsonElement> = emptyMap()) {
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

        private fun choice(delta: JsonObject, finish: String?) = buildJsonArray {
            add(buildJsonObject { put("index", 0); put("delta", delta); put("finish_reason", finish) })
        }

        override fun start() = data(choice(buildJsonObject { put("role", "assistant"); put("content", "") }, null))

        override fun text(text: String, chunkChars: Int, intervalMs: Long) =
            streamChunks(rec, text, chunkChars, intervalMs) { part -> data(choice(buildJsonObject { put("content", part) }, null)) }

        override fun thinking(text: String, chunkChars: Int, intervalMs: Long) =
            streamChunks(rec, text, chunkChars, intervalMs) { part -> data(choice(buildJsonObject { put("reasoning_content", part) }, null)) }

        override fun toolUse(id: String, name: String, args: JsonObject) {
            val i = toolIndex++
            data(choice(buildJsonObject {
                putJsonArray("tool_calls") {
                    add(buildJsonObject { put("index", i); put("id", id); put("type", "function"); putJsonObject("function") { put("name", name); put("arguments", "") } })
                }
            }, null))
            for (part in chunkByCodePoints(args.toString(), maxOf(1, args.toString().length / 3))) {
                Thread.sleep(10)
                data(choice(buildJsonObject {
                    putJsonArray("tool_calls") { add(buildJsonObject { put("index", i); putJsonObject("function") { put("arguments", part) } }) }
                }, null))
            }
        }

        override fun error(code: ErrorCode) {
            // Wording of real OpenAI-compatible providers; the OpenAI SDK only keeps the message text.
            val message = when (code) {
                ErrorCode.MODEL_RATE_LIMITED -> "Rate limit reached for requests (fake)"
                ErrorCode.MODEL_UNAVAILABLE -> "The server is overloaded (fake)"
                ErrorCode.MODEL_AUTH_FAILED -> "Incorrect API key provided (fake)"
                else -> "fake ${code.wire}"
            }
            send("data: ${buildJsonObject { putJsonObject("error") { put("message", message); put("type", "server_error") } }}\n\n")
        }

        override fun finish(stop: String) {
            val reason = when (stop) { "tool" -> "tool_calls"; "max" -> "length"; else -> "stop" }
            data(choice(JsonObject(emptyMap()), reason))
            data(JsonArray(emptyList()), mapOf("usage" to buildJsonObject { put("prompt_tokens", promptTokens); put("completion_tokens", 5); put("total_tokens", promptTokens + 5) }))
            send("data: [DONE]\n\n")
        }
    }

    companion object {
        const val DEFAULT_KEY: String = "sk-fake-s8-not-a-secret"
        private val TOOL_DIRECTIVE = Regex("""\[tool:([A-Za-z_][\w-]*)\s+(-?\d+)\s+(-?\d+)]""")

        /** Splits by code points: real servers never cut a surrogate pair across SSE events. */
        fun chunkByCodePoints(text: String, size: Int): List<String> {
            val cps = text.codePoints().toArray()
            val n = maxOf(1, size)
            return (cps.indices step n).map { i -> String(cps, i, minOf(n, cps.size - i)) }
        }
    }
}

/**
 * One HTTP/1.1 request on its own connection (`Connection: close`), with the response API of the
 * JDK's HttpExchange that [FakeModelServer] was written against: [sendResponseHeaders] takes -1
 * for no body, 0 for a chunked body and n > 0 for a fixed length.
 */
private class Exchange private constructor(
    socket: Socket,
    val method: String,
    val path: String,
    val requestHeaders: Map<String, List<String>>,
    val body: ByteArray,
) {
    private val out = BufferedOutputStream(socket.getOutputStream(), 8192)
    private val headers = ArrayList<Pair<String, String>>()
    private var bodyStream: OutputStream? = null

    fun header(name: String): String? = requestHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    fun addResponseHeader(name: String, value: String) {
        headers += name to value
    }

    fun sendResponseHeaders(status: Int, length: Long) {
        check(bodyStream == null) { "response headers already sent" }
        val head = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
        for ((n, v) in headers) head.append(n).append(": ").append(v).append("\r\n")
        when {
            length > 0 -> head.append("Content-Length: ").append(length).append("\r\n")
            length == 0L -> head.append("Transfer-Encoding: chunked\r\n")
            else -> head.append("Content-Length: 0\r\n")
        }
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
        bodyStream = if (length == 0L) ChunkedOutputStream(out) else PlainOutputStream(out)
    }

    val responseBody: OutputStream get() = checkNotNull(bodyStream) { "send the response headers first" }

    /** Completes the response: the terminating chunk for a chunked body, then flush. */
    fun finish() {
        if (bodyStream == null) sendResponseHeaders(500, -1)
        bodyStream?.close()
        out.flush()
    }

    private class ChunkedOutputStream(private val out: OutputStream) : OutputStream() {
        private var closed = false
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("stream closed")
            if (len == 0) return
            out.write("${Integer.toHexString(len)}\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write(b, off, len)
            out.write(CRLF)
        }
        override fun flush() = out.flush()
        override fun close() {
            if (closed) return
            closed = true
            out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    /** Fixed-length or empty body: closing only flushes; the socket is closed by the server. */
    private class PlainOutputStream(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun flush() = out.flush()
        override fun close() = out.flush()
    }

    companion object {
        private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)

        private fun reason(status: Int): String = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            402 -> "Payment Required"
            404 -> "Not Found"
            408 -> "Request Timeout"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Status"
        }

        /** Reads the request line, headers and body; null when the client closed without sending one. */
        fun read(socket: Socket): Exchange? {
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return null
            val parts = requestLine.split(' ')
            if (parts.size < 3) throw IOException("bad request line")
            val headers = LinkedHashMap<String, MutableList<String>>()
            while (true) {
                val line = readLine(input) ?: throw IOException("connection closed in the request headers")
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                headers.getOrPut(line.substring(0, colon).trim()) { ArrayList() } += line.substring(colon + 1).trim()
            }
            fun h(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
            val body = if (h("transfer-encoding")?.contains("chunked", ignoreCase = true) == true) {
                readChunked(input)
            } else {
                readExactly(input, h("content-length")?.toIntOrNull() ?: 0)
            }
            return Exchange(socket, parts[0], parts[1].substringBefore('?'), headers, body)
        }

        private fun readLine(input: InputStream): String? {
            val buf = java.io.ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c == -1) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
                if (c == '\n'.code) return buf.toString("ISO-8859-1")
                if (c != '\r'.code) buf.write(c)
            }
        }

        private fun readExactly(input: InputStream, n: Int): ByteArray {
            val bytes = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(bytes, read, n - read)
                if (r < 0) throw IOException("connection closed in the request body")
                read += r
            }
            return bytes
        }

        private fun readChunked(input: InputStream): ByteArray {
            val all = java.io.ByteArrayOutputStream()
            while (true) {
                val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw IOException("bad chunk size")
                if (size == 0) {
                    while (!readLine(input).isNullOrEmpty()) Unit // trailers
                    return all.toByteArray()
                }
                all.write(readExactly(input, size))
                readLine(input)
            }
        }
    }
}
