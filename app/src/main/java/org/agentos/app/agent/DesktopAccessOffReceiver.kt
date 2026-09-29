package org.agentos.app.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 电脑端接入通知上的“关闭”按钮（architecture F11 第 4 点）：关掉开关，与设置页的关闭相同（断开全部电脑端连接、作废配对），
 * 随后 [RuntimeLifecycle] 在没有任务时走宽限期退出前台。
 *
 * 不导出、运行在 `:agent`：只有本 App（通知的 PendingIntent）能发。DesktopGateway 会读写文件、关 socket，所以放到 IO 线程，
 * 用 goAsync 撑住广播。
 */
class DesktopAccessOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val process = AgentProcess.get(context)
        process.scope.launch(Dispatchers.IO + CoroutineName("desktop-access-off")) {
            try {
                process.desktop.setEnabled(false)
                Log.i(TAG, "desktop access turned off from the notification")
            } catch (e: Exception) {
                Log.w(TAG, "turning desktop access off failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DesktopAccessOff"
    }
}
