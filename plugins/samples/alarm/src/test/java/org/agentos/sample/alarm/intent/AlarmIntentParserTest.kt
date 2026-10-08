package org.agentos.sample.alarm.intent

import android.provider.AlarmClock
import java.time.DayOfWeek
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent 参数 → 请求的映射（含 EXTRA_DAYS 星期常量转换、缺参、非法值）。extras 用 Map 代替 Bundle。 */
class AlarmIntentParserTest {
    private fun set(vararg extras: Pair<String, Any?>) = AlarmIntentParser.parse(AlarmClock.ACTION_SET_ALARM, mapOf(*extras))

    private fun rejected(request: AlarmIntentRequest): RejectReason {
        assertTrue("expected Rejected but was $request", request is AlarmIntentRequest.Rejected)
        return (request as AlarmIntentRequest.Rejected).reason
    }

    // ---- SET_ALARM：正常 ----

    @Test
    fun hourAndMinutesMapToAnAlarmWithOfficialDefaults() {
        val r = set(AlarmClock.EXTRA_HOUR to 7, AlarmClock.EXTRA_MINUTES to 30)
        // 官方默认：EXTRA_VIBRATE = true，EXTRA_SKIP_UI = false，不重复，无标签
        assertEquals(AlarmIntentRequest.SetAlarm(7, 30, "", emptySet(), vibrate = true, skipUi = false), r)
    }

    @Test
    fun minutesDefaultToZero() {
        assertEquals(AlarmIntentRequest.SetAlarm(23, 0, "", emptySet(), true, false), set(AlarmClock.EXTRA_HOUR to 23))
        assertEquals(AlarmIntentRequest.SetAlarm(0, 0, "", emptySet(), true, false), set(AlarmClock.EXTRA_HOUR to 0))
    }

    @Test
    fun messageVibrateAndSkipUiAreCarriedOver() {
        val r = set(
            AlarmClock.EXTRA_HOUR to 6, AlarmClock.EXTRA_MINUTES to 45,
            AlarmClock.EXTRA_MESSAGE to "  Run ", AlarmClock.EXTRA_VIBRATE to false, AlarmClock.EXTRA_SKIP_UI to true,
        )
        assertEquals(AlarmIntentRequest.SetAlarm(6, 45, "Run", emptySet(), vibrate = false, skipUi = true), r)
    }

    @Test
    fun everyCalendarDayConstantMapsToTheRightDayOfWeek() {
        val expected = mapOf(
            Calendar.SUNDAY to DayOfWeek.SUNDAY,
            Calendar.MONDAY to DayOfWeek.MONDAY,
            Calendar.TUESDAY to DayOfWeek.TUESDAY,
            Calendar.WEDNESDAY to DayOfWeek.WEDNESDAY,
            Calendar.THURSDAY to DayOfWeek.THURSDAY,
            Calendar.FRIDAY to DayOfWeek.FRIDAY,
            Calendar.SATURDAY to DayOfWeek.SATURDAY,
        )
        for ((calendarDay, dayOfWeek) in expected) {
            assertEquals(dayOfWeek, AlarmIntentParser.fromCalendarDay(calendarDay))
            val r = set(AlarmClock.EXTRA_HOUR to 8, AlarmClock.EXTRA_DAYS to arrayListOf(calendarDay)) as AlarmIntentRequest.SetAlarm
            assertEquals(setOf(dayOfWeek), r.days)
        }
    }

    @Test
    fun daysAcceptTheOfficialListAndIntArrayAndDeduplicate() {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val official = arrayListOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        assertEquals(weekdays, (set(AlarmClock.EXTRA_HOUR to 7, AlarmClock.EXTRA_DAYS to official) as AlarmIntentRequest.SetAlarm).days)
        // adb 的 --eia 给的是 int[]
        assertEquals(weekdays, (set(AlarmClock.EXTRA_HOUR to 7, AlarmClock.EXTRA_DAYS to intArrayOf(2, 3, 4, 5, 6)) as AlarmIntentRequest.SetAlarm).days)
        assertEquals(setOf(DayOfWeek.SUNDAY), (set(AlarmClock.EXTRA_HOUR to 7, AlarmClock.EXTRA_DAYS to arrayListOf(1, 1)) as AlarmIntentRequest.SetAlarm).days)
        // 空列表 = 只响一次
        assertEquals(emptySet<DayOfWeek>(), (set(AlarmClock.EXTRA_HOUR to 7, AlarmClock.EXTRA_DAYS to arrayListOf<Int>()) as AlarmIntentRequest.SetAlarm).days)
    }

