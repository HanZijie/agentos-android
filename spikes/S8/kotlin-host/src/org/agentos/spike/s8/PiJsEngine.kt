package org.agentos.spike.s8

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** The bundle, either as source or as QuickJS bytecode compiled from it. */
sealed interface JsBundle {
    class Source(val code: String, val filename: String = "pi-agent.js") : JsBundle
    class Bytecode(val bytes: ByteArray) : JsBundle
}

/** Host-side handlers for tools and hooks. Called on the JS dispatcher; must not block. */
interface HostCalls {
    suspend fun call(method: String, payload: JSONObject): JSONObject?
    fun onToolCancel(sid: String, toolCallId: String) {}
}

fun interface EngineListener {
    /** Called on the JS thread for every Pi lifecycle event (compacted). Keep it cheap. */
    fun onEvent(sid: String, event: JSONObject)
}

class JsError(message: String, val jsStack: String?) : RuntimeException(message)

data class StartupTimings(val createMs: Double, val evalMs: Double, val readyMs: Double) {
    val totalMs get() = createMs + evalMs + readyMs
}

/**
 * One QuickJS runtime running the S8 bundle in "pump" mode: `__pi_main()` is
 * evaluated once and stays pending; commands are fed through `__host_next()`.
 *
 * quickjs-kt serialises root `evaluate()` calls on an instance, so a second
 * evaluate (another session's prompt, or `abort`) would wait until the first
 * prompt finished. The pump avoids that: every command is a message, and all
 * sessions interleave on the single JS thread.
 */
class PiJsEngine(
    private val bundle: JsBundle,
    val fetch: HostFetch,
    private val host: HostCalls,
    private val listener: EngineListener,
    private val jsDispatcher: CoroutineDispatcher,
    private val onLog: (level: String, msg: String) -> Unit = { _, _ -> },
) {
    private val scope = CoroutineScope(SupervisorJob() + jsDispatcher)
    lateinit var quickJs: QuickJs
        private set
    private val commands = Channel<String?>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Any?>>()
    private val timers = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val nextId = AtomicLong(1)
    private val ready = CompletableDeferred<Unit>()
    private var mainJob: Job? = null
    @Volatile var mainError: Throwable? = null
        private set

    val activeHostTimers: Int get() = timers.size

    suspend fun start(): StartupTimings = withContext(jsDispatcher) {
        val t0 = System.nanoTime()
        val js = QuickJs.create(jsDispatcher)
        quickJs = js
        defineBindings(js)
        val t1 = System.nanoTime()
        when (bundle) {
            is JsBundle.Source -> js.evaluate<Any?>(bundle.code, bundle.filename, false)
            is JsBundle.Bytecode -> js.evaluate<Any?>(bundle.bytes)
        }
        val t2 = System.nanoTime()
        mainJob = scope.launch {
            try {
                js.evaluate<Any?>("__pi_main()", "pi-main.js", false)
            } catch (e: Throwable) {
                mainError = e
                ready.completeExceptionally(e)
                val err = IllegalStateException("JS pump stopped: ${e.message}", e)
                pending.values.forEach { it.completeExceptionally(err) }
                pending.clear()
            }
        }
        ready.await()
        val t3 = System.nanoTime()
        StartupTimings((t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6)
    }

    private fun defineBindings(js: QuickJs) {
        js.function<Any?>("__host_emit") { args ->
            onEmit(args[0] as String)
            null
        }
        js.asyncFunction<String?>("__host_next") { commands.receive() }
        js.asyncFunction<String>("__host_timer") { args ->
            val id = (args[0] as Number).toLong()
            val ms = (args[1] as Number).toLong()
            val signal = CompletableDeferred<String>()
            timers[id] = signal
            try {
                if (ms <= 0) "fired" else withTimeoutOrNull(ms) { signal.await() } ?: "fired"
            } finally {
                timers.remove(id, signal)
            }
        }
        js.function<Any?>("__host_timer_cancel") { args ->
            timers.remove((args[0] as Number).toLong())?.complete("cancelled")
            null
        }
        js.asyncFunction<String>("__host_fetch") { args ->
            fetch.start((args[0] as Number).toLong(), args[1] as String)
        }
        js.asyncFunction<ByteArray?>("__host_fetch_read") { args ->
            fetch.read((args[0] as Number).toLong())
        }
        js.function<Any?>("__host_fetch_abort") { args ->
            fetch.abort((args[0] as Number).toLong())
            null
        }
        js.asyncFunction<String>("__host_call") { args ->
            val result = host.call(args[0] as String, JSONObject(args[1] as String))
            val text = result?.toString() ?: "{}"
            fetch.ingressAudit?.invoke(text.toByteArray())
            text
        }
    }

    private fun onEmit(json: String) {
        val msg = JSONObject(json)
        when (msg.optString("t")) {
            "reply" -> {
                val d = pending.remove(msg.getLong("id")) ?: return
                if (msg.optBoolean("ok")) d.complete(msg.opt("value"))
                else {
                    val e = msg.optJSONObject("error")
                    d.completeExceptionally(JsError(e?.optString("message") ?: "JS error", e?.optString("stack")))
                }
            }
            "event" -> listener.onEvent(msg.getString("sid"), msg.getJSONObject("e"))
            "tool_cancel" -> host.onToolCancel(msg.getString("sid"), msg.getString("toolCallId"))
            "ready" -> ready.complete(Unit)
            "log" -> onLog(msg.optString("level"), msg.optString("msg"))
        }
    }

    /** Sends a command to the pump and suspends until its reply. Returns the reply value (JSONObject/JSONArray/primitive). */
    suspend fun request(op: String, payload: JSONObject = JSONObject()): Any? {
        mainError?.let { throw IllegalStateException("JS pump stopped", it) }
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<Any?>()
        pending[id] = deferred
        val cmd = JSONObject(payload.toString()).put("id", id).put("op", op).toString()
        fetch.ingressAudit?.invoke(cmd.toByteArray())
        commands.send(cmd)
        return deferred.await()
    }

    suspend fun requestObject(op: String, payload: JSONObject = JSONObject()): JSONObject =
        request(op, payload) as? JSONObject ?: JSONObject()

    suspend fun stop() {
        commands.trySend(null)
        withTimeoutOrNull(5_000) { mainJob?.join() }
        fetch.abortAll()
        timers.values.forEach { it.complete("cancelled") }
        withContext(jsDispatcher) { runCatching { quickJs.close() } }
        scope.cancel()
    }

    companion object {
        /** Compiles the bundle to QuickJS bytecode in a throwaway runtime. */
        suspend fun compile(source: String, dispatcher: CoroutineDispatcher): ByteArray = withContext(dispatcher) {
            val js = QuickJs.create(dispatcher)
            try { js.compile(source, "pi-agent.js", false) } finally { js.close() }
        }

        fun parse(value: Any?): Any? = if (value is String) JSONTokener(value).nextValue() else value
    }
}
