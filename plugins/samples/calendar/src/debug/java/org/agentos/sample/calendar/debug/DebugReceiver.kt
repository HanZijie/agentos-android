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
 *   adb shell am broadcast -n org.agentos.sample.calendar/.debug.DebugReceiver -a x --es cmd seed [--es lang zh|en]
 *   ... --es cmd seed_system [--es lang zh|en]               (a few events in the default write calendar, e.g. the fake Google calendar of provider_setup)
 *   ... --es cmd remind_test --ei start_in 2 --ei lead 1     (local event starting in N minutes, reminder M minutes before)
 *   ... --es cmd dump [--ei offset N --ei limit M]           (read-only state dump, JSON in the broadcast result data)
 *   ... --es cmd reset                                       (delete what THIS APP created and cancel the reminder alarm)
 *   ... --es cmd clear                                       (older name of reset's data part; logs only)
 *   ... --es cmd set_default [--es id <calendar id>]         (choose the default write calendar; no id = automatic)
 *   ... --es cmd provider_setup [--es lang zh|en] | provider_teardown   (emulator only: fake account calendars in the system calendar database)
 *   ... --es cmd provider_probe                              (emulator only: the C1 experiments, see README)
 *   ... --es cmd provider_watch [--ei seconds N]             (emulator only: which change notifications arrive while something else writes)
 *
 * What reset / seed / clear touch: the app's own (local) calendars and events, and, in the system calendar database, only events
 * carrying this app's CUSTOM_APP_PACKAGE tag. They never delete other apps' or synced events, never delete a system calendar, and are
 * not compiled into release builds. seed / clear / remind_test report in logcat (tag CalendarDebug). dump and reset put one JSON
 * string in the broadcast result data (result code 1 = ok, 2 = error) and never log it. The receiver requires
 * android.permission.DUMP, so only the shell (adb) can send these.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "calendar-debug") {
            try {
                val repo = CalendarGraph.repository(context)
                when (val cmd = intent.getStringExtra("cmd")) {
                    "seed" -> seed(repo, intent.getStringExtra("lang") ?: "zh")
                    "seed_system" -> answer(pending, 1, seedSystem(repo, intent.getStringExtra("lang") ?: "zh"))
                    "clear" -> clear(repo)
                    "remind_test" -> remindTest(repo, intent.getIntExtra("start_in", 2), intent.getIntExtra("lead", 1))
                    "dump" -> answer(pending, 1, dump(context, repo, intent))
                    "reset" -> answer(pending, 1, reset(context, repo))
                    "set_default" -> answer(pending, 1, setDefault(repo, intent.getStringExtra("id")))
                    "provider_setup" -> answer(pending, 1, providerSetup(context, repo, intent.getStringExtra("lang") ?: "zh"))
                    "provider_teardown" -> answer(pending, 1, providerTeardown(context, repo))
                    "provider_probe" -> answer(pending, 1, ProviderProbe.run(context))
                    "provider_watch" -> answer(pending, 1, ProviderProbe.watch(context, intent.getIntExtra("seconds", 20)))
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
        val foreignBefore = repo.foreignEventCount()
        val cleared = clear(repo)
        repo.setDefaultWriteCalendar(null)
        // The reschedule after a data change is debounced (250 ms); do it now and wait for the result so the answer is final.
        val scheduler = CalendarGraph.scheduler(context)
        scheduler.reschedule()
        repo.reloadCalendars()
        return buildJsonObject {
            // events this app created: local ones plus the tagged ones in the system calendar database
            put("cleared", cleared.local + cleared.system)
            // the app's own calendars (the default one is always left; the driver expects exactly 1)
            put("calendars_remaining", repo.calendars.value.count { !it.system })
            put("remaining_scheduled", if (scheduler.isAlarmRegistered()) 1 else 0)
            put("system_events_cleared", cleared.system)
            put("system_calendars_untouched", repo.calendars.value.count { it.system })
            put("other_events_untouched", foreignBefore)
        }
    }

    /** Deletes what this app created (own events + tagged system events) and the app's extra calendars. Other apps' data is not touched. */
    private fun clear(repo: CalendarRepository): CalendarRepository.Cleared {
        val cleared = repo.clearOwnedEvents()
        repo.calendars.value.filter { !it.system && !it.isDefault }.forEach { repo.deleteCalendar(it.id) }
        Log.i(TAG, "cleared: ${cleared.local} local + ${cleared.system} tagged system events")
        return cleared
    }

    private fun setDefault(repo: CalendarRepository, id: String?): JsonObject {
        repo.reloadCalendars()
        repo.setDefaultWriteCalendar(id)
        return buildJsonObject { put("default_write_calendar", repo.defaultWriteCalendar().id) }
    }

    /** Emulator only: three fake account calendars (Google writable, CalDAV read-only, LOCAL type) in the system calendar database. */
    private fun providerSetup(context: Context, repo: CalendarRepository, lang: String): JsonObject {
        ProviderFixtures.deleteTestCalendars(context)
        val en = lang == "en"
        val g = ProviderFixtures.createCalendar(context, if (en) "AgentOS Test Google" else "AgentOS 测试 Google", "agentos-test-g@example.com", "com.google", 700, 0xFF4A7BDB.toInt())
        val c = ProviderFixtures.createCalendar(context, if (en) "AgentOS Test Read-only" else "AgentOS 测试 只读", "agentos-test-c@example.com", "bitfire.at.davdroid", 200, 0xFF5BA55B.toInt())
        val l = ProviderFixtures.createCalendar(context, if (en) "AgentOS Test Device" else "AgentOS 测试 本地", "agentos-test-local", "LOCAL", 700, 0xFFF2A33A.toInt())
        repo.reloadCalendars()
        repo.refresh()
        return buildJsonObject {
            put("google", "sys:$g")
            put("caldav_read_only", "sys:$c")
            put("local_type", "sys:$l")
        }
    }

    private fun providerTeardown(context: Context, repo: CalendarRepository): JsonObject {
        val n = ProviderFixtures.deleteTestCalendars(context)
        repo.setDefaultWriteCalendar(null)
        repo.reloadCalendars()
        repo.refresh()
        return buildJsonObject { put("calendars_removed", n) }
    }

    private fun remindTest(repo: CalendarRepository, startIn: Int, lead: Int) {
        val start = repo.time.nowMs() + startIn * 60_000L
        val e = repo.saveEvent(
            EventSeries(
                id = "", calendarId = repo.localDefaultCalendar.id, title = "提醒测试：${startIn} 分钟后开始", location = "会议室 A",
                startUtc = start, endUtc = start + 30 * 60_000L, zoneId = repo.zone.id, reminders = listOf(lead),
            ),
        )
        Log.i(TAG, "remind_test created id=${e.id} start=$start reminder=-${lead}min")
    }

    /** Demo data for screenshots (synthetic; Chinese or English titles). Local calendars and events only. */
    private fun seed(repo: CalendarRepository, lang: String) {
        fun t(zh: String, en: String) = if (lang == "en") en else zh
        clear(repo)
        val zone = repo.zone
        val today = LocalDate.now(zone)
        val personal = repo.localDefaultCalendar.id
        val work = repo.createCalendar(t("工作", "Work"), Palette.colors[4]).id
        val family = repo.createCalendar(t("家庭", "Family"), Palette.colors[2]).id
        val fit = repo.createCalendar(t("运动", "Fitness"), Palette.colors[1]).id

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

        timed(t("晨跑", "Morning run"), 0, "07:00", "07:45", fit, t("滨江绿道", "Riverside trail"), rec = Recurrence.DAILY, reminders = listOf(15))
        timed(t("产品评审会", "Product review"), 0, "10:00", "11:30", work, t("3 号会议室", "Room 3"), t("带上 Q4 路线图和竞品对比。", "Bring the Q4 roadmap and the competitor comparison."), reminders = listOf(10, 60))
        timed(t("午餐 · 老王", "Lunch with Sam"), 0, "12:30", "13:30", personal, t("楼下面馆", "Noodle bar downstairs"))
        timed(t("写周报", "Write the weekly report"), 0, "16:00", "17:00", work)
        timed(t("给爸妈打电话", "Call mom and dad"), 0, "20:30", "21:00", family, rec = Recurrence.WEEKLY)
        timed(t("团队站会", "Team stand-up"), 1, "10:00", "10:15", work, t("线上", "Online"), rec = Recurrence.DAILY, reminders = listOf(5))
        timed(t("牙医复诊", "Dentist follow-up"), 1, "15:00", "16:00", personal, t("瑞尔齿科 · 国贸店", "Downtown Dental"), t("记得带医保卡。", "Bring the insurance card."), reminders = listOf(30, 1440))
        timed(t("客户电话", "Client call"), 1, "11:00", "12:00", work, t("电话会议", "Conference call"))
        timed(t("设计走查", "Design walkthrough"), 1, "11:30", "12:30", work, "Figma")
        timed(t("瑜伽课", "Yoga class"), 2, "19:00", "20:15", fit, "FitLab", rec = Recurrence.WEEKLY)
        allDay(t("出差：上海", "Business trip: Shanghai"), 2, 2, work)
        allDay(t("小满生日", "Alex's birthday"), 3, 0, family, Recurrence.YEARLY, Palette.colors[6])
        timed(t("电影《沙丘》", "Movie night: Dune"), 3, "19:30", "22:00", personal, t("百老汇影城", "Cinema"))
        timed(t("家庭聚餐", "Family dinner"), 5, "18:00", "20:00", family, t("外婆家", "Grandma's"), reminders = listOf(60))
        timed(t("季度复盘", "Quarterly review"), 6, "14:00", "16:00", work, t("大会议室", "Main conference room"))
        allDay(t("房租到期", "Rent due"), 9, 0, personal, Recurrence.MONTHLY, Palette.colors[0])
        timed(t("项目复盘", "Project retro"), -2, "14:00", "15:00", work, t("线上", "Online"))
        timed(t("跨夜发布窗口", "Overnight release window"), 4, "22:00", "02:00", work, t("值班", "On call"), t("发布完成后在群里同步。", "Post an update in the channel when done."), endDay = 5)
        Log.i(TAG, "seeded: ${repo.calendars.value.size} calendars, ${repo.localEvents.value.size} events")
    }

    /** Demo events in the default write calendar (tagged with this app's package, so reset removes them again). */
    private fun seedSystem(repo: CalendarRepository, lang: String): JsonObject {
        fun t(zh: String, en: String) = if (lang == "en") en else zh
        repo.reloadCalendars()
        val cal = repo.defaultWriteCalendar()
        val zone = repo.zone
        val today = LocalDate.now(zone)
        fun at(day: Int, hm: String): Long = today.plusDays(day.toLong()).atTime(LocalTime.parse(hm)).atZone(zone).toInstant().toEpochMilli()
        fun ev(title: String, day: Int, from: String, to: String, location: String = "", rec: Recurrence = Recurrence.NONE, reminders: List<Int> = listOf(10)) = repo.saveEvent(
            EventSeries(id = "", calendarId = cal.id, title = title, location = location, startUtc = at(day, from), endUtc = at(day, to), zoneId = zone.id, recurrence = rec, reminders = reminders),
        )
        ev(t("需求评审会", "Requirements review"), 0, "15:00", "16:00", t("3 号会议室", "Room 3"), reminders = listOf(30))
        ev(t("周一例会", "Monday sync"), 1, "09:30", "10:00", t("线上", "Online"), Recurrence.WEEKLY, listOf(10))
        ev(t("客户拜访", "Customer visit"), 2, "13:30", "15:00", t("国贸", "Downtown"))
        ev(t("季度规划", "Quarterly planning"), 4, "10:00", "12:00", reminders = listOf(60))
        return buildJsonObject { put("calendar", cal.id); put("calendar_name", cal.name) }
    }

    private companion object {
        const val TAG = "CalendarDebug"
    }
}
