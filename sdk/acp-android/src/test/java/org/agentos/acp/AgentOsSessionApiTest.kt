@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.HttpHeader
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.protocol.JsonRpcException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SDK 的会话 API 里不依赖 Android 的部分（docs/third-party-acp.md 4.7，core/protocol/acp-mapping.md 4a–4c）：
 * 新的错误分类、MCP 服务器的转换、`session_info_update` 里 AgentOS 字段的解析、思考和历史里用户消息的映射、公开类型不泄露凭据。
 * Binder 上的端到端在设备测试里（tests/device/acp-channel）；ACP 层本身由 core:runtime 的 SessionLifecycleAcpTest 等用官方 SDK 的客户端覆盖。
 */
class AgentOsSessionApiTest {
    private fun rpc(code: Int, agentosCode: String?, reason: String? = null) = JsonRpcException(
        code, "message with the user's words and https://secret.example/mcp",
        data = buildJsonObject {
            if (agentosCode != null) put("agentosCode", agentosCode)
            if (reason != null) put("details", buildJsonObject { put("reason", reason) })
        },
    )

    // ------------------------------------------------------------------ 错误分类

    @Test
    fun `the new session errors map to their own kinds`() {
        assertEquals(AgentOsError.SESSION_NOT_FOUND, AgentOsMapping.fromRpc(rpc(-32004, "session_not_found")).error)
        assertEquals(AgentOsError.INVALID_REQUEST, AgentOsMapping.fromRpc(rpc(-32602, "invalid_params")).error)
        assertEquals(AgentOsError.INVALID_REQUEST, AgentOsMapping.fromRpc(rpc(-32602, "invalid_params", "unknown_mode")).error)
        assertEquals(AgentOsError.UNSUPPORTED, AgentOsMapping.fromRpc(rpc(-32602, "unsupported")).error)
    }

    @Test
    fun `too many mcp servers is a bad request, the hourly limit is still rate limiting`() {
        assertEquals(AgentOsError.INVALID_REQUEST, AgentOsMapping.fromRpc(rpc(-32048, "quota_exceeded", "mcp_servers")).error)
        assertEquals(AgentOsError.RATE_LIMITED, AgentOsMapping.fromRpc(rpc(-32048, "quota_exceeded", "hourly")).error)
        assertEquals(AgentOsError.RATE_LIMITED, AgentOsMapping.fromRpc(rpc(-32048, "quota_exceeded")).error)
    }

    @Test
    fun `too_large still wins over the general invalid_params`() {
        assertEquals(AgentOsError.TOO_LARGE, AgentOsMapping.fromRpc(rpc(-32602, "invalid_params", "too_large")).error)
    }

    @Test
    fun `a method an older AgentOS does not have is UNSUPPORTED, not a mystery failure`() {
        assertEquals(AgentOsError.UNSUPPORTED, AgentOsMapping.fromRpc(JsonRpcException(-32601, "session/load is not enabled in AgentOS Profile v1")).error)
    }

    @Test
    fun `errors about sessions and servers never carry the servers text or any url`() {
        for (c in listOf(rpc(-32004, "session_not_found"), rpc(-32602, "invalid_params"), rpc(-32602, "unsupported"), rpc(-32048, "quota_exceeded", "mcp_servers"))) {
            val m = AgentOsMapping.fromRpc(c).message!!
            assertFalse(m, "user's words" in m || "secret.example" in m)
        }
    }

    // ------------------------------------------------------------------ MCP 服务器

    @Test
    fun `mcp servers become ACP http servers with their headers, in order`() {
        val out = AgentOsMapping.mcpServers(
            listOf(
                McpHttpServer("notes", "https://notes.example/mcp", listOf("Authorization" to "Bearer t", "X-Tenant" to "7")),
                McpHttpServer("plain", "https://plain.example/mcp"),
            ),
        )
        val first = out[0] as McpServer.Http
        assertEquals("notes", first.name)
        assertEquals("https://notes.example/mcp", first.url)
        assertEquals(listOf("Authorization" to "Bearer t", "X-Tenant" to "7"), first.headers.map { it.name to it.value })
        assertEquals(emptyList<HttpHeader>(), (out[1] as McpServer.Http).headers)
        assertEquals(emptyList<McpServer>(), AgentOsMapping.mcpServers(emptyList()))
    }

    @Test
    fun `a server without a name or a url is a programming error caught before anything is sent`() {
        for (bad in listOf(McpHttpServer("", "https://x.example/mcp"), McpHttpServer("a", " "))) {
            try { AgentOsMapping.mcpServers(listOf(bad)); fail() } catch (e: IllegalArgumentException) { /* expected */ }
        }
    }

    @Test
    fun `the url and the headers never show up in toString, which ends up in logs`() {
        val s = McpHttpServer("notes", "https://secret-host.example/mcp?key=ZZZ", listOf("Authorization" to "Bearer SECRET"))
        val text = s.toString()
        assertEquals("McpHttpServer(name=notes)", text)
        assertFalse("SECRET" in text || "secret-host" in text || "ZZZ" in text)
    }

    // ------------------------------------------------------------------ session_info_update

