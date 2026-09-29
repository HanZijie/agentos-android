package org.agentos.runtime.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.agentos.runtime.pi.testing.RevocableSecrets
import org.agentos.runtime.ports.Credential
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Architecture F9 second layer: HostFetch cuts off in-flight calls whose key is revoked. */
class HostFetchRevocationTest {

    private lateinit var server: MockWebServer
    private val secret = "sk-revocation-test-key"
    private val k1 = Credential(secret)
    private val k2 = Credential(secret) // same secret, different key object

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun base(path: String) = "http://127.0.0.1:${server.port}/$path"

    private fun post(base: String) = FetchRequest(
        url = "$base/v1/messages",
        method = "POST",
        headers = listOf("x-api-key" to PLACEHOLDER_API_KEY, "content-type" to "application/json"),
        body = "{}",
    )

    /** A body of [chunks] SSE lines, one every [intervalMs]. */
    private fun slowStream(chunks: Int = 100, intervalMs: Long = 50): MockResponse {
        val line = "data: {\"n\":0000}\n\n"
        return MockResponse().setHeader("content-type", "text/event-stream")
            .setBody(line.repeat(chunks)).throttleBody(line.length.toLong(), intervalMs, TimeUnit.MILLISECONDS)
    }

    private fun msSince(t0: Long) = (System.nanoTime() - t0) / 1e6

    @Test
    fun `revoking the key cuts off a streaming body with KEY_REVOKED`() = runBlocking<Unit> {
        val secrets = RevocableSecrets(listOf(base("a") to k1))
        val fetch = HostFetch(secrets)
        assertEquals(1, secrets.subscribers, "subscribed when constructed, before any request")
        server.enqueue(slowStream())
        val response = fetch.open(post(base("a")))
        assertTrue(response.read()!!.isNotEmpty())
        assertEquals(1, fetch.callsWithKey)

        val t0 = System.nanoTime()
        secrets.revoke(k1)
        val e = assertFailsWith<HostFetchException> {
            withTimeout(5_000) { while (response.read() != null) Unit }
        }
        val ms = msSince(t0)
        assertEquals(NetErrorKind.KEY_REVOKED, e.kind)
        assertFalse(e.retryable)
        assertTrue(response.keyRevoked)
        assertTrue(ms < 1_000, "cut off after ${ms}ms")
        assertFalse(secret in e.message.orEmpty(), "the message never carries the key")
        assertEquals(0, fetch.callsWithKey, "released")
        println("revoke -> KEY_REVOKED on read: ${"%.0f".format(ms)} ms")
        fetch.close()
        withTimeout(2_000) { while (secrets.subscribers != 0) delay(5) } // close ends the subscription
    }

    @Test
    fun `revoking while waiting for the head fails open and is not retried`() = runBlocking<Unit> {
        val secrets = RevocableSecrets(listOf(base("a") to k1))
        val fetch = HostFetch(secrets, retry = RetryPolicy(maxAttempts = 3), sleep = {})
        server.enqueue(MockResponse().setHeadersDelay(3, TimeUnit.SECONDS).setBody("late"))
        server.enqueue(MockResponse().setBody("must not be requested"))

        // Own scope: a failing child of runBlocking would cancel the test before await() sees it.
        val opening = CoroutineScope(Dispatchers.IO).async { fetch.open(post(base("a"))) }
        withTimeout(5_000) { while (server.requestCount == 0) delay(5) }
        val t0 = System.nanoTime()
        secrets.revoke(k1)
        val e = assertFailsWith<HostFetchException> { withTimeout(5_000) { opening.await() } }
        val ms = msSince(t0)
        assertEquals(NetErrorKind.KEY_REVOKED, e.kind)
        assertTrue(ms < 1_000, "open failed after ${ms}ms")
        assertEquals(1, fetch.started, "KEY_REVOKED is never retried")
        assertEquals(0, fetch.retried)
        assertEquals(0, fetch.callsWithKey)
        fetch.close()
    }

    @Test
    fun `only calls carrying the revoked key object are cut off`() = runBlocking<Unit> {
        val k3 = Credential("sk-another-key")
        val secrets = RevocableSecrets(listOf(base("a") to k1, base("b") to k2, base("c") to k3))
        val fetch = HostFetch(secrets)
        repeat(3) { server.enqueue(slowStream(chunks = 20, intervalMs = 20)) }
        val a = fetch.open(post(base("a")))
        val b = fetch.open(post(base("b")))
        val c = fetch.open(post(base("c")))
        listOf(a, b, c).forEach { assertTrue(it.read()!!.isNotEmpty()) }

        secrets.revoke(k1)
        assertEquals(NetErrorKind.KEY_REVOKED, assertFailsWith<HostFetchException> { withTimeout(5_000) { while (a.read() != null) Unit } }.kind)
        // k2 has the same secret but is another key object; k3 is another key. Both run to the end.
        for (r in listOf(b, c)) {
            withTimeout(10_000) { while (r.read() != null) Unit }
            assertFalse(r.keyRevoked)
        }
        assertEquals(0, fetch.callsWithKey)
        fetch.close()
    }

    @Test
    fun `a key revoked between lookup and send is never sent`() = runBlocking<Unit> {
        val secrets = RevocableSecrets(listOf(base("a") to k1))
        val fetch = HostFetch(secrets)
        // The revocation arrives after credentialFor found the key but before the call is registered.
        secrets.beforeReturn = { key ->
            secrets.signalOnly(key)
            delay(200)
        }
        server.enqueue(MockResponse().setBody("must not be requested"))
        val e = assertFailsWith<HostFetchException> { fetch.open(post(base("a"))) }
        assertEquals(NetErrorKind.KEY_REVOKED, e.kind)
        assertEquals(0, server.requestCount)
        assertEquals(0, fetch.started)
        fetch.close()
    }

    @Test
    fun `after a revocation the next request finds no key and sends nothing`() = runBlocking<Unit> {
        val secrets = RevocableSecrets(listOf(base("a") to k1))
        val fetch = HostFetch(secrets)
        secrets.revoke(k1)
        val e = assertFailsWith<HostFetchException> { fetch.open(post(base("a"))) }
        assertEquals(NetErrorKind.NO_CREDENTIAL, e.kind, "first layer: credentialFor returns null")
        assertEquals(0, server.requestCount)
        fetch.close()
    }

    @Test
    fun `requests without a key and finished calls are not tracked`() = runBlocking<Unit> {
        val secrets = RevocableSecrets(listOf(base("a") to k1))
        val fetch = HostFetch(secrets)
        server.enqueue(MockResponse().setBody("no key needed"))
        server.enqueue(MockResponse().setBody("done"))
        fetch.open(FetchRequest(base("a") + "/health")).use { assertEquals(0, fetch.callsWithKey) }
        val r = fetch.open(post(base("a")))
        assertEquals(1, fetch.callsWithKey)
        while (r.read() != null) Unit
        assertEquals(0, fetch.callsWithKey, "released at the end of the body")
        secrets.revoke(k1) // nothing in flight: no effect, no error
        assertNull(r.read())
        fetch.close()
    }
}
