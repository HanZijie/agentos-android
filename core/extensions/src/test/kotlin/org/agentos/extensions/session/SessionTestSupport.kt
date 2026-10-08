package org.agentos.extensions.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.agentos.extensions.host.ConnectFailed
import org.agentos.extensions.host.McpCallResult
import org.agentos.extensions.host.McpContentPart
import org.agentos.extensions.host.McpLinkException
import org.agentos.extensions.host.McpServerLink
import org.agentos.extensions.host.McpToolAnnotations
import org.agentos.extensions.host.McpToolInfo
import org.agentos.extensions.host.NotSent
import org.agentos.runtime.ports.SessionMcpServer
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

// ---------------------------------------------------------------------------------------------------------------------
// MockWebServer 上的假 MCP 服务器（给 StreamableHttpLink 的测试）
// ---------------------------------------------------------------------------------------------------------------------

/** 服务器看到的一个请求。[body] 是 JSON-RPC 消息（DELETE 没有）。 */
class Seen(val httpMethod: String, val headers: Map<String, String>, val body: JsonObject?) {
    val rpc: String? get() = (body?.get("method") as? JsonPrimitive)?.contentOrNull
    val id: JsonElement? get() = body?.get("id")
    val params: JsonObject? get() = body?.get("params") as? JsonObject
    fun header(name: String): String? = headers[name.lowercase()]
}

class Rpc(val seen: Seen) {
    val method: String get() = seen.rpc ?: ""
    val id: JsonElement? get() = seen.id
    val params: JsonObject get() = seen.params ?: JsonObject(emptyMap())
}

