package org.agentos.extensions.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.agentos.extensions.host.ConnectFailed
import org.agentos.extensions.host.ConnectionLost
import org.agentos.extensions.host.McpContentPart
import org.agentos.extensions.host.McpLinkException
import org.agentos.extensions.host.NotSent
import org.agentos.extensions.host.ServerError
import org.agentos.extensions.host.TimedOut
import org.agentos.extensions.session.McpTestServer.Companion.error
import org.agentos.extensions.session.McpTestServer.Companion.json
import org.agentos.extensions.session.McpTestServer.Companion.message
import org.agentos.extensions.session.McpTestServer.Companion.sse
import org.agentos.extensions.session.McpTestServer.Companion.textContent
import org.agentos.extensions.session.McpTestServer.Companion.toolJson
import java.net.InetAddress
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamableHttpLinkTest {
    private val servers = ArrayList<McpTestServer>()
    private val policy = SessionUrlPolicy(allowLoopbackHttp = true)
    private val args = buildJsonObject { put("a", 1) }

    private fun newServer(sessionId: String? = "sess-1", protocolVersion: String = "2025-06-18") = McpTestServer(sessionId, protocolVersion).also { servers += it }

    @AfterTest
    fun tearDown() {
        servers.forEach { it.close() }
    }

    private fun blocking(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(60_000) { block() } }

    private suspend fun connect(
        server: McpTestServer,
        headers: List<Pair<String, String>> = emptyList(),
        timeoutMillis: Long = 5_000,
        url: String = server.url,
    ): StreamableHttpLink = StreamableHttpLink.connect(url, headers, policy, timeoutMillis)

    // ------------------------------------------------------------------ 握手

    @Test
    fun `connect posts initialize then initialized and keeps the session id`() = blocking {
        val server = newServer()
        val link = connect(server)
        val init = server.requests("initialize").single()
        assertEquals("POST", init.httpMethod)
        assertEquals("2025-06-18", init.params!!["protocolVersion"]!!.let { (it as JsonPrimitive).content })
        assertNull(init.header("Mcp-Session-Id"), "no session before the server hands one out")
        assertNull(init.header("MCP-Protocol-Version"))
        val initialized = server.requests("notifications/initialized").single()
        assertEquals("sess-1", initialized.header("Mcp-Session-Id"))
        assertEquals("2025-06-18", initialized.header("MCP-Protocol-Version"))
        assertNull(initialized.id, "a notification has no id")
        link.listTools(5_000)
        assertEquals("sess-1", server.requests("tools/list").single().header("mcp-session-id"))
        link.close()
    }

    @Test
    fun `every post is application json and accepts json and event streams`() = blocking {
        val server = newServer()
        val link = connect(server)
        link.listTools(5_000)
        for (seen in server.log) {
            assertEquals("application/json", seen.header("Content-Type"))
            assertEquals("application/json, text/event-stream", seen.header("Accept"))
        }
        link.close()
    }

    @Test
    fun `the negotiated protocol version is used on later requests`() = blocking {
        val server = newServer(protocolVersion = "2025-03-26")
        val link = connect(server)
        link.listTools(5_000)
        assertEquals("2025-03-26", server.requests("notifications/initialized").single().header("MCP-Protocol-Version"))
        assertEquals("2025-03-26", server.requests("tools/list").single().header("MCP-Protocol-Version"))
        link.close()
    }

    @Test
    fun `a server without sessions gets no session header`() = blocking {
        val server = newServer(sessionId = null)
        val link = connect(server)
        link.listTools(5_000)
        assertNull(server.requests("tools/list").single().header("Mcp-Session-Id"))
        link.close()
        Thread.sleep(200)
        assertTrue(server.log.none { it.httpMethod == "DELETE" }, "no session, nothing to delete")
    }

    @Test
    fun `custom headers go on every request`() = blocking {
        val server = newServer()
        val link = connect(server, headers = listOf("Authorization" to "Bearer abc", "X-Tenant" to "t1"))
        link.listTools(5_000)
        link.callTool("t", args, 5_000)
        for (seen in server.log) {
            assertEquals("Bearer abc", seen.header("Authorization"), seen.rpc)
            assertEquals("t1", seen.header("X-Tenant"))
        }
        assertTrue(server.log.size >= 4)
        link.close()
    }

    @Test
    fun `an initialize error fails the connection`() = blocking {
        val server = newServer()
        server.hook = { if (it.method == "initialize") error(it.id, -32600, "nope") else null }
        assertFailsWith<ConnectFailed> { connect(server) }
    }

    @Test
    fun `an http error on initialize fails the connection`() = blocking {
        val server = newServer()
        server.hook = { if (it.method == "initialize") MockResponse().setResponseCode(500).setBody("boom") else null }
        assertFailsWith<ConnectFailed> { connect(server) }
    }

    @Test
    fun `a failing initialized notification fails the connection and deletes the session`() = blocking {
        val server = newServer()
        server.hook = { if (it.method == "notifications/initialized") MockResponse().setResponseCode(500) else null }
        assertFailsWith<ConnectFailed> { connect(server) }
        server.awaitSeen { it.httpMethod == "DELETE" }
    }

    @Test
    fun `an unreachable server fails the connection`() = blocking {
        val server = newServer()
        val url = server.url
        server.close()
        assertFailsWith<ConnectFailed> { connect(server, url = url) }
    }

    @Test
    fun `a server that never answers initialize times out as a failed connection`() = blocking {
        val server = newServer()
        server.hook = { if (it.method == "initialize") MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else null }
        assertFailsWith<ConnectFailed> { connect(server, timeoutMillis = 300) }
    }

    @Test
    fun `an unusable protocol version or session id fails the connection`() = blocking {
        val badVersion = newServer(protocolVersion = "not a version!")
        assertFailsWith<ConnectFailed> { connect(badVersion) }
        val badSession = newServer(sessionId = "has space")
        assertFailsWith<ConnectFailed> { connect(badSession) }
    }

    @Test
    fun `connect rechecks the url and headers against the policy`() = blocking {
        val server = newServer()
        assertFailsWith<ConnectFailed> { StreamableHttpLink.connect(server.url, listOf("Host" to "x"), policy, 5_000) }
        assertFailsWith<ConnectFailed> { StreamableHttpLink.connect(server.url, emptyList(), SessionUrlPolicy(), 5_000) }
        assertFailsWith<ConnectFailed> { StreamableHttpLink.connect("http://example.com/mcp", emptyList(), policy, 5_000) }
        assertTrue(server.log.isEmpty())
    }

    @Test
    fun `connect resolves through the policy dns and refuses private resolutions`() = blocking {
        val server = newServer()
        val lookups = java.util.concurrent.CopyOnWriteArrayList<String>()
        // the name resolves to the address of a live server, but a loopback address is not public: the connection must not be made
        val rebinding = SessionUrlPolicy(resolver = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                lookups += hostname
                return listOf(InetAddress.getByName("127.0.0.1"))
            }
        })
        val e = assertFailsWith<ConnectFailed> { StreamableHttpLink.connect("https://rebind.example.com:${server.web.port}/mcp", emptyList(), rebinding, 3_000) }
        assertEquals(listOf("rebind.example.com"), lookups.toList(), "OkHttp resolved the name through the policy's Dns")
        assertEquals(0, server.web.requestCount, "nothing was sent to the resolved address")
        assertFalse(e.message!!.contains("rebind"))
    }

    // ------------------------------------------------------------------ tools/list

    @Test
    fun `listTools maps names, titles, descriptions, schemas and annotations`() = blocking {
        val server = newServer()
        server.tools = buildJsonArray {
            add(buildJsonObject {
                put("name", "search")
                put("title", "Search")
                put("description", "Finds things")
                put("inputSchema", buildJsonObject { put("type", "object"); put("required", buildJsonArray { add(JsonPrimitive("q")) }) })
                put("annotations", buildJsonObject { put("readOnlyHint", true); put("destructiveHint", false); put("idempotentHint", true); put("openWorldHint", false) })
            })
            add(toolJson("bare"))
            add(buildJsonObject { put("description", "no name") })
            add(JsonPrimitive("junk"))
        }
        val link = connect(server)
        val tools = link.listTools(5_000)
        assertEquals(listOf("search", "bare"), tools.map { it.name })
        val search = tools[0]
        assertEquals("Search", search.title)
        assertEquals("Finds things", search.description)
        assertEquals(JsonPrimitive("object"), search.inputSchema["type"])
        assertEquals(true, search.annotations!!.readOnlyHint)
        assertEquals(false, search.annotations.destructiveHint)
        assertEquals(true, search.annotations.idempotentHint)
        assertEquals(false, search.annotations.openWorldHint)
        assertNull(tools[1].annotations)
        link.close()
    }

    @Test
    fun `listTools follows nextCursor pages`() = blocking {
        val server = newServer()
        server.hook = { rpc ->
            if (rpc.method != "tools/list") {
                null
            } else {
                when ((rpc.params["cursor"] as? JsonPrimitive)?.content) {
                    null -> json(rpc.id, buildJsonObject { put("tools", buildJsonArray { add(toolJson("a")) }); put("nextCursor", "p2") })
                    "p2" -> json(rpc.id, buildJsonObject { put("tools", buildJsonArray { add(toolJson("b")) }); put("nextCursor", "p3") })
                    else -> json(rpc.id, buildJsonObject { put("tools", buildJsonArray { add(toolJson("c")) }) })
                }
            }
        }
        val link = connect(server)
        assertEquals(listOf("a", "b", "c"), link.listTools(5_000).map { it.name })
        assertEquals(3, server.requests("tools/list").size)
        link.close()
    }

    @Test
    fun `listTools stops after twenty pages`() = blocking {
        val server = newServer()
        var page = 0
        server.hook = { rpc ->
            if (rpc.method != "tools/list") {
                null
            } else {
                page++
                json(rpc.id, buildJsonObject { put("tools", buildJsonArray { add(toolJson("t$page")) }); put("nextCursor", "c$page") })
            }
        }
        val link = connect(server)
        assertEquals(20, link.listTools(10_000).size)
        assertEquals(20, server.requests("tools/list").size)
        link.close()
    }

    @Test
    fun `listTools stops on a repeated cursor`() = blocking {
        val server = newServer()
        server.hook = { rpc -> if (rpc.method == "tools/list") json(rpc.id, buildJsonObject { put("tools", buildJsonArray { add(toolJson("x")) }); put("nextCursor", "same") }) else null }
        val link = connect(server)
        assertEquals(2, link.listTools(5_000).size)
        link.close()
    }

    @Test
    fun `listTools reads an event stream response`() = blocking {
        val server = newServer()
        server.hook = { rpc ->
            if (rpc.method == "tools/list") {
                sse(
                    """{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}""",
                    message(rpc.id, buildJsonObject { put("tools", buildJsonArray { add(toolJson("streamed")) }) }),
                )
            } else {
                null
            }
        }
        val link = connect(server)
        val changed = async(start = CoroutineStart.UNDISPATCHED) { link.toolsChanged.first() }
        assertEquals(listOf("streamed"), link.listTools(5_000).map { it.name })
        withTimeout(5_000) { changed.await() }
        link.close()
    }

    @Test
    fun `listTools without a tools array is a bad response`() = blocking {
        val server = newServer()
        server.hook = { rpc -> if (rpc.method == "tools/list") json(rpc.id, buildJsonObject { put("nope", 1) }) else null }
        val link = connect(server)
        assertFailsWith<ConnectionLost> { link.listTools(5_000) }
        link.close()
    }

    @Test
    fun `an oversized tools list page is refused`() = blocking {
        val server = newServer()
        val pad = "a".repeat(StreamableHttpLink.MAX_LIST_BYTES.toInt() + 10)
        server.hook = { rpc -> if (rpc.method == "tools/list") json(rpc.id, buildJsonObject { put("tools", buildJsonArray { }); put("pad", pad) }) else null }
        val link = connect(server)
        assertEquals("response_too_large", assertFailsWith<ConnectionLost> { link.listTools(10_000) }.message)
        link.close()
    }

    @Test
    fun `an oversized chunked response without a length is refused`() = blocking {
        val server = newServer()
        val pad = "a".repeat(StreamableHttpLink.MAX_LIST_BYTES.toInt() + 10)
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", 0); put("result", buildJsonObject { put("tools", buildJsonArray { }); put("pad", pad) }) }.toString()
        server.hook = { rpc -> if (rpc.method == "tools/list") MockResponse().addHeader("Content-Type", "application/json").setChunkedBody(body, 16_384) else null }
        val link = connect(server)
        assertEquals("response_too_large", assertFailsWith<ConnectionLost> { link.listTools(10_000) }.message)
        link.close()
    }

    @Test
    fun `an oversized event stream is refused`() = blocking {
        val server = newServer()
        val filler = (1..40).joinToString("") { "data: ${"a".repeat(30_000)}\n\n" }
        server.hook = { rpc -> if (rpc.method == "tools/list") MockResponse().addHeader("Content-Type", "text/event-stream").setBody(filler) else null }
        val link = connect(server)
        assertEquals("response_too_large", assertFailsWith<ConnectionLost> { link.listTools(10_000) }.message)
        link.close()
    }

    // ------------------------------------------------------------------ tools/call

    @Test
    fun `callTool sends name and arguments and maps text image other and structured content`() = blocking {
        val server = newServer()
        server.callHandler = { rpc ->
            json(rpc.id, buildJsonObject {
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", "hello") })
                    add(buildJsonObject { put("type", "image"); put("data", "QUJD"); put("mimeType", "image/png") })
                    add(buildJsonObject { put("type", "audio"); put("data", "AAAA"); put("mimeType", "audio/wav") })
                    add(buildJsonObject { put("type", "resource_link"); put("uri", "file:///x") })
                    add(JsonPrimitive("junk"))
                }
                put("structuredContent", buildJsonObject { put("n", 3) })
                put("isError", true)
            })
        }
        val link = connect(server)
        val result = link.callTool("do_it", args, 5_000)
        assertEquals("do_it", (server.requests("tools/call").single().params!!["name"] as JsonPrimitive).content)
        assertEquals(args, server.requests("tools/call").single().params!!["arguments"])
        assertEquals(McpContentPart.Text("hello"), result.content[0])
        assertEquals(McpContentPart.Image("QUJD", "image/png"), result.content[1])
        val audio = result.content[2] as McpContentPart.Other
        assertEquals("audio", audio.type)
        assertEquals("AAAA", (audio.json["data"] as JsonPrimitive).content)
        assertEquals("resource_link", (result.content[3] as McpContentPart.Other).type)
        assertEquals("unknown", (result.content[4] as McpContentPart.Other).type)
        assertEquals(buildJsonObject { put("n", 3) }, result.structuredContent)
        assertTrue(result.isError)
        link.close()
    }

    @Test
    fun `a plain result is not an error`() = blocking {
        val server = newServer()
        val link = connect(server)
        val result = link.callTool("t", args, 5_000)
        assertFalse(result.isError)
        assertNull(result.structuredContent)
        assertEquals(listOf<Any>(McpContentPart.Text("ok")), result.content)
        link.close()
    }

    @Test
    fun `callTool reads an event stream and skips notifications and other ids`() = blocking {
        val server = newServer()
        server.callHandler = { rpc ->
            sse(
                """{"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":1}}""",
                """{"jsonrpc":"2.0","id":99999,"result":{"content":[{"type":"text","text":"someone else"}]}}""",
                """{"jsonrpc":"2.0","id":42,"method":"ping"}""",
                "not json at all",
                message(rpc.id, textContent("streamed result")),
                message(rpc.id, textContent("a second answer is ignored")),
            )
        }
        val link = connect(server)
        assertEquals("streamed result", (link.callTool("t", args, 5_000).content.single() as McpContentPart.Text).text)
        link.close()
    }

    @Test
    fun `an event stream accepts CRLF lines, multi line data and a final event without a blank line`() = blocking {
        val server = newServer()
        server.callHandler = { rpc ->
            // the two data lines are joined with "\n", which JSON allows between tokens
            val full = message(rpc.id, textContent("multi"))
            val cut = full.indexOf(",\"id\"") + 1
            MockResponse().addHeader("Content-Type", "text/event-stream; charset=utf-8")
                .setBody(": comment\r\nid: 7\r\nretry: 100\r\ndata: ${full.substring(0, cut)}\r\ndata:${full.substring(cut)}\r\n\r\n")
        }
        val link = connect(server)
        assertEquals("multi", (link.callTool("t", args, 5_000).content.single() as McpContentPart.Text).text)
        // the last event is not followed by a blank line: it is dispatched when the stream ends
        server.callHandler = { rpc -> MockResponse().addHeader("Content-Type", "text/event-stream").setBody("data: ${message(rpc.id, textContent("crlf"))}\r\n") }
        assertEquals("crlf", (link.callTool("t", args, 5_000).content.single() as McpContentPart.Text).text)
        link.close()
    }

    @Test
    fun `an event stream that ends without the response is a lost connection`() = blocking {
        val server = newServer()
        server.callHandler = { sse("""{"jsonrpc":"2.0","method":"notifications/progress"}""") }
        val link = connect(server)
        assertEquals("stream_ended", assertFailsWith<ConnectionLost> { link.callTool("t", args, 5_000) }.message)
        link.close()
    }

    @Test
    fun `a json rpc error is a server error with a short code, not the server text`() = blocking {
        val server = newServer()
        server.callHandler = { error(it.id, -32602, "Invalid arguments: field 'path' = /etc/secret is bad") }
        val link = connect(server)
        val e = assertFailsWith<ServerError> { link.callTool("t", args, 5_000) }
        assertEquals(-32602, e.code)
        assertEquals("invalid_params", e.detail)
        assertFalse(e.message!!.contains("secret"))
        assertTrue(e.mayHaveBeenSent)
        server.callHandler = { error(it.id, -32601, "x") }
        assertEquals("method_not_found", assertFailsWith<ServerError> { link.callTool("t", args, 5_000) }.detail)
        server.callHandler = { error(it.id, 12345, "x") }
        assertEquals("server_error", assertFailsWith<ServerError> { link.callTool("t", args, 5_000) }.detail)
        link.close()
    }

    @Test
    fun `4xx and most 5xx statuses are server errors carrying the status`() = blocking {
        val server = newServer()
        val link = connect(server)
        for (status in listOf(400, 401, 403, 405, 429, 500, 501)) {
            server.callHandler = { MockResponse().setResponseCode(status).setBody("BODYSECRET") }
            val e = assertFailsWith<ServerError>("status $status") { link.callTool("t", args, 5_000) }
            assertEquals(status, e.code)
            assertEquals("http_$status", e.detail)
        }
        link.close()
    }

    @Test
    fun `gateway statuses 502 503 504 leave the result unknown`() = blocking {
        val server = newServer()
        val link = connect(server)
        for (status in listOf(502, 503, 504)) {
            server.callHandler = { MockResponse().setResponseCode(status) }
            val e = assertFailsWith<ConnectionLost>("status $status") { link.callTool("t", args, 5_000) }
            assertTrue(e.mayHaveBeenSent)
            assertEquals("http_$status", e.message)
        }
        link.close()
    }

    @Test
    fun `a wrong content type or malformed json is a lost connection`() = blocking {
        val server = newServer()
        val link = connect(server)
        server.callHandler = { MockResponse().addHeader("Content-Type", "text/plain").setBody("hello") }
        assertEquals("bad_content_type", assertFailsWith<ConnectionLost> { link.callTool("t", args, 5_000) }.message)
        server.callHandler = { MockResponse().setBody("hello") }
        assertEquals("bad_content_type", assertFailsWith<ConnectionLost> { link.callTool("t", args, 5_000) }.message)
        server.callHandler = { MockResponse().addHeader("Content-Type", "application/json").setBody("{not json") }
        assertEquals("bad_response", assertFailsWith<ConnectionLost> { link.callTool("t", args, 5_000) }.message)
        server.callHandler = { MockResponse().addHeader("Content-Type", "application/json").setBody("""{"jsonrpc":"2.0","id":99999,"result":{}}""") }
        assertEquals("bad_response", assertFailsWith<ConnectionLost> { link.callTool("t", args, 5_000) }.message)
        server.callHandler = { MockResponse().addHeader("Content-Type", "application/json").setBody("[1,2]") }
        assertEquals("bad_response", assertFailsWith<ConnectionLost> { link.callTool("t", args, 5_000) }.message)
        server.callHandler = { MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody("") }
        assertIs<ConnectionLost>(assertFailsWith<McpLinkException> { link.callTool("t", args, 5_000) })
        link.close()
    }

    @Test
    fun `an oversized tool result is refused as lost, not completed`() = blocking {
        val server = newServer()
        val pad = "a".repeat(StreamableHttpLink.MAX_CALL_BYTES.toInt() + 10)
        server.callHandler = { json(it.id, buildJsonObject { putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", pad) }) } }) }
        val link = connect(server)
        val e = assertFailsWith<ConnectionLost> { link.callTool("t", args, 20_000) }
        assertEquals("response_too_large", e.message)
        assertTrue(e.mayHaveBeenSent)
        // the link itself is still usable afterwards
        server.callHandler = { json(it.id, textContent("small")) }
        assertEquals("small", (link.callTool("t", args, 5_000).content.single() as McpContentPart.Text).text)
        link.close()
    }

    @Test
    fun `a result just under the limit is accepted`() = blocking {
        val server = newServer()
        val pad = "a".repeat(3 * 1024 * 1024)
        server.callHandler = { json(it.id, textContent(pad)) }
        val link = connect(server)
        assertEquals(pad.length, (link.callTool("t", args, 20_000).content.single() as McpContentPart.Text).text.length)
        link.close()
    }

    @Test
    fun `parallel calls get their own answers`() = blocking {
        val server = newServer()
        server.callHandler = { rpc -> json(rpc.id, textContent("answer-" + (rpc.params["arguments"] as JsonObject)["n"])) }
        val link = connect(server)
        val results = (1..12).map { n -> async(Dispatchers.IO) { link.callTool("t", buildJsonObject { put("n", n) }, 10_000) } }.awaitAll()
        assertEquals((1..12).map { "answer-$it" }, results.map { (it.content.single() as McpContentPart.Text).text })
        link.close()
    }

    // ------------------------------------------------------------------ 超时与取消

    @Test
    fun `a call that gets no answer times out and tells the server it was abandoned`() = blocking {
        val server = newServer()
        server.callHandler = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val link = connect(server)
        val e = assertFailsWith<TimedOut> { link.callTool("slow", args, 300) }
        assertEquals(300, e.timeoutMillis)
        assertTrue(e.mayHaveBeenSent)
        val callId = server.requests("tools/call").single().id
        val cancelled = server.awaitSeen { it.rpc == "notifications/cancelled" }
        assertEquals(callId, cancelled.params!!["requestId"])
        link.close()
    }

    @Test
    fun `cancelling the caller cancels the call and posts notifications cancelled`() = blocking {
        val server = newServer()
        server.callHandler = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val link = connect(server)
        var thrown: Throwable? = null
        val job = launch(Dispatchers.IO) {
            try {
                link.callTool("slow", args, 60_000)
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }
        val call = server.awaitSeen { it.rpc == "tools/call" }
        job.cancelAndJoin()
        assertTrue(thrown is CancellationException, "got $thrown")
        val cancelled = server.awaitSeen { it.rpc == "notifications/cancelled" }
        assertEquals(call.id, cancelled.params!!["requestId"])
        assertEquals("sess-1", cancelled.header("Mcp-Session-Id"))
        assertFalse(link.closed.isCompleted, "cancelling a call does not close the link")
        link.close()
    }

    @Test
    fun `cancelling before the request is written sends no notification`() = blocking {
        val server = newServer()
        val link = connect(server)
        val before = server.log.size
        val job = launch(start = CoroutineStart.LAZY) { link.callTool("t", args, 5_000) }
        job.cancel()
        job.join()
        Thread.sleep(200)
        assertTrue(server.log.size == before)
        link.close()
    }

    @Test
    fun `a dropped connection after the request was sent is a lost connection`() = blocking {
        val server = newServer()
        server.callHandler = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val link = connect(server)
        val e = assertFailsWith<ConnectionLost> { link.callTool("t", args, 5_000) }
        assertTrue(e.mayHaveBeenSent)
        link.close()
    }

    @Test
    fun `a request that never reached the server is not sent`() = blocking {
        val server = newServer()
        val ownClient = okhttp3.OkHttpClient()
        val link = StreamableHttpLink.connect(server.url, emptyList(), policy, 5_000, ownClient)
        server.close()
        ownClient.connectionPool.evictAll() // no kept-alive connection left: the next request has to connect, and is refused
        val e = assertFailsWith<NotSent> { link.callTool("t", args, 5_000) }
        assertFalse(e.mayHaveBeenSent)
        link.close()
    }

    @Test
    fun `a request that fails on a kept alive connection after it was written is conservatively lost`() = blocking {
        val server = newServer()
        val link = connect(server)
        server.close()
        // depending on whether OkHttp notices the dead connection first, this is NotSent or ConnectionLost: never a ServerError or a success
        val e = assertFailsWith<McpLinkException> { link.callTool("t", args, 5_000) }
        assertTrue(e is NotSent || e is ConnectionLost)
        link.close()
    }

    // ------------------------------------------------------------------ 重定向

    @Test
    fun `a redirect on a call is not followed`() = blocking {
        val server = newServer()
        val other = newServer()
        server.callHandler = { MockResponse().setResponseCode(302).addHeader("Location", other.url) }
        val link = connect(server)
        val e = assertFailsWith<ServerError> { link.callTool("t", args, 5_000) }
        assertEquals(302, e.code)
        assertEquals(0, other.web.requestCount, "the redirect target was never contacted")
        link.close()
    }

    @Test
    fun `a redirect on initialize fails the connection without following it`() = blocking {
        val server = newServer()
        val other = newServer()
        for (status in listOf(301, 302, 307, 308)) {
            server.hook = { MockResponse().setResponseCode(status).addHeader("Location", other.url) }
            assertFailsWith<ConnectFailed>("status $status") { connect(server) }
        }
        assertEquals(0, other.web.requestCount)
    }

    // ------------------------------------------------------------------ 关闭与会话过期

    @Test
    fun `close deletes the session once, completes closed and refuses further calls`() = blocking {
        val server = newServer()
        val link = connect(server)
        link.close()
        link.close()
        assertEquals("closed", link.closed.await())
        val delete = server.awaitSeen { it.httpMethod == "DELETE" }
        assertEquals("sess-1", delete.header("Mcp-Session-Id"))
        Thread.sleep(300)
        assertEquals(1, server.log.count { it.httpMethod == "DELETE" })
        val e = assertFailsWith<NotSent> { link.callTool("t", args, 5_000) }
        assertEquals("closed", e.message)
        assertFailsWith<NotSent> { link.listTools(5_000) }
    }

    @Test
    fun `a failing delete is ignored`() = blocking {
        val server = newServer()
        val link = connect(server)
        server.close()
        link.close()
        assertTrue(link.closed.isCompleted)
    }

    @Test
    fun `close cancels a call in flight`() = blocking {
        val server = newServer()
        server.callHandler = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val link = connect(server)
        val call = async(Dispatchers.IO) { runCatching { link.callTool("slow", args, 60_000) } }
        server.awaitSeen { it.rpc == "tools/call" }
        link.close()
        val outcome = withTimeout(10_000) { call.await() }.exceptionOrNull()
        assertIs<ConnectionLost>(outcome)
        assertEquals("closed", outcome.message)
    }

    @Test
    fun `404 with a session id means the session expired`() = blocking {
        val server = newServer()
        val link = connect(server)
        server.callHandler = { MockResponse().setResponseCode(404) }
        val e = assertFailsWith<ConnectionLost> { link.callTool("t", args, 5_000) }
        assertEquals("session_expired", e.message)
        assertEquals("session_expired", link.closed.await())
        Thread.sleep(300)
        assertTrue(server.log.none { it.httpMethod == "DELETE" }, "the session is gone, nothing to delete")
        assertFailsWith<NotSent> { link.listTools(5_000) }
    }

    @Test
    fun `404 without a session id is an ordinary server error`() = blocking {
        val server = newServer(sessionId = null)
        val link = connect(server)
        server.callHandler = { MockResponse().setResponseCode(404) }
        val e = assertFailsWith<ServerError> { link.callTool("t", args, 5_000) }
        assertEquals(404, e.code)
        assertFalse(link.closed.isCompleted)
        link.close()
    }

    @Test
    fun `toString says nothing about the url`() = blocking {
        val server = newServer()
        val link = connect(server, url = server.url + "-URLSECRET-4c1d?token=URLSECRET-4c1d")
        assertFalse(link.toString().contains("URLSECRET"))
        assertFalse(link.toString().contains("127.0.0.1"))
        link.close()
    }

    // ------------------------------------------------------------------ 不泄露

    @Test
    fun `no failure mentions the url, header values or response bodies`() = blocking {
        val urlMarker = "URLSECRET-4c1d"
        val headerMarker = "HDRSECRET-91ab"
        val bodyMarker = "BODYSECRET-7e55"
        val headers = listOf("Authorization" to "Bearer $headerMarker", "X-Api-Key" to headerMarker)
        val failures = ArrayList<Throwable>()

        suspend fun collect(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: McpLinkException) {
                failures += e
            }
        }

        val server = newServer()
        val url = server.url + "/$urlMarker?token=$urlMarker"
        // connection phase
        val dead = newServer().also { it.close() }
        collect { connect(dead, headers, url = dead.url + "/$urlMarker?token=$urlMarker") }
        server.hook = { if (it.method == "initialize") MockResponse().setResponseCode(500).setBody("$bodyMarker $headerMarker") else null }
        collect { connect(server, headers, url = url) }
        server.hook = { if (it.method == "initialize") error(it.id, -32000, "$bodyMarker $headerMarker") else null }
        collect { connect(server, headers, url = url) }
        server.hook = { if (it.method == "initialize") MockResponse().setResponseCode(302).addHeader("Location", "https://$bodyMarker.example.com/$urlMarker") else null }
        collect { connect(server, headers, url = url) }
        collect { StreamableHttpLink.connect("http://$urlMarker.example.com/x", headers, policy, 1_000) }
        collect { StreamableHttpLink.connect("https://$urlMarker.example.com/x", listOf("Host" to headerMarker), policy, 1_000) }
        server.hook = { null }

        // running phase
        val link = connect(server, headers, url = url)
        server.hook = { if (it.method == "tools/list") MockResponse().setResponseCode(401).setBody("$bodyMarker $headerMarker $url") else null }
        collect { link.listTools(5_000) }
        for (handler in listOf<(Rpc) -> MockResponse>(
            { error(it.id, -32602, "$bodyMarker $headerMarker $url") },
            { MockResponse().setResponseCode(502).setBody(bodyMarker) },
            { MockResponse().setResponseCode(400).setBody("$bodyMarker $headerMarker") },
            { MockResponse().addHeader("Content-Type", "application/json").setBody("{$bodyMarker $headerMarker") },
            { MockResponse().addHeader("Content-Type", "text/plain").setBody("$bodyMarker $headerMarker") },
            { sse("data: $bodyMarker") },
            { MockResponse().setResponseCode(302).addHeader("Location", "http://$bodyMarker.example.com/$urlMarker") },
            { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) },
            { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) },
        )) {
            server.callHandler = handler
            collect { link.callTool("t", args, 400) }
        }
        server.callHandler = { MockResponse().setResponseCode(404).setBody(bodyMarker) }
        collect { link.callTool("t", args, 5_000) } // session expired: closes the link
        collect { link.callTool("t", args, 5_000) } // closed

        assertTrue(failures.size >= 14, "collected ${failures.size} failures")
        for (e in failures) {
            val text = e.stackTraceToString()
            for (marker in listOf(urlMarker, headerMarker, bodyMarker, "127.0.0.1", "Bearer", "token=")) {
                assertFalse(text.contains(marker), "'$marker' leaked into: $text")
            }
            // (kotlinx.coroutines stack trace recovery may add a copy of the same exception as cause when assertions are on; it is covered by the text check above)
        }
    }

    @Test
    fun `the default client never follows redirects`() {
        // the hardening is applied by connect() to whatever client it is given: a permissive client does not weaken it
        val permissive = okhttp3.OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).retryOnConnectionFailure(true).build()
        val server = newServer()
        val other = newServer()
        server.callHandler = { MockResponse().setResponseCode(307).addHeader("Location", other.url) }
        blocking {
            val link = StreamableHttpLink.connect(server.url, emptyList(), policy, 5_000, permissive)
            assertEquals(307, assertFailsWith<ServerError> { link.callTool("t", args, 5_000) }.code)
            assertEquals(0, other.web.requestCount)
            link.close()
        }
    }
}
