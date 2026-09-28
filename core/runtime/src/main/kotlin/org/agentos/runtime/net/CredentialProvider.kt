package org.agentos.runtime.net

import java.net.URI
import java.util.Locale

/**
 * A model API key. The value is only readable inside core:runtime (by [HostFetch], when it
 * injects the key into a request); [toString] never shows it, so a key that ends up in a
 * log line, an exception message or a data-class dump stays hidden (F9: keys never appear
 * in events, snapshots, logs or diagnostics).
 */
class ApiKey(value: String) {
    internal val secret: String = value

    init {
        require(value.isNotBlank()) { "API key is blank" }
        require(value.none { it == '\r' || it == '\n' }) { "API key contains a line break" }
    }

    override fun toString(): String = "ApiKey(****)"
    override fun equals(other: Any?): Boolean = other is ApiKey && other.secret == secret
    override fun hashCode(): Int = secret.hashCode()
}

/**
 * Where model keys come from. Implemented by the :agent process on top of Android Keystore
 * (KeystoreSecrets, lane C / W6); tests use [BaseUrlCredentials] with fixed keys.
 *
 * [HostFetch] calls this once per model request, on an I/O thread, only when the JS side sent
 * the placeholder key. Return null when no key is configured for [target]: the request is then
 * not sent at all. Implementations should match by parsed endpoint, not by string prefix;
 * [BaseUrlCredentials] does that and is the recommended building block.
 */
fun interface CredentialProvider {
    fun apiKeyFor(target: URI): ApiKey?
}

/**
 * Keys bound to model base URLs (for example `https://api.minimax.io/anthropic`).
 *
 * A key is used for a request only when scheme, host and port are identical and the request
 * path starts with the base path on a segment boundary. So `https://api.minimax.io.evil.com/…`,
 * `http://api.minimax.io/…` and `https://api.minimax.io/anthropicx/…` never receive the key for
 * `https://api.minimax.io/anthropic`. When several entries match, the longest base path wins.
 *
 * [key] is evaluated per request, so a key changed in settings takes effect on the next request
 * without touching running sessions (F9 hot reload).
 */
class BaseUrlCredentials(entries: List<Entry>) : CredentialProvider {

    class Entry(baseUrl: String, val key: () -> ApiKey?) {
        internal val base: Endpoint = Endpoint.parse(baseUrl)
            ?: throw IllegalArgumentException("Not an absolute http(s) URL: $baseUrl")

        constructor(baseUrl: String, key: ApiKey) : this(baseUrl, { key })
    }

    private val entries = entries.toList()

    override fun apiKeyFor(target: URI): ApiKey? {
        val t = Endpoint.of(target) ?: return null
        return entries
            .filter { it.base.contains(t) }
            .maxByOrNull { it.base.segments.size }
            ?.key?.invoke()
    }
}

/** Scheme, host, port and path segments of an http(s) URL, normalised for exact comparison. */
internal class Endpoint(val scheme: String, val host: String, val port: Int, val segments: List<String>) {

    /** True when [other] is this endpoint or below it. */
    fun contains(other: Endpoint): Boolean =
        scheme == other.scheme && host == other.host && port == other.port &&
            other.segments.size >= segments.size && other.segments.subList(0, segments.size) == segments

    companion object {
        fun parse(url: String): Endpoint? = runCatching { of(URI(url.trim())) }.getOrNull()

        fun of(uri: URI): Endpoint? {
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "https" && scheme != "http") return null
            if (uri.rawUserInfo != null) return null
            val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
            if (host.isEmpty()) return null
            val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
            val path = uri.normalize().rawPath.orEmpty()
            if (path.split('/').any { it == ".." }) return null
            return Endpoint(scheme, host, port, path.split('/').filter { it.isNotEmpty() })
        }
    }
}
