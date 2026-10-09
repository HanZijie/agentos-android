package org.agentos.sample.alarm.debug

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import java.time.LocalTime
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.plugin.McpBinderClient
import org.agentos.plugin.McpToolResult
import org.agentos.sample.alarm.agent.AlarmMcpService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 仅 debug 构建的 MCP 自测入口（docs/sample-apps.md 第 5 节第 2 条）：
 * 在独立的 `:selftest` 进程里经 [McpBinderClient] 绑定本 App 的 [AlarmMcpService]（所以是真正跨进程的 Binder，
 * 服务在主进程里和界面共用同一个仓库），走 initialize、tools/list，再把全部工具的增删改查走一遍。
 *
 * 全部自测（默认，会清理自己建的闹钟）：
 * ```
 * adb shell am broadcast -n org.agentos.sample.alarm/.debug.SelfTestReceiver
 * ```
 * 经 MCP 调一个工具（比如造一个会响的闹钟、观察界面实时刷新）：
 * ```
 * adb shell am broadcast -n org.agentos.sample.alarm/.debug.SelfTestReceiver \
 *     --es tool alarm_create --es args '{"time":"09:30","label":"x"}'
 * ```
 * 结果：logcat（tag `AlarmSelfTest`）里一行 JSON 摘要，同时放进广播的 result data（resultCode 1 = 通过，2 = 失败）。
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
                    val client = McpBinderClient.bind(context, ComponentName(context, AlarmMcpService::class.java), scope)
                    try {
                        val info = client.initialize("alarm-selftest", "1")
                        out.put("server", "${info.name}/${info.version}").put("protocolVersion", info.protocolVersion)
                        out.put("listChanged", info.toolsListChanged)
                        ok = if (toolName == null) runAll(client, out) else runOne(client, toolName, argsText, out)
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

    // ---- 单个工具 ----

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

    // ---- 全部自测 ----

    private suspend fun runAll(client: McpBinderClient, out: JSONObject): Boolean {
        val checks = JSONObject()
        fun check(name: String, passed: Boolean) {
            checks.put(name, passed)
        }

        suspend fun call(name: String, vararg args: Pair<String, JsonElement>): McpToolResult =
            client.callTool(name, buildJsonObject { args.forEach { (k, v) -> put(k, v) } })

        fun McpToolResult.obj(): JsonObject = structuredContent ?: JsonObject(emptyMap())
        fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        // tools/list：契约里的 8 个工具（加上本 App 额外的 alarm_system_next）、必填参数、注解
        val tools = client.listTools()
        out.put("tools", JSONArray(tools.map { it.name }))
        val byName = tools.associateBy { it.name }
        val contract = mapOf(
            "alarm_list" to emptyList(),
            "alarm_get" to listOf("id"),
            "alarm_create" to listOf("time"),
            "alarm_update" to listOf("id"),
            "alarm_set_enabled" to listOf("id", "enabled"),
            "alarm_delete" to listOf("id"),
            "alarm_next" to emptyList(),
            "alarm_dismiss" to emptyList(),
            "alarm_system_next" to emptyList(),
        )
        check("contract_tools", contract.all { (name, required) ->
            val t = byName[name]
            t != null && t.inputSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content } == required
        })
        check(
            "annotations",
            byName["alarm_list"]?.annotations?.readOnlyHint == true &&
                byName["alarm_get"]?.annotations?.readOnlyHint == true &&
                byName["alarm_next"]?.annotations?.readOnlyHint == true &&
                byName["alarm_system_next"]?.annotations?.readOnlyHint == true &&
                byName["alarm_delete"]?.annotations?.destructiveHint == true &&
                byName["alarm_update"]?.annotations?.idempotentHint == true &&
                byName["alarm_set_enabled"]?.annotations?.idempotentHint == true,
        )

        val before = call("alarm_list").obj()["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        check("list_before", before >= 0)

        var createdId: String? = null
        try {
            // 造一个 3 小时后的闹钟（不会在自测期间响）
            val time = LocalTime.now().plusHours(3).let { "%02d:%02d".format(it.hour, it.minute) }
            val created = call(
                "alarm_create",
                "time" to JsonPrimitive(time),
                "label" to JsonPrimitive("selftest"),
                "days" to buildJsonArray { add(JsonPrimitive("mon")); add(JsonPrimitive("fri")) },
            )
            val c = created.obj()
            createdId = c.str("id")
            val nextFire = c.str("next_fire_at")
            check("create", !created.isError && createdId != null && c.str("time") == time && c.str("label") == "selftest")
            check("create_next_fire_at_iso", nextFire != null && runCatching { OffsetDateTime.parse(nextFire) }.isSuccess)
            val id = createdId ?: return finish(out, checks)

            val got = call("alarm_get", "id" to JsonPrimitive(id))
            check("get", !got.isError && got.obj().str("id") == id)

            val updated = call("alarm_update", "id" to JsonPrimitive(id), "time" to JsonPrimitive("05:55"), "label" to JsonPrimitive("selftest 2"))
            check("update", !updated.isError && updated.obj().str("time") == "05:55" && updated.obj().str("label") == "selftest 2" &&
                updated.obj().str("next_fire_at") != nextFire)

            val off = call("alarm_set_enabled", "id" to JsonPrimitive(id), "enabled" to JsonPrimitive(false))
            check("set_enabled_off", !off.isError && (off.obj()["enabled"] as? JsonPrimitive)?.booleanOrNull == false && off.obj()["next_fire_at"] is JsonNull)
            val onlyOn = call("alarm_list", "enabled_only" to JsonPrimitive(true)).obj()["alarms"]?.jsonArray.orEmpty()
            check("list_enabled_only_excludes_off", onlyOn.none { it.jsonObject.str("id") == id })
            val on = call("alarm_set_enabled", "id" to JsonPrimitive(id), "enabled" to JsonPrimitive(true))
            check("set_enabled_on", !on.isError && on.obj().str("next_fire_at") != null)
            val onlyOn2 = call("alarm_list", "enabled_only" to JsonPrimitive(true)).obj()["alarms"]?.jsonArray.orEmpty()
            check("list_enabled_only_includes_on", onlyOn2.any { it.jsonObject.str("id") == id })

            val next = call("alarm_next")
            check("next", !next.isError && next.text != "null" && next.obj().str("next_fire_at") != null)

            // alarm_system_next：系统范围的下一个闹钟。刚建了本 App 的闹钟，所以不会是 null；
            // 归属取决于系统里有没有比它更早的别家闹钟，所以只检查字段齐全、类型对
            val systemNext = call("alarm_system_next")
            val sn = systemNext.obj()
            check(
                "system_next",
                !systemNext.isError && systemNext.text != "null" &&
                    runCatching { OffsetDateTime.parse(sn.str("next_fire_at")!!) }.isSuccess &&
                    (sn["fires_in_minutes"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() != null &&
                    (sn["owned_by_this_app"] as? JsonPrimitive)?.booleanOrNull != null,
            )

            // 错误路径：isError + 一句话原因
            check("err_get_unknown", call("alarm_get", "id" to JsonPrimitive("no-such-id")).isError)
            check("err_create_missing_time", call("alarm_create").isError)
            check("err_create_bad_time", call("alarm_create", "time" to JsonPrimitive("25:99")).isError)
            check("err_update_nothing", call("alarm_update", "id" to JsonPrimitive(id)).isError)
            check("err_dismiss_not_ringing", call("alarm_dismiss").isError)

            val deleted = call("alarm_delete", "id" to JsonPrimitive(id))
            check("delete", !deleted.isError && (deleted.obj()["deleted"] as? JsonPrimitive)?.booleanOrNull == true)
            createdId = null
            check("get_after_delete_is_error", call("alarm_get", "id" to JsonPrimitive(id)).isError)
            check("delete_twice_is_error", call("alarm_delete", "id" to JsonPrimitive(id)).isError)
            val after = call("alarm_list").obj()["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: -2
            check("list_after_matches_before", after == before)
        } finally {
            // 中途失败也不留垃圾
            createdId?.let { runCatching { call("alarm_delete", "id" to JsonPrimitive(it)) } }
        }
        return finish(out, checks)
    }

    private fun finish(out: JSONObject, checks: JSONObject): Boolean {
        out.put("checks", checks)
        val failed = checks.keys().asSequence().filter { !checks.optBoolean(it) }.toList()
        out.put("passed", checks.length() - failed.size).put("total", checks.length())
        if (failed.isNotEmpty()) out.put("failed", JSONArray(failed))
        return failed.isEmpty() && checks.length() >= 20
    }

    private companion object {
        const val TAG = "AlarmSelfTest"
    }
}
