package org.agentos.sample.sms.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.sample.sms.SmsGraph
import org.agentos.sample.sms.agentos.FakeScripts
import org.agentos.sample.sms.agentos.GatewayProvider
import org.agentos.sample.sms.agentos.ScheduleReport
import org.agentos.sample.sms.agentos.ScheduleState
import org.agentos.sample.sms.data.MessageQuery
import org.agentos.sample.sms.rules.RateLimit
import org.agentos.sample.sms.tools.SmsDump

/**
 * 仅 debug 构建（release 里没有），要求 DUMP 权限（只有 adb shell 和系统持有，其他 App 调不了）。
 * 结果放在广播的 **result data**（一个 JSON 字符串，`Broadcast completed: result=1, data="..."`）。
 *
 * ## 读状态
 * ```
 * adb shell am broadcast -n org.agentos.sample.sms/.debug.DebugToolReceiver --es cmd dump [--ei offset N --ei limit M]
 * ```
 * ```
 * {"mode":"full|partial|compose_only","permissions":{"read_sms":true,"send_sms":true},
 *  "settings":{"mask_codes":true,"allow_short_numbers":false,"rate_limit":5},
 *  "outbox":[{"id","to","text","parts","state","sent_parts","delivered_parts","error","created_at","updated_at"}…],
 *  "drafts":[{"id","to","text","created_at"}…],
 *  "total":N,"offset":0,"count":N,"next_offset":null,"now":"…","time_zone":"…"}
 * ```
 * - `outbox` 是**本 App 发出的**短信（新到旧，分页，`next_offset` 为 null 表示最后一页）；**不读系统短信库**，dump 里没有任何收到的短信内容。
 *
 * ## 复位
 * ```
 * adb shell am broadcast -n org.agentos.sample.sms/.debug.DebugToolReceiver --es cmd reset
 * ```
 * **只清本 App 自己的记录**：outbox、`sms_compose` 留下的草稿和“已处理”记录（返回 `{"cleared":N,"outbox_remaining":0,"drafts_cleared":M,"processed_cleared":K}`）。
 * 不碰系统短信库（非默认短信应用也删不掉），不改设置、不改权限。清空 outbox 同时清掉发送频率和去重的历史。
 *
 * ## 改设置（测试用；用户在设置页做同样的事）
 * ```
 * adb shell am broadcast -n org.agentos.sample.sms/.debug.DebugToolReceiver --es cmd set --es key allow_short_numbers --es value true
 * ```
 * `key`：`mask_codes`（true/false）、`allow_short_numbers`（true/false）、`rate_limit`（1–30）。返回新的 `settings`。
 * 模拟器之间互发短信的号码是 4 位端口号（5604），属于短号，默认被拒；测试前要先放行。
 *
 * ## 让 AgentOS 安排（会话页按钮的同一条路径：SmsScheduleUseCase）
 * ```
 * adb shell am broadcast -n org.agentos.sample.sms/.debug.DebugToolReceiver --es cmd ask_agent --es address <号码> \
 *     [--ez include_processed true] [--es instructions <覆盖任务说明>] [--ei wait_s <秒，默认 45，最多 50>]
 * ```
 * 取这个号码最新的 200 条短信（按设置遮蔽验证码），默认只带没处理过的，开一轮，等它走到 Done / Error，把汇总（见 ScheduleReport）放进 result data。
 * 这一轮自己有 150 秒硬超时（`timed_out:true`）；广播最多等 `wait_s` 秒（后台广播 60 秒超时），没等到则 `pending:true`，稍后用 `ask_agent_status` 轮询。
 * result code：1 = Done；2 = Error / 已停止 / 超时 / 参数不对；3 = 还在进行。
 * `--es cmd ask_agent_status` 读最近一次的汇总；`--es cmd ask_agent_stop` 点“停止”；
 * `--es cmd fake_gateway --es script <名字|off>` 切换假网关脚本（名字见 FakeScripts.names；默认 off = 真网关）；
 * `--es cmd raw_prompt --ez on true|false`：打开后发给 AgentOS 的提示词就是任务说明本身，用来让测试假模型的 JSON 脚本驱动真 SDK；
 * `--es cmd processed [--es op clear]`：读 / 清空“已处理”记录（返回条数）；
 * `--es cmd instructions [--es op reset | --es text <新任务说明>]`：读 / 恢复默认 / 设置用户的任务说明（和面板里编辑框同一个存储）。
 * 汇总里没有任何密钥，也没有短信原文。
 *
 * ## 调工具（进程内，与 MCP 注册的是同一批工具）
 * ```
 * adb shell am broadcast -n org.agentos.sample.sms/.debug.DebugToolReceiver --es tool sms_thread_list --es args '{"limit":5}'
 * ```
 * 结果在 result data：`{"tool","isError","result"}`；logcat（tag `SmsDebug`）只写工具名、isError 和结果长度，**不写短信内容**。`--es tool list` 返回全部工具名。
 */
class DebugToolReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            val (code, json) = try {
                val cmd = intent.getStringExtra("cmd")
                val tool = intent.getStringExtra("tool")
                when {
                    cmd == "dump" -> 1 to dump(context, intent.getIntExtra("offset", 0), intent.getIntExtra("limit", SmsDump.DEFAULT_LIMIT))
                    cmd == "reset" -> 1 to reset(context)
                    cmd == "set" -> set(context, intent.getStringExtra("key"), intent.getStringExtra("value"))
                    cmd == "ask_agent" -> askAgent(context, intent)
                    cmd == "ask_agent_status" -> {
                        val uc = SmsGraph.get(context).agentSchedule
                        uc.restoreInterrupted()
                        (if (uc.state.value.inFlight) 3 else 1) to ScheduleReport.status(uc.lastRun, uc.state.value, uc.lastRunTimedOut, gatewayName(context))
                    }
                    cmd == "ask_agent_stop" -> {
                        SmsGraph.get(context).agentSchedule.stop()
                        1 to buildJsonObject { put("ok", true) }
                    }
                    cmd == "raw_prompt" -> {
                        GatewayProvider.setRawPrompt(context, intent.getBooleanExtra("on", false))
                        1 to buildJsonObject { put("ok", true); put("raw_prompt", GatewayProvider.rawPrompt(context)) }
                    }
                    cmd == "fake_gateway" -> {
                        val script = intent.getStringExtra("script")
                        if (GatewayProvider.setScript(context, script)) {
                            1 to buildJsonObject { put("ok", true); put("script", script ?: "off"); put("scripts", FakeScripts.names.joinToString(",")) }
                        } else {
                            2 to buildJsonObject { put("ok", false); put("error", "unknown script: $script"); put("scripts", FakeScripts.names.joinToString(",")) }
                        }
                    }
                    cmd == "processed" -> processed(context, intent.getStringExtra("op"))
                    cmd == "instructions" -> instructions(context, intent.getStringExtra("op"), intent.getStringExtra("text"))
                    cmd != null -> 2 to error("unknown cmd '$cmd'; use dump, reset, set, ask_agent, ask_agent_status, ask_agent_stop, fake_gateway, raw_prompt, processed or instructions")
                    tool != null -> callTool(context, tool, intent.getStringExtra("args"))
                    else -> 2 to error("pass --es cmd dump|reset|set or --es tool <name>")
                }
            } catch (e: Exception) {
                2 to error("${e.javaClass.simpleName}: ${e.message}")
            }
            pending.resultCode = code
            pending.resultData = json.toString()
            pending.finish()
        }
    }

    private fun dump(context: Context, offset: Int, limit: Int): JsonObject {
        val graph = SmsGraph.get(context)
        return SmsDump.build(
            access = graph.gateway.access(),
            settings = graph.settings.current,
            outbox = graph.outbox,
            drafts = graph.drafts,
            nowMillis = System.currentTimeMillis(),
            zone = ZoneId.systemDefault(),
            offset = offset,
            limit = limit,
        )
    }

    private fun reset(context: Context): JsonObject {
        val graph = SmsGraph.get(context)
        val cleared = graph.outbox.clear()
        val draftsCleared = graph.drafts.clear() // 草稿也是本 App 自己的记录，不是系统短信
        val processedCleared = graph.processed.clear() // “已处理”记录同样只是本 App 自己的 id 列表
        return buildJsonObject {
            put("cleared", cleared)
            put("outbox_remaining", graph.outbox.count())
            put("drafts_cleared", draftsCleared)
            put("processed_cleared", processedCleared)
        }
    }

    private fun set(context: Context, key: String?, value: String?): Pair<Int, JsonObject> {
        val settings = SmsGraph.get(context).settings
        when (key) {
            "mask_codes" -> settings.update { it.copy(maskCodes = value.toBoolean()) }
            "allow_short_numbers" -> settings.update { it.copy(allowShortNumbers = value.toBoolean()) }
            "rate_limit" -> {
                val n = value?.toIntOrNull() ?: return 2 to error("rate_limit must be an integer ${RateLimit.MIN_LIMIT}..${RateLimit.MAX_LIMIT}")
                settings.update { it.copy(rateLimit = n) }
            }
            else -> return 2 to error("unknown key '$key'; use mask_codes, allow_short_numbers or rate_limit")
        }
        val s = settings.current
        return 1 to buildJsonObject {
            put("mask_codes", s.maskCodes)
            put("allow_short_numbers", s.allowShortNumbers)
            put("rate_limit", s.rateLimit)
        }
    }

    private suspend fun callTool(context: Context, name: String, argsText: String?): Pair<Int, JsonObject> {
        val tools = SmsGraph.get(context).tools
        if (name == "list") return 1 to buildJsonObject { put("tools", tools.tools.joinToString(",") { it.name }) }
        val tool = tools.find(name) ?: return 2 to error("unknown tool '$name'")
        val args = runCatching { Json.parseToJsonElement(argsText ?: "{}") as JsonObject }.getOrElse { return 2 to error("args is not a JSON object") }
        val out = tools.find(name)!!.handler(args)
        Log.i(TAG, "$name isError=${out.isError} resultChars=${out.text.length}")
        return (if (out.isError) 2 else 1) to buildJsonObject {
            put("tool", tool.name)
            put("isError", out.isError)
            put("result", out.text)
        }
    }

    private fun error(message: String): JsonObject = buildJsonObject { put("error", message) }

    private fun gatewayName(context: Context): String = GatewayProvider.currentScript(context)?.let { "fake:$it" } ?: "real"

    /** 返回（result code, JSON）：1 Done，2 出错 / 参数不对，3 还在进行。号码不对、或这个号码下没有短信时直接报错，不发任何东西。 */
    private suspend fun askAgent(context: Context, intent: Intent): Pair<Int, JsonObject> {
        val address = intent.getStringExtra("address") ?: return 2 to error("give --es address <number>")
        val graph = SmsGraph.get(context)
        if (!graph.gateway.access().canRead) return 2 to error("READ_SMS is not granted")
        val uc = graph.agentSchedule
        uc.restoreInterrupted()
        val records = graph.gateway.messages(MessageQuery(address, null, null, 0, MESSAGE_LIMIT))
        var source = uc.sourceFor(address, records, graph.settings.current.maskCodes, intent.getBooleanExtra("include_processed", false))
        intent.getStringExtra("instructions")?.let { source = source.copy(instructions = it) }
        val waitMs = (if (intent.hasExtra("wait_s")) intent.getIntExtra("wait_s", DEFAULT_WAIT_S) else DEFAULT_WAIT_S)
            .coerceIn(1, MAX_WAIT_S) * 1_000L
        val outcome = uc.runToEnd(source, RUN_TIMEOUT_MS, waitMs)
        val code = when {
            outcome.pending -> 3
            outcome.state is ScheduleState.Done && !(outcome.state as ScheduleState.Done).stopped && !outcome.timedOut -> 1
            else -> 2
        }
        return code to ScheduleReport.of(outcome.state, outcome.timedOut, gatewayName(context), outcome.pending)
    }

    private fun processed(context: Context, op: String?): Pair<Int, JsonObject> {
        val ledger = SmsGraph.get(context).processed
        return when (op) {
            null -> 1 to buildJsonObject { put("count", ledger.ids.value.size) }
            "clear" -> 1 to buildJsonObject { put("cleared", ledger.clear()) }
            else -> 2 to error("unknown op '$op'; use clear or omit it")
        }
    }

    private fun instructions(context: Context, op: String?, text: String?): Pair<Int, JsonObject> {
        val uc = SmsGraph.get(context).agentSchedule
        when {
            op == "reset" -> uc.resetInstructions()
            text != null -> uc.setInstructions(text)
            op != null -> return 2 to error("unknown op '$op'; use reset, or pass --es text")
        }
        return 1 to buildJsonObject {
            put("customized", uc.instructionsCustomized)
            put("chars", uc.instructions.length)
            put("text", uc.instructions)
        }
    }

    private companion object {
        const val TAG = "SmsDebug"

        /** 一轮“让 AgentOS 安排”的硬超时。 */
        const val RUN_TIMEOUT_MS = 150_000L
        const val DEFAULT_WAIT_S = 45
        const val MAX_WAIT_S = 50

        /** 和会话页一样：取这个号码最新的 200 条。 */
        const val MESSAGE_LIMIT = 200
    }
}
