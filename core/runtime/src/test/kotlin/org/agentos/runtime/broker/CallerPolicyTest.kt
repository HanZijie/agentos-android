package org.agentos.runtime.broker

import kotlinx.serialization.json.buildJsonObject
import org.agentos.runtime.consent.ConsentChoice
import org.agentos.runtime.consent.ConsentText
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.CatalogTool
import org.agentos.runtime.ports.ConsentDecision
import org.agentos.runtime.ports.ToolCall
import org.agentos.runtime.ports.ToolCallDecision
import org.agentos.runtime.ports.ToolRef
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolScope
import org.agentos.runtime.ports.ToolSource
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * docs/third-party-acp.md 4.4: the [CallerPolicy] hook. Two implementations, one switch, and a broker that lets a policy only tighten.
 */
class CallerPolicyTest {
    private val everyone = listOf(
        TestRuntime.APP, TestRuntime.OTHER_APP, TestRuntime.SELF, TestRuntime.DESKTOP, CallerIdentity.SYSTEM,
    )
    private val memo = ToolScope.only(listOf(ToolRef("alarm", "alarm_create"), ToolRef("calendar", "event_create")))
    private val writeTool = CatalogTool("alarm_create", "d", buildJsonObject { }, ToolRisk.WRITE, provider = "fake", source = ToolSource("alarm", "main", "alarm_create"))

    // ------------------------------------------------------------------ the two policies on their own

    @Test
    fun `open - every caller keeps the scope it asked for, and no base requirement is tightened`() {
        for (caller in everyone) {
            assertEquals(ToolScope.ALL, OpenCallerPolicy.scopeFor(caller, ToolScope.ALL), caller.kind.name)
            assertEquals(memo, OpenCallerPolicy.scopeFor(caller, memo), caller.kind.name)
            assertEquals(ToolScope.NONE, OpenCallerPolicy.scopeFor(caller, ToolScope.NONE), caller.kind.name)
            for (base in ConsentRequirement.entries) {
                assertEquals(ConsentTerms.unchanged(base), OpenCallerPolicy.requiresConsent(caller, writeTool, base), "${caller.kind} $base")
            }
        }
    }

    @Test
    fun `strict - a third-party app without a scope has no tools, with a scope it keeps that scope, and nobody else is touched`() {
        assertEquals(ToolScope.NONE, StrictCallerPolicy.scopeFor(TestRuntime.APP, ToolScope.ALL))
        assertEquals(memo, StrictCallerPolicy.scopeFor(TestRuntime.APP, memo))
        assertEquals(ToolScope.NONE, StrictCallerPolicy.scopeFor(TestRuntime.APP, ToolScope.NONE), "an empty scope is asked for, and given")
        for (caller in everyone.filter { it.kind != CallerKind.APP }) {
            assertEquals(ToolScope.ALL, StrictCallerPolicy.scopeFor(caller, ToolScope.ALL), caller.kind.name)
            assertEquals(memo, StrictCallerPolicy.scopeFor(caller, memo), caller.kind.name)
        }
    }

    @Test
    fun `strict - a third-party app is always asked and offered neither always allow nor remember, everybody else is unchanged`() {
        for (base in ConsentRequirement.entries) {
            for (app in listOf(TestRuntime.APP, TestRuntime.OTHER_APP)) {
                assertEquals(ConsentTerms(ConsentRequirement.ASK, offerSessionRemember = false, offerAlwaysAllow = false), StrictCallerPolicy.requiresConsent(app, writeTool, base), "$base")
            }
            for (caller in everyone.filter { it.kind != CallerKind.APP }) {
                assertEquals(ConsentTerms.unchanged(base), StrictCallerPolicy.requiresConsent(caller, writeTool, base), "${caller.kind} $base")
            }
        }
    }

    @Test
    fun `the policy is chosen by one name, anything unknown is an error and not a silent default`() {
        assertSame(OpenCallerPolicy, CallerPolicy.named("open"))
        assertSame(StrictCallerPolicy, CallerPolicy.named("strict"))
        assertSame(StrictCallerPolicy, CallerPolicy.named("STRICT"))
        assertFailsWith<IllegalArgumentException> { CallerPolicy.named("") }
        assertFailsWith<IllegalArgumentException> { CallerPolicy.named("lenient") }
    }

