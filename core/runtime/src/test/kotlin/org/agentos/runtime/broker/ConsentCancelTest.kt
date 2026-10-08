package org.agentos.runtime.broker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.ports.ConsentRequest
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.store.TaskState
import org.agentos.runtime.testing.FakeConsentPort
import org.agentos.runtime.testing.FakeHostPort
import org.agentos.runtime.testing.FakeStep
import org.agentos.runtime.testing.FakeTurnScript
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 用户取消任务时，正在等用户确认的工具调用要撤回确认（Agent core 的回调不会被 abort 打断，所以 Broker 自己撤回）。 */
class ConsentCancelTest {

    @Test
    fun `cancelling the task withdraws an open confirmation and the call is not made`() {
        val withdrawn = CompletableDeferred<Unit>()
        val asked = CompletableDeferred<ConsentRequest>()
        val consent = FakeConsentPort {
            asked.complete(it)
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                withdrawn.complete(Unit)
                throw e
            }
        }
        val host = FakeHostPort(consent = consent)
        host.tools.registerSimple("write_note", risk = ToolRisk.WRITE) { ToolResult.text("saved") }
        val rt = TestRuntime(
            { FakeTurnScript(listOf(listOf(FakeStep.ToolUse("write_note", buildJsonObject { put("t", "x") }, id = "call_1")), listOf(FakeStep.Text("done")))) },
            host = host,
        )
        runBlocking {
            rt.start()
            try {
                withTimeout(15_000) {
                    val s = rt.engine.createSession(TestRuntime.APP, null)
                    val t = rt.engine.submit(TestRuntime.APP, s.id, TestRuntime.text("go"))
                    asked.await()
                    rt.engine.cancel(TestRuntime.APP, s.id)
                    withdrawn.await()
                    val done = rt.engine.awaitTask(t.id)
                    assertEquals(TaskState.CANCELLED, done.state)
                    assertTrue(host.tools.invocations.isEmpty(), "the tool was never called")
                    val events = rt.engine.readEvents(s.id)
                    val resolved = events.single { it.eventType == EventTypes.CONSENT_RESOLVED }
                    assertEquals("client", resolved.payload["reason"]!!.jsonPrimitive.content)
                    assertEquals("deny", resolved.payload["decision"]?.jsonPrimitive?.content ?: "deny")
                    assertTrue(events.none { it.eventType == EventTypes.TOOL_DISPATCHED })
                }
            } finally {
                rt.stop()
                host.deleteDatabase()
            }
        }
    }
}
