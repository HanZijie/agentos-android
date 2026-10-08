@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.agentclientprotocol.protocol.JsonRpcException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** AgentOs SDK 里不依赖 Android 的部分：授权重试、错误映射、toolScope、事件映射（docs/third-party-acp.md 4.7）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentOsMappingTest {

    private val pending = SecurityException(AcpServiceContract.message(AcpServiceContract.REASON_AUTHORIZATION_PENDING, "wait"))

    // ------------------------------------------------------------------ 授权重试

    @Test
    fun `retries every second while pending and reports waiting each time`() = runTest {
        val waits = ArrayList<Waiting>()
        var calls = 0
        val result = AgentOsMapping.retryWhilePending(90_000, 1_000, { testScheduler.currentTime }, { waits += it }) {
            if (++calls <= 3) throw pending
            "open"
        }
        assertEquals("open", result)
        assertEquals(4, calls)
        assertEquals(listOf(0L, 1_000L, 2_000L), waits.map { it.elapsedMillis })
        assertTrue(waits.all { it.timeoutMillis == 90_000L })
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test
    fun `gives up after 90 seconds with AUTHORIZATION_PENDING_TIMEOUT`() = runTest {
        var calls = 0
        try {
            AgentOsMapping.retryWhilePending(90_000, 1_000, { testScheduler.currentTime }, {}) { calls++; throw pending }
            fail()
        } catch (e: AgentOsException) {
            assertEquals(AgentOsError.AUTHORIZATION_PENDING_TIMEOUT, e.error)
        }
        assertEquals(90_000L, testScheduler.currentTime)
        assertEquals(91, calls) // t = 0, 1, ... 90 s
    }

    @Test
    fun `any other refusal is not retried`() = runTest {
        for (reason in listOf(AcpServiceContract.REASON_DENIED, AcpServiceContract.REASON_NOT_OPEN, "agentos.acp.other")) {
            var calls = 0
            try {
                AgentOsMapping.retryWhilePending(90_000, 1_000, { testScheduler.currentTime }, {}) {
                    calls++
                    throw SecurityException(AcpServiceContract.message(reason, "x"))
                }
                fail()
            } catch (e: SecurityException) {
                assertEquals(reason, AcpServiceContract.reasonOf(e))
            }
            assertEquals(1, calls)
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a permission denial from the system is not an agentos reason and is not retried`() = runTest {
        var calls = 0
        try {
            AgentOsMapping.retryWhilePending(90_000, 1_000, { testScheduler.currentTime }, {}) { calls++; throw SecurityException("Permission Denial") }
            fail()
        } catch (e: SecurityException) {
            assertNull(AcpServiceContract.reasonOf(e))
        }
        assertEquals(1, calls)
    }

    // ------------------------------------------------------------------ 错误映射

    private fun rpc(code: Int, agentosCode: String?, reason: String? = null) = JsonRpcException(
        code, "message with the user's words",
        data = buildJsonObject {
            if (agentosCode != null) put("agentosCode", agentosCode)
            if (reason != null) put("details", buildJsonObject { put("reason", reason) })
        },
    )

    @Test
    fun `open refusals map to errors`() {
        fun err(reason: String) = AgentOsMapping.fromOpen(SecurityException(AcpServiceContract.message(reason, "x"))).error
        assertEquals(AgentOsError.DENIED, err(AcpServiceContract.REASON_DENIED))
        assertEquals(AgentOsError.NOT_INSTALLED, err(AcpServiceContract.REASON_NOT_OPEN))
        assertEquals(AgentOsError.FAILED, err("agentos.acp.surprise"))
        assertEquals(AgentOsError.FAILED, AgentOsMapping.fromOpen(SecurityException("Permission Denial")).error)
    }

    @Test
    fun `rpc errors map to errors, model_not_configured is NO_MODEL`() {
        assertEquals(AgentOsError.NO_MODEL, AgentOsMapping.fromRpc(rpc(-32051, "model_not_configured")).error)
        assertEquals(AgentOsError.NO_MODEL, AgentOsMapping.fromRpc(rpc(-32051, "model_not_configured", "key_revoked")).error)
        assertEquals(AgentOsError.BUSY, AgentOsMapping.fromRpc(rpc(-32047, "rate_limited", "busy")).error)
        assertEquals(AgentOsError.RATE_LIMITED, AgentOsMapping.fromRpc(rpc(-32048, "rate_limited", "hourly")).error)
        assertEquals(AgentOsError.RATE_LIMITED, AgentOsMapping.fromRpc(rpc(-32048, null)).error)
        assertEquals(AgentOsError.BUSY, AgentOsMapping.fromRpc(rpc(-32047, null)).error)
        assertEquals(AgentOsError.TOO_LARGE, AgentOsMapping.fromRpc(rpc(-32602, "invalid_params", "too_large")).error)
        assertEquals(AgentOsError.TOO_LARGE, AgentOsMapping.fromRpc(rpc(-32046, null)).error)
        assertEquals(AgentOsError.FAILED, AgentOsMapping.fromRpc(rpc(-32602, "invalid_params", "bad_scope")).error)
        assertEquals(AgentOsError.FAILED, AgentOsMapping.fromRpc(rpc(-32051, "model_timeout")).error)
        assertEquals(AgentOsError.FAILED, AgentOsMapping.fromRpc(JsonRpcException(-32603, "x")).error)
    }

    @Test
    fun `an exception message never carries the servers message text`() {
        val e = AgentOsMapping.fromRpc(rpc(-32051, "model_not_configured"))
        assertTrue(e.message!!, !e.message!!.contains("user's words"))
    }

    // ------------------------------------------------------------------ toolScope

    @Test
    fun `scope meta has the agreed shape, duplicates are dropped, an empty scope is an empty array`() {
        val meta = AgentOsMapping.scopeMeta(listOf(ToolRef("alarm", "alarm_create"), ToolRef("calendar", "event_create"), ToolRef("alarm", "alarm_create")))
        val scope = ((meta["org.agentos"] as JsonObject)["toolScope"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf("alarm" to "alarm_create", "calendar" to "event_create"), scope.map { (it["plugin"] as JsonPrimitive).content to (it["tool"] as JsonPrimitive).content })
        val empty = AgentOsMapping.scopeMeta(emptyList())
        assertEquals(0, ((empty["org.agentos"] as JsonObject)["toolScope"] as JsonArray).size)
    }

    @Test
    fun `no scope sends no meta at all, an empty scope is an explicit zero tools`() {
        assertNull(AgentOsMapping.sessionMeta(null))
        val empty = AgentOsMapping.sessionMeta(emptyList())!!
        assertEquals(0, ((empty["org.agentos"] as JsonObject)["toolScope"] as JsonArray).size)
        assertEquals(1, (((AgentOsMapping.sessionMeta(listOf(ToolRef("a", "b")))!!["org.agentos"] as JsonObject)["toolScope"]) as JsonArray).size)
    }

    @Test
    fun `without a scope a tool keeps its final name and has no ref`() {
        val m = AgentOsMapping.PromptMapper(null)
        val e = m.map(toolCall("n", "mcp__calendar__calendar__event_list")) as AgentOsEvent.ToolCall
        assertEquals("mcp__calendar__calendar__event_list", e.tool)
        assertNull(e.ref)
        assertEquals(ToolStatus.PENDING_APPROVAL, e.status)
    }

    @Test
    fun `scope meta rejects shapes the server would refuse`() {
        for (bad in listOf(
            List(33) { ToolRef("p$it", "t") },
            listOf(ToolRef("", "t")),
            listOf(ToolRef("p", "")),
            listOf(ToolRef("p".repeat(129), "t")),
            listOf(ToolRef("p", "t".repeat(129))),
        )) {
            try { AgentOsMapping.scopeMeta(bad); fail() } catch (e: IllegalArgumentException) { /* expected */ }
        }
        AgentOsMapping.scopeMeta(List(32) { ToolRef("p$it", "t") }) // exactly at the limit
    }

    // ------------------------------------------------------------------ 事件映射

    private val scope = listOf(ToolRef("alarm", "alarm_create"), ToolRef("calendar", "event_create"))

    private fun toolCall(id: String, title: String, status: ToolCallStatus = ToolCallStatus.PENDING, args: JsonObject? = null) =
        SessionUpdate.ToolCall(toolCallId = ToolCallId(id), title = title, kind = ToolKind.OTHER, status = status, rawInput = args)

    private fun update(id: String, status: ToolCallStatus?, text: String? = null) = SessionUpdate.ToolCallUpdate(
        ToolCallId(id), status = status, content = text?.let { listOf(ToolCallContent.Content(ContentBlock.Text(it))) },
    )

    @Test
    fun `a tool call goes pending approval, running, completed with one id and the original tool name`() {
        val m = AgentOsMapping.PromptMapper(scope)
        val args = buildJsonObject { put("title", "meeting") }
        val a = m.map(toolCall("c1", "mcp__calendar__calendar__event_create", args = args)) as AgentOsEvent.ToolCall
        assertEquals(ToolStatus.PENDING_APPROVAL, a.status)
        assertEquals("event_create", a.tool) // the caller does not need to know the suffix rules
        assertEquals(ToolRef("calendar", "event_create"), a.ref)
        assertEquals("""{"title":"meeting"}""", a.argumentsJson)
        assertNull(a.resultJson)
        val b = m.map(update("c1", ToolCallStatus.IN_PROGRESS)) as AgentOsEvent.ToolCall
        assertEquals(ToolStatus.RUNNING, b.status)
        assertEquals("c1", b.id); assertEquals("event_create", b.tool); assertEquals(a.argumentsJson, b.argumentsJson)
        val c = m.map(update("c1", ToolCallStatus.COMPLETED, """{"id":42}""")) as AgentOsEvent.ToolCall
        assertEquals(ToolStatus.COMPLETED, c.status)
        assertEquals("""{"id":42}""", c.resultJson)
        assertEquals(ToolRef("calendar", "event_create"), c.ref)
    }

    @Test
    fun `a refused tool is DENIED, any other failure is FAILED`() {
        val m = AgentOsMapping.PromptMapper(scope)
        m.map(toolCall("d", "mcp__alarm__alarm__alarm_create"))
        val denied = m.map(update("d", ToolCallStatus.FAILED, "${AgentOsMapping.DENIED_PREFIX} The user declined this tool call.")) as AgentOsEvent.ToolCall
        assertEquals(ToolStatus.DENIED, denied.status)
        m.map(toolCall("f", "mcp__alarm__alarm__alarm_create"))
        val failed = m.map(update("f", ToolCallStatus.FAILED, "[agentos:tool_unavailable] not reachable")) as AgentOsEvent.ToolCall
        assertEquals(ToolStatus.FAILED, failed.status)
    }

    @Test
    fun `a patch without status keeps the last status, an unknown call id still maps`() {
        val m = AgentOsMapping.PromptMapper(scope)
        m.map(toolCall("p", "mcp__alarm__alarm__alarm_create"))
        m.map(update("p", ToolCallStatus.IN_PROGRESS))
        val patched = m.map(update("p", null, "partial")) as AgentOsEvent.ToolCall
        assertEquals(ToolStatus.RUNNING, patched.status)
        assertEquals("partial", patched.resultJson)
        val stray = m.map(update("never-seen", ToolCallStatus.COMPLETED, "x")) as AgentOsEvent.ToolCall
        assertEquals(ToolStatus.COMPLETED, stray.status)
    }

    @Test
    fun `text chunks map, thoughts and plans are not exposed`() {
        val m = AgentOsMapping.PromptMapper(scope)
        assertEquals(AgentOsEvent.Text("hello"), m.map(SessionUpdate.AgentMessageChunk(ContentBlock.Text("hello"))))
        assertNull(m.map(SessionUpdate.AgentMessageChunk(ContentBlock.Text(""))))
        assertNull(m.map(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("thinking about secrets"))))
    }

    @Test
    fun `a tool outside the scope keeps its final name and has no ref`() {
        val m = AgentOsMapping.PromptMapper(scope)
        val e = m.map(toolCall("o", "mcp__notes__notes__note_delete")) as AgentOsEvent.ToolCall
        assertEquals("mcp__notes__notes__note_delete", e.tool)
        assertNull(e.ref)
    }

    @Test
    fun `ref inference follows the naming rule for unusual plugin names`() {
        val s = listOf(ToolRef("my.plugin", "do-it"), ToolRef("alarm", "alarm_create"))
        assertEquals(s[0], AgentOsMapping.refFor("mcp__my_plugin__srv__do-it", s))
        assertEquals(s[1], AgentOsMapping.refFor("mcp__alarm__alarm__alarm_create", s))
        assertNull(AgentOsMapping.refFor("mcp__alarm__alarm__alarm_delete", s))
        assertNull(AgentOsMapping.refFor("mcp__other__alarm__alarm_create", s))
        // a bare name (not an mcp__ name) matches the tool name directly
        assertEquals(s[1], AgentOsMapping.refFor("alarm_create", s))
    }
}
