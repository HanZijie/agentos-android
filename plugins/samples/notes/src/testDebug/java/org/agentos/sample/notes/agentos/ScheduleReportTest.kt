package org.agentos.sample.notes.agentos

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** debug 入口返回的汇总：字段、状态名、不泄露备忘全文。 */
class ScheduleReportTest {
    private val source = ScheduleSource.of("n1", "标题", "这是备忘的全文：周五 15:00 开会，密码 hunter2", null)
    private fun call(id: String, tool: String, status: ToolStatus, result: String? = null) = GatewayEvent.ToolCall(id, tool, status, result)
    private fun items(vararg calls: GatewayEvent.ToolCall) = calls.fold(emptyList<ScheduleItem>()) { a, c -> ScheduleItems.apply(a, c) }

    private val event = """{"title":"开会","start":"2026-10-09T15:00:00+08:00","end":"2026-10-09T16:00:00+08:00"}"""
    private val alarm = """{"time":"07:00","label":"跑步","days":["mon"]}"""

    @Test fun `done report lists created items with their fields`() {
        val done = ScheduleState.Done(
            source,
            ScheduleSummary(items(call("e1", "event_create", ToolStatus.COMPLETED, event), call("a1", "alarm_create", ToolStatus.COMPLETED, alarm), call("a2", "alarm_create", ToolStatus.DENIED, "no"))),
            "好了", stopped = false,
        )
        val j = ScheduleReport.of(done, gateway = "fake:success")
        assertEquals("done", j["state"]!!.jsonPrimitive.content)
        assertFalse(j["pending"]!!.jsonPrimitive.boolean)
        assertEquals(1, j["created"]!!.jsonObject["events"]!!.jsonPrimitive.int)
        assertEquals(1, j["created"]!!.jsonObject["alarms"]!!.jsonPrimitive.int)
        assertEquals(1, j["created"]!!.jsonObject["denied"]!!.jsonPrimitive.int)
        val arr = j["items"]!!.jsonArray
        assertEquals(3, arr.size)
        assertEquals("开会", arr[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("created", arr[0].jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("07:00", arr[1].jsonObject["time"]!!.jsonPrimitive.content)
        assertEquals("denied", arr[2].jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("好了", j["agent_text"]!!.jsonPrimitive.content)
    }

    @Test fun `error report names the error and keeps what was created`() {
        val err = ScheduleState.Error(source, AgentOsError.NO_MODEL, "x", ScheduleSummary(items(call("e1", "event_create", ToolStatus.COMPLETED, event))))
        val j = ScheduleReport.of(err)
        assertEquals("error", j["state"]!!.jsonPrimitive.content)
        assertEquals("NO_MODEL", j["error"]!!.jsonPrimitive.content)
        assertEquals(1, j["created"]!!.jsonObject["events"]!!.jsonPrimitive.int)
    }

    @Test fun `the report never contains the note text`() {
        val running = ScheduleState.Running(source, items(call("e1", "event_create", ToolStatus.PENDING_APPROVAL)), "")
        for (s in listOf<ScheduleState>(running, ScheduleState.Ready(source), ScheduleState.Error(source, AgentOsError.FAILED), ScheduleState.Done(source, ScheduleSummary(emptyList()), "", false))) {
            val text = ScheduleReport.of(s).toString()
            assertFalse(text, text.contains("hunter2"))
            assertFalse(text, text.contains("这是备忘的全文"))
            assertEquals(source.text.length, ScheduleReport.of(s)["text_chars"]!!.jsonPrimitive.int)
        }
    }

    @Test fun `a running report is pending and every state has a name`() {
        val running = ScheduleState.Running(source, items(call("e1", "event_create", ToolStatus.RUNNING)), "")
        val j = ScheduleReport.of(running, pending = true)
        assertTrue(j["pending"]!!.jsonPrimitive.boolean)
        assertEquals("running", j["state"]!!.jsonPrimitive.content)
        assertEquals("creating", j["items"]!!.jsonArray[0].jsonObject["status"]!!.jsonPrimitive.content)
        val names = listOf(ScheduleState.Idle, ScheduleState.Ready(source), ScheduleState.Checking(source), ScheduleState.WaitingAuthorization(source), running).map(ScheduleReport::stateName)
        assertEquals(listOf("idle", "ready", "checking", "waiting_authorization", "running"), names)
    }

    @Test fun `status before any run says none, and long agent text is capped`() {
        val none = ScheduleReport.status(null, ScheduleState.Idle)
        assertEquals("none", none["state"]!!.jsonPrimitive.content)
        assertFalse(none["pending"]!!.jsonPrimitive.boolean)
        val long = ScheduleState.Done(source, ScheduleSummary(emptyList()), "字".repeat(5_000), false)
        assertEquals(ScheduleReport.MAX_AGENT_TEXT, ScheduleReport.of(long)["agent_text"]!!.jsonPrimitive.content.length)
        val st = ScheduleReport.status(long, ScheduleState.Idle, timedOut = true)
        assertTrue(st["timed_out"]!!.jsonPrimitive.boolean)
        assertEquals("idle", st["current_state"]!!.jsonPrimitive.content)
    }

    @Test fun `report is a flat json object without secrets-like keys`() {
        val j: JsonObject = ScheduleReport.of(ScheduleState.Done(source, ScheduleSummary(emptyList()), "", false))
        assertTrue(j.keys.none { it.contains("key", true) || it.contains("token", true) || it.contains("secret", true) })
        assertTrue(j["items"] is JsonArray)
    }
}
