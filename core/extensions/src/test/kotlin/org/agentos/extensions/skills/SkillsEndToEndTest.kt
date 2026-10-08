package org.agentos.extensions.skills

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.extensions.host.E2e
import org.agentos.extensions.registry.InstalledAppView
import org.agentos.extensions.registry.PersistedRegistry
import org.agentos.extensions.registry.PluginAssets
import org.agentos.extensions.registry.PluginIdentity
import org.agentos.extensions.registry.PluginRecord
import org.agentos.extensions.registry.PluginRegistry
import org.agentos.extensions.registry.PluginScanLogic
import org.agentos.extensions.registry.PluginServiceInfo
import org.agentos.runtime.RuntimeConfig
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.broker.PolicyScope
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.scheduler.SchedulerConfig
import org.agentos.runtime.store.TaskState
import org.agentos.runtime.testing.FakeApprovalPolicyPort
import org.agentos.runtime.testing.FakeHostPort
import org.agentos.runtime.testing.FakeSkillPort
import org.agentos.runtime.testing.FakeStep
import org.agentos.runtime.testing.FakeTurnScript
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 真实的 ExtensionSkillPort（三个示例 App 的 SKILL.md）+ 真实的运行时：系统提示里的 Skill 目录，模型调 read_skill 读到指南，
 * 插件被禁用后目录和 read_skill 都变化。
 */
class SkillsEndToEndTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val policy = FakeApprovalPolicyPort()
    private val registry = MutableStateFlow(PluginRegistry())

    private fun sample(n: String) = SkillsEndToEndTest::class.java.getResourceAsStream("/samples/$n/SKILL.md")!!.readBytes().toString(Charsets.UTF_8)

    private val content = HashMap<String, String>()

    private val source = object : SkillFileSource {
        override suspend fun read(plugin: PluginRecord, path: String, maxBytes: Int): SkillFile? =
            content["${plugin.id}|$path"]?.toByteArray()?.let { SkillFile(it.copyOf(minOf(it.size, maxBytes)), it.size > maxBytes) }
    }

    init {
        val views = listOf("alarm", "calendar", "notes").map { n ->
            val pkg = "org.x.$n"
            content["$pkg/agent-plugin|skills/$n/SKILL.md"] = sample(n)
            InstalledAppView(
                PluginIdentity(pkg, "sig", 1),
                listOf(PluginServiceInfo("$pkg.Mcp", true, PluginScanLogic.BIND_PERMISSION, true, "agent-plugin")),
                mapOf("agent-plugin" to PluginAssets("""{"${'$'}schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"$n"}""", null, listOf("skills/$n/SKILL.md"))),
            )
        }
        val scan = PluginScanLogic.scan(views, PersistedRegistry.EMPTY, ApprovalPolicy.DEFAULT)
        registry.value = scan.registry
        policy.update { var p = scan.policy; for (n in listOf("alarm", "calendar", "notes")) p = p.withEnabled(PolicyScope.Plugin(n), true); p }
    }

    private val skills = ExtensionSkillPort(registry, policy, source, scope)
    /** 运行时看到的 SkillPort 就是 ExtensionSkillPort。 */
    private class ExtSkills(private val real: ExtensionSkillPort) : FakeSkillPort() {
        override val catalog get() = real.catalog

        override suspend fun read(skillId: String, path: String?) = real.read(skillId, path)
    }

    private val host = FakeHostPort(skills = ExtSkills(skills), approvals = policy)

    @AfterTest
    fun cleanUp() {
        skills.close()
        scope.cancel()
        host.deleteDatabase()
    }

    private fun readSkill(args: JsonObject) = FakeTurnScript(
        listOf(listOf(FakeStep.ToolUse("read_skill", args, id = "call_1")), listOf(FakeStep.Text("done"))),
    )

    private fun <T> run(args: JsonObject, block: suspend (TestRuntime) -> T): T {
        val rt = TestRuntime({ readSkill(args) }, host = host, config = RuntimeConfig(scheduler = SchedulerConfig(tickMillis = 20)))
        return runBlocking {
            rt.start()
            try {
                withTimeout(E2e.TEST_MILLIS) {
                    E2e.awaitUntil("the skill catalog to list the skills") { skills.catalog.value.skills.isNotEmpty() }
                    block(rt)
                }
            } finally {
                rt.stop()
            }
        }
    }

    private suspend fun TestRuntime.turn(): List<org.agentos.runtime.events.EventEnvelope> {
        val s = engine.createSession(TestRuntime.SELF, null)
        val t = engine.submit(TestRuntime.SELF, s.id, TestRuntime.text("go"))
        assertEquals(TaskState.COMPLETED, engine.awaitTask(t.id).state)
        return engine.readEvents(s.id)
    }

    private fun org.agentos.runtime.events.EventEnvelope.resultText() =
        payload["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    @Test
    fun `the model is told about the three skills in the system prompt and reads the calendar guide with read_skill`() {
        run(buildJsonObject { put("name", "calendar") }) { rt ->
            val events = rt.turn()
            val cfg = rt.core!!.configs.single()
            for (n in listOf("alarm", "calendar", "notes")) assertTrue(cfg.systemPrompt.contains("\"name\":\"$n\""), n)
            assertTrue(cfg.tools.any { it.name == "read_skill" })
            val text = events.single { it.eventType == EventTypes.TOOL_EXECUTION_END }.resultText()
            assertTrue(text.startsWith("[skill \"calendar\" from plugin \"calendar\""), text.take(120))
            assertTrue(text.contains("ISO-8601 **with the time zone offset**"), "the time format pitfall reaches the model")
            assertTrue(host.consent.requests.isEmpty())
        }
    }

    @Test
    fun `a disabled plugin disappears from the next task's prompt and from read_skill`() {
        policy.update { it.withEnabled(PolicyScope.Plugin("notes"), false) }
        run(buildJsonObject { put("name", "notes") }) { rt ->
            // the policy change reaches the skill catalog asynchronously: wait until the plugin's skill has left it
            E2e.awaitUntil("the notes skill to leave the catalog", { "skills=${skills.catalog.value.skills.map { it.name }}" }) { skills.catalog.value.skills.none { it.provider == "notes" } }
            val events = rt.turn()
            val cfg = rt.core!!.configs.single()
            assertFalse(cfg.systemPrompt.contains("\"name\":\"notes\""))
            assertTrue(cfg.systemPrompt.contains("\"name\":\"alarm\""))
            val text = events.single { it.eventType == EventTypes.TOOL_EXECUTION_END }.resultText()
            assertTrue(text.startsWith("[agentos:tool_failed]") && text.contains("Available skills: alarm, calendar"), text)
        }
    }

    @Test
    fun `a path that tries to leave the skill directory comes back as an error and nothing is read`() {
        run(buildJsonObject { put("name", "alarm"); put("path", "../calendar/SKILL.md") }) { rt ->
            val events = rt.turn()
            val text = events.single { it.eventType == EventTypes.TOOL_EXECUTION_END }.resultText()
            assertTrue(text.startsWith("[agentos:tool_failed]") && text.contains(".."), text)
            assertFalse(text.contains("Create and look up events"), "nothing of the calendar guide leaked")
        }
    }
}
