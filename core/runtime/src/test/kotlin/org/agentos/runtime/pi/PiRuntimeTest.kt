package org.agentos.runtime.pi

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.agentos.runtime.net.BaseUrlCredentials
import org.agentos.runtime.net.HostFetch
import org.agentos.runtime.pi.desktop.QuickJsJvmEngine
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PiRuntime + the desktop QuickJS engine, driven by a tiny pump script instead of pi-agent.js,
 * so these run without the Node build. The real bundle is covered by PiBundleContractTest.
 */
class PiRuntimeTest {

    private val miniPump = """
        let timerSeq = 1;
        const sleep = (ms) => __host_timer(timerSeq++, ms);
        const ops = {
          ping: async () => ({ protocol: 1 }),
          sleep: async ({ ms }) => { const t0 = Date.now(); const r = await sleep(ms); return { elapsed: Date.now() - t0, r }; },
          fetchAll: async ({ url }) => {
            const head = JSON.parse(await __host_fetch(1, JSON.stringify({ url, method: "GET", headers: [], body: null, sid: "s1" })));
            let bytes = 0, chunks = 0, isU8 = true;
            for (;;) { const c = await __host_fetch_read(1); if (c == null) break; isU8 = isU8 && (c instanceof Uint8Array); bytes += c.length; chunks++; }
            return { status: head.status, bytes, chunks, isU8 };
          },
          fetchAbortEarly: async ({ url }) => {
            const p = __host_fetch(2, JSON.stringify({ url, method: "GET", headers: [], body: null, sid: "s2" }));
            await sleep(50);
            __host_fetch_abort(2);
            try { await p; return { aborted: false }; } catch (e) { return { aborted: true, message: String(e.message ?? e) }; }
          },
          tool: async ({ args }) => JSON.parse(await __host_call("tool", JSON.stringify({ sid: "s1", toolCallId: "t1", name: "echo", args }))),
          event: async () => { __host_emit(JSON.stringify({ t: "event", sid: "s1", e: { type: "agent_start" } })); return {}; },
          fail: async () => { throw new Error("boom"); },
          reject: async () => { Promise.reject(new Error("deliberate unhandled rejection")); await sleep(20); return {}; },
        };
        globalThis.__pi_main = async function () {
          __host_emit(JSON.stringify({ t: "ready", protocol: PROTOCOL, apis: ["test"] }));
          for (;;) {
            const raw = await __host_next();
            if (raw == null) break;
            const cmd = JSON.parse(raw);
            (ops[cmd.op] ? ops[cmd.op](cmd) : Promise.reject(new Error("Unknown op")))
              .then((value) => __host_emit(JSON.stringify({ t: "reply", id: cmd.id, ok: true, value: value ?? null })),
                    (e) => __host_emit(JSON.stringify({ t: "reply", id: cmd.id, ok: false, error: { message: String(e.message) } })));
          }
          return "stopped";
        };
    """.trimIndent()

    private fun bundle(protocol: Int = 1) = PiBundle("const PROTOCOL = $protocol;\n$miniPump", "mini-pump.js")

