package org.agentos.runtime.broker

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.ports.ConsentDecision
import org.agentos.runtime.ports.HookDecision
import org.agentos.runtime.ports.HookOutcome
import org.agentos.runtime.ports.ToolCall
import org.agentos.runtime.ports.ToolCallDecision
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.agentos.runtime.store.TaskState
import org.agentos.runtime.testing.FakeStep
import org.agentos.runtime.testing.FakeTurnScript
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** W16：CapabilityBroker 按用户策略（ApprovalPolicy）决定“能不能用、要不要确认”。 */
class CapabilityBrokerPolicyTest {
    private val src = ToolSource("notes", "main", "create_note")

    private fun <T> run(rt: TestRuntime, block: suspend (TestRuntime) -> T) = runBlocking {
        rt.start()
        try {
            withTimeout(10_000) { block(rt) }
        } finally {
            rt.stop()
            rt.host.deleteDatabase()
        }
        Unit
    }

    /** 同一个工具连续调用 [times] 次（每次一轮，最后一轮文字收尾）。 */
    private fun calls(name: String, times: Int) = FakeTurnScript(
        (1..times).map { listOf(FakeStep.ToolUse(name, buildJsonObject { put("n", it) }, id = "call_$it")) } + listOf(listOf(FakeStep.Text("done"))),
    )

    private suspend fun TestRuntime.consentReasons(sessionId: String) =
        engine.readEvents(sessionId).filter { it.eventType == EventTypes.CONSENT_RESOLVED }.map { it.payload["reason"]!!.jsonPrimitive.content }

    @Test
    fun `without a policy a write tool is confirmed every time (as before)`() {
        val rt = TestRuntime({ calls("create_note", 2) })
        rt.host.tools.registerSimple("create_note", ToolRisk.WRITE, src) { ToolResult.text("ok") }
        run(rt) {
            val s = rt.engine.createSession(TestRuntime.SELF, null)
            val t = rt.engine.submit(TestRuntime.SELF, s.id, TestRuntime.text("go"))
            assertEquals(TaskState.COMPLETED, rt.engine.awaitTask(t.id).state)
            assertEquals(2, rt.host.consent.requests.size)
            assertEquals(2, rt.host.tools.invocations.size)
            assertEquals(src, rt.host.consent.requests.first().source, "the confirmation says which plugin the tool is from")
            assertTrue(rt.host.consent.requests.first().rememberable)
        }
    }

