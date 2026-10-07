package org.agentos.app.agent.consent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.agentos.runtime.consent.ApprovalWriteResult
import org.agentos.runtime.consent.ApprovalWriter
import org.agentos.runtime.consent.ConsentChoice
import org.agentos.runtime.consent.ConsentCoordinator
import org.agentos.runtime.consent.ConsentResolution
import org.agentos.runtime.consent.ConsentSurface
import org.agentos.runtime.consent.ConsentView
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.ConsentDecision
import org.agentos.runtime.ports.ConsentRequest
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The surface shows exactly the options of the request it receives, and the coordinator (`:agent`) still refuses any answer that
 * is not in that list. Rule (docs/extensions.md 5.4, ConsentCoordinator): HIGH = ALLOW_ONCE + DENY only; WRITE = ALLOW_ONCE,
 * ALLOW_FOR_SESSION (when rememberable), ALWAYS_ALLOW only when the write-back is available and the tool has a source; DENY always last.
 * Everything goes through the real coordinator and the same wire encoding the dialog is rendered from.
 */
class ConsentOptionsTest {
    private class Capture : ConsentSurface {
        val views = CopyOnWriteArrayList<ConsentView>()
        override fun requested(view: ConsentView) { views += view }
        override fun resolved(requestId: String, resolution: ConsentResolution) = Unit
    }

    private val writer = object : ApprovalWriter {
        override suspend fun setAlways(source: ToolSource, risk: ToolRisk) = ApprovalWriteResult.Saved
    }

    private fun request(id: String, risk: ToolRisk, source: ToolSource? = ToolSource("p", "s", "t"), remember: Boolean = true) = ConsentRequest(
        requestId = id, sessionId = "s", taskId = "t", toolCallId = "c", toolName = "mcp__p__t", toolTitle = "t", risk = risk,
        caller = CallerIdentity(10123, CallerKind.APP, "com.example"), argumentsPreview = "{}", rememberable = remember,
        timeoutMillis = 30_000, source = source,
    )

    private fun withCoordinator(approvals: ApprovalWriter, block: suspend (ConsentCoordinator, Capture, CoroutineScope) -> Unit): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val surface = Capture()
        val c = ConsentCoordinator(surface, approvals, scope)
        try {
            block(c, surface, scope)
        } finally {
            c.close()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    private suspend fun shown(surface: Capture, id: String): ConsentWire.Card = withTimeout(5_000) {
        while (surface.views.none { it.requestId == id }) delay(5)
        // what the main process renders: the wire form of the view the surface got
        ConsentWire.parseCard(ConsentWire.encodeViewString(surface.views.first { it.requestId == id }))!!
    }

    private fun choices(card: ConsentWire.Card) = card.options.map { it.choice }

    @Test
    fun highRiskShowsOnlyAllowOnceAndDeny() = withCoordinator(writer) { c, surface, scope ->
        val d = scope.async { c.request(request("h", ToolRisk.HIGH)) }
        assertEquals(listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.DENY), choices(shown(surface, "h")))
        // defence in depth: an answer outside the list (a rogue or stale UI) is a denial, never an allow
        assertTrue(c.respond("h", ConsentChoice.ALWAYS_ALLOW))
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.USER), d.await())
        Unit
    }

    @Test
    fun highRiskRefusesSessionRememberToo() = withCoordinator(writer) { c, surface, scope ->
        val d = scope.async { c.request(request("h2", ToolRisk.HIGH)) }
        shown(surface, "h2")
        c.respond("h2", ConsentChoice.ALLOW_FOR_SESSION)
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.USER), d.await())
        Unit
    }

    @Test
    fun writeOffersEverythingWhenWriteBackIsAvailable() = withCoordinator(writer) { c, surface, scope ->
        val d = scope.async { c.request(request("w", ToolRisk.WRITE)) }
        assertEquals(
            listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.ALLOW_FOR_SESSION, ConsentChoice.ALWAYS_ALLOW, ConsentChoice.DENY),
            choices(shown(surface, "w")),
        )
        c.respond("w", ConsentChoice.ALLOW_ONCE)
        assertEquals(ConsentDecision.Allow(), d.await())
        Unit
    }

    @Test
    fun writeWithoutWriteBackHasNoAlwaysButton() = withCoordinator(ApprovalWriter.UNAVAILABLE) { c, surface, scope ->
        val d = scope.async { c.request(request("w2", ToolRisk.WRITE)) }
        assertEquals(
            listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.ALLOW_FOR_SESSION, ConsentChoice.DENY),
            choices(shown(surface, "w2")),
        )
        // a forced ALWAYS_ALLOW is not in the list: denied
        c.respond("w2", ConsentChoice.ALWAYS_ALLOW)
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.USER), d.await())
        Unit
    }

    @Test
    fun noSourceOrNotRememberableTrimsTheList() = withCoordinator(writer) { c, surface, scope ->
        val d = scope.async { c.request(request("w3", ToolRisk.WRITE, source = null, remember = false)) }
        assertEquals(listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.DENY), choices(shown(surface, "w3")))
        c.respond("w3", ConsentChoice.DENY)
        d.await()
        Unit
    }

    @Test
    fun denyIsAlwaysLastAndAllowOnceAlwaysFirst() = withCoordinator(writer) { c, surface, scope ->
        for ((i, r) in listOf(ToolRisk.READ, ToolRisk.WRITE, ToolRisk.HIGH).withIndex()) {
            val id = "x$i"
            val d = scope.async { c.request(request(id, r)) }
            val opts = choices(shown(surface, id))
            assertEquals(ConsentChoice.ALLOW_ONCE, opts.first())
            assertEquals(ConsentChoice.DENY, opts.last())
            assertFalse(r == ToolRisk.HIGH && opts.any { it == ConsentChoice.ALWAYS_ALLOW || it == ConsentChoice.ALLOW_FOR_SESSION })
            c.respond(id, ConsentChoice.DENY)
            d.await()
        }
        Unit
    }
}
