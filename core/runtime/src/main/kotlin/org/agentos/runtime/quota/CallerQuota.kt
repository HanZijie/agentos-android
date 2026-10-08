package org.agentos.runtime.quota

import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.errors.ErrorInfo
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Limits for third-party apps (docs/third-party-acp.md 4.6). Only [CallerKind.APP] is limited; AgentOS itself, the desktop and the runtime are
 * never counted, never refused and never reported.
 *
 * @property maxPromptChars The text of one prompt, in characters (text blocks and embedded text resources).
 * @property maxPromptsPerHour Prompts one app may start within [windowMillis] (a sliding window, not a clock hour).
 * @property maxConcurrentPrompts Prompts one app may have running (or queued) at the same time.
 */
data class CallerQuotaConfig(
    val maxPromptChars: Int = 16_000,
    val maxPromptsPerHour: Int = 30,
    val maxConcurrentPrompts: Int = 1,
    val windowMillis: Long = 3_600_000L,
) {
    init {
        require(maxPromptChars > 0) { "maxPromptChars must be positive" }
        require(maxPromptsPerHour > 0) { "maxPromptsPerHour must be positive" }
        require(maxConcurrentPrompts > 0) { "maxConcurrentPrompts must be positive" }
        require(windowMillis > 0) { "windowMillis must be positive" }
    }
}

/** How a prompt that was admitted ended (the task's final state). */
enum class PromptOutcome(val wire: String) {
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    FAILED("failed"),

    /** The result is not known (the runtime or the agent core died during the prompt); a recovery decision is pending. */
    UNKNOWN("unknown"),
}

/** Why a prompt was refused. [wire] is `error.details.reason` on the JSON-RPC error. */
enum class QuotaReason(val wire: String, val code: ErrorCode) {
    /** Over [CallerQuotaConfig.maxPromptChars]: `invalid_params`, not retryable (shorten the text). */
    TOO_LARGE("too_large", ErrorCode.INVALID_PARAMS),

    /** The app already has [CallerQuotaConfig.maxConcurrentPrompts] prompts in progress: `quota_exceeded`, retry when the running one ends. */
    BUSY("busy", ErrorCode.QUOTA_EXCEEDED),

    /** The app started [CallerQuotaConfig.maxPromptsPerHour] prompts in the last hour: `quota_exceeded`, `retryAfterSeconds` says when. */
    HOURLY("hourly", ErrorCode.QUOTA_EXCEEDED),
}

/** What [CallerQuota.admit] decided. */
sealed interface Admission {
    /**
     * The prompt may start. Hand it back with [CallerQuota.release] when the task ends, or [CallerQuota.cancelAdmission] if the prompt could not
     * be started after all.
     */
    class Admitted internal constructor(
        val caller: CallerIdentity,
        val promptChars: Int,
        val admittedAtMillis: Long,
        /** False for callers that are not limited (nothing was counted). */
        internal val counted: Boolean,
    ) : Admission {
        private val settled = java.util.concurrent.atomic.AtomicBoolean(false)

        internal fun settleOnce(): Boolean = settled.compareAndSet(false, true)
    }

    class Rejected internal constructor(
        val reason: QuotaReason,
        /** The limit that was hit (characters, prompts in progress, or prompts per window). */
        val limit: Int,
        /** For [QuotaReason.HOURLY]: how long until the oldest prompt in the window falls out of it. */
        val retryAfterMillis: Long?,
    ) : Admission {
        /** The error to give the caller (JSON-RPC `invalid_params` or `quota_exceeded`, `details.reason`). No prompt text, no package name. */
        fun toError(): ErrorInfo {
            val message = when (reason) {
                QuotaReason.TOO_LARGE -> "The prompt is longer than $limit characters."
                QuotaReason.BUSY -> "This app already has a prompt in progress. Wait for it to finish."
                QuotaReason.HOURLY -> "This app has used its $limit prompts for the hour."
            }
            return reason.code.info(
                message,
                buildJsonObject {
                    put("reason", reason.wire)
                    put("limit", limit)
                    retryAfterMillis?.let { put("retryAfterSeconds", (it + 999) / 1000) }
                },
            )
        }
    }
}

/**
 * One finished prompt, given to every [CallerUsageListener] exactly once (the usage count that C's `CallerRegistry` keeps per app).
 * Prompts that were refused and prompts that could not be started are not reported.
 *
 * @property promptsInWindow Prompts this app has started within the last hour, this one included.
 */
data class PromptUsage(
    val caller: CallerIdentity,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val promptChars: Int,
    val outcome: PromptOutcome,
    val promptsInWindow: Int,
)

/** Called once per finished prompt of a limited caller, on the thread that ended it. An exception thrown here is caught and dropped. */
fun interface CallerUsageListener {
    fun onPromptFinished(usage: PromptUsage)
}

/** What [CallerQuota.usage] reports about one app right now. */
data class CallerUsage(
    val activePrompts: Int,
    val promptsInWindow: Int,
    /** Prompts admitted since the :agent process started (the counters live in memory and restart with the process). */
    val promptsTotal: Long,
    val lastPromptAtMillis: Long?,
)