    @Test
    fun `always allow by policy skips the confirmation and says so in the event`() {
        val rt = TestRuntime({ calls("create_note", 2) })
        rt.host.tools.registerSimple("create_note", ToolRisk.WRITE, src) { ToolResult.text("ok") }
        rt.host.approvals.update { it.withApproval(PolicyScope.Server("notes", "main"), ApprovalMode.ALWAYS) }
        run(rt) {
            val s = rt.engine.createSession(TestRuntime.SELF, null)
            val t = rt.engine.submit(TestRuntime.SELF, s.id, TestRuntime.text("go"))
            assertEquals(TaskState.COMPLETED, rt.engine.awaitTask(t.id).state)
            assertTrue(rt.host.consent.requests.isEmpty(), "never asked")
            assertEquals(2, rt.host.tools.invocations.size)
            assertEquals(listOf("policy", "policy"), rt.consentReasons(s.id))
            val resolved = rt.engine.readEvents(s.id).first { it.eventType == EventTypes.CONSENT_RESOLVED }
            assertEquals("allow", resolved.payload["decision"]!!.jsonPrimitive.content)
            assertEquals("false", resolved.payload["remember"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `always allow does not apply to high risk tools`() {
        val rt = TestRuntime({ calls("wipe", 1) })
        rt.host.tools.registerSimple("wipe", ToolRisk.HIGH, ToolSource("notes", "main", "wipe")) { ToolResult.text("wiped") }
        rt.host.approvals.update { it.withApproval(PolicyScope.Plugin("notes"), ApprovalMode.ALWAYS) }
        run(rt) {
            val s = rt.engine.createSession(TestRuntime.SELF, null)
            val t = rt.engine.submit(TestRuntime.SELF, s.id, TestRuntime.text("go"))
            assertEquals(TaskState.COMPLETED, rt.engine.awaitTask(t.id).state)
            assertEquals(1, rt.host.consent.requests.size, "a high risk tool is confirmed whatever the policy says")
            assertEquals(ToolRisk.HIGH, rt.host.consent.requests.single().risk)
            assertEquals(false, rt.host.consent.requests.single().rememberable)
        }
    }

    @Test
    fun `a tool the user disabled is not offered and is rejected like a tool that does not exist`() {
        val rt = TestRuntime({ calls("create_note", 1) })
        rt.host.tools.registerSimple("create_note", ToolRisk.WRITE, src) { ToolResult.text("ok") }
        rt.host.tools.registerSimple("list_notes", ToolRisk.READ, ToolSource("notes", "main", "list_notes")) { ToolResult.text("[]") }
        rt.host.approvals.update { it.withEnabled(PolicyScope.of(src), false) }
        run(rt) {
            assertEquals(listOf("list_notes"), rt.engine.broker.declarations().map { it.name })
            val s = rt.engine.createSession(TestRuntime.SELF, null)
            val t = rt.engine.submit(TestRuntime.SELF, s.id, TestRuntime.text("go"))
            assertEquals(TaskState.COMPLETED, rt.engine.awaitTask(t.id).state)
            assertTrue(rt.host.tools.invocations.isEmpty())
            assertTrue(rt.host.consent.requests.isEmpty(), "no confirmation for a tool that is switched off")
            val settled = rt.engine.readEvents(s.id).single { it.eventType == EventTypes.TOOL_SETTLED }
            assertEquals(ErrorCode.TOOL_NOT_IN_CATALOG, settled.error!!.code)
        }
    }

    @Test
    fun `a policy change takes effect at the next call, also between authorize and execute`() {
        val rt = TestRuntime()
        rt.host.tools.registerSimple("create_note", ToolRisk.WRITE, src) { ToolResult.text("ok") }
        run(rt) {
            val s = rt.engine.createSession(TestRuntime.SELF, null)
            val broker = rt.engine.broker
            val ctx = ToolContext(s.id, "tsk_direct", TestRuntime.SELF) { block ->
                rt.engine.storeForTesting.write { tx ->
                    if (tx.tasks.get("tsk_direct") == null) tx.tasks.create("tsk_direct", s.id, TestRuntime.text("x"), TestRuntime.SELF, null, tx.now, null)
                    block(tx)
                }
            }
            val call = ToolCall("call_a", "create_note", buildJsonObject { })

            // 1. 默认：要确认（脚本里允许）
            assertEquals(ToolCallDecision.Allow, broker.authorize(ctx, call))
            assertEquals(1, rt.host.consent.requests.size)
            // 2. 改成始终允许：下一次不再问
            rt.host.approvals.update { it.withApproval(PolicyScope.of(src), ApprovalMode.ALWAYS) }
            assertEquals(ToolCallDecision.Allow, broker.authorize(ctx, ToolCall("call_b", "create_note", buildJsonObject { })))
            assertEquals(1, rt.host.consent.requests.size)
            // 3. 授权之后、执行之前被禁用：执行时再检查一次
            rt.host.approvals.update { it.withEnabled(PolicyScope.of(src), false) }
            assertTrue(broker.execute(ctx, call).isError)
            assertTrue(rt.host.tools.invocations.isEmpty())
            // 4. 重新启用并改回每次确认
            rt.host.approvals.update { it.cleared(PolicyScope.of(src)) }
            assertEquals(ToolCallDecision.Allow, broker.authorize(ctx, ToolCall("call_c", "create_note", buildJsonObject { })))
            assertEquals(2, rt.host.consent.requests.size)
        }
    }

    @Test
    fun `a hook that says ask overrides always allow`() {
        val rt = TestRuntime({ calls("create_note", 1) })
        rt.host.tools.registerSimple("create_note", ToolRisk.WRITE, src) { ToolResult.text("ok") }
        rt.host.approvals.update { it.withApproval(PolicyScope.of(src), ApprovalMode.ALWAYS) }
        rt.host.hooks.answer = { HookOutcome(matched = 1, decision = HookDecision.ASK) }
        run(rt) {
            val s = rt.engine.createSession(TestRuntime.SELF, null)
            val t = rt.engine.submit(TestRuntime.SELF, s.id, TestRuntime.text("go"))
            assertEquals(TaskState.COMPLETED, rt.engine.awaitTask(t.id).state)
            assertEquals(1, rt.host.consent.requests.size)
        }
    }

    @Test
    fun `remembering for the session still works and is recorded as remembered`() {
        val rt = TestRuntime({ calls("create_note", 2) })
        rt.host.tools.registerSimple("create_note", ToolRisk.WRITE, src) { ToolResult.text("ok") }
        rt.host.consent.answer = { ConsentDecision.Allow(rememberForSession = true) }
        run(rt) {
            val s = rt.engine.createSession(TestRuntime.SELF, null)
            val t = rt.engine.submit(TestRuntime.SELF, s.id, TestRuntime.text("go"))
            assertEquals(TaskState.COMPLETED, rt.engine.awaitTask(t.id).state)
            assertEquals(1, rt.host.consent.requests.size)
            assertEquals(listOf("user", "remembered"), rt.consentReasons(s.id))
        }
    }

    @Test
    fun `tools without a source are not touched by the user policy`() {
        val rt = TestRuntime()
        rt.host.tools.registerSimple("read_skill", ToolRisk.READ) { ToolResult.text("x") }
        rt.host.approvals.update { it.withEnabled(PolicyScope.Plugin("notes"), false) }
        run(rt) {
            assertEquals(listOf("read_skill"), rt.engine.broker.declarations().map { it.name })
            val s = rt.engine.createSession(TestRuntime.SELF, null)
            val ctx = ToolContext(s.id, "tsk_direct", TestRuntime.SELF) { block -> rt.engine.storeForTesting.write { tx -> block(tx) } }
            assertIs<ToolCallDecision.Allow>(rt.engine.broker.authorize(ctx, ToolCall("c", "read_skill", buildJsonObject { })))
        }
    }
}
