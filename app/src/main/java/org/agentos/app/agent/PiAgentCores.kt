package org.agentos.app.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.agentos.runtime.net.RetryPolicy
import org.agentos.runtime.pi.BytecodeCache
import org.agentos.runtime.pi.FileBytecodeCache
import org.agentos.runtime.pi.ModelCatalog
import org.agentos.runtime.pi.PiAdapter
import org.agentos.runtime.pi.PiBundle
import org.agentos.runtime.ports.AgentCoreFactory
import org.agentos.runtime.ports.HostPort
import java.io.File

/**
 * The production [AgentCoreFactory] for the `:agent` process (W6): `PiAdapter` running
 * `assets/pi-agent.js` on [QuickJsEngine].
 *
 * Wiring (AgentProcess, lane C):
 * `AgentRuntimes.create(RuntimeDependencies(hostPort, PiAgentCores.create(context, hostPort)))`.
 * The host creates one core at start and a new one after a pump failure (CoreSessions).
 *
 * - Bundle: read from assets on Dispatchers.IO whenever a core starts; not kept in memory.
 * - Keys: `hostPort.secrets`, matched per endpoint (use `BaseUrlCredentials` in KeystoreSecrets).
 *   Keys stay in Kotlin: HostFetch injects them into requests, JS only sees a placeholder.
 * - Bytecode cache: `codeCacheDir/pi` ([FileBytecodeCache]), keyed by the bundle's SHA-256, the
 *   engine (quickjs-kt version and ABI) and the protocol version. The first start after an
 *   install or upgrade evaluates the source and compiles the cache in the background; later
 *   starts load the bytecode.
 * - Threads: each core owns one `pi-js-N` thread (see [QuickJsEngine]); `start()` does not block
 *   its caller, so it may run on any dispatcher.
 * - Retries: [RetryPolicy.NONE] by default (M1): a failed model call ends the turn with a
 *   classified error (errors.md) and the host decides. A production policy should allow at
 *   least 2 attempts before any byte reaches JS, see the W3 report.
 */
object PiAgentCores {

    const val BUNDLE_ASSET: String = "pi-agent.js"
    const val CATALOG_ASSET: String = "model-catalog.json"

    fun create(context: Context, hostPort: HostPort, retry: RetryPolicy = RetryPolicy.NONE): AgentCoreFactory {
        val app = context.applicationContext
        return PiAdapter.factory(
            bundle = { loadBundle(app) },
            engineFactory = QuickJsEngine.factory,
            secrets = hostPort.secrets,
            bytecodeCache = bytecodeCache(app),
            retry = retry,
            log = hostPort.log,
        )
    }

    suspend fun loadBundle(context: Context): PiBundle = withContext(Dispatchers.IO) {
        PiBundle(context.assets.open(BUNDLE_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }, BUNDLE_ASSET)
    }

    fun bytecodeCache(context: Context): BytecodeCache = FileBytecodeCache(File(context.codeCacheDir, "pi"))

    /** Vendor presets (`model-catalog.json`, schemaVersion 1, core/pi-runtime/README.md). */
    fun loadCatalog(context: Context): ModelCatalog =
        context.assets.open(CATALOG_ASSET).use { ModelCatalog.parse(it.readBytes().toString(Charsets.UTF_8)) }
}
