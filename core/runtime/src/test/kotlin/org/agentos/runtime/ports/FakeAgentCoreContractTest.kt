package org.agentos.runtime.ports

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
import org.agentos.runtime.testing.FakeAgentCore
import org.agentos.runtime.testing.FakeHostPort
import org.agentos.runtime.testing.FakeScripts
import org.agentos.runtime.testing.FakeStep
import org.agentos.runtime.testing.FakeTurnScript
import org.agentos.runtime.testing.RecordingTurnHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 按 ports/AgentCore.kt 文件头的契约测 FakeAgentCore。B lane 的 PiAdapter 应满足同样的行为（用假模型端点编排）。
 */
class FakeAgentCoreContractTest {
    private val config = AgentSessionConfig(FakeHostPort.FAKE_MODEL, systemPrompt = "You are a test agent.")

    private fun core(vararg scripts: FakeTurnScript) = FakeAgentCore(FakeScripts.sequence(*scripts))

    /** JUnit 4 要求测试方法返回 void，所以这里固定返回 Unit。 */
    private fun run(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10_000) { block(this) } }

    private fun roles(messages: PiMessages) = messages.json.map { it.jsonObject["role"]!!.jsonPrimitive.content }

    private fun args(vararg pairs: Pair<String, Int>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    @Test
    fun `text turn streams deltas in Pi order and ends after agent_end`() = run {
        val core = core(FakeTurnScript(FakeStep.Text("hello world", chunkChars = 3)))
        core.start()
        val session = core.openSession("s1", config)
        val host = RecordingTurnHost()

        val outcome = session.runTurn(TurnInput("hi"), host)

        assertEquals(TurnOutcome.Finished(FinishReason.END_TURN), (outcome as TurnOutcome.Finished).copy(usage = null))
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
        val assistant = session.messages().json.last().jsonObject
        assertEquals("stop", assistant["stopReason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool use goes through before, execute, after in Pi order`() = run {
        val core = core(
            FakeTurnScript(
                listOf(
                    listOf(FakeStep.Text("let me add"), FakeStep.ToolUse("add", args("a" to 2, "b" to 3), id = "t1")),
                    listOf(FakeStep.Text("sum=5")),
                ),
            ),
        )
        core.start()
        val session = core.openSession("s1", config)
        val host = RecordingTurnHost(
            tools = mapOf(
                "add" to { call ->
                    val sum = call.arguments["a"]!!.jsonPrimitive.int + call.arguments["b"]!!.jsonPrimitive.int
                    ToolResult.text(sum.toString())
                },
            ),
            after = { _, r -> r.copy(details = JsonPrimitive("audited")) },
        )

        val outcome = session.runTurn(TurnInput("2+3?"), host)

        assertIs<TurnOutcome.Finished>(outcome)
        assertEquals(listOf("before:t1", "execute:t1", "after:t1"), host.calls)
        val types = host.eventTypes()
        val start = types.indexOf(EventTypes.TOOL_EXECUTION_START)
        val end = types.indexOf(EventTypes.TOOL_EXECUTION_END)
        assertTrue(start in 0 until end, "tool_execution_start before tool_execution_end: $types")
        assertEquals(2, types.count { it == EventTypes.TURN_START }, "two model round trips")
        val endEvent = host.events.filterIsInstance<AgentEvent.ToolExecutionEnd>().single()
        assertEquals(false, endEvent.isError)
        assertEquals(listOf("system", "user", "assistant", "toolResult", "assistant"), roles(session.messages()))
        val toolResult = session.messages().json[3].jsonObject
        assertEquals("t1", toolResult["toolCallId"]!!.jsonPrimitive.content)
        assertEquals("5", toolResult["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("audited", toolResult["details"]!!.jsonPrimitive.content, "afterToolCall override applied")
        assertEquals("let me addsum=5", host.streamedText())
    }

    @Test
    fun `blocked tool is not executed and becomes an error result`() = run {
        val core = core(FakeTurnScript(listOf(listOf(FakeStep.ToolUse("rm", id = "t1")), listOf(FakeStep.Text("ok")))))
        core.start()
        val session = core.openSession("s1", config)
        val host = RecordingTurnHost(
            tools = mapOf("rm" to { error("must not run") }),
            decide = { ToolCallDecision.Block("denied by user") },
        )

        assertIs<TurnOutcome.Finished>(session.runTurn(TurnInput("go"), host))

        assertEquals(listOf("before:t1", "after:t1"), host.calls)
        val result = session.messages().json[3].jsonObject
        assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("denied by user", result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `abort while streaming ends the turn as Aborted and the session stays usable`() = run {
        val core = core(
            FakeTurnScript(FakeStep.Text("partial", chunkChars = 2), FakeStep.AwaitAbort),
            FakeTurnScript(FakeStep.Text("again")),
        )
        core.start()
        val session = core.openSession("s1", config)
        val host = RecordingTurnHost()

        val turn = async { session.runTurn(TurnInput("long"), host) }
        while (host.streamedText() != "partial") delay(1)
        session.abort()

        assertEquals(TurnOutcome.Aborted, turn.await())
        assertEquals(EventTypes.AGENT_END, host.eventTypes().last())
        val aborted = session.messages().json.last().jsonObject
        assertEquals("aborted", aborted["stopReason"]!!.jsonPrimitive.content)
        assertEquals("partial", aborted["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)

        val next = RecordingTurnHost()
        assertIs<TurnOutcome.Finished>(session.runTurn(TurnInput("next"), next))
        assertEquals("again", next.streamedText())
    }

    @Test
    fun `abort during a tool call cancels executeTool`() = run {
        val core = core(FakeTurnScript(listOf(listOf(FakeStep.ToolUse("slow", id = "t1")), listOf(FakeStep.Text("never")))))
        core.start()
        val session = core.openSession("s1", config)
        val host = RecordingTurnHost(tools = mapOf("slow" to RecordingTurnHost.HANGING_TOOL))

        val turn = async { session.runTurn(TurnInput("go"), host) }
        while ("execute:t1" !in host.calls) delay(1)
        session.abort()

        assertEquals(TurnOutcome.Aborted, turn.await())
        assertTrue("cancelled:t1" in host.calls, "executeTool coroutine was cancelled: ${host.calls}")
        assertTrue("after:t1" !in host.calls)
        val roles = roles(session.messages())
        assertEquals(listOf("system", "user", "assistant", "toolResult"), roles, "every toolCall has a toolResult")
        assertTrue("never" !in host.streamedText(), "no further model round after abort")
    }

    @Test
    fun `tool round limit stops the turn with ToolRoundLimit`() = run {
        val round = listOf(FakeStep.ToolUse("noop"))
        val core = core(FakeTurnScript(listOf(round, round, round, listOf(FakeStep.Text("unreachable")))))
        core.start()
        val session = core.openSession("s1", config.copy(maxToolRounds = 2))
        val host = RecordingTurnHost(tools = mapOf("noop" to { ToolResult.text("ok") }))

        val outcome = session.runTurn(TurnInput("loop"), host)

        assertEquals(TurnOutcome.ToolRoundLimit(rounds = 3, limit = 2), outcome)
        assertEquals(2, host.calls.count { it.startsWith("execute:") })
        val messages = session.messages().json.map { it.jsonObject }
        val calls = messages.filter { it["role"]!!.jsonPrimitive.content == "assistant" }
            .flatMap { m -> m["content"]!!.jsonArray.map { it.jsonObject }.filter { it["type"]!!.jsonPrimitive.content == "toolCall" } }
            .map { it["id"]!!.jsonPrimitive.content }
        val results = messages.filter { it["role"]!!.jsonPrimitive.content == "toolResult" }.map { it["toolCallId"]!!.jsonPrimitive.content }
        assertEquals(calls, results, "messages stay valid for the next turn")
    }

    @Test
    fun `model failure is returned as Failed with the classified error`() = run {
        val core = core(FakeTurnScript(FakeStep.Text("hm"), FakeStep.Fail(ErrorCode.MODEL_RATE_LIMITED.info("429 from provider"))))
        core.start()
        val session = core.openSession("s1", config)
        val host = RecordingTurnHost()

        val outcome = session.runTurn(TurnInput("hi"), host)

        assertIs<TurnOutcome.Failed>(outcome)
        assertEquals(ErrorCode.MODEL_RATE_LIMITED, outcome.error.code)
        assertTrue(outcome.error.retryable)
        assertEquals(EventTypes.AGENT_END, host.eventTypes().last())
    }

    @Test
    fun `restore from saved messages rebuilds the same context`() = run {
        val core = core(FakeTurnScript(FakeStep.Text("first")), FakeTurnScript(FakeStep.Text("second")))
        core.start()
        val original = core.openSession("s1", config)
        original.runTurn(TurnInput("one"), RecordingTurnHost())
        val saved = original.messages()
        original.dispose()

        val restored = core.openSession("s1", config, restore = saved)
        assertEquals(saved, restored.messages(), "restore is verbatim; system message is not duplicated")
        restored.runTurn(TurnInput("two"), RecordingTurnHost())
        assertEquals(listOf("system", "user", "assistant", "user", "assistant"), roles(restored.messages()))
    }

    @Test
    fun `pump failure returns CoreLost and the instance becomes unusable`() = run {
        val core = core(FakeTurnScript(FakeStep.Text("x"), FakeStep.AwaitAbort))
        core.start()
        val session = core.openSession("s1", config)
        val saved = session.messages()
        val host = RecordingTurnHost()

        val turn = async { session.runTurn(TurnInput("go"), host) }
        while (host.streamedText() != "x") delay(1)
        val before = host.events.size
        core.crash()

        val outcome = turn.await()
        assertIs<TurnOutcome.CoreLost>(outcome)
        assertEquals(ErrorCode.AGENT_CORE_FAILED, outcome.error.code)
        assertEquals(before, host.events.size, "no events after the pump died")
        assertIs<AgentCoreState.Failed>(core.state.value)
        assertFailsWith<AgentCoreUnavailableException> { core.openSession("s2", config) }
        assertFailsWith<AgentCoreUnavailableException> { session.messages() }

        // 宿主层用工厂新建实例，按 Store 里保存的 messages 重建
        val replacement = FakeAgentCore.factory().create()
        replacement.start()
        val rebuilt = replacement.openSession("s1", config, restore = saved)
        assertEquals(saved, rebuilt.messages())
    }

    @Test
    fun `crash step in a script behaves like a pump failure`() = run {
        val core = core(FakeTurnScript(FakeStep.CrashCore()))
        core.start()
        val outcome = core.openSession("s1", config).runTurn(TurnInput("boom"), RecordingTurnHost())
        assertIs<TurnOutcome.CoreLost>(outcome)
        assertIs<AgentCoreState.Failed>(core.state.value)
    }

    @Test
    fun `one turn per session, sessions run concurrently`() = run {
        val core = FakeAgentCore(FakeScripts.always(FakeTurnScript(FakeStep.Text("zzz", intervalMs = 20), FakeStep.Text("done"))))
        core.start()
        val a = core.openSession("a", config)
        val b = core.openSession("b", config)
        val hostA = RecordingTurnHost()
        val hostB = RecordingTurnHost()

        val turnA = async { a.runTurn(TurnInput("a"), hostA) }
        val turnB = async { b.runTurn(TurnInput("b"), hostB) }
        while (hostA.events.isEmpty()) delay(1)
        assertFailsWith<IllegalStateException> { a.runTurn(TurnInput("again"), RecordingTurnHost()) }

        assertIs<TurnOutcome.Finished>(turnA.await())
        assertIs<TurnOutcome.Finished>(turnB.await())
        assertEquals("zzzdone", hostA.streamedText())
        assertEquals("zzzdone", hostB.streamedText())
        assertFailsWith<IllegalStateException> { core.openSession("a", config) }
    }

    @Test
    fun `prompt directives script the fake from plain ACP clients`() = run {
        val core = FakeAgentCore(FakeScripts.directives())
        core.start()
        val session = core.openSession("s1", config)

        val host = RecordingTurnHost(tools = mapOf("add" to { ToolResult.text("3") }))
        val prompt = """{"fake": {"chunks": 3, "chunkChars": 2, "text": "ab", "tools": [{"name": "add", "arguments": {"a": 1}}]}}"""
        assertIs<TurnOutcome.Finished>(session.runTurn(TurnInput(prompt), host))
        assertEquals("ababab" + FakeTurnScript.FINAL_TEXT, host.streamedText())
        assertEquals(1, host.calls.count { it.startsWith("execute:") })

        val echo = RecordingTurnHost()
        session.runTurn(TurnInput("plain text"), echo)
        assertEquals("echo: plain text", echo.streamedText())

        val failing = session.runTurn(TurnInput("""{"fake": {"fail": "model_auth_failed"}}"""), RecordingTurnHost())
        assertIs<TurnOutcome.Failed>(failing)
        assertEquals(ErrorCode.MODEL_AUTH_FAILED, failing.error.code)
    }
}
