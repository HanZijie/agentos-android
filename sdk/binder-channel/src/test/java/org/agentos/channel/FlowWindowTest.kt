package org.agentos.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowWindowTest {
    private val cfg = ChannelConfig() // 65,536 字符 / 32 条 / 32,768 字符 / ack 每 8 条或 8,192 字符

    @Test
    fun defaultsMatchSpec() {
        assertEquals(65_536, cfg.maxMessageChars)
        assertEquals(32, cfg.windowMessages)
        assertEquals(32_768, cfg.windowChars)
        assertEquals(8, cfg.ackEveryMessages)
        assertEquals(8_192, cfg.ackEveryChars)
    }

    @Test
    fun emptyWindowAlwaysAcceptsOneMaxSizedMessage() {
        val w = SendWindow(cfg)
        assertTrue(w.canSend(cfg.maxMessageChars))
        w.onSent(cfg.maxMessageChars)
        assertFalse("second message must wait while a max-sized one is in flight", w.canSend(1))
    }

    @Test
    fun messageCountLimit() {
        val w = SendWindow(cfg)
        repeat(32) {
            assertTrue(w.canSend(10))
            w.onSent(10)
        }
        assertFalse(w.canSend(10))
        assertEquals(SendWindow.AckResult.ADVANCED, w.onAck(1))
        assertTrue(w.canSend(10))
    }

    @Test
    fun charLimit() {
        val w = SendWindow(cfg)
        w.onSent(20_000)
        assertTrue(w.canSend(12_768))
        assertFalse(w.canSend(12_769))
    }

    @Test
    fun ackAccounting() {
        val w = SendWindow(cfg)
        w.onSent(100); w.onSent(200); w.onSent(300)
        assertEquals(600, w.inFlightChars)
        assertEquals(SendWindow.AckResult.ADVANCED, w.onAck(2))
        assertEquals(300, w.inFlightChars)
        assertEquals(1, w.inFlightMessages)
        assertEquals(SendWindow.AckResult.STALE, w.onAck(1))
        assertEquals(SendWindow.AckResult.STALE, w.onAck(2))
        assertEquals(SendWindow.AckResult.BEYOND_SENT, w.onAck(4))
        assertEquals(300, w.inFlightChars)
        assertEquals(SendWindow.AckResult.ADVANCED, w.onAck(3))
        assertEquals(0, w.inFlightChars)
        assertEquals(0, w.inFlightMessages)
    }

    @Test
    fun receiverAcksEveryEightMessages() {
        val r = ReceiveWindow(cfg)
        repeat(20) { assertNull(r.onReceived(10)) }
        val acks = (1..20).map { r.onConsumed(10) }.filter { it >= 0 }
        // 第 8、16 条到阈值；第 20 条时已收到的都处理完（idle）
        assertEquals(listOf(8L, 16L, 20L), acks)
        assertEquals(0, r.unackedMessages)
        assertEquals(0, r.unackedChars)
    }

    @Test
    fun receiverAcksWhenIdle() {
        val r = ReceiveWindow(cfg)
        assertNull(r.onReceived(5))
        assertEquals(1L, r.onConsumed(5))
    }

    @Test
    fun receiverAcksOnChars() {
        val r = ReceiveWindow(cfg)
        repeat(3) { assertNull(r.onReceived(5_000)) }
        assertEquals(-1L, r.onConsumed(5_000))
        assertEquals(2L, r.onConsumed(5_000)) // 10,000 ≥ 8,192
        assertEquals(3L, r.onConsumed(5_000)) // idle
    }

    @Test
    fun receiverDetectsOversize() {
        val r = ReceiveWindow(cfg)
        val v = r.onReceived(cfg.maxMessageChars + 1)
        assertNotNull(v)
        assertTrue(v!!.contains("exceeds"))
    }

    @Test
    fun receiverAllowsOneMaxSizedMessageButNotTwo() {
        val r = ReceiveWindow(cfg)
        assertNull(r.onReceived(cfg.maxMessageChars))
        val v = r.onReceived(1)
        assertNotNull(v)
        assertTrue(v!!.startsWith("window exceeded"))
    }

    @Test
    fun receiverDetectsTooManyMessages() {
        val r = ReceiveWindow(cfg)
        repeat(32) { assertNull(r.onReceived(1)) }
        assertNotNull(r.onReceived(1))
    }

    /** 发送方守窗口时，接收方永远不会判违规（按最坏的 ack 时机：只在阈值处 ack）。 */
    @Test
    fun conformingSenderNeverViolates() {
        val sizes = listOf(1, 64, 900, 5_000, 16_000, 32_768, 65_536, 3)
        val send = SendWindow(cfg)
        val recv = ReceiveWindow(cfg)
        val inFlight = ArrayDeque<Int>()
        var i = 0
        repeat(5_000) {
            val len = sizes[i++ % sizes.size]
            while (!send.canSend(len)) {
                // 接收方处理掉最早的一条；只有它决定 ack 时发送方才释放窗口
                val consumedLen = inFlight.removeFirst()
                val ack = recv.onConsumed(consumedLen)
                if (ack >= 0) send.onAck(ack)
                if (inFlight.isEmpty() && !send.canSend(len)) error("deadlock")
            }
            send.onSent(len)
            inFlight.addLast(len)
            assertNull(recv.onReceived(len))
        }
    }
}
