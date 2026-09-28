package org.agentos.spike.s3.agent

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
 * 假 Agent：不调模型，按 prompt 文本里的 JSON 指令流式输出。
 *
 * 指令字段：chunks（条数）、chunkChars（每条字符数）、intervalMs（间隔）、cjk（用中文填充）、
 * bp（生产者背压：每条之前等本地积压降到 bpChars 以下）、bigChunkChars（最后额外发一条这么长的）。
 * 不是 JSON 时（例如电脑端的 TypeScript 客户端），按 20 条、每条间隔 20 ms 回显。
 *
 * 每条 agent_message_chunk 的 _meta 带 seq 和发出时的 elapsedRealtimeNanos，客户端据此算端到端延迟。
 */
class SpikeAgentSupport(
    private val connId: Int,
    private val transport: BinderAcpTransport?,
) : AgentSupport {
    private val sessions = AtomicInteger()

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo = AgentInfo(
        capabilities = AgentCapabilities(),
        implementation = Implementation(name = "agentos-s3-spike-agent", version = "0.1"),
    )

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession =
        SpikeSession(SessionId("s3-$connId-${sessions.incrementAndGet()}"), transport)
}

private class PromptCommand(json: JSONObject?, echoText: String) {
    val chunks = json?.optInt("chunks", 20) ?: 20
    val chunkChars = json?.optInt("chunkChars", 0) ?: 0
    val intervalMs = json?.optLong("intervalMs", 20) ?: 20L
    val cjk = json?.optBoolean("cjk", false) ?: false
    val backpressure = json?.optBoolean("bp", false) ?: false
    val bpChars = json?.optLong("bpChars", 16_384) ?: 16_384L
    val bigChunkChars = json?.optInt("bigChunkChars", 0) ?: 0
    val echo = if (json == null) echoText else null

    companion object {
        fun parse(text: String): PromptCommand {
            val json = text.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
            return PromptCommand(json, text)
        }
    }
}

private class SpikeSession(
    override val sessionId: SessionId,
    private val transport: BinderAcpTransport?,
) : AgentSession {

    override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
        val text = content.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
        val cmd = PromptCommand.parse(text)
        val filler = if (cmd.chunkChars > 0) (if (cmd.cjk) "中" else "x").repeat(cmd.chunkChars) else null
        AgentHost.promptStarted()
        var outcome = "error"
        try {
            for (i in 0 until cmd.chunks) {
                currentCoroutineContext().ensureActive()
                if (cmd.backpressure) transport?.awaitWritable(cmd.bpChars)
                val body = filler ?: if (cmd.echo != null) "echo[$i]: ${cmd.echo.take(64)}\n" else "chunk $i "
                emit(chunk(body, i))
                if (cmd.intervalMs > 0) delay(cmd.intervalMs) else if (i % 64 == 63) yield()
            }
            if (cmd.bigChunkChars > 0) emit(chunk("y".repeat(cmd.bigChunkChars), cmd.chunks))
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
