package org.agentos.runtime.net

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.agentos.runtime.ports.Credential
import org.agentos.runtime.ports.SecretPort
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * Placeholder key the JS side passes to pi-ai (core/pi-runtime/src/host-bridge.js).
 * [HostFetch] replaces it with the real key; the JS runtime never sees a key (F9).
 */
const val PLACEHOLDER_API_KEY: String = "agentos-host-injected-key"

/** A request from the JS runtime. [sessionId] is the Pi session that issued it, when known. */
class FetchRequest(
    val url: String,
    val method: String = "GET",
    val headers: List<Pair<String, String>> = emptyList(),
    val body: String? = null,
    val sessionId: String? = null,
)

/**
 * Why a request failed before a response head arrived. [retryable] says whether sending the
 * same request again may succeed (the host's retry policy and error mapping use it; see
 * core/contracts/errors.md once it lands).
 */
enum class NetErrorKind(val retryable: Boolean) {
    /** Connect / read / write timed out. */
    TIMEOUT(true),
    /** TCP connection refused or no route. */
    CONNECT(true),
    /** Host name did not resolve (offline, captive network). */
    DNS(true),
    /** Connection reset, unexpected end of stream and similar transport failures. */
    NETWORK(true),
    /** TLS handshake or certificate failure. Retrying the same endpoint will not help. */
    TLS(false),
    /** Cancelled by the host (abort / cancel / shutdown). */
    CANCELED(false),
    /** The JS side asked for a key, but none is configured for this endpoint. Nothing was sent. */
    NO_CREDENTIAL(false),
    /** Refused by policy before sending: invalid URL, cleartext to a non-loopback host, ... */
    REJECTED(false),
    /**
     * The key this request carries was revoked while it was in flight (the user cleared the model
     * source, architecture F9): the call was cut off. Never retried.
     */
    KEY_REVOKED(false),
}

class HostFetchException(
    val kind: NetErrorKind,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    val retryable: Boolean get() = kind.retryable
}

/** HTTP status classification, aligned with the retry rules of the pinned Anthropic / OpenAI SDKs. */
object HttpStatusPolicy {
    fun isRetryable(status: Int): Boolean =
        status == 408 || status == 409 || status == 429 || status in 500..599
}

/**
 * Host-side retry, applied only before a response head is handed to JS: nothing has been
 * streamed yet, so retrying cannot duplicate output. pi-ai and the SDKs run with
 * `maxRetries: 0`; this is the only retry in the model path.
 *
 * - transport errors with [NetErrorKind.retryable] and HTTP 408 / 409 / 429 / 5xx are retried;
 * - `retry-after-ms` / `retry-after` (seconds or HTTP date) are honoured up to [maxRetryAfterMs];
 *   a longer server-requested delay returns the response as is;
 * - no retry starts if it would end after [deadline] (epoch ms, per request; null = none).
 */
class RetryPolicy(
    val maxAttempts: Int = 1,
    val initialBackoffMs: Long = 500,
    val maxBackoffMs: Long = 8_000,
    val maxRetryAfterMs: Long = 60_000,
    val deadline: (FetchRequest) -> Long? = { null },
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    companion object {
        /** One attempt, no retry. */
        val NONE = RetryPolicy(maxAttempts = 1)
    }
}

