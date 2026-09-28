package org.agentos.app.agent

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.agentos.runtime.pi.JsException
import org.agentos.runtime.pi.JsScript
import org.junit.After
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Android [QuickJsEngine] against the JsEngine contract (core/runtime/.../pi/JsEngine.kt). */
class QuickJsEngineTest {

    private val engines = CopyOnWriteArrayList<QuickJsEngine>()

    private fun engine(): QuickJsEngine = QuickJsEngine().also { engines += it }

    @After
    fun closeAll() = runBlocking {
        engines.forEach { it.close() }
    }

    @Test
    fun describesTheEngineForTheBytecodeCacheKey() {
        assertEquals(Device.AGENT_PROCESS, Application.getProcessName())
        val d = engine().description
        assertTrue(d.startsWith("quickjs-kt-android ${QuickJsEngine.QUICKJS_KT_VERSION} "), d)
        assertTrue(d.endsWith(System.getProperty("os.arch")!!), d)
    }

    @Test
    fun creatingAndDefiningDoNotBlockTheCaller() = runBlocking<Unit> {
        // On the main thread: nothing may touch QuickJS there (no runBlocking on Main).
        lateinit var js: QuickJsEngine
        val threads = CopyOnWriteArrayList<String>()
        var ms = 0.0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val t0 = System.nanoTime()
            js = engine()
            js.defineFunction("__where") {
                threads += Thread.currentThread().name
                null
            }
            ms = (System.nanoTime() - t0) / 1e6
        }
        assertTrue(ms < 50, "create + define on Main took ${ms}ms")
        assertNull(js.memoryUsage(), "no runtime before the first evaluate")
        js.evaluate(JsScript.Source("__where(); 1", "where.js"))
        assertTrue(threads.single().startsWith("pi-js-"), "sync bindings run on the JS thread: $threads")
        assertFailsWith<IllegalStateException> { js.defineFunction("__late") { null } }
    }

    @Test
    fun bytesCrossAsUint8ArrayBothWays() = runBlocking<Unit> {
        val js = engine()
        val received = CopyOnWriteArrayList<Any?>()
        js.defineAsyncFunction("__bytes") { byteArrayOf(1, 2, -1) }
        js.defineFunction("__take") { args ->
            received.addAll(args)
            null
        }
        // An async script's completion value is the promise itself; evaluate returns once it has
        // settled, so results come back through a binding.
        js.evaluate(
            JsScript.Source(
                """
                (async () => {
                  const b = await __bytes();
                  if (!(b instanceof Uint8Array)) throw new Error("not a Uint8Array: " + Object.prototype.toString.call(b));
                  __take(new Uint8Array([b[0] + b[1], b[2]]), b.length);
                })()
                """.trimIndent(),
                "bytes.js",
            ),
        )
        assertEquals(2, received.size, "settled before evaluate returned: $received")
        assertContentEquals(byteArrayOf(3, -1), received[0] as ByteArray)
        assertEquals(3, (received[1] as Number).toInt())
    }

    @Test
    fun bytecodeFromOneEngineRunsInAnother() = runBlocking<Unit> {
        val bytes = assertNotNull(engine().compile("globalThis.answer = 6 * 7; answer", "compiled.js"))
        assertEquals(42, (engine().evaluate(JsScript.Bytecode(bytes)) as Number).toInt())
    }

    @Test
    fun errorsBecomeJsException() = runBlocking<Unit> {
        val thrown = assertFailsWith<JsException> { engine().evaluate(JsScript.Source("throw new TypeError('boom')", "throw.js")) }
        assertTrue("boom" in thrown.message.orEmpty(), thrown.message)
        assertFailsWith<JsException> { engine().evaluate(JsScript.Source("Promise.reject(new Error('nobody handles me')); 1", "reject.js")) }
        assertFailsWith<JsException> { engine().compile("function (", "syntax.js") }
    }

    @Test
    fun deepRecursionIsAJsErrorNotACrash() = runBlocking<Unit> {
        val fn = "function f(n) { return n === 0 ? 0 : 1 + f(n - 1) } "
        assertEquals(200, (engine().evaluate(JsScript.Source("${fn}f(200)", "ok.js")) as Number).toInt())
        val e = assertFailsWith<JsException> { engine().evaluate(JsScript.Source("${fn}f(1e7)", "deep.js")) }
        assertTrue(Regex("stack", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty()), e.message)
    }

    @Test
    fun memoryUsageWhileJavaScriptWaitsOnTheHost() = runBlocking<Unit> {
        val js = engine()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        js.defineAsyncFunction("__wait") {
            entered.complete(Unit)
            gate.await()
            "done"
        }
        val result = CompletableDeferred<String>()
        js.defineFunction("__result") { args ->
            result.complete(args[0] as String)
            null
        }
        val run = async(Dispatchers.Default) {
            js.evaluate(JsScript.Source("(async () => { const big = new Array(10000).fill('x'); __result((await __wait()) + big.length) })()", "wait.js"))
        }
        withTimeout(10_000) { entered.await() }
        val m = assertNotNull(withTimeout(2_000) { js.memoryUsage() })
        assertTrue(m.usedBytes > 0 && m.objectCount > 0, "used=${m.usedBytes} objects=${m.objectCount}")
        assertFalse(run.isCompleted, "evaluate waits for the pending host call")
        gate.complete(Unit)
        withTimeout(10_000) { run.await() }
        assertTrue(result.isCompleted, "settled before evaluate returned")
        assertEquals("done10000", result.await())
    }

    @Test
    fun closeInterruptsBusyJavaScript() = runBlocking<Unit> {
        val js = engine()
        val started = CompletableDeferred<Unit>()
        js.defineFunction("__started") {
            started.complete(Unit)
            null
        }
        val run = async(Dispatchers.Default) { runCatching { js.evaluate(JsScript.Source("__started(); while (true) {}", "busy.js")) } }
        withTimeout(10_000) { started.await() }
        val t0 = System.nanoTime()
        withTimeout(5_000) { js.close() }
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue(withTimeout(5_000) { run.await() }.isFailure, "the busy evaluation fails")
        assertTrue(ms < 2_000, "close took ${ms}ms")
        assertFailsWith<JsException> { js.evaluate(JsScript.Source("1", "after.js")) }
        js.close() // idempotent
    }
}
