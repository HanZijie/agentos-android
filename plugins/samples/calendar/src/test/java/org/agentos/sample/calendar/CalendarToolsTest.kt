package org.agentos.sample.calendar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.agentos.sample.calendar.data.CalendarRepository
import org.agentos.sample.calendar.tools.CalendarTools
import org.agentos.sample.calendar.tools.ToolOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

/** 设备时区固定为 Asia/Shanghai，“现在”是 2026-10-07（周三）08:00。 */
class CalendarToolsTest {
    private lateinit var time: FakeTime
    private lateinit var repo: CalendarRepository
    private lateinit var tools: CalendarTools
    private val defaultCal get() = repo.defaultCalendar.id

    @Before fun setUp() {
        time = FakeTime()
        repo = newRepo(time)
        tools = CalendarTools(repo, Dispatchers.Unconfined)
    }

    private fun call(name: String, json: String = "{}"): ToolOutput =
        runBlocking { tools.all().first { it.name == name }.handler(Json.parseToJsonElement(json).jsonObject) }

    private fun ok(name: String, json: String = "{}"): JsonObject {
        val out = call(name, json)
        assertFalse("$name failed: ${out.text}", out.isError)
        assertEquals("structuredContent mirrors the text content", Json.parseToJsonElement(out.text), out.structured)
        return out.structured!!
    }

