package org.agentos.app.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import org.agentos.runtime.events.AgentEvent
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.ports.AgentCoreState
import org.agentos.runtime.ports.AgentSessionConfig
import org.agentos.runtime.ports.FinishReason
import org.agentos.runtime.ports.ModelSpec
import org.agentos.runtime.ports.ToolCall
import org.agentos.runtime.ports.ToolCallDecision
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.TurnHost
import org.agentos.runtime.ports.TurnInput
import org.agentos.runtime.ports.TurnOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

/** 占位 Agent core 遵守 ports/AgentCore.kt 的契约（与 testFixtures 的 FakeAgentCore 同样的事件顺序）。 */
class ScriptedAgentCoreTest {
    private val config = AgentSessionConfig(
        ModelSpec(buildJsonObject { put("id", "m"); put("api", "openai-completions"); put("provider", "custom") }),
        systemPrompt = "you are a test",
    )

    private class Recorder : TurnHost {
        val events: MutableList<AgentEvent> = Collections.synchronizedList(mutableListOf())
        override fun onEvent(event: AgentEvent) {
            events += event
        }
        override suspend fun beforeToolCall(call: ToolCall) = ToolCallDecision.Allow
        override suspend fun executeTool(call: ToolCall) = ToolResult.text("no tools", isError = true)
        override suspend fun afterToolCall(call: ToolCall, result: ToolResult): ToolResult? = null

        fun text(): String = events.filterIsInstance<AgentEvent.MessageUpdate>()
            .filter { it.update.kind == "text_delta" }.joinToString("") { it.update.delta.orEmpty() }

        fun types(): List<String> = events.map { e -> if (e is AgentEvent.MessageUpdate) "update:${e.update.kind}" else e.type }
    }

