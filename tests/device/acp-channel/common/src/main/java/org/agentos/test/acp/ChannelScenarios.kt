package org.agentos.test.acp

import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.agentos.acp.AcpServiceContract
import org.agentos.acp.BinderAcpTransport
import org.agentos.channel.BinderChannel
import org.agentos.channel.ChannelConfig
import org.agentos.channel.CloseCause
import org.json.JSONArray
import org.json.JSONObject

/**
 * 被测的 ACP 服务端：SDK 回归用测试 Agent App，W6 用 AgentOS 的 :agent。
 *
 * [stats] 的字段：pid、connectionsOpen、connectionsOpened、connectionsClosed、liveChannels、hostJobChildren、
 * promptsActive、promptOutcomes、threads、pssKb、mainThreadBinderCalls、recentCloses[{id,peerUid,cause,closedAtNs}]、
 * open[{id,peerUid,transport}]。
 */
interface AcpTarget {
    val name: String
    val acpComponent: ComponentName
    /** 能否调整服务端的通道参数；不能时只跑默认参数的用例。 */
    val supportsChannelConfig: Boolean

    /**
     * 服务端是真正的 AgentOS 宿主层（A3 起）：文字增量由宿主层按 32 ms 合并、长文字切成不超过 8,192 字符的块、
     * 没有 _meta.seq / t，所以只能按“文字完整”校验，不能按条数和顺序号；连接断开不取消任务（F7）。
     * 测试 Agent App（SDK 回归）逐条原样发出，按条数校验。
     */
    val hostRuntime: Boolean get() = false

    suspend fun stats(): JSONObject
    suspend fun setChannelConfig(cfg: JSONObject?)
    /** 对服务端进程发 SIGKILL。 */
    suspend fun killServer()
}

/** 假 Agent 按 [agentCommand] 输出的总字符数（chunkChars=0 时每条是 "chunk i "）。 */
fun expectedChars(chunks: Int, chunkChars: Int, bigChunkChars: Int = 0): Long =
    (if (chunkChars > 0) chunks.toLong() * chunkChars else (0 until chunks).sumOf { "chunk $it ".length.toLong() }) + bigChunkChars

/** 测试 Agent App，经 [ITestProbe] 读状态、调参数、自杀。 */
class ProbeTarget(private val ctx: Context) : AcpTarget {
    override val name = "test-agent"
    override val acpComponent: ComponentName = TestIds.TEST_AGENT_ACP
    override val supportsChannelConfig = true

    private suspend fun <T> withProbe(block: (ITestProbe) -> T): T {
        val b = ServiceBinding(ctx, TestIds.TEST_AGENT_PROBE)
        check(b.bind()) { "cannot bind probe" }
        try {
            val binder = b.awaitConnected(15_000) ?: error("probe not connected")
            return withContext(Dispatchers.IO) { block(ITestProbe.Stub.asInterface(binder)) }
        } finally {
            b.unbind()
        }
    }

    override suspend fun stats(): JSONObject = withProbe { JSONObject(it.stats()) }
    override suspend fun setChannelConfig(cfg: JSONObject?) = withProbe { it.setChannelConfig(cfg?.toString() ?: "") }
    override suspend fun killServer() = withProbe { it.killProcess() }
}

