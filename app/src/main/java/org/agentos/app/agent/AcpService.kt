package org.agentos.app.agent

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Debug
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.agentos.acp.AcpServiceContract
import org.agentos.acp.BinderAcpTransport
import org.agentos.channel.BinderChannel
import org.agentos.channel.ChannelConfig
import org.agentos.channel.IAcpService
import org.agentos.channel.IChannel
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.OutboundGate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 导出的 ACP 服务（architecture 5.2、5.3；intent action [AcpServiceContract.ACTION]），运行在 `:agent`。
 *
 * 每条通道绑定调用方 UID（`Binder.getCallingUid()`，不信任客户端自报）。M1 只接受 AgentOS App 自己；
 * 其他 UID 在 open 里抛 SecurityException，原因码 [AcpServiceContract.REASON_NOT_OPEN]。W25 放开第三方。
 */
class AcpService : Service() {
    private lateinit var runtime: AgentProcess

    private val binder = object : IAcpService.Stub() {
        override fun open(client: IChannel?): IChannel {
            val uid = Binder.getCallingUid()
            AcpAccessPolicy.check(uid)?.let { reason ->
                runtime.acp.onRejected(uid)
                throw SecurityException(reason)
            }
            return runtime.acp.open(requireNotNull(client) { "client channel is null" }, uid)
        }
    }

    override fun onCreate() {
        super.onCreate()
        runtime = AgentProcess.get(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

/** 谁能打开 ACP 通道。返回 null 表示允许，否则返回 SecurityException 的 message。 */
object AcpAccessPolicy {
    fun check(callerUid: Int, myUid: Int = Process.myUid()): String? =
        if (callerUid == myUid) {
            null
        } else {
            AcpServiceContract.message(
                AcpServiceContract.REASON_NOT_OPEN,
                "this AgentOS version only accepts the AgentOS app itself; third-party apps are not enabled yet",
            )
        }
}

/**
 * `:agent` 里的全部 Binder ACP 连接：每条一个 [BinderAcpTransport]，交给宿主层的
 * `AgentRuntime.serveAcp(transport, caller, gate)`（A1 的接口）；传输关闭时连接随之结束。
 * 统计字段与 tests/device/acp-channel 的 AcpTarget.stats() 一致。
 */
class AcpConnections(private val process: AgentProcess) {
    /** 所有连接作用域的父作用域；它的子协程数就是还没释放的连接数。 */
    private val parent = CoroutineScope(process.scope.coroutineContext + SupervisorJob(process.scope.coroutineContext[Job]) + CoroutineName("acp"))

    private val nextId = AtomicInteger()
    private val conns = ConcurrentHashMap<Int, Conn>()
    private val opened = AtomicLong()
    private val closed = AtomicLong()
    private val rejected = AtomicLong()
    private val closeRecords = ConcurrentLinkedDeque<JSONObject>()

    private class Conn(val id: Int, val peerUid: Int, val transport: BinderAcpTransport, val openedAt: Long)

    fun open(client: IChannel, uid: Int): IChannel {
        val id = nextId.incrementAndGet()
        val name = "acp-conn-$id(uid=$uid)"
        val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + CoroutineName(name))
        val transport = BinderAcpTransport.accept(client, uid, scope, ChannelConfig.DEFAULT, name)
        conns[id] = Conn(id, uid, transport, SystemClock.elapsedRealtime())
        opened.incrementAndGet()
        transport.onClose {
            onClosed(id, transport.channel.closeCauseOrNull?.toString() ?: "unknown")
            scope.cancel()
        }
        // M1 只有本 App 能走到这里（AcpAccessPolicy），所以调用方类别是 SELF；W25 放开第三方后按 UID 解析为 APP
        val caller = CallerIdentity(uid = uid, kind = CallerKind.SELF, label = "AgentOS")
        process.runtime.serveAcp(transport, caller, OutboundGate { transport.awaitWritable(OutboundGate.BINDER_HIGH_WATER_CHARS) })
        Log.i(TAG, "opened $name")
        return transport.channel.binder
    }

    fun onRejected(uid: Int) {
        rejected.incrementAndGet()
        Log.i(TAG, "rejected ACP open from uid $uid (not open)")
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
        Log.i(TAG, "closed conn $id: $cause")
    }

    fun stats(): JSONObject {
        val open = JSONArray()
        for (c in conns.values) open.put(JSONObject().put("id", c.id).put("peerUid", c.peerUid).put("transport", c.transport.stats()))
        val rs = process.runtime.runState.value
        val transportScopes = parent.coroutineContext[Job]?.children?.count() ?: -1
        return JSONObject()
            .put("pid", Process.myPid())
            .put("connectionsOpen", conns.size)
            .put("connectionsOpened", opened.get())
            .put("connectionsClosed", closed.get())
            .put("rejectedOpens", rejected.get())
            .put("liveChannels", BinderChannel.liveChannels)
            // 还没释放的传输作用域（宿主层的 ACP 连接随传输关闭，见 AgentRuntime.serveAcp）
            .put("hostJobChildren", transportScopes)
            // 未结束的任务（执行中 + 排队 + 受理中）。连接断开不取消任务（F7），所以客户端死后它会等任务自己结束才归零
            .put("promptsActive", rs.activeTasks + rs.queuedTasks)
            .put("recoveryPending", rs.recoveryPending)
            .put("mainThreadBinderCalls", BinderChannel.mainThreadBinderCalls)
            .put("closeNotifyRetries", BinderChannel.closeNotifyRetries)
            .put("ackRetries", BinderChannel.ackRetries)
            .put("threads", threadCount())
            .put("pssKb", Debug.getPss())
            .put("open", open)
            .put("recentCloses", JSONArray(closeRecords.toList()))
    }

    private fun threadCount(): Int = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("Threads:") }.substringAfter(":").trim().toInt()
    }.getOrDefault(-1)

    private companion object {
        const val TAG = "AgentAcp"
    }
}