    private class Recorder : PiRuntimeListener, PiHost {
        val events = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val stopped = CopyOnWriteArrayList<Throwable?>()
        val outcomes = CopyOnWriteArrayList<ModelRequestOutcome>()
        override fun onEvent(sid: String, event: JsonObject) { events += sid to event }
        override fun onStopped(cause: Throwable?) { stopped += cause }
        override fun onModelRequest(outcome: ModelRequestOutcome) { outcomes += outcome }
        override suspend fun executeTool(sid: String, toolCallId: String, name: String, args: JsonElement): JsonObject =
            buildJsonObject { put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "echo:$name:$args") }) }) }
    }

    private fun runtime(rec: Recorder, cache: BytecodeCache? = null) =
        PiRuntime(QuickJsJvmEngine.factory, HostFetch(BaseUrlCredentials(emptyList())), rec, rec, cache)

    @Test
    fun `starts, answers commands and closes cleanly`() = runBlocking<Unit> {
        val rec = Recorder()
        val rt = runtime(rec)
        val startup = rt.start(bundle())
        assertEquals(listOf("test"), startup.apis)
        assertEquals(1, rt.ping()["protocol"]!!.jsonPrimitive.int)
        val err = assertFailsWith<JsException> { rt.request("fail") }
        assertEquals("boom", err.message)
        assertFailsWith<JsException> { rt.request("nope") }
        rt.request("event")
        assertEquals("s1" to "agent_start", rec.events.single().let { it.first to it.second["type"]!!.jsonPrimitive.content })
        rt.close()
        assertIs<PiRuntimeState.Stopped>(rt.state.value)
        assertEquals(listOf<Throwable?>(null), rec.stopped.toList())
        assertFailsWith<PiRuntimeStoppedException> { rt.ping() }
    }

    @Test
    fun `timers are real delays`() = runBlocking<Unit> {
        val rt = runtime(Recorder())
        rt.start(bundle())
        val r = rt.request("sleep", buildJsonObject { put("ms", 150) }).jsonObject
        assertTrue(r["elapsed"]!!.jsonPrimitive.long >= 145, "elapsed ${r["elapsed"]}")
        assertEquals("fired", r["r"]!!.jsonPrimitive.content)
        assertEquals(0, rt.activeHostTimers)
        rt.close()
    }

    @Test
    fun `commands interleave instead of queueing behind a long one`() = runBlocking<Unit> {
        val rt = runtime(Recorder())
        rt.start(bundle())
        val long = async { rt.request("sleep", buildJsonObject { put("ms", 1_000) }) }
        delay(50)
        val t0 = System.nanoTime()
        rt.ping()
        assertTrue((System.nanoTime() - t0) / 1e6 < 300, "ping waited for the long command")
        long.await()
        rt.close()
    }

    @Test
    fun `fetch streams Uint8Array chunks and an early abort cancels the request`() = runBlocking<Unit> {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("y".repeat(4_000)).throttleBody(500, 20, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody("late").setHeadersDelay(5, TimeUnit.SECONDS))
            val rec = Recorder()
            val rt = runtime(rec)
            rt.start(bundle())
            val all = rt.request("fetchAll", buildJsonObject { put("url", "http://127.0.0.1:${server.port}/a") }).jsonObject
            assertEquals(200, all["status"]!!.jsonPrimitive.int)
            assertEquals(4_000, all["bytes"]!!.jsonPrimitive.int)
            assertTrue(all["chunks"]!!.jsonPrimitive.int > 1)
            assertEquals("true", all["isU8"]!!.jsonPrimitive.content)
            val t0 = System.nanoTime()
            val early = rt.request("fetchAbortEarly", buildJsonObject { put("url", "http://127.0.0.1:${server.port}/b") }).jsonObject
            assertEquals("true", early["aborted"]!!.jsonPrimitive.content)
            assertTrue((System.nanoTime() - t0) / 1e6 < 2_000)
            assertEquals(0, rt.inflightHostFetches)
            val responded = rec.outcomes.single() as ModelRequestOutcome.Responded
            assertEquals("s1", responded.sessionId)
            rt.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `host calls reach PiHost`() = runBlocking<Unit> {
        val rt = runtime(Recorder())
        rt.start(bundle())
        val r = rt.request("tool", buildJsonObject { put("args", buildJsonObject { put("q", "你好 🙂") }) }).jsonObject
        assertEquals("""echo:echo:{"q":"你好 🙂"}""", r["content"].toString().substringAfter("\"text\":\"").substringBeforeLast("\"}").replace("\\\"", "\""))
        rt.close()
    }

    @Test
    fun `protocol mismatch fails start`() = runBlocking<Unit> {
        val rt = runtime(Recorder())
        val e = assertFailsWith<IllegalStateException> { rt.start(bundle(protocol = 2)) }
        assertTrue("protocol 2" in e.message.orEmpty())
        assertIs<PiRuntimeState.Stopped>(rt.state.value)
    }

    @Test
    fun `an unhandled rejection stops the pump, later requests fail fast, a new runtime works`() = runBlocking<Unit> {
        val rec = Recorder()
        val rt = runtime(rec)
        rt.start(bundle())
        runCatching { withTimeout(5_000) { rt.request("reject") } }
        withTimeout(5_000) { while (rt.state.value !is PiRuntimeState.Stopped) delay(10) }
        val stopped = rt.state.value as PiRuntimeState.Stopped
        assertTrue(stopped.cause is JsException, "cause ${stopped.cause}")
        assertEquals(1, rec.stopped.size)
        val t0 = System.nanoTime()
        assertFailsWith<PiRuntimeStoppedException> { rt.ping() }
        assertTrue((System.nanoTime() - t0) / 1e6 < 100)
        rt.close()

        val again = runtime(Recorder())
        again.start(bundle())
        assertEquals(1, again.ping()["protocol"]!!.jsonPrimitive.int)
        again.close()
    }

    @Test
    fun `bytecode cache is filled after the first start and used by the next`() = runBlocking<Unit> {
        val cache = InMemoryBytecodeCache()
        val first = runtime(Recorder(), cache)
        assertEquals(false, first.start(bundle()).fromBytecode)
        withTimeout(10_000) { while (cache.size == 0) delay(20) }
        first.close()

        val second = runtime(Recorder(), cache)
        val s = second.start(bundle())
        assertEquals(true, s.fromBytecode)
        assertEquals(1, second.ping()["protocol"]!!.jsonPrimitive.int)
        second.close()
    }

    @Test
    fun `corrupt bytecode falls back to source and is dropped`() = runBlocking<Unit> {
        val cache = InMemoryBytecodeCache()
        val b = bundle()
        val key = "pi-agent:${b.sha256}:${QuickJsJvmEngine().let { e -> e.description.also { runBlocking { e.close() } } }}:p$PI_PROTOCOL_VERSION"
        cache.put(key, ByteArray(64) { it.toByte() })
        val rt = runtime(Recorder(), cache)
        val s = rt.start(b)
        assertEquals(false, s.fromBytecode)
        assertEquals(1, rt.ping()["protocol"]!!.jsonPrimitive.int)
        rt.close()
        withTimeout(10_000) { while (cache.get(key)?.size == 64) delay(20) }
        assertTrue(cache.get(key).let { it == null || it.size > 64 })
    }

    @Test
    fun `memory usage is available while the pump runs`() = runBlocking<Unit> {
        val rt = runtime(Recorder())
        rt.start(bundle())
        val m = rt.memoryUsage()!!
        assertTrue(m.usedBytes > 0 && m.objectCount > 0)
        rt.close()
        assertNull(rt.memoryUsage())
    }
}
