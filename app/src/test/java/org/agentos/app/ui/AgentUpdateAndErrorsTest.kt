package org.agentos.app.ui

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import org.agentos.app.i18n.ResStrings
import org.agentos.app.i18n.Strings
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

    private val zh: Strings = ResStrings.zh
    private val en: Strings = ResStrings.en

    @Test
    fun modelErrorsHaveActionableTextInBothLanguages() {
        val notConfigured = AgentErrors.fromRpc(zh, -32051, "model_not_configured: no key", "model_not_configured", false)
        assertEquals("还没有配置模型", notConfigured.title)
        assertEquals("到设置页选择模型厂商并填写 key", notConfigured.hint)
        assertFalse(notConfigured.retryable)
        val notConfiguredEn = AgentErrors.fromRpc(en, -32051, "model_not_configured: no key", "model_not_configured", false)
        assertEquals("No model is configured yet", notConfiguredEn.title)
        assertEquals("Open Settings, choose a model provider and enter your key", notConfiguredEn.hint)
        assertEquals("model_not_configured", notConfiguredEn.code)

        val limited = AgentErrors.fromRpc(zh, -32051, "model_rate_limited: 429", "model_rate_limited", true, retryAfterSeconds = 20)
        assertEquals("被模型服务限流了", limited.title)
        assertEquals("约 20 秒后再发", limited.hint)
        assertTrue(limited.retryable)
        // English plural: one / other
        assertEquals("Try again in about 20 seconds", AgentErrors.fromRpc(en, -32051, "x", "model_rate_limited", true, retryAfterSeconds = 20).hint)
        assertEquals("Try again in about 1 second", AgentErrors.fromRpc(en, -32051, "x", "model_rate_limited", true, retryAfterSeconds = 1).hint)
        // no usable Retry-After: the plain hint
        assertEquals("Wait a moment before sending again", AgentErrors.fromRpc(en, -32051, "x", "model_rate_limited", true).hint)

        val safe = AgentErrors.fromRpc(zh, -32050, "safe_mode: runtime is in safe mode", "safe_mode", false)
        assertTrue(safe.hint!!.contains("动作"))
        assertTrue(AgentErrors.fromRpc(en, -32050, "safe_mode: runtime is in safe mode", "safe_mode", false).hint!!.contains("Action"))

        // F9: the key was cleared while this turn was running
        val revoked = AgentErrors.fromRpc(zh, -32051, "model_not_configured: revoked", "model_not_configured", false, reason = "key_revoked")
        assertEquals("模型 key 已在设置中清除，这一轮已停止", revoked.title)
        assertEquals("model_not_configured", revoked.code)
        assertEquals(
            "The model key was cleared in Settings, so this turn was stopped",
            AgentErrors.fromRpc(en, -32051, "model_not_configured: revoked", "model_not_configured", false, reason = "key_revoked").title,
        )

        // B6: the network exit retried before giving up; details.attempts counts all attempts
        val network = AgentErrors.fromRpc(zh, -32051, "model_network: connect", "model_network", true, attempts = 7)
        assertEquals("连不上模型服务", network.title)
        assertEquals("检查网络后再试（已自动重试，共尝试 7 次）", network.hint)
        assertTrue(network.retryable)
        assertEquals("Check your network and try again (Retried automatically. Attempts in total: 7)", AgentErrors.fromRpc(en, -32051, "model_network: connect", "model_network", true, attempts = 7).hint)
        assertEquals("检查网络后再试", AgentErrors.fromRpc(zh, -32051, "x", "model_network", true, attempts = 1).hint)
        val limited429 = AgentErrors.fromRpc(zh, -32051, "429", "model_rate_limited", true, retryAfterSeconds = 90, attempts = 3)
        assertEquals("约 90 秒后再发（已自动重试，共尝试 3 次）", limited429.hint)
        assertEquals("Try again in about 90 seconds (Retried automatically. Attempts in total: 3)", AgentErrors.fromRpc(en, -32051, "429", "model_rate_limited", true, retryAfterSeconds = 90, attempts = 3).hint)
        // a hint-less error gets just the attempts note
        assertEquals("Retried automatically. Attempts in total: 4", AgentErrors.fromRpc(en, -32051, "x", "tool_failed", true, attempts = 4).hint)
    }

    @Test
    fun everyKnownErrorCodeHasTitleAndHintInBothLanguages() {
        // the table is the one place that lists the codes; resource lookups fail loudly (ResStrings) if either language lacks a string
        assertTrue(AgentErrors.knownCodes.size >= 40)
        for (code in AgentErrors.knownCodes) {
            for (s in listOf(zh, en)) {
                val e = AgentErrors.fromRpc(s, -32051, "m", code, false)
                assertTrue("$code title", e.title.isNotBlank())
                assertEquals(code, e.code)
            }
        }
        // the English wording never carries Chinese and the Chinese never falls back to English text for the same code
        for (code in AgentErrors.knownCodes) {
            val zhTitle = AgentErrors.fromRpc(zh, -32051, "m", code, false).title
            val enTitle = AgentErrors.fromRpc(en, -32051, "m", code, false).title
            assertTrue("$code: <$enTitle> has CJK", enTitle.none { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF })
            assertTrue("$code: <$zhTitle> has no CJK", zhTitle.any { it.code in 0x4E00..0x9FFF } || zhTitle.contains("AgentOS"))
        }
    }

    @Test
    fun unknownCodesFallBackWithoutLeakingDetail() {
        val unknown = AgentErrors.fromRpc(zh, -32051, "brand_new_code: something\nstack line", "brand_new_code", true)
        assertEquals("请求失败", unknown.title)
        assertEquals("brand_new_code: something", unknown.hint)
        assertEquals("brand_new_code", unknown.code)
        val sdk = AgentErrors.fromRpc(zh, -32601, "Method not found", null, null)
        assertEquals("运行时不支持这个请求", sdk.title)
        assertEquals("rpc_-32601", sdk.code)
        // English: the title is translated, the runtime's own diagnostic line is shown as it is
        val unknownEn = AgentErrors.fromRpc(en, -32051, "brand_new_code: something\nstack line", "brand_new_code", true)
        assertEquals("The request failed", unknownEn.title)
        assertEquals("brand_new_code: something", unknownEn.hint)
        assertEquals("The runtime doesn't support this request", AgentErrors.fromRpc(en, -32601, "Method not found", null, null).title)
        assertEquals("Protocol error", AgentErrors.fromRpc(en, -32700, null, null, null).title)
        assertEquals("The request was cancelled", AgentErrors.fromRpc(en, -32800, null, null, null).title)
    }

    @Test
    fun channelCloseCauses() {
        assertEquals("运行时进程重启了", AgentErrors.fromClose(zh, CloseCause.PeerDied).title)
        assertEquals("The runtime process restarted", AgentErrors.fromClose(en, CloseCause.PeerDied).title)
        assertEquals("channel_remote", AgentErrors.fromClose(zh, CloseCause.Remote("bye")).code)
        assertEquals("连接已断开", AgentErrors.fromClose(zh, null).title)
        assertEquals("The connection was lost", AgentErrors.fromClose(en, null).title)
        assertEquals("Sending again reconnects", AgentErrors.fromClose(en, null).hint)
        assertEquals("连接出现异常（window exceeded）", AgentErrors.fromClose(zh, CloseCause.Violation("window exceeded")).title)
        assertEquals("Connection problem (window exceeded)", AgentErrors.fromClose(en, CloseCause.Violation("window exceeded")).title)
        assertEquals("connect_not_open", AgentErrors.connectFailed(zh, "agentos.acp.not_open").code)
        assertEquals("AgentOS refused the connection", AgentErrors.connectFailed(en, "agentos.acp.not_open").title)
        assertEquals("AgentOS refused the connection (security)", AgentErrors.connectFailed(en, "security").title)
        assertEquals("连不上 AgentOS 运行时", AgentErrors.connectFailed(zh, null).title)
        assertEquals("Can't reach the AgentOS runtime", AgentErrors.connectFailed(en, null).title)
        assertEquals("Please reinstall AgentOS", AgentErrors.serviceMissing(en).hint)
        assertEquals("出现意外错误（IllegalStateException）", AgentErrors.unexpected(zh, IllegalStateException("x")).title)
        assertEquals("Something unexpected went wrong (IllegalStateException)", AgentErrors.unexpected(en, IllegalStateException("x")).title)
    }
}
