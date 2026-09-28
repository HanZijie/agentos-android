package org.agentos.test.acp.agent

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Debug
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.agentos.acp.AcpAndroid
import org.agentos.acp.BinderAcpTransport
import org.agentos.channel.BinderChannel
import org.agentos.channel.ChannelConfig
import org.agentos.channel.IAcpService
import org.agentos.channel.IChannel
import org.agentos.test.acp.ITestProbe
import org.agentos.test.acp.threadCount
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** :agent 进程里的共享状态：所有 ACP 连接与统计。 */
object AgentHost {
    private const val TAG = "AcpTestAgent"

    init {
        AcpAndroid.ensureInitialized()
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("agent-host"))

    @Volatile var channelConfig = ChannelConfig.DEFAULT

    private val nextId = AtomicInteger()
    private val conns = ConcurrentHashMap<Int, Conn>()
    private val opened = AtomicLong()
    private val closed = AtomicLong()
    private val closeRecords = ConcurrentLinkedDeque<JSONObject>()
    private val promptsActive = AtomicInteger()
    private val promptOutcomes = ConcurrentHashMap<String, AtomicLong>()

    private class Conn(val id: Int, val peerUid: Int, val transport: BinderAcpTransport, val openedAt: Long)

    fun open(client: IChannel, uid: Int): IChannel {
        val id = nextId.incrementAndGet()
        val name = "agent-conn-$id(uid=$uid)"
        val connScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + CoroutineName(name))
        val transport = BinderAcpTransport.accept(client, uid, connScope, channelConfig, name)
        val protocol = Protocol(connScope, transport, ProtocolOptions(protocolDebugName = name))
        Agent(protocol, TestAgentSupport(id, transport))
        BinderAcpTransport.bindTo(protocol, transport)
        conns[id] = Conn(id, uid, transport, SystemClock.elapsedRealtime())
        opened.incrementAndGet()
        transport.onClose {
            onClosed(id, transport.channel.closeCauseOrNull?.toString() ?: "unknown")
            connScope.cancel()
        }
        protocol.start()
        Log.i(TAG, "opened $name")
        return transport.channel.binder
    }

    private fun onClosed(id: Int, cause: String) {
        val c = conns.remove(id) ?: return
        closed.incrementAndGet()
        closeRecords.addLast(
            JSONObject().put("id", id).put("peerUid", c.peerUid).put("cause", cause)
                .put("aliveMs", SystemClock.elapsedRealtime() - c.openedAt)
                .put("closedAtNs", SystemClock.elapsedRealtimeNanos())
        )
        while (closeRecords.size > 30) closeRecords.pollFirst()
    }

    fun promptStarted() {
        promptsActive.incrementAndGet()
    }

    fun promptEnded(outcome: String) {
        promptsActive.decrementAndGet()
        promptOutcomes.getOrPut(outcome) { AtomicLong() }.incrementAndGet()
    }

    fun reset() {
        closeRecords.clear()
        promptOutcomes.clear()
        opened.set(conns.size.toLong())
        closed.set(0)
    }

    fun stats(): JSONObject {
        val open = JSONArray()
        for (c in conns.values) {
            open.put(JSONObject().put("id", c.id).put("peerUid", c.peerUid).put("transport", c.transport.stats()))
        }
        val outcomes = JSONObject()
        promptOutcomes.forEach { (k, v) -> outcomes.put(k, v.get()) }
        return JSONObject()
            .put("pid", Process.myPid())
            .put("connectionsOpen", conns.size)
            .put("connectionsOpened", opened.get())
            .put("connectionsClosed", closed.get())
            .put("liveChannels", BinderChannel.liveChannels)
            .put("hostJobChildren", scope.coroutineContext[Job]?.children?.count() ?: -1)
            .put("promptsActive", promptsActive.get())
            .put("promptOutcomes", outcomes)
            .put("mainThreadBinderCalls", BinderChannel.mainThreadBinderCalls)
            .put("closeNotifyRetries", BinderChannel.closeNotifyRetries)
            .put("ackRetries", BinderChannel.ackRetries)
            .put("threads", threadCount())
            .put("pssKb", Debug.getPss())
            .put("channelConfig", channelConfig.toJson())
            .put("open", open)
            .put("recentCloses", JSONArray(closeRecords.toList()))
    }
}

/** 与 AgentOS 的 AcpService 同形；测试 App 不做 UID 准入，只把调用方 UID 绑定到通道上。 */
class AcpService : Service() {
    private val binder = object : IAcpService.Stub() {
        override fun open(client: IChannel?): IChannel =
            AgentHost.open(requireNotNull(client) { "client channel is null" }, Binder.getCallingUid())
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

class ProbeService : Service() {
    private val binder = object : ITestProbe.Stub() {
        override fun stats(): String = AgentHost.stats().toString()
        override fun setChannelConfig(json: String?) {
            AgentHost.channelConfig = if (json.isNullOrBlank()) ChannelConfig.DEFAULT else ChannelConfig.fromJson(JSONObject(json))
        }
        override fun resetStats() = AgentHost.reset()
        override fun killProcess() = Process.killProcess(Process.myPid())
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