    @Test
    fun streamsScriptInPiOrder() = runBlocking {
        val core = ScriptedAgentCore().create()
        core.start()
        assertEquals(AgentCoreState.Ready, core.state.value)
        val s = core.openSession("ses_1", config)
        val host = Recorder()
        val outcome = s.runTurn(TurnInput("""{"chunks":5,"intervalMs":0}"""), host)
        assertEquals(TurnOutcome.Finished(FinishReason.END_TURN), outcome)
        assertEquals("chunk 0 chunk 1 chunk 2 chunk 3 chunk 4 ", host.text())
        val t = host.types()
        assertEquals(
            listOf(EventTypes.AGENT_START, EventTypes.TURN_START, EventTypes.MESSAGE_START, EventTypes.MESSAGE_END, EventTypes.MESSAGE_START,
                "update:start", "update:text_start"),
            t.take(7),
        )
        assertEquals(listOf("update:text_end", "update:done", EventTypes.MESSAGE_END, EventTypes.TURN_END, EventTypes.AGENT_END), t.takeLast(5))
        val end = host.events.last { it is AgentEvent.MessageEnd } as AgentEvent.MessageEnd
        assertEquals("assistant", end.role)
        assertEquals("stop", end.message["stopReason"]!!.jsonPrimitive.content)
        // messages：system + user + assistant，可以重建
        val msgs = s.messages()
        assertEquals(listOf("system", "user", "assistant"), msgs.json.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        s.dispose()
        val restored = core.openSession("ses_1", config, msgs)
        restored.runTurn(TurnInput("hello"), Recorder())
        assertEquals(5, restored.messages().size)
    }

    @Test
    fun scripts() = runBlocking {
        val core = ScriptedAgentCore().create().also { it.start() }
        val s = core.openSession("s", config)
        val cjk = Recorder()
        s.runTurn(TurnInput("""{"chunks":3,"chunkChars":4,"intervalMs":0,"cjk":true}"""), cjk)
        assertEquals("中".repeat(12), cjk.text())
        val big = Recorder()
        s.runTurn(TurnInput("""{"chunks":2,"intervalMs":0,"bigChunkChars":70000}"""), big)
        assertEquals("chunk 0 chunk 1 ".length + 70_000, big.text().length)
        // 保存进 messages 的文字有上限，流式输出不截断
        val saved = (s.messages().json.last().jsonObject["content"]!!.jsonArray[0] as JsonObject)["text"]!!.jsonPrimitive.content
        assertTrue(saved.length < 70_000)
        val echo = Recorder()
        s.runTurn(TurnInput("not json"), echo)
        assertEquals(20, echo.text().lines().count { it.startsWith("echo[") })
    }

    @Test
    fun abortEndsTheTurnAsAborted() = runBlocking {
        val core = ScriptedAgentCore().create().also { it.start() }
        val s = core.openSession("s", config)
        val host = Recorder()
        val turn = async { s.runTurn(TurnInput("""{"chunks":1000000,"intervalMs":20}"""), host) }
        while (host.text().length < 20) delay(5)
        // 同一会话同时只能有一轮
        try {
            s.runTurn(TurnInput("x"), Recorder())
            fail("second concurrent turn must be rejected")
        } catch (e: IllegalStateException) {
            // expected
        }
        s.abort()
        val outcome = withTimeout(2_000) { turn.await() }
        assertEquals(TurnOutcome.Aborted, outcome)
        val end = host.events.last { it is AgentEvent.MessageEnd } as AgentEvent.MessageEnd
        assertEquals("aborted", (end.message["stopReason"] as JsonPrimitive).content)
        assertEquals(EventTypes.AGENT_END, host.events.last().type)
        // abort 之后下一轮照常
        assertEquals(TurnOutcome.Finished(FinishReason.END_TURN), s.runTurn(TurnInput("""{"chunks":1,"intervalMs":0}"""), Recorder()))
    }

    /** keyEvery：每段前取一次 key；key 被清除后下一段取不到，本轮以 model_not_configured 结束，消息里没有 key。 */
    @Test
    fun keyEveryStopsTheTurnOnceTheKeyIsGone() = runBlocking {
        val secrets = KeystoreSecrets(SoftwareCipher()) { false }
        val base = "https://gw.example.com/v1"
        secrets.activate("sk-scripted-SECRET-000000", listOf(base))
        val cfg = config.copy(model = ModelSpec(buildJsonObject { put("id", "m"); put("api", "openai-completions"); put("baseUrl", base) }))
        val core = ScriptedAgentCore(secrets).create().also { it.start() }
        val s = core.openSession("s", cfg)
        val host = Recorder()
        val turn = async { s.runTurn(TurnInput("""{"chunks":1000000,"intervalMs":10,"keyEvery":5}"""), host) }
        while (host.text().length < 100) delay(5)
        secrets.revoke()
        val outcome = withTimeout(2_000) { turn.await() }
        assertTrue(outcome is TurnOutcome.Failed)
        val error = (outcome as TurnOutcome.Failed).error
        assertEquals(org.agentos.runtime.errors.ErrorCode.MODEL_NOT_CONFIGURED, error.code)
        assertTrue(!error.message.contains("SECRET"))
        val end = host.events.last { it is AgentEvent.MessageEnd } as AgentEvent.MessageEnd
        assertEquals("error", end.message["stopReason"]!!.jsonPrimitive.content)
        assertEquals(EventTypes.AGENT_END, host.events.last().type)
        // 没有 key 时一开始就失败；没有 keyEvery 的脚本不取 key
        assertTrue(s.runTurn(TurnInput("""{"chunks":3,"intervalMs":0,"keyEvery":1}"""), Recorder()) is TurnOutcome.Failed)
        assertEquals(TurnOutcome.Finished(FinishReason.END_TURN), s.runTurn(TurnInput("""{"chunks":3,"intervalMs":0}"""), Recorder()))
    }

    @Test
    fun cancellingTheCallerPropagates() = runBlocking {
        val core = ScriptedAgentCore().create().also { it.start() }
        val s = core.openSession("s", config)
        val host = Recorder()
        val turn = async { s.runTurn(TurnInput("""{"chunks":1000000,"intervalMs":20}"""), host) }
        while (host.text().isEmpty()) delay(5)
        turn.cancel()
        try {
            turn.await()
            fail("expected cancellation")
        } catch (e: CancellationException) {
            // expected
        }
        // 会话可以继续
        assertEquals(TurnOutcome.Finished(FinishReason.END_TURN), s.runTurn(TurnInput("""{"chunks":1,"intervalMs":0}"""), Recorder()))
        core.close()
        assertEquals(AgentCoreState.Closed, core.state.value)
    }
}
