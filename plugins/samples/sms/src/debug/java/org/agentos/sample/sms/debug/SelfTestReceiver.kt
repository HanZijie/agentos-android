package org.agentos.sample.sms.debug

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.plugin.McpBinderClient
import org.agentos.plugin.McpToolResult
import org.agentos.sample.sms.agent.SmsMcpService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 仅 debug 构建的 MCP 自测入口（docs/sample-apps.md, section 5, item 2）：
 * 在独立的 `:selftest` 进程里经 [McpBinderClient] 绑定本 App 的 [SmsMcpService]（真正跨进程的 Binder），
 * 走 initialize、tools/list（六个工具、必填参数、注解），再按**当前权限状态**走一遍：
 * - 没有权限：读 / 发工具必须返回明确的权限错误（仅撰写模式），目录里工具仍然全部在；
 * - 有 READ_SMS：三个读工具返回正确的结构（只看结构，**结果里不带任何短信内容**）；
 * - 有 SEND_SMS：只走会被拒绝的路径（缺参数、非法号码、过长正文、未知 id）。**自测不发任何短信**，也不调用 sms_compose 打开界面。
 *
 * ```
 * adb shell am broadcast -n org.agentos.sample.sms/.debug.SelfTestReceiver
 * ```
 * 结果：logcat（tag `SmsSelfTest`）里一行 JSON 摘要，同时放进广播的 result data（resultCode 1 = 通过，2 = 失败）。
 * 也可以经 MCP 调单个工具：`--es tool sms_send_status --es args '{"id":"1"}'`（`--es tool tools` 只列工具名）。
 */
class SelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val toolName = intent.getStringExtra("tool")
        val argsText = intent.getStringExtra("args")
        scope.launch {
            val out = JSONObject().put("clientPid", Process.myPid())
            var ok = false
            try {
                withTimeout(40_000) {
                    val client = McpBinderClient.bind(context, ComponentName(context, SmsMcpService::class.java), scope)
                    try {
                        val info = client.initialize("sms-selftest", "1")
                        out.put("server", "${info.name}/${info.version}").put("protocolVersion", info.protocolVersion)
                        out.put("listChanged", info.toolsListChanged)
                        ok = if (toolName == null) runAll(context, client, out) else runOne(client, toolName, argsText, out)
                    } finally {
                        client.close()
                    }
                }
            } catch (e: Throwable) {
                out.put("error", "${e.javaClass.simpleName}: ${e.message}")
            }
            out.put("ok", ok)
            Log.i(TAG, out.toString())
            pending.resultCode = if (ok) 1 else 2
            pending.resultData = out.toString()
            pending.finish()
            scope.cancel()
        }
    }

    private suspend fun runOne(client: McpBinderClient, name: String, argsText: String?, out: JSONObject): Boolean {
        if (name == "tools") {
            out.put("tools", JSONArray(client.listTools().map { it.name }))
            return true
        }
        val args = try {
            (argsText?.let { Json.parseToJsonElement(it) } ?: JsonObject(emptyMap())) as JsonObject
        } catch (e: Exception) {
            out.put("error", "args is not a JSON object")
            return false
        }
        val result = client.callTool(name, args)
        out.put("tool", name).put("isError", result.isError).put("result", result.text)
        return !result.isError
    }

    private suspend fun runAll(context: Context, client: McpBinderClient, out: JSONObject): Boolean {
        val checks = JSONObject()
        fun check(name: String, passed: Boolean) {
            checks.put(name, passed)
        }

        suspend fun call(name: String, vararg args: Pair<String, JsonElement>): McpToolResult =
            client.callTool(name, buildJsonObject { args.forEach { (k, v) -> put(k, v) } })

        fun McpToolResult.obj(): JsonObject = structuredContent ?: JsonObject(emptyMap())
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val canRead = granted(Manifest.permission.READ_SMS)
        val canSend = granted(Manifest.permission.SEND_SMS)
        out.put("read_sms", canRead).put("send_sms", canSend)

        // tools/list：契约里的 6 个工具、必填参数、注解——不随权限增减
        val tools = client.listTools()
        out.put("tools", JSONArray(tools.map { it.name }))
        val byName = tools.associateBy { it.name }
        val contract = mapOf(
            "sms_thread_list" to emptyList(),
            "sms_message_list" to listOf("address"),
            "sms_search" to listOf("query"),
            "sms_send" to listOf("to", "text"),
            "sms_send_status" to listOf("id"),
            "sms_compose" to listOf("to"),
        )
        check("contract_tools", tools.size == contract.size && contract.all { (name, required) ->
            val t = byName[name]
            t != null && t.inputSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content } == required
        })
        check(
            "annotations",
            byName["sms_thread_list"]?.annotations?.readOnlyHint == true &&
                byName["sms_message_list"]?.annotations?.readOnlyHint == true &&
                byName["sms_search"]?.annotations?.readOnlyHint == true &&
                byName["sms_send_status"]?.annotations?.readOnlyHint == true &&
                byName["sms_send"]?.annotations?.destructiveHint == true &&
                byName["sms_compose"]?.annotations?.destructiveHint != true,
        )

        // sms_compose 不需要任何权限：这里只走拒绝路径（不打开界面）
        check("compose_missing_to", call("sms_compose").isError)
        check("compose_bad_to", call("sms_compose", "to" to JsonPrimitive("not a number")).isError)

        val readTools = listOf(
            Triple("sms_thread_list", emptyArray<Pair<String, JsonElement>>(), "threads"),
            Triple("sms_message_list", arrayOf<Pair<String, JsonElement>>("address" to JsonPrimitive("+0000000")), "messages"),
            Triple("sms_search", arrayOf<Pair<String, JsonElement>>("query" to JsonPrimitive("zzz-selftest-no-such-text")), "messages"),
        )
        for ((name, args, listKey) in readTools) {
            val result = call(name, *args)
            if (canRead) {
                val o = result.obj()
                check(
                    "${name}_shape",
                    !result.isError && o["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() != null &&
                        o["has_more"]?.jsonPrimitive?.contentOrNull != null && o[listKey]?.jsonArray != null &&
                        o["masking"] != null,
                )
            } else {
                check("${name}_denied", result.isError && permissionMessage(result.text))
            }
        }

        val send = call("sms_send", "to" to JsonPrimitive("abc"), "text" to JsonPrimitive("x"))
        check(if (canSend) "send_bad_number" else "send_denied", send.isError && (canSend || permissionMessage(send.text)))
        val sendMissing = call("sms_send", "to" to JsonPrimitive("+10000000000"))
        check("send_missing_text", sendMissing.isError)
        val sendLong = call("sms_send", "to" to JsonPrimitive("+10000000000"), "text" to JsonPrimitive("x".repeat(501)))
        check("send_text_too_long", sendLong.isError && (!canSend || sendLong.text.contains("too long")))
        val sendMany = call("sms_send", "to" to JsonPrimitive("+10000000000,+10000000001"), "text" to JsonPrimitive("x"))
        check("send_two_recipients", sendMany.isError && (!canSend || sendMany.text.contains("one recipient", ignoreCase = true)))
        val status = call("sms_send_status", "id" to JsonPrimitive("no-such-id"))
        check("status_unknown_id", status.isError && (!canSend || status.text.contains("Unknown id")))

        return finish(out, checks)
    }

    private fun permissionMessage(text: String) =
        text.contains("permission", ignoreCase = true) || text.contains("Compose-only", ignoreCase = true)

    private fun finish(out: JSONObject, checks: JSONObject): Boolean {
        out.put("checks", checks)
        val failed = checks.keys().asSequence().filter { !checks.optBoolean(it) }.toList()
        out.put("passed", checks.length() - failed.size).put("total", checks.length())
        if (failed.isNotEmpty()) out.put("failed", JSONArray(failed))
        return failed.isEmpty() && checks.length() >= 10
    }

    private companion object {
        const val TAG = "SmsSelfTest"
    }
}
