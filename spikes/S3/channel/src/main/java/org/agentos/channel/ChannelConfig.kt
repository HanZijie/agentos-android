package org.agentos.channel

import org.json.JSONObject

/**
 * binder-channel-v1 的参数。两端必须一致：接收方按同一组数值检查对方有没有超限。
 *
 * 单位说明：Java 的 AIDL `String` 在 Parcel 里是 UTF-16，所以长度一律按 `String.length`
 * （UTF-16 code unit，下文记作“字符”）计，线上占用约为 2 倍字节。
 */
data class ChannelConfig(
    /** 单条消息上限（字符）。发送方超过就不发；接收方收到超过的，视为违规并关闭通道。 */
    val maxMessageChars: Int = 65_536,
    /** 在途消息数上限：已发出但对方还没 ack 的条数。 */
    val windowMessages: Int = 64,
    /** 在途字符数上限。在途为 0 时，单条不超过 maxMessageChars 的消息总能发出。 */
    val windowChars: Int = 65_536,
    /** 接收方每处理这么多条，或处理完这么多字符，或收件箱已经处理空了，就回一次 ack。 */
    val ackEveryMessages: Int = maxOf(1, windowMessages / 4),
    val ackEveryChars: Int = maxOf(1, windowChars / 4),
    /** 发送方本地积压（还没交给 Binder）的上限；超过说明对方长时间不消费，关闭通道。 */
    val maxQueuedChars: Long = 4L * 1024 * 1024,
    /** 窗口满后，这么久收不到任何 ack 就关闭通道。 */
    val ackTimeoutMs: Long = 30_000,
    /** 本端主动关闭时，最多等这么久把积压发完再发 close。 */
    val closeFlushTimeoutMs: Long = 2_000,
    /** 只给 spike 用：关掉发送端流控，测无背压时的行为。 */
    val flowControl: Boolean = true,
    /** 只给 spike 用：关掉接收端的超长、超窗口检查。 */
    val enforceInboundLimits: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("maxMessageChars", maxMessageChars)
        .put("windowMessages", windowMessages)
        .put("windowChars", windowChars)
        .put("ackEveryMessages", ackEveryMessages)
        .put("ackEveryChars", ackEveryChars)
        .put("maxQueuedChars", maxQueuedChars)
        .put("ackTimeoutMs", ackTimeoutMs)
        .put("closeFlushTimeoutMs", closeFlushTimeoutMs)
        .put("flowControl", flowControl)
        .put("enforceInboundLimits", enforceInboundLimits)

    companion object {
        /** 未给出的字段取默认值；ackEvery* 未给出时按新的窗口重新推算。 */
        fun fromJson(json: JSONObject?): ChannelConfig {
            val d = ChannelConfig()
            if (json == null) return d
            val windowMessages = json.optInt("windowMessages", d.windowMessages)
            val windowChars = json.optInt("windowChars", d.windowChars)
            return ChannelConfig(
                maxMessageChars = json.optInt("maxMessageChars", d.maxMessageChars),
                windowMessages = windowMessages,
                windowChars = windowChars,
                ackEveryMessages = json.optInt("ackEveryMessages", maxOf(1, windowMessages / 4)),
                ackEveryChars = json.optInt("ackEveryChars", maxOf(1, windowChars / 4)),
                maxQueuedChars = json.optLong("maxQueuedChars", d.maxQueuedChars),
                ackTimeoutMs = json.optLong("ackTimeoutMs", d.ackTimeoutMs),
                closeFlushTimeoutMs = json.optLong("closeFlushTimeoutMs", d.closeFlushTimeoutMs),
                flowControl = json.optBoolean("flowControl", d.flowControl),
                enforceInboundLimits = json.optBoolean("enforceInboundLimits", d.enforceInboundLimits),
            )
        }
    }
}
