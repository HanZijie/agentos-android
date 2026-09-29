package org.agentos.runtime.pi.testing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.pi.CatalogModel
import org.agentos.runtime.pi.ModelCatalog

/**
 * Targets for the live smoke tests on the OpenAI Chat Completions route (MiniMax's
 * OpenAI-compatible endpoint, for example https://api.minimax.cn/v1). Shared by the JVM test
 * (MiniMaxLiveSmokeTest) and the device test (MiniMaxLiveDeviceTest), and by their fake-endpoint
 * variants that run the same code path without a key.
 *
 * B7 showed MiniMax accepts what pi-ai 0.86.1 sends for an unknown OpenAI-compatible host (store,
 * strict, tool results without name), but returns M2.x reasoning inline as <think>…</think>.
 * B8 / B8.1: pi-agent.js turns a leading <think> into thinking on custom endpoints, and
 * [ModelCatalog.customModel] gives MiniMax hosts [ModelCatalog.MINIMAX_OPENAI_COMPAT]
 * (reasoning_split: true, inline reasoning replayed as <think>). The variants:
 * - template: [ModelCatalog.customModel] as is. On a MiniMax host that is the MiniMax compat; on
 *   other hosts (the fake endpoint) nothing extra: a leading <think> is split and not replayed;
 * - MiniMax compat: [ModelCatalog.MINIMAX_OPENAI_COMPAT] on any host, so reasoning arrives in
 *   reasoning_content (the fake endpoint honours reasoning_split like MiniMax);
 * - think-tags keep / drop: reasoning_split: false, so MiniMax (and the fake endpoint) answer with
 *   an inline <think>…</think> that the response-side fallback turns into thinking; later requests
 *   send it back as <think>…</think> at the start of the assistant text (keep) or leave it out (drop).
 */
object OpenAiCompatibleTargets {

    const val DEFAULT_MODEL: String = "MiniMax-M2.7"

    /** The fake endpoint answers like MiniMax M2.x for model ids containing this. */
    const val FAKE_THINK_TAGS_MODEL: String = "fake-think-tags"

    /**
     * One configuration of the route. What the requests carry when the host is not a MiniMax host
     * (the fake endpoint): [reasoningSplit] (null: no such key), and whether replayed assistant
     * messages carry the inline reasoning as <think>…</think> ([replaysThinkTags]).
     */
    data class Variant(val name: String, val model: CatalogModel, val reasoningSplit: Boolean?, val replaysThinkTags: Boolean)

    private fun fallback(replay: String) = buildJsonObject {
        put("agentosExtraBody", buildJsonObject { put("reasoning_split", false) })
        put("agentosThinkTagsReplay", replay)
    }

    fun targets(catalog: ModelCatalog, baseUrl: String, modelId: String = DEFAULT_MODEL): List<Variant> {
        val template = catalog.customModel("openai-completions", modelId, baseUrl)
        return listOf(
            Variant("openai-compatible (template)", template, reasoningSplit = null, replaysThinkTags = false),
            Variant("openai-compatible (MiniMax compat)", template.withCompat(ModelCatalog.MINIMAX_OPENAI_COMPAT), reasoningSplit = true, replaysThinkTags = false),
            Variant("openai-compatible (think-tags keep)", template.withCompat(fallback("keep")), reasoningSplit = false, replaysThinkTags = true),
            Variant("openai-compatible (think-tags drop)", template.withCompat(fallback("drop")), reasoningSplit = false, replaysThinkTags = false),
        )
    }

    /**
     * Checks the Chat Completions request [bodies] one variant sent to the fake endpoint (playing
     * MiniMax M2.x, [FAKE_THINK_TAGS_MODEL]); returns the failures. Every fake answer starts with
     * reasoning, so with keep every replayed assistant message starts with <think>…</think>, and
     * without it none has a think tag in its text.
     */
    fun checkFakeWire(v: Variant, bodies: List<JsonObject>): List<String> {
        val failures = mutableListOf<String>()
        val split = bodies.map { (it["reasoning_split"] as? JsonPrimitive)?.content }.toSet()
        val expected = setOf(v.reasoningSplit?.toString())
        if (split != expected) failures += "${v.name}: reasoning_split in the requests $split, expected $expected"
        val replayed = bodies.flatMap { b ->
            b["messages"]!!.jsonArray.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "assistant" }
        }
        if (replayed.isEmpty()) failures += "${v.name}: later requests do not replay the earlier turns"
        val texts = replayed.map { (it["content"] as? JsonPrimitive)?.content.orEmpty() }
        if (v.replaysThinkTags) {
            val bad = texts.filterNot { it.startsWith("<think>") && "</think>" in it }
            if (bad.isNotEmpty()) failures += "${v.name}: replayed assistant text without its <think> part: $bad"
        } else {
            val bad = replayed.filter { "think>" in it["content"].toString() }
            if (bad.isNotEmpty()) failures += "${v.name}: reasoning replayed as assistant text: $bad"
        }
        // With reasoning_split the reasoning goes back in its own field, never in the text.
        if (v.reasoningSplit == true && replayed.none { "reasoning_content" in it || "reasoning_details" in it }) {
            failures += "${v.name}: reasoning_content not replayed"
        }
        return failures
    }
}
