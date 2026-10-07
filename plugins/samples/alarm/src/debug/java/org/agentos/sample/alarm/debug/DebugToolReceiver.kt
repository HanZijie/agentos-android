package org.agentos.sample.alarm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.sample.alarm.AlarmGraph

/**
 * 仅 debug 构建（release 里没有），要求 DUMP 权限（只有 adb shell 和系统持有，其他 App 调不了）。
 *
 * ## 读状态（给设备验收用 adb 核对 AgentOS 经 MCP 的操作结果）
 * ```
 * adb shell am broadcast -n org.agentos.sample.alarm/.debug.DebugToolReceiver --es cmd dump [--ei offset N --ei limit M]
 * ```
 * 结果放在广播的 **result data**（一个 JSON 字符串，`Broadcast completed: result=1, data="..."`），不进 logcat：
 * ```
 * {"total":3,"offset":0,"limit":50,"next_offset":null,
 *  "alarms":[{"id","time","label","days","repeat","enabled","vibrate","snooze_minutes","snoozed_until","next_fire_at","ringing"}…],
 *  "scheduled":[{"id","fire_at","registered"}…],
 *  "system":{"next_alarm_clock":"2026-…+08:00" | null,"now":"2026-…+08:00","time_zone":"Asia/Shanghai"}}
 * ```
 * - `alarms` 与 MCP 工具返回的闹钟字段完全一致（同一个序列化函数），按一天里的时刻排序，支持分页；`next_offset` 为 null 表示最后一页。
 * - `scheduled` 是**全部**闹钟里应当排在系统里的（有 `fire_at` 的）：`fire_at` 是仓库记录的已排触发时刻，
 *   `registered` 是向 AlarmManager 实测的——用 FLAG_NO_CREATE 取同一个 PendingIntent，取到才是 true，所以它能证明
 *   “通过 MCP 设的闹钟真的在系统里排了”。`system.next_alarm_clock` 是 `AlarmManager.nextAlarmClock`（系统眼里的下一个闹钟，
 *   含其他 App 的，只当旁证）。
 * - 只读：不创建、不修改、不取消任何东西；不返回任何密钥（这个 App 里本来也没有）。
 *
 * ## 复位
 * ```
 * adb shell am broadcast -n org.agentos.sample.alarm/.debug.DebugToolReceiver --es cmd reset
 * ```
 * 清空全部闹钟数据并取消所有已排的系统闹钟（含正在响的会被停掉）。返回 `{"cleared":N,"remaining_registered":0}`。
 *
 * ## 调工具（进程内，与 MCP 注册的是同一批工具；结果写 logcat）
 * ```
 * adb shell am broadcast -n org.agentos.sample.alarm/.debug.DebugToolReceiver --es tool alarm_create --es args '{"time":"09:30"}'
 * ```
 * 结果：`AlarmDebug: <tool> isError=<bool> <json>`；`--es tool list` 打印全部工具名。
 */
class DebugToolReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd")
        if (cmd != null) {
            handleCommand(context, intent, cmd)
            return
        }
        val name = intent.getStringExtra("tool") ?: return
        val graph = AlarmGraph.get(context)
        if (name == "list") {
            Log.i(TAG, "tools: " + graph.tools.tools.joinToString(",") { it.name })
            return
        }
        val tool = graph.tools.find(name)
        if (tool == null) {
            Log.w(TAG, "$name isError=true unknown tool")
            return
        }
        val args = runCatching { Json.parseToJsonElement(intent.getStringExtra("args") ?: "{}") as JsonObject }
            .getOrElse {
                Log.w(TAG, "$name isError=true args is not a JSON object")
                return
            }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val out = tool.handler(args)
                Log.i(TAG, "$name isError=${out.isError} ${out.text}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleCommand(context: Context, intent: Intent, cmd: String) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            val (code, json) = try {
                when (cmd) {
                    "dump" -> 1 to dump(context, intent.getIntExtra("offset", 0), intent.getIntExtra("limit", DEFAULT_LIMIT))
                    "reset" -> 1 to reset(context)
                    else -> 2 to error("unknown cmd '$cmd'; use dump or reset")
                }
            } catch (e: Exception) {
                2 to error("${e.javaClass.simpleName}: ${e.message}")
            }
            pending.resultCode = code
            pending.resultData = json.toString()
            pending.finish()
        }
    }

    private fun dump(context: Context, offsetArg: Int, limitArg: Int): JsonObject {
        val graph = AlarmGraph.get(context)
        val all = graph.repository.alarms.value // 只读快照
        val offset = offsetArg.coerceIn(0, all.size)
        val limit = limitArg.coerceIn(1, MAX_LIMIT)
        val page = all.drop(offset).take(limit)
        val nextOffset = (offset + page.size).takeIf { it < all.size }
        val zone = graph.repository.now().zone
        fun iso(millis: Long): String = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).truncatedTo(ChronoUnit.SECONDS).format(ISO)
        return buildJsonObject {
            put("total", all.size)
            put("offset", offset)
            put("limit", limit)
            put("next_offset", nextOffset?.let { JsonPrimitive(it) } ?: JsonNull)
            put("alarms", buildJsonArray { page.forEach { add(graph.tools.describe(it)) } })
            // scheduled：全部闹钟里仓库认为已排在系统里的，逐个向 AlarmManager 实测
            put(
                "scheduled",
                buildJsonArray {
                    for (alarm in all) {
                        val fireAt = alarm.fireAt ?: continue
                        add(
                            buildJsonObject {
                                put("id", alarm.id)
                                put("fire_at", iso(fireAt))
                                put("registered", graph.scheduler.isRegistered(alarm.id))
                            },
                        )
                    }
                },
            )
            put(
                "system",
                buildJsonObject {
                    put("next_alarm_clock", graph.scheduler.nextAlarmClockMillis()?.let { JsonPrimitive(iso(it)) } ?: JsonNull)
                    put("now", iso(System.currentTimeMillis()))
                    put("time_zone", zone.id)
                },
            )
        }
    }

    private fun reset(context: Context): JsonObject {
        val graph = AlarmGraph.get(context)
        val before = graph.repository.alarms.value
        val cleared = graph.repository.clearAll()
        // 复位后逐个实测：不应再有任何已登记的系统闹钟
        val stillRegistered = before.count { graph.scheduler.isRegistered(it.id) }
        return buildJsonObject {
            put("cleared", cleared)
            put("remaining_registered", stillRegistered)
        }
    }

    private fun error(message: String): JsonObject = buildJsonObject { put("error", message) }

    private companion object {
        const val TAG = "AlarmDebug"
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