/** A response whose head has arrived. Read the body with [read]; [cancel] closes the connection. */
class FetchResponse internal constructor(
    private val response: Response,
    val attempts: Int,
    private val call: Call,
    /** The key injected into this request, if any; [HostFetch] cuts the call off when it is revoked. */
    private val keyUse: HostFetch.KeyUse? = null,
) : Closeable {
    val status: Int = response.code
    val statusText: String = response.message
    val url: String = response.request.url.toString()
    val headers: List<Pair<String, String>> = response.headers.map { (n, v) -> n.lowercase() to v }

    private val source = response.body?.source()
    private val cancelled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /** True once the key this request carries has been revoked; [read] then fails with KEY_REVOKED. */
    val keyRevoked: Boolean get() = keyUse?.revoked == true

    /**
     * The next chunk of the body as soon as any bytes are available (at most [maxBytes]),
     * or null at the end. Throws [HostFetchException]: [NetErrorKind.CANCELED] after [cancel],
     * [NetErrorKind.KEY_REVOKED] once the request's key has been revoked (no bytes after that).
     */
    suspend fun read(maxBytes: Long = 16 * 1024): ByteArray? {
        if (keyRevoked) throw revoked().also { close() }
        if (cancelled.get()) throw HostFetchException(NetErrorKind.CANCELED, "Canceled")
        if (closed.get()) return null // already at the end (or closed by the caller)
        val src = source ?: return null.also { close() }
        return withContext(Dispatchers.IO) {
            val buffer = Buffer()
            val n = try {
                src.read(buffer, maxBytes)
            } catch (e: IOException) {
                close()
                if (keyRevoked) throw revoked(e)
                if (cancelled.get()) throw HostFetchException(NetErrorKind.CANCELED, "Canceled", e)
                throw HostFetch.classify(e)
            }
            // A revocation that raced with this read: do not hand out bytes, nor a clean end,
            // after the key was withdrawn (a close-delimited body would otherwise look complete).
            if (keyRevoked) throw revoked().also { close() }
            if (n < 0) {
                close()
                null
            } else {
                buffer.readByteArray()
            }
        }
    }

    private fun revoked(cause: Throwable? = null) = HostFetchException(NetErrorKind.KEY_REVOKED, HostFetch.KEY_REVOKED_MESSAGE, cause)

    /**
     * Aborts the transfer and closes the socket right away (F6: "宿主层关闭对应的 HTTPS 连接");
     * a blocked [read] fails with CANCELED. Only closing the body is not enough: OkHttp would
     * first try to drain an unfinished stream and keep the socket open meanwhile.
     */
    fun cancel() {
        cancelled.set(true)
        runCatching { call.cancel() }
        close()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { response.close() }
            keyUse?.release()
        }
    }
}

/**
 * The network egress for Pi (architecture 4.1: "JS 里不做 I/O"): a streaming fetch backed by
 * OkHttp. It
 * - replaces the placeholder key in `x-api-key` / `Authorization: Bearer` with the key from
 *   [secrets] (HostPort.secrets; [BaseUrlCredentials] for safe endpoint matching) for the
 *   request's endpoint, and refuses to send when none is configured;
 * - drops `accept-encoding` and hop-by-hop headers set by JS (OkHttp handles gzip itself);
 * - never follows redirects (a redirect could carry the key header to another host) and never
 *   retries at the transport level (`retryOnConnectionFailure(false)`);
 * - allows cleartext http only to loopback hosts ([isLoopback]: local model servers, tests, `adb reverse`);
 * - classifies failures as retryable / non-retryable ([NetErrorKind], [HttpStatusPolicy]) and
 *   retries only per [retry] (default: no retry);
 * - cuts off calls whose key is revoked (architecture F9 "清除 = 立即作废", second layer): it
 *   subscribes to [SecretPort.revocations] when constructed, before any request, remembers which
 *   [Credential] object each open call carries, and cancels the matching calls when that object is
 *   revoked (identity, `===`). Their [open] / [FetchResponse.read] fail with
 *   [NetErrorKind.KEY_REVOKED], which is never retried. Calls with other keys are not affected.
 *   [close] ends the subscription.
 *
 * Note: with `retryOnConnectionFailure(false)` OkHttp does not try the host's next IP address
 * within one attempt (for example IPv4 after a dead IPv6 route). The next attempt of [retry]
 * does, because OkHttp's route database skips the failed address. Production callers should
 * therefore use `maxAttempts >= 2`.
 */
