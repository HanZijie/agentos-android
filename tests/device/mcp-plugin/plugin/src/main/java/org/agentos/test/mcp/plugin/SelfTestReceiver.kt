package org.agentos.test.mcp.plugin

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.plugin.McpBinderClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自测入口（只在测试 App 里）：在 :selftest 进程里经 [McpBinderClient] 绑定本 App 的 [TestMcpService]，
 * 依次 initialize、tools/list、tools/call（含错误、取消、list_changed），结果放进广播的 result data（JSON），不进日志。
 *
 * ```
 * adb shell am broadcast -n org.agentos.test.mcp.plugin/.SelfTestReceiver
 * ```
 */
class SelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            val out = JSONObject()
            val checks = JSONObject()
            try {
                withTimeout(8_000) {
                    val client = McpBinderClient.bind(context, ComponentName(context, TestMcpService::class.java), scope)
                    val info = client.initialize("selftest", "1")
                    out.put("server", "${info.name}/${info.version}").put("protocolVersion", info.protocolVersion)
                    val tools = client.listTools()
                    out.put("tools", JSONArray(tools.map { it.name }))
                    checks.put("listed", tools.any { it.name == "echo" } && tools.first { it.name == "wipe" }.annotations.destructiveHint == true)

                    val echo = client.callTool("echo", buildJsonObject { put("text", "自测") })
                    val pid = echo.structuredContent?.get("pid")?.jsonPrimitive?.int
                    checks.put("echo", !echo.isError && echo.structuredContent?.get("text")?.jsonPrimitive?.content == "自测")
                    checks.put("crossProcess", pid != null && pid != Process.myPid())
                    checks.put("errorResult", client.callTool("echo").isError)

                    val before = client.callTool("stats").structuredContent!!["cancelled"]!!.jsonPrimitive.int
                    val slow = async { client.callTool("slow", buildJsonObject { put("ms", 30_000) }) }
                    delay(300)
                    slow.cancel()
                    var cancelledSeen = false
                    repeat(40) {
                        if (!cancelledSeen) {
                            cancelledSeen = client.callTool("stats").structuredContent!!["cancelled"]!!.jsonPrimitive.int > before
                            if (!cancelledSeen) delay(50)
                        }
                    }
                    checks.put("cancel", cancelledSeen)

                    val changed = async { client.toolsChanged.first() }
                    delay(50)
                    client.callTool("add_tool")
                    changed.await()
                    checks.put("listChanged", client.listTools().any { it.name == "extra" })
                    client.callTool("remove_tool")
                    client.close()
                }
            } catch (e: Throwable) {
                out.put("error", "${e.javaClass.name}: ${e.message}")
            }
            val ok = !out.has("error") && checks.length() >= 6 && checks.keys().asSequence().all { checks.optBoolean(it) }
            pending.resultCode = if (ok) 1 else 2
            pending.resultData = out.put("ok", ok).put("checks", checks).toString()
            pending.finish()
            scope.cancel()
        }
    }
}
