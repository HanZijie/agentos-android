package org.agentos.test.acp

import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import android.os.SystemClock
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.agentos.acp.BinderAcpTransport
import org.agentos.channel.BinderChannel
import org.agentos.channel.ChannelConfig
import org.agentos.channel.CloseCause
import org.agentos.channel.IAcpService
import org.json.JSONObject

private object NoopClientOps : ClientSessionOperations {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse = RequestPermissionResponse(RequestPermissionOutcome.Cancelled)

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit
}

/** 一条 ACP 连接：bindService → IAcpService.open（BinderAcpTransport.connect）→ SDK Client。 */
class AcpConn(
    private val ctx: Context,
    val component: ComponentName,
    val config: ChannelConfig,
    parent: CoroutineScope,
    val label: String = "client",
) {
    val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + CoroutineName(label))
    val binding = ServiceBinding(ctx, component)
    lateinit var transport: BinderAcpTransport
        private set
    lateinit var protocol: Protocol
        private set
    lateinit var client: Client
        private set
    val channel: BinderChannel get() = transport.channel

    /** 各阶段耗时（毫秒，从 connect 开始算）。 */
    val timings = linkedMapOf<String, Double>()

    private fun mark(name: String, t0: Long) {
        timings[name] = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
    }

    suspend fun connect(timeoutMs: Long = 15_000): AgentInfo {
        val t0 = SystemClock.elapsedRealtimeNanos()
        check(binding.bind()) { "bindService returned false for $component" }
        val b = binding.awaitConnected(timeoutMs) ?: error("service not connected in $timeoutMs ms")
        mark("bound", t0)
        openOn(b)
        mark("opened", t0)
        val info = withTimeout(timeoutMs) { initialize() }
        mark("initialized", t0)
        return info
    }

    /** 在已经拿到的服务 Binder 上打开通道；服务端拒绝时抛 SecurityException。 */
    suspend fun openOn(b: IBinder) {
        val serverUid = ctx.packageManager.getPackageUid(component.packageName, 0)
        transport = BinderAcpTransport.connect(IAcpService.Stub.asInterface(b), serverUid, scope, config, "$label-channel")
        protocol = Protocol(scope, transport, ProtocolOptions(protocolDebugName = label))
        client = Client(protocol)
        BinderAcpTransport.bindTo(protocol, transport)
        protocol.start()
    }

    suspend fun initialize(): AgentInfo =
        client.initialize(ClientInfo(implementation = Implementation("agentos-device-test-client", "0.1")))

    suspend fun newSession(): ClientSession =
        client.newSession(SessionCreationParameters(cwd = "/", mcpServers = emptyList())) { _, _ -> NoopClientOps }

    /** 本端优雅关闭，等到通道真正关闭。 */
    suspend fun closeAndWait(timeoutMs: Long = 5_000): CloseCause? {
        protocol.close()
        return withTimeoutOrNull(timeoutMs) { channel.closeCause.await() }
    }

    fun dispose() {
        binding.unbind()
        scope.cancel()
    }
}

/** 一轮 prompt 的记录。假 Agent 的每条 agent_message_chunk 在 _meta 里带 seq 和发出时刻 t。 */
class PromptRun {
    var chunks = 0
    var lastSeq = -1
    var outOfOrder = 0
    val lat = LatencyStats()
    var stopReason: String? = null
    var error: Throwable? = null
    var startNs = 0L
    var firstChunkNs = 0L
    var endNs = 0L
    var chunkChars = 0L
    var maxChunkChars = 0

    /** 收到的文字（前 [TEXT_CAP] 个字符，用来核对上下文）。 */
    val text = StringBuilder()
    /** session/update 里的 tool_call 条数，以及 tool_call_update 的状态（按到达顺序）。 */
    var toolCalls = 0
    val toolStatuses = mutableListOf<String>()

    companion object {
        const val TEXT_CAP = 65_536
    }

