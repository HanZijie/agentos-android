package org.agentos.sample.sms

import android.app.Application
import android.content.Context
import org.agentos.sample.sms.data.Drafts
import org.agentos.sample.sms.data.Outbox
import org.agentos.sample.sms.data.PrefsDraftStore
import org.agentos.sample.sms.data.PrefsSmsSettings
import org.agentos.sample.sms.data.SmsGateway
import org.agentos.sample.sms.data.SmsSettings
import org.agentos.sample.sms.data.SqliteOutboxStore
import org.agentos.sample.sms.platform.AndroidSmsGateway
import org.agentos.sample.sms.tools.SmsTools

/**
 * 进程内的单例装配：界面、MCP 服务、sent / delivered 回调接收器共用同一个 [outbox] 和 [settings]。
 */
class SmsGraph private constructor(context: Context) {
    val gateway: SmsGateway = AndroidSmsGateway(context)
    val outbox: Outbox = Outbox(SqliteOutboxStore(context))
    val drafts: Drafts = Drafts(PrefsDraftStore(context))
    val settings: SmsSettings = PrefsSmsSettings(context)
    val tools: SmsTools = SmsTools(gateway, outbox, drafts, settings)

    companion object {
        @Volatile
        private var instance: SmsGraph? = null

        fun get(context: Context): SmsGraph =
            instance ?: synchronized(this) {
                instance ?: SmsGraph(context.applicationContext).also { instance = it }
            }
    }
}

class SmsApp : Application()
