package org.agentos.app.agent

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import org.agentos.internal.IAgentControl
import org.json.JSONObject

/** IAgentControl 的服务端（architecture 5.4）：`:agent` 进程，不导出。主进程只在界面打开时绑定。 */
class AgentControlService : Service() {
    private lateinit var control: AgentControl

    override fun onCreate() {
        super.onCreate()
        control = AgentControl(AgentProcess.get(this))
    }

    override fun onBind(intent: Intent?): IBinder = control
}

/**
 * IAgentControl v3（字段说明见 IAgentControl.aidl；v3 的电脑端接入由 A lane 追加）。每个方法都校验调用方 UID 等于本 App（服务不导出之外的
 * 第二道检查：root 和 system 也能绑定不导出的服务）。
 *
 * BYOK 方法只让 `agentos.byok.*` 的 IllegalArgumentException / IllegalStateException 回到调用方；其他异常换成
 * 只带类型名的 `agentos.byok.internal`，保证 Binder 返回的异常消息里不会出现 key。
 */
class AgentControl(private val process: AgentProcess) : IAgentControl.Stub() {

    override fun getVersion(): Int {
        enforceSelf()
        return VERSION
    }

    override fun getRuntimeStatus(): String {
        enforceSelf()
        return process.runtimeStatus().toString()
    }

    override fun getDiagnostics(): String {
        enforceSelf()
        return process.diagnostics().put("supervisor", JSONObject(supervisorJson())).toString()
    }

    override fun getSupervisorStatus(): String {
        enforceSelf()
        return supervisorJson()
    }

    /** 格式只有 D 的 SupervisorStatus 一处实现（监督契约 v0.2）。 */
    private fun supervisorJson(): String = process.supervisorStatus()?.toJson() ?: "{}"

    // ------------------------------------------------------------------ v2：BYOK

    override fun getModelPresets(providerId: String?): String {
        enforceSelf()
        return byok { process.models.presets(providerId).toString() }
    }

    override fun getModelSource(): String {
        enforceSelf()
        return byok { process.models.get().toString() }
    }

    override fun setModelSource(sourceJson: String?, apiKey: String?): String {
        enforceSelf()
        if (sourceJson == null) throw ByokException(ModelSources.INVALID_SOURCE, "sourceJson is null")
        return byok { process.models.set(sourceJson, apiKey).toString() }
    }

    override fun clearModelSource() {
        enforceSelf()
        byok { process.models.clear() }
    }

    // ------------------------------------------------------------------ v3：电脑端接入（A lane，DesktopGateway.kt）

    override fun getDesktopAccess(): String {
        enforceSelf()
        return desktop("getDesktopAccess") { process.desktop.status().toString() }
    }

    override fun setDesktopAccessEnabled(enabled: Boolean): String {
        enforceSelf()
        return desktop("setDesktopAccessEnabled($enabled)") { process.desktop.setEnabled(enabled).let { process.desktop.status().toString() } }
    }

    override fun newDesktopPairingCode(): String {
        enforceSelf()
        return desktop("newDesktopPairingCode") { process.desktop.newPairingCodeJson().toString() }
    }

    override fun revokeDesktopPairing(pairingId: String?): String {
        enforceSelf()
        return desktop("revokeDesktopPairing") {
            if (pairingId.isNullOrEmpty()) process.desktop.revokeAll() else process.desktop.revoke(pairingId)
            process.desktop.status().toString()
        }
    }

    /**
     * v3 的异常：`agentos.desktop.*`（例如开关关闭时生成配对码的 `agentos.desktop.disabled`）原样回到调用方；其他异常先在
     * logcat 打出完整堆栈（电脑端接入的异常消息里没有配对码和令牌），IllegalStateException / IllegalArgumentException 照常回去，
     * 别的类型换成只带类型名的 `agentos.desktop.internal: <类型>`。Binder 只能传回少数几种异常（Parcel.getExceptionCode），
     * 其他类型（例如 A9 的 NoSuchElementException）会在 `:agent` 的 Binder 线程上重新抛出，调用方拿不到原因。
     */
    private inline fun <T> desktop(what: String, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        val passThrough = e is IllegalStateException || e is IllegalArgumentException
        if (!(passThrough && e.message?.startsWith("agentos.desktop.") == true)) Log.w(TAG, "$what failed", e)
        throw if (passThrough) e else IllegalStateException("agentos.desktop.internal: ${e.javaClass.simpleName}")
    }

    private inline fun <T> byok(block: () -> T): T = try {
        block()
    } catch (e: ByokException) {
        throw e
    } catch (e: ByokStorageException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "BYOK call failed: ${e.javaClass.simpleName}")
        throw IllegalStateException("agentos.byok.internal: ${e.javaClass.simpleName}")
    }

    private fun enforceSelf() {
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid()) throw SecurityException("IAgentControl only accepts the AgentOS app itself (uid $uid)")
    }

    companion object {
        private const val TAG = "AgentControl"
        const val VERSION = 3
    }
}