/** ACP 通道的场景：SDK 回归（W5）和 AgentOS :agent 的设备用例（W6）共用。 */
class ChannelScenarios(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val runId: String,
    private val target: AcpTarget,
    private val status: (String) -> Unit,
) {
    suspend fun run(name: String, args: JSONObject): JSONObject? = when (name) {
        "handshake" -> handshake(args)
        "stream" -> stream(args)
        "cancel" -> cancel(args)
        "reconnect" -> reconnect(args)
        "server-kill" -> serverKill(args)
        "client-kill" -> clientKill(args)
        "server-stats" -> JSONObject().put("ok", true).put("server", target.stats()).put("client", clientStats())
        "oversize" -> oversize(args)
        "window-violation" -> windowViolation(args)
        "foreign-open" -> foreignOpen(args)
        "control-foreign" -> controlForeign()
        else -> null
    }

    private fun now() = SystemClock.elapsedRealtimeNanos()
    private fun ms(fromNs: Long, toNs: Long = now()) = (toNs - fromNs) / 1e6

    fun clientStats(): JSONObject = JSONObject()
        .put("pid", Process.myPid())
        .put("liveChannels", BinderChannel.liveChannels)
        .put("mainThreadBinderCalls", BinderChannel.mainThreadBinderCalls)
        .put("closeNotifyRetries", BinderChannel.closeNotifyRetries)
        .put("ackRetries", BinderChannel.ackRetries)
        .put("threads", threadCount())

    /** 两端用同一组通道参数：服务端经探针设置，本端直接用。服务端不支持调参时，只接受默认参数。 */
    private suspend fun applyConfig(args: JSONObject): ChannelConfig {
        val json = args.optJSONObject("cfg")
        if (!target.supportsChannelConfig) {
            require(json == null) { "${target.name} only runs with the default channel config" }
            return ChannelConfig.DEFAULT
        }
        target.setChannelConfig(json)
        return ChannelConfig.fromJson(json)
    }

    private suspend fun resetConfig() {
        if (target.supportsChannelConfig) runCatching { target.setChannelConfig(null) }
    }

    private fun conn(cfg: ChannelConfig, label: String) = AcpConn(ctx, target.acpComponent, cfg, scope, label)

    private suspend fun prompt(session: com.agentclientprotocol.client.ClientSession, text: String, run: PromptRun,
                               onChunk: (PromptRun) -> Unit = {}) = runPrompt(session, text, run, status, onChunk)

    private fun pick(s: JSONObject) = JSONObject()
        .put("pid", s.optInt("pid"))
        .put("connectionsOpen", s.optInt("connectionsOpen"))
        .put("connectionsOpened", s.optLong("connectionsOpened"))
        .put("connectionsClosed", s.optLong("connectionsClosed"))
        .put("liveChannels", s.optInt("liveChannels"))
        .put("hostJobChildren", s.optInt("hostJobChildren"))
        .put("promptsActive", s.optInt("promptsActive"))
        .put("promptOutcomes", s.optJSONObject("promptOutcomes"))
        .put("threads", s.optInt("threads"))
        .put("pssKb", s.optLong("pssKb"))
        .put("mainThreadBinderCalls", s.optLong("mainThreadBinderCalls"))

    private fun noServerLeak(s: JSONObject) =
        s.optInt("connectionsOpen") == 0 && s.optInt("liveChannels") == 0 &&
            s.optInt("promptsActive") == 0 && s.optInt("hostJobChildren") == 0

    // ------------------------------------------------------------------

    /** initialize → session/new → session/prompt（流式）→ close。 */
    private suspend fun handshake(args: JSONObject): JSONObject {
        val cfg = applyConfig(args)
        val c = conn(cfg, "handshake")
        try {
            val t0 = now()
            val info = c.connect()
            val session = c.newSession()
            val tSession = ms(t0)
            val run = PromptRun()
            prompt(session, """{"chunks":20,"intervalMs":0}""", run)
            val cause = c.closeAndWait()
            val textOk = if (target.hostRuntime) run.chunkChars == expectedChars(20, 0) else run.chunks == 20 && run.outOfOrder == 0
            return JSONObject()
                .put("ok", run.stopReason == "END_TURN" && textOk && cause?.kind == CloseCause.KIND_LOCAL)
                .put("agent", info.implementation?.name ?: JSONObject.NULL)
                .put("protocolVersion", info.protocolVersion.toString())
                .put("sessionId", session.sessionId.value)
                .put("timingsMs", JSONObject(c.timings as Map<*, *>).put("session", tSession))
                .put("prompt", run.json())
                .put("closeCause", cause?.toString() ?: JSONObject.NULL)
        } finally {
            c.dispose()
            resetConfig()
        }
    }

    /** 流式：端到端延迟、吞吐、主线程是否被阻塞、两端通道统计。 */
    private suspend fun stream(args: JSONObject): JSONObject {
        val cfg = applyConfig(args)
        val c = conn(cfg, "stream")
        try {
            c.connect()
            val session = c.newSession()
            val wd = MainThreadWatchdog()
            wd.start()
            val run = PromptRun()
            prompt(session, agentCommand(args), run)
            val main = wd.stop()
            val server = target.stats()
            val clientTransport = c.transport.stats()
            val cause = c.closeAndWait()
            val expected = args.optInt("chunks", 5000)
            val textOk = if (target.hostRuntime) {
                run.chunkChars == expectedChars(expected, args.optInt("chunkChars", 32))
            } else {
                run.chunks == expected && run.outOfOrder == 0
            }
            return JSONObject()
                .put("ok", run.stopReason == "END_TURN" && textOk &&
                    BinderChannel.mainThreadBinderCalls == 0L && server.optLong("mainThreadBinderCalls") == 0L)
                .put("expectedChars", expectedChars(expected, args.optInt("chunkChars", 32)))
                .put("cfg", cfg.toJson())
                .put("cmd", JSONObject(agentCommand(args)))
                .put("prompt", run.json())
                .put("mainThread", main)
                .put("clientTransport", clientTransport)
                .put("serverConnections", server.optJSONArray("open"))
                .put("serverMainThreadBinderCalls", server.optLong("mainThreadBinderCalls"))
                .put("client", clientStats())
                .put("closeCause", cause?.toString() ?: JSONObject.NULL)
        } finally {
            c.dispose()
            resetConfig()
        }
    }

    /** 流式中途 session/cancel：本轮以 CANCELLED 结束，同一会话随后还能继续。 */
    private suspend fun cancel(args: JSONObject): JSONObject {
        val cfg = applyConfig(args)
        val c = conn(cfg, "cancel")
        try {
            c.connect()
            val session = c.newSession()
            val cancelAfter = args.optInt("cancelAfterChunks", 100)
            val cmd = agentCommand(args, JSONObject().put("chunks", 1_000_000).put("intervalMs", 5))
            val run = PromptRun()
            var tCancel = 0L
            var chunksAtCancel = 0
            coroutineScope {
                val cs = this
                prompt(session, cmd, run) { r ->
                    if (tCancel == 0L && r.chunks >= cancelAfter) {
                        tCancel = now()
                        chunksAtCancel = r.chunks
                        cs.launch { session.cancel() }
                    }
                }
            }
            val after = PromptRun()
            prompt(session, """{"chunks":5,"intervalMs":0}""", after)
            val server = target.stats()
            val cause = c.closeAndWait()
            return JSONObject()
                .put("ok", run.stopReason == "CANCELLED" && after.stopReason == "END_TURN")
                .put("cmd", JSONObject(cmd))
                .put("cancelled", run.json())
                .put("cancelToStopMs", if (tCancel > 0) ms(tCancel, run.endNs) else -1.0)
                .put("chunksAfterCancel", run.chunks - chunksAtCancel)
                .put("followUp", after.json())
                .put("serverPromptOutcomes", server.optJSONObject("promptOutcomes"))
                .put("closeCause", cause?.toString() ?: JSONObject.NULL)
        } finally {
            c.dispose()
            resetConfig()
        }
    }

    /** 反复 bind → 对话 → close（或不 close 直接丢弃）→ unbind，检查两端没有残留。 */
    private suspend fun reconnect(args: JSONObject): JSONObject {
        val cfg = applyConfig(args)
        val iterations = args.optInt("iterations", 20)
        val closeFirst = args.optBoolean("closeFirst", true)
        val before = target.stats()
        val iters = JSONArray()
        var okCount = 0
        for (i in 0 until iterations) {
            val c = conn(cfg, "reconnect-$i")
            val t0 = now()
            try {
                c.connect()
                val s = c.newSession()
                val run = PromptRun()
                prompt(s, """{"chunks":5,"intervalMs":0}""", run)
                val cause = if (closeFirst) c.closeAndWait() else null
                val ok = run.stopReason == "END_TURN" && (!closeFirst || cause?.kind == CloseCause.KIND_LOCAL)
                if (ok) okCount++
                iters.put(JSONObject().put("i", i).put("ok", ok).put("ms", ms(t0)))
            } catch (e: Exception) {
                iters.put(JSONObject().put("i", i).put("ok", false).put("error", errJson(e)))
            } finally {
                c.dispose()
            }
            status("reconnect $i")
        }
        // 服务端经对端的 close（或作用域取消时的 close 通知）结束连接，给它一点时间
        var after = target.stats()
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!noServerLeak(after) && SystemClock.elapsedRealtime() < deadline) {
            delay(200)
            after = target.stats()
        }
        resetConfig()
        val noLeak = noServerLeak(after) && BinderChannel.liveChannels == 0
        return JSONObject()
            .put("ok", okCount == iterations && noLeak)
            .put("noLeak", noLeak)
            .put("iterations", iterations).put("okCount", okCount).put("closeFirst", closeFirst)
            .put("iters", iters)
            .put("serverBefore", pick(before))
            .put("serverAfter", pick(after))
            .put("client", clientStats())
    }

    /** 流式中途 SIGKILL 服务端：本端经 linkToDeath 感知、结束挂起的 prompt；服务重建后能重新对话。 */
    private suspend fun serverKill(args: JSONObject): JSONObject {
        val cfg = applyConfig(args)
        val before = target.stats()
        val c = conn(cfg, "server-kill")
        val c2 = conn(cfg, "after-kill")
        try {
            c.connect()
            val session = c.newSession()
            val killAfter = args.optInt("killAfterChunks", 50)
            val run = PromptRun()
            var tKill = 0L
            var detectMs = -1.0
            var cause: CloseCause? = null
            var promptHung = false
            coroutineScope {
                val cs = this
                val job = launch {
                    prompt(session, agentCommand(args, JSONObject().put("chunks", 1_000_000).put("intervalMs", 5)), run) { r ->
                        if (tKill == 0L && r.chunks >= killAfter) {
                            tKill = now()
                            cs.launch { target.killServer() }
                        }
                    }
                }
                val cc = withTimeoutOrNull(15_000) { c.channel.closeCause.await() }
                if (cc != null && tKill > 0) detectMs = ms(tKill)
                cause = cc
                if (withTimeoutOrNull(10_000) { job.join() } == null) {
                    promptHung = true
                    job.cancel()
                }
            }
            val clientLiveAfterDeath = BinderChannel.liveChannels
            // BIND_AUTO_CREATE：进程死后系统重建服务，再次回调 onServiceConnected
            val restarted = c.binding.awaitConnected(20_000)
            val restartMs = if (restarted != null && tKill > 0) ms(tKill, c.binding.connectedAtNs) else -1.0
            var recoveredMs = -1.0
            val after = PromptRun()
            if (restarted != null) {
                c2.openOn(restarted)
                c2.initialize()
                val s2 = c2.newSession()
                prompt(s2, """{"chunks":5,"intervalMs":0}""", after)
                recoveredMs = ms(tKill)
                c2.closeAndWait()
            }
            c.dispose()
            c2.dispose()
            delay(500)
            val server = target.stats()
            return JSONObject()
                .put("ok", cause?.kind == CloseCause.KIND_PEER_DIED && !promptHung && after.stopReason == "END_TURN" &&
                    server.optInt("pid") != before.optInt("pid") && BinderChannel.liveChannels == 0)
                .put("killedAfterChunks", run.chunks)
                .put("closeCause", cause?.toString() ?: JSONObject.NULL)
                .put("detectMs", detectMs)
                .put("promptError", errJson(run.error))
                .put("promptEndMs", if (tKill > 0) ms(tKill, run.endNs) else -1.0)
                .put("promptHung", promptHung)
                .put("serviceRestartMs", restartMs)
                .put("recoveredMs", recoveredMs)
                .put("afterRestartPrompt", after.json())
                .put("clientLiveChannelsAfterDeath", clientLiveAfterDeath)
                .put("serverBefore", pick(before))
                .put("serverAfter", pick(server))
                .put("client", clientStats())
        } finally {
            c.dispose()
            c2.dispose()
            resetConfig()
        }
    }

    /** 流式中途本进程自杀。主机端随后跑 server-stats，确认服务端关闭了通道、结束了 prompt。 */
    private suspend fun clientKill(args: JSONObject): JSONObject {
        val cfg = applyConfig(args)
        val c = conn(cfg, "client-kill")
        c.connect()
        val session = c.newSession()
        val killAfter = args.optInt("killAfterChunks", 50)
        val run = PromptRun()
        prompt(session, agentCommand(args, JSONObject().put("chunks", 1_000_000).put("intervalMs", 5)), run) { r ->
            if (r.chunks >= killAfter) {
                Results.emit(
                    runId, JSONObject().put("scenario", "client-kill").put("phase", "dying")
                        .put("pid", Process.myPid()).put("dyingAtNs", now()).put("chunks", r.chunks)
                )
                Process.killProcess(Process.myPid())
            }
        }
        return JSONObject().put("ok", false).put("error", "process should have died").put("prompt", run.json())
    }

    /** 超长消息：发送端拒发并本地报错；接收端收到超长消息视为违规并关闭通道。 */
    private suspend fun oversize(args: JSONObject): JSONObject {
        val cfg = applyConfig(args)
        val c = conn(cfg, "oversize")
        try {
            c.connect()
            val session = c.newSession()
            val max = cfg.maxMessageChars
            val out = JSONObject().put("maxMessageChars", max)

            // A. 本端发超长 prompt：BinderAcpTransport 不发，本地合成错误响应，连接不受影响
            val a = PromptRun()
            prompt(session, "z".repeat(max + 1000), a)
            out.put("A_clientOversizePrompt", JSONObject()
                .put("ok", a.error != null && a.stopReason == null &&
                    (a.error?.message ?: "").startsWith(BinderAcpTransport.TOO_LARGE_PREFIX))
                .put("error", errJson(a.error)).put("ms", (a.endNs - a.startNs) / 1e6))

            // B. 上限以内的 prompt 正常
            val b = PromptRun()
            prompt(session, "w".repeat(max - 1024), b)
            out.put("B_nearLimitPrompt", JSONObject().put("ok", b.stopReason == "END_TURN").put("prompt", b.json()))

            // C. 服务端要发超长通知：测试 Agent 丢弃该条、本轮其他消息照常；AgentOS 宿主层把长文字切成不超过 8,192 字符的块，
            //    根本不会产生超长通知；文字要完整送达（A4 起宿主层把单条超长增量先切到 ≤ 8,192 字符再写日志，
            //    之前按 events.md 第 5 节被截断到 16,384 字符，C3 发现）
            val c3 = PromptRun()
            val big = max + 100
            prompt(session, JSONObject().put("chunks", 3).put("intervalMs", 0).put("bigChunkChars", big).toString(), c3)
            val server = target.stats()
            val dropped = server.optJSONArray("open")?.let { arr ->
                (0 until arr.length()).sumOf { arr.getJSONObject(it).optJSONObject("transport")?.optLong("droppedTooLarge") ?: 0L }
            } ?: -1L
            val cOk = if (target.hostRuntime) {
                c3.stopReason == "END_TURN" && c3.chunks >= 2 && c3.maxChunkChars <= 8_192 && dropped == 0L &&
                    c3.chunkChars == expectedChars(3, 0, big)
            } else {
                c3.stopReason == "END_TURN" && c3.chunks == 3 && dropped >= 1
            }
            out.put("C_agentOversizeNotification", JSONObject()
                .put("ok", cOk).put("hostSplits", target.hostRuntime)
                .put("textComplete", c3.chunkChars == expectedChars(3, 0, big)).put("expectedChars", expectedChars(3, 0, big))
                .put("prompt", c3.json()).put("serverDroppedTooLarge", dropped))

            // D. 绕过 SDK 直接往服务端的接收端塞一条超长消息：服务端判违规并关闭通道
            val peer = c.channel.peerForTesting!!
            val t0 = now()
            withContext(Dispatchers.IO) { peer.send("x".repeat(max + 1)) }
            val cause = withTimeoutOrNull(5_000) { c.channel.closeCause.await() }
            out.put("D_rawOversizeInjection", JSONObject()
                .put("ok", cause?.kind == CloseCause.KIND_REMOTE && cause.reason.contains("exceeds"))
                .put("closeCause", cause?.toString() ?: JSONObject.NULL).put("ms", ms(t0)))
            out.put("ok", listOf("A_clientOversizePrompt", "B_nearLimitPrompt", "C_agentOversizeNotification", "D_rawOversizeInjection")
                .all { out.getJSONObject(it).optBoolean("ok") })
            return out
        } finally {
            c.dispose()
            resetConfig()
        }
    }

    /** 绕过发送端流控连续塞消息：服务端判“超出窗口”并关闭通道。 */
    private suspend fun windowViolation(args: JSONObject): JSONObject {
        val cfg = applyConfig(args)
        // 本端关掉入站检查：服务端回的 ack 会超过本端经 SDK 发出的条数
        val c = conn(cfg.copy(enforceInboundLimits = false), "window")
        try {
            c.connect()
            val peer = c.channel.peerForTesting!!
            val units = args.optInt("units", 30_000)
            val n = args.optInt("n", 200)
            val msg = "x".repeat(units)
            var sent = 0
            var sendError: Throwable? = null
            withContext(Dispatchers.IO) {
                for (i in 0 until n) {
                    if (c.channel.closeCauseOrNull != null) break
                    try {
                        peer.send(msg)
                        sent++
                    } catch (e: Exception) {
                        sendError = e
                        break
                    }
                }
            }
            val cause = withTimeoutOrNull(5_000) { c.channel.closeCause.await() }
            return JSONObject()
                .put("ok", cause?.kind == CloseCause.KIND_REMOTE && cause.reason.contains("window"))
                .put("injected", sent).put("units", units)
                .put("sendError", errJson(sendError))
                .put("closeCause", cause?.toString() ?: JSONObject.NULL)
        } finally {
            c.dispose()
            resetConfig()
        }
    }

    /**
     * 第三方 App 碰 AgentOS 的内部组件：绑定不导出的 AgentControlService、启动不导出的 AgentService，都应被系统拒绝。
     */
    private suspend fun controlForeign(): JSONObject {
        val bind = runCatching {
            val b = ServiceBinding(ctx, TestIds.APP_CONTROL)
            val bound = b.bind()
            val connected = if (bound) b.awaitConnected(3_000) != null else false
            b.unbind()
            "bound=$bound connected=$connected"
        }
        val start = runCatching {
            ctx.startForegroundService(android.content.Intent().setComponent(TestIds.APP_AGENT_SERVICE)
                .setAction("org.agentos.action.SUPERVISOR_START").putExtra("org.agentos.extra.REASON", "boot"))
            "started"
        }
        val bindRejected = bind.exceptionOrNull() is SecurityException || bind.getOrNull() == "bound=false connected=false"
        val startRejected = start.exceptionOrNull() is SecurityException
        return JSONObject()
            .put("ok", bindRejected && startRejected)
            .put("summary", "bindRejected=$bindRejected startRejected=$startRejected")
            .put("bind", bind.getOrNull() ?: errJson(bind.exceptionOrNull()))
            .put("start", start.getOrNull() ?: errJson(start.exceptionOrNull()))
    }

    /**
     * 以本进程的 UID 调用另一个 App 的 IAcpService.open，期望被拒：SecurityException，原因码为 args.reason
     * （默认 agentos.acp.not_open）。组件取自 args.pkg / args.cls，默认是 AgentOS 的 AcpService。
     */
    private suspend fun foreignOpen(args: JSONObject): JSONObject {
        val component = ComponentName(
            args.optString("pkg", TestIds.APP_PKG),
            args.optString("cls", TestIds.APP_ACP.className),
        )
        val expected = args.optString("reason", AcpServiceContract.REASON_NOT_OPEN)
        val c = AcpConn(ctx, component, ChannelConfig.DEFAULT, scope, "foreign")
        try {
            check(c.binding.bind()) { "bindService returned false for $component (missing <queries>?)" }
            val b = c.binding.awaitConnected(15_000) ?: error("service not connected")
            val t0 = now()
            val error = runCatching { c.openOn(b) }.exceptionOrNull()
            val openMs = ms(t0)
            val reason = error?.let { AcpServiceContract.reasonOf(it) }
            // 失败时本端的通道由 connect 异步关闭
            val deadline = SystemClock.elapsedRealtime() + 2_000
            while (BinderChannel.liveChannels != 0 && SystemClock.elapsedRealtime() < deadline) delay(20)
            return JSONObject()
                .put("ok", error is SecurityException && reason == expected && BinderChannel.liveChannels == 0)
                .put("myUid", Process.myUid())
                .put("error", errJson(error))
                .put("reason", reason ?: JSONObject.NULL)
                .put("openMs", openMs)
                .put("client", clientStats())
        } finally {
            c.dispose()
        }
    }
}
