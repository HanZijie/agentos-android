package org.agentos.runtime.pi

/**
 * The embedded JS engine that runs `pi-agent.js`, reduced to what [PiRuntime] needs.
 *
 * There is one implementation per platform, both on quickjs-kt (docs/spikes/S8.md c, d):
 * - Android: `app/.../agent/QuickJsEngine.kt` (W6, quickjs-kt-android);
 * - desktop: `org.agentos.runtime.pi.desktop.QuickJsJvmEngine` in this module's testFixtures
 *   (quickjs-kt-jvm), used by the JUnit tests here and by the desktop ACP conformance runtime.
 *
 * The engine does no protocol work. [PiRuntime] installs the host primitives with
 * [defineFunction] / [defineAsyncFunction], evaluates the bundle once and then keeps one
 * long-running evaluation (`__pi_main()`, the "pump") that exchanges messages with Kotlin.
 * Do not build per-operation evaluations on top of this: quickjs-kt serialises root
 * evaluations, so a second evaluate waits for the first to finish (S8.md b).
 *
 * Threading: an implementation runs all JavaScript on a single thread of its own (the "JS
 * thread"). Functions from [defineFunction] are called on it synchronously and must not block.
 * Bodies from [defineAsyncFunction] start on it and may suspend or switch dispatchers; their
 * result or exception settles the JS promise.
 *
 * Values crossing the boundary: `null`/`undefined` <-> null, boolean <-> Boolean, number ->
 * Long or Double (either may arrive; use [Number]), string <-> String, Uint8Array <-> ByteArray.
 * quickjs-kt maps ByteArray to Int8Array by default; implementations on quickjs-kt must return
 * `bytes.asUByteArray()` (which it maps to Uint8Array) so response bodies need no copy in JS.
 */
interface JsEngine {
    /** For diagnostics and bytecode cache keys, for example "quickjs-kt-jvm 1.0.15". */
    val description: String

    /** Installs `globalThis[name]` as a synchronous function. Call before the first [evaluate]. */
    fun defineFunction(name: String, body: (args: List<Any?>) -> Any?)

    /** Installs `globalThis[name]` as a function returning a Promise. Call before the first [evaluate]. */
    fun defineAsyncFunction(name: String, body: suspend (args: List<Any?>) -> Any?)

    /**
     * Evaluates [script] as a classic script and suspends until it has settled: when the
     * completion value is a promise, including the async host calls it starts, evaluate returns
     * only after that promise settles. The value returned for such a script is the promise
     * itself (quickjs-kt converts it to a string), not its resolution: pass results back through
     * a binding. Throws [JsException] when the script throws or a promise rejection is left unhandled.
     */
    suspend fun evaluate(script: JsScript): Any?

    /** Compiles [source] to engine bytecode for [JsScript.Bytecode], or returns null if unsupported. */
    suspend fun compile(source: String, fileName: String): ByteArray?

    /** Current heap statistics, or null if unsupported. Safe to call while the pump runs. */
    suspend fun memoryUsage(): JsMemoryUsage?

    /** Interrupts running JavaScript, releases the runtime and its thread. Idempotent. */
    suspend fun close()
}

fun interface JsEngineFactory {
    /** A new, empty runtime. Each call returns an independent engine. */
    fun create(): JsEngine
}

sealed interface JsScript {
    class Source(val code: String, val fileName: String) : JsScript
    class Bytecode(val bytes: ByteArray) : JsScript
}

class JsMemoryUsage(
    /** Bytes in use by the JS heap (QuickJS `memory_used_size`). */
    val usedBytes: Long,
    /** Bytes allocated through the engine's allocator (QuickJS `malloc_size`). */
    val mallocBytes: Long,
    val objectCount: Long,
)

/** A JavaScript exception or an unhandled rejection, with the JS stack when available. */
class JsException(message: String, val jsStack: String? = null, cause: Throwable? = null) : RuntimeException(message, cause)
