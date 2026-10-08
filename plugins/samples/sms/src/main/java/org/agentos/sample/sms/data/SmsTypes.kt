package org.agentos.sample.sms.data

/**
 * 隔离 Android 的边界：读系统短信库、发送、打开系统短信界面。JVM 测试用假实现，设备上用 `AndroidSmsGateway`。
 * 这里的类型都是纯 Kotlin，不依赖 android.*。
 */
interface SmsGateway {
    /** 当前授予情况（每次调用实测：用户随时可以在系统设置里撤销）。 */
    fun access(): SmsAccess

    /** 全部会话，按最近一条的时间从新到旧；扫描有上限，见 [ThreadScan.scanTruncated]。需要 READ_SMS。 */
    fun threads(): ThreadScan

    /** 某号码的消息，从新到旧，先按时间过滤再跳过 [MessageQuery.offset] 条。需要 READ_SMS。 */
    fun messages(query: MessageQuery): List<SmsRecord>

    /** 正文包含 [query] 的消息（大小写不敏感），从新到旧，最多 [max] 条。需要 READ_SMS。 */
    fun search(query: String, max: Int): List<SmsRecord>

    /** 按 GSM / UCS-2 规则切成短信段（`SmsManager.divideMessage`），至少一段。 */
    fun divide(text: String): List<String>

    /**
     * 把已分段的短信交给系统发送（`sendMultipartTextMessage`，系统默认短信 SIM）。**只是提交**，结果经 sent / delivered 回调更新 outbox。
     * 提交本身失败（权限、参数）抛 [SmsSendException]。需要 SEND_SMS。
     */
    fun submit(outboxId: String, to: String, parts: List<String>)

    /** 打开系统短信界面并预填收件人和正文（`ACTION_SENDTO smsto:`），不需要任何短信权限。返回是否成功交给了系统。 */
    fun openComposer(to: String, text: String?): Boolean
}

/** 两个短信权限当前是否已授予。 */
data class SmsAccess(val canRead: Boolean, val canSend: Boolean) {
    /** 完整模式 / 部分 / 仅撰写（两个权限都没有）。 */
    val mode: SmsMode
        get() = when {
            canRead && canSend -> SmsMode.FULL
            !canRead && !canSend -> SmsMode.COMPOSE_ONLY
            else -> SmsMode.PARTIAL
        }
}

enum class SmsMode(val wire: String) {
    FULL("full"),
    PARTIAL("partial"),
    COMPOSE_ONLY("compose_only"),
}

/** 系统短信库的 `type` 列。 */
enum class SmsBox(val wire: String) {
    INBOX("inbox"),
    SENT("sent"),
    DRAFT("draft"),
    OUTBOX("outbox"),
    FAILED("failed"),
    QUEUED("queued"),
    OTHER("other");

    companion object {
        fun fromType(type: Int): SmsBox = when (type) {
            1 -> INBOX
            2 -> SENT
            3 -> DRAFT
            4 -> OUTBOX
            5 -> FAILED
            6 -> QUEUED
            else -> OTHER
        }
    }
}

/** 系统短信库里的一条短信。[body] 是原文；遮蔽由工具层按设置做。 */
data class SmsRecord(
    val id: String,
    val threadId: Long,
    val address: String,
    val dateMillis: Long,
    val box: SmsBox,
    val read: Boolean,
    val body: String,
)

/** 一个会话的摘要（[snippet] 是最近一条的原文）。 */
data class ThreadSummary(
    val threadId: Long,
    val address: String,
    val snippet: String,
    val lastDateMillis: Long,
    val lastBox: SmsBox,
    val messageCount: Int,
    val unreadCount: Int,
)

class ThreadScan(val threads: List<ThreadSummary>, val scanTruncated: Boolean)

/** [address] 用 [org.agentos.sample.sms.rules.AddressMatcher] 的规则匹配；[sinceMillis] 含，[untilMillis] 不含。 */
data class MessageQuery(
    val address: String,
    val sinceMillis: Long?,
    val untilMillis: Long?,
    val offset: Int,
    val limit: Int,
)

/** 提交给系统失败；[reason] 是给模型看的英文短语。 */
class SmsSendException(val reason: String, cause: Throwable? = null) : Exception(reason, cause)
