package org.agentos.sample.sms.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun entry(parts: Int = 1, state: OutboxState = OutboxState.QUEUED, sent: Long = 0, delivered: Long = 0) = OutboxEntry(
    id = "1", to = "+8613800138000", text = "hi", parts = parts, state = state, sentMask = sent, deliveredMask = delivered,
    error = null, createdAt = 1_000, updatedAt = 1_000,
)

/** outbox 的状态机：queued → sent → delivered、queued → failed、重复回调幂等、乱序。 */
class OutboxMachineTest {
    @Test fun `queued to sent to delivered for a single part`() {
        val queued = entry()
        val sent = OutboxMachine.onSent(queued, 0, now = 2_000)
        assertEquals(OutboxState.SENT, sent.state)
        assertEquals(1, sent.sentParts)
        assertEquals(2_000, sent.updatedAt)
        val delivered = OutboxMachine.onDelivered(sent, 0, now = 3_000)
        assertEquals(OutboxState.DELIVERED, delivered.state)
        assertEquals(1, delivered.deliveredParts)
        assertEquals(3_000, delivered.updatedAt)
        assertNull(delivered.error)
    }

    @Test fun `queued to failed when the submission or a part fails`() {
        val failed = OutboxMachine.onFailed(entry(), "radio_off", now = 2_000)
        assertEquals(OutboxState.FAILED, failed.state)
        assertEquals("radio_off", failed.error)
        assertEquals(2_000, failed.updatedAt)
    }

    @Test fun `a multi-part message is sent only when every part is sent`() {
        var e = entry(parts = 3)
        e = OutboxMachine.onSent(e, 0, 2_000)
        assertEquals(OutboxState.QUEUED, e.state)
        e = OutboxMachine.onSent(e, 2, 2_100)
        assertEquals(OutboxState.QUEUED, e.state)
        assertEquals(2, e.sentParts)
        e = OutboxMachine.onSent(e, 1, 2_200)
        assertEquals(OutboxState.SENT, e.state)
        assertEquals(3, e.sentParts)
    }

    @Test fun `delivered needs every part delivered`() {
        var e = entry(parts = 2)
        e = OutboxMachine.onSent(OutboxMachine.onSent(e, 0, 2_000), 1, 2_000)
        e = OutboxMachine.onDelivered(e, 1, 3_000)
        assertEquals(OutboxState.SENT, e.state)
        assertEquals(1, e.deliveredParts)
        e = OutboxMachine.onDelivered(e, 0, 3_100)
        assertEquals(OutboxState.DELIVERED, e.state)
    }

    @Test fun `a repeated callback changes nothing and returns the same object`() {
        val sent = OutboxMachine.onSent(entry(parts = 2), 0, 2_000)
        assertSame(sent, OutboxMachine.onSent(sent, 0, 9_000))
        val delivered = OutboxMachine.onDelivered(sent, 0, 3_000)
        assertSame(delivered, OutboxMachine.onDelivered(delivered, 0, 9_000))
        val failed = OutboxMachine.onFailed(entry(), "x", 2_000)
        assertSame(failed, OutboxMachine.onFailed(failed, "y", 9_000))
        assertEquals("x", failed.error)
    }

    @Test fun `delivered before sent is accepted - delivery implies sent`() {
        val e = OutboxMachine.onDelivered(entry(), 0, 2_000)
        assertEquals(OutboxState.DELIVERED, e.state)
        assertEquals(1, e.sentParts)
        // the late sent callback is a no-op
        assertSame(e, OutboxMachine.onSent(e, 0, 3_000))
    }

    @Test fun `out of order across parts`() {
        var e = entry(parts = 2)
        e = OutboxMachine.onDelivered(e, 1, 2_000) // part 1 delivered before anything else
        assertEquals(OutboxState.QUEUED, e.state)
        e = OutboxMachine.onSent(e, 0, 2_100)
        assertEquals(OutboxState.SENT, e.state) // both parts are now known sent
        e = OutboxMachine.onDelivered(e, 0, 2_200)
        assertEquals(OutboxState.DELIVERED, e.state)
    }

    @Test fun `terminal states do not move`() {
        val delivered = OutboxMachine.onDelivered(entry(), 0, 2_000)
        assertSame(delivered, OutboxMachine.onFailed(delivered, "late failure", 3_000))
        val failed = OutboxMachine.onFailed(entry(), "no_service", 2_000)
        assertSame(failed, OutboxMachine.onSent(failed, 0, 3_000))
        assertSame(failed, OutboxMachine.onDelivered(failed, 0, 3_000))
    }

    @Test fun `a delivery report can still fail a sent message`() {
        val sent = OutboxMachine.onSent(entry(), 0, 2_000)
        val failed = OutboxMachine.onFailed(sent, "delivery_failed", 3_000)
        assertEquals(OutboxState.FAILED, failed.state)
        assertEquals("delivery_failed", failed.error)
    }

    @Test fun `one failed part fails the whole message even if the others were sent`() {
        var e = OutboxMachine.onSent(entry(parts = 2), 0, 2_000)
        e = OutboxMachine.onFailed(e, "limit_exceeded", 2_100)
        assertEquals(OutboxState.FAILED, e.state)
        assertSame(e, OutboxMachine.onSent(e, 1, 2_200))
    }

    @Test fun `part numbers outside the message are ignored`() {
        val e = entry(parts = 2)
        assertSame(e, OutboxMachine.onSent(e, 2, 2_000))
        assertSame(e, OutboxMachine.onSent(e, -1, 2_000))
        assertSame(e, OutboxMachine.onDelivered(e, 5, 2_000))
    }

