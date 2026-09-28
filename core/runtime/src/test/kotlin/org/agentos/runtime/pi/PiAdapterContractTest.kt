package org.agentos.runtime.pi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.events.AgentEvent
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.net.BaseUrlCredentials
import org.agentos.runtime.pi.desktop.QuickJsJvmEngine
import org.agentos.runtime.pi.testing.FakeModelServer
import org.agentos.runtime.ports.AgentCoreState
import org.agentos.runtime.ports.AgentCoreUnavailableException
import org.agentos.runtime.ports.AgentSessionConfig
import org.agentos.runtime.ports.Credential
import org.agentos.runtime.ports.FinishReason
import org.agentos.runtime.ports.ModelSpec
import org.agentos.runtime.ports.PiMessages
import org.agentos.runtime.ports.ToolCallDecision
import org.agentos.runtime.ports.ToolDeclaration
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.TurnInput
import org.agentos.runtime.ports.TurnOutcome
import org.agentos.runtime.testing.FakeStep
import org.agentos.runtime.testing.FakeTurnScript
import org.agentos.runtime.testing.RecordingTurnHost
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The AgentCore contract (ports/AgentCore.kt) on the real Pi: PiAdapter + pi-agent.js on QuickJS
 * (quickjs-kt-jvm) + HostFetch, against FakeModelServer playing A's FakeTurnScript model.
 * The first group mirrors FakeAgentCoreContractTest scenario by scenario; the second covers what
 * only the real stack can show (error classification, no retries, thinking replay, ...).
 * Every scenario runs for both API families. Skipped when pi-agent.js has not been built.
 */
class PiAdapterContractTest {

