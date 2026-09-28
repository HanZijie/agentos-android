package org.agentos.spike.s3.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import org.agentos.acp.BinderAcpTransport
import org.agentos.channel.BinderChannel
import org.agentos.channel.ChannelConfig
import org.agentos.channel.CloseCause
import org.agentos.channel.IAcpService
import org.agentos.spike.s3.api.SpikeIds
import java.util.concurrent.Executors

private val callbackExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "service-conn") }

/** bindService 的协程封装；回调不在主线程。 */
class ServiceBinding(private val ctx: Context, private val component: ComponentName) : ServiceConnection {
    private val connected = Channel<IBinder>(Channel.CONFLATED)
    @Volatile var binder: IBinder? = null
        private set
    @Volatile var connectedAtNs = 0L
        private set
    @Volatile var disconnectedAtNs = 0L
        private set
    @Volatile var connectCount = 0
        private set
    private var bound = false

    fun bind(): Boolean {
        val intent = Intent().setComponent(component)
        bound = ctx.bindService(intent, Context.BIND_AUTO_CREATE, callbackExecutor, this)
        return bound
    }

    /** 等下一次 onServiceConnected（包括进程死亡后系统重建服务的那一次）。 */
    suspend fun awaitConnected(timeoutMs: Long): IBinder? = withTimeoutOrNull(timeoutMs) { connected.receive() }

    fun unbind() {
        if (bound) runCatching { ctx.unbindService(this) }
        bound = false
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder) {
        binder = service
        connectedAtNs = SystemClock.elapsedRealtimeNanos()
        connectCount++
        connected.trySend(service)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        binder = null
        disconnectedAtNs = SystemClock.elapsedRealtimeNanos()
    }
}

private object NoopClientOps : ClientSessionOperations {
    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse = RequestPermissionResponse(RequestPermissionOutcome.Cancelled)

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit
}

/** 一条 ACP 连接：bindService → IAcpService.open → BinderAcpTransport → SDK Client。 */
class AcpConn(private val ctx: Context, val config: ChannelConfig, parent: CoroutineScope, val label: String = "client") {
    val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + CoroutineName(label))
    val binding = ServiceBinding(ctx, SpikeIds.ACP_SERVICE)
    lateinit var channel: BinderChannel
        private set
    lateinit var transport: BinderAcpTransport
        private set
    lateinit var protocol: Protocol
        private set
    lateinit var client: Client
        private set

    val timings = linkedMapOf<String, Double>()

    private fun mark(name: String, t0: Long) {
        timings[name] = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
    }

    /** 返回 initialize 的结果；各阶段耗时记在 [timings]（毫秒，从调用开始算）。 */
    suspend fun connect(timeoutMs: Long = 15_000): AgentInfo {
        val t0 = SystemClock.elapsedRealtimeNanos()
        check(binding.bind()) { "bindService returned false" }
        val b = binding.awaitConnected(timeoutMs) ?: error("service not connected in $timeoutMs ms")
        mark("bound", t0)
        openOn(b)
        mark("opened", t0)
        val info = withTimeout(timeoutMs) {
            client.initialize(ClientInfo(implementation = Implementation("agentos-s3-spike-client", "0.1")))
        }
        mark("initialized", t0)
        return info
    }

    suspend fun openOn(b: IBinder) {
        val serverUid = ctx.packageManager.getPackageUid(SpikeIds.SERVER_PKG, 0)
        channel = BinderChannel("$label-channel", serverUid, config, scope)
        transport = BinderAcpTransport(channel, scope)
        protocol = Protocol(scope, transport, ProtocolOptions(protocolDebugName = label))
        client = Client(protocol)
        BinderAcpTransport.bindTo(protocol, transport)
        protocol.start()
        val agentEnd = try {
            withContext(Dispatchers.IO) { IAcpService.Stub.asInterface(b).open(channel.binder) }
        } catch (e: Exception) {
            protocol.close()
            throw e
        }
        channel.attachPeer(agentEnd)
    }

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
