package org.agentos.runtime.pi

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.agentos.runtime.pi.testing.FakeModelServer
import org.agentos.runtime.pi.testing.OpenAiCompatibleTargets
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Smoke test against the real MiniMax endpoints. Runs only when MINIMAX_API_KEY is set
 * (never in CI); the key is read from the environment and never printed.
 *
 *   set -a; . <path outside the repo>/minimax.env; set +a
 *   ./gradlew :core:runtime:test --tests '*MiniMaxLiveSmokeTest*' --rerun
 *
 * Optional targets:
 * - MINIMAX_ANTHROPIC_BASE_URL (for example https://api.minimax.cn/anthropic): the "custom
 *   compatible endpoint" path on anthropic-messages, with the template default (reasoning: false)
 *   and with reasoning: true;
 * - MINIMAX_OPENAI_BASE_URL (for example https://api.minimax.cn/v1): the OpenAI Chat Completions
 *   route (openai-completions), model MINIMAX_OPENAI_MODEL (default MiniMax-M2.7), once with the
 *   template and once with [OpenAiCompatibleTargets.MINIMAX_COMPAT].
 *
 * Results are printed per target ("LIVE ..." lines in the test's standard output).
 * `openai-compatible route runs against the fake endpoint` runs the same steps without a key.
 */
class MiniMaxLiveSmokeTest {

    private class Target(val name: String, val model: CatalogModel)

    /** Prompts per step; the fake endpoint needs its directives to play a tool call and a slow stream. */
    private class Prompts(val chat: String, val recall: String, val tool: String, val abort: String)

    private val live = Prompts(
        chat = "Reply with the single word: pong",
        recall = "What single word did you reply with in your previous message? Answer with that word only.",
        tool = "Use the add tool to add 2 and 3. Then reply with only the result.",
        abort = "Count from 1 to 300, one number per line, no other text.",
    )

    private val fake = Prompts(
        chat = "Reply with the single word: pong",
        recall = "Recall check: pong",
        tool = "[tool:add 2 3] Use the add tool to add 2 and 3. Then reply with only the result.",
        abort = "[slow] Count from 1 to 300, one number per line, no other text.",
    )

    /** Chat, recall across turns, a tool call and an abort; returns the report line and the failures. */
    private suspend fun runTarget(h: PiHarness, t: Target, p: Prompts): Pair<String, List<String>> {
        val failures = mutableListOf<String>()
        val report = StringBuilder("LIVE ${t.name} (${t.model.baseUrl}, api=${t.model.json["api"]?.jsonPrimitive?.content}, reasoning=${t.model.reasoning}):")
        // 1. streamed chat
        val s1 = h.session(t.model)
        val t0 = System.currentTimeMillis()
        val chat = h.runtime.prompt(s1, p.chat)
        val firstDelta = (h.deltas(s1, "thinking_delta") + h.deltas(s1)).minOfOrNull { it.at }?.minus(t0)
        // MiniMax's OpenAI-compatible API puts M2.x thinking inline as <think>...</think> unless
        // the request sets reasoning_split, which pi-ai cannot send: report it.
        val inlineThink = chat.text.contains("<think>")
        report.append(" chat=${chat.stopReason} text=${chat.text.trim().takeLast(40).replace('\n', ' ')} textDeltas=${h.deltas(s1).size} thinkingDeltas=${h.deltas(s1, "thinking_delta").size} inlineThink=$inlineThink firstDeltaMs=$firstDelta")
        if (chat.stopReason != "stop" || !chat.text.contains("pong", ignoreCase = true)) failures += "${t.name}: chat ${chat.stopReason} ${chat.errorMessage?.take(200)}"

        // 2. context across turns: the second request replays turn 1 including its thinking
        val recall = h.runtime.prompt(s1, p.recall)
        report.append(" recall=${recall.stopReason}/${recall.text.trim().takeLast(20).replace('\n', ' ')}")
        if (recall.stopReason != "stop" || !recall.text.contains("pong", ignoreCase = true)) failures += "${t.name}: recall ${recall.stopReason} ${recall.errorMessage?.take(200)}"
        val thinkingBlocks = h.runtime.history(s1).count { m -> (m.jsonObject["content"] as? JsonArray)?.any { (it as JsonObject)["type"]?.jsonPrimitive?.content == "thinking" } == true }
        report.append(" assistantMsgsWithThinking=$thinkingBlocks")

        // 3. tool call, then the tool result goes back
        val s2 = h.session(t.model)
        val tool = h.runtime.prompt(s2, p.tool)
        val end = h.eventsFor(s2).firstOrNull { it.type == "tool_execution_end" }?.e
        val toolText = end?.get("result")?.jsonObject?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
        report.append(" tool=${tool.stopReason} toolResult=$toolText text=${tool.text.trim().takeLast(20).replace('\n', ' ')}")
        if (tool.stopReason != "stop" || toolText != "5" || !tool.text.contains("5")) failures += "${t.name}: tool ${tool.stopReason} $toolText ${tool.errorMessage?.take(200)}"

        // 4. abort mid-stream
        val s3 = h.session(t.model)
        val aborted = coroutineScope {
            val pr = async { h.runtime.prompt(s3, p.abort) }
            h.awaitDelta(s3, 60_000)
            h.runtime.abort(s3)
            withTimeout(10_000) { pr.await() }
        }
        delay(300)
        report.append(" abort=${aborted.stopReason} inflightAfter=${h.runtime.inflightHostFetches}")
        if (aborted.stopReason != "aborted" || h.runtime.inflightHostFetches != 0) failures += "${t.name}: abort ${aborted.stopReason}"

        report.append(" keyLeaks=${h.keyLeaks.size} requests=${h.fetch.started}")
        if (h.keyLeaks.isNotEmpty()) failures += "${t.name}: key flowed into JS"
        return report.toString() to failures
    }

    @Test
    fun `MiniMax endpoints chat, call a tool, keep context and abort`() = runBlocking<Unit> {
        val key = System.getenv("MINIMAX_API_KEY")
        assumeTrue("MINIMAX_API_KEY not set", !key.isNullOrBlank())
        val bundle = PiAssets.bundle
        val catalog = PiAssets.catalog
        assumeTrue(PiAssets.MISSING, bundle != null && catalog != null)

        val targets = mutableListOf(Target("minimax-cn preset", catalog!!.model("minimax-cn", "MiniMax-M2.7")!!))
        System.getenv("MINIMAX_ANTHROPIC_BASE_URL")?.takeIf { it.isNotBlank() }?.let { base ->
            targets += Target("custom endpoint, template (reasoning=false)", catalog.customModel("anthropic-messages", "MiniMax-M2.7", base))
            targets += Target("custom endpoint, reasoning=true", catalog.customModel("anthropic-messages", "MiniMax-M2.7", base, reasoning = true))
        }
        System.getenv("MINIMAX_OPENAI_BASE_URL")?.takeIf { it.isNotBlank() }?.let { base ->
            val model = System.getenv("MINIMAX_OPENAI_MODEL")?.takeIf { it.isNotBlank() } ?: OpenAiCompatibleTargets.DEFAULT_MODEL
            OpenAiCompatibleTargets.targets(catalog, base, model).forEach { (name, m) -> targets += Target(name, m) }
        }

        val failures = mutableListOf<String>()
        for (t in targets) {
            val h = PiHarness(listOf(t.model.baseUrl to key!!))
            h.runtime.start(bundle!!)
            try {
                val (report, failed) = runTarget(h, t, live)
                println(report)
                failures += failed
            } finally {
                h.runtime.close()
            }
        }
        assertTrue(targets.isNotEmpty())
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `openai-compatible route runs against the fake endpoint`() = runBlocking<Unit> {
        val bundle = PiAssets.bundle
        val catalog = PiAssets.catalog
        assumeTrue(PiAssets.MISSING, bundle != null && catalog != null)
        FakeModelServer().use { server ->
            for ((name, model) in OpenAiCompatibleTargets.targets(catalog!!, server.openaiBaseUrl)) {
                val h = PiHarness(listOf(model.baseUrl to server.key))
                h.runtime.start(bundle!!)
                val n0 = server.requests.size
                try {
                    val (report, failed) = runTarget(h, Target(name, model), fake)
                    println(report)
                    assertEquals(emptyList(), failed)
                } finally {
                    h.runtime.close()
                }
                // What pi-ai 0.86.1 puts on the wire for each variant (see OpenAiCompatibleTargets).
                val bodies = server.requests.drop(n0).filter { it.api == "openai" }.map { it.body }
                val toolDefs = bodies.first()["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject }
                val toolResults = bodies.flatMap { b -> b["messages"]!!.jsonArray.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "tool" } }
                assertTrue(toolResults.isNotEmpty(), "$name: the tool result went back")
                assertTrue(bodies.all { it["stream"]?.jsonPrimitive?.content == "true" }, "$name: streaming")
                if (name.endsWith("(template)")) {
                    assertEquals("false", bodies.first()["store"]?.jsonPrimitive?.content, "$name: store")
                    assertTrue(toolDefs.all { "strict" in it }, "$name: strict")
                    assertTrue(toolResults.all { "name" !in it }, "$name: no name on tool results")
                } else {
                    assertNull(bodies.first()["store"], "$name: store")
                    assertTrue(toolDefs.none { "strict" in it }, "$name: strict")
                    assertTrue(toolResults.all { it["name"]?.jsonPrimitive?.content == "add" }, "$name: name on tool results")
                }
                // The template default (reasoning: false) keeps the plain system role and no reasoning_effort.
                assertTrue(bodies.all { b -> b["messages"]!!.jsonArray.none { it.jsonObject["role"]?.jsonPrimitive?.content == "developer" } }, name)
                assertFalse(bodies.any { "reasoning_effort" in it }, name)
                println("WIRE $name: keys=${bodies.first().keys.sorted()}")
            }
        }
    }
}
