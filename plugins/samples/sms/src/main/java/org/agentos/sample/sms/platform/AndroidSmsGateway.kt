package org.agentos.sample.sms.platform

import android.Manifest
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import androidx.core.net.toUri
import android.telephony.SmsManager
import org.agentos.sample.sms.data.MessageQuery
import org.agentos.sample.sms.data.SmsAccess
import org.agentos.sample.sms.data.SmsBox
import org.agentos.sample.sms.data.SmsGateway
import org.agentos.sample.sms.data.SmsRecord
import org.agentos.sample.sms.data.SmsSendException
import org.agentos.sample.sms.data.StatusCallback
import org.agentos.sample.sms.data.ThreadScan
import org.agentos.sample.sms.data.ThreadSummary
import org.agentos.sample.sms.rules.AddressMatcher

/**
 * 设备上的 [SmsGateway]：读系统短信库（`content://sms`，需要 READ_SMS）、`SmsManager` 发送（需要 SEND_SMS）。
 * 本 App **不是默认短信应用**：不写短信库（非默认应用发出的短信由系统自动写入）、不接收 SMS_DELIVER / RECEIVE_SMS。
 */
class AndroidSmsGateway(context: Context) : SmsGateway {
    private val context = context.applicationContext

    override fun access() = SmsAccess(
        canRead = granted(Manifest.permission.READ_SMS),
        canSend = granted(Manifest.permission.SEND_SMS),
    )

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // ---- 读 ----

    override fun threads(): ThreadScan {
        val order = LinkedHashMap<Long, ThreadAcc>()
        var rows = 0
        var truncated = false
        query(null, null, "date DESC")?.use { c ->
            val col = Columns(c)
            while (c.moveToNext()) {
                if (++rows > SCAN_MAX_ROWS) {
                    truncated = true
                    break
                }
                val threadId = c.getLong(col.threadId)
                val key = if (threadId > 0) threadId else -(c.getString(col.address).orEmpty().hashCode().toLong()) - 1
                val acc = order.getOrPut(key) { ThreadAcc(threadId, c.getString(col.address).orEmpty(), c.getString(col.body).orEmpty(), c.getLong(col.date), SmsBox.fromType(c.getInt(col.type))) }
                acc.count++
                if (c.getInt(col.read) == 0 && c.getInt(col.type) == Telephony.Sms.MESSAGE_TYPE_INBOX) acc.unread++
            }
        }
        val threads = order.values.map { ThreadSummary(it.threadId, it.address, it.snippet, it.date, it.box, it.count, it.unread) }
            .sortedByDescending { it.lastDateMillis }
        return ThreadScan(threads, truncated)
    }

    override fun messages(query: MessageQuery): List<SmsRecord> {
        val selection = StringBuilder()
        val args = ArrayList<String>()
        if (query.sinceMillis != null) {
            selection.append("date >= ?")
            args += query.sinceMillis.toString()
        }
        if (query.untilMillis != null) {
            if (selection.isNotEmpty()) selection.append(" AND ")
            selection.append("date < ?")
            args += query.untilMillis.toString()
        }
        val out = ArrayList<SmsRecord>()
        var skipped = 0
        var rows = 0
        query(selection.takeIf { it.isNotEmpty() }?.toString(), args.toTypedArray().takeIf { it.isNotEmpty() }, "date DESC")?.use { c ->
            val col = Columns(c)
            while (c.moveToNext() && out.size < query.limit) {
                if (++rows > SCAN_MAX_ROWS) break
                if (!AddressMatcher.matches(c.getString(col.address).orEmpty(), query.address)) continue
                if (skipped < query.offset) {
                    skipped++
                    continue
                }
                out += c.toRecord(col)
            }
        }
        return out
    }

    override fun search(query: String, max: Int): List<SmsRecord> {
        val out = ArrayList<SmsRecord>()
        query("body LIKE ? ESCAPE '\\'", arrayOf("%" + escapeLike(query) + "%"), "date DESC")?.use { c ->
            val col = Columns(c)
            while (c.moveToNext() && out.size < max) out += c.toRecord(col)
        }
        return out
    }

    @Suppress("Recycle") // 调用方都用 use { } 关闭
    private fun query(selection: String?, args: Array<String>?, order: String): Cursor? =
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, PROJECTION, selection, args, order)

    private class ThreadAcc(val threadId: Long, val address: String, val snippet: String, val date: Long, val box: SmsBox) {
        var count = 0
        var unread = 0
    }

    private class Columns(c: Cursor) {
        val id = c.getColumnIndexOrThrow(Telephony.Sms._ID)
        val threadId = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
        val address = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        val date = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
        val type = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
        val read = c.getColumnIndexOrThrow(Telephony.Sms.READ)
        val body = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
    }

    private fun Cursor.toRecord(col: Columns) = SmsRecord(
        id = getLong(col.id).toString(),
        threadId = getLong(col.threadId),
        address = getString(col.address).orEmpty(),
        dateMillis = getLong(col.date),
        box = SmsBox.fromType(getInt(col.type)),
        read = getInt(col.read) != 0,
        body = getString(col.body).orEmpty(),
    )

    // ---- 发送 ----

    override fun divide(text: String): List<String> = smsManager().divideMessage(text)

    override fun submit(outboxId: String, to: String, parts: List<String>) {
        val manager = smsManager()
        val sent = ArrayList<PendingIntent>(parts.size)
        val delivered = ArrayList<PendingIntent>(parts.size)
        parts.indices.forEach { i ->
            // 结果码在广播的 resultCode 里，FLAG_IMMUTABLE 够用。
            sent += callback(StatusCallback(outboxId, i, StatusCallback.Kind.SENT), mutable = false)
            // 送达报告的内容（失败还是成功）只在 fill-in Intent 的 "pdu" extra 里；FLAG_IMMUTABLE 会丢弃它，
            // 把永久失败误报成“已送达”。接收器不导出、Intent 是显式的，所以这里用 FLAG_MUTABLE。
            delivered += callback(StatusCallback(outboxId, i, StatusCallback.Kind.DELIVERED), mutable = true)
        }
        try {
            manager.sendMultipartTextMessage(to, null, ArrayList(parts), sent, delivered)
        } catch (e: SecurityException) {
            throw SmsSendException("permission_denied", e)
        } catch (e: IllegalArgumentException) {
            throw SmsSendException("invalid_arguments", e)
        } catch (e: RuntimeException) {
            throw SmsSendException("submit_failed: ${e.javaClass.simpleName}", e)
        }
    }

    private fun callback(callback: StatusCallback, mutable: Boolean): PendingIntent {
        val intent = Intent(SmsStatusReceiver.ACTION, callback.encode().toUri())
            .setClass(context, SmsStatusReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /** 系统默认短信 SIM（不申请 READ_PHONE_STATE）；没有设默认卡时用系统默认实例。 */
    private fun smsManager(): SmsManager {
        val subscription = SmsManager.getDefaultSmsSubscriptionId()
        val system = context.getSystemService(SmsManager::class.java)
        return if (subscription != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            system.createForSubscriptionId(subscription)
        } else {
            system
        }
    }

    // ---- 撰写 ----

    override fun openComposer(to: String, text: String?): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO, ("smsto:" + Uri.encode(to)).toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (text != null) intent.putExtra("sms_body", text)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private companion object {
        val PROJECTION = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.DATE,
            Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.BODY,
        )

        /** 单次扫描最多看多少行（最新的在前）。 */
        const val SCAN_MAX_ROWS = 20_000

        fun escapeLike(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
