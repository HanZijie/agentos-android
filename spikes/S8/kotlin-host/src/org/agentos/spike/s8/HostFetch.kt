package org.agentos.spike.s8

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Placeholder key the JS side passes to pi-ai; must match host-bridge.js. */
const val PLACEHOLDER_API_KEY = "agentos-host-injected-key"

/** Where model keys come from. In AgentOS this is implemented by KeystoreSecrets (lane C). */
fun interface CredentialProvider {
    /** Returns the key for a request to [url], or null when no credential is configured for it. */
    fun apiKeyFor(url: HttpUrl): String?
}

/** S8: keys matched by base-URL prefix; keys come from env / instrumentation args, never from files. */
class PrefixCredentials(private val entries: List<Pair<String, String>>) : CredentialProvider {
    override fun apiKeyFor(url: HttpUrl): String? {
        val s = url.toString()
        return entries.firstOrNull { (prefix, _) -> s.startsWith(prefix) }?.second
    }
}

/**
 * Streaming fetch for the JS runtime, backed by OkHttp.
 *
 * - The JS side sends the placeholder key; it is replaced here, per endpoint.
 * - No transport retries (retryOnConnectionFailure = false): retry/deadline policy
 *   belongs to the host, and pi-ai / the SDKs are configured with maxRetries = 0.
 * - [abort] cancels the OkHttp call, which closes the socket.
 */
class HostFetch(
    private val credentials: CredentialProvider,
    client: OkHttpClient? = null,
) {
    val client: OkHttpClient = client ?: OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private class Active(val call: Call) {
        @Volatile var response: Response? = null
        @Volatile var source: BufferedSource? = null
    }

    private val active = ConcurrentHashMap<Long, Active>()
    val started = AtomicInteger()
    val aborted = AtomicInteger()
    val inflight: Int get() = active.size

    /** Observer for everything that flows back into JS (used by the key-isolation check). */
    @Volatile var ingressAudit: ((ByteArray) -> Unit)? = null

    suspend fun start(reqId: Long, requestJson: String): String {
        val req = JSONObject(requestJson)
        val url = req.getString("url").toHttpUrl()
        val builder = Request.Builder().url(url)
        val headers = req.getJSONArray("headers")
        var contentType = "application/json"
        for (i in 0 until headers.length()) {
            val pair = headers.getJSONArray(i)
            val name = pair.getString(0)
            var value = pair.getString(1)
            when (name.lowercase()) {
                // OkHttp owns these; letting JS set accept-encoding would disable transparent gzip.
                "content-length", "host", "connection", "accept-encoding", "transfer-encoding" -> continue
                "content-type" -> contentType = value
            }
            if (value == PLACEHOLDER_API_KEY || value == "Bearer $PLACEHOLDER_API_KEY") {
                val key = credentials.apiKeyFor(url)
                    ?: throw IOException("No credential configured for ${url.scheme}://${url.host}")
                value = if (value.startsWith("Bearer ")) "Bearer $key" else key
            }
            builder.addHeader(name, value)
        }
        val method = req.optString("method", "GET")
        val body = if (req.isNull("body")) null else req.getString("body").toRequestBody(contentType.toMediaTypeOrNull())
        builder.method(method, body ?: if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody() else null)

        val call = client.newCall(builder.build())
        val entry = Active(call)
        active[reqId] = entry
        started.incrementAndGet()
        val response = try {
            call.await()
        } catch (e: Throwable) {
            active.remove(reqId)
            throw e
        }
        entry.response = response
        entry.source = response.body?.source()
        if (active[reqId] !== entry) { response.close(); throw IOException("Canceled") }
        val head = JSONObject()
            .put("status", response.code)
            .put("statusText", response.message)
            .put("url", response.request.url.toString())
            .put("headers", JSONArray().apply {
                for ((n, v) in response.headers) put(JSONArray().put(n.lowercase()).put(v))
            })
            .toString()
        ingressAudit?.invoke(head.toByteArray())
        return head
    }

    /** Next chunk of the body as soon as any bytes are available; null at EOF. */
    suspend fun read(reqId: Long): ByteArray? = withContext(Dispatchers.IO) {
        val entry = active[reqId] ?: return@withContext null
        val source = entry.source ?: run { finish(reqId); return@withContext null }
        val buffer = Buffer()
        val n = try {
            source.read(buffer, 16 * 1024)
        } catch (e: IOException) {
            finish(reqId)
            throw e
        }
        if (n < 0) {
            finish(reqId)
            null
        } else {
            val bytes = buffer.readByteArray()
            ingressAudit?.invoke(bytes)
            bytes
        }
    }

    fun abort(reqId: Long) {
        val entry = active.remove(reqId) ?: return
        aborted.incrementAndGet()
        entry.call.cancel()
        runCatching { entry.response?.close() }
    }

    fun abortAll() {
        // Snapshot the live concurrent-key view via toArray(); keys.toList() can read size=1 and
        // then observe an empty iterator when the sole request finishes between those operations.
        for (id in ArrayList(active.keys)) abort(id)
    }

    private fun finish(reqId: Long) {
        active.remove(reqId)?.let { runCatching { it.response?.close() } }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
