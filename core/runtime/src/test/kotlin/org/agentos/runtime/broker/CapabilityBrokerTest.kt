package org.agentos.runtime.broker

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.ports.ConsentDecision
import org.agentos.runtime.ports.ToolCall
import org.agentos.runtime.ports.ToolCallDecision
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.store.TaskState
import org.agentos.runtime.testing.FakeHostPort
import org.agentos.runtime.testing.FakeStep
import org.agentos.runtime.testing.FakeTurnScript
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** W2 验收项 4：broker 拒绝不在目录里的工具。另外覆盖“先落盘再调用”和确认。 */
class CapabilityBrokerTest {

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

    private fun toolScript(name: String) = FakeTurnScript(
        listOf(listOf(FakeStep.ToolUse(name, buildJsonObject { put("path", "/") }, id = "call_1")), listOf(FakeStep.Text("done"))),
    )

    @Test
    fun `a tool that is not in the catalog is rejected without being invoked`() {
        val rt = TestRuntime({ toolScript("rm_rf") })
        rt.host.tools.registerSimple("read_notes") { ToolResult.text("notes") }
        run(rt) {
            val s = rt.engine.createSession(TestRuntime.APP, null)
            val t = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("delete everything"))
            val done = rt.engine.awaitTask(t.id)

            assertEquals(TaskState.COMPLETED, done.state, "a rejected tool does not fail the turn")
            assertTrue(rt.host.tools.invocations.isEmpty(), "ToolPort.invoke was never called")
            val events = rt.engine.readEvents(s.id)
            val settled = events.single { it.eventType == EventTypes.TOOL_SETTLED }
            assertEquals("rejected", settled.payload["outcome"]!!.jsonPrimitive.content)
            assertEquals(ErrorCode.TOOL_NOT_IN_CATALOG, settled.error!!.code)
            assertTrue(events.none { it.eventType == EventTypes.TOOL_DISPATCHED })
            val end = events.single { it.eventType == EventTypes.TOOL_EXECUTION_END }
            assertEquals("true", end.payload["isError"]!!.jsonPrimitive.content)
            val text = end.payload["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            assertTrue(text.startsWith("[agentos:tool_not_in_catalog]"), text)
        }
    }

    @Test
    fun `execute re-checks the catalog even after authorize allowed the call`() {
        val rt = TestRuntime()
        rt.host.tools.registerSimple("flaky") { ToolResult.text("ok") }
        run(rt) {
            val s = rt.engine.createSession(TestRuntime.APP, null)
            val broker = rt.engine.broker
            val commits = mutableListOf<String>()
            val ctx = ToolContext(s.id, "tsk_direct", TestRuntime.APP) { block ->
                rt.engine.storeForTesting.write { tx ->
                    // 直接测试时没有真实任务行：给 tool_calls 的外键准备一个任务
                    if (tx.tasks.get("tsk_direct") == null) {
                        tx.tasks.create("tsk_direct", s.id, TestRuntime.text("x"), TestRuntime.APP, null, tx.now, null)
                    }
                    block(tx)
                }
                commits += "commit"
            }
            val call = ToolCall("call_x", "flaky", buildJsonObject { })
            assertEquals(ToolCallDecision.Allow, broker.authorize(ctx, call))
            rt.host.tools.unregister("flaky") // 目录在两步之间变化（插件被禁用）
            val result = broker.execute(ctx, call)
            assertTrue(result.isError)
            assertTrue(rt.host.tools.invocations.isEmpty())
        }
    }

    @Test
    fun `tool dispatched is committed before the provider is invoked`() {
        val rt = TestRuntime({ toolScript("save_note") })
        run(rt) {
            val s = rt.engine.createSession(TestRuntime.APP, null)
            var dispatchedBeforeInvoke = false
            rt.host.tools.register("save_note", ToolRisk.READ) {
                dispatchedBeforeInvoke = rt.engine.readEvents(s.id).any { e -> e.eventType == EventTypes.TOOL_DISPATCHED }
                ToolInvocationResult.Completed(ToolResult.text("saved"))
            }
            val t = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("save"))
            assertEquals(TaskState.COMPLETED, rt.engine.awaitTask(t.id).state)
            assertTrue(dispatchedBeforeInvoke)
            val types = rt.engine.readEvents(s.id).map { it.eventType }
            val order = listOf(EventTypes.TOOL_EXECUTION_START, EventTypes.TOOL_DISPATCHED, EventTypes.TOOL_SETTLED, EventTypes.TOOL_EXECUTION_END)
            assertEquals(order, types.filter { it in order }, "events follow the Pi order")
        }
    }

    @Test
    fun `write tools ask the user and a denial becomes an error result`() {
        val rt = TestRuntime({ toolScript("send_sms") })
        rt.host.consent.answer = { ConsentDecision.Deny(ConsentDecision.DenyReason.USER) }
        rt.host.tools.register("send_sms", ToolRisk.WRITE) { error("must not run") }
        run(rt) {
            val s = rt.engine.createSession(TestRuntime.APP, null)
            val t = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("text mom"))
            assertEquals(TaskState.COMPLETED, rt.engine.awaitTask(t.id).state)
            assertEquals(1, rt.host.consent.requests.size)
            assertEquals(TestRuntime.APP, rt.host.consent.requests.single().caller)
            assertTrue(rt.host.tools.invocations.isEmpty())
            val types = rt.engine.readEvents(s.id).map { it.eventType }
            assertTrue(EventTypes.CONSENT_REQUESTED in types && EventTypes.CONSENT_RESOLVED in types)
        }
    }

    @Test
    fun `oversized results are truncated before they reach the model`() {
        val broker = DefaultCapabilityBroker(FakeHostPort(), BrokerConfig(maxResultChars = 10))
        val out = runBlocking {
            broker.afterExecute(ToolContext("s", "t", TestRuntime.APP) { }, ToolCall("c", "n", buildJsonObject { }), ToolResult.text("0123456789ABCDEF"))
        }
        assertIs<ToolResult>(out)
        assertEquals("0123456789", (out.content[0] as org.agentos.runtime.ports.ContentPart.Text).text)
        assertTrue((out.content.last() as org.agentos.runtime.ports.ContentPart.Text).text.contains("tool_result_too_large"))
    }
}