    private fun ext(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject { put("org.agentos", buildJsonObject(block)) }

    @Test
    fun `server states and the running task are read from the info update`() {
        val info = AgentOsMapping.sessionInfo(
            ext {
                put(
                    "mcpServers",
                    buildJsonArray {
                        add(buildJsonObject { put("name", "up"); put("connected", true); put("toolCount", 3) })
                        add(buildJsonObject { put("name", "down"); put("connected", false); put("toolCount", 0); put("reason", "connect_failed") })
                    },
                )
                put("activeTask", buildJsonObject { put("taskId", "tsk_1"); put("state", "running") })
            },
        )!!
        assertEquals(
            listOf(McpServerStatus("up", true, 3, null), McpServerStatus("down", false, 0, "connect_failed")),
            info.mcpServers,
        )
        assertEquals("tsk_1", info.activeTaskId)
    }

    @Test
    fun `an info update without agentos fields, or with broken ones, is ignored without an exception`() {
        assertNull(AgentOsMapping.sessionInfo(null))
        assertNull(AgentOsMapping.sessionInfo(buildJsonObject { put("other", "x") }))
        assertNull(AgentOsMapping.sessionInfo(ext { put("selection", buildJsonObject { put("sessionId", "ses_1") }) }))
        val odd = AgentOsMapping.sessionInfo(
            ext {
                put("mcpServers", buildJsonArray { add(buildJsonObject { put("connected", true) }); add(kotlinx.serialization.json.JsonPrimitive("x")) })
                put("activeTask", "not an object")
            },
        )!!
        assertEquals(emptyList<McpServerStatus>(), odd.mcpServers)
        assertNull(odd.activeTaskId)
        assertNull(AgentOsMapping.sessionInfo(ext { put("mcpServers", JsonArray(emptyList())) })!!.activeTaskId)
    }

    // ------------------------------------------------------------------ 事件映射

    private fun text(t: String) = ContentBlock.Text(t)

    @Test
    fun `thoughts are dropped by default and shown when asked for`() {
        val thought = SessionUpdate.AgentThoughtChunk(text("let me think"))
        assertNull(AgentOsMapping.PromptMapper(null).map(thought))
        assertEquals(AgentOsEvent.Thought("let me think"), AgentOsMapping.PromptMapper(null, includeThoughts = true).map(thought))
        assertNull(AgentOsMapping.PromptMapper(null, includeThoughts = true).map(SessionUpdate.AgentThoughtChunk(text(""))))
    }

    @Test
    fun `user messages are only mapped for a replay, a live turn does not echo what the app said`() {
        val user = SessionUpdate.UserMessageChunk(text("what is on tomorrow?"))
        assertNull(AgentOsMapping.PromptMapper(null).map(user))
        assertEquals(AgentOsEvent.UserMessage("what is on tomorrow?"), AgentOsMapping.PromptMapper(null, includeUserMessages = true).map(user))
    }

    @Test
    fun `a replayed conversation maps to user, thought, tool and answer in order`() {
        val m = AgentOsMapping.PromptMapper(null, includeThoughts = true, includeUserMessages = true)
        val updates = listOf(
            SessionUpdate.UserMessageChunk(text("hi")),
            SessionUpdate.AgentThoughtChunk(text("hmm")),
            SessionUpdate.ToolCall(toolCallId = ToolCallId("c1"), title = "ses__notes__search", kind = com.agentclientprotocol.model.ToolKind.OTHER, status = ToolCallStatus.PENDING),
            SessionUpdate.ToolCallUpdate(ToolCallId("c1"), status = ToolCallStatus.COMPLETED),
            SessionUpdate.AgentMessageChunk(text("done")),
        )
        val events = updates.mapNotNull { m.map(it) }
        assertEquals(listOf("UserMessage", "Thought", "ToolCall", "ToolCall", "Text"), events.map { it::class.simpleName })
        // a tool of the app's own MCP server keeps the name AgentOS gave it
        assertEquals("ses__notes__search", (events[2] as AgentOsEvent.ToolCall).tool)
        assertEquals(ToolStatus.COMPLETED, (events[3] as AgentOsEvent.ToolCall).status)
    }

    @Test
    fun `plans and usage stay internal`() {
        val m = AgentOsMapping.PromptMapper(null, includeThoughts = true, includeUserMessages = true)
        assertNull(m.map(SessionUpdate.CurrentModeUpdate(com.agentclientprotocol.model.SessionModeId("chat"))))
    }

    // ------------------------------------------------------------------ 公开类型

    @Test
    fun `session modes round trip through their wire names, unknown names are not guessed`() {
        for (m in SessionMode.entries) assertEquals(m, SessionMode.of(m.wire))
        assertEquals(listOf("default", "read_only", "chat"), SessionMode.entries.map { it.wire })
        assertNull(SessionMode.of("admin"))
    }

    @Test
    fun `every session error kind exists for the caller to switch on`() {
        val names = AgentOsError.entries.map { it.name }
        for (n in listOf("SESSION_NOT_FOUND", "INVALID_REQUEST", "UNSUPPORTED", "DISCONNECTED", "FAILED")) assertTrue(n, n in names)
        assertNotNull(AgentOsException(AgentOsError.UNSUPPORTED).message)
    }
}
