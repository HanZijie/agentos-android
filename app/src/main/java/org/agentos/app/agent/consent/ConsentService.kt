package org.agentos.app.agent.consent

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import org.agentos.app.agent.AgentProcess

/** IConsentService 的服务端（D5.2）：`:agent`，不导出；主进程在前台时 bind，服务端再校验调用方 UID。 */
class ConsentService : Service() {
    override fun onBind(intent: Intent?): IBinder = AgentProcess.get(this).consentBridge.binder()
}

/**
 * 确认通知上的“允许一次 / 拒绝”（不导出，只有本 App 的 PendingIntent 能触发；运行在 `:agent`，协调器在那里）。
 * 通知上只给这两个选择；“始终允许”“本会话内不再询问”要在对话框里看清楚再选。
 */
class ConsentActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_REQUEST) ?: return
        // 第三方 App 授权提示通知上的“拒绝”（没有“允许”：授权要在对话框里看清包名和签名）
        if (intent.getBooleanExtra(EXTRA_AUTH_DENY, false)) {
            AgentProcess.get(context).consentBridge.denyAuthorizationFromNotification(id)
            return
        }
        val choice = ConsentWire.parseChoice(intent.getStringExtra(EXTRA_CHOICE)) ?: return
        // 通知只能给这两个；别的值（被篡改的 PendingIntent 不可能，但防御）一律不处理
        if (choice != org.agentos.runtime.consent.ConsentChoice.ALLOW_ONCE && choice != org.agentos.runtime.consent.ConsentChoice.DENY) return
        AgentProcess.get(context).consentBridge.respondFromNotification(id, choice)
    }

    companion object {
        const val EXTRA_REQUEST = "requestId"
        const val EXTRA_CHOICE = "choice"
        const val EXTRA_AUTH_DENY = "authDeny"
    }
}
