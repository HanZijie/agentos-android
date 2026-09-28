package org.agentos.runtime.pi.desktop

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.agentos.runtime.pi.JsEngine
import org.agentos.runtime.pi.JsEngineFactory
import org.agentos.runtime.pi.JsException
import org.agentos.runtime.pi.JsMemoryUsage
import org.agentos.runtime.pi.JsScript
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [JsEngine] on the desktop JVM: quickjs-kt-jvm (the same QuickJS binding as Android, with
 * native libraries for macOS / Linux / Windows in the jar; docs/spikes/S8.md c).
 *
 * Every engine owns one thread; it is both the JS thread and quickjs-kt's job dispatcher.
 */
class QuickJsJvmEngine(threadName: String = "pi-js-${counter.incrementAndGet()}") : JsEngine {

    override val description: String = "quickjs-kt-jvm $QUICKJS_KT_VERSION"

    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        // QuickJS recursion needs more than the default 512 KB on some platforms.
        Thread(null, r, threadName, 4L * 1024 * 1024).apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // Created, and bindings defined, on the JS thread itself (as in S8).
    private val quickJs: QuickJs = runBlocking(dispatcher) { QuickJs.create(dispatcher) }
    private val closed = AtomicBoolean(false)

    override fun defineFunction(name: String, body: (args: List<Any?>) -> Any?) {
        runBlocking(dispatcher) { quickJs.function<Any?>(name) { args -> toJs(body(fromJs(args))) } }
    }

    override fun defineAsyncFunction(name: String, body: suspend (args: List<Any?>) -> Any?) {
        runBlocking(dispatcher) { quickJs.asyncFunction<Any?>(name) { args -> toJs(body(fromJs(args))) } }
    }

    // quickjs-kt maps ByteArray to Int8Array and UByteArray to Uint8Array; the JsEngine contract
    // is ByteArray <-> Uint8Array, so reinterpret (no copy) on the way in and out.
    @OptIn(ExperimentalUnsignedTypes::class)
    private fun toJs(value: Any?): Any? = if (value is ByteArray) value.asUByteArray() else value

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun fromJs(args: Array<Any?>): List<Any?> = args.map { if (it is UByteArray) it.asByteArray() else it }

    override suspend fun evaluate(script: JsScript): Any? = withContext(dispatcher) {
        try {
            when (script) {
                is JsScript.Source -> quickJs.evaluate<Any?>(script.code, script.fileName, false)
                is JsScript.Bytecode -> quickJs.evaluate<Any?>(script.bytes)
            }
        } catch (e: QuickJsException) {
            throw JsException(e.message ?: "JavaScript error", e.message, e)
        }
    }

    override suspend fun compile(source: String, fileName: String): ByteArray = withContext(dispatcher) {
        quickJs.compile(source, fileName, false)
    }

    override suspend fun memoryUsage(): JsMemoryUsage? {
        if (closed.get()) return null
        val m = quickJs.memoryUsage
        return JsMemoryUsage(usedBytes = m.memoryUsedSize, mallocBytes = m.mallocSize, objectCount = m.objCount)
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        // QuickJs.close() interrupts a running evaluation first, then frees the runtime.
        withContext(NonCancellable) { runCatching { quickJs.close() } }
        dispatcher.close()
    }

    companion object {
        /** Keep in sync with gradle/libs.versions.toml; used only in [description] (bytecode cache key). */
        const val QUICKJS_KT_VERSION: String = "1.0.15"

        private val counter = AtomicInteger()

        val factory: JsEngineFactory = JsEngineFactory { QuickJsJvmEngine() }
    }
}
