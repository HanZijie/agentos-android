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
import org.agentos.app.agent.acp.AcpDecision
import org.agentos.app.agent.acp.ResolvedCaller
import org.agentos.app.agent.acp.RevocationOwners
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
 * 每条通道绑定调用方 UID（`Binder.getCallingUid()`，不信任客户端自报）。AgentOS 自己直接放行；第三方 App 要先被用户允许
 * （docs/third-party-acp.md 4.1，[AcpAccessPolicy] + [CallerRegistry]）：open **不阻塞**，没决定时立刻抛
 * `agentos.acp.authorization_pending`，SDK 每秒重试。拒绝原因码见 [AcpServiceContract]。
 */
class AcpService : Service() {
    private lateinit var runtime: AgentProcess

    private val binder = object : IAcpService.Stub() {
        override fun open(client: IChannel?): IChannel {
            val uid = Binder.getCallingUid()
            return when (val d = runtime.admitAcp(uid)) {
                is AcpDecision.Reject -> {
                    runtime.acp.onRejected(uid, d.reason)
                    throw SecurityException(d.message)
                }
                is AcpDecision.Open -> runtime.acp.open(requireNotNull(client) { "client channel is null" }, uid, d.caller, d.app)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runtime = AgentProcess.get(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder
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
    private val uidsSeen = ConcurrentHashMap<String, MutableSet<Int>>()
    private val opened = AtomicLong()
    private val closed = AtomicLong()
    private val rejected = AtomicLong()
    private val closeRecords = ConcurrentLinkedDeque<JSONObject>()

    private class Conn(
        val id: Int,
        val peerUid: Int,
        val transport: BinderAcpTransport,
        val openedAt: Long,
        val caller: CallerIdentity,
        val app: ResolvedCaller?,
    )

    fun open(client: IChannel, uid: Int, caller: CallerIdentity, app: ResolvedCaller? = null): IChannel {
        val id = nextId.incrementAndGet()
        val name = "acp-conn-$id(uid=$uid)"
        val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + CoroutineName(name))
        val transport = BinderAcpTransport.accept(client, uid, scope, ChannelConfig.DEFAULT, name)
        conns[id] = Conn(id, uid, transport, SystemClock.elapsedRealtime(), caller, app)
        // remember which uids a package opened channels with: a task outlives its channel (F7), and revoking must still find it
        if (caller.kind == CallerKind.APP && app != null) uidsSeen.getOrPut(app.packageName) { ConcurrentHashMap.newKeySet() }.add(uid)
        opened.incrementAndGet()
        transport.onClose {
            onClosed(id, transport.channel.closeCauseOrNull?.toString() ?: "unknown")
            scope.cancel()
        }
        // 调用方身份由 AcpAccessPolicy 按 UID 定：AgentOS 自己 SELF，已被用户允许的第三方 APP（packageName = 按 UID 解析出的包名）。
        // 绝不能把第三方建成 SELF：SELF 能看到所有会话
        process.runtime.serveAcp(transport, caller, OutboundGate { transport.awaitWritable(OutboundGate.BINDER_HIGH_WATER_CHARS) })
        Log.i(TAG, "opened $name kind=${caller.kind}")
        return transport.channel.binder
    }

    fun onRejected(uid: Int, reason: String) {
        rejected.incrementAndGet()
        Log.i(TAG, "rejected ACP open from uid $uid: ${reason.removePrefix(AcpServiceContract.REASON_PREFIX)}")
    }

    /** 这个 App（包名 + 签名摘要）现在开着的通道数。 */
    fun channelsOf(packageName: String): Int = conns.values.count { it.app?.packageName == packageName }

    /**
     * 撤销：关掉这个 App 现有的全部通道（包名相同、签名摘要相同或不限）。关通道不会取消任务（F7），任务由 [AgentProcess.cancelTasksOf] 取消。
     * 返回关掉的通道数。
     */
    fun closeFor(packageName: String, signingDigest: String?, reason: String): Int {
        val victims = conns.values.filter { it.app?.packageName == packageName && (signingDigest == null || it.app.signingDigest == signingDigest) }
        for (c in victims) c.transport.channel.close(reason)
        return victims.size
    }

    /**
     * 撤销时要替哪些归属键取消任务：开着的通道的 UID（**关通道之前**取）+ 这个包名见过的 UID + 它现在安装的 UID，见 [RevocationOwners]。
     * 通道关了任务还在跑（F7）是常态，所以不能只看开着的通道。
     */
    fun ownersOf(packageName: String, installedUid: Int?): List<CallerIdentity> =
        RevocationOwners.of(packageName, callersOf(packageName), uidsSeen[packageName].orEmpty().toSet(), installedUid, Process.myUid())

    /** 这个 App 开着的通道里的调用方身份。 */
    fun callersOf(packageName: String): List<CallerIdentity> = conns.values.filter { it.app?.packageName == packageName }.map { it.caller }.distinct()

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
        for (c in conns.values) {
            open.put(
                JSONObject().put("id", c.id).put("peerUid", c.peerUid).put("kind", c.caller.kind.name)
                    .put("package", c.app?.packageName ?: JSONObject.NULL).put("transport", c.transport.stats())
            )
        }
        val rs = process.runtime.runState.value
        val transportScopes = parent.coroutineContext[Job]?.children?.count() ?: -1
        return JSONObject()
            .put("pid", Process.myPid())
            .put("connectionsOpen", conns.size)
            .put("connectionsOpened", opened.get())
            .put("connectionsClosed", closed.get())
            .put("rejectedOpens", rejected.get())
            .put("thirdPartyChannels", conns.values.count { it.caller.kind == CallerKind.APP })
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
