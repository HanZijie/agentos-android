package org.agentos.sample.sms

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.agentos.sample.sms.agentos.GatewayProvider
import org.agentos.sample.sms.agentos.PrefsInstructionStore
import org.agentos.sample.sms.agentos.PrefsProcessedStore
import org.agentos.sample.sms.agentos.PrefsRunMarker
import org.agentos.sample.sms.agentos.ProcessedLedger
import org.agentos.sample.sms.agentos.PromptSettings
import org.agentos.sample.sms.agentos.SmsScheduleUseCase
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

    private val appContext: Context = context
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 已经让 AgentOS 处理过的短信（会话页据此标记、按钮据此数“未处理”）。 */
    val processed = ProcessedLedger(PrefsProcessedStore(context))

    /**
     * “让 AgentOS 安排”的用例，进程内单例：会话页的按钮和 debug 的 ask_agent 用的是同一个，所以旋转屏幕、切页签都不影响进行中的一轮。
     * 进程被回收后上一轮丢了的处理见 [SmsScheduleUseCase.restoreInterrupted]（MainActivity 重建时调）。
     */
    val agentSchedule: SmsScheduleUseCase by lazy {
        SmsScheduleUseCase(
            scope = appScope,
            gatewayFactory = { GatewayProvider.create(appContext) },
            ledger = processed,
            prompt = PromptSettings(PrefsInstructionStore(appContext)) { appContext.getString(R.string.sms_prompt_default) },
            marker = PrefsRunMarker(appContext.getSharedPreferences("sms_agentos", Context.MODE_PRIVATE)),
            promptFor = { source, now, locale -> GatewayProvider.promptFor(appContext, source, now, locale) },
        )
    }

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
