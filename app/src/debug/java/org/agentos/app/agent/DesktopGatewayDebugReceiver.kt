package org.agentos.app.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.json.JSONObject

/**
 * **debug 包专用的测试入口**（app/src/debug 源集，release 包里没有这个组件）：tests/acp-conformance 的设备模式用它操作
 * 电脑端接入（DesktopGateway）。adb shell 绑不了只接受本 App 的 IAgentControl，所以不走 v3。
 *
 * ```
 * adb shell am broadcast -f 32 -n org.agentos.app/.agent.DesktopGatewayDebugReceiver --es op <op> [--el ttlMs <毫秒>]
 * ```
 *
 * op：`enable`、`disable`、`pair`（生成配对码，返回 code）、`status`、`revoke_all`。结果在广播的 result data 里（JSON）。
 * 配对码只在返回值里，不经 adb 命令行传入（API 37 的 adbd 会把 adb shell 命令行写进 logcat）。
 * Manifest 要求发送方持有 DUMP 权限（adb shell 和系统有，普通 App 没有）；再加一道：不可调试的包一律返回 `not_debuggable`。
 * 运行在 `:agent` 进程；`:agent` 没在运行时由这条广播拉起。
 */
class DesktopGatewayDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val process = AgentProcess.get(context)
        val op = intent.getStringExtra("op")
        val result = try {
            if (!process.debuggable) {
                JSONObject().put("ok", false).put("error", "not_debuggable")
            } else {
                val gw = process.desktop
                when (op) {
                    "enable" -> gw.setEnabled(true).let { gw.status().put("ok", true) }
                    "disable" -> gw.setEnabled(false).let { gw.status().put("ok", true) }
                    // modelUsable：设备用例据此判断要不要先配测试模型（C 的 ensureTestModel）
                    "status" -> gw.stats().put("ok", true).put("modelUsable", (process.models.get()["usable"] as? JsonPrimitive)?.booleanOrNull == true)
                    "revoke_all" -> JSONObject().put("ok", true).put("revoked", gw.revokeAll())
                    "pair" -> gw.newPairingCodeJson(intent.getLongExtra("ttlMs", 0L)).put("ok", true)
                    else -> JSONObject().put("ok", false).put("error", "unknown op: $op")
                }
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
        }
        resultCode = if (result.optBoolean("ok")) RESULT_OK_CODE else RESULT_ERROR_CODE
        resultData = result.put("op", op ?: JSONObject.NULL).toString()
    }

    companion object {
        const val RESULT_OK_CODE = 1
        const val RESULT_ERROR_CODE = 2
    }
}
