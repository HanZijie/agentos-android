package org.agentos.runtime.pi.testing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
 * B8 handles that in core/pi-runtime (src/compat.js, src/think-tags.js); the variants cover both
 * ways and the default:
 * - template: the catalog's custom-endpoint template as is. On a MiniMax host pi-agent.js adds
 *   reasoning_split: true by itself; on other hosts a leading <think> is split into thinking;
 * - reasoning_split: the flag set explicitly (agentosExtraBody), so reasoning arrives in
 *   reasoning_content on any host (the fake endpoint honours it like MiniMax);
 * - think-tags fallback: reasoning_split: false, so MiniMax (and the fake endpoint) answer with an
 *   inline <think>…</think> that the response-side fallback must turn into thinking.
 */
object OpenAiCompatibleTargets {

    const val DEFAULT_MODEL: String = "MiniMax-M2.7"

    /** The fake endpoint answers like MiniMax M2.x for model ids containing this. */
    const val FAKE_THINK_TAGS_MODEL: String = "fake-think-tags"

    private fun withExtraBody(template: CatalogModel, reasoningSplit: Boolean) = CatalogModel(
        JsonObject(template.json + ("compat" to buildJsonObject { put("agentosExtraBody", buildJsonObject { put("reasoning_split", reasoningSplit) }) })),
    )

    fun targets(catalog: ModelCatalog, baseUrl: String, modelId: String = DEFAULT_MODEL): List<Pair<String, CatalogModel>> {
        val template = catalog.customModel("openai-completions", modelId, baseUrl)
        return listOf(
            "openai-compatible (template)" to template,
            "openai-compatible (reasoning_split)" to withExtraBody(template, reasoningSplit = true),
            "openai-compatible (think-tags fallback)" to withExtraBody(template, reasoningSplit = false),
        )
    }
}