    // ---- SET_ALARM：缺参 ----

    @Test
    fun noTimeAtAllOpensTheEditorInsteadOfCreatingAnything() {
        // 官方：没有时间参数时应打开能设闹钟的界面（EXTRA_SKIP_UI 被忽略）
        assertEquals(AlarmIntentRequest.OpenEditor, set())
        assertEquals(AlarmIntentRequest.OpenEditor, set(AlarmClock.EXTRA_SKIP_UI to true, AlarmClock.EXTRA_MESSAGE to "x"))
    }

    @Test
    fun minutesWithoutHourIsRejected() {
        assertEquals(RejectReason.MISSING_HOUR, rejected(set(AlarmClock.EXTRA_MINUTES to 30)))
    }

    // ---- SET_ALARM：非法值 ----

    @Test
    fun hourOutOfRangeOrWrongTypeIsRejected() {
        for (bad in listOf<Any>(-1, 24, 99, "7", 7L, 7.0, true)) {
            assertEquals("hour=$bad", RejectReason.INVALID_HOUR, rejected(set(AlarmClock.EXTRA_HOUR to bad)))
        }
    }

    @Test
    fun minutesOutOfRangeOrWrongTypeIsRejected() {
        for (bad in listOf<Any>(-1, 60, 100, "30", 30L)) {
            assertEquals("minutes=$bad", RejectReason.INVALID_MINUTES, rejected(set(AlarmClock.EXTRA_HOUR to 7, AlarmClock.EXTRA_MINUTES to bad)))
        }
    }

    @Test
    fun weekdayOutsideOneToSevenIsRejectedAndNothingIsHalfApplied() {
        for (bad in listOf<Any>(arrayListOf(0), arrayListOf(8), arrayListOf(2, 8), arrayListOf(-1), intArrayOf(9))) {
            assertEquals("days=$bad", RejectReason.INVALID_DAYS, rejected(set(AlarmClock.EXTRA_HOUR to 7, AlarmClock.EXTRA_DAYS to bad)))
        }
        // 元素不是整数、整个值不是列表
        assertEquals(RejectReason.INVALID_DAYS, rejected(set(AlarmClock.EXTRA_HOUR to 7, AlarmClock.EXTRA_DAYS to arrayListOf("mon"))))
        assertEquals(RejectReason.INVALID_DAYS, rejected(set(AlarmClock.EXTRA_HOUR to 7, AlarmClock.EXTRA_DAYS to 2)))
        assertEquals(RejectReason.INVALID_DAYS, rejected(set(AlarmClock.EXTRA_HOUR to 7, AlarmClock.EXTRA_DAYS to "2,3")))
    }

    @Test
    fun labelTooLongOrWrongTypeIsRejected() {
        assertEquals(RejectReason.LABEL_TOO_LONG, rejected(set(AlarmClock.EXTRA_HOUR to 7, AlarmClock.EXTRA_MESSAGE to "x".repeat(61))))
        assertEquals(RejectReason.INVALID_PARAM, rejected(set(AlarmClock.EXTRA_HOUR to 7, AlarmClock.EXTRA_MESSAGE to 5)))
        // 恰好 60 个字符可以
        assertTrue(set(AlarmClock.EXTRA_HOUR to 7, AlarmClock.EXTRA_MESSAGE to "x".repeat(60)) is AlarmIntentRequest.SetAlarm)
    }

    @Test
    fun booleanExtrasWithTheWrongTypeAreRejected() {
        assertEquals(RejectReason.INVALID_PARAM, rejected(set(AlarmClock.EXTRA_HOUR to 7, AlarmClock.EXTRA_VIBRATE to "true")))
        assertEquals(RejectReason.INVALID_PARAM, rejected(set(AlarmClock.EXTRA_HOUR to 7, AlarmClock.EXTRA_SKIP_UI to 1)))
    }

    @Test
    fun ringtoneIsIgnoredNotRejected() {
        // 不支持自定义 / 静音铃声：忽略，用默认铃声（README 的已知行为）
        val r = set(AlarmClock.EXTRA_HOUR to 7, AlarmClock.EXTRA_RINGTONE to AlarmClock.VALUE_RINGTONE_SILENT)
        assertTrue(r is AlarmIntentRequest.SetAlarm)
    }

