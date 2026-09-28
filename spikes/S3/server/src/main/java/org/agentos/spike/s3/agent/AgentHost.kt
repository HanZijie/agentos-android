package org.agentos.spike.s3.agent

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.StdioTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import org.agentos.acp.AcpAndroid
import org.agentos.acp.BinderAcpTransport
import org.agentos.channel.BinderChannel
import org.agentos.channel.ChannelConfig
import org.agentos.channel.IChannel
import org.agentos.spike.s3.api.SpikeIds
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** :agent 进程里的共享状态：所有 ACP 连接（Binder 与电脑端 socket）、统计、网关。 */
object AgentHost {
    private const val TAG = "S3Agent"

    init {
        // 电脑端网关不经过 BinderAcpTransport，也要先打开 SDK 的 Android 日志开关
        AcpAndroid.ensureInitialized()
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("agent-host"))

    @Volatile var channelConfig = ChannelConfig()

    private val nextId = AtomicInteger()
    private val conns = ConcurrentHashMap<Int, Conn>()
    private val opened = AtomicLong()
    private val closed = AtomicLong()
    private val closeRecords = ConcurrentLinkedDeque<JSONObject>()

    private val promptsActive = AtomicInteger()
    private val promptsStarted = AtomicLong()
    private val promptOutcomes = ConcurrentHashMap<String, AtomicLong>()

    @Volatile private var gateway: LocalServerSocket? = null
    private val startedAt = SystemClock.elapsedRealtime()

    private class Conn(
        val id: Int,
        val kind: String,
        val peerUid: Int,
        val scope: CoroutineScope,
        val transport: Transport,
        val channel: BinderChannel?,
        val openedAt: Long,
    )

    private fun childScope(name: String) = CoroutineScope(
        scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + CoroutineName(name)
    )

    /** IAcpService.open 的实现：每条通道绑定调用方 UID，交给 SDK 的 Agent 端。 */
    fun openBinder(client: IChannel, uid: Int): IChannel {
        val id = nextId.incrementAndGet()
        val name = "agent-conn-$id(uid=$uid)"
        val connScope = childScope(name)
        val channel = BinderChannel(name, uid, channelConfig, connScope)
        val transport = BinderAcpTransport(channel, connScope)
        val protocol = Protocol(connScope, transport, ProtocolOptions(protocolDebugName = name))
        Agent(protocol, SpikeAgentSupport(id, transport))
        BinderAcpTransport.bindTo(protocol, transport)
        conns[id] = Conn(id, "binder", uid, connScope, transport, channel, SystemClock.elapsedRealtime())
        opened.incrementAndGet()
        transport.onClose {
            onClosed(id, channel.closeCauseOrNull?.toString() ?: "unknown")
            connScope.cancel()
        }
        protocol.start()
        channel.attachPeer(client)
        Log.i(TAG, "opened $name")
        return channel.binder
    }

    /** 电脑端：一条 socket 连接 = 一个 SDK Agent，按行收发 JSON（SDK 自带的 StdioTransport）。 */
    private fun openSocket(socket: LocalSocket) {
        val id = nextId.incrementAndGet()
        val peerUid = runCatching { socket.peerCredentials.uid }.getOrDefault(-1)
        val name = "gateway-conn-$id(uid=$peerUid)"
        val connScope = childScope(name)
        val reader = socket.inputStream.bufferedReader(Charsets.UTF_8)
        val writer = socket.outputStream.bufferedWriter(Charsets.UTF_8)
        val input = flow {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) emit(line)
            }
        }
        val output: suspend (String) -> Unit = { line ->
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
        val transport = StdioTransport(connScope, Dispatchers.IO, input, output, name)
        val protocol = Protocol(connScope, transport, ProtocolOptions(protocolDebugName = name))
        Agent(protocol, SpikeAgentSupport(id, null))
        BinderAcpTransport.bindTo(protocol, transport)
        conns[id] = Conn(id, "socket", peerUid, connScope, transport, null, SystemClock.elapsedRealtime())
        opened.incrementAndGet()
        transport.onClose {
            runCatching { socket.shutdownInput() }
            runCatching { socket.close() }
            onClosed(id, "socket closed")
            connScope.cancel()
        }
        protocol.start()
        Log.i(TAG, "opened $name")
    }

    private fun onClosed(id: Int, cause: String) {
        val c = conns.remove(id) ?: return
        closed.incrementAndGet()
        val rec = JSONObject().put("id", id).put("kind", c.kind).put("peerUid", c.peerUid).put("cause", cause)
            .put("aliveMs", SystemClock.elapsedRealtime() - c.openedAt)
            .put("closedAtNs", SystemClock.elapsedRealtimeNanos())
        closeRecords.addLast(rec)
        while (closeRecords.size > 30) closeRecords.pollFirst()
        Log.i(TAG, "closed conn $id: $cause")
    }

    @Synchronized
    fun startGateway(): Boolean {
        if (gateway != null) return true
        val server = try {
            LocalServerSocket(SpikeIds.GATEWAY_SOCKET)
        } catch (e: IOException) {
            Log.e(TAG, "cannot open abstract socket ${SpikeIds.GATEWAY_SOCKET}", e)
            return false
        }
        gateway = server
        thread(name = "gateway-accept", isDaemon = true) {
            while (true) {
                val s = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                runCatching { openSocket(s) }.onFailure { Log.e(TAG, "gateway open failed", it) }
            }
        }
        Log.i(TAG, "gateway listening on localabstract:${SpikeIds.GATEWAY_SOCKET}")
        return true
    }

    fun promptStarted() {
        promptsActive.incrementAndGet()
        promptsStarted.incrementAndGet()
    }

    fun promptEnded(outcome: String) {
        promptsActive.decrementAndGet()
        promptOutcomes.getOrPut(outcome) { AtomicLong() }.incrementAndGet()
    }

    fun reset() {
        closeRecords.clear()
        promptOutcomes.clear()
        promptsStarted.set(0)
        opened.set(conns.size.toLong())
        closed.set(0)
    }

    fun stats(): JSONObject {
        val open = JSONArray()
        for (c in conns.values) {
            open.put(
                JSONObject().put("id", c.id).put("kind", c.kind).put("peerUid", c.peerUid)
                    .put("transport", (c.transport as? BinderAcpTransport)?.stats() ?: JSONObject().put("state", c.transport.state.value.name))
            )
        }
        val outcomes = JSONObject()
        promptOutcomes.forEach { (k, v) -> outcomes.put(k, v.get()) }
        return JSONObject()
            .put("pid", Process.myPid())
            .put("uptimeMs", SystemClock.elapsedRealtime() - startedAt)
            .put("connectionsOpen", conns.size)
            .put("connectionsOpened", opened.get())
            .put("connectionsClosed", closed.get())
            .put("liveChannels", BinderChannel.liveChannels)
            .put("hostJobChildren", scope.coroutineContext[Job]?.children?.count() ?: -1)
            .put("promptsActive", promptsActive.get())
            .put("promptsStarted", promptsStarted.get())
            .put("promptOutcomes", outcomes)
            .put("mainThreadBinderCalls", BinderChannel.mainThreadBinderCalls)
            .put("threads", threadCount())
            .put("pssKb", Debug.getPss())
            .put("channelConfig", channelConfig.toJson())
            .put("gateway", gateway != null)
            .put("open", open)
            .put("recentCloses", JSONArray(closeRecords.toList()))
    }

    private fun threadCount(): Int = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("Threads:") }.substringAfter(":").trim().toInt()
    }.getOrDefault(-1)
}
