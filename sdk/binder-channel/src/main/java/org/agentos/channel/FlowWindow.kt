package org.agentos.channel

/**
 * binder-channel-v1 第 5 节的流控账本，纯 Kotlin，不依赖 Android，由 [BinderChannel] 在锁内调用。
 *
 * 长度单位一律是 `String.length`（UTF-16 code unit，下称“字符”）。
 */

/** 发送方：记录已交给 Binder、对方还没 ack 的消息。 */
class SendWindow(private val config: ChannelConfig) {
    private val sizes = ArrayDeque<Int>()
    var inFlightChars: Long = 0
        private set
    var sent: Long = 0
        private set
    var acked: Long = 0
        private set

    val inFlightMessages: Int get() = sizes.size

    /**
     * 这条消息现在能不能发出。在途为 0 时总能发出（单条不超过上限的消息不会被窗口卡死）；
     * 否则要求在途条数 < windowMessages，且在途字符 + 本条 ≤ windowChars。
     */
    fun canSend(length: Int): Boolean {
        val n = sizes.size
        return n == 0 || (n < config.windowMessages && inFlightChars + length <= config.windowChars)
    }

    /** 已交给 Binder。 */
    fun onSent(length: Int) {
        sent++
        sizes.addLast(length)
        inFlightChars += length
    }

    enum class AckResult { ADVANCED, STALE, BEYOND_SENT }

    /** 对方回了累计值 [consumed]。超过已发条数属于违规；不大于上次的值视为过期，忽略。 */
    fun onAck(consumed: Long): AckResult {
        if (consumed > sent) return AckResult.BEYOND_SENT
        if (consumed <= acked) return AckResult.STALE
        var n = consumed - acked
        while (n-- > 0) inFlightChars -= sizes.removeFirst()
        acked = consumed
        return AckResult.ADVANCED
    }
}

/** 接收方：记录收到但还没 ack 的消息，决定什么时候回 ack、对方是否超出窗口。 */
class ReceiveWindow(private val config: ChannelConfig) {
    private val sizes = ArrayDeque<Int>()
    var unackedChars: Long = 0
        private set
    var received: Long = 0
        private set
    var consumed: Long = 0
        private set
    private var lastAck: Long = 0
    private var consumedCharsSinceAck: Long = 0

    val unackedMessages: Int get() = sizes.size

    /**
     * 收到一条。返回违规原因，没有违规返回 null。
     * 违规：单条超长；未 ack 条数 > windowMessages；未 ack 条数 > 1 且未 ack 字符 > windowChars。
     */
    fun onReceived(length: Int): String? {
        received++
        sizes.addLast(length)
        unackedChars += length
        if (length > config.maxMessageChars) {
            return "message of $length chars exceeds ${config.maxMessageChars}"
        }
        val n = sizes.size
        if (n > config.windowMessages || (n > 1 && unackedChars > config.windowChars)) {
            return "window exceeded: $n messages / $unackedChars chars unacked"
        }
        return null
    }

    /**
     * 上层处理完一条（按收到的顺序）。需要回 ack 时返回累计值，否则返回 -1。
     * 每处理 ackEveryMessages 条、或 ackEveryChars 字符、或把已收到的都处理完时回一次。
     */
    fun onConsumed(length: Int): Long {
        consumed++
        consumedCharsSinceAck += length
        val pending = consumed - lastAck
        val idle = consumed == received
        if (pending <= 0) return -1
        if (pending < config.ackEveryMessages && consumedCharsSinceAck < config.ackEveryChars && !idle) return -1
        var n = pending
        while (n-- > 0 && sizes.isNotEmpty()) unackedChars -= sizes.removeFirst()
        lastAck = consumed
        consumedCharsSinceAck = 0
        return consumed
    }
}
