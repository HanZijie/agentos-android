package org.agentos.sample.todo.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.sample.todo.TodoGraph
import org.agentos.sample.todo.tools.TodoDump
import org.agentos.sample.todo.tools.ToolOutput

/**
 * 仅 debug 构建（release 里没有），要求 DUMP 权限（只有 adb shell 和系统持有，其他 App 调不了）。
 * 结果都放在**广播的 result data**（一个 JSON 字符串，`Broadcast completed: result=1, data="{...}"`），不进 logcat。
 * result code：1 成功，2 失败。
 *
 * ## 读状态（给设备验收用 adb 核对 AgentOS 经 MCP 的操作结果）
 * ```
 * adb shell am broadcast -n org.agentos.sample.todo/.debug.DebugToolReceiver --es cmd dump [--ei offset N --ei limit M]
 * ```
 * ```
 * {"todos":[{"id","title","status","priority","due","due_all_day","tags","parent_id","completed_at","overdue","created_at","updated_at","notes"}…],
 *  "counts":{"todo":N,"doing":N,"done":N,"shelved":N},"total":N,"offset":N,"count":N,"next_offset":N|null,
 *  "now":"2026-…+08:00","time_zone":"Asia/Shanghai"}
 * ```
 * - 全部待办（含子任务、已完成），按创建时间排序，可分页；`next_offset` 为 null 表示最后一页。每条的字段与 MCP 的 `todo_get` 一致（同一个序列化函数）。
 * - 只读：不创建、不修改任何东西；这个 App 里本来也没有任何密钥。
 *
 * ## 复位
 * ```
 * adb shell am broadcast -n org.agentos.sample.todo/.debug.DebugToolReceiver --es cmd reset
 * ```
 * 清空本 App 的全部待办，同步完成（返回时库已清空）：`{"cleared":N,"remaining":0,"remaining_in_db":0}`，后者是直接数库里的行数。
 *
 * ## 调工具（进程内，和 MCP 注册的是同一批工具）
 * ```
 * adb shell am broadcast -n org.agentos.sample.todo/.debug.DebugToolReceiver --es tool todo_create --es args '{"title":"x"}'
 * ```
 * 结果：`{"tool":…,"ok":true,"result":{…}}` 或 `{"tool":…,"ok":false,"error":"…"}`；`--es tool list` 列出全部工具名。
 */
class DebugToolReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd")
        val tool = intent.getStringExtra("tool")
        if (cmd == null && tool == null) return
        val offset = if (intent.hasExtra("offset")) intent.getIntExtra("offset", 0) else 0
        val limit = if (intent.hasExtra("limit")) intent.getIntExtra("limit", TodoDump.MAX_LIMIT) else null
        val args = intent.getStringExtra("args") ?: "{}"
        val pending = goAsync()
        scope.launch {
            var code = 1
            val json = try {
                TodoGraph.awaitInit()
                when {
                    cmd == "dump" -> dump(offset, limit)
                    cmd == "reset" -> reset()
                    cmd != null -> {
                        code = 2
                        error("unknown cmd: $cmd (use dump or reset)")
                    }
                    tool == "list" -> buildJsonObject { put("tools", TodoGraph.tools.all.joinToString(",") { it.name }) }.toString()
                    else -> callTool(tool!!, args).also { if (it.contains("\"ok\":false")) code = 2 }
                }
            } catch (e: Exception) {
                code = 2
                buildJsonObject { put("ok", false); put("error", e.message ?: e.javaClass.simpleName) }.toString()
            }
            Log.i(TAG, "${cmd ?: tool} code=$code")
            pending.resultCode = code
            pending.resultData = json
            pending.finish()
        }
    }

    private fun dump(offset: Int, limit: Int?): String {
        val now = System.currentTimeMillis()
        val page = TodoDump.build(TodoGraph.tools, TodoGraph.repository.todos.value, now, offset, limit)
        val zone = ZoneId.systemDefault()
        return buildJsonObject {
            page.forEach { (k, v) -> put(k, v) }
            put("now", org.agentos.sample.todo.data.DueTime.iso(now, zone))
            put("time_zone", zone.id)
        }.toString()
    }

    private suspend fun reset(): String {
        val repo = TodoGraph.repository
        val cleared = repo.clearAll()
        return buildJsonObject {
            put("cleared", cleared)
            put("remaining", repo.todos.value.size)
            put("remaining_in_db", repo.storedCount())
        }.toString()
    }

    private suspend fun callTool(tool: String, args: String): String {
        val parsed = Json.parseToJsonElement(args) as? JsonObject ?: error("args must be a JSON object")
        return when (val out = TodoGraph.tools.call(tool, parsed)) {
            is ToolOutput.Ok -> buildJsonObject { put("tool", tool); put("ok", true); put("result", out.value) }.toString()
            is ToolOutput.Error -> buildJsonObject { put("tool", tool); put("ok", false); put("error", out.message) }.toString()
        }
    }

    private companion object {
        const val TAG = "TodoDebug"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
