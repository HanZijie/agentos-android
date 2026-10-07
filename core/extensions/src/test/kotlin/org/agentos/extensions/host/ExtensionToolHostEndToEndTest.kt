package org.agentos.extensions.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.extensions.registry.PersistedRegistry
import org.agentos.extensions.registry.PluginRegistry
import org.agentos.extensions.registry.PluginScanLogic
import org.agentos.runtime.broker.ApprovalMode
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.broker.PolicyScope
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.ports.ConsentDecision
import org.agentos.runtime.ports.ToolInvocation
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.store.TaskState
import org.agentos.runtime.testing.FakeApprovalPolicyPort
import org.agentos.runtime.testing.FakeHostPort
import org.agentos.runtime.testing.FakeStep
import org.agentos.runtime.testing.FakeToolPort
import org.agentos.runtime.testing.FakeTurnScript
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ExtensionToolHost 经真实的运行时与 CapabilityBroker：模型看到的工具名、确认、调用、结果（假的只有 MCP 服务器）。 */
class ExtensionToolHostEndToEndTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notesPkg = "org.x.notes"
    private val policy = FakeApprovalPolicyPort()
    private val connector = FakeConnector()
    private val server = FakeServer(Samples.notes).also { connector.servers[ServerKey(pluginId(notesPkg), "notes")] = it }
    private val scan = PluginScanLogic.scan(listOf(appView(notesPkg, "notes")), PersistedRegistry.EMPTY, ApprovalPolicy.DEFAULT)
    private val extHost = ExtensionToolHost(MutableStateFlow<PluginRegistry>(scan.registry), policy, connector, scope)

    /** 运行时看到的 ToolPort 就是 Extension Host。 */
    private class ExtToolPort(private val host: ExtensionToolHost) : FakeToolPort() {
        override val catalog get() = host.catalog

        override suspend fun invoke(invocation: ToolInvocation): ToolInvocationResult = host.invoke(invocation)
    }

    private val fakeHost = FakeHostPort(tools = ExtToolPort(extHost), approvals = policy)

    init {
        policy.update { scan.policy.withEnabled(PolicyScope.Plugin("notes"), true) }
    }

    @AfterTest
    fun cleanUp() {
        extHost.close()
        scope.cancel()
        fakeHost.deleteDatabase()
    }

    private fun script(name: String) = FakeTurnScript(
        listOf(listOf(FakeStep.ToolUse(name, buildJsonObject { put("title", "hello") }, id = "call_1")), listOf(FakeStep.Text("done"))),
    )

    private fun <T> run(name: String, block: suspend (TestRuntime) -> T): T {
        val rt = TestRuntime({ script(name) }, host = fakeHost)
        return runBlocking {
            rt.start()
            try {
                withTimeout(E2e.TEST_MILLIS) {
                    E2e.awaitUntil("the tool catalog to list the notes tools", { "serverStates=${extHost.serverStates.value}" }) { extHost.catalog.value.tools.isNotEmpty() }
                    block(rt)
                }
            } finally {
                rt.stop()
            }
        }
    }

    private suspend fun TestRuntime.turn(): Pair<TaskState, List<org.agentos.runtime.events.EventEnvelope>> {
        val s = engine.createSession(TestRuntime.APP, null)
        val t = engine.submit(TestRuntime.APP, s.id, TestRuntime.text("go"))
        val done = engine.awaitTask(t.id)
        return done.state to engine.readEvents(s.id)
    }

    private fun org.agentos.runtime.events.EventEnvelope.resultText() =
        payload["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    @Test
    fun `the model is offered the sample app tools by name, a write tool is confirmed on the phone, then called, and the result comes back`() {
        run("mcp__notes__notes__note_create") { rt ->
            val offered = rt.engine.broker.declarations().map { it.name }
            assertTrue("mcp__notes__notes__note_create" in offered && "mcp__notes__notes__note_delete" in offered, offered.toString())
            val (state, events) = rt.turn()
            assertEquals(TaskState.COMPLETED, state)

            val ask = fakeHost.consent.requests.single()
            assertEquals("mcp__notes__notes__note_create", ask.toolName)
            assertEquals(ToolRisk.WRITE, ask.risk)
            assertEquals("notes", ask.source!!.plugin)
            assertEquals("note_create", ask.source!!.tool)

            assertEquals("note_create", server.calls.single().first, "the MCP server received the original tool name")
            assertEquals("hello", server.calls.single().second["title"]!!.jsonPrimitive.content)
            val types = events.map { it.eventType }
            val order = listOf(EventTypes.TOOL_EXECUTION_START, EventTypes.CONSENT_REQUESTED, EventTypes.CONSENT_RESOLVED, EventTypes.TOOL_DISPATCHED, EventTypes.TOOL_SETTLED, EventTypes.TOOL_EXECUTION_END)
            assertEquals(order, types.filter { it in order })
            assertEquals("ok:note_create", events.single { it.eventType == EventTypes.TOOL_EXECUTION_END }.resultText())
            assertEquals("completed", events.single { it.eventType == EventTypes.TOOL_SETTLED }.payload["outcome"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a destructive tool is high risk, the user can decline, and then nothing is sent to the app`() {
        fakeHost.consent.answer = { ConsentDecision.Deny(ConsentDecision.DenyReason.USER) }
        run("mcp__notes__notes__note_delete") { rt ->
            val (state, events) = rt.turn()
            assertEquals(TaskState.COMPLETED, state)
            val ask = fakeHost.consent.requests.single()
            assertEquals(ToolRisk.HIGH, ask.risk)
            assertEquals(false, ask.rememberable)
            assertTrue(server.calls.isEmpty(), "declined: never sent")
            assertTrue(events.none { it.eventType == EventTypes.TOOL_DISPATCHED })
            assertTrue(events.single { it.eventType == EventTypes.TOOL_EXECUTION_END }.resultText().startsWith("[agentos:tool_denied]"))
        }
    }

    @Test
    fun `always allow in the policy skips the confirmation for write tools but never for high risk ones`() {
        policy.update { it.withApproval(PolicyScope.Plugin("notes"), ApprovalMode.ALWAYS) }
        run("mcp__notes__notes__note_append") { rt ->
            val (_, events) = rt.turn()
            assertTrue(fakeHost.consent.requests.isEmpty())
            assertEquals("policy", events.single { it.eventType == EventTypes.CONSENT_RESOLVED }.payload["reason"]!!.jsonPrimitive.content)
            assertEquals("note_append", server.calls.single().first)
        }
        fakeHost.consent.requests.clear()
        server.calls.clear()
        run("mcp__notes__notes__note_delete") { rt ->
            rt.turn()
            assertEquals(1, fakeHost.consent.requests.size, "high risk is always confirmed")
        }
    }

    @Test
    fun `a tool the user switched off is not offered and a call to it is rejected like an unknown tool`() {
        policy.update { it.withEnabled(PolicyScope.Tool("notes", "notes", "note_trash"), false) }
        run("mcp__notes__notes__note_trash") { rt ->
            // the policy change reaches the host asynchronously: wait until the tool has left the catalog, then assert what the model is offered
            E2e.awaitUntil("note_trash to leave the catalog", { "catalog=${extHost.catalog.value.tools.map { it.name }}" }) { extHost.catalog.value.tools.none { it.name == "mcp__notes__notes__note_trash" } }
            assertTrue("mcp__notes__notes__note_trash" !in rt.engine.broker.declarations().map { it.name })
            val (state, events) = rt.turn()
            assertEquals(TaskState.COMPLETED, state)
            assertTrue(server.calls.isEmpty())
            assertTrue(fakeHost.consent.requests.isEmpty())
            assertEquals(ErrorCode.TOOL_NOT_IN_CATALOG, events.single { it.eventType == EventTypes.TOOL_SETTLED }.error!!.code)
        }
    }

    @Test
    fun `when the app dies after the request was sent the task goes on and the result is recorded as unknown`() {
        server.handler = { _, _ -> delay(30); server.kill("app crashed"); delay(5_000); org.agentos.extensions.host.McpCallResult(emptyList()) }
        run("mcp__notes__notes__note_create") { rt ->
            val (state, events) = rt.turn()
            assertEquals(TaskState.COMPLETED, state, "a tool failure does not fail the turn")
            assertEquals("unknown", events.single { it.eventType == EventTypes.TOOL_SETTLED }.payload["outcome"]!!.jsonPrimitive.content)
            assertEquals(ErrorCode.TOOL_RESULT_UNKNOWN, events.single { it.eventType == EventTypes.TOOL_SETTLED }.error!!.code)
            assertEquals(1, server.calls.size, "never replayed")
            assertTrue(events.single { it.eventType == EventTypes.TOOL_EXECUTION_END }.resultText().startsWith("[agentos:tool_result_unknown]"))
        }
    }
}
