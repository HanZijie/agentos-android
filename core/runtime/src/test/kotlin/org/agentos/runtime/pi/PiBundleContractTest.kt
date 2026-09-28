package org.agentos.runtime.pi

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.agentos.runtime.net.NetErrorKind
import org.agentos.runtime.net.RetryPolicy
import org.agentos.runtime.pi.testing.FakeModelServer
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Contract test of the generated `pi-agent.js` on QuickJS (quickjs-kt-jvm), through the same
 * PiRuntime + HostFetch the :agent process uses. Mirrors core/pi-runtime/test/contract.mjs
 * (the Node `vm` run of the same bundle). Skipped when the bundle has not been built.
 */
class PiBundleContractTest {

    companion object {
        private lateinit var fake: FakeModelServer
        private lateinit var h: PiHarness
        private lateinit var families: List<Pair<String, ModelSpec>>

        @BeforeClass
        @JvmStatic
        fun startAll() {
            val bundle = PiAssets.bundle
            val catalog = PiAssets.catalog
            assumeTrue(PiAssets.MISSING, bundle != null && catalog != null)
            fake = FakeModelServer()
            families = listOf(
                "anthropic-messages" to ModelSpec(JsonObject(catalog!!.model("minimax", "MiniMax-M2.7")!!.json + ("baseUrl" to kotlinx.serialization.json.JsonPrimitive(fake.anthropicBaseUrl)))),
                "openai-completions" to catalog.customModel("openai-completions", "deepseek-chat", fake.openaiBaseUrl),
            )
            h = PiHarness(families.map { it.second.baseUrl to fake.key })
            val startup = runBlocking { h.runtime.start(bundle!!) }
            println("startup $startup")
        }

        @AfterClass
        @JvmStatic
        fun stopAll() {
            if (!::h.isInitialized) return
            runBlocking { h.runtime.close() }
            fake.close()
        }
    }

    private fun since(n: Int) = fake.requests.drop(n)

    private fun forEachFamily(block: suspend (tag: String, model: ModelSpec) -> Unit) = runBlocking<Unit> {
        for ((tag, model) in families) {
            try {
                block(tag, model)
            } catch (e: Throwable) {
                throw AssertionError("[$tag] ${e.message}", e)
            }
        }
    }

