package org.agentos.runtime.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.agentos.runtime.pi.testing.RevocableSecrets
import org.agentos.runtime.ports.Credential
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** errors.md section 4: the egress retries the same request before the head reaches JS. */
class HostFetchRetryTest {

    private lateinit var server: MockWebServer
    private val key = Credential("sk-retry-test-key")
    private var now = 1_000_000L
    private val sleeps = CopyOnWriteArrayList<Long>()
    private val notices = CopyOnWriteArrayList<RetryNotice>()

    /** Jitter off: nextDouble() == 0.0, so waits are exactly the rule. */
    private val noJitter = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun base() = "http://127.0.0.1:${server.port}/api"

    /** Virtual time: sleeping advances the clock. */
    private fun fetch(
        retry: RetryPolicy = RetryPolicy.DEFAULT,
        client: okhttp3.OkHttpClient = HostFetch.defaultClient(),
        secrets: org.agentos.runtime.ports.SecretPort = BaseUrlCredentials(listOf(BaseUrlCredentials.Entry(base(), key))),
        virtualTime: Boolean = true,
    ) = if (virtualTime) {
        HostFetch(secrets, client, retry, clock = { now }, sleep = { now += it; sleeps += it }, random = noJitter, onRetry = { notices += it })
    } else {
        HostFetch(secrets, client, retry, random = noJitter, onRetry = { notices += it })
    }

    private fun post(query: String = "") = FetchRequest(
        url = base() + "/v1/messages$query",
        method = "POST",
        headers = listOf("x-api-key" to PLACEHOLDER_API_KEY, "content-type" to "application/json"),
        body = "{}",
        sessionId = "s1",
    )

