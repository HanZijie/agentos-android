package org.agentos.sample.sms.agentos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleItemsTest {
    private val eventJson =
        """{"id":"e1","series_id":"e1","title":"和王总开会","start":"2026-10-09T15:00:00+08:00","end":"2026-10-09T16:00:00+08:00","all_day":false,"location":"3 号会议室","timezone":"Asia/Shanghai"}"""
    private val alarmJson =
        """{"id":"a1","time":"07:00","label":"跑步","days":["mon","wed"],"repeat":"weekly","enabled":true,"next_fire_at":"2026-10-12T07:00:00+08:00"}"""

    private fun call(id: String, tool: String, status: ToolStatus, result: String? = null) = GatewayEvent.ToolCall(id, tool, status, result)

    private fun fold(vararg events: GatewayEvent.ToolCall): List<ScheduleItem> = events.fold(emptyList()) { acc, e -> ScheduleItems.apply(acc, e) }

    @Test fun `kinds come from the tool name, with or without a prefix`() {
        assertEquals(ItemKind.EVENT, ScheduleItems.kindOf("event_create"))
        assertEquals(ItemKind.EVENT, ScheduleItems.kindOf("mcp__calendar__event_create"))
        assertEquals(ItemKind.ALARM, ScheduleItems.kindOf("alarm_create"))
        assertEquals(ItemKind.ALARM, ScheduleItems.kindOf("mcp__alarm__alarm_create"))
        assertEquals(ItemKind.TODO, ScheduleItems.kindOf("todo_create"))
        assertEquals(ItemKind.TODO, ScheduleItems.kindOf("mcp__todo__todo_create"))
        assertEquals(ItemKind.TODO, ScheduleItems.kindOf("anything", ToolRef("todo", "todo_create")))
        assertEquals(ItemKind.OTHER, ScheduleItems.kindOf("todo_delete"))
        assertEquals(ItemKind.OTHER, ScheduleItems.kindOf("my_todo_create"))
        assertEquals(ItemKind.OTHER, ScheduleItems.kindOf("note_delete"))
        assertEquals(ItemKind.OTHER, ScheduleItems.kindOf("event_create_all"))
        assertEquals(ItemKind.OTHER, ScheduleItems.kindOf("my_event_create"))
        assertEquals(ItemKind.ALARM, ScheduleItems.kindOf("anything", ToolRef("alarm", "alarm_create")))
        assertEquals("a ref wins over a misleading name", ItemKind.OTHER, ScheduleItems.kindOf("mcp__x__event_create", ToolRef("notes", "note_delete")))
    }

    @Test fun `while waiting the card previews the arguments, and the result replaces them`() {
        val args = """{"title":"和王总开会（模型的写法）","start":"2026-10-09T15:00:00","location":"3 号会议室"}"""
        val waiting = fold(GatewayEvent.ToolCall("e1", "event_create", ToolStatus.PENDING_APPROVAL, null, argumentsJson = args, ref = ToolRef("calendar", "event_create")))
        assertEquals("和王总开会（模型的写法）", waiting.single().event!!.title)
        assertEquals(ItemStatus.AWAITING_APPROVAL, waiting.single().status)
        assertEquals("a preview is not a created item", 0, ScheduleSummary(waiting).eventCount)
        val done = ScheduleItems.apply(waiting, GatewayEvent.ToolCall("e1", "event_create", ToolStatus.COMPLETED, eventJson, argumentsJson = args, ref = ToolRef("calendar", "event_create")))
        assertEquals("the tool's own result wins", "和王总开会", done.single().event!!.title)
        val denied = ScheduleItems.apply(waiting, GatewayEvent.ToolCall("e1", "event_create", ToolStatus.DENIED, "denied by user"))
        assertEquals("a refused card still shows what was refused", "和王总开会（模型的写法）", denied.single().event!!.title)
        assertEquals(0, ScheduleSummary(denied).eventCount)
    }

    @Test fun `alarm arguments preview the same way, and broken arguments are ignored`() {
        val a = fold(GatewayEvent.ToolCall("a1", "alarm_create", ToolStatus.RUNNING, null, argumentsJson = """{"time":"07:00","label":"跑步","days":["mon"]}""", ref = ToolRef("alarm", "alarm_create")))
        assertEquals("07:00", a.single().alarm!!.time)
        val bad = fold(GatewayEvent.ToolCall("a2", "alarm_create", ToolStatus.RUNNING, null, argumentsJson = "not json", ref = ToolRef("alarm", "alarm_create")))
        assertNull(bad.single().alarm)
    }

    @Test fun `every tool status maps to an item status`() {
        assertEquals(ItemStatus.AWAITING_APPROVAL, ScheduleItems.statusOf(ToolStatus.PENDING_APPROVAL))
        assertEquals(ItemStatus.CREATING, ScheduleItems.statusOf(ToolStatus.RUNNING))
        assertEquals(ItemStatus.CREATED, ScheduleItems.statusOf(ToolStatus.COMPLETED))
        assertEquals(ItemStatus.DENIED, ScheduleItems.statusOf(ToolStatus.DENIED))
        assertEquals(ItemStatus.FAILED, ScheduleItems.statusOf(ToolStatus.FAILED))
    }

    @Test fun `an event result gives title start and end`() {
        val info = ScheduleItems.parseEvent(eventJson)!!
        assertEquals("和王总开会", info.title)
        assertEquals("2026-10-09T15:00:00+08:00", info.start)
        assertEquals("2026-10-09T16:00:00+08:00", info.end)
    }

    @Test fun `an all-day event is flagged`() {
        val info = ScheduleItems.parseEvent("""{"title":"出差","start":"2026-10-12T00:00:00+08:00","end":"2026-10-14T23:59:59+08:00","all_day":true}""")!!
        assertTrue(info.allDay)
        assertFalse(ScheduleItems.parseEvent(eventJson)!!.allDay)
    }

    @Test fun `times are read with their own offset, local time or just a date`() {
        val zone = java.time.ZoneId.of("Asia/Tokyo")
        val a = ScheduleItems.parseWhen("2026-10-09T15:00:00+08:00", zone)!!
        assertEquals("+08:00", a.offset)
        assertEquals(java.time.OffsetDateTime.parse("2026-10-09T07:00:00Z").toInstant().toEpochMilli(), a.millis)
        assertFalse(a.dateOnly)
        assertEquals("+00:00", ScheduleItems.parseWhen("2026-10-09T07:00:00Z", zone)!!.offset)
        val local = ScheduleItems.parseWhen("2026-10-09T15:00:00", zone)!!
        assertEquals("+09:00", local.offset)
        assertEquals(java.time.OffsetDateTime.parse("2026-10-09T06:00:00Z").toInstant().toEpochMilli(), local.millis)
        val day = ScheduleItems.parseWhen("2026-10-09", zone)!!
        assertTrue(day.dateOnly)
        for (bad in listOf(null, "", "tomorrow", "2026-13-40", "15:00")) assertNull(bad, ScheduleItems.parseWhen(bad, zone))
    }

    @Test fun `an alarm result gives time label and days`() {
        val info = ScheduleItems.parseAlarm(alarmJson)!!
        assertEquals("07:00", info.time)
        assertEquals("跑步", info.label)
        assertEquals(listOf("mon", "wed"), info.days)
        val once = ScheduleItems.parseAlarm("""{"time":"06:30","label":"","days":[]}""")!!
        assertEquals("", once.label)
        assertTrue(once.days.isEmpty())
    }

    @Test fun `results that are not the expected json give no info instead of throwing`() {
        for (bad in listOf(null, "", "ok", "[1,2]", "{", """{"title":5}""", """{"nothing":"here"}""", "null")) {
            assertNull("event from $bad", ScheduleItems.parseEvent(bad))
            assertNull("alarm from $bad", ScheduleItems.parseAlarm(bad))
        }
        assertNull(ScheduleItems.parseAlarm("""{"title":"x"}"""))
        assertEquals(EventInfo("x", null, null), ScheduleItems.parseEvent("""{"title":"x"}"""))
    }

    @Test fun `a full life of one item updates it in place`() {
        val items = fold(
            call("e1", "event_create", ToolStatus.PENDING_APPROVAL),
            call("e1", "event_create", ToolStatus.RUNNING),
            call("e1", "event_create", ToolStatus.COMPLETED, eventJson),
        )
        val e = items.single()
        assertEquals(ItemStatus.CREATED, e.status)
        assertEquals(ItemKind.EVENT, e.kind)
        assertEquals("和王总开会", e.event!!.title)
    }

    @Test fun `an item may start already running or already completed, with no approval step`() {
        val running = fold(call("e1", "event_create", ToolStatus.RUNNING), call("e1", "event_create", ToolStatus.COMPLETED, eventJson))
        assertEquals(ItemStatus.CREATED, running.single().status)
        assertEquals("和王总开会", running.single().event!!.title)
        val direct = fold(call("a1", "alarm_create", ToolStatus.COMPLETED, alarmJson))
        assertEquals(ItemStatus.CREATED, direct.single().status)
        assertEquals("07:00", direct.single().alarm!!.time)
        val summary = ScheduleSummary(running + direct)
        assertEquals(1, summary.eventCount)
        assertEquals(1, summary.alarmCount)
    }

    @Test fun `items keep their first-seen order`() {
        val items = fold(
            call("e1", "event_create", ToolStatus.PENDING_APPROVAL),
            call("a1", "alarm_create", ToolStatus.PENDING_APPROVAL),
            call("e1", "event_create", ToolStatus.COMPLETED, eventJson),
            call("a1", "alarm_create", ToolStatus.COMPLETED, alarmJson),
        )
        assertEquals(listOf("e1", "a1"), items.map { it.id })
        assertEquals(listOf(ItemStatus.CREATED, ItemStatus.CREATED), items.map { it.status })
    }

    @Test fun `a late in-progress event never reverts a final status`() {
        val items = fold(
            call("e1", "event_create", ToolStatus.COMPLETED, eventJson),
            call("e1", "event_create", ToolStatus.RUNNING),
            call("e1", "event_create", ToolStatus.PENDING_APPROVAL),
        )
        assertEquals(ItemStatus.CREATED, items.single().status)
        assertNotNull(items.single().event)
    }

    @Test fun `a completed event without a result keeps what was parsed before and does not invent anything`() {
        val bare = fold(call("e1", "event_create", ToolStatus.COMPLETED))
        assertEquals(ItemStatus.CREATED, bare.single().status)
        assertNull(bare.single().event)
    }

    @Test fun `denied and failed carry the one-line reason`() {
        val items = fold(
            call("e1", "event_create", ToolStatus.DENIED, "denied by user"),
            call("a1", "alarm_create", ToolStatus.FAILED, """{"error":"time must be HH:mm"}"""),
            call("a2", "alarm_create", ToolStatus.FAILED, "x".repeat(500)),
        )
        assertEquals(ItemStatus.DENIED, items[0].status)
        assertEquals("denied by user", items[0].message)
        assertEquals("time must be HH:mm", items[1].message)
        assertEquals(200, items[2].message!!.length)
    }

    @Test fun `a failed result does not count as created even if it looks like an event`() {
        val items = fold(call("e1", "event_create", ToolStatus.FAILED, eventJson))
        assertNull(items.single().event)
        assertEquals(0, ScheduleSummary(items).eventCount)
    }

    @Test fun `unfinished items become cancelled when the round ends`() {
        val items = fold(
            call("e1", "event_create", ToolStatus.COMPLETED, eventJson),
            call("a1", "alarm_create", ToolStatus.PENDING_APPROVAL),
            call("a2", "alarm_create", ToolStatus.RUNNING),
            call("a3", "alarm_create", ToolStatus.DENIED),
        )
        val final = ScheduleItems.finalize(items)
        assertEquals(
            listOf(ItemStatus.CREATED, ItemStatus.CANCELLED, ItemStatus.CANCELLED, ItemStatus.DENIED),
            final.map { it.status },
        )
        assertTrue(final.all { it.status.isFinal })
    }

    @Test fun `summary counts only created items, by kind`() {
        val items = fold(
            call("e1", "event_create", ToolStatus.COMPLETED, eventJson),
            call("e2", "event_create", ToolStatus.COMPLETED, eventJson),
            call("a1", "alarm_create", ToolStatus.COMPLETED, alarmJson),
            call("a2", "alarm_create", ToolStatus.DENIED, "no"),
            call("e3", "event_create", ToolStatus.FAILED, "boom"),
            call("x1", "note_delete", ToolStatus.COMPLETED, "{}"),
        )
        val s = ScheduleSummary(items)
        assertEquals(2, s.eventCount)
        assertEquals(1, s.alarmCount)
        assertEquals(4, s.createdCount) // 含那个不该出现的 note_delete：它也如实列出来，不藏
        assertEquals(1, s.deniedCount)
        assertEquals(1, s.failedCount)
        assertFalse(s.isEmpty)
        assertTrue(ScheduleSummary(emptyList()).isEmpty)
    }

    @Test fun `a todo result gives title and due, and a todo is counted on its own`() {
        val dated = """{"id":"t1","title":"写 PRD：搜索","status":"todo","priority":"high","due":"2026-10-14","due_all_day":true}"""
        val timed = """{"id":"t2","title":"Send the report","due":"2026-10-16T17:00:00+08:00"}"""
        val undated = """{"id":"t3","title":"Read the spec"}"""
        assertEquals(TodoInfo("写 PRD：搜索", "2026-10-14"), ScheduleItems.parseTodo(dated))
        assertEquals("2026-10-16T17:00:00+08:00", ScheduleItems.parseTodo(timed)!!.due)
        assertNull("no due date is fine", ScheduleItems.parseTodo(undated)!!.due)
        assertNull(ScheduleItems.parseTodo("""{"id":"t4"}"""))
        assertNull(ScheduleItems.parseTodo("not json"))
        val items = fold(
            call("e1", "event_create", ToolStatus.COMPLETED, eventJson),
            call("t1", "todo_create", ToolStatus.COMPLETED, dated),
            call("t2", "todo_create", ToolStatus.COMPLETED, timed),
            call("t3", "todo_create", ToolStatus.DENIED, "no"),
            call("t4", "todo_create", ToolStatus.FAILED, """{"error":"due must carry a UTC offset"}"""),
        )
        val s = ScheduleSummary(items)
        assertEquals(2, s.todoCount)
        assertEquals(1, s.eventCount)
        assertEquals(0, s.alarmCount)
        assertEquals(3, s.createdCount)
        assertEquals("a todo item carries its title for the card", "写 PRD：搜索", s.createdTodos.first().todo!!.title)
        assertEquals(1, s.deniedCount)
        assertEquals(1, s.failedCount)
    }

    @Test fun `while a todo waits for approval its arguments are previewed, and a refusal keeps the preview`() {
        val args = """{"title":"写三份 PRD","due":"2026-10-14","priority":"high"}"""
        val ref = ToolRef("todo", "todo_create")
        val waiting = fold(GatewayEvent.ToolCall("t1", "todo_create", ToolStatus.PENDING_APPROVAL, null, argumentsJson = args, ref = ref))
        assertEquals("写三份 PRD", waiting.single().todo!!.title)
        assertEquals(0, ScheduleSummary(waiting).todoCount)
        val denied = ScheduleItems.apply(waiting, GatewayEvent.ToolCall("t1", "todo_create", ToolStatus.DENIED, "denied by user"))
        assertEquals("写三份 PRD", denied.single().todo!!.title)
        assertEquals(0, ScheduleSummary(denied).todoCount)
    }

    @Test fun `the summary never reads the model's words`() {
        // 模型文字说“已创建 3 个日程”，但工具事件只有一个：汇总只看工具事件
        val items = fold(call("e1", "event_create", ToolStatus.COMPLETED, eventJson))
        assertEquals(1, ScheduleSummary(items).eventCount)
    }
}
