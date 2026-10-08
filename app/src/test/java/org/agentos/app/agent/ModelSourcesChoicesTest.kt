package org.agentos.app.agent

import org.agentos.runtime.pi.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [ModelSources.choices]：会话可以选的模型（ACP `session/set_model`）。规则：厂商预设下、**同一把 key** 能用的模型；
 * 其他端点的模型、自定义端点、没有 key 的时候都不在里面。
 */
class ModelSourcesChoicesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val key = "sk-cp-SECRET0123456789-abcdefghijkLMNO"

    private fun sources(catalogText: String = TEST_CATALOG): ModelSources {
        val secrets = KeystoreSecrets(SoftwareCipher()) { true }
        return ModelSources(File(tmp.root, "byok"), secrets, { ModelCatalog.parse(catalogText) })
    }

    private fun preset(provider: String, model: String) = """{"kind":"preset","provider":"$provider","model":"$model"}"""

    @Test
    fun `nothing is selectable before a source is set`() {
        val m = sources()
        m.ensureLoaded()
        assertEquals(emptyList<String>(), m.choices.value.map { it.id })
    }

    @Test
    fun `a preset offers the models of its provider that the same key can use, with the active one among them`() {
        val m = sources()
        m.set(preset("minimax-cn", "MiniMax-M2.7"), key)
        assertEquals(listOf("MiniMax-M2.7", "MiniMax-M2.5"), m.choices.value.map { it.id })
        assertEquals("MiniMax-M2.7", m.activeModel.value!!.id)
        // every choice is a complete model object for the same endpoint, ready for the agent core
        m.choices.value.forEach {
            assertEquals("https://api.minimaxi.com/anthropic", it.spec.baseUrl)
            assertEquals(it.id, it.spec.id)
        }
    }

    @Test
    fun `a model of the same provider on another endpoint is not offered - the key would not be sent there`() {
        val other = TEST_CATALOG.replace(
            "{ \"id\": \"MiniMax-M2.5\", \"name\": \"MiniMax-M2.5\", \"api\": \"anthropic-messages\", \"provider\": \"minimax-cn\",\n          \"baseUrl\": \"https://api.minimaxi.com/anthropic\"",
            "{ \"id\": \"MiniMax-M2.5\", \"name\": \"MiniMax-M2.5\", \"api\": \"anthropic-messages\", \"provider\": \"minimax-cn\",\n          \"baseUrl\": \"https://elsewhere.example.com/anthropic\"",
        )
        assertTrue("the fixture edit applied", "elsewhere.example.com" in other)
        val m = sources(other)
        m.set(preset("minimax-cn", "MiniMax-M2.7"), key)
        assertEquals(listOf("MiniMax-M2.7"), m.choices.value.map { it.id })
    }

    @Test
    fun `a custom endpoint has no other models to choose from`() {
        val m = sources()
        m.set("""{"kind":"custom","api":"anthropic-messages","baseUrl":"https://gw.example.com/anthropic","model":"m"}""", key)
        assertTrue(m.activeModel.value != null)
        assertEquals(emptyList<String>(), m.choices.value.map { it.id })
    }

    @Test
    fun `switching the provider switches the choices, and clearing the source empties them at once`() {
        val m = sources()
        m.set(preset("minimax-cn", "MiniMax-M2.7"), key)
        assertEquals(2, m.choices.value.size)
        m.set(preset("minimax", "MiniMax-M2.7"), key)
        assertEquals(listOf("MiniMax-M2.7"), m.choices.value.map { it.id })
        assertEquals("https://api.minimax.io/anthropic", m.choices.value.single().spec.baseUrl)
        m.clear()
        assertEquals(emptyList<String>(), m.choices.value.map { it.id })
        assertEquals(null, m.activeModel.value)
    }

    @Test
    fun `choices come back after a restart together with the active model`() {
        sources().set(preset("minimax-cn", "MiniMax-M2.5"), key)
        // a new process: new ModelSources and KeystoreSecrets over the same files; the cipher keeps its master key (same Keystore)
        val cipher = SoftwareCipher()
        val first = ModelSources(File(tmp.root, "byok2"), KeystoreSecrets(cipher) { true }, { ModelCatalog.parse(TEST_CATALOG) })
        first.set(preset("minimax-cn", "MiniMax-M2.5"), key)
        val second = ModelSources(File(tmp.root, "byok2"), KeystoreSecrets(cipher) { true }, { ModelCatalog.parse(TEST_CATALOG) })
        second.ensureLoaded()
        assertEquals("MiniMax-M2.5", second.activeModel.value!!.id)
        assertEquals(listOf("MiniMax-M2.7", "MiniMax-M2.5"), second.choices.value.map { it.id })
    }
}