    private fun fails(name: String, json: String, contains: String) {
        val out = call(name, json)
        assertTrue("$name should fail for $json but returned ${out.text}", out.isError)
        assertTrue("error '${out.text}' should mention '$contains'", out.text.contains(contains, ignoreCase = true))
        assertFalse("error is a single line", out.text.contains('\n'))
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.arr(k: String): JsonArray = this[k]!!.jsonArray
    private fun JsonArray.titles() = map { it.jsonObject.s("title") }

    private fun event(title: String, start: String, end: String? = null, extra: String = ""): JsonObject {
        val endPart = if (end != null) ""","end":"$end"""" else ""
        return ok("event_create", """{"title":"$title","start":"$start"$endPart$extra}""")
    }

    // ---- 契约：名字、必填参数、注解 ----

    @Test fun toolSetMatchesTheContract() {
        val byName = tools.all().associateBy { it.name }
        val required = mapOf(
            "calendar_list" to emptyList(), "calendar_create" to listOf("name"), "calendar_delete" to listOf("id"),
            "event_list" to emptyList(), "event_get" to listOf("id"), "event_create" to listOf("title", "start"),
            "event_update" to listOf("id"), "event_delete" to listOf("id"), "event_search" to listOf("query"),
            "agenda_today" to emptyList(), "free_slots" to listOf("date", "duration_minutes"),
        )
        for ((name, req) in required) {
            val t = byName[name] ?: error("missing tool $name")
            assertEquals("object", t.inputSchema["type"]!!.jsonPrimitive.content)
            val declared = t.inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals("required params of $name", req, declared)
            assertTrue("$name needs a description", t.description.length > 40)
            assertTrue("$name description is English", t.description.all { it.code < 128 })
            for (r in req) assertNotNull(t.inputSchema["properties"]!!.jsonObject[r])
        }
        for (n in listOf("calendar_list", "event_list", "event_get", "event_search", "agenda_today", "free_slots")) {
            assertEquals("$n readOnly", true, byName[n]!!.annotations.readOnlyHint)
        }
        for (n in listOf("calendar_delete", "event_delete")) assertEquals("$n destructive", true, byName[n]!!.annotations.destructiveHint)
        assertEquals(true, byName["event_update"]!!.annotations.idempotentHint)
        assertEquals(true, byName["calendar_update"]!!.annotations.idempotentHint)
        for (n in listOf("event_create", "calendar_create")) {
            assertNotEquals(true, byName[n]!!.annotations.readOnlyHint)
            assertNotEquals(true, byName[n]!!.annotations.destructiveHint)
        }
        assertEquals("tool names are unique", tools.all().size, byName.size)
        assertTrue(tools.all().all { Regex("[a-z]+(_[a-z]+)+").matches(it.name) })
    }

    private fun assertNotEquals(a: Any?, b: Any?) = org.junit.Assert.assertNotEquals(a, b)

    // ---- calendar_* ----

    @Test fun calendarListShowsDefaultAndCounts() {
        event("A", "2026-10-08T10:00:00+08:00")
        val list = ok("calendar_list").arr("calendars")
        assertEquals(1, list.size)
        val c = list[0].jsonObject
        assertEquals(defaultCal, c.s("id"))
        assertEquals("Personal", c.s("name"))
        assertTrue(Regex("#[0-9A-F]{6}").matches(c.s("color")))
        assertTrue(c["visible"]!!.jsonPrimitive.boolean)
        assertTrue(c["is_default"]!!.jsonPrimitive.boolean)
        assertEquals(1, c["event_count"]!!.jsonPrimitive.int)
    }

    @Test fun calendarCreate() {
        val c = ok("calendar_create", """{"name":"Work","color":"#4a7bdb"}""")
        assertEquals("Work", c.s("name"))
        assertEquals("#4A7BDB", c.s("color"))
        assertFalse(c["is_default"]!!.jsonPrimitive.boolean)
        assertEquals(2, repo.calendars.value.size)
        assertTrue(Regex("#[0-9A-F]{6}").matches(ok("calendar_create", """{"name":"Family"}""").s("color")))
        fails("calendar_create", "{}", "name")
        fails("calendar_create", """{"name":"  "}""", "name")
        fails("calendar_create", """{"name":"work"}""", "already exists")
        fails("calendar_create", """{"name":"X","color":"red"}""", "color")
        fails("calendar_create", """{"name":"X","color":"#12345"}""", "color")
        fails("calendar_create", """{"name":["a"]}""", "name")
    }

    @Test fun calendarUpdate() {
        val id = ok("calendar_create", """{"name":"Work"}""").s("id")
        val c = ok("calendar_update", """{"id":"$id","name":"Office","visible":false,"color":"#C7468F"}""")
        assertEquals("Office", c.s("name"))
        assertFalse(c["visible"]!!.jsonPrimitive.boolean)
        assertEquals("#C7468F", c.s("color"))
        assertEquals("idempotent", c, ok("calendar_update", """{"id":"$id","name":"Office","visible":false,"color":"#C7468F"}"""))
        fails("calendar_update", "{}", "id")
        fails("calendar_update", """{"id":"$id"}""", "nothing to update")
        fails("calendar_update", """{"id":"nope","name":"x"}""", "not found")
        fails("calendar_update", """{"id":"$id","name":"Personal"}""", "already exists")
        fails("calendar_update", """{"id":"$id","visible":"maybe"}""", "visible")
    }

    @Test fun calendarDeleteCascades() {
        val id = ok("calendar_create", """{"name":"Work"}""").s("id")
        event("A", "2026-10-08T10:00:00+08:00", extra = ""","calendar_id":"$id"""")
        event("B", "2026-10-09T10:00:00+08:00", extra = ""","calendar_id":"$id"""")
        event("C", "2026-10-09T10:00:00+08:00")
        val r = ok("calendar_delete", """{"id":"$id"}""")
        assertEquals(2, r["deleted_events"]!!.jsonPrimitive.int)
        assertEquals(listOf("C"), ok("event_list", """{"to":"2026-12-31"}""").arr("events").titles())
        fails("calendar_delete", """{"id":"$id"}""", "not found")
        fails("calendar_delete", """{"id":"$defaultCal"}""", "default calendar")
        fails("calendar_delete", "{}", "id")
    }

    // ---- event_create / event_get ----

    @Test fun eventCreateMinimal() {
        val e = event("Dentist", "2026-10-08T15:00:00+08:00")
        assertEquals("Dentist", e.s("title"))
        assertEquals("2026-10-08T15:00:00+08:00", e.s("start"))
        assertEquals("end defaults to start + 1 hour", "2026-10-08T16:00:00+08:00", e.s("end"))
        assertEquals(defaultCal, e.s("calendar_id"))
        assertEquals(e.s("id"), e.s("series_id"))
        assertFalse(e["all_day"]!!.jsonPrimitive.boolean)
        assertEquals("none", e.s("recurrence"))
        assertEquals(0, e.arr("reminder_minutes").size)
        assertEquals("Asia/Shanghai", e.s("timezone"))
        assertEquals(1, repo.events.value.size)
    }

    @Test fun eventCreateWithEverything() {
        val cal = ok("calendar_create", """{"name":"Work"}""").s("id")
        val e = ok(
            "event_create",
            """{"title":"Review","start":"2026-10-08T15:00:00+08:00","end":"2026-10-08T16:30:00+08:00","location":"Room 4",
               "description":"Bring slides","calendar_id":"$cal","reminder_minutes":[60,10],"recurrence":"weekly",
               "recurrence_until":"2026-12-31","color":"#6C5CE7"}""",
        )
        assertEquals("Room 4", e.s("location"))
        assertEquals("Bring slides", e.s("description"))
        assertEquals(cal, e.s("calendar_id"))
        assertEquals("Work", e.s("calendar_name"))
        assertEquals(listOf(10, 60), e.arr("reminder_minutes").map { it.jsonPrimitive.int })
        assertEquals("weekly", e.s("recurrence"))
        assertEquals("2026-12-31T23:59:59+08:00", e.s("recurrence_until").replace(Regex("\\.\\d+"), ""))
        assertEquals("#6C5CE7", e.s("color"))
        assertEquals("Mobile-friendly numbers as strings are accepted", listOf(5), ok("event_create", """{"title":"T","start":"2026-10-08T15:00:00+08:00","reminder_minutes":["5"]}""").arr("reminder_minutes").map { it.jsonPrimitive.int })
    }

    @Test fun eventCreateInterpretsTimesWithoutOffsetInDeviceZoneAndKeepsOtherOffsets() {
        assertEquals("2026-10-08T09:30:00+08:00", event("Local", "2026-10-08T09:30:00").s("start"))
        assertEquals("2026-10-08T09:30:00+08:00", event("Spaced", "2026-10-08 09:30").s("start"))
        assertEquals("2026-10-08T09:30:00+08:00", event("Utc", "2026-10-08T01:30:00Z").s("start"))
        val tokyo = event("Tokyo", "2026-10-08T15:00:00+09:00")
        assertEquals("shown in device zone", "2026-10-08T14:00:00+08:00", tokyo.s("start"))
        assertEquals("+09:00", tokyo.s("timezone"))
    }

    @Test fun eventCreateAllDay() {
        val one = ok("event_create", """{"title":"Holiday","start":"2026-10-08","all_day":true}""")
        assertTrue(one["all_day"]!!.jsonPrimitive.boolean)
        assertEquals("2026-10-08T00:00:00+08:00", one.s("start"))
        assertEquals("2026-10-08T23:59:59+08:00", one.s("end"))
        val dateOnly = ok("event_create", """{"title":"Implied","start":"2026-10-08"}""")
        assertTrue("date-only start implies all_day", dateOnly["all_day"]!!.jsonPrimitive.boolean)
        val multi = ok("event_create", """{"title":"Trip","start":"2026-10-30T00:00:00+08:00","end":"2026-11-02T00:00:00+08:00","all_day":true}""")
        assertEquals("a later midnight is exclusive", "2026-11-01T23:59:59+08:00", multi.s("end"))
        val inclusive = ok("event_create", """{"title":"Trip2","start":"2026-10-30","end":"2026-11-02","all_day":true}""")
        assertEquals("2026-11-02T23:59:59+08:00", inclusive.s("end"))
    }

    @Test fun eventCreateRejectsBadInput() {
        fails("event_create", """{"start":"2026-10-08T15:00:00+08:00"}""", "title")
        fails("event_create", """{"title":"  ","start":"2026-10-08T15:00:00+08:00"}""", "title")
        fails("event_create", """{"title":"A"}""", "start")
        fails("event_create", """{"title":"A","start":"tomorrow at 3pm"}""", "start")
        fails("event_create", """{"title":"A","start":"2026-13-45T10:00:00+08:00"}""", "start")
        fails("event_create", """{"title":"A","start":"2026-10-08T15:00:00+08:00","end":"nonsense"}""", "end")
        fails("event_create", """{"title":"A","start":"2026-10-08T15:00:00+08:00","end":"2026-10-08T14:00:00+08:00"}""", "end")
        fails("event_create", """{"title":"A","start":"2026-10-08T15:00:00+08:00","calendar_id":"nope"}""", "calendar not found")
        fails("event_create", """{"title":"A","start":"2026-10-08T15:00:00+08:00","recurrence":"hourly"}""", "recurrence")
        fails("event_create", """{"title":"A","start":"2026-10-08T15:00:00+08:00","color":"blue"}""", "color")
        fails("event_create", """{"title":"A","start":"2026-10-08T15:00:00+08:00","reminder_minutes":[-5]}""", "reminder_minutes")
        fails("event_create", """{"title":"A","start":"2026-10-08T15:00:00+08:00","reminder_minutes":["soon"]}""", "reminder_minutes")
        fails("event_create", """{"title":"A","start":"2026-10-08T15:00:00+08:00","reminder_minutes":5}""", "reminder_minutes")
        fails("event_create", """{"title":"A","start":"2026-10-08T15:00:00+08:00","all_day":"perhaps"}""", "all_day")
        fails("event_create", """{"title":"A","start":"2026-10-08T15:00:00+08:00","recurrence":"daily","recurrence_until":"2026-10-01"}""", "recurrence_until")
        fails("event_create", """{"title":{"a":1},"start":"2026-10-08T15:00:00+08:00"}""", "title")
        assertTrue("nothing was stored", repo.events.value.isEmpty())
    }

    @Test fun eventGetByIdAndOccurrenceId() {
        val e = event("Standup", "2026-10-08T09:00:00+08:00", extra = ""","recurrence":"daily"""")
        val id = e.s("series_id")
        assertEquals("a recurring event is created as its first occurrence", "$id@20261008T010000Z", e.s("id"))
        assertEquals(e, ok("event_get", """{"id":"$id"}"""))
        assertEquals(e, ok("event_get", """{"id":"${e.s("id")}"}"""))
        val third = ok("event_list", """{"from":"2026-10-10","to":"2026-10-10"}""").arr("events").single().jsonObject
        assertEquals("$id@20261010T010000Z", third.s("id"))
        assertEquals(id, third.s("series_id"))
        assertEquals(third, ok("event_get", """{"id":"${third.s("id")}"}"""))
        fails("event_get", "{}", "id")
        fails("event_get", """{"id":"nope"}""", "not found")
        fails("event_get", """{"id":"$id@20261010T020000Z"}""", "not found")
        fails("event_get", """{"id":""}""", "id")
    }

    // ---- event_update ----

    @Test fun eventUpdateChangesOnlyGivenFields() {
        val e = event("Dentist", "2026-10-08T15:00:00+08:00", "2026-10-08T16:30:00+08:00", extra = ""","location":"Clinic"""")
        val id = e.s("id")
        val renamed = ok("event_update", """{"id":"$id","title":"Dentist (moved)"}""")
        assertEquals("Dentist (moved)", renamed.s("title"))
        assertEquals("Clinic", renamed.s("location"))
        assertEquals("2026-10-08T16:30:00+08:00", renamed.s("end"))
        val moved = ok("event_update", """{"id":"$id","start":"2026-10-09T10:00:00+08:00"}""")
        assertEquals("start only → duration kept (1.5h)", "2026-10-09T11:30:00+08:00", moved.s("end"))
        val shorter = ok("event_update", """{"id":"$id","end":"2026-10-09T10:30:00+08:00"}""")
        assertEquals("2026-10-09T10:00:00+08:00", shorter.s("start"))
        assertEquals("2026-10-09T10:30:00+08:00", shorter.s("end"))
        val cleared = ok("event_update", """{"id":"$id","location":"","description":"notes","reminder_minutes":[15]}""")
        assertEquals("", cleared.s("location"))
        assertEquals("notes", cleared.s("description"))
        assertEquals(listOf(15), cleared.arr("reminder_minutes").map { it.jsonPrimitive.int })
        assertEquals("idempotent", cleared, ok("event_update", """{"id":"$id","location":"","description":"notes","reminder_minutes":[15]}"""))
    }

    @Test fun eventUpdateMovesCalendarAndColor() {
        val e = event("A", "2026-10-08T15:00:00+08:00", extra = ""","color":"#E4572E"""")
        val cal = ok("calendar_create", """{"name":"Work"}""").s("id")
        val moved = ok("event_update", """{"id":"${e.s("id")}","calendar_id":"$cal","color":null}""")
        assertEquals(cal, moved.s("calendar_id"))
        assertEquals("color follows the calendar once cleared", repo.calendar(cal)!!.color.let { org.agentos.sample.calendar.data.Palette.format(it) }, moved.s("color"))
        fails("event_update", """{"id":"${e.s("id")}","calendar_id":"nope"}""", "calendar not found")
    }

    @Test fun eventUpdateAppliesToTheWholeSeriesEvenViaOccurrenceId() {
        val e = event("Standup", "2026-10-08T09:00:00+08:00", "2026-10-08T09:15:00+08:00", extra = ""","recurrence":"daily","recurrence_until":"2026-10-20"""")
        val occ = "${e.s("series_id")}@20261010T010000Z"
        val r = ok("event_update", """{"id":"$occ","title":"Daily sync","start":"2026-10-10T10:00:00+08:00"}""")
        assertEquals("the returned item is that occurrence", occ.substringBefore('@') + "@20261010T020000Z", r.s("id"))
        assertEquals("Daily sync", r.s("title"))
        assertEquals(1, repo.events.value.size)
        val all = ok("event_list", """{"from":"2026-10-01","to":"2026-10-31","limit":200}""").arr("events")
        assertEquals("series start moved to Oct 10 10:00, until Oct 20 → 11 occurrences", 11, all.size)
        assertTrue(all.titles().all { it == "Daily sync" })
        val open = ok("event_update", """{"id":"${e.s("series_id")}","recurrence_until":null}""")
        assertEquals(JsonPrimitive(null as String?), open["recurrence_until"])
        val none = ok("event_update", """{"id":"${e.s("series_id")}","recurrence":"none"}""")
        assertEquals("none", none.s("recurrence"))
        assertEquals(1, ok("event_list", """{"from":"2026-10-01","to":"2026-12-31"}""").arr("events").size)
    }

    @Test fun eventUpdateConvertsBetweenTimedAndAllDay() {
        val e = event("Offsite", "2026-10-08T13:00:00+08:00", "2026-10-08T15:00:00+08:00")
        val id = e.s("id")
        val day = ok("event_update", """{"id":"$id","all_day":true}""")
        assertTrue(day["all_day"]!!.jsonPrimitive.boolean)
        assertEquals("2026-10-08T00:00:00+08:00", day.s("start"))
        assertEquals("2026-10-08T23:59:59+08:00", day.s("end"))
        val longer = ok("event_update", """{"id":"$id","end":"2026-10-10"}""")
        assertEquals("2026-10-10T23:59:59+08:00", longer.s("end"))
        val shifted = ok("event_update", """{"id":"$id","start":"2026-10-12"}""")
        assertEquals("start moved, span (3 days) kept", "2026-10-14T23:59:59+08:00", shifted.s("end"))
        val back = ok("event_update", """{"id":"$id","all_day":false,"start":"2026-10-12T09:00:00+08:00","end":"2026-10-12T10:00:00+08:00"}""")
        assertFalse(back["all_day"]!!.jsonPrimitive.boolean)
        assertEquals("2026-10-12T10:00:00+08:00", back.s("end"))
    }

    @Test fun eventUpdateRejectsBadInput() {
        val e = event("A", "2026-10-08T15:00:00+08:00")
        val id = e.s("id")
        fails("event_update", "{}", "id")
        fails("event_update", """{"id":"$id"}""", "nothing to update")
        fails("event_update", """{"id":"nope","title":"x"}""", "not found")
        fails("event_update", """{"id":"$id","title":""}""", "title")
        fails("event_update", """{"id":"$id","end":"2026-10-08T14:00:00+08:00"}""", "end")
        fails("event_update", """{"id":"$id","start":"later"}""", "start")
        fails("event_update", """{"id":"$id","recurrence":"sometimes"}""", "recurrence")
        fails("event_update", """{"id":"$id","color":"nope"}""", "color")
        fails("event_update", """{"id":"$id","reminder_minutes":[1,2,3,4,5,6]}""", "reminders")
        assertEquals("unchanged after failures", e, ok("event_get", """{"id":"$id"}"""))
    }

    // ---- event_delete ----

    @Test fun eventDelete() {
        val e = event("Gone", "2026-10-08T15:00:00+08:00")
        val r = ok("event_delete", """{"id":"${e.s("id")}"}""")
        assertTrue(r["deleted"]!!.jsonPrimitive.boolean)
        assertEquals("Gone", r.s("title"))
        assertFalse(r["was_recurring"]!!.jsonPrimitive.boolean)
        assertTrue(repo.events.value.isEmpty())
        fails("event_delete", """{"id":"${e.s("id")}"}""", "not found")
        fails("event_delete", "{}", "id")
        fails("event_delete", """{"id":"nope"}""", "not found")
    }

    @Test fun deletingAnOccurrenceDeletesTheWholeSeries() {
        val e = event("Daily", "2026-10-08T09:00:00+08:00", extra = ""","recurrence":"daily"""")
        val r = ok("event_delete", """{"id":"${e.s("series_id")}@20261012T010000Z"}""")
        assertTrue(r["was_recurring"]!!.jsonPrimitive.boolean)
        assertEquals(e.s("series_id"), r.s("series_id"))
        assertTrue(repo.events.value.isEmpty())
    }

    // ---- event_list ----

    @Test fun eventListDefaultsToTodayPlusThirtyDays() {
        event("Yesterday", "2026-10-06T10:00:00+08:00")
        event("Earlier today", "2026-10-07T00:30:00+08:00")
        event("Soon", "2026-10-20T10:00:00+08:00")
        event("Day 30", "2026-11-05T10:00:00+08:00")
        event("Too far", "2026-11-07T10:00:00+08:00")
        val r = ok("event_list")
        assertEquals(listOf("Earlier today", "Soon", "Day 30"), r.arr("events").titles())
        assertEquals("2026-10-07T00:00:00+08:00", r.s("from"))
        assertEquals("Asia/Shanghai", r.s("timezone"))
        assertEquals(3, r["count"]!!.jsonPrimitive.int)
        assertFalse(r["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun eventListRangeBoundaries() {
        event("In", "2026-10-08T10:00:00+08:00", "2026-10-08T11:00:00+08:00")
        event("Ends at from", "2026-10-08T09:00:00+08:00", "2026-10-08T10:00:00+08:00")
        event("Starts at to", "2026-10-08T12:00:00+08:00", "2026-10-08T13:00:00+08:00")
        event("Straddles", "2026-10-08T11:30:00+08:00", "2026-10-08T12:30:00+08:00")
        val r = ok("event_list", """{"from":"2026-10-08T10:00:00+08:00","to":"2026-10-08T12:00:00+08:00"}""")
        assertEquals(listOf("In", "Straddles"), r.arr("events").titles())
        assertEquals("date-only to includes the whole day", 4, ok("event_list", """{"from":"2026-10-08","to":"2026-10-08"}""").arr("events").size)
        assertEquals("offsets other than the device's are converted", listOf("In", "Straddles"),
            ok("event_list", """{"from":"2026-10-08T03:00:00+01:00","to":"2026-10-08T11:00:00+07:00"}""").arr("events").titles())
    }

    @Test fun eventListExpandsRecurringEventsWithSeriesIds() {
        val e = event("Gym", "2026-10-05T07:00:00+08:00", extra = ""","recurrence":"weekly"""")
        val list = ok("event_list", """{"from":"2026-10-01","to":"2026-10-31"}""").arr("events")
        assertEquals(4, list.size)
        assertTrue(list.all { it.jsonObject.s("series_id") == e.s("series_id") })
        assertEquals(4, list.map { it.jsonObject.s("id") }.toSet().size)
        assertEquals(listOf("2026-10-05", "2026-10-12", "2026-10-19", "2026-10-26"), list.map { it.jsonObject.s("start").take(10) })
    }

    @Test fun eventListFiltersAndLimits() {
        val work = ok("calendar_create", """{"name":"Work"}""").s("id")
        event("Budget review", "2026-10-08T10:00:00+08:00", extra = ""","calendar_id":"$work","location":"HQ"""")
        event("Lunch", "2026-10-08T12:00:00+08:00", extra = ""","description":"budget talk"""")
        event("Gym", "2026-10-08T18:00:00+08:00")
        assertEquals(listOf("Budget review"), ok("event_list", """{"calendar_id":"$work"}""").arr("events").titles())
        assertEquals(listOf("Budget review", "Lunch"), ok("event_list", """{"query":"BUDGET"}""").arr("events").titles())
        assertEquals(listOf("Budget review"), ok("event_list", """{"query":"hq"}""").arr("events").titles())
        val limited = ok("event_list", """{"limit":2}""")
        assertEquals(2, limited.arr("events").size)
        assertTrue(limited["truncated"]!!.jsonPrimitive.boolean)
        assertEquals("limit above 200 is clamped", 3, ok("event_list", """{"limit":9999}""").arr("events").size)
        repo.updateCalendar(work, visible = false)
        assertEquals("hidden calendars are still listed", 3, ok("event_list").arr("events").size)
    }

    @Test fun eventListRejectsBadInput() {
        fails("event_list", """{"from":"soon"}""", "from")
        fails("event_list", """{"to":"later"}""", "to")
        fails("event_list", """{"from":"2026-10-10","to":"2026-10-09"}""", "'to' must be after")
        fails("event_list", """{"from":"2026-10-10T10:00:00+08:00","to":"2026-10-10T10:00:00+08:00"}""", "'to' must be after")
        fails("event_list", """{"calendar_id":"nope"}""", "not found")
        fails("event_list", """{"limit":0}""", "limit")
        fails("event_list", """{"limit":"many"}""", "limit")
        fails("event_list", """{"limit":-3}""", "limit")
    }

    // ---- event_search ----

    @Test fun eventSearchFindsTitleLocationDescriptionAndOrdersUpcomingFirst() {
        event("Weekly sync", "2026-09-01T10:00:00+08:00", extra = ""","recurrence":"weekly"""")
        event("Old sync", "2026-09-20T10:00:00+08:00")
        event("Offsite", "2026-10-20T10:00:00+08:00", extra = ""","description":"Sync topics"""")
        event("Dinner", "2026-10-21T19:00:00+08:00", extra = ""","location":"Syncopated Bistro"""")
        event("Unrelated", "2026-10-22T19:00:00+08:00")
        val r = ok("event_search", """{"query":"sync"}""")
        assertEquals(listOf("Weekly sync", "Offsite", "Dinner", "Old sync"), r.arr("events").titles())
        assertEquals("next occurrence of the series", "2026-10-13", r.arr("events")[0].jsonObject.s("start").take(10))
        assertEquals(1, ok("event_search", """{"query":"sync","limit":1}""").arr("events").size)
        assertEquals(0, ok("event_search", """{"query":"zzz"}""").arr("events").size)
        fails("event_search", "{}", "query")
        fails("event_search", """{"query":"  "}""", "query")
        fails("event_search", """{"query":"x","limit":0}""", "limit")
    }

    // ---- agenda_today ----

    @Test fun agendaTodayIsSortedAndScopedToToday() {
        event("Lunch", "2026-10-07T12:00:00+08:00")
        event("Standup", "2026-10-07T09:30:00+08:00", "2026-10-07T10:00:00+08:00")
        event("Tomorrow", "2026-10-08T09:00:00+08:00")
        event("Holiday", "2026-10-07", extra = ""","all_day":true""")
        event("Daily", "2026-10-01T18:00:00+08:00", extra = ""","recurrence":"daily"""")
        event("Night shift", "2026-10-06T22:00:00+08:00", "2026-10-07T02:00:00+08:00")
        val r = ok("agenda_today")
        assertEquals("2026-10-07", r.s("date"))
        assertEquals("strictly by start time", listOf("Night shift", "Holiday", "Standup", "Lunch", "Daily"), r.arr("events").titles())
        val cal = ok("calendar_create", """{"name":"Work"}""").s("id")
        event("Work thing", "2026-10-07T14:00:00+08:00", extra = ""","calendar_id":"$cal"""")
        assertEquals(listOf("Work thing"), ok("agenda_today", """{"calendar_id":"$cal"}""").arr("events").titles())
        fails("agenda_today", """{"calendar_id":"nope"}""", "not found")
        time.now = ms("2026-10-07T23:30:00+08:00")
        assertEquals("still today late in the evening", 6, ok("agenda_today").arr("events").size)
        time.zone = ZoneId.of("America/Los_Angeles")
        assertEquals("2026-10-07", ok("agenda_today").s("date"))
    }

    // ---- free_slots ----

    private fun slots(json: String): List<Pair<String, String>> =
        ok("free_slots", json).arr("slots").map { it.jsonObject.let { o -> o.s("start").substring(11, 16) to o.s("end").substring(11, 16) } }

    @Test fun freeSlotsBasic() {
        event("A", "2026-10-08T10:00:00+08:00", "2026-10-08T11:00:00+08:00")
        event("B", "2026-10-08T14:00:00+08:00", "2026-10-08T15:30:00+08:00")
        assertEquals(listOf("09:00" to "10:00", "11:00" to "14:00", "15:30" to "18:00"), slots("""{"date":"2026-10-08","duration_minutes":60}"""))
        assertEquals(listOf("11:00" to "14:00", "15:30" to "18:00"), slots("""{"date":"2026-10-08","duration_minutes":90}"""))
        assertEquals(listOf("11:00" to "14:00"), slots("""{"date":"2026-10-08","duration_minutes":160}"""))
        assertEquals(emptyList<Pair<String, String>>(), slots("""{"date":"2026-10-08","duration_minutes":300}"""))
        val r = ok("free_slots", """{"date":"2026-10-08","duration_minutes":60}""")
        assertEquals(2, r.arr("busy").size)
        assertEquals(60, r.arr("slots")[0].jsonObject["minutes"]!!.jsonPrimitive.int)
        assertEquals("2026-10-08T09:00:00+08:00", r["window"]!!.jsonObject.s("start"))
    }

    @Test fun freeSlotsCustomWindowAndAdjacentEvents() {
        event("A", "2026-10-08T10:00:00+08:00", "2026-10-08T11:00:00+08:00")
        event("B", "2026-10-08T11:00:00+08:00", "2026-10-08T12:00:00+08:00")
        assertEquals(listOf("08:00" to "10:00", "12:00" to "20:00"), slots("""{"date":"2026-10-08","duration_minutes":30,"day_start":"08:00","day_end":"20:00"}"""))
        val late = ok("free_slots", """{"date":"2026-10-08","duration_minutes":30,"day_start":"12:00","day_end":"24:00"}""")
        assertEquals("2026-10-09T00:00:00+08:00", late["window"]!!.jsonObject.s("end"))
        assertEquals(1, late.arr("slots").size)
    }

    @Test fun freeSlotsCountRecurringAndMidnightCrossingEvents() {
        event("Daily standup", "2026-10-01T09:00:00+08:00", "2026-10-01T09:30:00+08:00", extra = ""","recurrence":"daily"""")
        event("Thursday class", "2026-09-03T13:00:00+08:00", "2026-09-03T14:00:00+08:00", extra = ""","recurrence":"weekly"""") // Thursdays
        event("Red-eye", "2026-10-07T22:00:00+08:00", "2026-10-08T09:45:00+08:00")
        // 10 月 8 日是周四：red-eye 占到 09:45，standup 09:00-09:30（已被 red-eye 覆盖），课 13:00-14:00
        assertEquals(listOf("09:45" to "13:00", "14:00" to "18:00"), slots("""{"date":"2026-10-08","duration_minutes":30}"""))
        // 10 月 9 日：只剩 standup，red-eye 已结束
        assertEquals(listOf("09:30" to "18:00"), slots("""{"date":"2026-10-09","duration_minutes":30}"""))
        // 夜班的前半段也要算：10 月 7 日 20:00-24:00
        assertEquals(listOf("20:00" to "22:00"), slots("""{"date":"2026-10-07","duration_minutes":30,"day_start":"20:00","day_end":"24:00"}"""))
    }

    @Test fun freeSlotsAllDayAndCalendarFilter() {
        event("Holiday", "2026-10-08", extra = ""","all_day":true""")
        assertEquals(listOf("09:00" to "18:00"), slots("""{"date":"2026-10-08","duration_minutes":60}"""))
        assertEquals(emptyList<Pair<String, String>>(), slots("""{"date":"2026-10-08","duration_minutes":60,"include_all_day":true}"""))
        val work = ok("calendar_create", """{"name":"Work"}""").s("id")
        event("Work meeting", "2026-10-08T10:00:00+08:00", "2026-10-08T11:00:00+08:00", extra = ""","calendar_id":"$work"""")
        assertEquals(listOf("09:00" to "10:00", "11:00" to "18:00"), slots("""{"date":"2026-10-08","duration_minutes":30}"""))
        assertEquals(listOf("09:00" to "18:00"), slots("""{"date":"2026-10-08","duration_minutes":30,"calendar_id":"$defaultCal"}"""))
    }

    @Test fun freeSlotsOnADaylightSavingDayUsesRealClockTime() {
        time.zone = ZoneId.of("America/New_York")
        val r = ok("free_slots", """{"date":"2026-03-08","duration_minutes":60,"day_start":"00:00","day_end":"06:00"}""")
        val w = r["window"]!!.jsonObject
        assertEquals("2026-03-08T00:00:00-05:00", w.s("start"))
        assertEquals("2026-03-08T06:00:00-04:00", w.s("end"))
        assertEquals("only 5 real hours in this window", 300, r.arr("slots").single().jsonObject["minutes"]!!.jsonPrimitive.int)
    }

    @Test fun freeSlotsRejectsBadInput() {
        fails("free_slots", """{"duration_minutes":30}""", "date")
        fails("free_slots", """{"date":"2026-10-08"}""", "duration_minutes")
        fails("free_slots", """{"date":"2026-10-08","duration_minutes":0}""", "duration_minutes")
        fails("free_slots", """{"date":"2026-10-08","duration_minutes":1441}""", "duration_minutes")
        fails("free_slots", """{"date":"2026-10-08","duration_minutes":"half an hour"}""", "duration_minutes")
        fails("free_slots", """{"date":"next friday","duration_minutes":30}""", "date")
        fails("free_slots", """{"date":"2026-10-08","duration_minutes":30,"day_start":"9am"}""", "day_start")
        fails("free_slots", """{"date":"2026-10-08","duration_minutes":30,"day_end":"25:00"}""", "day_end")
        fails("free_slots", """{"date":"2026-10-08","duration_minutes":30,"day_start":"18:00","day_end":"09:00"}""", "day_end")
        fails("free_slots", """{"date":"2026-10-08","duration_minutes":30,"calendar_id":"nope"}""", "not found")
    }

    @Test fun handlerErrorsNeverEscape() {
        // 非对象参数、未知字段都不应该抛出
        assertFalse(call("event_list", """{"unknown":123}""").isError)
        assertTrue(call("event_get", """{"id":123}""").isError)
        assertEquals(12, tools.all().size)
    }
}
