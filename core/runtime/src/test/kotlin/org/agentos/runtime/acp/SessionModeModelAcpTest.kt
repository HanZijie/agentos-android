@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.runtime.acp

import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.RuntimeConfig
import org.agentos.runtime.ports.ModelChoice
import org.agentos.runtime.ports.ModelSpec
import org.agentos.runtime.ports.ToolRef
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.agentos.runtime.store.SessionMode
import org.agentos.runtime.testing.FakeHostPort
import org.agentos.runtime.testing.FakeScripts
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ACP 的 `session/set_mode`、`session/set_model`、`session/set_config_option`（core/protocol/acp-mapping.md 第 4b 节）：
 * 模式只能在会话的工具范围之上**再收一层**，模型只能在用户那个 key 下的模型里选，两者都存在会话上、下一个任务起生效。
 */
class SessionModeModelAcpTest {
    private val alarm = ToolSource("alarm", "main", "alarm_create")
    private val delete = ToolSource("notes", "main", "note_delete")
    private val math = ToolSource("math", "main", "add")

    private val modelA = FakeHostPort.FAKE_MODEL
    private val modelB = ModelSpec(JsonObject(FakeHostPort.FAKE_MODEL.model + ("id" to JsonPrimitive("model-b")) + ("name" to JsonPrimitive("Model B"))))

    private fun test(
        withModels: Boolean = true,
        config: RuntimeConfig = TestRuntime.config(),
        block: suspend CoroutineScope.(AcpPair, TestRuntime) -> Unit,
    ): Unit = runBlocking {
        val rt = TestRuntime(FakeScripts.directives(), config = config)
        rt.host.tools.registerSimple("add", ToolRisk.READ, math) { ToolResult.text("5") }
        rt.host.tools.registerSimple("alarm_create", ToolRisk.WRITE, alarm) { ToolResult.text("alarm set") }
        rt.host.tools.registerSimple("note_delete", ToolRisk.HIGH, delete) { ToolResult.text("deleted") }
        if (withModels) rt.host.choices.value = listOf(ModelChoice("fake-model", "Fake model", modelA), ModelChoice("model-b", "Model B", modelB))
        rt.start()
        val pair = AcpPair(rt)
        try {
            withTimeout(60_000) { block(this, pair, rt) }
        } finally {
            pair.close()
            rt.stop()
            rt.host.deleteDatabase()
        }
    }

    private fun AcpPair.errorCode(): String = lastError()["data"]!!.jsonObject["agentosCode"]!!.jsonPrimitive.content

    private fun callAll() = buildJsonObject {
        put(
            "fake",
            buildJsonObject {
                put("tools", buildJsonArray { listOf("add", "alarm_create", "note_delete").forEach { n -> add(buildJsonObject { put("name", n) }) } })
            },
        )
    }.toString()

    /** 下一轮交给模型的工具名（排好序）。 */
    private suspend fun AcpPair.offeredTools(rt: TestRuntime, session: ClientSession): List<String> {
        prompt(session, "go")
        return rt.core!!.configs.last().tools.map { it.name }.sorted()
    }

    private fun ClientSession.modeOption(): SessionConfigOption.Select = configOptions.value.filterIsInstance<SessionConfigOption.Select>().single { it.id.value == "mode" }

    private fun ClientSession.modelOption(): SessionConfigOption.Select = configOptions.value.filterIsInstance<SessionConfigOption.Select>().single { it.id.value == "model" }

    // ------------------------------------------------------------------ 声明

    @Test
    fun `a new session advertises its modes, models and config options`() = test { pair, _ ->
        pair.initialize()
        val s = pair.newSession()
        assertTrue(s.modesSupported)
        assertEquals(listOf("default", "read_only", "chat"), s.availableModes.map { it.id.value })
        assertEquals("default", s.currentMode.value.value)
        assertTrue(s.modelsSupported)
        assertEquals(listOf("fake-model", "model-b"), s.availableModels.map { it.modelId.value })
        assertEquals("fake-model", s.currentModel.value.value, "the model the user picked in settings")
        assertTrue(s.configOptionsSupported)
        assertEquals(listOf("mode", "model"), s.configOptions.value.map { it.id.value })
        assertEquals("default", s.modeOption().currentValue.value)
        assertEquals("fake-model", s.modelOption().currentValue.value)
    }

    @Test
    fun `without selectable models there is no model state, only modes`() = test(withModels = false) { pair, _ ->
        pair.initialize()
        val s = pair.newSession()
        assertTrue(s.modesSupported)
        assertEquals(false, s.modelsSupported)
        assertEquals(listOf("mode"), s.configOptions.value.map { it.id.value })
    }

