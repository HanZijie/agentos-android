package org.agentos.runtime.net

import org.agentos.runtime.ports.Credential
import org.agentos.runtime.ports.SecretPort
import java.net.URI
import java.util.Locale

/**
 * A [SecretPort] that binds keys to model base URLs (for example `https://api.minimax.io/anthropic`)
 * and matches requests by parsed endpoint, not by string prefix.
 *
 * A key is used for a request only when scheme, host and port are identical and the request
 * path starts with the base path on a segment boundary. So `https://api.minimax.io.evil.com/…`,
 * `http://api.minimax.io/…` and `https://api.minimax.io/anthropicx/…` never receive the key for
 * `https://api.minimax.io/anthropic` (a plain `url.startsWith(baseUrl)` would send it to the first
 * of these). When several entries match, the longest base path wins.
 *
 * [Entry.key] is evaluated per request, so a key changed in settings takes effect on the next
 * request without touching running sessions (F9 hot reload). KeystoreSecrets (W6) can build its
 * `SecretPort` from this class, supplying decrypted keys through [Entry.key].
 */
class BaseUrlCredentials(entries: List<Entry>) : SecretPort {

    class Entry(baseUrl: String, val key: suspend () -> Credential?) {
        internal val base: Endpoint = Endpoint.parse(baseUrl)
            ?: throw IllegalArgumentException("Not an absolute http(s) URL")

        constructor(baseUrl: String, key: Credential) : this(baseUrl, { key })
    }

    private val entries = entries.toList()

    override suspend fun credentialFor(url: String): Credential? {
        val target = Endpoint.parse(url) ?: return null
        return entries
            .filter { it.base.contains(target) }
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
            // IPv6 literals without brackets, as OkHttp's HttpUrl.host has them (HostFetch.isLoopback).
            val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')?.removeSurrounding("[", "]") ?: return null
            if (host.isEmpty()) return null
            val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
            val path = uri.normalize().rawPath.orEmpty()
            if (path.split('/').any { it == ".." }) return null
            return Endpoint(scheme, host, port, path.split('/').filter { it.isNotEmpty() })
        }
    }
}
