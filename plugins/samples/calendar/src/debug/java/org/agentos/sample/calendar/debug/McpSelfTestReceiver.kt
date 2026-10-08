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
 * adb shell am broadcast -n org.agentos.sample.calendar/.debug.McpSelfTestReceiver                      # 全部工具（本机日历，不要求授权），结束后清理
 * adb shell am broadcast -n org.agentos.sample.calendar/.debug.McpSelfTestReceiver --es mode create --ei start_in 3 --ei lead 1
 *     # 只经 MCP 创建一个“N 分钟后开始、提前 M 分钟提醒”的日程并保留（看界面实时刷新与到点通知）
 * adb shell am broadcast -n org.agentos.sample.calendar/.debug.McpSelfTestReceiver --es mode system
 *     # 仅模拟器、需先授权：在系统日历库里造假账号日历（Google 可写 / CalDAV 只读 / LOCAL），把全部工具跑一遍（Instances 展开、RRULE、
 *     # 提醒、custom 重复拒改、只读拒写、账号日历不能建 / 删 / 改名），结束后清理
 * adb shell am broadcast -n org.agentos.sample.calendar/.debug.McpSelfTestReceiver --es mode denied
 *     # 没有日历权限时（先 pm revoke）：涉及系统日历的工具返回固定的权限错误而不抛异常，本机日历照常可用
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
                        "system" -> systemRun(context, client, report)
                        "denied" -> deniedRun(context, client, report)
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
        r.check("calendar_create(is local, app storage)", cal.structuredContent.s("source") == "local" && cal.structuredContent.s("storage") == "app" && cal.structuredContent?.get("writable")?.jsonPrimitive?.boolean == true)
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
        val search = call("event_search", obj("query" to "自测会议$tag", "calendar_id" to calId))
        r.check("event_search", !search.isError && search.structuredContent.arr("events").size == 1)
        val upd = call("event_update", obj("id" to (seriesId ?: ""), "title" to "MCP 自测会议（改）$tag", "location" to "Room 2"))
        r.check("event_update", !upd.isError && upd.structuredContent.s("location") == "Room 2")
        r.check("event_update(nothing)->error", call("event_update", obj("id" to (seriesId ?: ""))).isError)
        r.check("event_update(unknown id)->error", call("event_update", obj("id" to "nope", "title" to "x")).isError)

        // agenda_today / free_slots
        r.check("agenda_today(calendar)", !call("agenda_today", obj("calendar_id" to calId)).isError)
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


    private fun granted(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun jobj(vararg kv: Pair<String, Any?>) = buildJsonObject {
        for ((k, v) in kv) when (v) {
            null -> put(k, kotlinx.serialization.json.JsonNull)
            is Int -> put(k, v)
            is Boolean -> put(k, v)
            is List<*> -> put(k, buildJsonArray { v.forEach { add(JsonPrimitive(it as Int)) } })
            else -> put(k, v.toString())
        }
    }

    private fun JsonObject?.s(k: String): String? = this?.get(k)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
    private fun JsonObject?.arr(k: String): JsonArray = this?.get(k)?.jsonArray ?: JsonArray(emptyList())
    private fun JsonObject?.b(k: String): Boolean? = this?.get(k)?.jsonPrimitive?.boolean

    /** 没有日历权限：涉及系统日历的工具返回固定的权限错误（不抛异常），本机日历照常。 */
    private suspend fun deniedRun(context: Context, c: McpBinderClient, r: SelfTest) {
        val perm = "Calendar permission not granted; ask the user to grant it in the Calendar app"
        suspend fun call(name: String, args: JsonObject = JsonObject(emptyMap())) = c.callTool(name, args)
        r.check("precondition: permission is NOT granted (pm revoke first)", !granted(context))
        val zone = ZoneId.systemDefault()
        val tomorrow = LocalDate.now(zone).plusDays(1)
        val list = call("calendar_list")
        val cals = list.structuredContent.arr("calendars").map { it.jsonObject }
        r.check("calendar_list works and shows only the app's own calendars", !list.isError && cals.isNotEmpty() && cals.all { it.s("storage") == "app" })
        val localId = cals.first { it.b("is_default") == true }.s("id") ?: ""
        r.check("default write calendar falls back to the local one", localId.startsWith("local:"))
        for ((name, args) in listOf(
            "event_list" to JsonObject(emptyMap()),
            "event_list(sys calendar)" to jobj("calendar_id" to "sys:1"),
            "event_search" to jobj("query" to "x"),
            "agenda_today" to JsonObject(emptyMap()),
            "free_slots" to jobj("date" to tomorrow.toString(), "duration_minutes" to 30),
        )) {
            val tool = name.substringBefore('(')
            val res = call(tool, args)
            r.check("$name -> permission error (not an exception)", res.isError && res.text == perm, res.text.take(100))
        }
        val created = call("event_create", jobj("title" to "无权限时的本机日程", "start" to ISO.format(tomorrow.atTime(10, 0).atZone(zone))))
        r.check("event_create without calendar_id -> local default calendar", !created.isError && created.structuredContent.s("calendar_id") == localId)
        val toSys = call("event_create", jobj("title" to "x", "start" to ISO.format(tomorrow.atTime(10, 0).atZone(zone)), "calendar_id" to "sys:1"))
        r.check("event_create(sys calendar) -> permission error", toSys.isError && toSys.text == perm, toSys.text.take(100))
        val localList = call("event_list", jobj("calendar_id" to localId, "from" to tomorrow.toString(), "to" to tomorrow.plusDays(1).toString()))
        r.check("event_list(local calendar) works", !localList.isError && localList.structuredContent.arr("events").any { it.jsonObject.s("title") == "无权限时的本机日程" })
        r.check("event_search(local calendar) works", !call("event_search", jobj("query" to "无权限", "calendar_id" to localId)).isError)
        r.check("event_get(sys id) -> permission error", call("event_get", jobj("id" to "sys:1")).let { it.isError && it.text == perm })
        r.check("event_delete(sys id) -> permission error", call("event_delete", jobj("id" to "sys:1")).let { it.isError && it.text == perm })
        r.check("calendar_delete(sys id) -> permission error", call("calendar_delete", jobj("id" to "sys:1")).let { it.isError && it.text == perm })
        val id = created.structuredContent.s("series_id") ?: ""
        r.check("cleanup: event_delete(local)", !call("event_delete", jobj("id" to id)).isError)
    }

    private fun rawEvent(context: Context, rawId: Long): Map<String, String?> {
        val cols = arrayOf("title", "dtstart", "dtend", "duration", "rrule", "allDay", "eventTimezone", "customAppPackage", "calendar_id", "hasAlarm", "availability")
        context.contentResolver.query(android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, rawId), cols, null, null, null)?.use { cur ->
            if (cur.moveToFirst()) return cols.indices.associate { cols[it] to cur.getString(it) }
        }
        return emptyMap()
    }

    private fun rawReminders(context: Context, rawId: Long): List<Int> {
        val out = ArrayList<Int>()
        context.contentResolver.query(android.provider.CalendarContract.Reminders.CONTENT_URI, arrayOf("minutes"), "event_id = $rawId", null, "minutes ASC")?.use { c -> while (c.moveToNext()) out += c.getInt(0) }
        return out
    }

    private fun rawInsert(context: Context, calId: Long, title: String, extra: android.content.ContentValues.() -> Unit): Long {
        val v = android.content.ContentValues().apply {
            put("calendar_id", calId); put("title", title); put("eventTimezone", "Asia/Shanghai"); extra()
        }
        return android.content.ContentUris.parseId(context.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, v)!!)
    }

    /** 系统日历库里的假账号日历：全部工具走一遍（仅模拟器；需要已授权）。 */
    private suspend fun systemRun(context: Context, c: McpBinderClient, r: SelfTest) {
        suspend fun call(name: String, args: JsonObject = JsonObject(emptyMap())) = c.callTool(name, args)
        r.check("precondition: emulator and permission granted", ProviderFixtures.isEmulator() && granted(context))
        if (!ProviderFixtures.isEmulator() || !granted(context)) return
        val zone = ZoneId.systemDefault()
        val tomorrow = LocalDate.now(zone).plusDays(1)
        val pkg = context.packageName
        ProviderFixtures.deleteTestCalendars(context)
        val g = ProviderFixtures.createCalendar(context, "AgentOS 测试 Google", "agentos-test-g@example.com", "com.google", 700)
        val ro = ProviderFixtures.createCalendar(context, "AgentOS 测试 只读", "agentos-test-c@example.com", "bitfire.at.davdroid", 200)
        val lt = ProviderFixtures.createCalendar(context, "AgentOS 测试 本地", "agentos-test-local", "LOCAL", 700)
        try {
            val gid = "sys:$g"
            // ---- calendar_list: 账号 / 来源 / 可写 / 默认写入 ----
            val list = call("calendar_list")
            val rows = list.structuredContent.arr("calendars").map { it.jsonObject }
            fun row(id: String) = rows.firstOrNull { it.s("id") == id }
            r.check("calendar_list has account, account_type, source, writable, storage", !list.isError && rows.all { listOf("account", "account_type", "source", "writable", "storage").all { k -> it.containsKey(k) } })
            r.check("google calendar: source google, writable, storage system", row(gid).let { it.s("source") == "google" && it.b("writable") == true && it.s("storage") == "system" && it.s("account") == "agentos-test-g@example.com" })
            r.check("caldav calendar: source caldav, read-only", row("sys:$ro").let { it.s("source") == "caldav" && it.b("writable") == false })
            r.check("LOCAL-type system calendar: source local, storage system", row("sys:$lt").let { it.s("source") == "local" && it.s("storage") == "system" })
            r.check("default write calendar = first writable visible account calendar (the google one)", row(gid).b("is_default") == true && rows.count { it.b("is_default") == true } == 1)
            val localDefault = rows.first { it.s("storage") == "app" && it.s("id")!!.startsWith("local:") }.s("id") ?: ""

            // ---- calendar_create / update / delete 语义 ----
            val newCal = call("calendar_create", jobj("name" to "MCP自测本机$g"))
            r.check("calendar_create -> app-local calendar", !newCal.isError && newCal.structuredContent.s("storage") == "app" && newCal.structuredContent.s("source") == "local")
            r.check("calendar_create(name of an account calendar) -> error", call("calendar_create", jobj("name" to "AgentOS 测试 Google")).isError)
            r.check("calendar_delete(account calendar) -> refused", call("calendar_delete", jobj("id" to gid)).let { it.isError && it.text.contains("cannot be deleted") })
            r.check("calendar_delete(LOCAL-type system calendar) -> refused", call("calendar_delete", jobj("id" to "sys:$lt")).isError)
            r.check("calendar_update(account, rename) -> refused", call("calendar_update", jobj("id" to gid, "name" to "改名")).isError)
            r.check("calendar_update(account, hide) -> ok and visible=false", call("calendar_update", jobj("id" to gid, "visible" to false)).structuredContent.b("visible") == false)
            r.check("calendar_update(account, show) -> ok", call("calendar_update", jobj("id" to gid, "visible" to true)).structuredContent.b("visible") == true)
            call("calendar_delete", jobj("id" to (newCal.structuredContent.s("id") ?: "")))

            // ---- event_create 默认写入账号日历：重复 + 提醒 + 地点；RRULE / DURATION / 打标 / Reminders 表 ----
            val start = tomorrow.atTime(10, 0).atZone(zone)
            val weekly = call(
                "event_create",
                jobj(
                    "title" to "系统周会", "start" to ISO.format(start), "end" to ISO.format(start.plusHours(1)), "location" to "A1", "description" to "notes",
                    "reminder_minutes" to listOf(10, 60), "recurrence" to "weekly", "recurrence_until" to tomorrow.plusDays(20).toString(),
                ),
            )
            val wid = weekly.structuredContent.s("series_id") ?: ""
            r.check("event_create (no calendar_id) -> default write calendar (google)", !weekly.isError && weekly.structuredContent.s("calendar_id") == gid && weekly.structuredContent.s("recurrence") == "weekly")
            val wraw = wid.removePrefix("sys:").toLongOrNull() ?: -1
            val raw = rawEvent(context, wraw)
            r.check("provider row: RRULE FREQ=WEEKLY;UNTIL=…Z, DURATION P3600S, DTEND null", raw["rrule"].orEmpty().startsWith("FREQ=WEEKLY;UNTIL=") && raw["rrule"].orEmpty().endsWith("Z") && raw["duration"] == "P3600S" && raw["dtend"] == null, raw.toString())
            r.check("provider row: tagged with CUSTOM_APP_PACKAGE = this package, timezone kept", raw["customAppPackage"] == pkg && raw["eventTimezone"] == zone.id, raw.toString())
            r.check("provider Reminders rows = [10, 60]", rawReminders(context, wraw) == listOf(10, 60), rawReminders(context, wraw).toString())
            val got = call("event_get", jobj("id" to wid))
            r.check("event_get reads reminders, location, recurrence back", got.structuredContent.s("location") == "A1" && got.structuredContent.arr("reminder_minutes").map { it.jsonPrimitive.int } == listOf(10, 60))
            // Instances 展开：start 之后 20 天内每周一次
            val ls = call("event_list", jobj("from" to tomorrow.toString(), "to" to tomorrow.plusDays(21).toString(), "calendar_id" to gid))
            val occ = ls.structuredContent.arr("events").map { it.jsonObject }
            r.check("event_list expands the weekly series through Instances (3 occurrences, ids series@key)", !ls.isError && occ.size == 3 && occ.all { it.s("series_id") == wid && it.s("id")!!.contains('@') }, occ.size.toString())
            r.check("occurrence times: same clock time each week", occ.map { it.s("start")!!.substring(11, 19) }.toSet().size == 1)
            val occ2 = occ[1].s("id") ?: ""
            r.check("event_get(occurrence id) finds the 2nd occurrence", call("event_get", jobj("id" to occ2)).structuredContent.s("start") == occ[1].s("start"))

            // ---- 全天、跨天 ----
            val dayStart = tomorrow.plusDays(2)
            val allDay = call("event_create", jobj("title" to "系统出差", "start" to dayStart.toString(), "end" to dayStart.plusDays(1).toString(), "all_day" to true, "calendar_id" to gid, "reminder_minutes" to listOf(0, 1440)))
            val aid = allDay.structuredContent.s("series_id") ?: ""
            val araw = rawEvent(context, aid.removePrefix("sys:").toLongOrNull() ?: -1)
            r.check("all-day provider row: UTC midnight start, tz UTC, DTEND = day after last (exclusive)", !allDay.isError && araw["allDay"] == "1" && araw["eventTimezone"] == "UTC" && araw["dtstart"]!!.toLong() % 86_400_000L == 0L && araw["dtend"]!!.toLong() - araw["dtstart"]!!.toLong() == 2 * 86_400_000L, araw.toString())
            r.check("all-day reminders written relative to 00:00 (-540, 900) and read back as [0, 1440]", rawReminders(context, aid.removePrefix("sys:").toLong()) == listOf(-540, 900) && allDay.structuredContent.arr("reminder_minutes").map { it.jsonPrimitive.int } == listOf(0, 1440), rawReminders(context, aid.removePrefix("sys:").toLong()).toString())
            val adl = call("event_list", jobj("from" to dayStart.toString(), "to" to dayStart.plusDays(2).toString(), "calendar_id" to gid)).structuredContent.arr("events").map { it.jsonObject }
            r.check("all-day event_list: start 00:00 first day, end 23:59:59 last day", adl.any { it.s("series_id") == aid && it.s("start")!!.contains("T00:00:00") && it.s("end")!!.contains("T23:59:59") && it.s("end")!!.startsWith(dayStart.plusDays(1).toString()) })

            // ---- event_update：改标题 / 时间 / 提醒 / 重复变不重复 ----
            val upd = call("event_update", jobj("id" to wid, "title" to "系统周会（改）", "location" to "B2", "reminder_minutes" to listOf(30)))
            r.check("event_update title/location/reminders", !upd.isError && upd.structuredContent.s("location") == "B2" && rawReminders(context, wraw) == listOf(30))
            val none = call("event_update", jobj("id" to wid, "recurrence" to "none"))
            val raw2 = rawEvent(context, wraw)
            r.check("event_update recurrence=none: RRULE and DURATION cleared, DTEND set", !none.isError && raw2["rrule"] == null && raw2["duration"] == null && raw2["dtend"] != null, raw2.toString())
            val again = call("event_update", jobj("id" to wid, "recurrence" to "daily", "recurrence_until" to tomorrow.plusDays(4).toString()))
            val raw3 = rawEvent(context, wraw)
            r.check("event_update recurrence=daily: RRULE FREQ=DAILY;UNTIL, DURATION set, DTEND cleared", !again.isError && raw3["rrule"].orEmpty().startsWith("FREQ=DAILY;UNTIL=") && raw3["duration"] == "P3600S" && raw3["dtend"] == null, raw3.toString())
            val movedStart = tomorrow.atTime(15, 0).atZone(zone)
            val moved = call("event_update", jobj("id" to wid, "start" to ISO.format(movedStart), "recurrence" to "none"))
            r.check("event_update start (keeps 1h duration)", moved.structuredContent.s("start") == ISO.format(movedStart) && moved.structuredContent.s("end") == ISO.format(movedStart.plusHours(1)))
            r.check("event_update(move to another calendar) -> refused for account calendars", call("event_update", jobj("id" to wid, "calendar_id" to "sys:$lt")).isError)

            // ---- custom 重复规则：读到原文，拒绝改动 ----
            val customRaw = rawInsert(context, g, "自定义重复") {
                put("dtstart", java.time.LocalDate.now(zone).plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()); put("duration", "P1800S"); put("rrule", "FREQ=MONTHLY;BYDAY=2TU")
            }
            val cget = call("event_get", jobj("id" to "sys:$customRaw"))
            r.check("custom RRULE is reported as recurrence=custom with the original rrule", cget.structuredContent.s("recurrence") == "custom" && cget.structuredContent.s("rrule") == "FREQ=MONTHLY;BYDAY=2TU", cget.text.take(160))
            val cupd = call("event_update", jobj("id" to "sys:$customRaw", "title" to "改不了"))
            r.check("event_update(custom series) -> refused with a clear error, nothing changed", cupd.isError && cupd.text.contains("custom") && rawEvent(context, customRaw)["title"] == "自定义重复", cupd.text.take(160))
            val intervalRaw = rawInsert(context, g, "每两周") { put("dtstart", java.time.LocalDate.now(zone).plusDays(1).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()); put("duration", "P1800S"); put("rrule", "FREQ=WEEKLY;INTERVAL=2") }
            r.check("INTERVAL=2 / COUNT / multi-BYDAY are custom too", call("event_get", jobj("id" to "sys:$intervalRaw")).structuredContent.s("recurrence") == "custom")
            val googleStyle = rawInsert(context, g, "Google 风格每周") {
                val d = java.time.LocalDate.now(zone).plusDays(1)
                put("dtstart", d.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()); put("duration", "P1800S")
                put("rrule", "FREQ=WEEKLY;WKST=SU;BYDAY=" + listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")[d.dayOfWeek.value - 1])
            }
            r.check("FREQ=WEEKLY;WKST;BYDAY=<start weekday> maps to plain weekly", call("event_get", jobj("id" to "sys:$googleStyle")).structuredContent.s("recurrence") == "weekly")
            r.check("event_delete(custom series) is allowed (whole series)", !call("event_delete", jobj("id" to "sys:$customRaw")).isError && rawEvent(context, customRaw).isEmpty())

            // ---- 只读日历 ----
            val roEvent = rawInsert(context, ro, "只读里的日程") { put("dtstart", java.time.LocalDate.now(zone).plusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()); put("dtend", java.time.LocalDate.now(zone).plusDays(1).atTime(13, 0).atZone(zone).toInstant().toEpochMilli()) }
            r.check("event_list reads read-only calendars", call("event_list", jobj("calendar_id" to "sys:$ro", "from" to tomorrow.toString(), "to" to tomorrow.plusDays(1).toString())).structuredContent.arr("events").size == 1)
            r.check("event_create(read-only calendar) -> error", call("event_create", jobj("title" to "x", "start" to ISO.format(start), "calendar_id" to "sys:$ro")).let { it.isError && it.text.contains("read-only") })
            r.check("event_update(read-only event) -> error", call("event_update", jobj("id" to "sys:$roEvent", "title" to "x")).let { it.isError && it.text.contains("read-only") })
            r.check("event_delete(read-only event) -> error, still there", call("event_delete", jobj("id" to "sys:$roEvent")).isError && rawEvent(context, roEvent).isNotEmpty())

            // ---- 搜索 / 议程 / 空闲 ----
            val srch = call("event_search", jobj("query" to "系统周会"))
            r.check("event_search finds system events across calendars", !srch.isError && srch.structuredContent.arr("events").any { it.jsonObject.s("series_id") == wid })
            r.check("event_search(calendar_id) narrows", call("event_search", jobj("query" to "系统周会", "calendar_id" to "sys:$ro")).structuredContent.arr("events").isEmpty())
            val todayStart = LocalDate.now(zone).atTime(23, 0).atZone(zone)
            call("event_create", jobj("title" to "系统今日", "start" to ISO.format(todayStart), "end" to ISO.format(todayStart.plusMinutes(30)), "calendar_id" to gid))
            r.check("agenda_today includes system events", call("agenda_today").structuredContent.arr("events").any { it.jsonObject.s("title") == "系统今日" })
            val busyDay = tomorrow.plusDays(3)
            val busy = call("event_create", jobj("title" to "系统忙", "start" to ISO.format(busyDay.atTime(10, 0).atZone(zone)), "end" to ISO.format(busyDay.atTime(11, 0).atZone(zone)), "calendar_id" to gid))
            rawInsert(context, g, "显示为有空") {
                put("dtstart", busyDay.atTime(13, 0).atZone(zone).toInstant().toEpochMilli()); put("dtend", busyDay.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()); put("availability", 1)
            }
            val free = call("free_slots", jobj("date" to busyDay.toString(), "duration_minutes" to 60, "calendar_id" to gid))
            r.check("free_slots counts system events as busy but ignores 'free' availability", !busy.isError && free.structuredContent.arr("busy").size == 1 && free.structuredContent.arr("slots").size == 2, free.text.take(200))

            // ---- 删除（高风险：账号日历里会同步到云端） ----
            val del = call("event_delete", jobj("id" to wid))
            r.check("event_delete(account event) -> gone from the provider", !del.isError && rawEvent(context, wraw).isEmpty())
            r.check("event_delete(again) -> error", call("event_delete", jobj("id" to wid)).isError)
        } finally {
            r.info("fixtures_removed", ProviderFixtures.deleteTestCalendars(context).toString())
            r.info("app_owned_left", context.contentResolver.query(android.provider.CalendarContract.Events.CONTENT_URI, arrayOf("_id"), "customAppPackage = ?", arrayOf(pkg), null)?.use { it.count }.toString())
        }
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