    // ------------------------------------------------------------------ 模式

    @Test
    fun `read only offers the read tools, chat none, and default gives everything back`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        assertEquals(listOf("add", "alarm_create", "note_delete"), pair.offeredTools(rt, s))

        s.setMode(SessionModeId("read_only"))
        assertEquals(listOf("add"), pair.offeredTools(rt, s))
        assertEquals("read_only", rt.engine.session(TestRuntime.APP, s.sessionId.value).mode.wire)

        s.setMode(SessionModeId("chat"))
        assertEquals(emptyList(), pair.offeredTools(rt, s))
        assertEquals(false, rt.core!!.configs.last().systemPrompt.contains("read_skill"), "no skills list in a chat")

        s.setMode(SessionModeId("default"))
        assertEquals(listOf("add", "alarm_create", "note_delete"), pair.offeredTools(rt, s))
    }

    @Test
    fun `a model that calls a tool the mode hides gets 'not available', exactly like a tool that does not exist`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        s.setMode(SessionModeId("read_only"))
        val events = pair.prompt(s, callAll())
        assertEquals(StopReason.END_TURN, events.response().stopReason)
        assertEquals(listOf("add"), rt.host.tools.invocations.map { it.name }, "alarm_create and note_delete never reached the tool port")
        assertTrue(rt.host.consent.requests.isEmpty(), "and the user was not asked about them")

        s.setMode(SessionModeId("chat"))
        rt.host.tools.invocations.clear()
        pair.prompt(s, callAll())
        assertEquals(emptyList(), rt.host.tools.invocations.map { it.name })
    }

    @Test
    fun `a mode can only narrow the scope the session was created with`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession(toolScopeMeta(ToolRef("alarm", "alarm_create"), ToolRef("math", "add")))
        assertEquals(listOf("add", "alarm_create"), pair.offeredTools(rt, s))
        s.setMode(SessionModeId("read_only"))
        assertEquals(listOf("add"), pair.offeredTools(rt, s), "read only inside the scope: add, not note_delete")
        s.setMode(SessionModeId("default"))
        assertEquals(listOf("add", "alarm_create"), pair.offeredTools(rt, s), "back to default is back to the scope, never past it")
        assertEquals(listOf(ToolRef("alarm", "alarm_create"), ToolRef("math", "add")), rt.engine.session(TestRuntime.APP, s.sessionId.value).toolScope, "the stored scope never changed")
    }

    @Test
    fun `an unknown mode is rejected and nothing changes`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        assertFailsWith<Exception> { s.setMode(SessionModeId("admin")) }
        assertEquals("invalid_params", pair.errorCode())
        assertFailsWith<Exception> { s.setMode(SessionModeId("")) }
        assertEquals("invalid_params", pair.errorCode())
        assertEquals(SessionMode.DEFAULT, rt.engine.session(TestRuntime.APP, s.sessionId.value).mode)
    }

    // ------------------------------------------------------------------ 模型

    @Test
    fun `set_model changes the model from the next turn, and only for this session`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        val other = pair.newSession()
        pair.prompt(s, "first")
        assertEquals("fake-model", rt.core!!.configs.last().model.id)

        s.setModel(ModelId("model-b"))
        pair.prompt(s, "second")
        assertEquals("model-b", rt.core!!.configs.last().model.id)
        assertEquals("model-b", rt.engine.session(TestRuntime.APP, s.sessionId.value).modelId)

        pair.prompt(other, "other session")
        assertEquals("fake-model", rt.core!!.configs.last().model.id, "another session still follows the user's setting")
        assertEquals(null, rt.engine.session(TestRuntime.APP, other.sessionId.value).modelId)
    }

    @Test
    fun `a model that is not one of the choices is rejected - no other endpoint, no other key`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        for (bad in listOf("gpt-unknown", "", "https://evil.example/v1", "fake-model ")) {
            assertFailsWith<Exception> { s.setModel(ModelId(bad)) }
            assertEquals("invalid_params", pair.errorCode(), "'$bad'")
            // the answer does not echo what was sent
            assertTrue(bad.isEmpty() || bad.trim() !in pair.lastError()["message"]!!.jsonPrimitive.content)
        }
        assertEquals(null, rt.engine.session(TestRuntime.APP, s.sessionId.value).modelId)
    }

    @Test
    fun `when the chosen model is no longer selectable the session falls back to the users model`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        s.setModel(ModelId("model-b"))
        pair.prompt(s, "with b")
        assertEquals("model-b", rt.core!!.configs.last().model.id)

        rt.host.choices.value = listOf(ModelChoice("fake-model", "Fake model", modelA)) // the user changed vendor
        pair.prompt(s, "after the change")
        assertEquals("fake-model", rt.core!!.configs.last().model.id)
    }

    @Test
    fun `a chosen model cannot get around a missing key`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        s.setModel(ModelId("model-b"))
        rt.host.activeModel.value = null // the user cleared the key
        assertFailsWith<Exception> { pair.prompt(s, "go") }
        assertEquals("model_not_configured", pair.errorCode())
    }

    // ------------------------------------------------------------------ 配置项

    @Test
    fun `set_config_option sets the mode and the model and answers with the new options`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        val afterMode = s.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.StringValue("read_only"))
        val mode = afterMode.configOptions.filterIsInstance<SessionConfigOption.Select>().single { it.id.value == "mode" }
        assertEquals("read_only", mode.currentValue.value)
        val afterModel = s.setConfigOption(SessionConfigId("model"), SessionConfigOptionValue.StringValue("model-b"))
        val model = afterModel.configOptions.filterIsInstance<SessionConfigOption.Select>().single { it.id.value == "model" }
        assertEquals("model-b", model.currentValue.value)
        assertEquals("read_only", afterModel.configOptions.filterIsInstance<SessionConfigOption.Select>().single { it.id.value == "mode" }.currentValue.value, "the earlier change is part of the answer")

        val stored = rt.engine.session(TestRuntime.APP, s.sessionId.value)
        assertEquals(SessionMode.READ_ONLY, stored.mode)
        assertEquals("model-b", stored.modelId)
    }

    @Test
    fun `set_config_option rejects unknown options and values that are not strings`() = test { pair, _ ->
        pair.initialize()
        val s = pair.newSession()
        assertFailsWith<Exception> { s.setConfigOption(SessionConfigId("temperature"), SessionConfigOptionValue.StringValue("1")) }
        assertEquals("invalid_params", pair.errorCode())
        assertFailsWith<Exception> { s.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.BoolValue(true)) }
        assertEquals("invalid_params", pair.errorCode())
        assertFailsWith<Exception> { s.setConfigOption(SessionConfigId("mode"), SessionConfigOptionValue.StringValue("root")) }
        assertEquals("invalid_params", pair.errorCode())
    }

    // ------------------------------------------------------------------ 随会话保存

    @Test
    fun `mode and model are kept with the session and come back with load and resume`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        s.setMode(SessionModeId("read_only"))
        s.setModel(ModelId("model-b"))

        val second = AcpPair(rt)
        second.initialize()
        val loaded = second.loadSession(s.sessionId.value)
        assertEquals("read_only", loaded.currentMode.value.value)
        assertEquals("model-b", loaded.currentModel.value.value)
        assertEquals("read_only", loaded.modeOption().currentValue.value)
        assertEquals(listOf("add"), second.offeredTools(rt, loaded))
        assertEquals("model-b", rt.core!!.configs.last().model.id)

        val resumed = second.resumeSession(s.sessionId.value)
        assertEquals("read_only", resumed.currentMode.value.value)
        second.close()
    }

    @Test
    fun `a fork starts with the mode and model of the original and then goes its own way`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        s.setMode(SessionModeId("read_only"))
        s.setModel(ModelId("model-b"))
        val f = pair.forkSession(s.sessionId.value)
        assertEquals("read_only", f.currentMode.value.value)
        assertEquals("model-b", f.currentModel.value.value)
        f.setMode(SessionModeId("chat"))
        assertEquals(SessionMode.READ_ONLY, rt.engine.session(TestRuntime.APP, s.sessionId.value).mode, "the original did not move")
    }

    @Test
    fun `another app cannot set the mode or model of a session that is not its own`() = test { pair, rt ->
        pair.initialize()
        val s = pair.newSession()
        val other = AcpPair(rt, TestRuntime.OTHER_APP)
        other.initialize()
        assertFailsWith<org.agentos.runtime.errors.AgentOsException> { rt.engine.setSessionMode(TestRuntime.OTHER_APP, s.sessionId.value, SessionMode.CHAT) }
        assertFailsWith<org.agentos.runtime.errors.AgentOsException> { rt.engine.setSessionModel(TestRuntime.OTHER_APP, s.sessionId.value, "model-b") }
        assertEquals(SessionMode.DEFAULT, rt.engine.session(TestRuntime.APP, s.sessionId.value).mode)
        other.close()
    }
}
