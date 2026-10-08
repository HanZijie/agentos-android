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
import org.agentos.sample.notes.agentos.FakeScripts
import org.agentos.sample.notes.agentos.GatewayProvider
import org.agentos.sample.notes.agentos.ScheduleReport
import org.agentos.sample.notes.agentos.ScheduleSource
import org.agentos.sample.notes.agentos.ScheduleState
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
 * - 让 AgentOS 安排：`--es cmd ask_agent --es note_id <id> [--es text <覆盖文字>] [--ei wait_s <秒，默认 45，最多 50>]`：
 *   走和面板按钮同一个用例（AgentScheduleUseCase）。这一轮在进程内跑，自己有 150 秒硬超时（到点停止，`timed_out:true`）；
 *   广播最多等 `wait_s` 秒（系统的后台广播超时是 60 秒，goAsync 撑不过去，会 ANR 并杀掉进程），走到 Done / Error 就把汇总（见
 *   ScheduleReport）放进 result data；没等到则 `pending:true`，稍后用 `ask_agent_status` 轮询到 `pending:false`。
 *   result code：1 = Done；2 = Error / 已停止 / 超时 / 参数不对；3 = 还在进行（pending）。
 *   `--es cmd ask_agent_status` 读最近一次的汇总（result code 3 = 还在进行，1 = 已结束或还没跑过）；
 *   `--es cmd ask_agent_stop` 点“停止”；
 *   `--es cmd fake_gateway --es script <名字|off>` 切换假网关脚本（名字见 FakeScripts.names；默认 off = 真网关）；
 *   `--es cmd raw_prompt --ez on true|false`：打开后发给 AgentOS 的提示词就是 `--es text` 的内容本身，用来让测试假模型的 JSON 脚本驱动真 SDK。
 *   汇总里没有任何密钥，也没有备忘全文。
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
        val noteId = intent.getStringExtra("note_id")
        val override = intent.getStringExtra("text")
        val waitMs = (if (intent.hasExtra("wait_s")) intent.getIntExtra("wait_s", DEFAULT_WAIT_S) else DEFAULT_WAIT_S)
            .coerceIn(1, MAX_WAIT_S) * 1_000L
        val script = intent.getStringExtra("script")
        scope.launch {
            var code = 1
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
                    cmd == "ask_agent" -> askAgent(context, noteId, override, waitMs).also { code = it.first }.second
                    cmd == "ask_agent_status" -> {
                        val uc = NotesGraph.agentSchedule
                        uc.restoreInterrupted()
                        if (uc.state.value.inFlight) code = 3
                        ScheduleReport.status(uc.lastRun, uc.state.value, uc.lastRunTimedOut, gatewayName(context)).toString()
                    }
                    cmd == "ask_agent_stop" -> {
                        NotesGraph.agentSchedule.stop()
                        buildJsonObject { put("ok", true) }.toString()
                    }
                    cmd == "raw_prompt" -> {
                        GatewayProvider.setRawPrompt(context, intent.getBooleanExtra("on", false))
                        buildJsonObject { put("ok", true); put("raw_prompt", GatewayProvider.rawPrompt(context)) }.toString()
                    }
                    cmd == "fake_gateway" -> {
                        if (GatewayProvider.setScript(context, script)) {
                            buildJsonObject { put("ok", true); put("script", script ?: "off"); put("scripts", FakeScripts.names.joinToString(",")) }.toString()
                        } else {
                            code = 2
                            buildJsonObject { put("ok", false); put("error", "unknown script: $script"); put("scripts", FakeScripts.names.joinToString(",")) }.toString()
                        }
                    }
                    cmd != null -> {
                        code = 2
                        error("unknown cmd: $cmd (use dump, reset, ask_agent, ask_agent_status, ask_agent_stop, fake_gateway or raw_prompt)")
                    }
                    else -> callTool(tool!!, args).also { if (it.contains("\"ok\":false")) code = 2 }
                }
            } catch (e: Exception) {
                code = 2
                buildJsonObject { put("ok", false); put("error", e.message ?: e.javaClass.simpleName) }.toString()
            }
            pending.resultCode = code
            pending.resultData = json
            pending.finish()
        }
    }

    /** 返回（result code, JSON）：1 Done，2 出错 / 参数不对，3 还在进行。note_id 找不到、也没给 text 时直接报错，不发任何东西。 */
    private suspend fun askAgent(context: Context, noteId: String?, override: String?, waitMs: Long): Pair<Int, String> {
        NotesGraph.awaitInit()
        val uc = NotesGraph.agentSchedule
        uc.restoreInterrupted()
        val note = noteId?.let { id -> NotesGraph.repository.notes.value.firstOrNull { it.id == id } }
        if (noteId != null && note == null && override == null) {
            return 2 to buildJsonObject { put("ok", false); put("error", "no note with id $noteId") }.toString()
        }
        if (noteId == null && override == null) {
            return 2 to buildJsonObject { put("ok", false); put("error", "give --es note_id or --es text") }.toString()
        }
        // 和按钮同一个来源规则：给了 text 就把它当“选中的文字”，否则用标题 + 正文
        val source = ScheduleSource.of(noteId, note?.title.orEmpty(), note?.content.orEmpty(), override)
        val outcome = uc.runToEnd(source, RUN_TIMEOUT_MS, waitMs)
        val code = when {
            outcome.pending -> 3
            outcome.state is ScheduleState.Done && !(outcome.state as ScheduleState.Done).stopped && !outcome.timedOut -> 1
            else -> 2
        }
        return code to ScheduleReport.of(outcome.state, outcome.timedOut, gatewayName(context), outcome.pending).toString()
    }

    private fun gatewayName(context: Context): String = GatewayProvider.currentScript(context)?.let { "fake:$it" } ?: "real"

    private suspend fun callTool(tool: String, args: String): String {
        val parsed = Json.parseToJsonElement(args) as? JsonObject ?: error("args must be a JSON object")
        return when (val out = NotesGraph.tools.call(tool, parsed)) {
            is ToolOutput.Ok -> buildJsonObject { put("tool", tool); put("ok", true); put("result", out.value) }.toString()
            is ToolOutput.Error -> buildJsonObject { put("tool", tool); put("ok", false); put("error", JsonPrimitive(out.message)) }.toString()
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        /** 一轮“让 AgentOS 安排”的硬超时。 */
        const val RUN_TIMEOUT_MS = 150_000L
        const val DEFAULT_WAIT_S = 45
        const val MAX_WAIT_S = 50
    }
}
