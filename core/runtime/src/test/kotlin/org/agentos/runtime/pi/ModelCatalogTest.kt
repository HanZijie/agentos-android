package org.agentos.runtime.pi

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelCatalogTest {

    private val sample = """
        {"schemaVersion":1,"generatedFrom":{"@earendil-works/pi-ai":"0.86.1"},
         "apis":["anthropic-messages","openai-completions"],
         "customTemplates":{
           "anthropic-messages":{"api":"anthropic-messages","provider":"custom","reasoning":false,"input":["text","image"],"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0},"contextWindow":128000,"maxTokens":8192},
           "openai-completions":{"api":"openai-completions","provider":"custom","reasoning":false,"input":["text","image"],"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0},"contextWindow":128000,"maxTokens":8192}},
         "providers":[
           {"id":"minimax","name":"MiniMax","apis":["anthropic-messages"],"baseUrls":["https://api.minimax.io/anthropic"],
            "auth":{"type":"api-key","label":"MiniMax API key"},
            "models":[{"id":"MiniMax-M2.7","name":"MiniMax-M2.7","api":"anthropic-messages","provider":"minimax","baseUrl":"https://api.minimax.io/anthropic","reasoning":true,"input":["text"],"cost":{"input":0.3,"output":1.2,"cacheRead":0.06,"cacheWrite":0.375},"contextWindow":204800,"maxTokens":131072,"compat":{"x":1}}]}]}
    """.trimIndent()

    @Test
    fun `parses providers and keeps model objects intact`() {
        val c = ModelCatalog.parse(sample)
        assertEquals(1, c.schemaVersion)
        assertEquals("0.86.1", c.piAiVersion)
        val p = c.providers.single()
        assertEquals("MiniMax API key", p.keyLabel)
        val m = assertNotNull(c.model("minimax", "MiniMax-M2.7"))
        assertEquals("anthropic-messages", m.api)
        assertEquals(204800, m.contextWindow)
        assertEquals(true, m.reasoning)
        assertEquals("""{"x":1}""", m.json["compat"].toString(), "unknown fields are kept")
    }

    @Test
    fun `custom endpoint models come from the protocol family template`() {
        val c = ModelCatalog.parse(sample)
        val m = c.customModel("openai-completions", "qwen-max", "https://gateway.example.com/v1/", contextWindow = 32000)
        assertEquals("qwen-max", m.id)
        assertEquals("custom", m.provider)
        assertEquals("https://gateway.example.com/v1", m.baseUrl)
        assertEquals(32000, m.contextWindow)
        assertEquals(8192, m.maxTokens)
        assertEquals("openai-completions", m.json["api"]!!.jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { c.customModel("google-generative-ai", "x", "https://x.example.com") }
        assertFailsWith<IllegalArgumentException> { c.customModel("openai-completions", "x", "http://gateway.example.com/v1") }
        assertFailsWith<IllegalArgumentException> { c.customModel("openai-completions", " ", "https://gateway.example.com/v1") }
    }

    @Test
    fun `unknown schema version is refused`() {
        assertFailsWith<IllegalArgumentException> { ModelCatalog.parse(sample.replace("\"schemaVersion\":1", "\"schemaVersion\":2")) }
    }

    @Test
    fun `generated catalog lists MiniMax international and China first`() {
        val file = File(System.getProperty("agentos.piAssetsDir").orEmpty(), "model-catalog.json")
        assumeTrue("run `node build.mjs` in core/pi-runtime to generate ${file.path}", file.isFile)
        val c = ModelCatalog.parse(file.readText())
        assertEquals(listOf("minimax", "minimax-cn"), c.providers.take(2).map { it.id })
        assertEquals("https://api.minimax.io/anthropic", c.provider("minimax")!!.baseUrls.single())
        assertEquals("https://api.minimaxi.com/anthropic", c.provider("minimax-cn")!!.baseUrls.single())
        assertTrue(c.providers.all { p -> p.models.all { it.api in c.apis } })
        assertTrue(c.provider("github-copilot") == null, "subscription providers are excluded")
    }
}
