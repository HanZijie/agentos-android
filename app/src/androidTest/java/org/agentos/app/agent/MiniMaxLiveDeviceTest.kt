package org.agentos.app.agent

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.events.AgentEvent
import org.agentos.runtime.pi.PiAdapter
import org.agentos.runtime.pi.testing.FakeModelServer
import org.agentos.runtime.pi.testing.OpenAiCompatibleTargets
import org.agentos.runtime.ports.AgentCoreFactory
import org.agentos.runtime.ports.AgentSessionConfig
import org.agentos.runtime.ports.ModelSpec
import org.agentos.runtime.ports.ToolDeclaration
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.TurnInput
import org.agentos.runtime.ports.TurnOutcome
import org.agentos.runtime.testing.FakeHostPort
import org.agentos.runtime.testing.RecordingTurnHost
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The real MiniMax endpoint (domestic platform) through the production factory in the `:agent`
 * process: a text turn, a tool call and an abort, for the `minimax-cn` preset and, when given,
 * the same model on another Anthropic-compatible base URL and on MiniMax's OpenAI-compatible
 * endpoint (openai-completions, template and [OpenAiCompatibleTargets.MINIMAX_COMPAT]).
 *
 * Credentials: the runner writes `MINIMAX_API_KEY=...` (and optionally
 * `MINIMAX_ANTHROPIC_BASE_URL=...`, `MINIMAX_OPENAI_BASE_URL=...`, `MINIMAX_OPENAI_MODEL=...`) into
 * the app's private `files/b3-live.env` through `run-as` and stdin, so the key is on no command
 * line; the test deletes the file first thing. Skipped without it. The key is never logged; the
 * test fails if it shows up in messages, events or logs. [openAiRouteAgainstFakeEndpoint] runs the
 * OpenAI route through the same code against the fake endpoint, without a key.
 */
class MiniMaxLiveDeviceTest {

