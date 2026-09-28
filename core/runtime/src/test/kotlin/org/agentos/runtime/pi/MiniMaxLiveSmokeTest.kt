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
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke test against the real MiniMax endpoints. Runs only when MINIMAX_API_KEY is set
 * (never in CI); the key is read from the environment and never printed.
 *
 *   set -a; . <path outside the repo>/minimax.env; set +a
 *   ./gradlew :core:runtime:test --tests '*MiniMaxLiveSmokeTest*' --rerun
 *
 * Optional MINIMAX_ANTHROPIC_BASE_URL (for example https://api.minimax.cn/anthropic) adds the
 * "custom compatible endpoint" path, once with the template default (reasoning: false) and
 * once with reasoning: true, to check how M2.7 thinking blocks are handled.
 */
class MiniMaxLiveSmokeTest {

    private class Target(val name: String, val model: CatalogModel)

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

        val failures = mutableListOf<String>()
        for (t in targets) {
            val h = PiHarness(listOf(t.model.baseUrl to key!!))
            h.runtime.start(bundle!!)
            val report = StringBuilder("LIVE ${t.name} (${t.model.baseUrl}, reasoning=${t.model.reasoning}):")
            try {
                // 1. streamed chat
                val s1 = h.session(t.model)
                val t0 = System.currentTimeMillis()
                val chat = h.runtime.prompt(s1, "Reply with the single word: pong")
                val firstDelta = (h.deltas(s1, "thinking_delta") + h.deltas(s1)).minOfOrNull { it.at }?.minus(t0)
                report.append(" chat=${chat.stopReason} text=${chat.text.trim().take(40)} textDeltas=${h.deltas(s1).size} thinkingDeltas=${h.deltas(s1, "thinking_delta").size} firstDeltaMs=$firstDelta")
                if (chat.stopReason != "stop" || !chat.text.contains("pong", ignoreCase = true)) failures += "${t.name}: chat ${chat.stopReason} ${chat.errorMessage?.take(200)}"

                // 2. context across turns: the second request replays turn 1 including its thinking block
                val recall = h.runtime.prompt(s1, "What single word did you reply with in your previous message? Answer with that word only.")
                report.append(" recall=${recall.stopReason}/${recall.text.trim().take(20)}")
                if (recall.stopReason != "stop" || !recall.text.contains("pong", ignoreCase = true)) failures += "${t.name}: recall ${recall.stopReason} ${recall.errorMessage?.take(200)}"
                val thinkingBlocks = h.runtime.history(s1).count { m -> (m.jsonObject["content"] as? JsonArray)?.any { (it as JsonObject)["type"]?.jsonPrimitive?.content == "thinking" } == true }
                report.append(" assistantMsgsWithThinking=$thinkingBlocks")

                // 3. tool call: thinking + tool_use, then the tool result goes back with the thinking block
                val s2 = h.session(t.model)
                val tool = h.runtime.prompt(s2, "Use the add tool to add 2 and 3. Then reply with only the result.")
                val end = h.eventsFor(s2).firstOrNull { it.type == "tool_execution_end" }?.e
                val toolText = end?.get("result")?.jsonObject?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                report.append(" tool=${tool.stopReason} toolResult=$toolText text=${tool.text.trim().take(20)}")
                if (tool.stopReason != "stop" || toolText != "5" || !tool.text.contains("5")) failures += "${t.name}: tool ${tool.stopReason} $toolText ${tool.errorMessage?.take(200)}"

                // 4. abort mid-stream
                val s3 = h.session(t.model)
                val aborted = coroutineScope {
                    val p = async { h.runtime.prompt(s3, "Count from 1 to 300, one number per line, no other text.") }
                    h.awaitDelta(s3, 60_000)
                    h.runtime.abort(s3)
                    withTimeout(10_000) { p.await() }
                }
                delay(300)
                report.append(" abort=${aborted.stopReason} inflightAfter=${h.runtime.inflightHostFetches}")
                if (aborted.stopReason != "aborted" || h.runtime.inflightHostFetches != 0) failures += "${t.name}: abort ${aborted.stopReason}"

                report.append(" keyLeaks=${h.keyLeaks.size} requests=${h.fetch.started}")
                if (h.keyLeaks.isNotEmpty()) failures += "${t.name}: key flowed into JS"
            } finally {
                h.runtime.close()
            }
            println(report)
        }
        assertTrue(targets.isNotEmpty())
        assertEquals(emptyList(), failures)
    }
}
