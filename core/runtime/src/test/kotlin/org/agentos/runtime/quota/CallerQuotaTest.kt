package org.agentos.runtime.quota

import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.testing.ManualClock
import org.agentos.runtime.testing.TestRuntime
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/third-party-acp.md 4.6: the per-app limits, with a clock we turn by hand. */
class CallerQuotaTest {
    private val clock = ManualClock()
    private val app = TestRuntime.APP
    private val other = TestRuntime.OTHER_APP

    private fun quota(
        chars: Int = 16_000,
        perHour: Int = 30,
        concurrent: Int = 1,
    ) = CallerQuota(CallerQuotaConfig(maxPromptChars = chars, maxPromptsPerHour = perHour, maxConcurrentPrompts = concurrent), clock)

    private fun CallerQuota.start(caller: CallerIdentity = app, chars: Int = 10): Admission.Admitted = assertIs(admit(caller, chars))

    private fun CallerQuota.refused(caller: CallerIdentity = app, chars: Int = 10): Admission.Rejected = assertIs(admit(caller, chars))

    @Test
    fun `the defaults are the numbers of the design - 16000 characters, 30 an hour, one at a time`() {
        val c = CallerQuotaConfig()
        assertEquals(16_000, c.maxPromptChars)
        assertEquals(30, c.maxPromptsPerHour)
        assertEquals(1, c.maxConcurrentPrompts)
        assertEquals(3_600_000L, c.windowMillis)
    }

    @Test
    fun `one prompt at a time - the second is refused with busy until the first is released`() {
        val q = quota()
        val first = q.start()
        val second = q.refused()
        assertEquals(QuotaReason.BUSY, second.reason)
        assertEquals(ErrorCode.QUOTA_EXCEEDED, second.toError().code, "rate_limited on the wire: quota_exceeded, retryable")
        assertTrue(second.toError().retryable)
        assertEquals("busy", second.toError().details!!["reason"]!!.jsonPrimitive.content)
        q.release(first, PromptOutcome.COMPLETED)
        q.start()
    }

    @Test
    fun `a refused prompt is not counted and does not hold a slot`() {
        val q = quota(perHour = 2)
        val first = q.start()
        q.refused()
        q.refused()
        q.release(first, PromptOutcome.COMPLETED)
        q.start().also { q.release(it, PromptOutcome.COMPLETED) }
        assertEquals(QuotaReason.HOURLY, q.refused().reason, "2 admitted, not 4")
    }