    @Test
    fun `ping reports protocol and API families`() = runBlocking<Unit> {
        val r = h.runtime.ping()
        assertEquals(PI_PROTOCOL_VERSION.toString(), r["protocol"]!!.jsonPrimitive.content)
        assertEquals(listOf("anthropic-messages", "openai-completions"), r["apis"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `streaming text arrives chunk by chunk with the real key`() = forEachFamily { tag, model ->
        val sid = h.session(model)
        val n0 = fake.requests.size
        val r = h.runtime.prompt(sid, "[echo] hello-$tag")
        val d = h.deltas(sid)
        val req = since(n0).single()
        assertEquals("stop", r.stopReason, r.errorMessage)
        assertTrue(r.text.startsWith("echo:[echo] hello-$tag"), r.text)
        assertTrue(d.size >= 4, "deltas ${d.size}")
        assertTrue(d.first().at < req.chunkTimes.last(), "first delta must arrive before the server sends its last chunk")
        assertTrue(req.stream && req.presentedKeyKind == "real")
        assertEquals(listOf("user", "assistant"), r.appended.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(3, r.messageCount)
        val types = h.eventsFor(sid).map { it.type }.toSet()
        for (t in listOf("agent_start", "turn_start", "message_start", "message_update", "message_end", "turn_end", "agent_end")) assertTrue(t in types, "missing $t")
        val outcome = h.outcomes.last() as ModelRequestOutcome.Responded
        assertEquals(sid, outcome.sessionId)
    }

    @Test
    fun `CJK and emoji survive host, JS and HTTP`() = forEachFamily { _, model ->
        val probe = "你好，世界 🙂👍🏽 naïve café 𝄞 한국어"
        val sid = h.session(model)
        val n0 = fake.requests.size
        val r = h.runtime.prompt(sid, "[echo] $probe")
        assertTrue(probe in since(n0).single().body["messages"].toString(), "request body lost characters")
        assertTrue(probe in r.text, r.text)
        assertEquals(r.text, h.streamedText(sid))
    }

    @Test
    fun `tool call round trip passes before and after hooks`() = forEachFamily { _, model ->
        val sid = h.session(model)
        val n0 = fake.requests.size
        h.hostCalls.clear()
        val r = h.runtime.prompt(sid, "[tool:add 2 3] please add")
        val ev = h.eventsFor(sid).map { it.e }
        val start = ev.first { it["type"]!!.jsonPrimitive.content == "tool_execution_start" }
        val end = ev.first { it["type"]!!.jsonPrimitive.content == "tool_execution_end" }
        assertEquals("add", start["toolName"]!!.jsonPrimitive.content)
        assertEquals("2", start["args"]!!.jsonObject["a"]!!.jsonPrimitive.content)
        assertEquals("false", end["isError"]!!.jsonPrimitive.content)
        assertEquals(2, since(n0).size)
        assertEquals("sum=5", r.text)
        assertEquals(listOf("before", "tool", "after"), h.hostCalls.map { it.first })
        assertEquals(listOf("user", "assistant", "toolResult", "assistant"), r.appended.map { it.jsonObject["role"]!!.jsonPrimitive.content })
    }

    @Test
    fun `beforeToolCall blocks a tool and the model sees the reason`() = forEachFamily { _, model ->
        val sid = h.session(model)
        h.hostCalls.clear()
        val r = h.runtime.prompt(sid, "[tool:forbidden 1 1]")
        val end = h.eventsFor(sid).first { it.type == "tool_execution_end" }.e
        assertEquals("true", end["isError"]!!.jsonPrimitive.content)
        assertTrue("blocked by policy" in end["result"].toString())
        assertTrue(h.hostCalls.none { it.first == "tool" }, "tool must not run")
        assertTrue(r.text.startsWith("sum=") && "blocked by policy" in r.text, r.text)
    }

    @Test
    fun `afterToolCall rewrites what the model sees`() = forEachFamily { _, model ->
        val sid = h.session(model)
        val n0 = fake.requests.size
        val r = h.runtime.prompt(sid, "[tool:secret 1 1]")
        val second = since(n0)[1].body["messages"].toString()
        assertTrue("redacted" in second && "raw-secret-output" !in second)
        assertEquals("sum=redacted", r.text)
    }

    @Test
    fun `abort mid-stream ends as aborted and closes the connection`() = forEachFamily { _, model ->
        val sid = h.session(model)
        val n0 = fake.requests.size
        val r = coroutineScope {
            val p = async { h.runtime.prompt(sid, "[slow] long answer") }
            h.awaitDelta(sid)
            h.runtime.abort(sid)
            withTimeout(5_000) { p.await() }
        }
        delay(300)
        assertEquals("aborted", r.stopReason)
        assertTrue(since(n0).single().closedEarly, "server should see the client close the connection")
        assertEquals(0, h.runtime.inflightHostFetches)
        assertEquals(0, h.runtime.stats().inflightFetches)
    }

    @Test
    fun `abort during a tool call cancels it`() = forEachFamily { _, model ->
        val sid = h.session(model)
        val r = coroutineScope {
            val p = async { h.runtime.prompt(sid, "[tool:slow 1 1]") }
            withTimeout(10_000) { while (h.eventsFor(sid).none { it.type == "tool_execution_start" }) delay(10) }
            val t0 = System.nanoTime()
            h.runtime.abort(sid)
            val res = withTimeout(5_000) { p.await() }
            assertTrue((System.nanoTime() - t0) / 1e6 < 1_000, "abort waited for the tool")
            res
        }
        assertEquals("aborted", r.stopReason)
        val toolCallId = h.eventsFor(sid).first { it.type == "tool_execution_start" }.e["toolCallId"]!!.jsonPrimitive.content
        assertTrue(toolCallId in h.cancelledTools, "host was told to cancel the tool")
    }

    @Test
    fun `HTTP 500 and 429 are not retried by pi-ai or the SDKs`() = forEachFamily { _, model ->
        for (status in listOf(500, 429)) {
            val sid = h.session(model)
            val n0 = fake.requests.size
            val r = h.runtime.prompt(sid, "[fail$status]")
            delay(1_300)
            assertEquals("error", r.stopReason)
            assertEquals(1, since(n0).size, "requests for $status")
        }
    }

    @Test
    fun `rebuilt Agent from saved messages sends identical context`() = forEachFamily { _, model ->
        val a = h.session(model)
        h.runtime.prompt(a, "[echo] first turn")
        val history = h.runtime.history(a)
        val b = h.session(model, messages = history)
        val n0 = fake.requests.size
        val ra = h.runtime.prompt(a, "[echo] second turn")
        val rb = h.runtime.prompt(b, "[echo] second turn")
        val (qa, qb) = since(n0)
        fun ctx(o: JsonObject) = listOf(o["system"], o["messages"], o["tools"]).toString()
        assertEquals(ctx(qa.body), ctx(qb.body))
        assertEquals(ra.text, rb.text)
    }

    @Test
    fun `setTools changes the tools of the next request`() = forEachFamily { _, model ->
        val sid = h.session(model)
        h.runtime.setTools(sid, JsonArray(listOf(PiHarness.TOOLS[0])))
        val n0 = fake.requests.size
        h.runtime.prompt(sid, "[echo] tools?")
        val tools = since(n0).single().body["tools"].toString()
        assertTrue("add" in tools && "forbidden" !in tools && "secret" !in tools, tools)
    }

    @Test
    fun `three concurrent sessions in one runtime do not cross-talk`() = forEachFamily { tag, model ->
        val sids = (0 until 3).map { h.session(model) }
        val replies = coroutineScope { sids.mapIndexed { i, sid -> async { h.runtime.prompt(sid, "[echo] marker-$tag-$i") } }.awaitAll() }
        replies.forEachIndexed { i, r ->
            assertTrue("marker-$tag-$i" in r.text, r.text)
            assertEquals(r.text, h.streamedText(sids[i]))
        }
        val spans = sids.map { sid -> h.eventsFor(sid).let { it.first().at to it.last().at } }
        assertTrue(spans.minOf { it.second } - spans.maxOf { it.first } > 0, "sessions did not overlap")
    }

    @Test
    fun `unknown op and unknown session fail without stopping the pump`() = runBlocking<Unit> {
        assertFailsWith<JsException> { h.runtime.request("nope") }
        val e = assertFailsWith<JsException> { h.runtime.prompt("missing", "x") }
        assertTrue("Unknown session" in e.message.orEmpty())
        assertEquals(PiRuntimeState.Running, h.runtime.state.value)
    }

    @Test
    fun `no key is sent when the endpoint has none configured`() = runBlocking<Unit> {
        val model = PiAssets.catalog!!.customModel("openai-completions", "x", "http://127.0.0.1:9/v1")
        val sid = h.session(model)
        val r = h.runtime.prompt(sid, "hello")
        assertEquals("error", r.stopReason)
        val failed = h.outcomes.last() as ModelRequestOutcome.Failed
        assertEquals(NetErrorKind.NO_CREDENTIAL, failed.error.kind)
        assertEquals(sid, failed.sessionId)
    }

    @Test
    fun `key never enters JS and the placeholder never reaches the server`() = runBlocking<Unit> {
        // Runs after or between the others; checks everything recorded so far, then one more turn.
        h.runtime.prompt(h.session(families[0].second), "[echo] leak check")
        assertEquals(emptyList(), h.keyLeaks.toList())
        assertEquals(setOf("real"), fake.requests.map { it.presentedKeyKind }.toSet())
    }

    @Test
    fun `host retry policy retries before the head, invisible to JS`() = runBlocking<Unit> {
        val bundle = PiAssets.bundle!!
        val retrying = PiHarness(listOf(fake.anthropicBaseUrl to fake.key), RetryPolicy(maxAttempts = 3, initialBackoffMs = 50))
        retrying.runtime.start(bundle)
        try {
            val sid = retrying.session(families[0].second)
            val n0 = fake.requests.size
            fake.failNext(503)
            val r = retrying.runtime.prompt(sid, "[echo] after one 503")
            assertEquals("stop", r.stopReason, r.errorMessage)
            assertEquals(listOf(503, 200), since(n0).map { it.status })
            assertEquals(2, (retrying.outcomes.single() as ModelRequestOutcome.Responded).attempts)
        } finally {
            retrying.runtime.close()
        }
    }

    @Test
    fun `no host requests or timers are left over`() = runBlocking<Unit> {
        delay(200)
        val s = h.runtime.stats()
        assertEquals(0, s.inflightFetches)
        assertEquals(0, h.runtime.inflightHostFetches)
        assertEquals(s.activeTimers, h.runtime.activeHostTimers)
    }
}
