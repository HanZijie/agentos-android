package org.agentos.sample.sms.agentos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 真网关的映射层：SDK 的类型和本 App 的类型一一对应（枚举名相同），工具名优先用 toolScope 里的原始名字。 */
class SdkMappingTest {
    /** 这个 App 不用的会话 / MCP 能力带来的错误：并入 FAILED（界面没有单独的文案）。 */
    private val foldedIntoFailed = setOf("SESSION_NOT_FOUND", "INVALID_REQUEST", "UNSUPPORTED")

    @Test fun `every sdk error maps to the error with the same name, except the ones this app folds into FAILED`() {
        for (e in org.agentos.acp.AgentOsError.entries) {
            assertEquals(if (e.name in foldedIntoFailed) "FAILED" else e.name, SdkMapping.fromSdk(e).name)
        }
        assertEquals(org.agentos.acp.AgentOsError.entries.size - foldedIntoFailed.size, AgentOsError.entries.size)
    }

    @Test fun `thoughts and replayed user messages are skipped, never shown as the answer`() {
        org.junit.Assert.assertNull(SdkMapping.fromSdk(org.agentos.acp.AgentOsEvent.Thought("hmm")))
        org.junit.Assert.assertNull(SdkMapping.fromSdk(org.agentos.acp.AgentOsEvent.UserMessage("hi")))
    }

    @Test fun `every sdk tool status maps to the status with the same name`() {
        for (s in org.agentos.acp.ToolStatus.entries) assertEquals(s.name, SdkMapping.fromSdk(s).name)
        assertEquals(org.agentos.acp.ToolStatus.entries.size, ToolStatus.entries.size)
    }

    @Test fun `the tool scope goes over as plugin and original tool name`() {
        val ref = SdkMapping.toSdk(SmsToolScope[0])
        assertEquals("alarm", ref.plugin)
        assertEquals("alarm_create", ref.tool)
        assertEquals(listOf("alarm_create", "event_create", "todo_create"), SmsToolScope.map { it.tool })
        assertEquals(listOf("alarm", "calendar", "todo"), SmsToolScope.map { it.plugin })
    }

    @Test fun `events map field by field`() {
        assertEquals(GatewayEvent.Text("你好"), SdkMapping.fromSdk(org.agentos.acp.AgentOsEvent.Text("你好")))
        assertEquals(GatewayEvent.Done("end_turn"), SdkMapping.fromSdk(org.agentos.acp.AgentOsEvent.Done("end_turn")))
        val call = org.agentos.acp.AgentOsEvent.ToolCall(
            "c1", "mcp__calendar__event_create", org.agentos.acp.ToolStatus.COMPLETED, "{\"title\":\"x\"}",
            argumentsJson = "{\"title\":\"x\"}", ref = org.agentos.acp.ToolRef("calendar", "event_create"),
        )
        assertEquals(
            GatewayEvent.ToolCall("c1", "mcp__calendar__event_create", ToolStatus.COMPLETED, "{\"title\":\"x\"}", "{\"title\":\"x\"}", ToolRef("calendar", "event_create")),
            SdkMapping.fromSdk(call),
        )
        val noRef = org.agentos.acp.AgentOsEvent.ToolCall("c2", "mcp__other__thing", org.agentos.acp.ToolStatus.RUNNING, null)
        assertEquals(GatewayEvent.ToolCall("c2", "mcp__other__thing", ToolStatus.RUNNING, null, null, null), SdkMapping.fromSdk(noRef))
    }

    @Test fun `the kind comes from the scope reference first, then from the tool name`() {
        val withRef = SdkMapping.fromSdk(org.agentos.acp.AgentOsEvent.ToolCall("c", "whatever_final_name", org.agentos.acp.ToolStatus.COMPLETED, null, null, org.agentos.acp.ToolRef("alarm", "alarm_create"))) as GatewayEvent.ToolCall
        assertEquals(ItemKind.ALARM, ScheduleItems.kindOf(withRef.tool, withRef.ref))
        val prefixed = SdkMapping.fromSdk(org.agentos.acp.AgentOsEvent.ToolCall("c", "mcp__calendar__event_create", org.agentos.acp.ToolStatus.COMPLETED, null)) as GatewayEvent.ToolCall
        assertEquals(ItemKind.EVENT, ScheduleItems.kindOf(prefixed.tool, prefixed.ref))
    }

    @Test fun `exceptions keep their error and message and the cause`() {
        val cause = org.agentos.acp.AgentOsException(org.agentos.acp.AgentOsError.NO_MODEL, "model_not_configured")
        val mapped = SdkMapping.fromSdk(cause)
        assertEquals(AgentOsError.NO_MODEL, mapped.error)
        assertEquals("model_not_configured", mapped.message)
        assertSame(cause, mapped.cause)
    }

    @Test fun `an unimplemented sdk is a plain failure, not a crash`() {
        val mapped = SdkMapping.notImplemented(NotImplementedError("An operation is not implemented: C8"))
        assertEquals(AgentOsError.FAILED, mapped.error)
        assertTrue(mapped.message!!.contains("not available"))
    }
}
