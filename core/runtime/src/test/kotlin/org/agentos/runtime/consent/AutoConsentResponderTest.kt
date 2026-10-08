package org.agentos.runtime.consent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.ConsentDecision
import org.agentos.runtime.ports.ConsentRequest
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AutoConsentResponderTest {

    private fun req(id: String, risk: ToolRisk = ToolRisk.WRITE, args: String = "{}", timeout: Long = 60_000) =
        ConsentRequest(
            id, "s", "t", "c-$id", "add_alarm", "添加闹钟", risk, CallerIdentity(10001, CallerKind.APP, "com.example.app"), args,
            rememberable = true, timeoutMillis = timeout, source = ToolSource("p", "s", "add_alarm"),
        )

    private class Rig(scope: TestScope, mode: AutoConsentResponder.Mode, max: Int = 50) {
        class Ui : ConsentSurface {
            val seen = mutableListOf<String>()
            override fun requested(view: ConsentView) {
                seen += "requested:${view.requestId}"
            }

            override fun resolved(requestId: String, resolution: ConsentResolution) {
                seen += "resolved:$requestId"
            }
        }

        val ui = Ui()
        val responder = AutoConsentResponder(ui, maxEntries = max).also { it.mode = mode }
        val coordinator = ConsentCoordinator(responder, ApprovalWriter.UNAVAILABLE, scope.backgroundScope).also { responder.attach(it) }
    }

    @Test
    fun `off leaves the request to the real surface`() = runTest {
        val r = Rig(this, AutoConsentResponder.Mode.OFF)
        val d = async { r.coordinator.request(req("a", timeout = 5_000)) }
        runCurrent()
        assertEquals(listOf("a"), r.coordinator.pending.value.map { it.requestId })
        assertNull(r.responder.recent.value.single().answeredWith)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.TIMEOUT), d.await())
        assertEquals(ConsentEnd.TIMED_OUT, r.responder.recent.value.single().end)
        assertEquals(listOf("requested:a", "resolved:a"), r.ui.seen, "the delegate still sees everything")
    }

    @Test
    fun `allow answers and prefers remember for session`() = runTest {
        val r = Rig(this, AutoConsentResponder.Mode.ALLOW)
        val d = async { r.coordinator.request(req("a")) }
        runCurrent()
        assertEquals(ConsentDecision.Allow(rememberForSession = true), d.await())
        runCurrent()
        val e = r.responder.recent.value.single()
        assertEquals(ConsentChoice.ALLOW_FOR_SESSION, e.answeredWith)
        assertEquals(ConsentEnd.ANSWERED, e.end)
    }

    @Test
    fun `allow falls back to once when remembering is not offered, and never picks always allow`() = runTest {
        val r = Rig(this, AutoConsentResponder.Mode.ALLOW)
        val d = async { r.coordinator.request(req("h", risk = ToolRisk.HIGH)) }
        runCurrent()
        assertEquals(ConsentDecision.Allow(), d.await())
        assertEquals(ConsentChoice.ALLOW_ONCE, r.responder.recent.value.single().answeredWith)
    }

    @Test
    fun `allow once and deny`() = runTest {
        val once = Rig(this, AutoConsentResponder.Mode.ALLOW_ONCE)
        assertEquals(ConsentDecision.Allow(), async { once.coordinator.request(req("a")) }.also { runCurrent() }.await())
        val deny = Rig(this, AutoConsentResponder.Mode.DENY)
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.USER), async { deny.coordinator.request(req("a")) }.also { runCurrent() }.await())
    }

    @Test
    fun `changing the mode applies to the next request`() = runTest {
        val r = Rig(this, AutoConsentResponder.Mode.DENY)
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.USER), async { r.coordinator.request(req("a")) }.also { runCurrent() }.await())
        r.responder.mode = AutoConsentResponder.Mode.ALLOW_ONCE
        assertEquals(ConsentDecision.Allow(), async { r.coordinator.request(req("b")) }.also { runCurrent() }.await())
        assertEquals(listOf(ConsentChoice.DENY, ConsentChoice.ALLOW_ONCE), r.responder.recent.value.map { it.answeredWith })
    }

    @Test
    fun `the log keeps only the latest entries and a truncated one-line summary`() = runTest {
        val r = Rig(this, AutoConsentResponder.Mode.ALLOW_ONCE, max = 3)
        val secret = "sk-SECRET-" + "x".repeat(500)
        repeat(5) { i ->
            async { r.coordinator.request(req("r$i", args = "{\"text\":\"line1\\nline2\",\n\"token\":\"$secret\"}")) }
            runCurrent()
        }
        val recent = r.responder.recent.value
        assertEquals(listOf("r2", "r3", "r4"), recent.map { it.requestId })
        for (e in recent) {
            assertTrue(e.argumentsSummary.length <= AutoConsentResponder.SUMMARY_CHARS, "summary is capped")
            assertFalse('\n' in e.argumentsSummary)
            assertFalse(secret in e.argumentsSummary, "the full argument text is not kept")
            assertEquals("add_alarm", e.toolName)
        }
    }
}
