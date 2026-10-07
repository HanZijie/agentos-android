package org.agentos.app.ext

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import org.agentos.app.agent.AgentControlService
import org.agentos.internal.IAgentControl
import org.agentos.internal.IExtensionCallback
import org.agentos.internal.IExtensionHost
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * **debug 包专用的测试入口**（app/src/debug 源集，release 包里没有）：tests/device/mcp-plugin 的设备用例用它操作 Extension Host。
 * 跑在主进程，经 IExtensionHost 访问 `:ext`（与插件管理页相同的路径；adb shell 的 UID 绑不了它）。
 *
 * ```
 * adb shell am broadcast -n org.agentos.app/.ext.ExtensionDebugReceiver --es op <op> [--es id <插件 ID 或包名>] [--es name <工具名>]
 *     [--es mode ask|always|""] [--es args <JSON>] [--el timeoutMs N] [--el cancelAfterMs N] [--ez force true] [--ez absent true]
 * ```
 *
 * op：`list`、`rescan`、`enable`、`disable`、`confirm`、`approval`（插件级）、`tools`、`tool_enable`、`tool_disable`、
 * `tool_approval`、`catalog`、`refresh`、`diag`、`call`（callTool 并等 onToolResult；cancelAfterMs > 0 时到点 cancelTool）、
 * `wait_catalog`（等目录里出现 / 不再有 name，最多 timeoutMs）、`skill`（readSkill：name = Skill 标识，path 可选）、
 * `approval_by_source`（--es plugin / server / tool + mode）、`policy`、`policy_reset`、`agent`（:agent 诊断里的 extensions 计数）。结果在广播的 result data 里（JSON，含 ok）。
 * Manifest 要求发送方持有 DUMP（adb shell 有，普通 App 没有）；不可调试的包一律返回 not_debuggable。
 */
class ExtensionDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val op = intent.getStringExtra("op")
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            finish(this, null, op, JSONObject().put("ok", false).put("error", "not_debuggable"))
            return
        }
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "ext-debug") {
            val result = try {
                if (op == "agent") agentStats(app) else withHost(app) { h -> run(h, op, intent) }
            } catch (e: Exception) {
                Log.w(TAG, "debug op $op failed", e)
                JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
            }
            finish(null, pending, op, result)
        }
    }

    private fun run(h: IExtensionHost, op: String?, intent: Intent): JSONObject {
        fun id() = resolveId(h, intent.getStringExtra("id"))
        val name = intent.getStringExtra("name")
        val mode = intent.getStringExtra("mode") ?: ""
        val timeoutMs = intent.getLongExtra("timeoutMs", 10_000L)
        return when (op) {
            "list" -> ok().put("plugins", JSONArray(h.listPlugins()))
            "rescan" -> ok().put("plugins", JSONArray(h.rescan()))
            "enable" -> ok().put("plugin", JSONObject(h.setPluginEnabled(id(), true)))
            "disable" -> ok().put("plugin", JSONObject(h.setPluginEnabled(id(), false)))
            "confirm" -> ok().put("plugin", JSONObject(h.confirmSignature(id())))
            "approval" -> ok().put("plugin", JSONObject(h.setPluginApproval(id(), mode)))
            "tools" -> ok().put("tools", JSONArray(h.listTools(id())))
            "tool_enable" -> ok().put("tool", JSONObject(h.setToolEnabled(name, true)))
            "tool_disable" -> ok().put("tool", JSONObject(h.setToolEnabled(name, false)))
            "tool_approval" -> ok().put("tool", JSONObject(h.setToolApproval(name, mode)))
            "catalog" -> ok().put("catalog", JSONObject(h.catalog))
            "refresh" -> ok().put("refresh", JSONObject(h.refreshTools(timeoutMs, intent.getBooleanExtra("force", false))))
            "diag" -> ok().put("diag", JSONObject(h.diagnostics)).put("version", h.version)
            "call" -> call(h, name, intent.getStringExtra("args") ?: "{}", timeoutMs, intent.getLongExtra("cancelAfterMs", 0L),
                intent.getLongExtra("disableAfterMs", 0L), intent.getStringExtra("id"))
            "wait_catalog" -> waitCatalog(h, name, intent.getBooleanExtra("absent", false), timeoutMs)
            "skill" -> ok().put("skill", JSONObject(h.readSkill(name, intent.getStringExtra("path") ?: "")))
            "policy" -> ok().put("policy", JSONObject(h.policyStatus))
            "policy_reset" -> ok().put("policy", JSONObject(h.resetPolicy()))
            "approval_by_source" -> ok().put("tool", JSONObject(h.setToolApprovalBySource(
                intent.getStringExtra("plugin"), intent.getStringExtra("server"), intent.getStringExtra("tool"), mode,
            )))
            else -> JSONObject().put("ok", false).put("error", "unknown op: $op")
        }
    }

    /**
     * callTool → 等 onToolResult（最多 timeoutMs + 20 秒）。cancelAfterMs / disableAfterMs > 0 时到点 cancelTool / 停用插件 id：
     * 同一进程的广播是串行投递的，调用进行中再发一条 disable 广播要等这条结束才送到，所以在这里做。
     */
    private fun call(h: IExtensionHost, name: String?, args: String, timeoutMs: Long, cancelAfterMs: Long, disableAfterMs: Long, id: String?): JSONObject {
        val callId = "debug-" + UUID.randomUUID()
        val results = LinkedBlockingQueue<String>()
        val cb = object : IExtensionCallback.Stub() {
            override fun onCatalogChanged(version: Long) {}
            override fun onToolResult(id: String?, outcomeJson: String?) {
                if (id == callId) results.add(outcomeJson ?: "null")
            }
            override fun onConnectionState(stateJson: String?) {}
        }
        val req = JSONObject().put("name", name).put("arguments", JSONObject(args)).put("timeoutMs", timeoutMs)
            .put("sessionId", "debug").put("taskId", "debug").put("toolCallId", callId).toString()
        val t0 = SystemClock.elapsedRealtime()
        val accepted = h.callTool(callId, req, cb)
        if (!accepted) return ok().put("accepted", false)
        if (cancelAfterMs > 0) {
            thread(name = "ext-debug-cancel") {
                Thread.sleep(cancelAfterMs)
                runCatching { h.cancelTool(callId) }
            }
        }
        if (disableAfterMs > 0) {
            thread(name = "ext-debug-disable") {
                Thread.sleep(disableAfterMs)
                runCatching { h.setPluginEnabled(resolveId(h, id), false) }.onFailure { Log.w(TAG, "disableAfterMs failed", it) }
            }
        }
        val outcome = results.poll(timeoutMs + 20_000L, TimeUnit.MILLISECONDS)
        val elapsed = SystemClock.elapsedRealtime() - t0
        // 恰好一次：再等一小会儿，看有没有第二次回调
        val extra = results.poll(300, TimeUnit.MILLISECONDS)
        return ok().put("accepted", true).put("elapsedMs", elapsed)
            .put("outcome", outcome?.let { JSONObject(it) } ?: JSONObject.NULL)
            .put("extraResults", if (extra == null) 0 else 1 + results.size)
    }

    /** 订阅目录变化，等 [name] 出现（absent = 不再有），最多 timeoutMs。 */
    private fun waitCatalog(h: IExtensionHost, name: String?, absent: Boolean, timeoutMs: Long): JSONObject {
        val changes = LinkedBlockingQueue<Long>()
        val cb = object : IExtensionCallback.Stub() {
            override fun onCatalogChanged(version: Long) { changes.add(version) }
            override fun onToolResult(id: String?, outcomeJson: String?) {}
            override fun onConnectionState(stateJson: String?) {}
        }
        val t0 = SystemClock.elapsedRealtime()
        h.subscribe(cb)
        try {
            while (true) {
                val cat = JSONObject(h.catalog)
                val tools = cat.getJSONArray("tools")
                val present = (0 until tools.length()).any { tools.getJSONObject(it).optString("name") == name }
                if (present != absent) {
                    return ok().put("met", true).put("elapsedMs", SystemClock.elapsedRealtime() - t0).put("version", cat.optLong("version"))
                        .put("tools", JSONArray((0 until tools.length()).map { tools.getJSONObject(it).optString("name") }))
                }
                val left = timeoutMs - (SystemClock.elapsedRealtime() - t0)
                if (left <= 0) return ok().put("met", false).put("elapsedMs", SystemClock.elapsedRealtime() - t0)
                changes.poll(left, TimeUnit.MILLISECONDS)
            }
        } finally {
            runCatching { h.unsubscribe(cb) }
        }
    }

    /** :agent 的诊断里 Extension Host 代理的计数（IAgentControl.getDiagnostics）。 */
    private fun agentStats(context: Context): JSONObject {
        val latch = CountDownLatch(1)
        var control: IAgentControl? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                control = IAgentControl.Stub.asInterface(service)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        check(context.bindService(Intent(context, AgentControlService::class.java), conn, Context.BIND_AUTO_CREATE)) { "bind AgentControlService failed" }
        try {
            check(latch.await(BIND_TIMEOUT_S, TimeUnit.SECONDS)) { ":agent did not connect" }
            val d = JSONObject(control!!.diagnostics)
            return ok().put("extensions", d.optJSONObject("extensions") ?: JSONObject.NULL)
                .put("agentPid", d.optJSONObject("runtime")?.optInt("pid") ?: -1)
        } finally {
            runCatching { context.unbindService(conn) }
        }
    }

    /** 插件 ID（含 "/"）原样；否则当包名，取这个包的第一个插件。 */
    private fun resolveId(h: IExtensionHost, idOrPackage: String?): String? {
        if (idOrPackage == null || idOrPackage.contains('/')) return idOrPackage
        val plugins = JSONArray(h.listPlugins())
        return (0 until plugins.length()).map { plugins.getJSONObject(it) }.firstOrNull { it.optString("packageName") == idOrPackage }?.optString("id")
            ?: idOrPackage
    }

    private fun ok() = JSONObject().put("ok", true)

    /** 绑定 IExtensionHost（BIND_AUTO_CREATE），做完解绑。 */
    private fun <T> withHost(context: Context, block: (IExtensionHost) -> T): T {
        val latch = CountDownLatch(1)
        var host: IExtensionHost? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                host = IExtensionHost.Stub.asInterface(service)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        check(context.bindService(Intent(context, ExtensionHostService::class.java), conn, Context.BIND_AUTO_CREATE)) { "bindService failed" }
        try {
            check(latch.await(BIND_TIMEOUT_S, TimeUnit.SECONDS)) { "extension host did not connect in ${BIND_TIMEOUT_S}s" }
            return block(host!!)
        } finally {
            runCatching { context.unbindService(conn) }
        }
    }

    private fun finish(sync: BroadcastReceiver?, pending: PendingResult?, op: String?, result: JSONObject) {
        val code = if (result.optBoolean("ok")) RESULT_OK_CODE else RESULT_ERROR_CODE
        val data = result.put("op", op ?: JSONObject.NULL).toString()
        Log.i(TAG, "op=$op ok=${result.optBoolean("ok")}")
        if (pending != null) {
            pending.setResult(code, data, null)
            pending.finish()
        } else {
            sync?.resultCode = code
            sync?.resultData = data
        }
    }

    companion object {
        private const val TAG = "ExtensionDebug"
        const val RESULT_OK_CODE = 1
        const val RESULT_ERROR_CODE = 2
        private const val BIND_TIMEOUT_S = 15L
    }
}
