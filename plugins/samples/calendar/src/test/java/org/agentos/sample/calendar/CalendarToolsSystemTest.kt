package org.agentos.sample.calendar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.agentos.sample.calendar.data.CalendarSource
import org.agentos.sample.calendar.data.Recurrence
import org.agentos.sample.calendar.tools.CalendarTools
import org.agentos.sample.calendar.tools.ToolOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** C4/C5/C7 在工具层的表现：账号 / 来源 / 可写、默认写入日历、账号日历不能建删改名、权限缺失、custom 重复、只读。MCP 契约（名字、必填参数）不变。 */
class CalendarToolsSystemTest {
    private lateinit var system: FakeSystemBackend
    private lateinit var repo: org.agentos.sample.calendar.data.CalendarRepository
    private lateinit var tools: CalendarTools
    private val permMsg = "Calendar permission not granted; ask the user to grant it in the Calendar app"

    @Before fun setUp() {
        val time = FakeTime()
        system = FakeSystemBackend(time = time)
        system.addCalendar(1, "Work (Google)", "me@example.com", "com.google", CalendarSource.GOOGLE)
        system.addCalendar(2, "Team (read-only)", "me@corp.example", "bitfire.at.davdroid", CalendarSource.CALDAV, writable = false)
        system.addCalendar(3, "Device only", "Phone", "LOCAL", CalendarSource.LOCAL)
        repo = newRepo(time = time, system = system)
        tools = CalendarTools(repo, Dispatchers.Unconfined)
    }

    private fun call(name: String, json: String = "{}"): ToolOutput = runBlocking { tools.all().first { it.name == name }.handler(Json.parseToJsonElement(json).jsonObject) }

