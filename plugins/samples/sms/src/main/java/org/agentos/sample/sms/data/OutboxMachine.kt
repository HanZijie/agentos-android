package org.agentos.sample.sms.data

/**
 * 发送记录（`outbox`）里一条的状态。`sms_send` 返回的是“已提交”，不承诺送达；状态由系统的 sent / delivered 回调推动。
 */
enum class OutboxState(val wire: String) {
    QUEUED("queued"),
    SENT("sent"),
    DELIVERED("delivered"),
    FAILED("failed");

    val terminal: Boolean get() = this == DELIVERED || this == FAILED

    companion object {
        fun fromWire(value: String?): OutboxState = entries.firstOrNull { it.wire == value } ?: FAILED
    }
}

/**
 * @property parts 短信段数（成本：每段计一条）
 * @property sentMask / [deliveredMask] 第几段已经 sent / delivered（位图，第 0 段是最低位），所以重复回调不会重复计数
 * @property error 失败原因（英文短语），只在 [OutboxState.FAILED] 时有
 */
data class OutboxEntry(
    val id: String,
    val to: String,
    val text: String,
    val parts: Int,
    val state: OutboxState,
    val sentMask: Long,
    val deliveredMask: Long,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val sentParts: Int get() = java.lang.Long.bitCount(sentMask)
    val deliveredParts: Int get() = java.lang.Long.bitCount(deliveredMask)
}

/**
 * outbox 的状态机（纯函数，JVM 可测）。
 *
 * ```
 * queued ──所有段 sent──▶ sent ──所有段 delivered──▶ delivered
 *    │                     │
 *    └──任一段失败──▶ failed ◀──送达报告失败──┘
 * ```
 * - **终态**：delivered、failed。进了终态的记录不再变化（迟到的回调被忽略）。
 * - **幂等**：同一段的同一个回调重复到达，结果不变——返回的是同一个对象（`===`），调用方据此跳过落盘。
 * - **乱序**：delivered 先于 sent 到达时，delivered 蕴含 sent（没发出去不可能送达），所以同时记两个位图。
 * - 段号越界（不属于这条记录）的回调被忽略。
 */
object OutboxMachine {
    fun fullMask(parts: Int): Long = if (parts >= 63) -1L ushr 1 else (1L shl parts) - 1

    fun onSent(entry: OutboxEntry, part: Int, now: Long): OutboxEntry {
        if (entry.state.terminal || part !in 0 until entry.parts) return entry
        val mask = entry.sentMask or (1L shl part)
        if (mask == entry.sentMask) return entry
        val full = mask == fullMask(entry.parts)
        return entry.copy(sentMask = mask, state = if (full) OutboxState.SENT else entry.state, updatedAt = now)
    }

    fun onDelivered(entry: OutboxEntry, part: Int, now: Long): OutboxEntry {
        if (entry.state.terminal || part !in 0 until entry.parts) return entry
        val bit = 1L shl part
        val sent = entry.sentMask or bit
        val delivered = entry.deliveredMask or bit
        if (sent == entry.sentMask && delivered == entry.deliveredMask) return entry
        val full = fullMask(entry.parts)
        val state = when {
            delivered == full -> OutboxState.DELIVERED
            sent == full -> OutboxState.SENT
            else -> entry.state
        }
        return entry.copy(sentMask = sent, deliveredMask = delivered, state = state, updatedAt = now)
    }

    /** 提交失败、某一段发送失败、或送达报告说失败。已送达 / 已失败的不改。 */
    fun onFailed(entry: OutboxEntry, reason: String, now: Long): OutboxEntry {
        if (entry.state.terminal) return entry
        return entry.copy(state = OutboxState.FAILED, error = reason, updatedAt = now)
    }
}

/** 系统回调里的结果码 / 状态报告 → 英文原因 / 结论（纯函数）。 */
object SendResults {
    /** `sent` 回调的结果码（`SmsManager.RESULT_ERROR_*`）；`Activity.RESULT_OK` = -1 表示这一段已发出。 */
    const val RESULT_OK = -1

    fun errorFor(resultCode: Int): String = when (resultCode) {
        1 -> "generic_failure"
        2 -> "radio_off"
        3 -> "null_pdu"
        4 -> "no_service"
        5 -> "limit_exceeded"
        6 -> "fdn_check_failure"
        7 -> "short_code_not_allowed"
        8 -> "short_code_never_allowed"
        9 -> "radio_not_available"
        10 -> "network_reject"
        11 -> "invalid_arguments"
        12 -> "invalid_state"
        13 -> "no_memory"
        14 -> "invalid_sms_format"
        15 -> "system_error"
        16 -> "modem_error"
        17 -> "network_error"
        18 -> "encoding_error"
        19 -> "invalid_smsc_address"
        20 -> "operation_not_allowed"
        21 -> "internal_error"
        22 -> "no_resources"
        23 -> "cancelled"
        24 -> "request_not_supported"
        32 -> "no_default_sms_app"
        else -> "error_$resultCode"
    }

    enum class Delivery { DELIVERED, PENDING, FAILED }

    /**
     * 送达报告的 TP-Status（`SmsMessage.getStatus()`）：0x00–0x1F 已送达（含“已转发但无法确认”）；0x20–0x3F 暂时失败、网络还在重试（不算结论）；
     * 0x40 及以上是永久失败。拿不到报告内容（[status] 为 null）时，回调本身只在有了最终结论时触发，按已送达处理。
     */
    fun classifyDelivery(status: Int?): Delivery = when {
        status == null -> Delivery.DELIVERED
        status in 0x00..0x1F -> Delivery.DELIVERED
        status in 0x20..0x3F -> Delivery.PENDING
        else -> Delivery.FAILED
    }
}

/**
 * sent / delivered 回调的“身份”：编进 PendingIntent 的 data（`sms-outbox://<id>/<part>/<kind>`），
 * 这样每段每种回调的 PendingIntent 互不相同（PendingIntent 按 data 区分，extras 不参与）。
 */
data class StatusCallback(val outboxId: String, val part: Int, val kind: Kind) {
    enum class Kind(val wire: String) { SENT("sent"), DELIVERED("delivered") }

    fun encode(): String = "$SCHEME://$outboxId/$part/${kind.wire}"

    companion object {
        const val SCHEME = "sms-outbox"

        fun decode(uri: String?): StatusCallback? {
            if (uri == null || !uri.startsWith("$SCHEME://")) return null
            val parts = uri.removePrefix("$SCHEME://").split('/')
            if (parts.size != 3) return null
            val id = parts[0].takeIf { it.isNotEmpty() } ?: return null
            val index = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val kind = Kind.entries.firstOrNull { it.wire == parts[2] } ?: return null
            return StatusCallback(id, index, kind)
        }
    }
}
