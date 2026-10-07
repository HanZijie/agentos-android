package org.agentos.sample.calendar.debug

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
import org.agentos.sample.calendar.agent.CalendarMcpService
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 仅 debug：MCP 自测入口（docs/sample-apps.md 第 5 节）。在 :selftest 进程里经 [McpBinderClient] 绑定本 App 的
 * [CalendarMcpService]，initialize、tools/list，再把全部工具（含错误路径）走一遍；结果是一行 JSON 摘要，写进 logcat
 * （tag CalendarMcpSelfTest）和广播的 result data。
 *
 * ```
 * adb shell am broadcast -n org.agentos.sample.calendar/.debug.McpSelfTestReceiver                      # 全部工具，结束后清理
 * adb shell am broadcast -n org.agentos.sample.calendar/.debug.McpSelfTestReceiver --es mode create --ei start_in 3 --ei lead 1
 *     # 只经 MCP 创建一个“N 分钟后开始、提前 M 分钟提醒”的日程并保留（看界面实时刷新与到点通知）
 * ```
 */
class McpSelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val mode = intent.getStringExtra("mode") ?: "full"
        val startIn = intent.getIntExtra("start_in", 3)
        val lead = intent.getIntExtra("lead", 1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            val report = SelfTest()
            try {
                withTimeout(30_000) {
                    val client = McpBinderClient.bind(context, ComponentName(context, CalendarMcpService::class.java), scope)
                    val info = client.initialize("calendar-selftest", "1")
                    report.info("server", "${info.name}/${info.version}")
                    report.info("pid", "client=${Process.myPid()}")
                    val tools = client.listTools()
                    report.contract(tools.associate { it.name to it.annotations })
                    when (mode) {
                        "create" -> createOnly(client, report, startIn, lead)
                        else -> fullRun(client, report)
                    }
                    client.close()
                }
            } catch (e: Throwable) {
                report.fail("exception", "${e.javaClass.simpleName}: ${e.message}")
            }
            val summary = report.summary()
            Log.i(TAG, summary)
            pending.resultCode = if (report.ok) 1 else 2
            pending.resultData = summary
            pending.finish()
            scope.cancel()
        }
    }

    private suspend fun createOnly(c: McpBinderClient, r: SelfTest, startIn: Int, lead: Int) {
        val zone = ZoneId.systemDefault()
        val start = ZonedDateTime.now(zone).plusMinutes(startIn.toLong())
        val res = c.callTool(
            "event_create",
            buildJsonObject {
                put("title", "MCP 创建：${startIn} 分钟后开始")
                put("start", ISO.format(start))
                put("end", ISO.format(start.plusMinutes(30)))
                put("location", "经 MCP 写入")
                put("reminder_minutes", buildJsonArray { add(JsonPrimitive(lead)) })
            },
        )
        r.check("event_create(create-only)", !res.isError, res.text.take(120))
        r.info("created_id", res.structuredContent?.get("id")?.jsonPrimitive?.content ?: "?")
    }

    private suspend fun fullRun(c: McpBinderClient, r: SelfTest) {
        val zone = ZoneId.systemDefault()
        val tomorrow = LocalDate.now(zone).plusDays(1)
        val tag = (System.currentTimeMillis() % 100000).toString()
        suspend fun call(name: String, args: JsonObject = JsonObject(emptyMap())): McpToolResult = c.callTool(name, args)
        fun obj(vararg kv: Pair<String, Any?>) = buildJsonObject {
            for ((k, v) in kv) when (v) {
                null -> put(k, kotlinx.serialization.json.JsonNull)
                is Int -> put(k, v)
                is Boolean -> put(k, v)
                is List<*> -> put(k, buildJsonArray { v.forEach { add(JsonPrimitive(it as Int)) } })
                else -> put(k, v.toString())
            }
        }
        fun JsonObject?.s(k: String) = this?.get(k)?.jsonPrimitive?.content
        fun JsonObject?.arr(k: String): JsonArray = this?.get(k)?.jsonArray ?: JsonArray(emptyList())

        // calendar_*
        val list0 = call("calendar_list")
        val defaultId = list0.structuredContent.arr("calendars").map { it.jsonObject }.firstOrNull { it["is_default"]?.jsonPrimitive?.boolean == true }.s("id")
        r.check("calendar_list", !list0.isError && defaultId != null)
        val cal = call("calendar_create", obj("name" to "MCP自测$tag", "color" to "#6C5CE7"))
        val calId = cal.structuredContent.s("id")
        r.check("calendar_create", !cal.isError && calId != null && cal.structuredContent.s("color") == "#6C5CE7")
        r.check("calendar_create(duplicate)->error", call("calendar_create", obj("name" to "mcp自测$tag")).isError)
        r.check("calendar_create(no name)->error", call("calendar_create").isError)

        // event_create / get / list / search / update
        val ev = call(
            "event_create",
            obj(
                "title" to "MCP 自测会议$tag", "start" to ISO.format(tomorrow.atTime(10, 0).atZone(zone)), "end" to ISO.format(tomorrow.atTime(11, 0).atZone(zone)),
                "location" to "Room 1", "description" to "selftest", "calendar_id" to calId, "reminder_minutes" to listOf(10, 60),
                "recurrence" to "weekly", "recurrence_until" to tomorrow.plusDays(60).toString(),
            ),
        )
        val seriesId = ev.structuredContent.s("series_id")
        r.check("event_create", !ev.isError && seriesId != null && ev.structuredContent.s("recurrence") == "weekly")
        r.check("event_create(no title)->error", call("event_create", obj("start" to ISO.format(tomorrow.atTime(9, 0).atZone(zone)))).isError)
        r.check("event_create(bad start)->error", call("event_create", obj("title" to "x", "start" to "tomorrow 3pm")).isError)
        val get = call("event_get", obj("id" to (seriesId ?: "")))
        r.check("event_get", !get.isError && get.structuredContent.s("title") == "MCP 自测会议$tag")
        r.check("event_get(unknown id)->error", call("event_get", obj("id" to "nope")).isError)
        val listed = call("event_list", obj("from" to tomorrow.toString(), "to" to tomorrow.plusDays(20).toString(), "calendar_id" to calId))
        r.check("event_list(expands weekly)", !listed.isError && listed.structuredContent.arr("events").size == 3 && listed.structuredContent.arr("events").all { it.jsonObject.s("series_id") == seriesId })
        r.check("event_list(bad from)->error", call("event_list", obj("from" to "soon")).isError)
        val search = call("event_search", obj("query" to "自测会议$tag"))
        r.check("event_search", !search.isError && search.structuredContent.arr("events").size == 1)
        val upd = call("event_update", obj("id" to (seriesId ?: ""), "title" to "MCP 自测会议（改）$tag", "location" to "Room 2"))
        r.check("event_update", !upd.isError && upd.structuredContent.s("location") == "Room 2")
        r.check("event_update(nothing)->error", call("event_update", obj("id" to (seriesId ?: ""))).isError)
        r.check("event_update(unknown id)->error", call("event_update", obj("id" to "nope", "title" to "x")).isError)

        // agenda_today / free_slots
        r.check("agenda_today", !call("agenda_today").isError)
        val free = call("free_slots", obj("date" to tomorrow.toString(), "duration_minutes" to 60, "calendar_id" to calId))
        r.check("free_slots(excludes 10-11)", !free.isError && free.structuredContent.arr("busy").size == 1 && free.structuredContent.arr("slots").size == 2)
        r.check("free_slots(missing duration)->error", call("free_slots", obj("date" to tomorrow.toString())).isError)

        // 删除
        val delEv = call("event_delete", obj("id" to (seriesId ?: "")))
        r.check("event_delete", !delEv.isError && delEv.structuredContent?.get("deleted")?.jsonPrimitive?.boolean == true)
        r.check("event_delete(again)->error", call("event_delete", obj("id" to (seriesId ?: ""))).isError)
        r.check("calendar_update(hide)", call("calendar_update", obj("id" to (calId ?: ""), "visible" to false)).structuredContent?.get("visible")?.jsonPrimitive?.boolean == false)
        r.check("calendar_delete(default)->error", call("calendar_delete", obj("id" to (defaultId ?: ""))).isError)
        val delCal = call("calendar_delete", obj("id" to (calId ?: "")))
        r.check("calendar_delete", !delCal.isError && delCal.structuredContent?.get("deleted_events")?.jsonPrimitive?.int == 0)
        r.check("calendar_delete(again)->error", call("calendar_delete", obj("id" to (calId ?: ""))).isError)
    }

    private class SelfTest {
        private val checks = LinkedHashMap<String, Boolean>()
        private val notes = LinkedHashMap<String, String>()
        private val failures = ArrayList<String>()
        val ok: Boolean get() = failures.isEmpty() && checks.isNotEmpty() && checks.values.all { it }

        fun info(k: String, v: String) {
            notes[k] = v
        }

        fun check(name: String, cond: Boolean, detail: String = "") {
            checks[name] = cond
            if (!cond) failures += if (detail.isNotEmpty()) "$name ($detail)" else name
        }

        fun fail(name: String, detail: String) {
            checks[name] = false
            failures += "$name ($detail)"
        }

        /** tools/list 必须包含契约里的 11 个工具，且注解准确（docs/sample-apps.md 4.2）。 */
        fun contract(tools: Map<String, org.agentos.plugin.McpToolAnnotations>) {
            val required = listOf(
                "calendar_list", "calendar_create", "calendar_delete", "event_list", "event_get", "event_create",
                "event_update", "event_delete", "event_search", "agenda_today", "free_slots",
            )
            info("tools", tools.keys.sorted().joinToString(","))
            check("tools/list has all ${required.size} contract tools", required.all { it in tools }, "missing=" + required.filter { it !in tools })
            check(
                "annotations",
                listOf("calendar_list", "event_list", "event_get", "event_search", "agenda_today", "free_slots").all { tools[it]?.readOnlyHint == true } &&
                    listOf("calendar_delete", "event_delete").all { tools[it]?.destructiveHint == true } &&
                    tools["event_update"]?.idempotentHint == true,
            )
        }

        fun summary(): String = buildJsonObject {
            put("ok", ok)
            put("passed", checks.values.count { it })
            put("total", checks.size)
            put("failed", buildJsonArray { failures.forEach { add(JsonPrimitive(it)) } })
            for ((k, v) in notes) put(k, v)
        }.toString()
    }

    private companion object {
        const val TAG = "CalendarMcpSelfTest"
        val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")
    }
}