    private fun ok(name: String, json: String = "{}"): JsonObject {
        val out = call(name, json)
        assertFalse("$name failed: ${out.text}", out.isError)
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
    private fun JsonObject.b(k: String) = this[k]!!.jsonPrimitive.boolean

    private fun cal(list: JsonObject, id: String) = list.arr("calendars").map { it.jsonObject }.first { it.s("id") == id }

    // ---- calendar_list ----

    @Test fun calendarListTellsWhereEachCalendarLives() {
        val list = ok("calendar_list")
        assertEquals(4, list["total"]!!.jsonPrimitive.content.toInt())
        val g = cal(list, "sys:1")
        assertEquals("me@example.com", g.s("account"))
        assertEquals("com.google", g.s("account_type"))
        assertEquals("google", g.s("source"))
        assertTrue(g.b("writable"))
        assertEquals("system", g.s("storage"))
        val c = cal(list, "sys:2")
        assertEquals("caldav", c.s("source"))
        assertFalse(c.b("writable"))
        assertEquals("a LOCAL-type calendar of the system database is 'local' but still lives in the system store", "local", cal(list, "sys:3").s("source"))
        assertEquals("system", cal(list, "sys:3").s("storage"))
        val mine = cal(list, "local:id1")
        assertEquals("local", mine.s("source"))
        assertEquals("app", mine.s("storage"))
        assertEquals("", mine.s("account"))
        assertTrue(mine.b("writable"))
        // 原有字段都还在
        for (k in listOf("id", "name", "color", "visible", "is_default", "event_count")) assertTrue("$k kept", mine.containsKey(k))
    }

    @Test fun isDefaultMarksTheDefaultWriteCalendarNotTheUndeletableLocalOne() {
        val list = ok("calendar_list")
        val defaults = list.arr("calendars").map { it.jsonObject }.filter { it.b("is_default") }
        assertEquals(listOf("sys:1"), defaults.map { it.s("id") })
        repo.setDefaultWriteCalendar("local:id1")
        assertEquals(listOf("local:id1"), ok("calendar_list").arr("calendars").map { it.jsonObject }.filter { it.b("is_default") }.map { it.s("id") })
    }

    @Test fun anUnknownAccountTypeIsOther() {
        system.addCalendar(8, "Exchange", "me@corp.example", "com.android.exchange", CalendarSource.OTHER)
        val list = ok("calendar_list")
        assertEquals("other", cal(list, "sys:8").s("source"))
        assertEquals("com.android.exchange", cal(list, "sys:8").s("account_type"))
    }

    // ---- calendar_create / update / delete ----

    @Test fun calendarCreateOnlyCreatesLocalCalendars() {
        val created = ok("calendar_create", """{"name":"Side project"}""")
        assertEquals("local", created.s("source"))
        assertEquals("app", created.s("storage"))
        assertTrue(created.s("id").startsWith("local:"))
        assertEquals(0, system.cals.count { it.name == "Side project" })
        fails("calendar_create", """{"name":"work (google)"}""", "already exists")
        assertTrue(call("calendar_create").isError)
    }

    @Test fun calendarDeleteRefusesAccountAndSystemCalendars() {
        for (id in listOf("sys:1", "sys:2", "sys:3")) fails("calendar_delete", """{"id":"$id"}""", "cannot be deleted")
        assertEquals(3, system.cals.size)
        fails("calendar_delete", """{"id":"local:id1"}""", "default calendar cannot be deleted")
        val extra = ok("calendar_create", """{"name":"Temp"}""").s("id")
        assertEquals(0, ok("calendar_delete", """{"id":"$extra"}""")["deleted_events"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun accountCalendarsCanBeHiddenButNotRenamed() {
        fails("calendar_update", """{"id":"sys:1","name":"Mine now"}""", "only be shown or hidden")
        assertFalse(ok("calendar_update", """{"id":"sys:1","visible":false}""").b("visible"))
        assertTrue(ok("calendar_update", """{"id":"sys:1","visible":true}""").b("visible"))
    }

    // ---- event_create 的默认写入日历 ----

    @Test fun eventCreateWithoutACalendarGoesToTheFirstWritableVisibleAccountCalendar() {
        val e = ok("event_create", """{"title":"Review","start":"2026-10-14T15:00:00+08:00"}""")
        assertEquals("sys:1", e.s("calendar_id"))
        assertEquals("Work (Google)", e.s("calendar_name"))
        assertTrue(e.s("id").startsWith("sys:"))
        assertEquals(listOf("Review"), system.ownedEvents().map { it.title })
        assertTrue("nothing was written to the local calendar", repo.localEvents.value.isEmpty())
    }

    @Test fun eventCreateFallsBackToTheLocalCalendarWhenNoAccountCalendarQualifies() {
        val only = FakeSystemBackend().also { it.addCalendar(1, "Read-only", writable = false) }
        val r = newRepo(system = only)
        val t = CalendarTools(r, Dispatchers.Unconfined)
        val out = runBlocking { t.all().first { it.name == "event_create" }.handler(Json.parseToJsonElement("""{"title":"X","start":"2026-10-14T15:00:00+08:00"}""").jsonObject) }
        assertFalse(out.text, out.isError)
        assertEquals("local:id1", out.structured!!.s("calendar_id"))
    }

    @Test fun theConfiguredDefaultWriteCalendarWins() {
        repo.setDefaultWriteCalendar("local:id1")
        assertEquals("local:id1", ok("event_create", """{"title":"X","start":"2026-10-14T15:00:00+08:00"}""").s("calendar_id"))
    }

    @Test fun explicitCalendarsAreRespectedAndReadOnlyOnesRefuse() {
        assertEquals("local:id1", ok("event_create", """{"title":"X","start":"2026-10-14T15:00:00+08:00","calendar_id":"local:id1"}""").s("calendar_id"))
        assertEquals("sys:3", ok("event_create", """{"title":"Y","start":"2026-10-14T16:00:00+08:00","calendar_id":"sys:3"}""").s("calendar_id"))
        fails("event_create", """{"title":"Z","start":"2026-10-14T15:00:00+08:00","calendar_id":"sys:2"}""", "read-only")
        fails("event_create", """{"title":"Z","start":"2026-10-14T15:00:00+08:00","calendar_id":"sys:99"}""", "Calendar not found")
    }

    @Test fun recurrenceCustomCannotBeSetThroughTheTools() {
        fails("event_create", """{"title":"X","start":"2026-10-14T15:00:00+08:00","recurrence":"custom"}""", "recurrence must be one of")
        val e = ok("event_create", """{"title":"X","start":"2026-10-14T15:00:00+08:00","recurrence":"weekly"}""")
        fails("event_update", """{"id":"${e.s("id")}","recurrence":"custom"}""", "recurrence must be one of")
    }

    // ---- 权限 ----

    @Test fun withoutPermissionTheToolsAnswerWithTheFixedErrorAndNeverThrow() {
        val sysEvent = ok("event_create", """{"title":"Review","start":"2026-10-14T15:00:00+08:00"}""").s("id")
        system.access = false
        for ((tool, args) in listOf(
            "event_list" to """{}""",
            "event_list" to """{"calendar_id":"sys:1"}""",
            "event_search" to """{"query":"x"}""",
            "agenda_today" to """{}""",
            "free_slots" to """{"date":"2026-10-14","duration_minutes":30}""",
            "event_get" to """{"id":"$sysEvent"}""",
            "event_update" to """{"id":"$sysEvent","title":"x"}""",
            "event_delete" to """{"id":"$sysEvent"}""",
            "event_create" to """{"title":"X","start":"2026-10-14T15:00:00+08:00","calendar_id":"sys:1"}""",
            "calendar_delete" to """{"id":"sys:1"}""",
        )) {
            val out = call(tool, args)
            assertTrue("$tool $args", out.isError)
            assertEquals("$tool $args", permMsg, out.text)
        }
    }

    @Test fun withoutPermissionLocalCalendarsKeepWorking() {
        system.access = false
        val list = ok("calendar_list")
        assertEquals("only the app's own calendars are listed", listOf("local:id1"), list.arr("calendars").map { it.jsonObject.s("id") })
        val e = ok("event_create", """{"title":"Local only","start":"2026-10-14T15:00:00+08:00"}""")
        assertEquals("falls back to the local default calendar", "local:id1", e.s("calendar_id"))
        assertEquals(1, ok("event_list", """{"calendar_id":"local:id1","from":"2026-10-14","to":"2026-10-15"}""").arr("events").size)
        assertEquals(1, ok("event_search", """{"query":"Local","calendar_id":"local:id1"}""").arr("events").size)
        ok("event_update", """{"id":"${e.s("id")}","title":"Renamed"}""")
        ok("event_delete", """{"id":"${e.s("id")}"}""")
    }

    // ---- custom 重复规则 ----

    @Test fun aCustomSeriesIsReadWithItsRuleAndRefusesUpdates() {
        val c = system.seedEvent(customSeries("sys:1"))
        val got = ok("event_get", """{"id":"${c.id}"}""")
        assertEquals("custom", got.s("recurrence"))
        assertEquals("FREQ=MONTHLY;BYDAY=2TU", got.s("rrule"))
        assertNull("no until for a custom rule", got["recurrence_until"]!!.let { if (it is kotlinx.serialization.json.JsonNull) null else it })
        val listed = ok("event_list", """{"from":"2026-10-01","to":"2026-11-01","calendar_id":"sys:1"}""").arr("events").map { it.jsonObject }
        assertEquals("custom", listed.single().s("recurrence"))
        fails("event_update", """{"id":"${c.id}","title":"Renamed"}""", "custom")
        fails("event_update", """{"id":"${c.id}","recurrence":"daily"}""", "custom")
        fails("event_update", """{"id":"${c.id}","reminder_minutes":[10]}""", "custom")
        assertEquals("nothing changed", "Second Tuesday", system.events.getValue(c.id).title)
        assertEquals(Recurrence.CUSTOM, system.events.getValue(c.id).recurrence)
        assertTrue(ok("event_delete", """{"id":"${c.id}"}""")["deleted"]!!.jsonPrimitive.boolean)
    }

    @Test fun plainEventsDoNotCarryAnRruleField() {
        val e = ok("event_create", """{"title":"X","start":"2026-10-14T15:00:00+08:00","recurrence":"weekly"}""")
        assertFalse(e.containsKey("rrule"))
        assertEquals("weekly", e.s("recurrence"))
    }

    // ---- 只读 ----

    @Test fun readOnlyCalendarEventsAreReadableButNotChangeable() {
        val ev = system.seedEvent(timed(id = "", title = "Holiday", start = "2026-10-14T00:00", end = "2026-10-14T23:00", calendarId = "sys:2"))
        assertEquals(1, ok("event_list", """{"calendar_id":"sys:2","from":"2026-10-14","to":"2026-10-15"}""").arr("events").size)
        assertEquals("sys:2", ok("event_get", """{"id":"${ev.id}"}""").s("calendar_id"))
        fails("event_update", """{"id":"${ev.id}","title":"x"}""", "read-only")
        fails("event_delete", """{"id":"${ev.id}"}""", "read-only")
        assertEquals(1, system.events.size)
    }

    // ---- 查询 ----

    @Test fun freeSlotsIgnoresEventsThatAreNotBusy() {
        system.seedEvent(timed(id = "", title = "Busy", start = "2026-10-14T10:00", end = "2026-10-14T11:00", calendarId = "sys:1"))
        system.seedEvent(timed(id = "", title = "Marked free", start = "2026-10-14T13:00", end = "2026-10-14T14:00", calendarId = "sys:1").copy(busy = false))
        val free = ok("free_slots", """{"date":"2026-10-14","duration_minutes":60}""")
        assertEquals(1, free.arr("busy").size)
        assertEquals("Busy", free.arr("busy")[0].jsonObject.s("title"))
        assertEquals(2, free.arr("slots").size)
    }

    @Test fun listSearchAndAgendaSpanLocalAndSystemCalendars() {
        ok("event_create", """{"title":"Sync local","start":"2026-10-07T09:00:00+08:00","calendar_id":"local:id1"}""")
        ok("event_create", """{"title":"Sync system","start":"2026-10-07T14:00:00+08:00"}""")
        val list = ok("event_list", """{"from":"2026-10-07","to":"2026-10-08"}""").arr("events").map { it.jsonObject.s("title") }
        assertEquals(listOf("Sync local", "Sync system"), list)
        assertEquals(setOf("Sync local", "Sync system"), ok("event_search", """{"query":"sync"}""").arr("events").map { it.jsonObject.s("title") }.toSet())
        assertEquals(listOf("Sync system"), ok("event_search", """{"query":"sync","calendar_id":"sys:1"}""").arr("events").map { it.jsonObject.s("title") })
        assertEquals(listOf("Sync local", "Sync system"), ok("agenda_today")["events"]!!.jsonArray.map { it.jsonObject.s("title") })
    }

    // ---- 契约不变 ----

    @Test fun theContractToolNamesRequiredParametersAndAnnotationsAreUnchanged() {
        val byName = tools.all().associateBy { it.name }
        val required = mapOf(
            "calendar_list" to emptyList(), "calendar_create" to listOf("name"), "calendar_delete" to listOf("id"),
            "event_list" to emptyList(), "event_get" to listOf("id"), "event_create" to listOf("title", "start"),
            "event_update" to listOf("id"), "event_delete" to listOf("id"), "event_search" to listOf("query"),
            "agenda_today" to emptyList(), "free_slots" to listOf("date", "duration_minutes"),
        )
        for ((name, req) in required) {
            assertEquals(name, req, byName.getValue(name).inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
        assertTrue(byName.getValue("event_delete").annotations.destructiveHint == true)
        assertTrue(byName.getValue("calendar_delete").annotations.destructiveHint == true)
        assertTrue(byName.getValue("event_update").annotations.idempotentHint == true)
        assertTrue(listOf("calendar_list", "event_list", "event_get", "event_search", "agenda_today", "free_slots").all { byName.getValue(it).annotations.readOnlyHint == true })
        // 描述（英文，给模型看）说清楚账号日历的后果
        assertTrue(byName.getValue("event_delete").description.contains("synced to the account", ignoreCase = true))
        assertTrue(byName.getValue("calendar_create").description.contains("never creates", ignoreCase = true))
        assertTrue(byName.getValue("calendar_delete").description.contains("refused", ignoreCase = true))
        assertTrue(byName.getValue("calendar_list").description.all { it.code < 128 })
        assertTrue("event_search gained an optional calendar_id", byName.getValue("event_search").inputSchema["properties"]!!.jsonObject.containsKey("calendar_id"))
    }
}
