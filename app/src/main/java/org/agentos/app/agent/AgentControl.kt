package org.agentos.app.agent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import org.agentos.internal.IAgentControl
import org.json.JSONObject
import java.io.File

/** IAgentControl 的服务端（architecture 5.4）：`:agent` 进程，不导出。主进程只在界面打开时绑定。 */
class AgentControlService : Service() {
    private lateinit var control: AgentControl

    override fun onCreate() {
        super.onCreate()
        control = AgentControl(this, AgentProcess.get(this))
    }

    override fun onBind(intent: Intent?): IBinder = control
}

/**
 * IAgentControl v1。每个方法都校验调用方 UID 等于本 App（服务不导出之外的第二道检查：root 和 system
 * 也能绑定不导出的服务）。
 */
class AgentControl(context: Context, private val runtime: AgentProcess) : IAgentControl.Stub() {
    private val de = context.createDeviceProtectedStorageContext()

    override fun getVersion(): Int {
        enforceSelf()
        return VERSION
    }

    override fun getRuntimeStatus(): String {
        enforceSelf()
        return runtime.runtimeStatus().toString()
    }

    override fun getDiagnostics(): String {
        enforceSelf()
        return runtime.diagnostics().put("supervisor", readSupervisorStatus()).toString()
    }

    override fun getSupervisorStatus(): String {
        enforceSelf()
        return readSupervisorStatus().toString()
    }

    private fun readSupervisorStatus(): JSONObject {
        val f = File(de.filesDir, SUPERVISOR_STATUS_FILE)
        val text = runCatching { f.readText() }.getOrNull()?.trim() ?: return JSONObject()
        if (text.startsWith("{")) return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", "unparseable") }
        val out = JSONObject()
        text.lineSequence().take(64).forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) out.put(line.substring(0, i).trim(), line.substring(i + 1).trim())
        }
        return out
    }

    private fun enforceSelf() {
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid()) throw SecurityException("IAgentControl only accepts the AgentOS app itself (uid $uid)")
    }

    companion object {
        const val VERSION = 1

        /** 监督状态文件（DE 存储 files/ 下），由 W7 的 SupervisorStatusReceiver 写入（监督契约 S2 e）。 */
        const val SUPERVISOR_STATUS_FILE = "supervisor/status"
    }
}
