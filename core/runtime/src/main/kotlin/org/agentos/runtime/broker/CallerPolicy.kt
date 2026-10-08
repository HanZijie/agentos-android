package org.agentos.runtime.broker

import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.CatalogTool
import org.agentos.runtime.ports.ToolScope

/**
 * What a caller is told about the confirmation of one tool call, after [CallerPolicy] looked at it.
 *
 * @property requirement Whether the user is asked. Can only tighten: the broker takes [ConsentRequirement.ASK] from here, and nothing else
 *   (a policy cannot turn an "ask" into a "skip", nor choose which skip reason is written to the event log).
 * @property offerSessionRemember The dialog may offer "do not ask again in this conversation". Can only remove it, never add it.
 * @property offerAlwaysAllow The dialog may offer "always allow this tool". Can only remove it, never add it.
 */
data class ConsentTerms(
    val requirement: ConsentRequirement,
    val offerSessionRemember: Boolean = true,
    val offerAlwaysAllow: Boolean = true,
) {
    companion object {
        /** Nothing tightened: the base requirement as it is, both options as they were. */
        fun unchanged(base: ConsentRequirement) = ConsentTerms(base)
    }
}

/**
 * The one place where the rules for who may use which tools, and how they are confirmed, depend on **who is calling**
 * (docs/third-party-acp.md 4.4 and 9.5). Pure Kotlin, no Android, no state.
 *
 * **A policy can only tighten, never loosen.** The broker enforces this, so a policy that tries to widen has no effect:
 * - [scopeFor]: the broker intersects the answer with the scope that was asked for ([ToolScope.intersect]);
 * - [requiresConsent]: the broker keeps `ASK` if either the base rules or the policy say ask, and only takes the two "offer" flags away.
 *
 * What the policy is *not* asked about, because it is the same for every caller: tools the user switched off, plugins that are not enabled,
 * high-risk tools (always asked, only "allow once"), hooks. Those stay in the broker.
 *
 * Which one runs is a single value: `RuntimeConfig.callerPolicy`. The default is [OpenCallerPolicy]. [StrictCallerPolicy] is written and
 * tested but off; a future permission mechanism (docs/third-party-acp.md section 9) plugs in here without touching the broker or the ACP layer.
 */
interface CallerPolicy {
    /**
     * The tools a session of [caller] may use.
     *
     * @param requested what the session was created with: [ToolScope.ALL] when it did not ask for a scope (or the caller kind never does),
     *   a restricted scope otherwise ([ToolScope.NONE] = it asked for no tools). The answer is intersected with it by the broker.
     */
    fun scopeFor(caller: CallerIdentity, requested: ToolScope): ToolScope

    /**
     * Whether the user is asked about [tool] for [caller], and what the dialog may offer. [base] is what the ordinary rules decided
     * ([RiskPolicy.consentRequirement]: read / policy / session memory / high risk / hook).
     */
    fun requiresConsent(caller: CallerIdentity, tool: CatalogTool, base: ConsentRequirement): ConsentTerms

    companion object {
        const val OPEN = "open"
        const val STRICT = "strict"

        /** The policy with this name (for a single string setting, a build flag, an adb extra); anything else is an error, not a silent default. */
        fun named(name: String): CallerPolicy = when (name.lowercase()) {
            OPEN -> OpenCallerPolicy
            STRICT -> StrictCallerPolicy
            else -> throw IllegalArgumentException("unknown caller policy \"$name\" (expected \"$OPEN\" or \"$STRICT\")")
        }
    }
}

/**
 * **The default** (user decision 2026-10-08, docs/third-party-acp.md 4.4): every caller is treated alike.
 * A third-party app needs no toolScope (none = the whole catalog, like AgentOS itself and the desktop), and its tool calls are confirmed by
 * exactly the same rules: a read tool runs, a write tool asks unless the user set "always allow" or the session remembered, a high-risk tool
 * always asks and offers only "allow once" and "decline". A toolScope the caller does choose to pass still only narrows.
 */
object OpenCallerPolicy : CallerPolicy {
    override fun scopeFor(caller: CallerIdentity, requested: ToolScope): ToolScope = requested

    override fun requiresConsent(caller: CallerIdentity, tool: CatalogTool, base: ConsentRequirement): ConsentTerms = ConsentTerms.unchanged(base)

    override fun toString() = "OpenCallerPolicy"
}

/**
 * **Off by default.** The first design, kept for when a permission mechanism needs it (docs/third-party-acp.md section 9):
 * - a third-party app ([CallerKind.APP]) that did not pass a toolScope has **no tools at all** (it can only chat);
 * - every tool call of a third-party app is confirmed, read tools too, whatever the user policy ("always allow") or the session ("do not ask
 *   again") says, and the dialog offers neither "always allow" nor "do not ask again in this conversation": only "allow once" and "decline".
 *
 * AgentOS itself ([CallerKind.SELF]), the desktop and the runtime are not touched by it.
 */
object StrictCallerPolicy : CallerPolicy {
    override fun scopeFor(caller: CallerIdentity, requested: ToolScope): ToolScope =
        if (caller.kind == CallerKind.APP && !requested.restricted) ToolScope.NONE else requested

    override fun requiresConsent(caller: CallerIdentity, tool: CatalogTool, base: ConsentRequirement): ConsentTerms =
        if (caller.kind == CallerKind.APP) ConsentTerms(ConsentRequirement.ASK, offerSessionRemember = false, offerAlwaysAllow = false) else ConsentTerms.unchanged(base)

    override fun toString() = "StrictCallerPolicy"
}
