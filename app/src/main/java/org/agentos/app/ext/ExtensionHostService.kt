package org.agentos.app.ext

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteCallbackList
import android.util.Log
import org.agentos.internal.IExtensionCallback
import org.agentos.internal.IExtensionHost
import org.agentos.runtime.broker.ApprovalPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Extension Host（docs/extensions.md 第 2、9 节）：`:ext` 进程，不导出，只接受本 App 的 UID。
 *
 * **C7a 是桩实现**：接口、调用方校验、订阅回调和错误格式按正式约定，但还没有插件（listPlugins 为空、目录为空、
 * callTool 一律不受理）。D 的插件管理页可以先对着它做。C7b 换成真正的实现（AppPluginScanner、McpClientManager、ToolCatalog）。
 */
class ExtensionHostService : Service() {

    private val callbacks = RemoteCallbackList<IExtensionCallback>()

    private val binder = object : IExtensionHost.Stub() {
        override fun getVersion(): Int = VERSION

        override fun listPlugins(): String = guarded { JSONArray().toString() }

        override fun setPluginEnabled(pluginId: String?, enabled: Boolean): String = guarded {
            throw ExtError.notFound(pluginId)
        }

        override fun confirmSignature(pluginId: String?): String = guarded { throw ExtError.notFound(pluginId) }

        override fun setPluginApproval(pluginId: String?, mode: String?): String = guarded {
            ExtError.checkMode(mode)
            throw ExtError.notFound(pluginId)
        }

        override fun listTools(pluginId: String?): String = guarded { throw ExtError.notFound(pluginId) }

        override fun setToolEnabled(toolName: String?, enabled: Boolean): String = guarded {
            throw ExtError.notFound(toolName)
        }

        override fun setToolApproval(toolName: String?, mode: String?): String = guarded {
            ExtError.checkMode(mode)
            throw ExtError.notFound(toolName)
        }

        override fun rescan(): String = guarded { JSONArray().toString() }

        override fun subscribe(callback: IExtensionCallback?) = guarded {
            if (callback != null && callbacks.register(callback)) {
                runCatching { callback.onCatalogChanged(CATALOG_VERSION) }
            }
        }

        override fun unsubscribe(callback: IExtensionCallback?) = guarded {
            if (callback != null) callbacks.unregister(callback)
        }

        override fun getCatalog(): String = guarded {
            JSONObject().put("version", CATALOG_VERSION).put("tools", JSONArray())
                .put("policy", JSONObject(ApprovalPolicy.DEFAULT.toJson())).toString()
        }

        override fun callTool(callId: String?, requestJson: String?, callback: IExtensionCallback?): Boolean = guarded { false }

        override fun cancelTool(callId: String?) = guarded { }

        override fun getDiagnostics(): String = guarded {
            JSONObject().put("implementation", "stub").put("version", VERSION).put("plugins", 0)
                .put("subscribers", callbacks.registeredCallbackCount).toString()
        }

        /** 调用方校验 + 异常归一：Binder 只能传回少数几类异常，其他类型换成 agentos.ext.internal。 */
        private inline fun <T> guarded(block: () -> T): T {
            val uid = Binder.getCallingUid()
            if (uid != Process.myUid()) throw SecurityException("IExtensionHost only accepts the AgentOS app itself (uid $uid)")
            return try {
                block()
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "IExtensionHost call failed", e)
                throw IllegalStateException("agentos.ext.internal: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        callbacks.kill()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ExtensionHost"
        const val VERSION = 1
        private const val CATALOG_VERSION = 0L
    }
}

/** IExtensionHost 的错误（message 形如 agentos.ext.<code>: <说明>）。 */
internal object ExtError {
    const val PREFIX = "agentos.ext."
    /** ApprovalMode 的 wire 值（core/runtime 的 ApprovalPolicy），"" 表示清除这一层。 */
    val APPROVAL_MODES = setOf("ask", "always", "")

    fun checkMode(mode: String?) {
        if (mode == null || mode !in APPROVAL_MODES) throw badMode(mode)
    }

    fun notFound(what: String?) = IllegalArgumentException("${PREFIX}not_found: no such plugin or tool: $what")
    fun badMode(mode: String?) = IllegalArgumentException("${PREFIX}bad_mode: approval mode must be ask, always or \"\" (clear), got $mode")
    fun notReady(detail: String) = IllegalStateException("${PREFIX}not_ready: $detail")
    fun notNeeded(detail: String) = IllegalStateException("${PREFIX}not_needed: $detail")
    fun highRisk(tool: String) = IllegalArgumentException("${PREFIX}high_risk: $tool is a high-risk tool and cannot be set to always")
    fun unavailable(detail: String) = IllegalStateException("${PREFIX}unavailable: $detail")
}
