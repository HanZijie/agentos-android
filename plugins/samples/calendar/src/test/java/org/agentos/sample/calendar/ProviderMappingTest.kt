package org.agentos.sample.calendar

import org.agentos.sample.calendar.data.CalendarIds
import org.agentos.sample.calendar.data.CalendarSource
import org.agentos.sample.calendar.data.EventSeries
import org.agentos.sample.calendar.data.ProviderMapping
import org.agentos.sample.calendar.data.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** C7/C9：本 App 的模型 ↔ 系统日历库的映射（纯函数）：全天、时区、RRULE 往返、提醒换算、账号分类。 */
class ProviderMappingTest {
    private val sh = ZoneId.of("Asia/Shanghai")
    private val la = ZoneId.of("America/Los_Angeles")
    private val day = 86_400_000L

    // 2026-10-12 是周一
    private val monday = LocalDate.parse("2026-10-12")

    private fun rule(rrule: String?, extras: String? = null, allDay: Boolean = false, zone: ZoneId = sh, start: LocalDate = monday) =
        ProviderMapping.parseRule(rrule, extras, start, allDay, zone)

    // ---- 账号分类 ----

    @Test fun accountTypesAreClassifiedAndUnknownOnesAreOther() {
        assertEquals(CalendarSource.GOOGLE, ProviderMapping.classify("com.google"))
        assertEquals(CalendarSource.LOCAL, ProviderMapping.classify("LOCAL"))
        assertEquals(CalendarSource.CALDAV, ProviderMapping.classify("bitfire.at.davdroid"))
        assertEquals(CalendarSource.CALDAV, ProviderMapping.classify("org.dmfs.account"))
        for (other in listOf("com.android.exchange", "com.microsoft.office.outlook", "com.xiaomi", "com.huawei.hwid", "", "something.new")) {
            assertEquals(other, CalendarSource.OTHER, ProviderMapping.classify(other))
        }
        assertEquals(CalendarSource.OTHER, ProviderMapping.classify(null))
    }

    @Test fun writableMeansContributorOrAbove() {
        assertFalse(ProviderMapping.isWritable(0))
        assertFalse(ProviderMapping.isWritable(200)) // read
        assertFalse(ProviderMapping.isWritable(300)) // respond
        assertFalse(ProviderMapping.isWritable(400)) // override
        assertTrue(ProviderMapping.isWritable(500)) // contributor
        assertTrue(ProviderMapping.isWritable(600)) // editor
        assertTrue(ProviderMapping.isWritable(700)) // owner
        assertTrue(ProviderMapping.isWritable(800)) // root
    }

    // ---- 时区 ----

