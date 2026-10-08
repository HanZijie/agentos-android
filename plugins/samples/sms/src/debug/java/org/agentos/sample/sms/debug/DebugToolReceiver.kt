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
 *  "total":N,"offset":0,"count":N,"next_offset":null,"now":"…","time_zone":"…"}
 * ```
 * - `outbox` 是**本 App 发出的**短信（新到旧，分页，`next_offset` 为 null 表示最后一页）；**不读系统短信库**，dump 里没有任何收到的短信内容。
 *
 * ## 复位
 * ```
 * adb shell am broadcast -n org.agentos.sample.sms/.debug.DebugToolReceiver --es cmd reset
 * ```
 * **只清 outbox**（返回 `{"cleared":N,"outbox_remaining":0}`）。不碰系统短信库（非默认短信应用也删不掉），不改设置、不改权限。
 * 清空 outbox 同时清掉发送频率和去重的历史。
 *
 * ## 改设置（测试用；用户在设置页做同样的事）
 * ```
 * adb shell am broadcast -n org.agentos.sample.sms/.debug.DebugToolReceiver --es cmd set --es key allow_short_numbers --es value true
 * ```
 * `key`：`mask_codes`（true/false）、`allow_short_numbers`（true/false）、`rate_limit`（1–30）。返回新的 `settings`。
 * 模拟器之间互发短信的号码是 4 位端口号（5604），属于短号，默认被拒；测试前要先放行。
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
                    cmd != null -> 2 to error("unknown cmd '$cmd'; use dump, reset or set")
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
            nowMillis = System.currentTimeMillis(),
            zone = ZoneId.systemDefault(),
            offset = offset,
            limit = limit,
        )
    }

    private fun reset(context: Context): JsonObject {
        val outbox = SmsGraph.get(context).outbox
        val cleared = outbox.clear()
        return buildJsonObject {
            put("cleared", cleared)
            put("outbox_remaining", outbox.count())
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

    private companion object {
        const val TAG = "SmsDebug"
    }
}
