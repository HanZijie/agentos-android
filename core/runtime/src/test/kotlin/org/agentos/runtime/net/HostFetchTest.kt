package org.agentos.runtime.net

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostFetchTest {
    private lateinit var server: MockWebServer
    private val key = ApiKey("sk-real-key-for-test")
    private val sleeps = mutableListOf<Long>()

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    // Numeric loopback: `localhost` also resolves to ::1, where MockWebServer does not listen.
    private fun base(): String = "http://127.0.0.1:${server.port}/api"

    private fun fetch(retry: RetryPolicy = RetryPolicy.NONE, clock: () -> Long = System::currentTimeMillis) = HostFetch(
        credentials = BaseUrlCredentials(listOf(BaseUrlCredentials.Entry(base(), key))),
        retry = retry,
        clock = clock,
        sleep = { sleeps += it },
    )

    private fun post(vararg headers: Pair<String, String>, url: String = base() + "/v1/messages") =
        FetchRequest(url = url, method = "POST", headers = headers.toList() + ("content-type" to "application/json"), body = """{"x":1}""", sessionId = "s1")

    @Test
    fun `placeholder in x-api-key and Bearer is replaced with the endpoint key`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("ok"))
        server.enqueue(MockResponse().setBody("ok"))
        val f = fetch()
        f.open(post("x-api-key" to PLACEHOLDER_API_KEY)).use { assertEquals(200, it.status) }
        f.open(post("Authorization" to "Bearer $PLACEHOLDER_API_KEY")).use { assertEquals(200, it.status) }
        assertEquals(key.secret, server.takeRequest().getHeader("x-api-key"))
        val second = server.takeRequest()
        assertEquals("Bearer ${key.secret}", second.getHeader("Authorization"))
        assertEquals("""{"x":1}""", second.body.readUtf8())
    }

    @Test
    fun `no request is sent when no key is configured for the endpoint`() = runBlocking<Unit> {
        val f = HostFetch(credentials = { null })
        val e = assertFailsWith<HostFetchException> { f.open(post("x-api-key" to PLACEHOLDER_API_KEY)) }
        assertEquals(NetErrorKind.NO_CREDENTIAL, e.kind)
        assertEquals(false, e.retryable)
        assertEquals(0, server.requestCount)
        assertTrue(key.secret !in e.message.orEmpty())
    }

    @Test
    fun `placeholder in any other form is refused`() = runBlocking<Unit> {
        val e = assertFailsWith<HostFetchException> { fetch().open(post("authorization" to "Token $PLACEHOLDER_API_KEY")) }
        assertEquals(NetErrorKind.REJECTED, e.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `requests without the placeholder need no key`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("ok"))
        HostFetch(credentials = { null }).open(post()).use { assertEquals(200, it.status) }
        assertNull(server.takeRequest().getHeader("x-api-key"))
    }

    @Test
    fun `hop-by-hop and encoding headers from JS are dropped, others kept`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("ok"))
        fetch().open(post("accept-encoding" to "br", "connection" to "close", "content-length" to "999", "x-stainless-os" to "Unknown", "anthropic-version" to "2023-06-01")).close()
        val r = server.takeRequest()
        assertEquals("gzip", r.getHeader("Accept-Encoding"), "OkHttp's own transparent gzip")
        assertEquals("7", r.getHeader("Content-Length"))
        assertEquals("Unknown", r.getHeader("x-stainless-os"))
        assertEquals("2023-06-01", r.getHeader("anthropic-version"))
    }

    @Test
    fun `redirects are not followed, so the key header cannot travel to another host`() = runBlocking<Unit> {
        val other = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "http://127.0.0.1:${other.port}/steal"))
            fetch().open(post("x-api-key" to PLACEHOLDER_API_KEY)).use { assertEquals(307, it.status) }
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `cleartext http is refused except to loopback`() = runBlocking<Unit> {
        val e = assertFailsWith<HostFetchException> { HostFetch(credentials = { null }).open(FetchRequest("http://example.com/v1")) }
        assertEquals(NetErrorKind.REJECTED, e.kind)
        assertTrue(HostFetch.isLoopback("127.0.0.1") && HostFetch.isLoopback("localhost") && !HostFetch.isLoopback("10.0.2.2"))
    }

    @Test
    fun `body streams chunk by chunk as it arrives`() = runBlocking<Unit> {
        val body = (1..20).joinToString("") { "data: {\"n\":$it}\n\n" }
        server.enqueue(MockResponse().setHeader("content-type", "text/event-stream").setBody(body).throttleBody(40, 50, TimeUnit.MILLISECONDS))
        val r = fetch().open(post())
        val t0 = System.nanoTime()
        val first = r.read()!!
        val firstMs = (System.nanoTime() - t0) / 1e6
        var total = first.size
        var chunks = 1
        while (true) {
            val c = r.read() ?: break
            total += c.size
            chunks++
        }
        assertEquals(body.toByteArray().size, total)
        assertTrue(chunks >= 5, "chunks=$chunks")
        assertTrue(firstMs < 200, "first chunk after ${firstMs}ms: not streamed")
    }

    @Test
    fun `cancel stops a body mid-stream`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("x".repeat(10_000)).throttleBody(10, 100, TimeUnit.MILLISECONDS))
        val r = fetch().open(post())
        r.read()
        r.cancel()
        val e = assertFailsWith<HostFetchException> { r.read() }
        assertEquals(NetErrorKind.CANCELED, e.kind)
    }

    @Test
    fun `cancelling the caller cancels a request waiting for its head`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val f = fetch()
        val t0 = System.nanoTime()
        val job = async { f.open(post()) }
        delay(200)
        job.cancel()
        withTimeout(2_000) { job.join() }
        assertTrue((System.nanoTime() - t0) / 1e6 < 2_000)
    }

    @Test
    fun `connection failure is classified as retryable CONNECT`() = runBlocking<Unit> {
        val port = server.port
        server.shutdown()
        val e = assertFailsWith<HostFetchException> {
            HostFetch(credentials = { null }).open(FetchRequest("http://127.0.0.1:$port/v1", "POST", body = "{}"))
        }
        assertEquals(NetErrorKind.CONNECT, e.kind)
        assertTrue(e.retryable)
    }

    @Test
    fun `status classification matches the SDK retry rules`() {
        for (s in listOf(408, 409, 429, 500, 502, 503, 529)) assertTrue(HttpStatusPolicy.isRetryable(s), "$s")
        for (s in listOf(200, 400, 401, 403, 404, 413, 422)) assertTrue(!HttpStatusPolicy.isRetryable(s), "$s")
    }

    @Test
    fun `default policy does not retry`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("ok"))
        fetch().open(post()).use { assertEquals(503, it.status); assertEquals(1, it.attempts) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `retryable status is retried before the head reaches JS`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("retry-after", "2"))
        server.enqueue(MockResponse().setBody("ok"))
        val f = fetch(RetryPolicy(maxAttempts = 3, initialBackoffMs = 100))
        f.open(post("x-api-key" to PLACEHOLDER_API_KEY)).use {
            assertEquals(200, it.status)
            assertEquals(3, it.attempts)
        }
        assertEquals(3, server.requestCount)
        assertEquals(2, f.retried)
        assertTrue(sleeps[0] in 75..100, "backoff ${sleeps[0]}")
        assertEquals(2_000, sleeps[1], "retry-after honoured")
    }

    @Test
    fun `transport failure is retried`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setBody("ok"))
        fetch(RetryPolicy(maxAttempts = 2)).open(post()).use { assertEquals(200, it.status); assertEquals(2, it.attempts) }
    }

    @Test
    fun `non-retryable status, long retry-after and a near deadline are returned as is`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(400))
        fetch(RetryPolicy(maxAttempts = 3)).open(post()).use { assertEquals(400, it.status); assertEquals(1, it.attempts) }

        server.enqueue(MockResponse().setResponseCode(429).setHeader("retry-after", "120"))
        fetch(RetryPolicy(maxAttempts = 3, maxRetryAfterMs = 60_000)).open(post()).use { assertEquals(429, it.status); assertEquals(1, it.attempts) }

        server.enqueue(MockResponse().setResponseCode(503))
        val now = 1_000_000L
        fetch(RetryPolicy(maxAttempts = 3, initialBackoffMs = 500, deadline = { now + 100 }), clock = { now })
            .open(post()).use { assertEquals(503, it.status); assertEquals(1, it.attempts) }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `deadline sees the session of the request`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("ok"))
        val seen = mutableListOf<String?>()
        fetch(RetryPolicy(maxAttempts = 2, deadline = { seen += it.sessionId; null })).open(post()).close()
        assertEquals(listOf<String?>("s1"), seen)
    }
}