    @Test
    fun `production policy backs off from 1 s doubling to 30 s within a 2 minute budget`() = runBlocking<Unit> {
        repeat(12) { server.enqueue(MockResponse().setResponseCode(529)) }
        fetch().open(post()).use {
            assertEquals(529, it.status, "the last response goes to JS when the budget is spent")
            assertEquals(8, it.attempts)
        }
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000), sleeps.toList())
        assertEquals(8, server.requestCount, "a further 30 s wait would end after 120 s")
        assertEquals((1..7).toList(), notices.map { it.attempt })
        assertTrue(notices.all { it.reason == "HTTP 529" && !it.serverRequested && it.sessionId == "s1" })
    }

    @Test
    fun `jitter only lengthens the wait and never passes the cap`() = runBlocking<Unit> {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        server.enqueue(MockResponse().setBody("ok"))
        val f = HostFetch(
            BaseUrlCredentials(listOf(BaseUrlCredentials.Entry(base(), key))),
            retry = RetryPolicy(maxAttempts = 4, initialBackoffMs = 1_000, maxBackoffMs = 2_000),
            sleep = { sleeps += it },
        )
        f.open(post()).use { assertEquals(200, it.status) }
        assertTrue(sleeps[0] in 1_000..1_250 && sleeps[1] in 2_000..2_000 && sleeps[2] == 2_000L, "$sleeps")
    }

    @Test
    fun `retry-after takes precedence, within the limit and the budget`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("retry-after", "7"))
        server.enqueue(MockResponse().setBody("ok"))
        fetch().open(post()).use { assertEquals(200, it.status) }
        assertEquals(listOf(7_000L), sleeps.toList(), "server's 7 s instead of the 1 s backoff")
        assertTrue(notices.single().serverRequested)

        // Longer than maxRetryAfterMs (60 s): handed to JS as is, with its retry-after.
        server.enqueue(MockResponse().setResponseCode(429).setHeader("retry-after", "90"))
        fetch().open(post()).use { assertEquals(429, it.status); assertEquals(1, it.attempts) }

        // Within the limit but past the 2 minute budget.
        server.enqueue(MockResponse().setResponseCode(503).setHeader("retry-after-ms", "50000"))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("retry-after-ms", "50000"))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("retry-after-ms", "50000"))
        fetch().open(post()).use { assertEquals(503, it.status); assertEquals(3, it.attempts, "50 + 50 s fit, a third wait would not") }
    }

    @Test
    fun `final statuses are never retried`() = runBlocking<Unit> {
        for (status in listOf(400, 401, 402, 403, 404, 409, 413, 422)) {
            server.enqueue(MockResponse().setResponseCode(status))
            fetch().open(post()).use { assertEquals(1, it.attempts, "$status") }
        }
        assertEquals(8, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `network failures before the head are retried and the attempts are reported`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setBody("ok"))
        fetch().open(post()).use { assertEquals(200, it.status); assertEquals(3, it.attempts) }
        assertEquals(listOf(1_000L, 2_000), sleeps.toList())

        repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        val e = assertFailsWith<HostFetchException> { fetch(RetryPolicy(maxAttempts = 3, initialBackoffMs = 10)).open(post()) }
        assertEquals(NetErrorKind.NETWORK, e.kind)
        assertEquals(3, e.attempts)
    }

    /** Fails the first [times] calls with [error], from an interceptor before or after the connection. */
    private fun failing(times: Int, afterConnect: Boolean, error: () -> java.io.IOException): okhttp3.OkHttpClient {
        val left = AtomicInteger(times)
        val interceptor = Interceptor { chain -> if (left.getAndDecrement() > 0) throw error() else chain.proceed(chain.request()) }
        val b = HostFetch.defaultClient().newBuilder()
        return (if (afterConnect) b.addNetworkInterceptor(interceptor) else b.addInterceptor(interceptor)).build()
    }

    @Test
    fun `a timeout is retried only while connecting`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("ok"))
        fetch(client = failing(1, afterConnect = false) { SocketTimeoutException("connect timed out") }).open(post()).use {
            assertEquals(200, it.status)
            assertEquals(2, it.attempts, "connect-phase timeout retried")
        }

        server.enqueue(MockResponse().setBody("never reached"))
        val e = assertFailsWith<HostFetchException> {
            fetch(client = failing(1, afterConnect = true) { SocketTimeoutException("timeout") }).open(post())
        }
        assertEquals(NetErrorKind.TIMEOUT, e.kind)
        assertEquals(1, e.attempts, "a timeout waiting for the head is not repeated")
    }

    @Test
    fun `TLS failures are never retried`() = runBlocking<Unit> {
        val e = assertFailsWith<HostFetchException> {
            fetch(client = failing(5, afterConnect = false) { SSLHandshakeException("bad certificate") }).open(post())
        }
        assertEquals(NetErrorKind.TLS, e.kind)
        assertEquals(1, e.attempts)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `failures after the head are not retried`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("data: x\n\n".repeat(2_000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val r = fetch().open(post())
        assertEquals(200, r.status)
        assertFailsWith<HostFetchException> { while (r.read() != null) Unit }
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `cancelling the caller ends a retry wait at once`() = runBlocking<Unit> {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        val f = fetch(RetryPolicy(maxAttempts = 3, initialBackoffMs = 10_000, maxBackoffMs = 10_000), virtualTime = false)
        val opening = CoroutineScope(Dispatchers.IO).async { f.open(post()) }
        withTimeout(5_000) { while (notices.isEmpty()) delay(5) }
        val t0 = System.nanoTime()
        opening.cancel()
        runCatching { opening.await() }
        assertTrue((System.nanoTime() - t0) / 1e6 < 1_000, "the 10 s wait did not hold up the cancellation")
        delay(200)
        assertEquals(1, server.requestCount, "no retry after the cancellation")
    }

    @Test
    fun `revoking the key ends a retry wait with KEY_REVOKED`() = runBlocking<Unit> {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        val secrets = RevocableSecrets(listOf(base() to key))
        val f = fetch(RetryPolicy(maxAttempts = 3, initialBackoffMs = 10_000, maxBackoffMs = 10_000), secrets = secrets, virtualTime = false)
        val opening = CoroutineScope(Dispatchers.IO).async { f.open(post()) }
        withTimeout(5_000) { while (notices.isEmpty()) delay(5) }
        val t0 = System.nanoTime()
        secrets.revoke(key)
        val e = assertFailsWith<HostFetchException> { withTimeout(5_000) { opening.await() } }
        assertEquals(NetErrorKind.KEY_REVOKED, e.kind)
        assertTrue((System.nanoTime() - t0) / 1e6 < 1_000)
        assertEquals(1, server.requestCount, "no retry with a revoked key")
        f.close()
    }

    @Test
    fun `retry notices carry no key and no query string`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("ok"))
        fetch().open(post(query = "?token=secret-in-query")).close()
        val n = notices.single()
        assertEquals(base() + "/v1/messages", n.endpoint)
        val text = listOf(n.sessionId, n.endpoint, n.reason, n.attempt, n.waitMs).joinToString()
        assertFalse(key.reveal() in text || "secret-in-query" in text, text)
    }
}
