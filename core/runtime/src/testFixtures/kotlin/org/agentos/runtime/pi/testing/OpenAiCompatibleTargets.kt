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
 */
object OpenAiCompatibleTargets {

    const val DEFAULT_MODEL: String = "MiniMax-M2.7"

    /**
     * pi-ai compat overrides worth trying against MiniMax (pi-ai 0.86.1 treats an unknown base URL
     * as standard OpenAI): no `store`, no `developer` role, `name` on tool results, no `strict` on
     * tool definitions. Compared with the plain template in one live run, it shows which of these
     * the provider needs.
     */
    val MINIMAX_COMPAT: JsonObject = buildJsonObject {
        put("supportsStore", false)
        put("supportsDeveloperRole", false)
        put("requiresToolResultName", true)
        put("supportsStrictMode", false)
    }

    /** The catalog's custom-endpoint template as is, and the same with [MINIMAX_COMPAT]. */
    fun targets(catalog: ModelCatalog, baseUrl: String, modelId: String = DEFAULT_MODEL): List<Pair<String, CatalogModel>> {
        val template = catalog.customModel("openai-completions", modelId, baseUrl)
        val compat = CatalogModel(JsonObject(template.json + ("compat" to MINIMAX_COMPAT)))
        return listOf(
            "openai-compatible (template)" to template,
            "openai-compatible (minimax compat)" to compat,
        )
    }
}
