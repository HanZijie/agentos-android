package org.agentos.sample.notes.debug

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.plugin.McpBinderClient
import org.agentos.plugin.McpToolResult
import org.agentos.sample.notes.agent.NotesMcpService

/**
 * 自测入口（只在 debug 包里）：在单独的 :selftest 进程里经 [McpBinderClient] 绑定本 App 的 [NotesMcpService]，
 * 依次 initialize、tools/list（核对 10 个工具与注解），再走一遍增删改查全部工具（含错误路径），
 * 结果以一行 JSON 摘要写到 logcat（tag NotesSelfTest，只含工具名与通过 / 失败，不含备忘录内容），同时放进广播的 result data。
 * 自测创建的备忘录最后会被清理掉；数据在主进程的 SQLite 里，所以界面在前台时可以看到它实时出现又消失。
 *
 * ```
 * adb shell am broadcast -n org.agentos.sample.notes/.debug.SelfTestReceiver
 * adb logcat -d -s NotesSelfTest
 * ```
 */
class SelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pauseMs = intent.getLongExtra("pause_ms", 0L).coerceIn(0L, 5_000L)
        scope.launch {
            val checks = LinkedHashMap<String, Boolean>()
            var error: String? = null
            var toolNames: List<String> = emptyList()
            try {
                withTimeout(30_000) {
                    run(context, scope, checks, pauseMs) { toolNames = it }
                }
            } catch (e: Throwable) {
                error = "${e.javaClass.simpleName}: ${e.message}"
            }
            val ok = error == null && checks.isNotEmpty() && checks.values.all { it }
            val summary = buildJsonObject {
                put("ok", ok)
                put("pid", Process.myPid())
                put("passed", checks.count { it.value })
                put("total", checks.size)
                put("tools", JsonArray(toolNames.map { JsonPrimitive(it) }))
                put("failed", JsonArray(checks.filterValues { !it }.keys.map { JsonPrimitive(it) }))
                if (error != null) put("error", error)
            }.toString()
            Log.i(TAG, summary)
            pending.resultCode = if (ok) 1 else 2
            pending.resultData = summary
            pending.finish()
            scope.cancel()
        }
    }

    private suspend fun run(
        context: Context,
        scope: CoroutineScope,
        checks: MutableMap<String, Boolean>,
        pauseMs: Long,
        onTools: (List<String>) -> Unit,
    ) {
        val client = McpBinderClient.bind(context, ComponentName(context, NotesMcpService::class.java), scope)
        try {
            val info = client.initialize("notes-selftest", "1")
            checks["initialize"] = info.name == "notes"

            val tools = client.listTools()
            onTools(tools.map { it.name })
            val byName = tools.associateBy { it.name }
            checks["tools/list has 10 tools"] = byName.keys == EXPECTED
            checks["annotations"] = listOf("note_list", "note_get", "note_search", "tag_list").all { byName[it]?.annotations?.readOnlyHint == true } &&
                byName["note_delete"]?.annotations?.destructiveHint == true &&
                byName["note_update"]?.annotations?.idempotentHint == true &&
                (EXPECTED - "note_delete").all { byName[it]?.annotations?.destructiveHint != true }

            suspend fun call(name: String, vararg args: Pair<String, Any?>): McpToolResult =
                client.callTool(
                    name,
                    buildJsonObject {
                        for ((k, v) in args) when (v) {
                            null -> Unit
                            is Boolean -> put(k, v)
                            is Int -> put(k, v)
                            is String -> put(k, v)
                            is List<*> -> put(k, buildJsonArray { v.forEach { add(JsonPrimitive(it.toString())) } })
                            else -> error("unsupported argument type for $k")
                        }
                    },
                )

            fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
            fun McpToolResult.obj(): JsonObject = structuredContent ?: JsonObject(emptyMap())

            val marker = "selftest-${System.currentTimeMillis() % 100000}"

            // 1. create（带标签 / 颜色 / 置顶）
            val created = call("note_create", "content" to "# $marker\n- [ ] one\n- [x] two", "tags" to listOf(marker, "selftest"), "color" to "blue", "pinned" to true)
            val id = created.obj().str("id").orEmpty()
            checks["note_create"] = !created.isError && id.isNotEmpty() && created.obj().str("title") == marker
            if (pauseMs > 0) kotlinx.coroutines.delay(pauseMs)

            // 2. get
            val got = call("note_get", "id" to id)
            checks["note_get"] = !got.isError && got.obj().str("content")?.contains("- [x] two") == true && got.obj()["truncated"]?.jsonPrimitive?.boolean == false

            // 3. update（只改给出的字段；tags 整体替换）
            val updated = call("note_update", "id" to id, "title" to "$marker updated", "tags" to listOf(marker, "second"), "color" to "teal")
            checks["note_update"] = !updated.isError && updated.obj().str("title") == "$marker updated" &&
                updated.obj()["tags"]?.jsonArray?.map { it.jsonPrimitive.content } == listOf(marker, "second") && updated.obj().str("color") == "teal"
            checks["note_update keeps content"] = call("note_get", "id" to id).obj().str("content")?.contains("# $marker") == true

            // 4. append
            val appended = call("note_append", "id" to id, "text" to "- [ ] three (appended)")
            checks["note_append"] = !appended.isError && appended.obj()["appended_chars"]?.jsonPrimitive?.int == "- [ ] three (appended)".length &&
                call("note_get", "id" to id).obj().str("content")?.endsWith("- [ ] three (appended)") == true

            // 5. search（命中片段）+ list + tag_list
            val search = call("note_search", "query" to "appended")
            val hit = search.obj()["results"]?.jsonArray?.firstOrNull { it.jsonObject.str("id") == id }?.jsonObject
            checks["note_search"] = !search.isError && hit != null && hit.str("snippet")?.contains("appended") == true
            val listed = call("note_list", "tag" to marker, "limit" to 5)
            checks["note_list"] = !listed.isError && listed.obj()["notes"]?.jsonArray?.map { it.jsonObject.str("id") } == listOf(id) &&
                listed.obj()["has_more"]?.jsonPrimitive?.boolean == false &&
                (listed.obj()["notes"]!!.jsonArray[0].jsonObject.str("summary")?.length ?: 999) <= 200
            checks["tag_list"] = call("tag_list").obj()["tags"]?.jsonArray?.any { it.jsonObject.str("name") == marker } == true

            // 6. 错误路径：缺参数、非法值、不存在的 id、只允许删回收站里的
            val missing = call("note_get")
            checks["error: missing id"] = missing.isError && missing.text.contains("id")
            checks["error: bad color"] = call("note_create", "content" to "x", "color" to "plaid").isError
            checks["error: unknown id"] = call("note_get", "id" to "no-such-id").isError
            val early = call("note_delete", "id" to id)
            checks["error: delete outside trash"] = early.isError && early.text.contains("note_trash") &&
                !call("note_get", "id" to id).isError

            // 7. trash → restore → trash → delete；archived 经 update
            val trashed = call("note_trash", "id" to id)
            checks["note_trash"] = !trashed.isError && trashed.obj()["trashed"]?.jsonPrimitive?.boolean == true &&
                call("note_list", "trashed" to true, "tag" to marker).obj()["notes"]?.jsonArray?.size == 1 &&
                call("note_list", "tag" to marker).obj()["notes"]?.jsonArray?.size == 0
            checks["error: update in trash"] = call("note_update", "id" to id, "title" to "x").isError
            val restored = call("note_restore", "id" to id)
            checks["note_restore"] = !restored.isError && restored.obj()["trashed"]?.jsonPrimitive?.boolean == false
            val archived = call("note_update", "id" to id, "archived" to true)
            checks["archive via note_update"] = !archived.isError && archived.obj()["archived"]?.jsonPrimitive?.boolean == true &&
                call("note_list", "archived" to true, "tag" to marker).obj()["notes"]?.jsonArray?.size == 1
            checks["restore from archive"] = call("note_restore", "id" to id).obj()["archived"]?.jsonPrimitive?.boolean == false
            call("note_trash", "id" to id)
            val deleted = call("note_delete", "id" to id)
            checks["note_delete"] = !deleted.isError && deleted.obj()["deleted"]?.jsonPrimitive?.boolean == true &&
                call("note_get", "id" to id).isError

            // 8. 通知：工具列表不变，但连接保持可用
            client.ping()
            checks["ping"] = true
            if (pauseMs > 0) kotlinx.coroutines.delay(pauseMs)
        } finally {
            client.close()
        }
    }

    private companion object {
        const val TAG = "NotesSelfTest"
        val EXPECTED = setOf(
            "note_list", "note_get", "note_create", "note_update", "note_append", "note_search",
            "note_trash", "note_restore", "note_delete", "tag_list",
        )
    }
}
