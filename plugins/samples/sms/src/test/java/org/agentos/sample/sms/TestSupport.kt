package org.agentos.sample.sms

import org.agentos.sample.sms.data.InMemoryOutboxStore
import org.agentos.sample.sms.data.InMemorySmsSettings
import org.agentos.sample.sms.data.MessageQuery
import org.agentos.sample.sms.data.Outbox
import org.agentos.sample.sms.data.SmsAccess
import org.agentos.sample.sms.data.SmsBox
import org.agentos.sample.sms.data.SmsGateway
import org.agentos.sample.sms.data.SmsRecord
import org.agentos.sample.sms.data.SmsSendException
import org.agentos.sample.sms.data.ThreadScan
import org.agentos.sample.sms.data.ThreadSummary
import org.agentos.sample.sms.rules.AddressMatcher
import org.agentos.sample.sms.tools.SmsTools

/** JVM 测试用的假网关：内存里的“系统短信库”，记录每次提交 / 撰写。 */
class FakeGateway(
    var access: SmsAccess = SmsAccess(canRead = true, canSend = true),
) : SmsGateway {
    val records = ArrayList<SmsRecord>()
    val submitted = ArrayList<Submission>()
    val composed = ArrayList<Pair<String, String?>>()
    var submitFailure: SmsSendException? = null
    var composerAvailable = true
    var scanTruncated = false

    data class Submission(val outboxId: String, val to: String, val parts: List<String>)

    private var nextId = 1L

    fun add(address: String, body: String, date: Long, box: SmsBox = SmsBox.INBOX, read: Boolean = false, threadId: Long = address.hashCode().toLong().let { if (it < 0) -it else it + 1 }) {
        records += SmsRecord((nextId++).toString(), threadId, address, date, box, read, body)
    }

    override fun access() = access

    override fun threads(): ThreadScan {
        val byThread = records.groupBy { it.threadId }
        val threads = byThread.values.map { rows ->
            val latest = rows.maxBy { it.dateMillis }
            ThreadSummary(
                threadId = latest.threadId, address = latest.address, snippet = latest.body, lastDateMillis = latest.dateMillis,
                lastBox = latest.box, messageCount = rows.size, unreadCount = rows.count { !it.read && it.box == SmsBox.INBOX },
            )
        }.sortedByDescending { it.lastDateMillis }
        return ThreadScan(threads, scanTruncated)
    }

    override fun messages(query: MessageQuery): List<SmsRecord> =
        records.filter { AddressMatcher.matches(it.address, query.address) }
            .filter { query.sinceMillis == null || it.dateMillis >= query.sinceMillis }
            .filter { query.untilMillis == null || it.dateMillis < query.untilMillis }
            .sortedByDescending { it.dateMillis }
            .drop(query.offset).take(query.limit)

    override fun search(query: String, max: Int): List<SmsRecord> =
        records.filter { it.body.contains(query, ignoreCase = true) }.sortedByDescending { it.dateMillis }.take(max)

    /** 假的分段：每 [PART_SIZE] 个字符一段（真实规则由 SmsManager.divideMessage 决定，这里只验证传递）。 */
    override fun divide(text: String): List<String> = text.chunked(PART_SIZE).ifEmpty { listOf("") }

    override fun submit(outboxId: String, to: String, parts: List<String>) {
        submitFailure?.let { throw it }
        submitted += Submission(outboxId, to, parts)
    }

    override fun openComposer(to: String, text: String?): Boolean {
        composed += to to text
        return composerAvailable
    }

    companion object {
        const val PART_SIZE = 70
    }
}

/** 装配一套可控时钟的工具层。 */
class Rig(
    val gateway: FakeGateway = FakeGateway(),
    val settings: InMemorySmsSettings = InMemorySmsSettings(),
    var now: Long = 1_760_000_000_000L, // 2025-10-09T08:53:20Z
) {
    val outbox = Outbox(InMemoryOutboxStore()) { now }
    val tools = SmsTools(gateway, outbox, settings, clock = { now }, zone = { java.time.ZoneOffset.ofHours(8) })

    suspend fun call(name: String, json: String = "{}") =
        tools.find(name)!!.handler(kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject)
}

fun org.agentos.sample.sms.tools.ToolOutput.obj() = structured ?: error("no structured result: $text")
