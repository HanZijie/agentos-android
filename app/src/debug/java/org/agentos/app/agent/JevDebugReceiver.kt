package org.agentos.app.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File

/**
 * **debug 包专用的测试入口**（app/src/debug 源集，release 包里没有）：用 adb 配置 Jev（自动选会话，D5.1），不点界面。
 *
 * ```
 * # key 只能经 stdin 进设备（API 37 的 adbd 会把 adb shell 的整条命令行写进 logcat）：
 * adb shell content write --uri content://org.agentos.test.acp.inapp.keydrop/jev_key < keyfile
 * adb shell am broadcast -f 32 -n org.agentos.app/.agent.JevDebugReceiver --es op set [--es endpoint <url>]
 * adb shell am broadcast -f 32 -n org.agentos.app/.agent.JevDebugReceiver --es op status|clear
 * ```
 *
 * `set` 读 `files/test/jev_key`（KeyDropProvider 写入）、读完立即删除，经与设置页相同的 [JevSources.set] 写入 Keystore
 * （endpoint 规则相同：https，只有回环地址可以用 http；不带 endpoint = 默认 https://api.typesafe.ai/v1/systemone）。
 * 结果在广播的 result data（JSON，只有首尾各 4 位，不进日志）。要求 DUMP 权限；不可调试的包一律 `not_debuggable`。运行在 `:agent`。
 */
class JevDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val process = AgentProcess.get(context)
        val op = intent.getStringExtra("op")
        val result = try {
            if (!process.debuggable) {
                JSONObject().put("ok", false).put("error", "not_debuggable")
            } else {
                when (op) {
                    "set" -> {
                        val f = File(context.filesDir, "test/jev_key")
                        val key = try {
                            f.takeIf { it.isFile }?.readText()?.trim()
                        } finally {
                            f.delete()
                        }
                        if (key.isNullOrEmpty()) {
                            JSONObject().put("ok", false).put("error", "no_key_file")
                        } else {
                            JSONObject(process.jev.set(intent.getStringExtra("endpoint"), key).toString()).put("ok", true)
                        }
                    }
                    "clear" -> process.jev.clear().let { JSONObject(process.jev.get().toString()).put("ok", true) }
                    "status" -> JSONObject(process.jev.get().toString()).put("ok", true)
                    else -> JSONObject().put("ok", false).put("error", "unknown op")
                }
            }
        } catch (e: JevSourceException) {
            JSONObject().put("ok", false).put("error", e.code)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.javaClass.simpleName)
        }
        resultCode = if (result.optBoolean("ok")) 1 else 2
        resultData = result.put("op", op ?: JSONObject.NULL).toString()
    }
}
