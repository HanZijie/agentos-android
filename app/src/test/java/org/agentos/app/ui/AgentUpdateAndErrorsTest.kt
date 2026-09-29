package org.agentos.app.ui

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import org.agentos.channel.CloseCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SDK → UI mapping with the real ACP SDK 0.30.1 model classes, and error texts. */
class AgentUpdateAndErrorsTest {
    @Test
    fun mapsMessageAndThoughtChunks() {
        assertEquals(
            AgentUpdate.MessageChunk("hello"),
            AgentUpdate.fromSdk(SessionUpdate.AgentMessageChunk(ContentBlock.Text("hello"))),
        )
        assertEquals(
            AgentUpdate.ThoughtChunk("hmm"),
            AgentUpdate.fromSdk(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("hmm"))),
        )
    }

    @Test
    fun mapsToolCallAndPatch() {
        val started = AgentUpdate.fromSdk(
            SessionUpdate.ToolCall(
                toolCallId = ToolCallId("call-1"),
                title = "create_event",
                kind = ToolKind.EDIT,
                status = ToolCallStatus.PENDING,
            )
        )
        assertEquals(AgentUpdate.ToolCallStarted("call-1", "create_event", "edit", ToolStatus.PENDING, null), started)

        val patch = AgentUpdate.fromSdk(
            SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId("call-1"),
                status = ToolCallStatus.COMPLETED,
                content = listOf(ToolCallContent.Content(ContentBlock.Text("created"))),
            )
        )
        assertEquals(AgentUpdate.ToolCallUpdated("call-1", null, ToolStatus.COMPLETED, "created"), patch)
    }

    @Test
    fun blankToolTitleGetsAName() {
        val u = AgentUpdate.fromSdk(SessionUpdate.ToolCall(toolCallId = ToolCallId("c"), title = " "))
        assertEquals("工具调用", (u as AgentUpdate.ToolCallStarted).title)
    }

    @Test
    fun modelErrorsHaveActionableText() {
        val notConfigured = AgentErrors.fromRpc(-32051, "model_not_configured: no key", "model_not_configured", false)
        assertEquals("还没有配置模型", notConfigured.title)
        assertEquals("到设置页选择模型厂商并填写 key", notConfigured.hint)
        assertFalse(notConfigured.retryable)

        val limited = AgentErrors.fromRpc(-32051, "model_rate_limited: 429", "model_rate_limited", true, retryAfterSeconds = 20)
        assertEquals("被模型服务限流了", limited.title)
        assertEquals("约 20 秒后再发", limited.hint)
        assertTrue(limited.retryable)

        val safe = AgentErrors.fromRpc(-32050, "safe_mode: runtime is in safe mode", "safe_mode", false)
        assertTrue(safe.hint!!.contains("动作"))

        // F9: the key was cleared while this turn was running
        val revoked = AgentErrors.fromRpc(-32051, "model_not_configured: revoked", "model_not_configured", false, reason = "key_revoked")
        assertEquals("模型 key 已在设置中清除，这一轮已停止", revoked.title)
        assertEquals("model_not_configured", revoked.code)
    }

    @Test
    fun unknownCodesFallBackWithoutLeakingDetail() {
        val unknown = AgentErrors.fromRpc(-32051, "brand_new_code: something\nstack line", "brand_new_code", true)
        assertEquals("请求失败", unknown.title)
        assertEquals("brand_new_code: something", unknown.hint)
        assertEquals("brand_new_code", unknown.code)
        val sdk = AgentErrors.fromRpc(-32601, "Method not found", null, null)
        assertEquals("运行时不支持这个请求", sdk.title)
        assertEquals("rpc_-32601", sdk.code)
    }

    @Test
    fun channelCloseCauses() {
        assertEquals("运行时进程重启了", AgentErrors.fromClose(CloseCause.PeerDied).title)
        assertEquals("channel_remote", AgentErrors.fromClose(CloseCause.Remote("bye")).code)
        assertEquals("连接已断开", AgentErrors.fromClose(null).title)
        assertEquals("connect_not_open", AgentErrors.connectFailed("agentos.acp.not_open").code)
    }
}
