package org.agentos.extensions.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.agentos.extensions.host.ConnectFailed
import org.agentos.extensions.host.ConnectionLost
import org.agentos.extensions.host.McpCallResult
import org.agentos.extensions.host.McpContentPart
import org.agentos.extensions.host.McpLinkException
import org.agentos.extensions.host.McpServerLink
import org.agentos.extensions.host.McpToolAnnotations
import org.agentos.extensions.host.McpToolInfo
import org.agentos.extensions.host.NotSent
import org.agentos.extensions.host.ServerError
import org.agentos.extensions.host.TimedOut
import org.agentos.runtime.ports.SessionMcpRejected
import org.agentos.runtime.ports.SessionMcpServer
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * MCP **Streamable HTTP** 传输的客户端（协议 2025-06-18），实现 [McpServerLink]；用 [connect] 建立。
 *
 * ## 传输
 * - 每条消息一个 POST：`Content-Type: application/json`、`Accept: application/json, text/event-stream`，加调用方给的自定义头
 *   （已经过 [SessionUrlPolicy] 校验）；`initialize` 之后每个请求带 `Mcp-Session-Id`（服务端给了的话）和 `MCP-Protocol-Version`（服务端协商出来的版本，
 *   不是 [PROTOCOL_VERSION] 也接受）。
 * - 响应是 `application/json`（一个 JSON-RPC 响应）或 `text/event-stream`（读到 id 匹配的响应就结束；中间的通知忽略，
 *   `notifications/tools/list_changed` 发到 [toolsChanged]；服务端发来的请求（ping、sampling…）不回复）。
 * - 不开 GET 的服务端推送流，不做 SSE 断线续传（`Last-Event-ID`）：只有 POST 响应流里的通知能收到。
 *
 * ## 错误分类（决定工具调用的结局，见 [McpLinkException]）
 * - **请求确定没有写出去**（连接失败、DNS 失败、TLS 失败、请求头非法、链接已关闭）→ [ConnectFailed]（[connect] 里）/ [NotSent]；
 * - **写出去之后没有拿到回复**（读超时、连接被重置、响应流中途断开、响应格式错误、响应超过大小上限）→ [ConnectionLost]；
 *   调用超时 → [TimedOut]（OkHttp 的整个调用超时，包含读完响应体）；
 * - **服务端有回复**：JSON-RPC error → [ServerError]（`detail` 只有短代码，不含服务端的 `message` 原文）；
 *   HTTP 3xx（不跟随重定向）、4xx、500、501、505 及以上 → [ServerError]（`code` 是 HTTP 状态码，`detail` 是 `http_<状态码>`；服务端明确拒绝了这个请求，
 *   通常没有执行）；**502、503、504 → [ConnectionLost]**：这是网关或代理的回复，上游是否已经执行了请求不确定；
 * - HTTP 404 且请求带了 `Mcp-Session-Id`（会话过期）→ 关闭链接（[closed] 以 `session_expired` 完成）并抛 [ConnectionLost]；
 * - [connect] 里的任何失败都是 [ConnectFailed]。
 * 所有异常的 message 只有短代码和状态码：**没有 URL、请求头、响应正文，也不带 cause**（底层异常的消息里常有主机名）。
 *
 * ## 取消与关闭
 * - [callTool] 所在的协程被取消（或调用超时）时：取消底层的 OkHttp 调用，并尽力（异步、短超时、忽略失败）POST `notifications/cancelled`。
 * - [close]：幂等；有会话 ID 时尽力发 HTTP DELETE（短超时、忽略失败）；取消在途请求；[closed] 以 `closed` 完成。
 *
 * ## 安全
 * 不跟随重定向（重定向到内网是 SSRF 通道）、不重试（`retryOnConnectionFailure(false)`：重放一个可能已经执行的 `tools/call` 不可接受）；
 * 域名解析走 [SessionUrlPolicy.dns]；响应体大小有上限（`tools/list` 一页 1 MiB，其他 4 MiB，SSE 的总量同样），超限立刻取消调用。
 */
