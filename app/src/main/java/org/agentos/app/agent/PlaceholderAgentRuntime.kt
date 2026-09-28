package org.agentos.app.agent

import android.os.SystemClock
import com.agentclientprotocol.agent.Agent
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
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.AcpConnection
import org.agentos.runtime.AgentRuntime
import org.agentos.runtime.RunState
import org.agentos.runtime.RuntimeEngine
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.OutboundGate
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * **过渡期的宿主层（W6）**：实现 A1 定下的 `org.agentos.runtime.AgentRuntime` 接口，由两部分组成：
 *
 * - 真的 [RuntimeEngine]（A2）：[start] 里打开 Store（HostPortImpl → AndroidStore）、迁移、执行恢复流程、启动调度器；
 *   ACP 会话在它的 Store 里新建（`session/new`）和查找（`session/load`），所以会话跨进程重启保留。
 * - 占位的 ACP Agent 端：A 的 W4（检查点 A3）进 main 之前 `RuntimeEngine.serveAcp` 还没接上，prompt 由不调模型的
 *   假 Agent 回答（Pi Agent core 在 Android 上也还没有，见 [UnwiredAgentCore]）。
 *
 * A3 进 main 后 [AgentProcess] 里 `runtime = engine`，本文件删除；W6 的接线（serveAcp、runState、start）不变。
 *
 * 它按正式宿主层的约定工作：每轮 prompt 是一个任务，**先登记**（runState.activeTasks + 1）**再等 [start] 结束**，
 * 结束时减 1；流式输出前调 [OutboundGate.awaitWritable] 做背压。[runState] = 引擎的状态 + 假 Agent 的任务数，
 * 假 Agent 登记任务时**同步**更新，所以 RuntimeLifecycle 现读到的任务数不会落后。
 *
 * 假 Agent 的指令（tests/device/acp-channel 的 agentCommand）：chunks、chunkChars、intervalMs、burst、cjk、bp、
 * bigChunkChars。不是 JSON 时回显 20 条。
 *
 * @param beforeStart 在 [start] 里、引擎启动之前执行（debug 包的设备用例用它拉长恢复）。
 */
class PlaceholderAgentRuntime(
    val engine: RuntimeEngine,
    private val beforeStart: suspend () -> Unit = {},
) : AgentRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("placeholder-runtime"))
    private val placeholderTasks = AtomicInteger()
    private val state = MutableStateFlow(RunState())
    override val runState: StateFlow<RunState> = state.asStateFlow()

    private val started = CompletableDeferred<Unit>()
    private val nextConn = AtomicInteger()
    private val live = ConcurrentHashMap<Int, CallerIdentity>()
    private val outcomes = ConcurrentHashMap<String, AtomicLong>()
    private val sessionsCreated = AtomicLong()
    private val sessionsLoaded = AtomicLong()
    private val sessionLoadsRejected = AtomicLong()

    /** 引擎是否已启动（Store 已打开、恢复已完成）。 */
    @Volatile var engineStarted = false
        private set

    init {
        scope.launch(CoroutineName("engine-run-state")) { engine.runState.collect { publish() } }
    }

    @Synchronized
    private fun publish() {
        val e = engine.runState.value
        state.value = e.copy(activeTasks = e.activeTasks + placeholderTasks.get())
    }

    override suspend fun start() {
        try {
            beforeStart()
            engine.start()
            engineStarted = true
        } finally {
            started.complete(Unit)
        }
    }

    override fun serveAcp(transport: Transport, caller: CallerIdentity, gate: OutboundGate): AcpConnection {
        val id = nextConn.incrementAndGet()
        val name = "acp-$id(${caller.ownerKey})"
        val connScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + CoroutineName(name))
        val protocol = Protocol(connScope, transport, ProtocolOptions(protocolDebugName = name))
        Agent(protocol, FakeAgentSupport(caller, gate))
        val closed = CompletableDeferred<Unit>()
        live[id] = caller
        // 传输关闭 → 连接结束、挂起的请求一并结束（S3 问题 4，BinderAcpTransport.bindTo 的做法）
        transport.onClose {
            protocol.close()
            live.remove(id)
            closed.complete(Unit)
            connScope.cancel()
        }
        protocol.start()
        return object : AcpConnection {
            override val caller: CallerIdentity = caller
            override suspend fun close(reason: String) = transport.close()
            override suspend fun awaitClosed() = closed.await()
        }
    }

    override suspend fun shutdown() {
        runCatching { engine.shutdown() }
        scope.cancel()
    }

    /** 诊断：还没结束的连接数、各结局的 prompt 数。 */
    fun liveConnections(): Int = live.size

    fun promptOutcomes(): JSONObject = JSONObject().also { o -> outcomes.forEach { (k, v) -> o.put(k, v.get()) } }

    /** 诊断：这个进程里经 ACP 新建、载入（成功 / 被拒）的会话数。 */
    fun sessionCounters(): JSONObject = JSONObject()
        .put("created", sessionsCreated.get()).put("loaded", sessionsLoaded.get()).put("loadRejected", sessionLoadsRejected.get())

    private inner class FakeAgentSupport(private val caller: CallerIdentity, private val gate: OutboundGate) : AgentSupport {

        override suspend fun initialize(clientInfo: ClientInfo): AgentInfo = AgentInfo(
            capabilities = AgentCapabilities(loadSession = true),
            implementation = Implementation(name = IMPLEMENTATION_NAME, version = "0.2"),
        )

        /** 会话记录在 Store 里（与正式宿主层一样按调用方隔离）。恢复流程结束之前先等。 */
        override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
            started.await()
            val s = engine.createSession(caller, sessionParameters.cwd)
            sessionsCreated.incrementAndGet()
            return FakeSession(SessionId(s.id), gate)
        }

        /** 只查 Store 里有没有这个会话（调用方自己的）；假 Agent 没有历史可回放。 */
        override suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession {
            started.await()
            try {
                engine.session(caller, sessionId.value)
            } catch (e: Exception) {
                sessionLoadsRejected.incrementAndGet()
                throw e
            }
            sessionsLoaded.incrementAndGet()
            return FakeSession(sessionId, gate)
        }
    }

    private inner class FakeSession(override val sessionId: SessionId, private val gate: OutboundGate) : AgentSession {
        override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
            // 先登记任务，再等恢复流程结束（与正式宿主层相同）
            placeholderTasks.incrementAndGet()
            publish()
            var outcome = "error"
            try {
                started.await()
                val text = content.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
                val cmd = text.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
                val chunks = cmd?.optInt("chunks", 20) ?: 20
                val chunkChars = cmd?.optInt("chunkChars", 0) ?: 0
                val intervalMs = cmd?.optLong("intervalMs", 20) ?: 20L
                val burst = maxOf(1, cmd?.optInt("burst", 1) ?: 1)
                val bp = cmd?.optBoolean("bp", false) ?: false
                val bigChunkChars = cmd?.optInt("bigChunkChars", 0) ?: 0
                val filler = if (chunkChars > 0) (if (cmd?.optBoolean("cjk", false) == true) "中" else "x").repeat(chunkChars) else null
                for (i in 0 until chunks) {
                    currentCoroutineContext().ensureActive()
                    if (bp) gate.awaitWritable()
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
                outcomes.getOrPut(outcome) { AtomicLong() }.incrementAndGet()
                placeholderTasks.decrementAndGet()
                publish()
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

    companion object {
        const val IMPLEMENTATION_NAME = "agentos-placeholder-agent"
    }
}