    companion object {
        private lateinit var fake: FakeModelServer
        private lateinit var families: List<Pair<String, ModelSpec>>

        @BeforeClass
        @JvmStatic
        fun startServer() {
            assumeTrue(PiAssets.MISSING, PiAssets.bundle != null && PiAssets.catalog != null)
            fake = FakeModelServer()
            val catalog = PiAssets.catalog!!
            val minimax = catalog.model("minimax", "MiniMax-M2.7")!!.json
            families = listOf(
                "anthropic" to ModelSpec(JsonObject(minimax + ("baseUrl" to JsonPrimitive(fake.anthropicBaseUrl)))),
                "openai" to catalog.customModel("openai-completions", "fake-chat", fake.openaiBaseUrl).toModelSpec(),
            )
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            if (::fake.isInitialized) fake.close()
        }

        private val ANY_ARGS = buildJsonObject { put("type", "object") }
        private val INT_ARGS = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("a", buildJsonObject { put("type", "integer") })
                put("b", buildJsonObject { put("type", "integer") })
            })
        }
        val TOOLS = listOf(
            ToolDeclaration("add", "Add two integers.", INT_ARGS),
            ToolDeclaration("rm", "Remove something.", ANY_ARGS),
            ToolDeclaration("slow", "Never returns.", ANY_ARGS),
            ToolDeclaration("noop", "Does nothing.", ANY_ARGS),
        )
    }

    /**
     * Desktop engines that can be made to fail like production does: on request, the next async
     * host call first evaluates (nested, same evaluation session) a promise rejection nobody
     * handles, and quickjs-kt ends the root evaluation, i.e. the pump (docs/spikes/S8.md b).
     * Closing the engine from outside is not a valid simulation: quickjs-kt can livelock when a
     * runtime is closed under active async bindings.
     */
    private class Engines : JsEngineFactory {
        @Volatile var crashRequested = false

        override fun create(): JsEngine {
            val d = QuickJsJvmEngine()
            return object : JsEngine by d {
                override fun defineAsyncFunction(name: String, body: suspend (args: List<Any?>) -> Any?) =
                    d.defineAsyncFunction(name) { args ->
                        if (crashRequested) {
                            crashRequested = false
                            d.evaluate(JsScript.Source("Promise.reject(new Error('injected pump failure')); 0", "crash.js"))
                        }
                        body(args)
                    }
            }
        }
    }

    private val engines = Engines()

    private fun core(): PiAdapter = PiAdapter(
        bundle = { PiAssets.bundle!! },
        engineFactory = engines,
        secrets = BaseUrlCredentials(
            listOf(
                BaseUrlCredentials.Entry(fake.anthropicBaseUrl, Credential(fake.key)),
                BaseUrlCredentials.Entry(fake.openaiBaseUrl, Credential(fake.key)),
            ),
        ),
    )

    private fun config(model: ModelSpec, maxToolRounds: Int = AgentSessionConfig.DEFAULT_MAX_TOOL_ROUNDS) =
        AgentSessionConfig(model, systemPrompt = "You are a test agent.", tools = TOOLS, maxToolRounds = maxToolRounds)

    private var seq = 0

    /** A prompt unique to this run, with [script] registered for it. */
    private fun prompt(tag: String, script: FakeTurnScript): String = "$tag-${++seq}-${System.nanoTime()}".also { fake.script(it, script) }

    private fun roles(messages: PiMessages) = messages.json.map { it.jsonObject["role"]!!.jsonPrimitive.content }

    private fun args(vararg pairs: Pair<String, Int>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    /** Runs [block] once per API family with a fresh core, closed afterwards. */
    private fun eachFamily(block: suspend CoroutineScope.(tag: String, model: ModelSpec, core: PiAdapter) -> Unit) = runBlocking<Unit> {
        for ((tag, model) in families) {
            val core = core()
            try {
                withTimeout(30_000) { block(tag, model, core) }
            } catch (e: Throwable) {
                throw AssertionError("[$tag] ${e.message}", e)
            } finally {
                core.close()
            }
        }
    }

    // ================================================================ FakeAgentCoreContractTest, on Pi

    @Test
    fun `text turn streams deltas in Pi order and ends after agent_end`() = eachFamily { tag, model, core ->
        core.start()
        val session = core.openSession("s1", config(model))
        val host = RecordingTurnHost()

        val outcome = session.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Text("hello world", chunkChars = 3)))), host)

        assertIs<TurnOutcome.Finished>(outcome)
        assertEquals(FinishReason.END_TURN, outcome.reason)
        assertNotNull(outcome.usage, "usage of the last assistant message")
        assertEquals("hello world", host.streamedText())
        val types = host.eventTypes()
        assertEquals(EventTypes.AGENT_START, types.first())
        assertEquals(EventTypes.AGENT_END, types.last())
        assertEquals(
            listOf(EventTypes.AGENT_START, EventTypes.TURN_START, EventTypes.MESSAGE_START, EventTypes.MESSAGE_END, EventTypes.MESSAGE_START),
            types.take(5),
        )
        assertEquals(EventTypes.TURN_END, types[types.size - 2])
        assertEquals(listOf("system", "user", "assistant"), roles(session.messages()))
        assertEquals("stop", session.messages().json.last().jsonObject["stopReason"]!!.jsonPrimitive.content)
        // Only the update kinds that go to the event log reach the host (events.md, section 3).
        val kinds = host.events.filterIsInstance<AgentEvent.MessageUpdate>().map { it.update.kind }.toSet()
        assertTrue(kinds.all { it in PiEventMapper.LOGGED_UPDATES }, "update kinds $kinds")
    }

    @Test
    fun `tool use goes through before, execute, after in Pi order`() = eachFamily { tag, model, core ->
        core.start()
        val session = core.openSession("s1", config(model))
        val host = RecordingTurnHost(
            tools = mapOf("add" to { call -> ToolResult.text((call.arguments["a"]!!.jsonPrimitive.int + call.arguments["b"]!!.jsonPrimitive.int).toString()) }),
            after = { _, r -> r.copy(details = JsonPrimitive("audited")) },
        )
        val script = FakeTurnScript(
            listOf(
                listOf(FakeStep.Text("let me add"), FakeStep.ToolUse("add", args("a" to 2, "b" to 3), id = "t1")),
                listOf(FakeStep.Text("sum=5")),
            ),
        )

        val outcome = session.runTurn(TurnInput(prompt(tag, script)), host)

        assertIs<TurnOutcome.Finished>(outcome)
        assertEquals(listOf("before:t1", "execute:t1", "after:t1"), host.calls)
        val types = host.eventTypes()
        val start = types.indexOf(EventTypes.TOOL_EXECUTION_START)
        val end = types.indexOf(EventTypes.TOOL_EXECUTION_END)
        assertTrue(start in 0 until end, "tool_execution_start before tool_execution_end: $types")
        assertEquals(2, types.count { it == EventTypes.TURN_START }, "two model round trips")
        assertEquals(false, host.events.filterIsInstance<AgentEvent.ToolExecutionEnd>().single().isError)
        assertEquals(listOf("system", "user", "assistant", "toolResult", "assistant"), roles(session.messages()))
        val toolResult = session.messages().json[3].jsonObject
        assertEquals("t1", toolResult["toolCallId"]!!.jsonPrimitive.content)
        assertEquals("5", toolResult["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("audited", toolResult["details"]!!.jsonPrimitive.content, "afterToolCall override applied")
        assertEquals("let me addsum=5", host.streamedText())
    }

    @Test
    fun `blocked tool is not executed and becomes an error result`() = eachFamily { tag, model, core ->
        core.start()
        val session = core.openSession("s1", config(model))
        val host = RecordingTurnHost(tools = mapOf("rm" to { error("must not run") }), decide = { ToolCallDecision.Block("denied by user") })
        val script = FakeTurnScript(listOf(listOf(FakeStep.ToolUse("rm", id = "t1")), listOf(FakeStep.Text("ok"))))

        assertIs<TurnOutcome.Finished>(session.runTurn(TurnInput(prompt(tag, script)), host))

        // Pi 0.86.1 does not call afterToolCall for a blocked call (FakeAgentCore does; see the W3 report).
        assertEquals(listOf("before:t1"), host.calls)
        val result = session.messages().json[3].jsonObject
        assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("denied by user", result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `abort while streaming ends the turn as Aborted and the session stays usable`() = eachFamily { tag, model, core ->
        core.start()
        val session = core.openSession("s1", config(model))
        val host = RecordingTurnHost()
        val first = prompt(tag, FakeTurnScript(FakeStep.Text("partial", chunkChars = 2), FakeStep.AwaitAbort))

        val turn = async { session.runTurn(TurnInput(first), host) }
        while (host.streamedText() != "partial") delay(5)
        session.abort()

        assertEquals(TurnOutcome.Aborted, turn.await())
        assertEquals(EventTypes.AGENT_END, host.eventTypes().last())
        val aborted = session.messages().json.last().jsonObject
        assertEquals("aborted", aborted["stopReason"]!!.jsonPrimitive.content)
        assertEquals("partial", aborted["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)

        val next = RecordingTurnHost()
        assertIs<TurnOutcome.Finished>(session.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Text("again")))), next))
        assertEquals("again", next.streamedText())
    }

    @Test
    fun `abort during a tool call cancels executeTool`() = eachFamily { tag, model, core ->
        core.start()
        val session = core.openSession("s1", config(model))
        val host = RecordingTurnHost(tools = mapOf("slow" to RecordingTurnHost.HANGING_TOOL))
        val script = FakeTurnScript(listOf(listOf(FakeStep.ToolUse("slow", id = "t1")), listOf(FakeStep.Text("never"))))
        val n0 = fake.requests.size

        val turn = async { session.runTurn(TurnInput(prompt(tag, script)), host) }
        while ("execute:t1" !in host.calls) delay(5)
        session.abort()

        assertEquals(TurnOutcome.Aborted, turn.await())
        assertTrue("cancelled:t1" in host.calls, "executeTool coroutine was cancelled: ${host.calls}")
        assertTrue("after:t1" !in host.calls)
        assertEquals(listOf("system", "user", "assistant", "toolResult"), roles(session.messages()), "every toolCall has a toolResult")
        assertTrue("never" !in host.streamedText(), "no further model round after abort")
        assertEquals(1, fake.requests.size - n0, "no model request after the abort")
    }

    @Test
    fun `tool round limit stops the turn with ToolRoundLimit`() = eachFamily { tag, model, core ->
        core.start()
        val session = core.openSession("s1", config(model, maxToolRounds = 2))
        val host = RecordingTurnHost(tools = mapOf("noop" to { ToolResult.text("ok") }))
        val round = listOf(FakeStep.ToolUse("noop"))
        val n0 = fake.requests.size

        val outcome = session.runTurn(TurnInput(prompt(tag, FakeTurnScript(listOf(round, round, round, listOf(FakeStep.Text("unreachable")))))), host)

        assertEquals(TurnOutcome.ToolRoundLimit(rounds = 3, limit = 2), outcome)
        assertEquals(2, host.calls.count { it.startsWith("execute:") })
        assertEquals(3, fake.requests.size - n0, "no model request after the limit")
        val messages = session.messages().json.map { it.jsonObject }
        val calls = messages.filter { it["role"]!!.jsonPrimitive.content == "assistant" }
            .flatMap { m -> m["content"]!!.jsonArray.map { it.jsonObject }.filter { it["type"]!!.jsonPrimitive.content == "toolCall" } }
            .map { it["id"]!!.jsonPrimitive.content }
        val results = messages.filter { it["role"]!!.jsonPrimitive.content == "toolResult" }.map { it["toolCallId"]!!.jsonPrimitive.content }
        assertEquals(calls, results, "messages stay valid for the next turn")
        assertIs<TurnOutcome.Finished>(session.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Text("next")))), RecordingTurnHost()))
    }

    @Test
    fun `model failure is returned as Failed with the classified error`() = eachFamily { tag, model, core ->
        core.start()
        val session = core.openSession("s1", config(model))
        val host = RecordingTurnHost()

        val outcome = session.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Text("hm"), FakeStep.Fail(ErrorCode.MODEL_RATE_LIMITED.info("429"))))), host)

        assertIs<TurnOutcome.Failed>(outcome)
        assertEquals(ErrorCode.MODEL_RATE_LIMITED, outcome.error.code, outcome.error.message)
        assertTrue(outcome.error.retryable)
        assertEquals(EventTypes.AGENT_END, host.eventTypes().last())
    }

    @Test
    fun `restore from saved messages rebuilds the same context`() = eachFamily { tag, model, core ->
        core.start()
        val original = core.openSession("s1", config(model))
        original.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Text("first")))), RecordingTurnHost())
        val saved = original.messages()
        original.dispose()

        val restored = core.openSession("s1", config(model), restore = saved)
        assertEquals(saved, restored.messages(), "restore is verbatim; system message is not duplicated")
        val n0 = fake.requests.size
        restored.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Text("second")))), RecordingTurnHost())
        assertEquals(listOf("system", "user", "assistant", "user", "assistant"), roles(restored.messages()))
        assertTrue("first" in fake.requests[n0].body["messages"].toString(), "the earlier turn is part of the context")
    }

    @Test
    fun `pump failure returns CoreLost and the instance becomes unusable`() = eachFamily { tag, model, core ->
        core.start()
        val session = core.openSession("s1", config(model))
        val saved = session.messages()
        val host = RecordingTurnHost()

        val turn = async { session.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Text("x"), FakeStep.AwaitAbort))), host) }
        while (host.streamedText() != "x") delay(5)
        val before = host.events.size
        val t0 = System.nanoTime()
        engines.crashRequested = true // the next host call makes the pump die

        val outcome = turn.await()
        val ms = (System.nanoTime() - t0) / 1e6
        assertIs<TurnOutcome.CoreLost>(outcome)
        assertEquals(ErrorCode.AGENT_CORE_FAILED, outcome.error.code)
        assertTrue(ms < 1_000, "CoreLost after ${ms}ms")
        assertEquals(before, host.events.size, "no events after the pump died")
        assertIs<AgentCoreState.Failed>(core.state.value)
        assertFailsWith<AgentCoreUnavailableException> { core.openSession("s2", config(model)) }
        assertFailsWith<AgentCoreUnavailableException> { session.messages() }

        // The host creates a new instance and rebuilds from stored messages.
        val replacement = core()
        try {
            replacement.start()
            val rebuilt = replacement.openSession("s1", config(model), restore = saved)
            assertEquals(saved, rebuilt.messages())
            assertIs<TurnOutcome.Finished>(rebuilt.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Text("back")))), RecordingTurnHost()))
        } finally {
            replacement.close()
        }
    }

    @Test
    fun `one turn per session, sessions run concurrently`() = eachFamily { tag, model, core ->
        core.start()
        val a = core.openSession("a", config(model))
        val b = core.openSession("b", config(model))
        val hostA = RecordingTurnHost()
        val hostB = RecordingTurnHost()
        val script = FakeTurnScript(FakeStep.Text("zzz", intervalMs = 200), FakeStep.Text("done"))

        val turnA = async { a.runTurn(TurnInput(prompt(tag, script)), hostA) }
        val turnB = async { b.runTurn(TurnInput(prompt(tag, script)), hostB) }
        while (hostA.events.isEmpty()) delay(1)
        assertFailsWith<IllegalStateException> { a.runTurn(TurnInput("again"), RecordingTurnHost()) }

        assertIs<TurnOutcome.Finished>(turnA.await())
        assertIs<TurnOutcome.Finished>(turnB.await())
        assertEquals("zzzdone", hostA.streamedText())
        assertEquals("zzzdone", hostB.streamedText())
        assertFailsWith<IllegalStateException> { core.openSession("a", config(model)) }
    }

    @Test
    fun `prompt directives script the model endpoint from plain ACP clients`() = eachFamily { _, model, core ->
        core.start()
        val session = core.openSession("s1", config(model))

        val host = RecordingTurnHost(tools = mapOf("add" to { ToolResult.text("3") }))
        val directive = """{"fake": {"chunks": 3, "chunkChars": 2, "text": "ab", "tools": [{"name": "add", "arguments": {"a": 1, "b": 2}}]}}"""
        assertIs<TurnOutcome.Finished>(session.runTurn(TurnInput(directive), host))
        assertEquals("ababab" + FakeTurnScript.FINAL_TEXT, host.streamedText())
        assertEquals(1, host.calls.count { it.startsWith("execute:") })

        val failing = session.runTurn(TurnInput("""{"fake": {"fail": "model_auth_failed"}}"""), RecordingTurnHost())
        assertIs<TurnOutcome.Failed>(failing)
        assertEquals(ErrorCode.MODEL_AUTH_FAILED, failing.error.code)
    }

    // ================================================================ only the real stack shows these

    @Test
    fun `model failures are classified per errors dot md and never retried by pi-ai`() = eachFamily { tag, model, core ->
        core.start()
        val expected = listOf(
            ErrorCode.MODEL_RATE_LIMITED to ErrorCode.MODEL_RATE_LIMITED,
            ErrorCode.MODEL_AUTH_FAILED to ErrorCode.MODEL_AUTH_FAILED,
            ErrorCode.MODEL_QUOTA_EXHAUSTED to ErrorCode.MODEL_QUOTA_EXHAUSTED,
            ErrorCode.MODEL_REQUEST_TOO_LARGE to ErrorCode.MODEL_REQUEST_TOO_LARGE,
            ErrorCode.MODEL_BAD_REQUEST to ErrorCode.MODEL_BAD_REQUEST,
            ErrorCode.MODEL_UNAVAILABLE to ErrorCode.MODEL_UNAVAILABLE,
            ErrorCode.MODEL_TIMEOUT to ErrorCode.MODEL_TIMEOUT,
            ErrorCode.MODEL_NETWORK to ErrorCode.MODEL_NETWORK,
        )
        val session = core.openSession("s1", config(model))
        for ((injected, code) in expected) {
            val n0 = fake.requests.size
            val outcome = session.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Fail(injected.info("x"))))), RecordingTurnHost())
            assertIs<TurnOutcome.Failed>(outcome, "$injected")
            assertEquals(code, outcome.error.code, "$injected: ${outcome.error.message}")
            assertEquals(code.retryable, outcome.error.retryable)
            assertEquals(1, fake.requests.size - n0, "$injected: exactly one request, no retry")
        }
        val details = (session.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Fail(ErrorCode.MODEL_RATE_LIMITED.info("x"))))), RecordingTurnHost()) as TurnOutcome.Failed).error.details!!
        assertEquals(429, details["status"]!!.jsonPrimitive.int)
        assertEquals(1, details["retryAfterSeconds"]!!.jsonPrimitive.int)

        val cut = session.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Text("some"), FakeStep.Fail(ErrorCode.MODEL_STREAM_INTERRUPTED.info("x"))))), RecordingTurnHost())
        assertEquals(ErrorCode.MODEL_STREAM_INTERRUPTED, (cut as TurnOutcome.Failed).error.code, cut.error.message)

        val unconfigured = core.openSession("s2", config(PiAssets.catalog!!.customModel("openai-completions", "x", "https://no-key.example.com/v1").toModelSpec()))
        val noKey = unconfigured.runTurn(TurnInput("hi"), RecordingTurnHost())
        assertEquals(ErrorCode.MODEL_NOT_CONFIGURED, (noKey as TurnOutcome.Failed).error.code)
    }

    @Test
    fun `thinking streams as thinking_delta and is replayed on the next turn`() = eachFamily { tag, model, core ->
        core.start()
        val session = core.openSession("s1", config(model))
        val host = RecordingTurnHost()
        session.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Thinking("pondering the answer", chunkChars = 5), FakeStep.Text("42")))), host)
        val thinking = host.events.filterIsInstance<AgentEvent.MessageUpdate>().filter { it.update.kind == "thinking_delta" }.joinToString("") { it.update.delta.orEmpty() }
        assertEquals("pondering the answer", thinking)
        assertEquals("42", host.streamedText())

        val n0 = fake.requests.size
        session.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Text("ok")))), RecordingTurnHost())
        val replay = fake.requests[n0].body["messages"].toString()
        if (tag == "anthropic") {
            assertTrue("pondering the answer" in replay && "fake-signature" in replay, "thinking block with its signature is replayed")
        }
    }

    @Test
    fun `max tokens finishes with MAX_TOKENS`() = eachFamily { tag, model, core ->
        core.start()
        val session = core.openSession("s1", config(model))
        val outcome = session.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Text("cut"), FakeStep.MaxTokens))), RecordingTurnHost())
        assertEquals(FinishReason.MAX_TOKENS, (outcome as TurnOutcome.Finished).reason)
    }

    @Test
    fun `reconfigure changes model tools and system prompt from the next turn`() = eachFamily { tag, model, core ->
        core.start()
        val session = core.openSession("s1", config(model))
        session.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Text("one")))), RecordingTurnHost())

        val changed = config(ModelSpec(JsonObject(model.model + ("id" to JsonPrimitive("other-model"))))).copy(
            systemPrompt = "You are the reconfigured agent.",
            tools = TOOLS.take(1),
        )
        session.reconfigure(changed)
        val n0 = fake.requests.size
        session.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Text("two")))), RecordingTurnHost())
        val body = fake.requests[n0].body
        assertEquals("other-model", body["model"]!!.jsonPrimitive.content)
        assertTrue("You are the reconfigured agent." in body.toString(), "new system prompt is sent")
        val tools = body["tools"].toString()
        assertTrue("add" in tools && "noop" !in tools, tools)

        val running = async { session.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Text("x"), FakeStep.AwaitAbort))), RecordingTurnHost()) }
        delay(300)
        assertFailsWith<IllegalStateException> { session.reconfigure(config(model)) }
        session.abort()
        running.await()
    }

    @Test
    fun `cancelling the caller aborts the turn and the session stays usable`() = eachFamily { tag, model, core ->
        core.start()
        val session = core.openSession("s1", config(model))
        val host = RecordingTurnHost()
        val n0 = fake.requests.size
        val turn = async { session.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Text("x"), FakeStep.AwaitAbort))), host) }
        while (host.streamedText() != "x") delay(5)
        turn.cancel()
        runCatching { turn.await() }
        val eventsAtCancel = host.events.size
        withTimeout(5_000) { while (!fake.requests[n0].closedEarly) delay(10) }

        // Once Pi has finished the aborted run, the next turn runs normally.
        var next: TurnOutcome? = null
        withTimeout(5_000) {
            while (next == null) {
                next = runCatching { session.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Text("fresh")))), RecordingTurnHost()) }.getOrNull()
                if (next == null) delay(20)
            }
        }
        assertIs<TurnOutcome.Finished>(next)
        assertEquals(eventsAtCancel, host.events.size, "no callbacks to the cancelled caller's host")
    }

    @Test
    fun `close ends running turns as Aborted`() = eachFamily { tag, model, core ->
        core.start()
        val session = core.openSession("s1", config(model))
        val host = RecordingTurnHost()
        val turn = async { session.runTurn(TurnInput(prompt(tag, FakeTurnScript(FakeStep.Text("x"), FakeStep.AwaitAbort))), host) }
        while (host.streamedText() != "x") delay(5)
        core.close()
        assertEquals(TurnOutcome.Aborted, turn.await())
        assertEquals(AgentCoreState.Closed, core.state.value)
        assertFailsWith<AgentCoreUnavailableException> { core.openSession("s2", config(model)) }
    }
}