    private val addSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("a", buildJsonObject { put("type", "integer") })
            put("b", buildJsonObject { put("type", "integer") })
        })
        put("required", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
    }

    private fun readCredentials(): Map<String, String>? {
        val file = File(Device.context.filesDir, "b3-live.env")
        if (!file.isFile) return null
        return try {
            file.readLines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim().removeSurrounding("\"")
            }.toMap()
        } finally {
            file.delete()
        }
    }

    /** Prompts per step; the fake endpoint needs its directives to play a tool call and a slow stream. */
    private class Prompts(val text: String, val tool: String, val abort: String)

    private val live = Prompts(
        text = "Reply with exactly one word: pong",
        tool = "Use the add tool to add 2 and 3, then reply with only the result.",
        abort = "Write the numbers from 1 to 300, one per line, nothing else.",
    )

    private val fake = Prompts(
        text = "Reply with exactly one word: pong",
        tool = "[tool:add 2 3] Use the add tool to add 2 and 3, then reply with only the result.",
        abort = "[slow] Write the numbers from 1 to 300, one per line, nothing else.",
    )

    private val tools = listOf(ToolDeclaration("add", "Add two integers and return the sum.", addSchema))

    /** A text turn, a tool call and an abort on one target; messages and events go to [seen] for the key check. */
    private suspend fun runTarget(factory: AgentCoreFactory, name: String, model: ModelSpec, p: Prompts, seen: StringBuilder) {
        val core = factory.create() as PiAdapter
        try {
            withTimeout(240_000) {
                core.start()
                val session = core.openSession("live", AgentSessionConfig(model, systemPrompt = "You are a terse test agent.", tools = tools))

                // 1. Text
                val textHost = RecordingTurnHost()
                val t0 = System.nanoTime()
                val firstDelta = async {
                    while (textHost.events.none { it is AgentEvent.MessageUpdate }) delay(5)
                    (System.nanoTime() - t0) / 1e6
                }
                val text = session.runTurn(TurnInput(p.text), textHost)
                assertIs<TurnOutcome.Finished>(text, "$name text: $text")
                assertTrue("pong" in textHost.streamedText().lowercase(), "$name text: ${textHost.streamedText().takeLast(200)}")
                val firstMs = firstDelta.await()
                // MiniMax's OpenAI-compatible API puts M2.x thinking inline as <think>...</think>
                // unless the request sets reasoning_split (pi-ai cannot): report it.
                val inlineThink = "<think>" in textHost.streamedText()

                // 2. Tool call
                val toolHost = RecordingTurnHost(
                    tools = mapOf("add" to { call -> ToolResult.text((call.arguments["a"]!!.jsonPrimitive.int + call.arguments["b"]!!.jsonPrimitive.int).toString()) }),
                )
                val tool = session.runTurn(TurnInput(p.tool), toolHost)
                assertIs<TurnOutcome.Finished>(tool, "$name tool: $tool")
                assertTrue(toolHost.calls.any { it.startsWith("execute:") }, "$name tool calls: ${toolHost.calls}")
                assertTrue("5" in toolHost.streamedText(), "$name tool reply: ${toolHost.streamedText().takeLast(200)}")

                // 3. Abort while streaming
                val abortHost = RecordingTurnHost()
                val running = async { session.runTurn(TurnInput(p.abort), abortHost) }
                while (abortHost.events.none { it is AgentEvent.MessageUpdate }) delay(5)
                session.abort()
                assertEquals(TurnOutcome.Aborted, running.await(), name)

                seen.append(session.messages().json.toString())
                listOf(textHost, toolHost, abortHost).forEach { h -> synchronized(h.events) { h.events.forEach { seen.append(it.toString()) } } }
                Log.i(
                    Device.TAG,
                    "live $name: text ok (first delta ${"%.0f".format(firstMs)} ms, inlineThink=$inlineThink), " +
                        "tool ok (${toolHost.calls.count { it.startsWith("execute:") }} call), abort ok; startup ${core.startup}",
                )
            }
        } finally {
            core.close()
        }
    }

    @Test
    fun realEndpointTextToolAndAbort() = runBlocking<Unit> {
        val env = readCredentials()
        val key = env?.get("MINIMAX_API_KEY")
        assumeTrue("no live credentials (files/b3-live.env)", !key.isNullOrEmpty())
        key!!

        val catalog = PiAgentCores.loadCatalog(Device.context)
        val preset = catalog.model("minimax-cn", "MiniMax-M2.7")!!
        val presetBase = preset.json["baseUrl"]!!.jsonPrimitive.content
        val targets = buildList {
            add("minimax-cn" to preset.toModelSpec())
            env["MINIMAX_ANTHROPIC_BASE_URL"]?.takeIf { it.isNotEmpty() && it != presetBase }?.let { base ->
                add("minimax-cn@custom-base" to ModelSpec(JsonObject(preset.json + ("baseUrl" to JsonPrimitive(base)))))
            }
            // OpenAI Chat Completions route on MiniMax's OpenAI-compatible endpoint (B7).
            env["MINIMAX_OPENAI_BASE_URL"]?.takeIf { it.isNotEmpty() }?.let { base ->
                val modelId = env["MINIMAX_OPENAI_MODEL"]?.takeIf { it.isNotEmpty() } ?: OpenAiCompatibleTargets.DEFAULT_MODEL
                OpenAiCompatibleTargets.targets(catalog, base, modelId).forEach { (name, m) -> add(name to m.toModelSpec()) }
            }
        }
        val hostPort = FakeHostPort(credentials = targets.associate { (_, m) -> m.model["baseUrl"]!!.jsonPrimitive.content to key })
        val factory = PiAgentCores.create(Device.context, hostPort)
        val seen = StringBuilder()

        for ((name, model) in targets) runTarget(factory, name, model, live, seen)

        synchronized(hostPort.log.lines) { hostPort.log.lines.forEach { seen.append(it) } }
        assertFalse(key in seen.toString(), "the key appears in messages, events or logs")
        Log.i(Device.TAG, "live key leaks: 0 (checked ${seen.length} chars of messages, events and logs)")
    }

    /**
     * The OpenAI Chat Completions route of [realEndpointTextToolAndAbort] (both variants of
     * [OpenAiCompatibleTargets]) through the same code, against the in-process fake endpoint:
     * no key needed, runs on every device run.
     */
    @Test
    fun openAiRouteAgainstFakeEndpoint() = runBlocking<Unit> {
        FakeModelServer().use { server ->
            val catalog = PiAgentCores.loadCatalog(Device.context)
            val hostPort = FakeHostPort(credentials = mapOf(server.openaiBaseUrl to server.key))
            val factory = PiAgentCores.create(Device.context, hostPort)
            val seen = StringBuilder()
            for ((name, model) in OpenAiCompatibleTargets.targets(catalog, server.openaiBaseUrl)) {
                runTarget(factory, "fake $name", model.toModelSpec(), fake, seen)
            }
            assertTrue(server.requests.all { it.api == "openai" && it.presentedKeyKind == "real" }, "OpenAI route, key injected by HostFetch")
            synchronized(hostPort.log.lines) { hostPort.log.lines.forEach { seen.append(it) } }
            assertFalse(server.key in seen.toString(), "the key appears in messages, events or logs")
        }
    }
}
