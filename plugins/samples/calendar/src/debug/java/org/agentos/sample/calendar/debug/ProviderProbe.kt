package org.agentos.sample.calendar.debug

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * C1 验证（debug 构建、仅模拟器）：在 **本 App 的 uid** 下对系统日历库做一组实验，把观察到的行为作为一段 JSON 返回，
 * 记录进 README 的“C1 验证”。没有授权时只报告“读库抛什么”，不做别的。
 *
 *   adb shell am broadcast -n org.agentos.sample.calendar/.debug.DebugReceiver -a x --es cmd provider_probe
 */
object ProviderProbe {
    private const val DAY = 86_400_000L

    fun run(context: Context): JsonObject = buildJsonObject {
        put("sdk", Build.VERSION.SDK_INT)
        put("emulator", ProviderFixtures.isEmulator())
        val granted = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        put("permission_granted", granted)
        put("raw_query_without_permission", if (granted) "skipped (permission granted)" else attempt { context.contentResolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID), null, null, null)?.use { "returned ${it.count} rows" } ?: "null cursor" })
        if (!granted || !ProviderFixtures.isEmulator()) return@buildJsonObject

        val r = context.contentResolver
        val pkg = context.packageName
        ProviderFixtures.deleteTestCalendars(context)
        val acct = "${ProviderFixtures.ACCOUNT_PREFIX}-probe"
        val cal = ProviderFixtures.createCalendar(context, "Probe", acct, CalendarContract.ACCOUNT_TYPE_LOCAL, 700)
        put("a_create_local_calendar_with_syncadapter_flag", "ok id=$cal")

        // b: 不带 CALLER_IS_SYNCADAPTER 建日历（普通 App 应该不行，或者只能建 LOCAL）
        put(
            "b_create_calendar_without_flag",
            attempt {
                val v = ContentValues().apply {
                    put(Calendars.ACCOUNT_NAME, "$acct-plain"); put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                    put(Calendars.NAME, "plain"); put(Calendars.CALENDAR_DISPLAY_NAME, "plain"); put(Calendars.CALENDAR_ACCESS_LEVEL, 700)
                }
                "inserted ${r.insert(Calendars.CONTENT_URI, v)}"
            },
        )

        fun event(title: String, extra: ContentValues.() -> Unit = {}): Long {
            val v = ContentValues().apply {
                put(Events.CALENDAR_ID, cal); put(Events.TITLE, title)
                put(Events.DTSTART, 1_791_770_400_000L); put(Events.DTEND, 1_791_774_000_000L); put(Events.EVENT_TIMEZONE, "Asia/Shanghai")
                extra()
            }
            return ContentUris.parseId(r.insert(Events.CONTENT_URI, v)!!)
        }
        fun tagOf(id: Long): String? = r.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), arrayOf(Events.CUSTOM_APP_PACKAGE), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "<gone>" }

        // c/d/e: CUSTOM_APP_PACKAGE 谁能写、写成什么
        val own = event("tagged") { put(Events.CUSTOM_APP_PACKAGE, pkg) }
        put("c_insert_tagged_readback", tagOf(own).toString())
        val spoof = attemptValue { event("spoofed") { put(Events.CUSTOM_APP_PACKAGE, "com.example.someone.else") } }
        put("d_insert_with_other_package_readback", spoof.first?.let { tagOf(it) } ?: spoof.second)
        val plain = event("untagged")
        put("e0_untagged_readback", tagOf(plain).toString())
        put(
            "e_update_tag_after_insert",
            attempt {
                val n = r.update(ContentUris.withAppendedId(Events.CONTENT_URI, plain), ContentValues().apply { put(Events.CUSTOM_APP_PACKAGE, pkg) }, null, null)
                "updated=$n tag=${tagOf(plain)}"
            },
        )
        // f: 按标记只删自己的
        val untouched = event("keep me")
        put(
            "f_delete_by_tag",
            attempt {
                val n = r.delete(Events.CONTENT_URI, "${Events.CUSTOM_APP_PACKAGE} = ?", arrayOf(pkg))
                "deleted=$n; untagged survivor=${tagOf(untouched)}; spoofed survivor=${spoof.first?.let { tagOf(it) }}"
            },
        )

        // g: ContentObserver 通知
        val latch = CountDownLatch(1)
        val ht = HandlerThread("probe-observer").also { it.start() }
        var t0 = 0L
        var latency = -1L
        val obs = object : ContentObserver(Handler(ht.looper)) {
            override fun onChange(selfChange: Boolean) {
                if (latency < 0) latency = System.currentTimeMillis() - t0
                latch.countDown()
            }
        }
        r.registerContentObserver(CalendarContract.CONTENT_URI, true, obs)
        t0 = System.currentTimeMillis()
        event("observer trigger")
        val got = latch.await(3, TimeUnit.SECONDS)
        r.unregisterContentObserver(obs)
        ht.quitSafely()
        put("g_content_observer_notified", if (got) "yes, after ${latency} ms" else "no notification within 3 s")

        // h: 全天重复 + UNTIL 日期 + DURATION P1D → Instances
        val begin = 1_791_763_200_000L // 2026-10-12T00:00Z
        val allDayId = event("allday weekly") {
            remove(Events.DTEND); put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "UTC"); put(Events.DTSTART, begin)
            put(Events.DURATION, "P1D"); put(Events.RRULE, "FREQ=WEEKLY;UNTIL=20261026")
        }
        val rows = ArrayList<String>()
        val uri = Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, begin - DAY); ContentUris.appendId(it, begin + 40 * DAY) }.build()
        r.query(uri, arrayOf(Instances.BEGIN, Instances.END), "${Instances.EVENT_ID} = $allDayId", null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) rows += "${(c.getLong(0) - begin) / DAY}d..${(c.getLong(1) - begin) / DAY}d"
        }
        put("h_allday_weekly_until_date_instances(day offsets from 2026-10-12 UTC)", rows.joinToString())

        // i: 负的提醒分钟数（全天日程“当天 09:00”）
        put(
            "i_reminder_minutes_minus540",
            attempt {
                r.insert(Reminders.CONTENT_URI, ContentValues().apply { put(Reminders.EVENT_ID, allDayId); put(Reminders.MINUTES, -540); put(Reminders.METHOD, Reminders.METHOD_ALERT) })
                r.query(Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES), "${Reminders.EVENT_ID} = $allDayId", null, null)?.use { c -> if (c.moveToFirst()) "stored ${c.getInt(0)}" else "no row" } ?: "null"
            },
        )

        // j: 重复日程带 DTEND（文档里的坑）：Provider 不报错，但 lastDate 会按 DTEND 算
        val trap = event("recurring with dtend") { put(Events.RRULE, "FREQ=DAILY") }
        put(
            "j_recurring_with_dtend_lastDate",
            r.query(ContentUris.withAppendedId(Events.CONTENT_URI, trap), arrayOf("lastDate", Events.DTEND, Events.DURATION), null, null, null)?.use { c ->
                if (c.moveToFirst()) "lastDate=${c.getLong(0)} dtend=${c.getLong(1)} duration=${c.getString(2)}" else "gone"
            },
        )

        put("z_cleanup_calendars_removed", ProviderFixtures.deleteTestCalendars(context))
    }

    private fun attempt(block: () -> String?): String = try {
        block() ?: "null"
    } catch (e: Exception) {
        "${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}"
    }

    private fun attemptValue(block: () -> Long): Pair<Long?, String> = try {
        block() to "ok"
    } catch (e: Exception) {
        null to "${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}"
    }
}
