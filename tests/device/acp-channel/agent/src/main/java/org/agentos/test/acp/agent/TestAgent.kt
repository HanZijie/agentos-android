package org.agentos.test.acp.agent

import android.os.SystemClock
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.acp.BinderAcpTransport
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假 Agent：不调模型，按 prompt 文本里的 JSON 指令流式输出（字段见 common 的 agentCommand）。
 * 每条 agent_message_chunk 的 _meta 带 seq 和发出时的 elapsedRealtimeNanos，客户端据此算端到端延迟。
 */
class TestAgentSupport(private val connId: Int, private val transport: BinderAcpTransport) : AgentSupport {
    private val sessions = AtomicInteger()

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo = AgentInfo(
        capabilities = AgentCapabilities(),
        implementation = Implementation(name = "agentos-test-agent", version = "0.1"),
    )

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession =
        TestSession(SessionId("t-$connId-${sessions.incrementAndGet()}"), transport)
}

private class TestSession(override val sessionId: SessionId, private val transport: BinderAcpTransport) : AgentSession {

    override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
        val text = content.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
        val cmd = text.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        val chunks = cmd?.optInt("chunks", 20) ?: 20
        val chunkChars = cmd?.optInt("chunkChars", 0) ?: 0
        val intervalMs = cmd?.optLong("intervalMs", 20) ?: 20L
        val burst = maxOf(1, cmd?.optInt("burst", 1) ?: 1)
        val bp = cmd?.optBoolean("bp", false) ?: false
        val bpChars = cmd?.optLong("bpChars", BinderAcpTransport.PRODUCER_HIGH_WATER_CHARS) ?: BinderAcpTransport.PRODUCER_HIGH_WATER_CHARS
        val bigChunkChars = cmd?.optInt("bigChunkChars", 0) ?: 0
        val filler = if (chunkChars > 0) (if (cmd?.optBoolean("cjk", false) == true) "中" else "x").repeat(chunkChars) else null
        AgentHost.promptStarted()
        var outcome = "error"
        try {
            for (i in 0 until chunks) {
                currentCoroutineContext().ensureActive()
                if (bp) transport.awaitWritable(bpChars)
                val body = filler ?: if (cmd == null) "echo[$i]: ${text.take(64)}\n" else "chunk $i "
                emit(chunk(body, i))
                if (intervalMs > 0) {
                    if (i % burst == burst - 1) delay(intervalMs)
                } else if (i % 64 == 63) {
                    yield()
                }
            }
            if (bigChunkChars > 0) emit(chunk("y".repeat(bigChunkChars), chunks))
            emit(Event.PromptResponseEvent(PromptResponse(StopReason.END_TURN)))
            outcome = "end_turn"
        } catch (e: CancellationException) {
            outcome = "cancelled"
            throw e
        } finally {
            AgentHost.promptEnded(outcome)
        }
    }

    private fun chunk(text: String, seq: Int) = Event.SessionUpdateEvent(
        SessionUpdate.AgentMessageChunk(
            content = ContentBlock.Text(text),
            _meta = buildJsonObject {
                put("seq", seq)
                put("t", SystemClock.elapsedRealtimeNanos())
            },
        )
    )
}