class HostFetch(
    private val secrets: SecretPort,
    client: OkHttpClient = defaultClient(),
    private val retry: RetryPolicy = RetryPolicy.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
) : Closeable {
    private val client: OkHttpClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val startedCount = AtomicInteger()
    private val retriedCount = AtomicInteger()

    /** Requests handed to OkHttp, including retries. */
    val started: Int get() = startedCount.get()

    /** Retries performed by [retry]. */
    val retried: Int get() = retriedCount.get()

    // ------------------------------------------------------------------ key revocation (F9)

    /** One open call and the key injected into it. Identity-based: never equal to another use. */
    internal inner class KeyUse(val credential: Credential) {
        @Volatile var revoked: Boolean = false
        @Volatile var call: Call? = null

        fun cancelCall() {
            call?.let { runCatching { it.cancel() } }
        }

        /** The call is over (response closed, or open failed): stop tracking it. */
        fun release() {
            synchronized(keyLock) { inUse.remove(this) }
        }
    }

    private val keyLock = Any()
    private val inUse = HashSet<KeyUse>() // guarded by keyLock
    // Keys revoked recently, compared by identity: covers a revocation that arrives after
    // credentialFor returned the key but before the call was registered.
    private val recentlyRevoked = ArrayDeque<Credential>() // guarded by keyLock
    private val revocationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("host-fetch-revocations"))

    init {
        // A hot flow without replay: subscribe now, synchronously, before the first request.
        revocationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            secrets.revocations
                .catch { /* the first layer (credentialFor returns null) still applies */ }
                .collect { revoke(it) }
        }
    }

    private fun revoke(credential: Credential) {
        val hit = synchronized(keyLock) {
            recentlyRevoked.addLast(credential)
            while (recentlyRevoked.size > RECENTLY_REVOKED) recentlyRevoked.removeFirst()
            inUse.filter { it.credential === credential }.onEach { it.revoked = true }
        }
        hit.forEach { it.cancelCall() }
    }

    private fun track(credential: Credential): KeyUse = synchronized(keyLock) {
        if (recentlyRevoked.any { it === credential }) throw HostFetchException(NetErrorKind.KEY_REVOKED, KEY_REVOKED_MESSAGE)
        KeyUse(credential).also { inUse += it }
    }

    /** Calls currently carrying a key (for tests and diagnostics). */
    val callsWithKey: Int get() = synchronized(keyLock) { inUse.size }

    /** Ends the revocation subscription. Open calls are not touched. */
    override fun close() {
        revocationScope.cancel()
    }

    // ------------------------------------------------------------------ requests

    /**
     * Sends [request] and suspends until the response head arrives. Cancelling the calling
     * coroutine cancels the HTTP call.
     */
    suspend fun open(request: FetchRequest): FetchResponse {
        val url = request.url.toHttpUrlOrNull()
            ?: throw HostFetchException(NetErrorKind.REJECTED, "Invalid URL")
        if (url.scheme != "https" && !isLoopback(url.host)) {
            throw HostFetchException(NetErrorKind.REJECTED, "Cleartext HTTP is only allowed to loopback hosts: ${url.host}")
        }
        val builder = Request.Builder().url(url)
        var contentType: String? = null
        var key: Credential? = null
        for ((rawName, rawValue) in request.headers) {
            val name = rawName.trim()
            when (name.lowercase()) {
                in DROPPED_HEADERS -> continue
                "content-type" -> contentType = rawValue
            }
            val value = when (rawValue.trim()) {
                PLACEHOLDER_API_KEY -> (key ?: lookupKey(url.toString()).also { key = it }).reveal()
                "Bearer $PLACEHOLDER_API_KEY" -> "Bearer " + (key ?: lookupKey(url.toString()).also { key = it }).reveal()
                else -> {
                    if (rawValue.contains(PLACEHOLDER_API_KEY)) {
                        throw HostFetchException(NetErrorKind.REJECTED, "Placeholder key in an unsupported form in header $name")
                    }
                    rawValue
                }
            }
            builder.addHeader(name, value)
        }
        val method = request.method.uppercase()
        val body = request.body?.toRequestBody(contentType?.toMediaTypeOrNull())
            ?: if (method in BODY_METHODS) ByteArray(0).toRequestBody(contentType?.toMediaTypeOrNull()) else null
        builder.method(method, body)
        val okRequest = builder.build()

        val use = key?.let { track(it) }
        try {
            var attempt = 0
            while (true) {
                attempt++
                if (use?.revoked == true) throw revokedError()
                startedCount.incrementAndGet()
                val call = client.newCall(okRequest)
                // Publish the call before checking the flag; revoke() sets the flag before reading
                // the call, so one of the two sides always sees the other.
                use?.call = call
                if (use?.revoked == true) throw revokedError()
                val response = try {
                    call.await()
                } catch (e: IOException) {
                    if (use?.revoked == true) throw revokedError(e)
                    val error = classify(e)
                    val wait = backoff(attempt)
                    if (error.retryable && canRetry(request, attempt, wait)) {
                        retriedCount.incrementAndGet()
                        sleep(wait)
                        continue
                    }
                    throw error
                }
                if (use?.revoked == true) {
                    response.close()
                    throw revokedError()
                }
                if (HttpStatusPolicy.isRetryable(response.code) && attempt < retry.maxAttempts) {
                    val serverWait = retryAfterMs(response)
                    val wait = serverWait ?: backoff(attempt)
                    if ((serverWait == null || serverWait <= retry.maxRetryAfterMs) && canRetry(request, attempt, wait)) {
                        response.close()
                        retriedCount.incrementAndGet()
                        sleep(wait)
                        continue
                    }
                }
                return FetchResponse(response, attempt, call, use)
            }
        } catch (e: Throwable) {
            use?.release()
            throw e
        }
    }

    private fun revokedError(cause: Throwable? = null) = HostFetchException(NetErrorKind.KEY_REVOKED, KEY_REVOKED_MESSAGE, cause)

    private suspend fun lookupKey(url: String): Credential {
        val credential = secrets.credentialFor(url)
        if (credential != null) return credential
        val host = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: "this endpoint"
        throw HostFetchException(NetErrorKind.NO_CREDENTIAL, "No API key configured for $host")
    }

    private fun canRetry(request: FetchRequest, attempt: Int, waitMs: Long): Boolean {
        if (attempt >= retry.maxAttempts) return false
        val deadline = retry.deadline(request) ?: return true
        return clock() + waitMs < deadline
    }

    private fun backoff(attempt: Int): Long {
        val exp = retry.initialBackoffMs * (1L shl (attempt - 1).coerceAtMost(20))
        val capped = exp.coerceAtMost(retry.maxBackoffMs)
        return (capped * (0.75 + random.nextDouble() * 0.25)).toLong()
    }

    private fun retryAfterMs(response: Response): Long? {
        response.header("retry-after-ms")?.trim()?.toDoubleOrNull()?.let { return it.toLong().coerceAtLeast(0) }
        val value = response.header("retry-after")?.trim() ?: return null
        value.toDoubleOrNull()?.let { return (it * 1000).toLong().coerceAtLeast(0) }
        return runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - clock()
        }.getOrNull()?.coerceAtLeast(0)
    }

    companion object {
        private val DROPPED_HEADERS = setOf(
            "accept-encoding", "content-length", "host", "connection", "keep-alive",
            "transfer-encoding", "te", "trailer", "upgrade", "proxy-authorization", "proxy-connection",
        )
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        private const val RECENTLY_REVOKED = 32

        /** Message of [NetErrorKind.KEY_REVOKED] failures. Never contains the key. */
        const val KEY_REVOKED_MESSAGE: String = "Model key revoked"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // Between bytes. Reasoning models can pause for a long time mid-stream; the task
            // deadline, not the socket, bounds a turn.
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        /**
         * The hosts cleartext http may go to (architecture F9: local model servers on the device,
         * tests, `adb reverse`): exactly `127.0.0.1`, `localhost` and `::1` (OkHttp canonicalizes
         * IPv6 hosts without brackets). Kept in sync with the app's network security config
         * (app/src/main/res/xml/network_security_config.xml), which the platform enforces as well.
         * Deliberately not "starts with 127.": that would also match names like 127.example.com.
         */
        fun isLoopback(host: String): Boolean = host in LOOPBACK_HOSTS

        val LOOPBACK_HOSTS: Set<String> = setOf("127.0.0.1", "localhost", "::1")

        /** Maps a transport failure to a [HostFetchException] with its [NetErrorKind]. */
        fun classify(e: IOException): HostFetchException {
            if (e is HostFetchException) return e
            val kind = when {
                e.message == "Canceled" -> NetErrorKind.CANCELED
                e is UnknownHostException -> NetErrorKind.DNS
                e is ConnectException || e is NoRouteToHostException -> NetErrorKind.CONNECT
                e is SocketTimeoutException -> NetErrorKind.TIMEOUT
                e is InterruptedIOException && e.message?.contains("timeout", ignoreCase = true) == true -> NetErrorKind.TIMEOUT
                e is SSLHandshakeException || e is SSLPeerUnverifiedException -> NetErrorKind.TLS
                else -> NetErrorKind.NETWORK
            }
            return HostFetchException(kind, "${kind.name.lowercase()}: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) {
                cont.resume(response) { _, value, _ -> value.close() }
            } else {
                response.close()
            }
        }
    })
}