    @Test
    fun `the shipped default is the open policy`() {
        assertSame(OpenCallerPolicy, org.agentos.runtime.RuntimeConfig().callerPolicy)
    }

    // ------------------------------------------------------------------ the broker lets a policy only tighten

    private object LoosePolicy : CallerPolicy {
        override fun scopeFor(caller: CallerIdentity, requested: ToolScope) = ToolScope.ALL

        override fun requiresConsent(caller: CallerIdentity, tool: CatalogTool, base: ConsentRequirement) =
            ConsentTerms(ConsentRequirement.ALWAYS_BY_POLICY, offerSessionRemember = true, offerAlwaysAllow = true)
    }

    private object BrokenPolicy : CallerPolicy {
        override fun scopeFor(caller: CallerIdentity, requested: ToolScope): ToolScope = error("boom")

        override fun requiresConsent(caller: CallerIdentity, tool: CatalogTool, base: ConsentRequirement): ConsentTerms = error("boom")
    }

    @Test
    fun `a policy that tries to widen the scope has no effect`() {
        val phone = Phone(policy = LoosePolicy)
        phone.run { rt ->
            assertEquals(memo, rt.engine.broker.scopeFor(TestRuntime.APP, memo), "asked for two tools, the policy said all: still two")
            assertEquals(ToolScope.NONE, rt.engine.broker.scopeFor(TestRuntime.APP, ToolScope.NONE))
            rt.turn(TestRuntime.APP, phone.memoScope)
            assertEquals(listOf("alarm_create", "event_create"), rt.offered().sorted())
        }
    }

    @Test
    fun `a policy that tries to skip a confirmation has no effect`() {
        val phone = Phone(Phone.script("alarm_create", "note_delete"), policy = LoosePolicy)
        phone.run { rt ->
            val (_, events) = rt.turn(TestRuntime.APP, null)
            assertEquals(listOf("alarm_create", "note_delete"), rt.host.consent.requests.map { it.toolName }, "both were asked about")
            assertEquals(listOf("user", "user"), events.consentReasons())
        }
    }

    @Test
    fun `a policy cannot invent a skip reason for a call the base rules let through`() {
        val phone = Phone(Phone.script("note_list"), policy = LoosePolicy)
        phone.run { rt ->
            val (_, events) = rt.turn(TestRuntime.APP, null)
            assertTrue(rt.host.consent.requests.isEmpty())
            assertEquals(emptyList(), events.consentReasons(), "a read tool leaves no consent event, as without a policy")
            assertEquals(listOf("note_list"), rt.host.tools.invocations.map { it.name })
        }
    }

    @Test
    fun `a policy cannot add an option the ordinary rules do not give`() {
        val phone = Phone(Phone.script("note_delete"), policy = LoosePolicy)
        phone.run { rt ->
            rt.turn(TestRuntime.APP, null)
            val request = rt.host.consent.requests.single()
            assertFalse(request.rememberable, "high risk is never rememberable")
            assertEquals(listOf(ConsentChoice.ALLOW_ONCE, ConsentChoice.DENY), ConsentText.allowedChoices(request, alwaysAvailable = true))
        }
    }

