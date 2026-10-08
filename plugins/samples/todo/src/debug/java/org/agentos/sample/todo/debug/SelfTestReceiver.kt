package org.agentos.sample.todo.debug

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.plugin.McpBinderClient
import org.agentos.plugin.McpToolResult
import org.agentos.sample.todo.agent.TodoMcpService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 仅 debug 构建的 MCP 自测入口（docs/sample-apps.md 第 5 节第 2 条）：
 * 在独立的 `:selftest` 进程里经 [McpBinderClient] 绑定本 App 的 [TodoMcpService]（所以是真正跨进程的 Binder，
 * 服务在主进程里和界面共用同一个仓库），走 initialize、tools/list，再把全部 8 个工具的增删改查和错误路径走一遍。
 *
 * 全部自测（默认，会清理自己建的待办；前台开着列表时能看到它们实时出现又消失）：
 * ```
 * adb shell am broadcast -n org.agentos.sample.todo/.debug.SelfTestReceiver [--ei pause_ms 800]
 * ```
 * 经 MCP 调一个工具（比如造一条待办、观察界面实时刷新）：
 * ```
 * adb shell am broadcast -n org.agentos.sample.todo/.debug.SelfTestReceiver --es tool todo_create --es args '{"title":"x"}'
 * ```
 * 结果：logcat（tag `TodoSelfTest`）里一行 JSON 摘要，同时放进广播的 result data（resultCode 1 = 通过，2 = 失败）。
 */
class SelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val toolName = intent.getStringExtra("tool")
        val argsText = intent.getStringExtra("args")
        val pauseMs = intent.getIntExtra("pause_ms", 0).toLong().coerceIn(0L, 5_000L)
        scope.launch {
            val out = JSONObject().put("clientPid", Process.myPid())
            var ok = false
            try {
                withTimeout(40_000) {
                    val client = McpBinderClient.bind(context, ComponentName(context, TodoMcpService::class.java), scope)
                    try {
                        val info = client.initialize("todo-selftest", "1")
                        out.put("server", "${info.name}/${info.version}").put("protocolVersion", info.protocolVersion)
                        out.put("listChanged", info.toolsListChanged)
                        ok = if (toolName == null) runAll(client, out, pauseMs) else runOne(client, toolName, argsText, out)
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

    private suspend fun runAll(client: McpBinderClient, out: JSONObject, pauseMs: Long): Boolean {
        val checks = JSONObject()
        fun check(name: String, passed: Boolean) {
            checks.put(name, passed)
        }

        suspend fun call(name: String, vararg args: Pair<String, JsonElement>): McpToolResult =
            client.callTool(name, buildJsonObject { args.forEach { (k, v) -> put(k, v) } })

        fun McpToolResult.obj(): JsonObject = structuredContent ?: JsonObject(emptyMap())
        fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
        fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
        fun s(v: String) = JsonPrimitive(v)

        // tools/list：契约里的 8 个工具、必填参数、注解
        val tools = client.listTools()
        out.put("tools", JSONArray(tools.map { it.name }))
        val byName = tools.associateBy { it.name }
        val contract = mapOf(
            "todo_list" to emptyList(),
            "todo_get" to listOf("id"),
            "todo_create" to listOf("title"),
            "todo_update" to listOf("id"),
            "todo_set_status" to listOf("id", "status"),
            "todo_delete" to listOf("id"),
            "todo_search" to listOf("query"),
            "todo_summary" to emptyList(),
        )
        check("contract_tools", byName.keys == contract.keys && contract.all { (name, required) ->
            byName.getValue(name).inputSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty() == required
        })
        check(
            "annotations",
            listOf("todo_list", "todo_get", "todo_search", "todo_summary").all { byName[it]?.annotations?.readOnlyHint == true } &&
                byName["todo_delete"]?.annotations?.destructiveHint == true &&
                byName["todo_update"]?.annotations?.idempotentHint == true &&
                byName["todo_set_status"]?.annotations?.idempotentHint == true &&
                (contract.keys - "todo_delete").all { byName[it]?.annotations?.destructiveHint != true },
        )

        val before = call("todo_list", "include_done" to JsonPrimitive(true), "limit" to JsonPrimitive(1)).obj().int("total") ?: -1
        check("list_before", before >= 0)

        val marker = "selftest-${System.currentTimeMillis() % 100000}"
        var parentId: String? = null
        try {
            // 1. create：父任务（全天截止、高优先级、标签）
            val created = call(
                "todo_create",
                "title" to s("$marker parent"),
                "notes" to s("selftest notes"),
                "priority" to s("high"),
                "due" to s("2030-01-15"),
                "tags" to buildJsonArray { add(s(marker)); add(s("selftest")) },
            )
            val c = created.obj()
            parentId = c.str("id")
            check("create", !created.isError && parentId != null && c.str("status") == "todo" && c.str("priority") == "high")
            check("create_all_day_due", c.str("due") == "2030-01-15" && c.bool("due_all_day") == true)
            val pid = parentId ?: return finish(out, checks)
            if (pauseMs > 0) kotlinx.coroutines.delay(pauseMs)

            // 2. 子任务（parent_id）；子任务不能再有子任务
            val child = call("todo_create", "title" to s("$marker child"), "parent_id" to s(pid), "tags" to buildJsonArray { add(s(marker)) })
            val cid = child.obj().str("id")
            check("create_subtask", !child.isError && cid != null && child.obj().str("parent_id") == pid)
            val grandchild = call("todo_create", "title" to s("$marker grandchild"), "parent_id" to s(cid ?: "none"))
            check("error_subtask_of_subtask", grandchild.isError && grandchild.text.contains("Subtasks cannot have"))

            // 3. get（含子任务）
            val got = call("todo_get", "id" to s(pid))
            check(
                "get_with_subtasks",
                !got.isError && got.obj().str("notes") == "selftest notes" && got.obj()["subtasks"]?.jsonArray?.size == 1 && got.obj().int("subtask_total") == 1,
            )

            // 4. update：只改给出的字段；带偏移的时刻；换优先级
            val timed = ZonedDateTime.now().plusDays(3).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            val updated = call("todo_update", "id" to s(pid), "title" to s("$marker parent 2"), "priority" to s("low"), "due" to s(timed))
            val u = updated.obj()
            check("update", !updated.isError && u.str("title") == "$marker parent 2" && u.str("priority") == "low" && u.str("notes") == "selftest notes")
            check("update_timed_due_keeps_offset", u.bool("due_all_day") == false && u.str("due") == timed)
            val cleared = call("todo_update", "id" to s(pid), "due" to s(""))
            check("update_clears_due", !cleared.isError && cleared.obj()["due"] is JsonNull && cleared.obj().bool("due_all_day") == false)

            // 5. 状态流转与完成时间
            val doing = call("todo_set_status", "id" to s(pid), "status" to s("doing"))
            check("set_status_doing", !doing.isError && doing.obj().str("status") == "doing" && doing.obj()["completed_at"] is JsonNull)
            val done = call("todo_set_status", "id" to s(pid), "status" to s("done"))
            val doneAt = done.obj().str("completed_at")
            check("set_status_done_sets_completed_at", !done.isError && doneAt != null && runCatching { OffsetDateTime.parse(doneAt) }.isSuccess)
            val doneAgain = call("todo_set_status", "id" to s(pid), "status" to s("done"))
            check("set_status_done_is_idempotent", doneAgain.obj().str("completed_at") == doneAt)
            val listDefault = call("todo_list", "tag" to s(marker)).obj()["todos"]?.jsonArray.orEmpty()
            check("list_hides_done_by_default", listDefault.none { it.jsonObject.str("id") == pid } && listDefault.any { it.jsonObject.str("id") == cid })
            val listDone = call("todo_list", "tag" to s(marker), "include_done" to JsonPrimitive(true)).obj()["todos"]?.jsonArray.orEmpty()
            check("list_include_done", listDone.any { it.jsonObject.str("id") == pid })
            val back = call("todo_set_status", "id" to s(pid), "status" to s("todo"))
            check("set_status_back_clears_completed_at", !back.isError && back.obj()["completed_at"] is JsonNull)

            // 6. list 的筛选 / 分页字段、search、summary
            val byParent = call("todo_list", "parent_id" to s(pid)).obj()
            check("list_by_parent", byParent["todos"]?.jsonArray?.map { it.jsonObject.str("id") } == listOf(cid) && byParent.bool("has_more") == false)
            val byPriority = call("todo_list", "priority" to s("low"), "tag" to s(marker)).obj()["todos"]?.jsonArray.orEmpty()
            check("list_filters", byPriority.map { it.jsonObject.str("id") } == listOf(pid))
            val search = call("todo_search", "query" to s("$marker PARENT"))
            val hit = search.obj()["results"]?.jsonArray?.firstOrNull { it.jsonObject.str("id") == pid }?.jsonObject
            check("search", !search.isError && hit != null && hit["matched_in"]?.jsonArray?.any { it.jsonPrimitive.content == "title" } == true)
            val summary = call("todo_summary").obj()
            check(
                "summary",
                (summary.int("total") ?: 0) >= before + 2 &&
                    summary["counts"]?.jsonObject?.keys == setOf("todo", "doing", "done", "shelved") &&
                    summary.int("overdue") != null && summary.int("due_today") != null && summary.int("due_this_week") != null &&
                    summary.str("time_zone") != null,
            )

            // 7. 错误路径：isError + 一句话原因
            check("error_get_unknown", call("todo_get", "id" to s("no-such-id")).isError)
            check("error_create_missing_title", call("todo_create").isError)
            check("error_create_bad_due", call("todo_create", "title" to s("x"), "due" to s("2030-02-31")).isError)
            check("error_create_due_without_offset", call("todo_create", "title" to s("x"), "due" to s("2030-01-15T10:00")).isError)
            check("error_set_status_bad_value", call("todo_set_status", "id" to s(pid), "status" to s("finished")).isError)
            check("error_update_nothing", call("todo_update", "id" to s(pid)).isError)
            check("error_unknown_parent", call("todo_create", "title" to s("x"), "parent_id" to s("no-such-id")).isError)

            // 8. 删除：子任务连带删除，返回删除总数
            val deleted = call("todo_delete", "id" to s(pid))
            check("delete_cascades", !deleted.isError && deleted.obj().int("deleted") == 2 && deleted.obj().int("subtasks_deleted") == 1)
            parentId = null
            check("get_after_delete_is_error", call("todo_get", "id" to s(pid)).isError && call("todo_get", "id" to s(cid ?: "none")).isError)
            check("delete_twice_is_error", call("todo_delete", "id" to s(pid)).isError)
            val after = call("todo_list", "include_done" to JsonPrimitive(true), "limit" to JsonPrimitive(1)).obj().int("total") ?: -2
            check("list_after_matches_before", after == before)
            if (pauseMs > 0) kotlinx.coroutines.delay(pauseMs)
        } finally {
            // 中途失败也不留垃圾（删父任务会连子任务一起删）
            parentId?.let { id -> runCatching { call("todo_delete", "id" to s(id)) } }
        }
        return finish(out, checks)
    }

    private fun finish(out: JSONObject, checks: JSONObject): Boolean {
        out.put("checks", checks)
        val failed = checks.keys().asSequence().filter { !checks.optBoolean(it) }.toList()
        out.put("passed", checks.length() - failed.size).put("total", checks.length())
        if (failed.isNotEmpty()) out.put("failed", JSONArray(failed))
        return failed.isEmpty() && checks.length() >= 25
    }

    private companion object {
        const val TAG = "TodoSelfTest"
    }
}