/** 回环地址上的 Streamable HTTP MCP 服务器。默认实现 initialize / initialized / tools/list / tools/call / cancelled / DELETE；[hook] 可以覆盖任何一个。 */
class McpTestServer(
    var sessionId: String? = "sess-1",
    var protocolVersion: String = "2025-06-18",
) : AutoCloseable {
    val web = MockWebServer()
    val log = CopyOnWriteArrayList<Seen>()
    var tools: JsonArray = buildJsonArray { }
    var hook: (Rpc) -> MockResponse? = { null }
    var callHandler: (Rpc) -> MockResponse = { rpc -> json(rpc.id, buildJsonObject { putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "ok") }) } }) }

    init {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val text = request.body.peek().readUtf8()
                val body = if (text.isEmpty()) null else Json.parseToJsonElement(text) as? JsonObject
                val headers = request.headers.toMultimap().mapValues { it.value.first() }.mapKeys { it.key.lowercase() }
                val seen = Seen(request.method ?: "", headers, body)
                log += seen
                if (request.method == "DELETE") return MockResponse().setResponseCode(200)
                val rpc = Rpc(seen)
                hook(rpc)?.let { return it }
                return when (rpc.method) {
                    "initialize" -> json(rpc.id, buildJsonObject {
                        put("protocolVersion", protocolVersion)
                        put("capabilities", buildJsonObject { put("tools", buildJsonObject { }) })
                        put("serverInfo", buildJsonObject { put("name", "test"); put("version", "1") })
                    }).also { r -> sessionId?.let { r.addHeader("Mcp-Session-Id", it) } }
                    "tools/list" -> json(rpc.id, buildJsonObject { put("tools", tools) })
                    "tools/call" -> callHandler(rpc)
                    else -> MockResponse().setResponseCode(202)
                }
            }
        }
        web.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    val url: String get() = "http://127.0.0.1:${web.port}/mcp"

    fun requests(rpc: String): List<Seen> = log.filter { it.rpc == rpc }

    /** 等到日志里出现满足条件的请求（异步发出的 DELETE、cancelled 通知）。 */
    fun awaitSeen(timeoutMillis: Long = 5_000, predicate: (Seen) -> Boolean): Seen {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            log.firstOrNull(predicate)?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("no matching request within $timeoutMillis ms; saw ${log.map { (it.httpMethod to it.rpc) }}")
    }

    override fun close() {
        runCatching { web.shutdown() }
    }

    companion object {
        fun json(id: JsonElement?, result: JsonObject, status: Int = 200): MockResponse = MockResponse()
            .setResponseCode(status)
            .addHeader("Content-Type", "application/json")
            .setBody(buildJsonObject { put("jsonrpc", "2.0"); put("id", id ?: JsonPrimitive(0)); put("result", result) }.toString())

        fun error(id: JsonElement?, code: Int, message: String): MockResponse = MockResponse()
            .addHeader("Content-Type", "application/json")
            .setBody(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id ?: JsonPrimitive(0))
                put("error", buildJsonObject { put("code", code); put("message", message) })
            }.toString())

        /** SSE 响应：每个元素是一个事件的 data。 */
        fun sse(vararg events: String): MockResponse = MockResponse()
            .addHeader("Content-Type", "text/event-stream")
            .setBody(events.joinToString("") { "event: message\ndata: $it\n\n" })

        fun message(id: JsonElement?, result: JsonObject): String =
            buildJsonObject { put("jsonrpc", "2.0"); put("id", id ?: JsonPrimitive(0)); put("result", result) }.toString()

        fun toolJson(name: String, description: String? = null, annotations: JsonObject? = null, schema: JsonObject = buildJsonObject { put("type", "object") }): JsonObject = buildJsonObject {
            put("name", name)
            if (description != null) put("description", description)
            put("inputSchema", schema)
            if (annotations != null) put("annotations", annotations)
        }

        fun textContent(text: String): JsonObject = buildJsonObject {
            putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// 假连接器与假链接（给 SessionToolHost 的测试）
// ---------------------------------------------------------------------------------------------------------------------

fun mcpTool(
    name: String,
    readOnly: Boolean? = null,
    destructive: Boolean? = null,
    description: String? = "does $name",
    title: String? = null,
    schema: JsonObject = buildJsonObject { put("type", "object") },
) = McpToolInfo(name, title = title, description = description, inputSchema = schema, annotations = McpToolAnnotations(readOnlyHint = readOnly, destructiveHint = destructive))

class FakeCall(val name: String, val arguments: JsonObject, val timeoutMillis: Long)

/** 一个假的远端 MCP 服务器：工具、调用行为、连接行为都可以脚本化。 */
class FakeMcpServer(var tools: List<McpToolInfo> = emptyList()) {
    var handler: suspend (FakeCall) -> McpCallResult = { call -> McpCallResult(listOf(McpContentPart.Text("ok:${call.name}"))) }
    var connectFailure: Throwable? = null
    var connectDelayMillis = 0L
    var listFailure: McpLinkException? = null
    var listDelayMillis = 0L
    var failNextCall: McpLinkException? = null

    val links = CopyOnWriteArrayList<FakeLink>()
    val connects = AtomicInteger()
    val listCalls = AtomicInteger()
    val calls = CopyOnWriteArrayList<FakeCall>()
    val cancelledCalls = AtomicInteger()

    val live: FakeLink? get() = links.lastOrNull { !it.closed.isCompleted }

    /** 模拟对端断开。 */
    fun kill(reason: String = "peer closed") = links.filter { !it.closed.isCompleted }.forEach { it.die(reason) }

    fun toolsChanged() = live?.changed?.tryEmit(Unit)
}

class FakeLink(private val server: FakeMcpServer) : McpServerLink {
    private val closedDeferred = CompletableDeferred<String>()
    val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val closeCalls = AtomicInteger()

    override val toolsChanged: Flow<Unit> get() = changed
    override val closed: Deferred<String> get() = closedDeferred

    fun die(reason: String) {
        closedDeferred.complete(reason)
    }

    override fun close() {
        closeCalls.incrementAndGet()
        closedDeferred.complete("closed")
    }

    override suspend fun listTools(timeoutMillis: Long): List<McpToolInfo> {
        server.listCalls.incrementAndGet()
        if (server.listDelayMillis > 0) delay(server.listDelayMillis)
        server.listFailure?.let { throw it }
        if (closedDeferred.isCompleted) throw org.agentos.extensions.host.ConnectionLost("closed")
        return server.tools
    }

    override suspend fun callTool(name: String, arguments: JsonObject, timeoutMillis: Long): McpCallResult {
        if (closedDeferred.isCompleted) throw NotSent("link is closed")
        server.failNextCall?.let {
            server.failNextCall = null
            throw it
        }
        val call = FakeCall(name, arguments, timeoutMillis)
        server.calls += call
        try {
            return server.handler(call)
        } catch (e: CancellationException) {
            server.cancelledCalls.incrementAndGet()
            throw e
        }
    }
}

/** 按服务器名找假服务器；记录收到的 [SessionMcpServer]（测试用它确认 URL 和头确实交给了连接器）。 */
class FakeConnector : SessionMcpConnector {
    val servers = ConcurrentHashMap<String, FakeMcpServer>()
    val connected = CopyOnWriteArrayList<SessionMcpServer>()

    fun server(name: String, tools: List<McpToolInfo> = emptyList()): FakeMcpServer = FakeMcpServer(tools).also { servers[name] = it }

    override suspend fun connect(server: SessionMcpServer, connectTimeoutMillis: Long): McpServerLink {
        val fake = servers[server.name] ?: throw ConnectFailed("no such server")
        connected += server
        if (fake.connectDelayMillis > 0) delay(fake.connectDelayMillis)
        fake.connectFailure?.let { throw it }
        fake.connects.incrementAndGet()
        return FakeLink(fake).also { fake.links += it }
    }
}

/** 测试里用的服务器（URL 是满足 [SessionUrlPolicy] 的公网地址，配合假连接器永远不会被真的访问）。 */
fun sessionServer(name: String, url: String = "https://$name.example.com/mcp", headers: List<Pair<String, String>> = emptyList()) =
    SessionMcpServer(name, url, headers)

fun objectSchema(vararg properties: String): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { properties.forEach { put(it, buildJsonObject { put("type", "string") }) } })
}