    fun json(): JSONObject = JSONObject()
        .put("chunks", chunks).put("chars", chunkChars).put("maxChunkChars", maxChunkChars).put("outOfOrder", outOfOrder)
        .put("toolCalls", toolCalls).put("toolStatuses", org.json.JSONArray(toolStatuses))
        .put("stopReason", stopReason ?: JSONObject.NULL)
        .put("error", errJson(error))
        .put("firstChunkMs", if (firstChunkNs > 0) (firstChunkNs - startNs) / 1e6 else -1.0)
        .put("totalMs", (endNs - startNs) / 1e6)
        .put("chunksPerSec", if (endNs > startNs) chunks / ((endNs - startNs) / 1e9) else 0.0)
        .put("latency", lat.summary())
}

/** 发一轮 prompt 并收完流式更新。被取消（外层协程）时照常抛出；其他错误记在 [PromptRun.error]。 */
suspend fun runPrompt(
    session: ClientSession,
    text: String,
    run: PromptRun,
    onStatus: (String) -> Unit = {},
    onChunk: (PromptRun) -> Unit = {},
) {
    run.startNs = SystemClock.elapsedRealtimeNanos()
    var lastUi = 0L
    try {
        session.prompt(listOf(ContentBlock.Text(text))).collect { ev ->
            when (ev) {
                is Event.SessionUpdateEvent -> {
                    val u = ev.update
                    if (u is SessionUpdate.AgentMessageChunk) {
                        val t = SystemClock.elapsedRealtimeNanos()
                        val meta = u._meta as? JsonObject
                        val seq = meta?.get("seq")?.jsonPrimitive?.intOrNull ?: -1
                        meta?.get("t")?.jsonPrimitive?.longOrNull?.let { run.lat.add(t - it) }
                        if (seq != run.lastSeq + 1) run.outOfOrder++
                        run.lastSeq = seq
                        if (run.chunks == 0) run.firstChunkNs = t
                        run.chunks++
                        val piece = (u.content as? ContentBlock.Text)?.text.orEmpty()
                        val len = piece.length
                        if (run.text.length < PromptRun.TEXT_CAP) run.text.append(piece.take(PromptRun.TEXT_CAP - run.text.length))
                        run.chunkChars += len
                        if (len > run.maxChunkChars) run.maxChunkChars = len
                        if (t - lastUi > 250_000_000) {
                            lastUi = t
                            onStatus("chunks=${run.chunks}")
                        }
                        onChunk(run)
                    } else if (u is SessionUpdate.ToolCall) {
                        run.toolCalls++
                    } else if (u is SessionUpdate.ToolCallUpdate) {
                        run.toolStatuses += u.status?.name ?: "NONE"
                    }
                }
                is Event.PromptResponseEvent -> run.stopReason = ev.response.stopReason.name
            }
        }
    } catch (e: CancellationException) {
        run.error = e
        if (!currentCoroutineContext().isActive) throw e
    } catch (e: Exception) {
        run.error = e
    }
    run.endNs = SystemClock.elapsedRealtimeNanos()
}

/**
 * 假 Agent 的指令（测试 Agent App 和 AgentOS 的占位 Agent 都认）：
 * chunks、chunkChars、intervalMs、burst（每个间隔发几条）、cjk、bp（生产者背压）、bpChars、bigChunkChars。
 */
fun agentCommand(args: JSONObject, defaults: JSONObject = JSONObject()): String = JSONObject()
    .put("chunks", args.optInt("chunks", defaults.optInt("chunks", 5000)))
    .put("chunkChars", args.optInt("chunkChars", defaults.optInt("chunkChars", 32)))
    .put("intervalMs", args.optLong("intervalMs", defaults.optLong("intervalMs", 0)))
    .put("burst", args.optInt("burst", defaults.optInt("burst", 1)))
    .put("bp", args.optBoolean("bp", defaults.optBoolean("bp", false)))
    .put("bpChars", args.optLong("bpChars", 16_384))
    .put("cjk", args.optBoolean("cjk", false))
    .toString()
