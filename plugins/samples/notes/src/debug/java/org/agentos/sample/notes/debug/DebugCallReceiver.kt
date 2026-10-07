package org.agentos.sample.notes.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.sample.notes.NotesGraph
import org.agentos.sample.notes.tools.NotesDump
import org.agentos.sample.notes.tools.ToolOutput

/**
 * debug 包专用（Manifest 里要求 DUMP，只有 adb shell 和系统能发）。结果都放在**广播的 result data**（一个 JSON 字符串），
 * 不进 logcat：`adb shell am broadcast ...` 会打印 `Broadcast completed: result=1, data="{...}"`。result code：1 成功，2 失败。
 *
 * - 读状态：`--es cmd dump [--ei offset N] [--ei limit M]`
 *   → `{"notes":[…全部，含归档和回收站，每条含 id/title/content/tags/color/pinned/archived/trashed/created_at/updated_at/trashed_at/…],
 *   "tags":[{"name","count"}],"total":N,"offset":N,"count":N,"next_offset":N|null}`；只读，不改任何数据。数据多时用 offset / limit 分页。
 * - 清场：`--es cmd reset` → 删掉全部备忘录（含归档和回收站）→ `{"ok":true,"deleted":N}`；不会重新放示例（示例只在第一次安装时放一次）。
 * - 调工具：`--es tool note_list --es args '{"limit":3}'`（和 MCP 服务用同一个仓库对象）→ `{"tool":…,"ok":true,"result":{…}}`
 *   或 `{"tool":…,"ok":false,"error":"…"}`。
 *
 * 不返回任何密钥；这个类和 NotesDump 只在 debug 包里被引用（release 里 R8 会去掉）。
 */
class DebugCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd")
        val tool = intent.getStringExtra("tool")
        if (cmd == null && tool == null) return
        val offset = if (intent.hasExtra("offset")) intent.getIntExtra("offset", 0) else 0
        val limit = if (intent.hasExtra("limit")) intent.getIntExtra("limit", NotesDump.MAX_LIMIT) else null
        val args = intent.getStringExtra("args") ?: "{}"
        val pending = goAsync()
        scope.launch {
            var ok = true
            val json = try {
                when {
                    cmd == "dump" -> {
                        NotesGraph.awaitInit()
                        NotesDump.build(NotesGraph.tools, NotesGraph.repository.notes.value, offset, limit).toString()
                    }
                    cmd == "reset" -> {
                        NotesGraph.awaitInit()
                        val deleted = NotesGraph.repository.clearAll()
                        buildJsonObject { put("ok", true); put("deleted", deleted) }.toString()
                    }
                    cmd != null -> {
                        ok = false
                        error("unknown cmd: $cmd (use dump or reset)")
                    }
                    else -> callTool(tool!!, args).also { ok = !it.contains("\"ok\":false") }
                }
            } catch (e: Exception) {
                ok = false
                buildJsonObject { put("ok", false); put("error", e.message ?: e.javaClass.simpleName) }.toString()
            }
            pending.resultCode = if (ok) 1 else 2
            pending.resultData = json
            pending.finish()
        }
    }

    private suspend fun callTool(tool: String, args: String): String {
        val parsed = Json.parseToJsonElement(args) as? JsonObject ?: error("args must be a JSON object")
        return when (val out = NotesGraph.tools.call(tool, parsed)) {
            is ToolOutput.Ok -> buildJsonObject { put("tool", tool); put("ok", true); put("result", out.value) }.toString()
            is ToolOutput.Error -> buildJsonObject { put("tool", tool); put("ok", false); put("error", JsonPrimitive(out.message)) }.toString()
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