/**
 * The per-app limits (docs/third-party-acp.md 4.6): a plain class with a clock, no Android, no coroutines.
 *
 * Order of checks in [admit]: text length (a refusal that never counts), then prompts in progress, then the hourly window. A refused prompt
 * is not counted anywhere. Counters are per [CallerIdentity.ownerKey] (one app = one uid), shared by all of its connections and sessions,
 * and live in memory: they reset when the :agent process restarts.
 *
 * A prompt is "in progress" from [admit] until its task ends ([release]) — not until the connection closes: a connection that drops does not
 * stop the task (architecture F7), so the app cannot start a second prompt by reconnecting.
 */
class CallerQuota(
    private val config: CallerQuotaConfig = CallerQuotaConfig(),
    private val clock: Clock = Clock.SYSTEM,
) {
    private class State {
        val admittedAt = ArrayDeque<Long>()
        var active = 0
        var total = 0L
        var last: Long? = null
    }

    private val lock = Any()
    private val states = HashMap<String, State>()
    private val listeners = CopyOnWriteArrayList<CallerUsageListener>()

    fun addListener(listener: CallerUsageListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: CallerUsageListener) {
        listeners.remove(listener)
    }

    /**
     * Only the text-length rule of [admit], without counting anything: the ACP layer calls it while it validates the prompt, so an app's
     * over-long prompt is answered with `invalid_params` / `too_large` before the general size limit of the protocol would apply.
     */
    fun checkSize(caller: CallerIdentity, promptChars: Int): Admission.Rejected? =
        if (caller.kind == CallerKind.APP && promptChars > config.maxPromptChars) Admission.Rejected(QuotaReason.TOO_LARGE, config.maxPromptChars, null) else null

    /** Decides whether [caller] may start a prompt of [promptChars] characters. Counts it when admitted. */
    fun admit(caller: CallerIdentity, promptChars: Int): Admission {
        val now = clock.nowMillis()
        if (caller.kind != CallerKind.APP) return Admission.Admitted(caller, promptChars, now, counted = false)
        checkSize(caller, promptChars)?.let { return it }
        synchronized(lock) {
            val state = states.getOrPut(caller.ownerKey) { State() }
            prune(state, now)
            if (state.active >= config.maxConcurrentPrompts) return Admission.Rejected(QuotaReason.BUSY, config.maxConcurrentPrompts, null)
            if (state.admittedAt.size >= config.maxPromptsPerHour) {
                val retryAfter = (state.admittedAt.first() + config.windowMillis - now).coerceAtLeast(1)
                return Admission.Rejected(QuotaReason.HOURLY, config.maxPromptsPerHour, retryAfter)
            }
            state.active++
            state.total++
            state.last = now
            state.admittedAt.addLast(now)
            return Admission.Admitted(caller, promptChars, now, counted = true)
        }
    }

    /** The task of an admitted prompt ended: frees the slot and tells the listeners. Calling it again for the same prompt does nothing. */
    fun release(admission: Admission.Admitted, outcome: PromptOutcome) {
        if (!admission.counted || !admission.settleOnce()) return
        val now = clock.nowMillis()
        val inWindow = synchronized(lock) {
            val state = states[admission.caller.ownerKey] ?: return@synchronized 0
            if (state.active > 0) state.active--
            prune(state, now)
            state.admittedAt.size
        }
        val usage = PromptUsage(admission.caller, admission.admittedAtMillis, now, admission.promptChars, outcome, inWindow)
        for (l in listeners) {
            try {
                l.onPromptFinished(usage)
            } catch (e: Exception) {
                // a listener must never break the prompt flow
            }
        }
    }

    /** The prompt could not be started after it was admitted (the scheduler refused it): frees the slot and gives the hourly count back. */
    fun cancelAdmission(admission: Admission.Admitted) {
        if (!admission.counted || !admission.settleOnce()) return
        synchronized(lock) {
            val state = states[admission.caller.ownerKey] ?: return
            if (state.active > 0) state.active--
            if (state.total > 0) state.total--
            state.admittedAt.remove(admission.admittedAtMillis)
        }
    }

    /** The current numbers for one caller (zeros for callers that are not limited). */
    fun usage(caller: CallerIdentity): CallerUsage {
        if (caller.kind != CallerKind.APP) return CallerUsage(0, 0, 0, null)
        val now = clock.nowMillis()
        synchronized(lock) {
            val state = states[caller.ownerKey] ?: return CallerUsage(0, 0, 0, null)
            prune(state, now)
            return CallerUsage(state.active, state.admittedAt.size, state.total, state.last)
        }
    }

    /** Forget everything about one app (the user revoked it, or the app was uninstalled). Prompts still running finish normally. */
    fun forget(caller: CallerIdentity) {
        synchronized(lock) { states.remove(caller.ownerKey) }
    }

    private fun prune(state: State, now: Long) {
        val cutoff = now - config.windowMillis
        while (state.admittedAt.isNotEmpty() && state.admittedAt.first() <= cutoff) state.admittedAt.removeFirst()
    }
}