    @Test fun fixedOffsetsAreWrittenAsGmtAndReadBack() {
        assertEquals("UTC", ProviderMapping.providerZoneId(ZoneOffset.UTC))
        assertEquals("GMT+05:30", ProviderMapping.providerZoneId(ZoneOffset.ofHoursMinutes(5, 30)))
        assertEquals("Asia/Shanghai", ProviderMapping.providerZoneId(sh))
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), ProviderMapping.parseZone("GMT+05:30", sh).rules.getOffset(java.time.Instant.EPOCH))
        assertEquals(sh, ProviderMapping.parseZone("Mars/Base", sh))
        assertEquals(sh, ProviderMapping.parseZone(null, sh))
        assertEquals(sh, ProviderMapping.parseZone("", sh))
    }

    // ---- 时长 ----

    @Test fun durationsRoundTripAndTolerateTheCommonForms() {
        assertEquals("P3600S", ProviderMapping.durationText(false, 0, 3_600_000, 0, 0))
        assertEquals("P0S", ProviderMapping.durationText(false, 5, 5, 0, 0))
        assertEquals("P1D", ProviderMapping.durationText(true, 0, 0, 100, 100))
        assertEquals("P3D", ProviderMapping.durationText(true, 0, 0, 100, 102))
        assertEquals(3_600_000L, ProviderMapping.parseDuration("P3600S"))
        assertEquals(3_600_000L, ProviderMapping.parseDuration("PT1H"))
        assertEquals(day, ProviderMapping.parseDuration("P1D"))
        assertEquals(7 * day, ProviderMapping.parseDuration("P1W"))
        assertEquals(day + 2 * 3_600_000L + 30 * 60_000L, ProviderMapping.parseDuration("P1DT2H30M"))
        assertEquals(0L, ProviderMapping.parseDuration("PT0S"))
        for (bad in listOf("", "P", "PT", "P1", "3600", "PXD", "P1Y", null)) assertNull("'$bad'", ProviderMapping.parseDuration(bad))
    }

    // ---- RRULE：写 ----

    @Test fun buildRruleWritesUtcUntilForTimedAndDateUntilForAllDay() {
        assertNull(ProviderMapping.buildRrule(Recurrence.NONE, null, false, sh))
        assertNull(ProviderMapping.buildRrule(Recurrence.CUSTOM, null, false, sh))
        assertEquals("FREQ=DAILY", ProviderMapping.buildRrule(Recurrence.DAILY, null, false, sh))
        assertEquals("FREQ=MONTHLY", ProviderMapping.buildRrule(Recurrence.MONTHLY, null, true, sh))
        // 2026-10-31 23:59:59.999 +08:00 = 15:59:59Z（向下取整到秒）
        val until = ms("2026-10-31T23:59:59+08:00") + 999
        assertEquals("FREQ=WEEKLY;UNTIL=20261031T155959Z", ProviderMapping.buildRrule(Recurrence.WEEKLY, until, false, sh))
        assertEquals("FREQ=YEARLY;UNTIL=20261031", ProviderMapping.buildRrule(Recurrence.YEARLY, until, true, sh))
        // 全天日程的截止日按设备时区取日期：同一时刻在洛杉矶是 10-31 08:59 → 日期仍是 10-31
        assertEquals("FREQ=YEARLY;UNTIL=20261031", ProviderMapping.buildRrule(Recurrence.YEARLY, until, true, la))
    }

    // ---- RRULE：读 ----

    @Test fun simpleRulesMapToTheFiveValues() {
        assertEquals(Recurrence.NONE, rule(null).recurrence)
        assertEquals(Recurrence.NONE, rule("   ").recurrence)
        assertEquals(Recurrence.DAILY, rule("FREQ=DAILY").recurrence)
        assertEquals(Recurrence.DAILY, rule("RRULE:FREQ=DAILY").recurrence)
        assertEquals(Recurrence.WEEKLY, rule("FREQ=WEEKLY").recurrence)
        assertEquals(Recurrence.MONTHLY, rule("FREQ=MONTHLY").recurrence)
        assertEquals(Recurrence.YEARLY, rule("FREQ=YEARLY").recurrence)
        assertEquals("keys are case-insensitive", Recurrence.DAILY, rule("freq=daily").recurrence)
        assertNull(rule("FREQ=DAILY").rrule)
    }

    @Test fun harmlessExtrasThatMatchTheStartDateStillMap() {
        assertEquals(Recurrence.WEEKLY, rule("FREQ=WEEKLY;WKST=SU;BYDAY=MO").recurrence) // Google 常写成这样
        assertEquals(Recurrence.WEEKLY, rule("FREQ=WEEKLY;INTERVAL=1").recurrence)
        assertEquals(Recurrence.MONTHLY, rule("FREQ=MONTHLY;BYMONTHDAY=12").recurrence)
        assertEquals(Recurrence.YEARLY, rule("FREQ=YEARLY;BYMONTH=10").recurrence)
        assertEquals(Recurrence.YEARLY, rule("FREQ=YEARLY;BYMONTH=10;BYMONTHDAY=12").recurrence)
    }

    @Test fun rulesThatCannotBeExpressedLosslesslyAreCustomWithTheOriginalText() {
        val cases = listOf(
            "FREQ=MONTHLY;BYDAY=2TU", // 每月第二个周二
            "FREQ=MONTHLY;BYDAY=MO", "FREQ=MONTHLY;BYSETPOS=2;BYDAY=TU",
            "FREQ=WEEKLY;BYDAY=MO,WE", // BYDAY 多值
            "FREQ=WEEKLY;BYDAY=TU", // 星期几和开始日不一致
            "FREQ=WEEKLY;INTERVAL=2", "FREQ=DAILY;INTERVAL=3", // 间隔大于 1
            "FREQ=DAILY;COUNT=5", "FREQ=WEEKLY;COUNT=2;BYDAY=MO", // COUNT
            "FREQ=MONTHLY;BYMONTHDAY=13", "FREQ=MONTHLY;BYMONTHDAY=-1", // 不是开始日
            "FREQ=YEARLY;BYMONTH=11", "FREQ=YEARLY;BYDAY=MO",
            "FREQ=DAILY;BYDAY=MO", "FREQ=WEEKLY;BYMONTHDAY=12",
            "FREQ=HOURLY", "FREQ=SECONDLY", "BYDAY=MO", "FREQ=WEEKLY;FOO", "FREQ=WEEKLY;BYHOUR=9",
            "FREQ=DAILY;UNTIL=garbage",
        )
        for (c in cases) {
            val r = rule(c)
            assertEquals(c, Recurrence.CUSTOM, r.recurrence)
            assertEquals("original text is returned: $c", c, r.rrule)
            assertNull(r.untilUtc)
        }
        assertEquals("a RRULE: prefix is dropped from the returned text", "FREQ=DAILY;COUNT=5", rule("RRULE:FREQ=DAILY;COUNT=5").rrule)
    }

    @Test fun exceptionsAndExtraDatesMakeTheSeriesCustomEvenWithASimpleRule() {
        val r = rule("FREQ=WEEKLY", extras = "20261019T020000Z")
        assertEquals(Recurrence.CUSTOM, r.recurrence)
        assertEquals("FREQ=WEEKLY", r.rrule)
        val only = rule(null, extras = "20261019T020000Z")
        assertEquals(Recurrence.CUSTOM, only.recurrence)
        assertEquals("20261019T020000Z", only.rrule)
    }

    @Test fun untilIsParsedFromUtcDateAndFloatingForms() {
        assertEquals(ms("2026-10-31T23:59:59+08:00"), rule("FREQ=WEEKLY;UNTIL=20261031T155959Z").untilUtc)
        assertEquals("a date means the whole day in the event's zone", ms("2026-11-01T00:00:00+08:00") - 1, rule("FREQ=WEEKLY;UNTIL=20261031").untilUtc)
        assertEquals("floating = local time of the event zone", ms("2026-10-31T23:59:59+08:00"), rule("FREQ=WEEKLY;UNTIL=20261031T235959").untilUtc)
        // 全天日程：只认日期部分，不管后面的 T000000Z
        assertEquals(ms("2026-10-27T00:00:00+08:00") - 1, rule("FREQ=WEEKLY;UNTIL=20261026T000000Z", allDay = true).untilUtc)
        assertEquals(ms("2026-10-27T00:00:00+08:00") - 1, rule("FREQ=WEEKLY;UNTIL=20261026", allDay = true).untilUtc)
        assertEquals(Recurrence.WEEKLY, rule("FREQ=WEEKLY;UNTIL=20261026", allDay = true).recurrence)
    }

    @Test fun ruleRoundTripsForEveryFrequencyTimedAndAllDay() {
        val until = ms("2026-12-31T23:59:59+08:00") + 999
        for (allDay in listOf(false, true)) {
            for (rec in listOf(Recurrence.DAILY, Recurrence.WEEKLY, Recurrence.MONTHLY, Recurrence.YEARLY)) {
                for (u in listOf<Long?>(null, until)) {
                    val text = ProviderMapping.buildRrule(rec, u, allDay, sh)!!
                    val back = ProviderMapping.parseRule(text, null, monday, allDay, sh)
                    assertEquals("$text (allDay=$allDay)", rec, back.recurrence)
                    // 定时：秒精度（去掉毫秒）；全天：当天最后一刻
                    val expected = u?.let { if (allDay) ms("2027-01-01T00:00:00+08:00") - 1 else it / 1000 * 1000 }
                    assertEquals("$text (allDay=$allDay)", expected, back.untilUtc)
                }
            }
        }
    }

    // ---- 提醒 ----

    @Test fun reminderMinutesAreConvertedForAllDayEventsOnly() {
        assertEquals(listOf(10, 60), ProviderMapping.remindersToProvider(listOf(10, 60), allDay = false))
        // 全天：本 App 相对第一天 09:00，系统相对 00:00（-540 = 当天 09:00，900 = 前一天 09:00）
        assertEquals(listOf(-540, 900), ProviderMapping.remindersToProvider(listOf(0, 1440), allDay = true))
        assertEquals(listOf(10, 60), ProviderMapping.remindersFromProvider(listOf(10 to 1, 60 to 0), allDay = false))
        assertEquals(listOf(0, 1440), ProviderMapping.remindersFromProvider(listOf(-540 to 1, 900 to 1), allDay = true))
    }

    @Test fun readingRemindersSkipsWhatCannotBeRepresented() {
        // 邮件(2) / 短信(3) 提醒不要；-1 = 用日历默认；全天换算后为负（比 09:00 还晚）表达不了；超过 4 周也不要
        val rows = listOf(10 to 1, 15 to 2, 20 to 3, -1 to 1, 60 to 1, 50_000 to 1)
        assertEquals(listOf(10, 60), ProviderMapping.remindersFromProvider(rows, allDay = false))
        assertEquals(listOf(0), ProviderMapping.remindersFromProvider(listOf(-540 to 1, -600 to 1), allDay = true))
        assertEquals("at most five, soonest first", listOf(0, 5, 10, 15, 30), ProviderMapping.remindersFromProvider(listOf(60, 30, 15, 10, 5, 0, 120).map { it to 1 }, allDay = false))
        assertEquals("duplicates collapse", listOf(10), ProviderMapping.remindersFromProvider(listOf(10 to 1, 10 to 0), allDay = false))
    }

    // ---- 行 → 模型 ----

    private fun row(vararg kv: Pair<String, Any?>): Map<String, Any?> = mapOf("_id" to 7L, "calendar_id" to 3L) + kv

    @Test fun aTimedOneOffRowBecomesATimedSeries() {
        val s = ProviderMapping.seriesFromRow(
            row("title" to "Standup", "description" to "d", "eventLocation" to "Room 1", "allDay" to 0L, "dtstart" to ms("2026-10-08T09:00:00+08:00"), "dtend" to ms("2026-10-08T09:15:00+08:00"), "eventTimezone" to "Asia/Shanghai"),
            listOf(10), sh,
        )
        assertEquals("sys:7", s.id)
        assertEquals("sys:3", s.calendarId)
        assertEquals("Standup", s.title)
        assertEquals("Room 1", s.location)
        assertEquals(ms("2026-10-08T09:00:00+08:00"), s.startUtc)
        assertEquals(ms("2026-10-08T09:15:00+08:00"), s.endUtc)
        assertEquals("Asia/Shanghai", s.zoneId)
        assertEquals(Recurrence.NONE, s.recurrence)
        assertEquals(listOf(10), s.reminders)
        assertTrue(s.busy)
    }

    @Test fun aRecurringRowUsesDurationNotDtend() {
        val s = ProviderMapping.seriesFromRow(
            row("title" to "Weekly", "allDay" to 0L, "dtstart" to ms("2026-10-12T10:00:00+08:00"), "duration" to "P3600S", "rrule" to "FREQ=WEEKLY;UNTIL=20261031T155959Z", "eventTimezone" to "Asia/Shanghai"),
            emptyList(), sh,
        )
        assertEquals(Recurrence.WEEKLY, s.recurrence)
        assertEquals(ms("2026-10-12T11:00:00+08:00"), s.endUtc)
        assertEquals(ms("2026-10-31T23:59:59+08:00"), s.recurrenceUntilUtc)
        assertNull(s.rrule)
    }

    @Test fun anAllDayRowUsesUtcMidnightsAndAnExclusiveEnd() {
        val start = monday.toEpochDay() * day
        val oneDay = ProviderMapping.seriesFromRow(row("allDay" to 1L, "dtstart" to start, "dtend" to start + day, "eventTimezone" to "UTC"), emptyList(), sh)
        assertTrue(oneDay.allDay)
        assertEquals(monday, oneDay.startDate)
        assertEquals(monday, oneDay.endDate)
        assertEquals("zone is the device's, not UTC", "Asia/Shanghai", oneDay.zoneId)
        assertEquals("startUtc/endUtc are the device-zone day bounds", ms("2026-10-12T00:00:00+08:00"), oneDay.startUtc)
        assertEquals(ms("2026-10-13T00:00:00+08:00"), oneDay.endUtc)
        val twoDays = ProviderMapping.seriesFromRow(row("allDay" to 1L, "dtstart" to start, "dtend" to start + 2 * day), emptyList(), la)
        assertEquals(monday.plusDays(1), twoDays.endDate)
        assertEquals("the day numbers do not move with the device zone", monday, twoDays.startDate)
        val recurring = ProviderMapping.seriesFromRow(row("allDay" to 1L, "dtstart" to start, "duration" to "P3D", "rrule" to "FREQ=YEARLY"), emptyList(), sh)
        assertEquals(monday.plusDays(2), recurring.endDate)
        assertEquals(Recurrence.YEARLY, recurring.recurrence)
    }

    @Test fun busyIsFalseForFreeDeclinedOrCancelledEvents() {
        fun busy(vararg kv: Pair<String, Any?>) = ProviderMapping.seriesFromRow(row("dtstart" to 0L, "dtend" to 1L, *kv), emptyList(), sh).busy
        assertTrue(busy())
        assertTrue(busy("availability" to 0L, "selfAttendeeStatus" to 1L))
        assertTrue("tentative still counts as busy", busy("availability" to 2L))
        assertFalse(busy("availability" to 1L))
        assertFalse(busy("selfAttendeeStatus" to 2L))
        assertFalse(busy("eventStatus" to 2L))
    }

    @Test fun customRowsKeepTheRuleText() {
        val s = ProviderMapping.seriesFromRow(row("allDay" to 0L, "dtstart" to 0L, "duration" to "P1800S", "rrule" to "FREQ=MONTHLY;BYDAY=2TU", "eventTimezone" to "Asia/Shanghai"), emptyList(), sh)
        assertEquals(Recurrence.CUSTOM, s.recurrence)
        assertEquals("FREQ=MONTHLY;BYDAY=2TU", s.rrule)
        assertTrue(s.isRecurring)
    }

    // ---- 实例 → 出现 ----

    @Test fun allDayInstancesKeepTheirCalendarDaysInAnyDeviceZone() {
        val series = ProviderMapping.seriesFromRow(row("allDay" to 1L, "dtstart" to monday.toEpochDay() * day, "dtend" to (monday.toEpochDay() + 2) * day), emptyList(), sh)
        val begin = monday.toEpochDay() * day
        for (zone in listOf(sh, la, ZoneId.of("Pacific/Auckland"))) {
            val o = ProviderMapping.occurrenceFrom(series, begin, begin + 2 * day, zone)
            assertEquals(zone.id, monday, o.firstDay)
            assertEquals(zone.id, monday.plusDays(1), o.lastDay)
            assertEquals(monday.atStartOfDay(zone).toInstant().toEpochMilli(), o.startMs)
            assertEquals(monday.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli(), o.endMs)
            assertTrue(o.allDay)
        }
    }

    @Test fun timedInstancesSpanTheDeviceZoneDays() {
        val s = ProviderMapping.seriesFromRow(row("allDay" to 0L, "dtstart" to ms("2026-10-08T22:00:00+08:00"), "dtend" to ms("2026-10-09T02:00:00+08:00"), "eventTimezone" to "Asia/Shanghai"), emptyList(), sh)
        val o = ProviderMapping.occurrenceFrom(s, s.startUtc, s.endUtc, sh)
        assertEquals(LocalDate.parse("2026-10-08"), o.firstDay)
        assertEquals(LocalDate.parse("2026-10-09"), o.lastDay)
        assertTrue(o.spansDays)
        val sameMoment = ProviderMapping.occurrenceFrom(s, s.startUtc, s.endUtc, la)
        assertEquals("the same instants fall on other days in Los Angeles", LocalDate.parse("2026-10-08"), sameMoment.firstDay)
        assertEquals(LocalDate.parse("2026-10-08"), sameMoment.lastDay)
    }

    @Test fun overlapIsHalfOpenAndZeroLengthEventsCountInsideTheRange() {
        val s = ProviderMapping.seriesFromRow(row("dtstart" to 1000L, "dtend" to 2000L), emptyList(), sh)
        val o = ProviderMapping.occurrenceFrom(s, 1000, 2000, sh)
        assertTrue(ProviderMapping.overlaps(o, 0, 1500))
        assertFalse("ends exactly at the range start", ProviderMapping.overlaps(o, 2000, 3000))
        assertFalse("starts exactly at the range end", ProviderMapping.overlaps(o, 0, 1000))
        val point = ProviderMapping.occurrenceFrom(s, 1500, 1500, sh)
        assertTrue(ProviderMapping.overlaps(point, 1000, 2000))
        assertFalse(ProviderMapping.overlaps(point, 1600, 2000))
    }

    // ---- 模型 → 列 ----

    private fun series(
        allDay: Boolean = false,
        rec: Recurrence = Recurrence.NONE,
        until: Long? = null,
        zone: String = "Asia/Shanghai",
        reminders: List<Int> = emptyList(),
        color: Int? = null,
    ): EventSeries = if (allDay) {
        EventSeries(
            id = "sys:7", calendarId = "sys:3", title = "T", description = "D", location = "L", allDay = true, startUtc = 0, endUtc = 0, zoneId = sh.id,
            startDay = monday.toEpochDay(), endDay = monday.toEpochDay() + 1, recurrence = rec, recurrenceUntilUtc = until, reminders = reminders, color = color,
        )
    } else {
        EventSeries(
            id = "sys:7", calendarId = "sys:3", title = "T", description = "D", location = "L", startUtc = ms("2026-10-12T10:00:00+08:00"), endUtc = ms("2026-10-12T11:30:00+08:00"),
            zoneId = zone, recurrence = rec, recurrenceUntilUtc = until, reminders = reminders, color = color,
        )
    }

    @Test fun oneOffTimedEventsWriteDtendAndNoDurationOrRule() {
        val v = ProviderMapping.toProviderValues(series(), sh, "org.agentos.sample.calendar", includeOwner = true)
        assertEquals(ms("2026-10-12T10:00:00+08:00"), v["dtstart"])
        assertEquals(ms("2026-10-12T11:30:00+08:00"), v["dtend"])
        assertNull(v["duration"])
        assertNull(v["rrule"])
        assertEquals(0, v["allDay"])
        assertEquals("Asia/Shanghai", v["eventTimezone"])
        assertEquals("org.agentos.sample.calendar", v["customAppPackage"])
        assertTrue("the keys exist (explicit null) so a former rule is cleared on update", v.containsKey("duration") && v.containsKey("rrule"))
    }

    @Test fun recurringTimedEventsWriteDurationAndRuleAndNeverDtend() {
        val until = ms("2026-10-31T23:59:59+08:00") + 999
        val v = ProviderMapping.toProviderValues(series(rec = Recurrence.WEEKLY, until = until), sh, "pkg", includeOwner = false)
        assertNull("DTEND must be empty for recurring events", v["dtend"])
        assertTrue(v.containsKey("dtend"))
        assertEquals("P5400S", v["duration"])
        assertEquals("FREQ=WEEKLY;UNTIL=20261031T155959Z", v["rrule"])
        assertFalse("owner tag is written on insert only", v.containsKey("customAppPackage"))
    }

    @Test fun allDayEventsWriteUtcMidnightsAndTheUtcZone() {
        val one = ProviderMapping.toProviderValues(series(allDay = true), sh, null, includeOwner = true)
        assertEquals(monday.toEpochDay() * day, one["dtstart"])
        assertEquals("exclusive end = the day after the last day", (monday.toEpochDay() + 2) * day, one["dtend"])
        assertEquals("UTC", one["eventTimezone"])
        assertEquals(1, one["allDay"])
        assertNull(one["duration"])
        val rec = ProviderMapping.toProviderValues(series(allDay = true, rec = Recurrence.MONTHLY, until = ms("2027-01-31T23:59:59+08:00")), sh, null, includeOwner = false)
        assertNull(rec["dtend"])
        assertEquals("all-day durations are in days", "P2D", rec["duration"])
        assertEquals("FREQ=MONTHLY;UNTIL=20270131", rec["rrule"])
    }

    @Test fun fixedOffsetZonesAreWrittenInAFormTheProviderUnderstands() {
        val v = ProviderMapping.toProviderValues(series(zone = "+05:30"), sh, null, includeOwner = false)
        assertEquals("GMT+05:30", v["eventTimezone"])
    }

    @Test fun colorIsWrittenOrCleared() {
        assertEquals(0xFF112233.toInt(), ProviderMapping.toProviderValues(series(color = 0xFF112233.toInt()), sh, null, false)["eventColor"])
        val v = ProviderMapping.toProviderValues(series(color = null), sh, null, false)
        assertTrue(v.containsKey("eventColor"))
        assertNull(v["eventColor"])
    }

    // ---- 往返：模型 → 列 → 行 → 模型 ----

    private fun roundTrip(s: EventSeries): EventSeries {
        val values = ProviderMapping.toProviderValues(s, sh, "pkg", includeOwner = true)
        val row = mapOf<String, Any?>("_id" to (CalendarIds.rawSystem(s.id)), "calendar_id" to CalendarIds.rawSystem(s.calendarId)) + values.mapValues { (_, v) -> if (v is Int) v.toLong() else v }
        return ProviderMapping.seriesFromRow(row, s.reminders, sh)
    }

    @Test fun modelToRowToModelIsLossless() {
        val until = ms("2026-12-31T23:59:59+08:00") + 999
        val shapes = buildList {
            add(series())
            add(series(color = 0xFF2BA0A4.toInt(), reminders = listOf(10, 60)))
            add(series(zone = "America/New_York"))
            for (rec in listOf(Recurrence.DAILY, Recurrence.WEEKLY, Recurrence.MONTHLY, Recurrence.YEARLY)) {
                add(series(rec = rec))
                add(series(rec = rec, until = until))
                add(series(allDay = true, rec = rec, until = until, reminders = listOf(0, 1440)))
            }
            add(series(allDay = true))
        }
        for (s in shapes) {
            val back = roundTrip(s)
            val ctx = "$s"
            assertEquals(ctx, s.id, back.id)
            assertEquals(ctx, s.calendarId, back.calendarId)
            assertEquals(ctx, s.title, back.title)
            assertEquals(ctx, s.allDay, back.allDay)
            assertEquals(ctx, s.recurrence, back.recurrence)
            assertEquals(ctx, s.reminders, back.reminders)
            assertEquals(ctx, s.color, back.color)
            if (s.allDay) {
                assertEquals(ctx, s.startDay, back.startDay)
                assertEquals(ctx, s.endDay, back.endDay)
            } else {
                assertEquals(ctx, s.startUtc, back.startUtc)
                assertEquals(ctx, s.endUtc, back.endUtc)
                assertEquals(ctx, s.zoneId, back.zoneId)
            }
            // 截止：定时日程秒精度，全天日程落在当天最后一刻
            val expectedUntil = s.recurrenceUntilUtc?.let { if (s.allDay) ms("2027-01-01T00:00:00+08:00") - 1 else it / 1000 * 1000 }
            assertEquals(ctx, expectedUntil, back.recurrenceUntilUtc)
        }
    }
}