    @Test
    fun `too long is refused with invalid_params and the reason too_large, before anything is counted`() {
        val q = quota(chars = 100)
        val big = q.refused(chars = 101)
        assertEquals(QuotaReason.TOO_LARGE, big.reason)
        val error = big.toError()
        assertEquals(ErrorCode.INVALID_PARAMS, error.code)
        assertFalse(error.retryable, "shorten the text")
        assertEquals("too_large", error.details!!["reason"]!!.jsonPrimitive.content)
        assertEquals(100, error.details!!["limit"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, q.usage(app).activePrompts)
        assertEquals(0L, q.usage(app).promptsTotal)
        q.start(chars = 100) // exactly the limit is fine
    }

    @Test
    fun `too long wins over busy - the caller learns the text is the problem`() {
        val q = quota(chars = 100)
        q.start()
        assertEquals(QuotaReason.TOO_LARGE, q.refused(chars = 101).reason)
    }

    @Test
    fun `thirty prompts an hour - the thirty-first is refused with hourly and says when to come back`() {
        val q = quota(perHour = 30)
        repeat(30) {
            q.release(q.start(), PromptOutcome.COMPLETED)
            clock.advance(60_000) // a minute apart: 30 minutes in all
        }
        val over = q.refused()
        assertEquals(QuotaReason.HOURLY, over.reason)
        assertEquals(ErrorCode.QUOTA_EXCEEDED, over.toError().code)
        assertEquals("hourly", over.toError().details!!["reason"]!!.jsonPrimitive.content)
        // the oldest prompt was 30 minutes ago: it leaves the window in 30 minutes
        assertEquals(30 * 60L, over.toError().details!!["retryAfterSeconds"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `the window slides - a prompt falls out an hour after it started`() {
        val q = quota(perHour = 2)
        q.release(q.start(), PromptOutcome.COMPLETED) // t = 0
        clock.advance(10 * 60_000)
        q.release(q.start(), PromptOutcome.COMPLETED) // t = 10 min
        assertEquals(QuotaReason.HOURLY, q.refused().reason)
        clock.advance(50 * 60_000 - 1) // t = 59:59.999
        assertEquals(QuotaReason.HOURLY, q.refused().reason, "the first one is still inside the window")
        clock.advance(1) // t = 60:00: the first one is out
        q.release(q.start(), PromptOutcome.COMPLETED)
        assertEquals(QuotaReason.HOURLY, q.refused().reason, "the second (t = 10 min) and the new one are inside")
        assertEquals(2, q.usage(app).promptsInWindow)
    }

    @Test
    fun `apps do not share anything`() {
        val q = quota(perHour = 1)
        val a = q.start(app)
        val b = q.start(other)
        assertEquals(QuotaReason.BUSY, q.refused(app).reason)
        q.release(a, PromptOutcome.COMPLETED)
        assertEquals(QuotaReason.HOURLY, q.refused(app).reason)
        assertEquals(1, q.usage(other).activePrompts)
        q.release(b, PromptOutcome.COMPLETED)
    }

    @Test
    fun `the same app on two connections is one app`() {
        val q = quota()
        val connectionOne = CallerIdentity(app.uid, CallerKind.APP, "com.example.app")
        val connectionTwo = CallerIdentity(app.uid, CallerKind.APP, "com.example.app")
        q.start(connectionOne)
        assertEquals(QuotaReason.BUSY, q.refused(connectionTwo).reason)
    }

    @Test
    fun `AgentOS itself, the desktop and the runtime are never limited, counted or reported`() {
        val q = quota(chars = 10, perHour = 1)
        val seen = mutableListOf<PromptUsage>()
        q.addListener { seen += it }
        for (caller in listOf(TestRuntime.SELF, TestRuntime.DESKTOP, CallerIdentity.SYSTEM)) {
            repeat(5) {
                val a = q.start(caller, chars = 1_000_000)
                q.start(caller, chars = 1_000_000) // and in parallel
                q.release(a, PromptOutcome.COMPLETED)
            }
            assertEquals(CallerUsage(0, 0, 0, null), q.usage(caller))
            assertNull(q.checkSize(caller, 1_000_000))
        }
        assertTrue(seen.isEmpty(), "nothing is reported for callers that are not limited")
    }

    @Test
    fun `the listener is called once per finished prompt with what it needs`() {
        val q = quota()
        val seen = mutableListOf<PromptUsage>()
        q.addListener { seen += it }
        val a = q.start(chars = 42)
        clock.advance(5_000)
        q.release(a, PromptOutcome.CANCELLED)
        q.release(a, PromptOutcome.FAILED) // the same prompt again: nothing
        val usage = seen.single()
        assertEquals(app, usage.caller)
        assertEquals(42, usage.promptChars)
        assertEquals(PromptOutcome.CANCELLED, usage.outcome)
        assertEquals(a.admittedAtMillis, usage.startedAtMillis)
        assertEquals(a.admittedAtMillis + 5_000, usage.finishedAtMillis)
        assertEquals(1, usage.promptsInWindow)
    }

    @Test
    fun `refused prompts are not reported`() {
        val q = quota(chars = 10)
        val seen = mutableListOf<PromptUsage>()
        q.addListener { seen += it }
        val a = q.start()
        q.refused()
        q.refused(chars = 11)
        assertTrue(seen.isEmpty())
        q.release(a, PromptOutcome.COMPLETED)
        assertEquals(1, seen.size)
    }

    @Test
    fun `a listener that throws does not stop the others or the release`() {
        val q = quota()
        val seen = mutableListOf<PromptUsage>()
        q.addListener { error("boom") }
        q.addListener { seen += it }
        q.release(q.start(), PromptOutcome.COMPLETED)
        assertEquals(1, seen.size)
        q.start() // the slot was freed
    }

    @Test
    fun `a removed listener hears nothing more, and adding one twice counts once`() {
        val q = quota()
        var calls = 0
        val l = CallerUsageListener { calls++ }
        q.addListener(l)
        q.addListener(l)
        q.release(q.start(), PromptOutcome.COMPLETED)
        assertEquals(1, calls)
        q.removeListener(l)
        q.release(q.start(), PromptOutcome.COMPLETED)
        assertEquals(1, calls)
    }

    @Test
    fun `a prompt that could not be started gives its slot and its count back, and is not reported`() {
        val q = quota(perHour = 1)
        var calls = 0
        q.addListener { calls++ }
        val a = q.start()
        q.cancelAdmission(a)
        q.release(a, PromptOutcome.FAILED) // too late: already settled
        assertEquals(0, calls)
        assertEquals(CallerUsage(0, 0, 0, a.admittedAtMillis), q.usage(app).copy(lastPromptAtMillis = a.admittedAtMillis))
        q.start() // the hour's one prompt is still available
    }

    @Test
    fun `release works whatever the order and frees the slot at once`() {
        val q = quota(concurrent = 2)
        val a = q.start()
        val b = q.start()
        assertEquals(QuotaReason.BUSY, q.refused().reason)
        q.release(b, PromptOutcome.COMPLETED)
        val c = q.start()
        assertEquals(QuotaReason.BUSY, q.refused().reason)
        q.release(a, PromptOutcome.FAILED)
        q.release(c, PromptOutcome.UNKNOWN)
        assertEquals(0, q.usage(app).activePrompts)
        assertEquals(3L, q.usage(app).promptsTotal)
    }

    @Test
    fun `usage reports the live numbers`() {
        val q = quota()
        assertEquals(CallerUsage(0, 0, 0, null), q.usage(app))
        val a = q.start()
        assertEquals(CallerUsage(1, 1, 1, clock.nowMillis()), q.usage(app))
        q.release(a, PromptOutcome.COMPLETED)
        clock.advance(61 * 60_000)
        val now = q.usage(app)
        assertEquals(0, now.activePrompts)
        assertEquals(0, now.promptsInWindow, "the hour is over")
        assertEquals(1L, now.promptsTotal, "the total does not forget")
        assertEquals(a.admittedAtMillis, now.lastPromptAtMillis)
    }

    @Test
    fun `forget clears one app and leaves the others`() {
        val q = quota()
        q.start(app)
        q.start(other)
        q.forget(app)
        assertEquals(CallerUsage(0, 0, 0, null), q.usage(app))
        assertEquals(1, q.usage(other).activePrompts)
    }

    @Test
    fun `numbers that make no sense are rejected`() {
        assertFailsWith<IllegalArgumentException> { CallerQuotaConfig(maxPromptChars = 0) }
        assertFailsWith<IllegalArgumentException> { CallerQuotaConfig(maxPromptsPerHour = 0) }
        assertFailsWith<IllegalArgumentException> { CallerQuotaConfig(maxConcurrentPrompts = 0) }
        assertFailsWith<IllegalArgumentException> { CallerQuotaConfig(windowMillis = 0) }
    }

    @Test
    fun `the error text has no prompt text and no package name`() {
        val q = quota(chars = 5)
        val text = q.refused(chars = 6).toError().message
        assertFalse(app.label!! in text)
        assertTrue("5" in text)
    }

    @Test
    fun `concurrent admits never let more than the limit through`() {
        val q = quota(perHour = 1_000, concurrent = 3)
        val admitted = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..16).map { Thread { repeat(50) { if (q.admit(app, 1) is Admission.Admitted) admitted.incrementAndGet() } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(3, admitted.get(), "nobody released: exactly 3 slots")
        assertEquals(3, q.usage(app).activePrompts)
    }
}
