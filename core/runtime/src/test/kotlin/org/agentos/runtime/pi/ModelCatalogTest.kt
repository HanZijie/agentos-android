package org.agentos.runtime.pi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
    fun `custom endpoints may use http only on loopback`() {
        val c = ModelCatalog.parse(sample)
        for (url in listOf("http://127.0.0.1:11434/v1", "http://localhost:8080/v1", "http://[::1]:8080/v1")) {
            assertEquals(url, c.customModel("openai-completions", "local", url).baseUrl)
        }
        for (url in listOf("http://10.0.2.2:8080/v1", "http://192.168.1.20:11434/v1", "http://127.0.0.2/v1", "http://127.example.com/v1", "http://localhost.example.com/v1")) {
            assertFailsWith<IllegalArgumentException>(url) { c.customModel("openai-completions", "lan", url) }
        }
    }

    @Test
    fun `unknown schema version is refused`() {
        assertFailsWith<IllegalArgumentException> { ModelCatalog.parse(sample.replace("\"schemaVersion\":1", "\"schemaVersion\":2")) }
    }

    @Test
    fun `MiniMax OpenAI hosts get reasoning_split and keep-replay, matched by exact host`() {
        val c = ModelCatalog.parse(sample)
        val expected = """{"agentosExtraBody":{"reasoning_split":true},"agentosThinkTagsReplay":"keep"}"""
        for (url in listOf(
            "https://api.minimax.io/v1", "https://api.minimaxi.com/v1", "https://api.minimax.cn/v1",
            "https://API.MiniMax.CN/v1/", "https://api.minimax.cn./v1", "https://api.minimax.io:443/v1", " https://api.minimaxi.com/v1 ",
        )) {
            assertEquals(expected, c.customModel("openai-completions", "MiniMax-M2.7", url).json["compat"].toString(), url)
        }
        for (url in listOf(
            "https://minimax.io/v1", "https://evil-minimax.cn/v1", "https://api.minimax.cn.evil.com/v1", "https://x.api.minimax.cn/v1",
            "https://api-minimax.cn/v1", "https://gateway.example.com/api.minimax.cn/v1",
            "https://api.deepseek.com/v1", "http://127.0.0.1:8080/v1",
        )) {
            assertEquals(null, c.customModel("openai-completions", "m", url).json["compat"], url)
        }
        // user info is refused outright (Endpoint.parse), never matched as a MiniMax host
        for (url in listOf("https://user@api.minimax.cn/v1", "https://api.minimax.cn@evil.com/v1")) {
            assertFailsWith<IllegalArgumentException>(url) { c.customModel("openai-completions", "m", url) }
        }
        // the Anthropic route is untouched (catalog presets and custom anthropic-messages)
        assertEquals(null, c.customModel("anthropic-messages", "MiniMax-M2.7", "https://api.minimax.cn/anthropic").json["compat"])
        assertEquals("""{"x":1}""", c.model("minimax", "MiniMax-M2.7")!!.json["compat"].toString())
    }

    @Test
    fun `template compat wins over the MiniMax defaults`() {
        val withCompat = sample.replace(
            "\"openai-completions\":{\"api\":\"openai-completions\",",
            "\"openai-completions\":{\"compat\":{\"agentosExtraBody\":{\"reasoning_split\":false,\"x\":1},\"supportsStore\":false},\"api\":\"openai-completions\",",
        )
        val m = ModelCatalog.parse(withCompat).customModel("openai-completions", "MiniMax-M2.7", "https://api.minimax.io/v1")
        assertEquals(
            """{"agentosExtraBody":{"reasoning_split":false,"x":1},"agentosThinkTagsReplay":"keep","supportsStore":false}""",
            m.json["compat"].toString(),
        )
    }

    @Test
    fun `withCompat merges into compat and the given values win`() {
        val c = ModelCatalog.parse(sample)
        val minimax = c.customModel("openai-completions", "MiniMax-M2.7", "https://api.minimax.cn/v1")
        val fallback = minimax.withCompat(Json.parseToJsonElement("""{"agentosExtraBody":{"reasoning_split":false,"y":2},"agentosThinkTags":true}""").jsonObject)
        assertEquals(
            """{"agentosExtraBody":{"reasoning_split":false,"y":2},"agentosThinkTagsReplay":"keep","agentosThinkTags":true}""",
            fallback.compat.toString(),
        )
        assertEquals(minimax.id, fallback.id)
        assertEquals(minimax.baseUrl, fallback.baseUrl)
        val plain = c.customModel("openai-completions", "m", "https://gateway.example.com/v1")
        assertEquals("{}", plain.compat.toString())
        assertEquals(ModelCatalog.MINIMAX_OPENAI_COMPAT, plain.withCompat(ModelCatalog.MINIMAX_OPENAI_COMPAT).compat)
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
