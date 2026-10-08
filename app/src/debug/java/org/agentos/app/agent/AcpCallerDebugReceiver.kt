package org.agentos.app.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.agentos.app.agent.acp.CallerConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * **debug 包专用的测试入口**（app/src/debug 源集，release 包里没有这个组件）：设备用例用它代替用户在 AgentOS 里点“允许 / 拒绝 / 撤销”
 * （docs/third-party-acp.md 第 7 节：真机只用 adb）。运行在 `:agent`，要求 DUMP 权限（adb shell 有，普通 App 没有）。
 * 结果在广播的 result data 里（JSON，含 ok）。
 *
 * ```
 * adb shell am broadcast -n org.agentos.app/.agent.AcpCallerDebugReceiver --es op <op> [--es pkg <包名>] [--ez allow true]
 * ```
 *
 * op：
 * - `list`：全部记录（和 IAgentControl.listAcpCallers 同一份 JSON），带 `pkg` 时只要这个包；另有 `health`、`config`、`channels`（开着的第三方通道）；
 * - `allow`：允许。这个包已经有记录（待决 / 已拒绝 / 已允许）就走 setAcpCaller；还没有记录时按已安装的包预授权（取现在的签名）；
 * - `deny`：拒绝（进入冷却）；`revoke`：撤销 = 改成拒绝，这个 App 现有的通道立即关闭；`remove`：删除记录，下次 open 重新询问；
 * - `answer`：回答待决的授权提示，`--es id <requestId> --ez allow true|false`；
 * - `clear`：删除全部记录（设备用例的起点）；
 * - `config`：缩短时间参数，`--el cooldownMs N --el ttlMs N`（只改内存里的值，进程重启后恢复）；不带参数时恢复默认。
 *
 * 不可调试的包一律返回 `not_debuggable`。
 */
class AcpCallerDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val op = intent.getStringExtra("op")
        val result = try {
            val process = AgentProcess.get(context)
            if (!process.debuggable) JSONObject().put("ok", false).put("error", "not_debuggable") else run(process, op, intent)
        } catch (e: IllegalArgumentException) {
            JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalStateException) {
            JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            Log.w(TAG, "debug op $op failed", e)
            JSONObject().put("ok", false).put("error", e.javaClass.simpleName)
        }
        resultCode = if (result.optBoolean("ok")) 1 else 2
        resultData = result.put("op", op ?: JSONObject.NULL).toString()
    }

    private fun run(p: AgentProcess, op: String?, intent: Intent): JSONObject {
        val pkg = intent.getStringExtra("pkg")
        return when (op) {
            "list" -> {
                val all = p.listCallersJson()
                val arr = if (pkg == null) all else JSONArray((0 until all.length()).map { all.getJSONObject(it) }.filter { it.optString("packageName") == pkg })
                ok(p).put("callers", arr)
            }
            "allow" -> {
                val entry = try {
                    p.setCaller(pkg, "allowed")
                } catch (e: IllegalArgumentException) {
                    if (e.message?.startsWith("agentos.acp.not_found") != true) throw e
                    val app = p.packageLookup.lookup(pkg ?: "") ?: throw IllegalArgumentException("agentos.acp.not_found: package is not installed")
                    JSONObject(p.callers.toJson(p.callers.grant(app.packageName, app.signingDigest, app.label)).toString())
                }
                ok(p).put("caller", entry ?: JSONObject.NULL)
            }
            "deny", "revoke" -> ok(p).put("caller", p.setCaller(pkg, "denied") ?: JSONObject.NULL)
            "remove" -> ok(p).put("caller", p.setCaller(pkg, "removed") ?: JSONObject.NULL)
            "answer" -> ok(p).put("accepted", p.answerAuthorization(intent.getStringExtra("id"), intent.getBooleanExtra("allow", false)))
            "clear" -> ok(p).put("cleared", p.callers.clear())
            "config" -> {
                val d = CallerConfig()
                p.callers.overrideConfig(
                    d.copy(
                        denyCooldownMillis = intent.getLongExtra("cooldownMs", d.denyCooldownMillis),
                        pendingTtlMillis = intent.getLongExtra("ttlMs", d.pendingTtlMillis),
                    ),
                )
                ok(p)
            }
            else -> JSONObject().put("ok", false).put("error", "unknown op: $op")
        }
    }

    private fun ok(p: AgentProcess): JSONObject = JSONObject().put("ok", true)
        .put("health", p.callers.health.let { h -> if (h is org.agentos.app.agent.acp.CallerHealth.Corrupt) "corrupt(${h.using.name.lowercase()})" else "ok" })
        .put("config", p.callers.config.let { JSONObject().put("cooldownMs", it.denyCooldownMillis).put("ttlMs", it.pendingTtlMillis) })
        .put("channels", p.acp.stats().let { JSONObject().put("thirdParty", it.optInt("thirdPartyChannels")).put("open", it.optJSONArray("open")) })

    private companion object {
        const val TAG = "AcpCallerDebug"
    }
}