    @Test fun `full masks`() {
        assertEquals(0b1L, OutboxMachine.fullMask(1))
        assertEquals(0b111L, OutboxMachine.fullMask(3))
        assertEquals((1L shl 62) - 1, OutboxMachine.fullMask(62))
    }
}

class SendResultsTest {
    @Test fun `result codes map to stable english reasons`() {
        assertEquals("generic_failure", SendResults.errorFor(1))
        assertEquals("radio_off", SendResults.errorFor(2))
        assertEquals("no_service", SendResults.errorFor(4))
        assertEquals("limit_exceeded", SendResults.errorFor(5))
        assertEquals("short_code_not_allowed", SendResults.errorFor(7))
        assertEquals("short_code_never_allowed", SendResults.errorFor(8))
        assertEquals("error_999", SendResults.errorFor(999))
    }

    @Test fun `delivery reports are classified by the TP-Status`() {
        assertEquals(SendResults.Delivery.DELIVERED, SendResults.classifyDelivery(null))
        assertEquals(SendResults.Delivery.DELIVERED, SendResults.classifyDelivery(0x00))
        assertEquals(SendResults.Delivery.DELIVERED, SendResults.classifyDelivery(0x01))
        assertEquals(SendResults.Delivery.PENDING, SendResults.classifyDelivery(0x20))
        assertEquals(SendResults.Delivery.PENDING, SendResults.classifyDelivery(0x3F))
        assertEquals(SendResults.Delivery.FAILED, SendResults.classifyDelivery(0x40))
        assertEquals(SendResults.Delivery.FAILED, SendResults.classifyDelivery(0x65))
    }

    @Test fun `callbacks round-trip through the PendingIntent data`() {
        val cb = StatusCallback("42", 3, StatusCallback.Kind.DELIVERED)
        assertEquals("sms-outbox://42/3/delivered", cb.encode())
        assertEquals(cb, StatusCallback.decode(cb.encode()))
        assertEquals(StatusCallback("7", 0, StatusCallback.Kind.SENT), StatusCallback.decode("sms-outbox://7/0/sent"))
    }

    @Test fun `malformed callback data is rejected`() {
        for (bad in listOf(null, "", "http://x/1/0/sent", "sms-outbox://", "sms-outbox://1/0", "sms-outbox://1/x/sent", "sms-outbox://1/-1/sent", "sms-outbox://1/0/other", "sms-outbox:///0/sent")) {
            assertNull(bad, StatusCallback.decode(bad))
        }
    }
}

class OutboxTest {
    private var now = 10_000L
    private val outbox = Outbox(InMemoryOutboxStore()) { now }

    @Test fun `create assigns increasing ids and starts queued`() {
        val a = outbox.create("+8613800138000", "one", 1)
        now += 1_000
        val b = outbox.create("+8613800138001", "two", 2)
        assertEquals("1", a.id)
        assertEquals("2", b.id)
        assertEquals(OutboxState.QUEUED, a.state)
        assertEquals(2, b.parts)
        assertEquals(2, outbox.count())
        assertEquals(listOf("2", "1"), outbox.page(0, 10).map { it.id }) // newest first
        assertEquals(listOf("1"), outbox.page(1, 10).map { it.id })
    }

    @Test fun `callbacks drive the stored entry and the observable list`() {
        val e = outbox.create("+8613800138000", "hi", 1)
        assertEquals(listOf(e.id), outbox.recent.value.map { it.id })
        now += 500
        outbox.onSent(e.id, 0)
        assertEquals(OutboxState.SENT, outbox.get(e.id)!!.state)
        assertEquals(OutboxState.SENT, outbox.recent.value.single().state)
        now += 500
        outbox.onDelivered(e.id, 0)
        assertEquals(OutboxState.DELIVERED, outbox.get(e.id)!!.state)
        assertEquals(11_000, outbox.get(e.id)!!.updatedAt)
    }

    @Test fun `a repeated callback does not touch updated_at`() {
        val e = outbox.create("+8613800138000", "hi", 1)
        now += 500
        outbox.onSent(e.id, 0)
        val stamp = outbox.get(e.id)!!.updatedAt
        now += 5_000
        outbox.onSent(e.id, 0)
        assertEquals(stamp, outbox.get(e.id)!!.updatedAt)
    }

    @Test fun `callbacks for an unknown id are ignored`() {
        assertNull(outbox.onSent("999", 0))
        assertNull(outbox.onFailed("999", "x"))
    }

    @Test fun `createdSince filters by creation time`() {
        outbox.create("a1", "x", 1)
        now += 60_000
        outbox.create("a2", "y", 1)
        assertEquals(1, outbox.createdSince(now - 1_000).size)
        assertEquals(2, outbox.createdSince(0).size)
    }

    @Test fun `clear empties only the outbox and reports how many`() {
        outbox.create("a1", "x", 1)
        outbox.create("a2", "y", 1)
        assertEquals(2, outbox.clear())
        assertEquals(0, outbox.count())
        assertTrue(outbox.recent.value.isEmpty())
        assertEquals(0, outbox.clear())
    }

    @Test fun `part counts are validated`() {
        var failed = false
        try {
            outbox.create("a", "x", 0)
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        assertNotNull(outbox.create("a", "x", Outbox.MAX_PARTS))
    }
}
