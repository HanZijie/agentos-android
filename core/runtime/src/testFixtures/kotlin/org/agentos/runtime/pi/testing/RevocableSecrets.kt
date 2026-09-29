package org.agentos.runtime.pi.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.agentos.runtime.net.BaseUrlCredentials
import org.agentos.runtime.ports.Credential
import org.agentos.runtime.ports.SecretPort
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [SecretPort] for tests of key revocation (architecture F9, "清除 = 立即作废"): keys bound to base
 * URLs ([BaseUrlCredentials]) plus the [revocations] signal that KeystoreSecrets sends in production.
 * Identity semantics as in production: [revoke] withdraws that very [Credential] object; another
 * object with the same secret is a different key.
 */
class RevocableSecrets(entries: List<Pair<String, Credential>>) : SecretPort {

    private val lookup = BaseUrlCredentials(entries.map { (base, key) -> BaseUrlCredentials.Entry(base, key) })
    private val withdrawn = CopyOnWriteArrayList<Credential>()
    private val signal = MutableSharedFlow<Credential>(extraBufferCapacity = 64)

    /** Runs with the key [credentialFor] is about to return; lets a test stage a revocation race. */
    @Volatile var beforeReturn: suspend (Credential) -> Unit = {}

    override suspend fun credentialFor(url: String): Credential? {
        val key = lookup.credentialFor(url) ?: return null
        if (withdrawn.any { it === key }) return null
        beforeReturn(key)
        return key
    }

    override val revocations: Flow<Credential> = signal.asSharedFlow()

    /** Clears [key] like KeystoreSecrets does: no more lookups (first layer), then the signal (second layer). */
    suspend fun revoke(key: Credential) {
        withdrawn += key
        signal.emit(key)
    }

    /** Only the signal, without withdrawing the key from lookups. */
    suspend fun signalOnly(key: Credential) = signal.emit(key)

    /** Active subscribers of [revocations] (HostFetch subscribes when constructed). */
    val subscribers: Int get() = signal.subscriptionCount.value
}