class StreamableHttpLink private constructor(
    private val client: OkHttpClient,
    private val endpoint: HttpUrl,
    private val customHeaders: List<Pair<String, String>>,
) : McpServerLink {

    private val nextId = AtomicLong(1)
    @Volatile private var sessionId: String? = null
    @Volatile private var protocolVersion: String? = null
    private val closedDeferred = CompletableDeferred<String>()
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val inFlight: MutableSet<Call> = ConcurrentHashMap.newKeySet()
    private val closing = AtomicBoolean(false)

    override val toolsChanged: Flow<Unit> get() = changed.asSharedFlow()

    override val closed: Deferred<String> get() = closedDeferred

    /** 请求有没有可能已经写到线上（第一个字节开始写之后为 true）：决定失败是 [NotSent] 还是 [ConnectionLost]。 */
    private class CallState {
        @Volatile var sent = false
    }

    // ------------------------------------------------------------------ McpServerLink

    override suspend fun listTools(timeoutMillis: Long): List<McpToolInfo> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(1))
        val out = ArrayList<McpToolInfo>()
        val cursors = HashSet<String>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) throw TimedOut(timeoutMillis)
            val params = buildJsonObject { cursor?.let { put("cursor", it) } }
            val result = rpc("tools/list", params, remaining, MAX_LIST_BYTES)
            val tools = result["tools"] as? JsonArray ?: throw ConnectionLost("bad_response")
            for (element in tools) parseTool(element)?.let { out += it }
            val next = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            if (next == null || !cursors.add(next) || out.size >= MAX_LISTED_TOOLS) return out
            cursor = next
        }
        return out
    }

    override suspend fun callTool(name: String, arguments: JsonObject, timeoutMillis: Long): McpCallResult {
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        return parseCallResult(rpc("tools/call", params, timeoutMillis, MAX_CALL_BYTES, notifyCancel = true))
    }

    override fun close() = shutdown("closed", sendDelete = true)

    override fun toString(): String = "StreamableHttpLink(closed=${closedDeferred.isCompleted})"

    // ------------------------------------------------------------------ 关闭

    private fun shutdown(reason: String, sendDelete: Boolean) {
        if (!closing.compareAndSet(false, true)) return
        closedDeferred.complete(reason)
        for (call in inFlight.toList()) runCatching { call.cancel() }
        val id = sessionId
        if (sendDelete && id != null) {
            // 尽力：异步、短超时、忽略结果
            runCatching {
                val request = decorate(Request.Builder().url(endpoint).delete()).build()
                fireAndForget(request, SIDE_CALL_TIMEOUT_MILLIS)
            }
        }
    }

    // ------------------------------------------------------------------ 请求

    /** 发一个 JSON-RPC 请求，返回 `result`。[notifyCancel]：取消或超时时向服务端发 `notifications/cancelled`。 */
    private suspend fun rpc(
        method: String,
        params: JsonObject,
        timeoutMillis: Long,
        maxBytes: Long,
        notifyCancel: Boolean = false,
        onHead: (Response) -> Unit = {},
    ): JsonObject {
        val id = nextId.getAndIncrement()
        val state = CallState()
        val request = post(jsonRpc(method, params, id))
        try {
            val message = send(request, state, timeoutMillis) { response ->
                onHead(response)
                readReply(response, request, id, maxBytes)
            }
            return resultOf(message)
        } catch (e: CancellationException) {
            if (notifyCancel && state.sent) notifyCancelled(id)
            throw e
        } catch (e: TimedOut) {
            if (notifyCancel) notifyCancelled(id)
            throw e
        }
    }

    private fun jsonRpc(method: String, params: JsonObject?, id: Long?): String = buildJsonObject {
        put("jsonrpc", "2.0")
        if (id != null) put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

    private fun post(payload: String): Request = try {
        decorate(Request.Builder().url(endpoint))
            .post(payload.toByteArray(Charsets.UTF_8).toRequestBody(JSON))
            .build()
    } catch (e: IllegalArgumentException) {
        throw NotSent("invalid_request")
    }

    /** 自定义头在前，传输层的头在后（同名覆盖；调用方本来也不能设它们）。 */
    private fun decorate(builder: Request.Builder): Request.Builder {
        for ((name, value) in customHeaders) builder.addHeader(name, value)
        builder.header("Accept", "application/json, text/event-stream")
        sessionId?.let { builder.header(SESSION_HEADER, it) }
        protocolVersion?.let { builder.header(VERSION_HEADER, it) }
        return builder
    }

    /**
     * 发出请求，在 OkHttp 的线程里用 [handler] 处理响应（读响应体），把结果交回协程。取消协程就取消 OkHttp 调用。
     * 只抛 [McpLinkException]（[handler] 抛的，或底层 IO 异常按 [mapIo] 分类后）和 CancellationException。
     */
    private suspend fun <T> send(request: Request, state: CallState, timeoutMillis: Long, handler: (Response) -> T): T {
        if (closedDeferred.isCompleted) throw NotSent("closed")
        val call = client.newBuilder()
            .callTimeout(timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()), TimeUnit.MILLISECONDS)
            .build()
            .newCall(request.newBuilder().tag(CallState::class.java, state).build())
        inFlight += call
        try {
            return suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val outcome: Result<T> = try {
                            response.use { Result.success(handler(it)) }
                        } catch (t: Throwable) {
                            call.cancel() // 出错时不让连接留在半读状态
                            Result.failure(t)
                        }
                        outcome.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                    }
                })
            }
        } catch (e: IOException) {
            throw mapIo(e, state, timeoutMillis)
        } catch (e: McpLinkException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 约定：只抛 McpLinkException。处理响应时的意外异常（不应该发生）按“已发出、结果未知”处理；消息不转述，也不带 cause
            throw if (state.sent) ConnectionLost("internal_error") else NotSent("internal_error")
        } finally {
            inFlight -= call
        }
    }

    private fun mapIo(e: IOException, state: CallState, timeoutMillis: Long): McpLinkException {
        val kind = e.javaClass.simpleName
        return when {
            closedDeferred.isCompleted -> if (state.sent) ConnectionLost("closed") else NotSent("closed")
            !state.sent -> NotSent("not_sent: $kind")
            e is InterruptedIOException -> TimedOut(timeoutMillis) // SocketTimeoutException 也是它的子类
            else -> ConnectionLost("io_error: $kind")
        }
    }

    // ------------------------------------------------------------------ 响应

    /** 状态码 → 异常（2xx 返回）。不读错误响应的正文。 */
    private fun checkStatus(response: Response, request: Request) {
        val code = response.code
        when {
            code in 200..299 -> return
            code == 404 && request.header(SESSION_HEADER) != null -> {
                shutdown("session_expired", sendDelete = false)
                throw ConnectionLost("session_expired")
            }
            code in 502..504 -> throw ConnectionLost("http_$code")
            else -> throw ServerError(code, "http_$code")
        }
    }

    /** 读出 id 为 [id] 的 JSON-RPC 响应（JSON 或 SSE）。在 OkHttp 的线程里运行，可以阻塞。 */
    private fun readReply(response: Response, request: Request, id: Long, maxBytes: Long): JsonObject {
        checkStatus(response, request)
        val body = response.body ?: throw ConnectionLost("empty_response")
        try {
            return when (body.contentType()?.let { "${it.type}/${it.subtype}".lowercase() }) {
                "application/json" -> {
                    val element = Json.parseToJsonElement(readBounded(body, maxBytes))
                    interpret(element, id) ?: throw ConnectionLost("bad_response")
                }
                "text/event-stream" -> readEvents(body, id, maxBytes)
                else -> throw ConnectionLost("bad_content_type")
            }
        } catch (e: SerializationException) {
            throw ConnectionLost("bad_response")
        } catch (e: IllegalArgumentException) {
            throw ConnectionLost("bad_response")
        }
        // 读响应体时的 IO 异常（超时、连接断开）不在这里处理：交回 send，用同一个 mapIo 分类（请求这时一定已经发出）
    }

    private fun readBounded(body: ResponseBody, max: Long): String {
        if (body.contentLength() > max) throw ConnectionLost("response_too_large")
        val source = body.source()
        val buffer = Buffer()
        while (source.read(buffer, CHUNK) != -1L) {
            if (buffer.size > max) throw ConnectionLost("response_too_large")
        }
        return buffer.readUtf8()
    }

    /** SSE：按行读，事件以空行结束；总量不超过 [max]。读到 id 匹配的响应就返回；流结束还没有就是 [ConnectionLost]。 */
    private fun readEvents(body: ResponseBody, id: Long, max: Long): JsonObject {
        val source = body.source()
        val pending = Buffer()
        val chunk = Buffer()
        val data = StringBuilder()
        var total = 0L

        fun dispatch(): JsonObject? {
            if (data.isEmpty()) return null
            val text = data.toString()
            data.setLength(0)
            val element = try {
                Json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                return null // 不是 JSON 的事件（心跳之类）忽略
            }
            return interpret(element, id)
        }

        while (true) {
            val n = source.read(chunk, CHUNK)
            if (n == -1L) break
            total += n
            if (total > max) throw ConnectionLost("response_too_large")
            pending.writeAll(chunk)
            while (true) {
                val end = pending.indexOf('\n'.code.toByte())
                if (end < 0) break
                val line = pending.readUtf8(end).removeSuffix("\r")
                pending.skip(1)
                when {
                    line.isEmpty() -> dispatch()?.let { return it }
                    line.startsWith(":") -> Unit
                    else -> {
                        val colon = line.indexOf(':')
                        val field = if (colon < 0) line else line.substring(0, colon)
                        if (field == "data") {
                            var value = if (colon < 0) "" else line.substring(colon + 1)
                            if (value.startsWith(" ")) value = value.substring(1)
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(value)
                        }
                    }
                }
            }
        }
        dispatch()?.let { return it }
        throw ConnectionLost("stream_ended")
    }

    /**
     * 一条 JSON-RPC 消息：是 id 匹配的响应就返回它；通知（`tools/list_changed` 发到 [toolsChanged]）、服务端的请求、
     * 别的 id 的响应都返回 null（忽略）。
     */
    private fun interpret(element: JsonElement, id: Long): JsonObject? {
        val message = element as? JsonObject ?: return null
        if (message["method"] != null) {
            if (message["id"] == null && (message["method"] as? JsonPrimitive)?.contentOrNull == "notifications/tools/list_changed") {
                changed.tryEmit(Unit)
            }
            return null
        }
        val messageId = message["id"] as? JsonPrimitive ?: return null
        if (messageId is JsonNull || messageId.content != id.toString()) return null
        return message
    }

    private fun resultOf(message: JsonObject): JsonObject {
        val error = message["error"]
        if (error != null) {
            val code = ((error as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull ?: -32603
            throw ServerError(code, describe(code))
        }
        return message["result"] as? JsonObject ?: throw ConnectionLost("bad_response")
    }

    private fun describe(code: Int) = when (code) {
        -32700 -> "parse_error"
        -32600 -> "invalid_request"
        -32601 -> "method_not_found"
        -32602 -> "invalid_params"
        -32603 -> "internal_error"
        else -> "server_error"
    }

    // ------------------------------------------------------------------ 通知（不等回复）

    private suspend fun notifyAndAwait(method: String, timeoutMillis: Long) {
        val state = CallState()
        val request = post(jsonRpc(method, null, null))
        send(request, state, timeoutMillis) { response -> checkStatus(response, request) }
    }

    /** 尽力：不等、不在乎结果。 */
    private fun notifyCancelled(requestId: Long) {
        runCatching {
            val params = buildJsonObject {
                put("requestId", requestId)
                put("reason", "cancelled")
            }
            fireAndForget(post(jsonRpc("notifications/cancelled", params, null)), SIDE_CALL_TIMEOUT_MILLIS)
        }
    }

    private fun fireAndForget(request: Request, timeoutMillis: Long) {
        client.newBuilder().callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit

            override fun onResponse(call: Call, response: Response) = response.close()
        })
    }

    // ------------------------------------------------------------------ 握手

    private suspend fun initialize(timeoutMillis: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(1))
        fun remaining() = (TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())).also { if (it <= 0) throw TimedOut(timeoutMillis) }

        var newSession: String? = null
        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject {
                put("name", "agentos")
                put("version", "1")
            })
        }
        val result = rpc("initialize", params, remaining(), MAX_SMALL_BYTES) { response -> newSession = response.header(SESSION_HEADER) }
        val version = (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull ?: PROTOCOL_VERSION
        if (!VERSION.matches(version)) throw ConnectionLost("bad_protocol_version")
        newSession?.let { if (!SESSION_ID.matches(it)) throw ConnectionLost("bad_session_id") }
        sessionId = newSession
        protocolVersion = version
        notifyAndAwait("notifications/initialized", remaining())
    }

    companion object {
        /** 我们发出的协议版本；服务端协商出别的版本也接受。 */
        const val PROTOCOL_VERSION = "2025-06-18"
        const val SESSION_HEADER = "Mcp-Session-Id"
        const val VERSION_HEADER = "MCP-Protocol-Version"

        /** `tools/list` 一页的响应上限。 */
        const val MAX_LIST_BYTES = 1L * 1024 * 1024

        /** `tools/call` 的响应上限（SSE 总量同样）。 */
        const val MAX_CALL_BYTES = 4L * 1024 * 1024
        const val MAX_PAGES = 20
        private const val MAX_LISTED_TOOLS = 1_000
        private const val MAX_SMALL_BYTES = 256L * 1024
        private const val SIDE_CALL_TIMEOUT_MILLIS = 3_000L
        private const val CHUNK = 8192L
        private val JSON = "application/json".toMediaType()
        private val VERSION = Regex("[A-Za-z0-9._-]{1,32}")
        private val SESSION_ID = Regex("[\\x21-\\x7e]{1,256}")

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .dispatcher(Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 16
                })
                .build()
        }

        /** 共用的底层客户端（连接池、线程池）；每条链接在它的基础上加固。 */
        fun defaultClient(): OkHttpClient = sharedClient

        /**
         * 建立连接：POST `initialize`，读 `Mcp-Session-Id`，再 POST `notifications/initialized`。整个过程不超过 [connectTimeoutMillis]。
         * 失败抛 [ConnectFailed]（message 只有短代码）；协程被取消时抛 CancellationException，已经建立的会话尽力 DELETE。
         *
         * @param policy 再校验一次 [url] 和 [headers]（调用方通常已经整批校验过），并提供防 DNS rebinding 的解析
         * @param client 底层客户端；这里会把重定向、重试关掉、换上 [policy] 的 DNS，传什么都一样安全
         */
        suspend fun connect(
            url: String,
            headers: List<Pair<String, String>>,
            policy: SessionUrlPolicy,
            connectTimeoutMillis: Long,
            client: OkHttpClient = defaultClient(),
        ): StreamableHttpLink {
            val endpoint = try {
                policy.validateOne(SessionMcpServer("link", url, headers))
            } catch (e: SessionMcpRejected) {
                throw ConnectFailed("rejected: ${e.reason}")
            }
            val link = StreamableHttpLink(harden(client, policy.dns()), endpoint, headers.toList())
            try {
                link.initialize(connectTimeoutMillis)
            } catch (e: McpLinkException) {
                link.close()
                throw ConnectFailed("initialize_failed: ${e.message}")
            } catch (e: Throwable) {
                link.close()
                throw e
            }
            return link
        }

        /** 安全加固：不跟随重定向、不重试、用给定的 DNS、不使用响应缓存；读超时交给整个调用的超时。 */
        private fun harden(base: OkHttpClient, dns: Dns): OkHttpClient = base.newBuilder()
            .dns(dns)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cache(null)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .eventListenerFactory { call ->
                val state = call.request().tag(CallState::class.java)
                if (state == null) {
                    EventListener.NONE
                } else {
                    object : EventListener() {
                        override fun requestHeadersStart(call: Call) {
                            state.sent = true
                        }
                    }
                }
            }
            .build()

        // ------------------------------------------------------------------ 结果解析

        private fun parseTool(element: JsonElement): McpToolInfo? {
            val tool = element as? JsonObject ?: return null
            val name = (tool["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
            val ann = tool["annotations"] as? JsonObject
            return McpToolInfo(
                name = name,
                title = (tool["title"] as? JsonPrimitive)?.contentOrNull,
                description = (tool["description"] as? JsonPrimitive)?.contentOrNull,
                inputSchema = tool["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
                annotations = ann?.let {
                    McpToolAnnotations(
                        readOnlyHint = it.bool("readOnlyHint"),
                        destructiveHint = it.bool("destructiveHint"),
                        idempotentHint = it.bool("idempotentHint"),
                        openWorldHint = it.bool("openWorldHint"),
                    )
                },
            )
        }

        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

        private fun parseCallResult(result: JsonObject): McpCallResult {
            val parts = ArrayList<McpContentPart>()
            for (item in result["content"] as? JsonArray ?: JsonArray(emptyList())) {
                val obj = item as? JsonObject
                val type = (obj?.get("type") as? JsonPrimitive)?.contentOrNull
                val text = (obj?.get("text") as? JsonPrimitive)?.contentOrNull
                val data = (obj?.get("data") as? JsonPrimitive)?.contentOrNull
                val mime = (obj?.get("mimeType") as? JsonPrimitive)?.contentOrNull
                parts += when {
                    type == "text" && text != null -> McpContentPart.Text(text)
                    type == "image" && data != null && mime != null -> McpContentPart.Image(data, mime)
                    else -> McpContentPart.Other(type ?: "unknown", obj ?: JsonObject(emptyMap()))
                }
            }
            return McpCallResult(
                content = parts,
                structuredContent = result["structuredContent"] as? JsonObject,
                isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    }
}
