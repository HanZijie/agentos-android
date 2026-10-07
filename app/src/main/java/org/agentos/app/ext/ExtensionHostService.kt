package org.agentos.app.ext

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteCallbackList
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.agentos.extensions.host.ServerKey
import org.agentos.extensions.host.ServerState
import org.agentos.internal.IExtensionCallback
import org.agentos.internal.IExtensionHost
import java.util.concurrent.ConcurrentHashMap

/**
 * Extension Host（docs/extensions.md 第 2、9 节）：`:ext` 进程，不导出，只接受本 App 的 UID。逻辑都在 [ExtensionHost]，
 * 这里是 IExtensionHost 的 Binder 外壳：调用方校验、错误归一、订阅回调、包变化时重新扫描。
 *
 * 进程生命周期：运行时（:agent）用 BIND_AUTO_CREATE 绑定，`:ext` 随它存活；没有人绑定时系统可以回收 `:ext`，
 * 下次绑定时重建（重建后重新扫描、重新推送目录）。
 */
class ExtensionHostService : Service() {

    private lateinit var host: ExtensionHost
    private val callbacks = object : RemoteCallbackList<IExtensionCallback>() {
        override fun onCallbackDied(callback: IExtensionCallback?) {
            callback?.asBinder()?.let { host.cancelCallsOf(it) }
        }
    }
    private val broadcastLock = Any()
    private var packageReceiver: BroadcastReceiver? = null
    @Volatile private var pendingRescan: Job? = null

    /** 调用方（callTool 的 callback）的死亡监听：它死了，它的调用全部取消。 */
    private val owners = ConcurrentHashMap<IBinder, IBinder.DeathRecipient>()

    override fun onCreate() {
        super.onCreate()
        host = ExtensionHost(applicationContext)
        host.start()
        host.scope.launch {
            host.snapshot.collect { s -> broadcast { it.onCatalogChanged(s.version) } }
        }
        host.scope.launch {
            var last: Map<ServerKey, ServerState> = emptyMap()
            host.tools.serverStates.collect { states ->
                for ((k, s) in states) {
                    if (last[k] != s) {
                        val json = ExtensionHost.stateJson(k, s).toString()
                        broadcast { it.onConnectionState(json) }
                    }
                }
                last = states
            }
        }
        registerPackageReceiver()
        Log.i(TAG, "extension host created (pid ${Process.myPid()})")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        packageReceiver?.let { runCatching { unregisterReceiver(it) } }
        callbacks.kill()
        host.close()
        Log.i(TAG, "extension host destroyed")
        super.onDestroy()
    }

    /** 安装、升级、卸载、启用状态变化：合并 [RESCAN_DEBOUNCE_MS] 内的多条后重新扫描。 */
    private fun registerPackageReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.data?.schemeSpecificPart == packageName) return
                pendingRescan?.cancel()
                pendingRescan = host.scope.launch(Dispatchers.IO) {
                    delay(RESCAN_DEBOUNCE_MS)
                    Log.i(TAG, "package change (${intent.action?.substringAfterLast('.')}): rescanning")
                    host.rescanQuietly()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        // 系统广播：NOT_EXPORTED 不影响接收，只是不让别的 App 伪造
        registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        packageReceiver = r
    }

    private inline fun broadcast(block: (IExtensionCallback) -> Unit) = synchronized(broadcastLock) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    block(callbacks.getBroadcastItem(i))
                } catch (e: Exception) {
                    // 回调方死了：RemoteCallbackList 会自己移除
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun watchOwner(callback: IExtensionCallback) {
        val b = callback.asBinder()
        if (owners.containsKey(b)) return
        val recipient = IBinder.DeathRecipient {
            owners.remove(b)
            host.cancelCallsOf(b)
        }
        try {
            b.linkToDeath(recipient, 0)
            owners[b] = recipient
        } catch (e: Exception) {
            host.cancelCallsOf(b)
        }
    }

    private val binder = object : IExtensionHost.Stub() {
        override fun getVersion(): Int = VERSION

        override fun listPlugins(): String = managed { host.pluginsJson() }

        override fun setPluginEnabled(pluginId: String?, enabled: Boolean): String = managed {
            host.setPluginEnabled(pluginId, enabled)
            host.pluginJson(pluginId)
        }

        override fun confirmSignature(pluginId: String?): String = managed {
            host.registry.confirmSignature(pluginId)
            host.pluginJson(pluginId)
        }

        override fun setPluginApproval(pluginId: String?, mode: String?): String = managed {
            host.registry.setPluginApproval(pluginId, mode)
            host.pluginJson(pluginId)
        }

        override fun listTools(pluginId: String?): String = managed { host.listTools(pluginId) }

        override fun setToolEnabled(toolName: String?, enabled: Boolean): String = managed { host.setToolEnabled(toolName, enabled) }

        override fun setToolApproval(toolName: String?, mode: String?): String = managed { host.setToolApproval(toolName, mode) }

        override fun rescan(): String = guarded {
            host.rescan()
            host.pluginsJson()
        }

        override fun subscribe(callback: IExtensionCallback?) = guarded {
            if (callback != null && callbacks.register(callback)) {
                runCatching { callback.onCatalogChanged(host.snapshot.value.version) }
            }
        }

        override fun unsubscribe(callback: IExtensionCallback?) = guarded {
            if (callback != null) callbacks.unregister(callback)
        }

        override fun getCatalog(): String = guarded { host.catalogJson() }

        override fun callTool(callId: String?, requestJson: String?, callback: IExtensionCallback?): Boolean = guarded {
            callback?.let { watchOwner(it) }
            host.callTool(callId, requestJson, callback?.asBinder()) { outcome -> callback?.onToolResult(callId, outcome) }
        }

        override fun cancelTool(callId: String?) = guarded { host.cancelTool(callId) }

        override fun getDiagnostics(): String = guarded { host.diagnosticsJson(callbacks.registeredCallbackCount) }

        override fun refreshTools(timeoutMs: Long, force: Boolean): String = guarded { host.refreshTools(timeoutMs, force) }

        override fun readSkill(skillId: String?, path: String?): String = guarded { host.readSkill(skillId, path) }

        override fun setToolApprovalBySource(plugin: String?, server: String?, tool: String?, mode: String?): String = managed {
            host.setToolApprovalBySource(plugin, server, tool, mode)
        }

        /** 插件管理：先等启动后的第一次扫描。 */
        private inline fun <T> managed(block: () -> T): T = guarded {
            host.awaitFirstScan()
            block()
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

    companion object {
        private const val TAG = "ExtensionHost"
        const val VERSION = 2
        const val RESCAN_DEBOUNCE_MS = 500L
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
    fun badRequest(detail: String) = IllegalArgumentException("${PREFIX}bad_request: $detail")
    fun notReady(detail: String) = IllegalStateException("${PREFIX}not_ready: $detail")
    fun notNeeded(detail: String) = IllegalStateException("${PREFIX}not_needed: $detail")
    fun highRisk(tool: String) = IllegalArgumentException("${PREFIX}high_risk: $tool is a high-risk tool and cannot be set to always")
    fun unavailable(detail: String) = IllegalStateException("${PREFIX}unavailable: $detail")
    fun badPath(detail: String) = IllegalArgumentException("${PREFIX}bad_path: $detail")
}