    @Test
    fun `a policy that throws fails closed - no tools and every call asked, with no always allow`() {
        val phone = Phone(Phone.script("alarm_create"), policy = BrokenPolicy)
        phone.run { rt ->
            assertEquals(ToolScope.NONE, rt.engine.broker.scopeFor(TestRuntime.SELF, ToolScope.ALL))
            rt.turn(TestRuntime.SELF, null)
            assertEquals(emptyList(), rt.offered())
            assertTrue(rt.host.tools.invocations.isEmpty())
        }
        // and the confirmation side, reached with a context that carries a scope of its own
        val phone2 = Phone(policy = BrokenPolicy)
        phone2.run { rt ->
            val consentBroker = DefaultCapabilityBroker(rt.host, BrokerConfig(), object : CallerPolicy {
                override fun scopeFor(caller: CallerIdentity, requested: ToolScope) = requested

                override fun requiresConsent(caller: CallerIdentity, tool: CatalogTool, base: ConsentRequirement): ConsentTerms = error("boom")
            })
            val s = rt.engine.createSession(TestRuntime.SELF, null)
            val ctx = org.agentos.runtime.broker.ToolContext(s.id, "tsk_direct", TestRuntime.SELF) { block ->
                rt.engine.storeForTesting.write { tx ->
                    if (tx.tasks.get("tsk_direct") == null) tx.tasks.create("tsk_direct", s.id, TestRuntime.text("x"), TestRuntime.SELF, null, tx.now, null)
                    block(tx)
                }
            }
            rt.host.approvals.update { it.withApproval(PolicyScope.of(phone2.alarm), ApprovalMode.ALWAYS) }
            rt.host.consent.answer = { ConsentDecision.Allow(rememberForSession = true) }
            assertIs<ToolCallDecision.Allow>(consentBroker.authorize(ctx, ToolCall("c1", "alarm_create", buildJsonObject { })))
            val request = rt.host.consent.requests.single()
            assertFalse(request.rememberable)
            assertFalse(request.alwaysAllowOffered)
        }
    }

    // ------------------------------------------------------------------ allowedChoices does not look at who is calling

    @Test
    fun `allowedChoices gives every kind of caller the same choices for the same request`() {
        val source = ToolSource("p", "s", "t")
        for (risk in ToolRisk.entries) for (rememberable in listOf(false, true)) for (alwaysOffered in listOf(false, true)) for (always in listOf(false, true)) for (src in listOf<ToolSource?>(null, source)) {
            val results = everyone.map { caller ->
                ConsentText.allowedChoices(
                    org.agentos.runtime.ports.ConsentRequest("r", "s", "t", "c", "tool", null, risk, caller, "{}", rememberable = rememberable, source = src, alwaysAllowOffered = alwaysOffered),
                    always,
                )
            }
            assertEquals(1, results.toSet().size, "$risk $rememberable $alwaysOffered $always $src")
        }
    }

    @Test
    fun `alwaysAllowOffered and rememberable can take an option away, never add one`() {
        val source = ToolSource("p", "s", "t")
        fun choices(risk: ToolRisk, rememberable: Boolean, alwaysOffered: Boolean, always: Boolean = true, src: ToolSource? = source) =
            ConsentText.allowedChoices(
                org.agentos.runtime.ports.ConsentRequest("r", "s", "t", "c", "tool", null, risk, TestRuntime.APP, "{}", rememberable = rememberable, source = src, alwaysAllowOffered = alwaysOffered),
                always,
            )
        val once = ConsentChoice.ALLOW_ONCE
        val session = ConsentChoice.ALLOW_FOR_SESSION
        val forever = ConsentChoice.ALWAYS_ALLOW
        val deny = ConsentChoice.DENY
        assertEquals(listOf(once, session, forever, deny), choices(ToolRisk.WRITE, rememberable = true, alwaysOffered = true))
        assertEquals(listOf(once, session, deny), choices(ToolRisk.WRITE, rememberable = true, alwaysOffered = false))
        assertEquals(listOf(once, forever, deny), choices(ToolRisk.WRITE, rememberable = false, alwaysOffered = true))
        assertEquals(listOf(once, deny), choices(ToolRisk.WRITE, rememberable = false, alwaysOffered = false))
        assertEquals(listOf(once, deny), choices(ToolRisk.HIGH, rememberable = true, alwaysOffered = true), "high risk: nothing to add")
        assertEquals(listOf(once, deny), choices(ToolRisk.READ, rememberable = true, alwaysOffered = true), "read: nothing to add")
        assertEquals(listOf(once, session, deny), choices(ToolRisk.WRITE, rememberable = true, alwaysOffered = true, always = false), "no write-back channel")
        assertEquals(listOf(once, session, deny), choices(ToolRisk.WRITE, rememberable = true, alwaysOffered = true, src = null), "no source plugin")
    }
}
