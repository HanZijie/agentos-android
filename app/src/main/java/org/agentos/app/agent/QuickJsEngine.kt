package org.agentos.app.agent

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.agentos.runtime.pi.JsEngine
import org.agentos.runtime.pi.JsEngineFactory
import org.agentos.runtime.pi.JsException
import org.agentos.runtime.pi.JsMemoryUsage
import org.agentos.runtime.pi.JsScript
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * [JsEngine] on Android (W6): quickjs-kt-android runs `assets/pi-agent.js` in the `:agent` process
 * (docs/spikes/S8.md b–d). The desktop counterpart, used by the JVM tests, is
 * `org.agentos.runtime.pi.desktop.QuickJsJvmEngine`; both behave the same apart from the
 * creation timing below.
 *
 * Threading:
 * - Every engine owns one thread, `pi-js-N` with a [THREAD_STACK_BYTES] stack. It is the JS
 *   thread and quickjs-kt's job dispatcher. `PiAdapter` keeps one engine for all sessions, plus a
 *   short-lived one after a cold start from source that compiles the bytecode cache.
 * - Nothing here blocks the caller: [JsEngineFactory.create], [defineFunction] and
 *   [defineAsyncFunction] only record. The QuickJS runtime (and, the first time in the process,
 *   `libquickjs.so`) is created on the JS thread by the first [evaluate] or [compile].
 *   `PiAdapter.start()` / `openSession()` may therefore be called from any dispatcher,
 *   including Main.
 * - Sync bindings run on the JS thread and must not block. Async bindings start there and may
 *   switch dispatchers (HostFetch waits on OkHttp's threads).
 * - quickjs-kt's synchronous lock spins with `Thread.yield()`, so [memoryUsage] runs on the JS
 *   thread instead of contending from outside.
 *
 * Values: ByteArray <-> Uint8Array through `asUByteArray()` / `asByteArray()` (no copy), see [JsEngine].
 */
class QuickJsEngine(threadName: String = "pi-js-${counter.incrementAndGet()}") : JsEngine {

    /** Part of the bytecode cache key: bytecode is only valid for the same QuickJS build and ABI. */
    override val description: String = "quickjs-kt-android $QUICKJS_KT_VERSION ${System.getProperty("os.arch")}"

    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, threadName, THREAD_STACK_BYTES).apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val lock = Any()

    // All guarded by [lock]. [quickJs] is created on the JS thread, then only read.
    private val pending = ArrayList<(QuickJs) -> Unit>()
    private var quickJs: QuickJs? = null
    private var closed = false

    override fun defineFunction(name: String, body: (args: List<Any?>) -> Any?) = define { js ->
        js.function<Any?>(name) { args -> toJs(body(fromJs(args))) }
    }

    override fun defineAsyncFunction(name: String, body: suspend (args: List<Any?>) -> Any?) = define { js ->
        js.asyncFunction<Any?>(name) { args -> toJs(body(fromJs(args))) }
    }

    private fun define(binding: (QuickJs) -> Unit) = synchronized(lock) {
        check(!closed) { "QuickJsEngine is closed" }
        check(quickJs == null) { "define bindings before the first evaluate or compile" }
        pending += binding
    }

    /** On the JS thread: the runtime, created with the recorded bindings on first use. */
    private fun runtime(): QuickJs = synchronized(lock) {
        if (closed) throw JsException("QuickJsEngine is closed")
        quickJs ?: QuickJs.create(dispatcher).also { js ->
            pending.forEach { it(js) }
            pending.clear()
            quickJs = js
        }
    }

    /** Before switching to the JS thread: a closed dispatcher would turn this into a cancellation. */
    private fun ensureOpen() = synchronized(lock) {
        if (closed) throw JsException("QuickJsEngine is closed")
    }

    override suspend fun evaluate(script: JsScript): Any? {
        ensureOpen()
        return withContext(dispatcher) {
            val js = runtime()
            val result = try {
                when (script) {
                    is JsScript.Source -> js.evaluate<Any?>(script.code, script.fileName, false)
                    is JsScript.Bytecode -> js.evaluate<Any?>(script.bytes)
                }
            } catch (e: QuickJsException) {
                throw JsException(e.message ?: "JavaScript error", e.message, e)
            }
            fromJs(result)
        }
    }

    override suspend fun compile(source: String, fileName: String): ByteArray {
        ensureOpen()
        return withContext(dispatcher) {
            try {
                runtime().compile(source, fileName, false)
            } catch (e: QuickJsException) {
                throw JsException(e.message ?: "JavaScript compile error", e.message, e)
            }
        }
    }

    override suspend fun memoryUsage(): JsMemoryUsage? {
        val js = synchronized(lock) { if (closed) null else quickJs } ?: return null
        return withContext(dispatcher) {
            runCatching { js.memoryUsage }.getOrNull()?.let {
                JsMemoryUsage(usedBytes = it.memoryUsedSize, mallocBytes = it.mallocSize, objectCount = it.objCount)
            }
        }
    }

    override suspend fun close() {
        val js = synchronized(lock) {
            if (closed) return
            closed = true
            pending.clear()
            quickJs
        }
        // Not on the JS thread: QuickJs.close() interrupts a running evaluation first (even a busy
        // loop holding the JS thread), then frees the runtime under its own lock.
        withContext(NonCancellable) { js?.let { runCatching { it.close() } } }
        dispatcher.close()
    }

    // quickjs-kt maps ByteArray to Int8Array and UByteArray to Uint8Array; the JsEngine contract is
    // ByteArray <-> Uint8Array, so reinterpret (no copy) in both directions.
    @OptIn(ExperimentalUnsignedTypes::class)
    private fun toJs(value: Any?): Any? = if (value is ByteArray) value.asUByteArray() else value

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun fromJs(value: Any?): Any? = if (value is UByteArray) value.asByteArray() else value

    private fun fromJs(args: Array<Any?>): List<Any?> = args.map(::fromJs)

    companion object {
        /**
         * Keep in sync with `quickjs-kt` in gradle/libs.versions.toml (checked by
         * QuickJsEngineVersionTest). Part of [description], i.e. of the bytecode cache key.
         */
        const val QUICKJS_KT_VERSION: String = "1.0.15"

        /** QuickJS recursion needs more than the default thread stack (as in S8). */
        const val THREAD_STACK_BYTES: Long = 4L * 1024 * 1024

        private val counter = AtomicInteger()

        val factory: JsEngineFactory = JsEngineFactory { QuickJsEngine() }
    }
}
