package org.agentos.sample.calendar.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.sample.calendar.CalendarGraph
import org.agentos.sample.calendar.data.CalendarRepository
import org.agentos.sample.calendar.data.EventSeries
import org.agentos.sample.calendar.data.Palette
import org.agentos.sample.calendar.data.Recurrence
import java.time.LocalDate
import java.time.LocalTime
import kotlin.concurrent.thread

/**
 * Debug builds only. Every command is an adb broadcast to this receiver:
 *
 *   adb shell am broadcast -n org.agentos.sample.calendar/.debug.DebugReceiver -a x --es cmd seed
 *   ... --es cmd remind_test --ei start_in 2 --ei lead 1     (event starting in N minutes, reminder M minutes before)
 *   ... --es cmd dump [--ei offset N --ei limit M]           (read-only state dump, JSON in the broadcast result data)
 *   ... --es cmd reset                                       (delete everything and cancel the reminder alarm)
 *   ... --es cmd clear                                       (older name of reset's data part; logs only)
 *
 * seed / clear / remind_test report in logcat (tag CalendarDebug). dump and reset put one JSON string in the broadcast
 * result data (result code 1 = ok, 2 = error) and never log it. The receiver requires android.permission.DUMP, so only
 * the shell (adb) can send these.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "calendar-debug") {
            try {
                val repo = CalendarGraph.repository(context)
                when (val cmd = intent.getStringExtra("cmd")) {
                    "seed" -> seed(repo)
                    "clear" -> clear(repo)
                    "remind_test" -> remindTest(repo, intent.getIntExtra("start_in", 2), intent.getIntExtra("lead", 1))
                    "dump" -> answer(pending, 1, dump(context, repo, intent))
                    "reset" -> answer(pending, 1, reset(context, repo))
                    else -> Log.w(TAG, "unknown cmd: $cmd")
                }
            } catch (e: Exception) {
                Log.e(TAG, "debug command failed", e)
                answer(pending, 2, error(e.message ?: e.javaClass.simpleName))
            } finally {
                pending.finish()
            }
        }
    }

    private fun answer(pending: PendingResult, code: Int, json: JsonObject) {
        pending.resultCode = code
        pending.resultData = json.toString()
    }

    private fun error(message: String): JsonObject = buildJsonObject { put("error", message) }

    private fun dump(context: Context, repo: CalendarRepository, intent: Intent): JsonObject {
        val offset = intent.getIntExtra("offset", 0).coerceAtLeast(0)
        val limit = intent.getIntExtra("limit", DebugDump.DEFAULT_LIMIT).coerceIn(1, DebugDump.MAX_LIMIT)
        val scheduler = CalendarGraph.scheduler(context)
        val armed = scheduler.armed()?.let { (at, id, lead) -> ArmedReminder(at, id, lead, scheduler.isAlarmRegistered()) }
        return DebugDump.build(repo, CalendarGraph.tools(context), armed, offset, limit)
    }

    private fun reset(context: Context, repo: CalendarRepository): JsonObject {
        val cleared = repo.events.value.size
        clear(repo)
        // The reschedule after a data change is debounced (250 ms); do it now and wait for the result so the answer is final.
        val scheduler = CalendarGraph.scheduler(context)
        scheduler.reschedule()
        return buildJsonObject {
            put("cleared", cleared)
            put("calendars_remaining", repo.calendars.value.size)
            put("remaining_scheduled", if (scheduler.isAlarmRegistered()) 1 else 0)
        }
    }

    private fun clear(repo: CalendarRepository) {
        repo.events.value.forEach { repo.deleteEvent(it.id) }
        repo.calendars.value.filter { !it.isDefault }.forEach { repo.deleteCalendar(it.id) }
        Log.i(TAG, "cleared")
    }

    private fun remindTest(repo: CalendarRepository, startIn: Int, lead: Int) {
        val start = repo.time.nowMs() + startIn * 60_000L
        val e = repo.saveEvent(
            EventSeries(
                id = "", calendarId = repo.defaultCalendar.id, title = "提醒测试：${startIn} 分钟后开始", location = "会议室 A",
                startUtc = start, endUtc = start + 30 * 60_000L, zoneId = repo.zone.id, reminders = listOf(lead),
            ),
        )
        Log.i(TAG, "remind_test created id=${e.id} start=$start reminder=-${lead}min")
    }

    private fun seed(repo: CalendarRepository) {
        clear(repo)
        val zone = repo.zone
        val today = LocalDate.now(zone)
        val personal = repo.defaultCalendar.id
        val work = repo.createCalendar("工作", Palette.colors[4]).id
        val family = repo.createCalendar("家庭", Palette.colors[2]).id
        val fit = repo.createCalendar("运动", Palette.colors[1]).id

        fun at(day: Int, hm: String): Long = today.plusDays(day.toLong()).atTime(LocalTime.parse(hm)).atZone(zone).toInstant().toEpochMilli()
        fun timed(
            title: String, day: Int, from: String, to: String, cal: String, location: String = "", notes: String = "",
            rec: Recurrence = Recurrence.NONE, color: Int? = null, reminders: List<Int> = listOf(10), endDay: Int = day,
        ) = repo.saveEvent(
            EventSeries(
                id = "", calendarId = cal, title = title, location = location, description = notes,
                startUtc = at(day, from), endUtc = at(endDay, to), zoneId = zone.id, recurrence = rec, color = color, reminders = reminders,
            ),
        )
        fun allDay(title: String, day: Int, span: Int, cal: String, rec: Recurrence = Recurrence.NONE, color: Int? = null) = repo.saveEvent(
            EventSeries(
                id = "", calendarId = cal, title = title, allDay = true, startUtc = 0, endUtc = 0, zoneId = zone.id,
                startDay = today.plusDays(day.toLong()).toEpochDay(), endDay = today.plusDays((day + span).toLong()).toEpochDay(),
                recurrence = rec, color = color, reminders = listOf(0),
            ),
        )

        timed("晨跑", 0, "07:00", "07:45", fit, "滨江绿道", rec = Recurrence.DAILY, reminders = listOf(15))
        timed("产品评审会", 0, "10:00", "11:30", work, "3 号会议室", "带上 Q4 路线图和竞品对比。", reminders = listOf(10, 60))
        timed("午餐 · 老王", 0, "12:30", "13:30", personal, "楼下面馆")
        timed("写周报", 0, "16:00", "17:00", work)
        timed("给爸妈打电话", 0, "20:30", "21:00", family, rec = Recurrence.WEEKLY)
        timed("团队站会", 1, "10:00", "10:15", work, "线上", rec = Recurrence.DAILY, reminders = listOf(5))
        timed("牙医复诊", 1, "15:00", "16:00", personal, "瑞尔齿科 · 国贸店", "记得带医保卡。", reminders = listOf(30, 1440))
        timed("客户电话", 1, "11:00", "12:00", work, "电话会议")
        timed("设计走查", 1, "11:30", "12:30", work, "Figma")
        timed("瑜伽课", 2, "19:00", "20:15", fit, "FitLab", rec = Recurrence.WEEKLY)
        allDay("出差：上海", 2, 2, work)
        allDay("小满生日", 3, 0, family, Recurrence.YEARLY, Palette.colors[6])
        timed("电影《沙丘》", 3, "19:30", "22:00", personal, "百老汇影城")
        timed("家庭聚餐", 5, "18:00", "20:00", family, "外婆家", reminders = listOf(60))
        timed("季度复盘", 6, "14:00", "16:00", work, "大会议室")
        allDay("房租到期", 9, 0, personal, Recurrence.MONTHLY, Palette.colors[0])
        timed("项目复盘", -2, "14:00", "15:00", work, "线上")
        timed("跨夜发布窗口", 4, "22:00", "02:00", work, "值班", "发布完成后在群里同步。", endDay = 5)
        Log.i(TAG, "seeded: ${repo.calendars.value.size} calendars, ${repo.events.value.size} events")
    }

    private companion object {
        const val TAG = "CalendarDebug"
    }
}
