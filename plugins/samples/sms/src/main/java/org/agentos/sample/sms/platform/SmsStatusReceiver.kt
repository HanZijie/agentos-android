package org.agentos.sample.sms.platform

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import org.agentos.sample.sms.SmsGraph
import org.agentos.sample.sms.data.SendResults
import org.agentos.sample.sms.data.StatusCallback

/**
 * `SmsManager` 的 sent / delivered 回调（显式广播，不导出）。每一段每种回调各一个 PendingIntent（身份编在 data 里，见 [StatusCallback]）。
 * 收到后推动 outbox 状态机；状态机本身是幂等的，重复回调不会重复计数。
 */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callback = StatusCallback.decode(intent.dataString) ?: return
        val outbox = SmsGraph.get(context).outbox
        when (callback.kind) {
            StatusCallback.Kind.SENT -> {
                if (resultCode == Activity.RESULT_OK) {
                    outbox.onSent(callback.outboxId, callback.part)
                } else {
                    outbox.onFailed(callback.outboxId, SendResults.errorFor(resultCode))
                }
            }
            StatusCallback.Kind.DELIVERED -> {
                val status = deliveryStatus(intent)
                when (SendResults.classifyDelivery(status)) {
                    SendResults.Delivery.DELIVERED -> outbox.onDelivered(callback.outboxId, callback.part)
                    SendResults.Delivery.FAILED -> outbox.onFailed(callback.outboxId, "delivery_failed")
                    SendResults.Delivery.PENDING -> Unit
                }
            }
        }
        Log.i(TAG, "${callback.kind.wire} id=${callback.outboxId} part=${callback.part} resultCode=$resultCode")
    }

    /** 状态报告 PDU 里的 TP-Status；拿不到就是 null。 */
    @Suppress("DEPRECATION")
    private fun deliveryStatus(intent: Intent): Int? {
        val pdu = intent.getByteArrayExtra("pdu") ?: return null
        val format = intent.getStringExtra("format")
        return runCatching { SmsMessage.createFromPdu(pdu, format)?.status }.getOrNull()
    }

    companion object {
        const val ACTION = "org.agentos.sample.sms.action.STATUS"
        private const val TAG = "SmsStatus"
    }
}