    @Test
    fun unknownActionIsRejected() {
        assertEquals(RejectReason.UNSUPPORTED_ACTION, rejected(AlarmIntentParser.parse(AlarmClock.ACTION_SET_TIMER, emptyMap())))
        assertEquals(RejectReason.UNSUPPORTED_ACTION, rejected(AlarmIntentParser.parse(null, emptyMap())))
    }

    // ---- DISMISS_ALARM ----

    private fun dismiss(vararg extras: Pair<String, Any?>) = AlarmIntentParser.parse(AlarmClock.ACTION_DISMISS_ALARM, mapOf(*extras))

    @Test
    fun dismissSearchModesMapToTargets() {
        assertEquals(AlarmIntentRequest.DismissAlarm(DismissTarget.Unspecified), dismiss())
        assertEquals(AlarmIntentRequest.DismissAlarm(DismissTarget.Next), dismiss(AlarmClock.EXTRA_ALARM_SEARCH_MODE to AlarmClock.ALARM_SEARCH_MODE_NEXT))
        assertEquals(AlarmIntentRequest.DismissAlarm(DismissTarget.All), dismiss(AlarmClock.EXTRA_ALARM_SEARCH_MODE to AlarmClock.ALARM_SEARCH_MODE_ALL))
        assertEquals(
            AlarmIntentRequest.DismissAlarm(DismissTarget.Label("Run")),
            dismiss(AlarmClock.EXTRA_ALARM_SEARCH_MODE to AlarmClock.ALARM_SEARCH_MODE_LABEL, AlarmClock.EXTRA_MESSAGE to " Run "),
        )
        assertEquals(
            AlarmIntentRequest.DismissAlarm(DismissTarget.Time(7, 30, true)),
            dismiss(
                AlarmClock.EXTRA_ALARM_SEARCH_MODE to AlarmClock.ALARM_SEARCH_MODE_TIME,
                AlarmClock.EXTRA_HOUR to 7, AlarmClock.EXTRA_MINUTES to 30, AlarmClock.EXTRA_IS_PM to true,
            ),
        )
    }

    @Test
    fun dismissBadSearchesAreRejected() {
        assertEquals(RejectReason.INVALID_PARAM, rejected(dismiss(AlarmClock.EXTRA_ALARM_SEARCH_MODE to "android.bogus")))
        assertEquals(RejectReason.INVALID_PARAM, rejected(dismiss(AlarmClock.EXTRA_ALARM_SEARCH_MODE to 3)))
        // 标签搜索必须带标签
        assertEquals(RejectReason.MISSING_LABEL, rejected(dismiss(AlarmClock.EXTRA_ALARM_SEARCH_MODE to AlarmClock.ALARM_SEARCH_MODE_LABEL)))
        // 时间搜索：小时 / 分钟 / 上下午至少一个；各自范围要对
        val time = AlarmClock.EXTRA_ALARM_SEARCH_MODE to AlarmClock.ALARM_SEARCH_MODE_TIME
        assertEquals(RejectReason.MISSING_HOUR, rejected(dismiss(time)))
        assertEquals(RejectReason.INVALID_HOUR, rejected(dismiss(time, AlarmClock.EXTRA_HOUR to 24)))
        assertEquals(RejectReason.INVALID_MINUTES, rejected(dismiss(time, AlarmClock.EXTRA_MINUTES to 60)))
        assertEquals(RejectReason.INVALID_PARAM, rejected(dismiss(time, AlarmClock.EXTRA_IS_PM to "pm")))
    }

    // ---- SNOOZE_ALARM ----

    @Test
    fun snoozeDurationIsOptionalAndRangeChecked() {
        fun snooze(vararg e: Pair<String, Any?>) = AlarmIntentParser.parse(AlarmClock.ACTION_SNOOZE_ALARM, mapOf(*e))
        assertEquals(AlarmIntentRequest.SnoozeAlarm(null), snooze())
        assertEquals(AlarmIntentRequest.SnoozeAlarm(5), snooze(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION to 5))
        assertEquals(AlarmIntentRequest.SnoozeAlarm(60), snooze(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION to 60))
        for (bad in listOf<Any>(0, -3, 61, "5", 5L)) {
            assertEquals("snooze=$bad", RejectReason.INVALID_SNOOZE, rejected(snooze(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION to bad)))
        }
    }
}
