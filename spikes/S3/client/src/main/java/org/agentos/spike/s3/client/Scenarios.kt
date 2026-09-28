package org.agentos.spike.s3.client

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.agentos.channel.BinderChannel
import org.agentos.channel.ChannelConfig
import org.agentos.spike.s3.api.BenchSender
import org.agentos.spike.s3.api.BenchSinkImpl
import org.agentos.spike.s3.api.IBench
import org.agentos.spike.s3.api.ISpikeProbe
import org.agentos.spike.s3.api.SpikeIds
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 所有场景。每个场景返回一个 JSON 结果，由 ScenarioActivity 写到 logcat。 */
class Scenarios(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val runId: String,
    private val status: (String) -> Unit,
) {
    suspend fun run(name: String, args: JSONObject): JSONObject = when (name) {
        "handshake" -> handshake(args)
        "stream" -> stream(args)
        "cancel" -> cancel(args)
        "reconnect" -> reconnect(args)
        "server-kill" -> serverKill(args)
        "client-kill" -> clientKill(args)
        "server-stats" -> JSONObject().put("server", serverStats()).put("client", clientStats())
        "oversize" -> oversize(args)
        "window-violation" -> windowViolation(args)
        "bench-single" -> benchSingle(args)
        "bench-burst" -> benchBurst(args)
        "bench-throughput" -> benchThroughput(args)
        else -> error("unknown scenario: $name")
    }

    // ------------------------------------------------------------------ 工具

    private fun now() = SystemClock.elapsedRealtimeNanos()
    private fun ms(fromNs: Long, toNs: Long = now()) = (toNs - fromNs) / 1e6

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun <T> withProbe(block: suspend (ISpikeProbe) -> T): T {
        val b = ServiceBinding(ctx, SpikeIds.PROBE_SERVICE)
        check(b.bind()) { "cannot bind probe" }
        try {
            val binder = b.awaitConnected(15_000) ?: error("probe not connected")
            return block(ISpikeProbe.Stub.asInterface(binder))
        } finally {
            b.unbind()
        }
    }

    private suspend fun serverStats(): JSONObject = withProbe { p -> JSONObject(io { p.stats() }) }

    private fun clientStats(): JSONObject = JSONObject()
        .put("pid", Process.myPid())
        .put("liveChannels", BinderChannel.liveChannels)
        .put("mainThreadBinderCalls", BinderChannel.mainThreadBinderCalls)
        .put("closeNotifyRetries", BinderChannel.closeNotifyRetries)
        .put("ackRetries", BinderChannel.ackRetries)
        .put("threads", threadCount())

    private fun threadCount(): Int = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("Threads:") }.substringAfter(":").trim().toInt()
    }.getOrDefault(-1)

    /** 把通道参数同时用到两端：server 经探针设置，client 本地使用同一份。 */
    private suspend fun applyConfig(args: JSONObject): ChannelConfig {
        val json = args.optJSONObject("cfg")
        withProbe { p -> io { p.setChannelConfig(json?.toString() ?: "") } }
        return ChannelConfig.fromJson(json)
    }

    private suspend fun resetConfig() = runCatching { withProbe { p -> io { p.setChannelConfig("") } } }

    private fun err(e: Throwable?): Any =
        e?.let { JSONObject().put("class", it.javaClass.name).put("message", it.message ?: "") } ?: JSONObject.NULL

    private class PromptRun {
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

        fun json(): JSONObject = JSONObject()
            .put("chunks", chunks).put("outOfOrder", outOfOrder)
            .put("stopReason", stopReason ?: JSONObject.NULL)
            .put("error", error?.let { JSONObject().put("class", it.javaClass.name).put("message", it.message ?: "") } ?: JSONObject.NULL)
            .put("firstChunkMs", if (firstChunkNs > 0) (firstChunkNs - startNs) / 1e6 else -1.0)
            .put("totalMs", (endNs - startNs) / 1e6)
            .put("chunksPerSec", if (endNs > startNs) chunks / ((endNs - startNs) / 1e9) else 0.0)
            .put("charsPerSec", if (endNs > startNs) chunkChars / ((endNs - startNs) / 1e9) else 0.0)
            .put("latency", lat.summary())
    }

    private suspend fun runPrompt(
        session: ClientSession,
        text: String,
        run: PromptRun,
        onChunk: (PromptRun) -> Unit = {},
    ) {
        run.startNs = now()
        var lastUi = 0L
        try {
            session.prompt(listOf(ContentBlock.Text(text))).collect { ev ->
                when (ev) {
                    is Event.SessionUpdateEvent -> {
                        val u = ev.update
                        if (u is SessionUpdate.AgentMessageChunk) {
                            val t = now()
                            val meta = u._meta as? JsonObject
                            val seq = meta?.get("seq")?.jsonPrimitive?.intOrNull ?: -1
                            meta?.get("t")?.jsonPrimitive?.longOrNull?.let { run.lat.add(t - it) }
                            if (seq != run.lastSeq + 1) run.outOfOrder++
                            run.lastSeq = seq
                            if (run.chunks == 0) run.firstChunkNs = t
                            run.chunks++
                            run.chunkChars += ((u.content as? ContentBlock.Text)?.text?.length ?: 0)
                            if (t - lastUi > 250_000_000) {
                                lastUi = t
                                status("chunks=${run.chunks}")
                            }
                            onChunk(run)
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
        run.endNs = now()
    }

    private fun streamCmd(args: JSONObject, defaults: JSONObject = JSONObject()): String = JSONObject()
        .put("chunks", args.optInt("chunks", defaults.optInt("chunks", 5000)))
        .put("chunkChars", args.optInt("chunkChars", defaults.optInt("chunkChars", 32)))
        .put("intervalMs", args.optLong("intervalMs", defaults.optLong("intervalMs", 0)))
        .put("burst", args.optInt("burst", 1))
        .put("bp", args.optBoolean("bp", defaults.optBoolean("bp", false)))
        .put("bpChars", args.optLong("bpChars", 16_384))
        .put("cjk", args.optBoolean("cjk", false))
        .toString()

    // ------------------------------------------------------------------ ACP 场景

    /** initialize → session/new → session/prompt（流式）→ close。 */
    private suspend fun handshake(args: JSONObject): JSONObject {
        val cfg = applyConfig(args)
        val conn = AcpConn(ctx, cfg, scope, "handshake")
        try {
            val t0 = now()
            val info = conn.connect()
            val session = conn.newSession()
            val tSession = ms(t0)
            val run = PromptRun()
            runPrompt(session, """{"chunks":20,"intervalMs":0}""", run)
            val cause = conn.closeAndWait()
            return JSONObject()
                .put("ok", run.stopReason == "END_TURN" && run.chunks == 20 && run.outOfOrder == 0)
                .put("agent", info.implementation?.name ?: JSONObject.NULL)
                .put("protocolVersion", info.protocolVersion.toString())
                .put("timingsMs", JSONObject(conn.timings as Map<*, *>).put("session", tSession))
                .put("prompt", run.json())
                .put("closeCause", cause?.toString() ?: JSONObject.NULL)
        } finally {
            conn.dispose()
            resetConfig()
        }
    }

    /** 流式压力：记录端到端延迟、吞吐、主线程是否被阻塞、两端通道的窗口统计。 */
    private suspend fun stream(args: JSONObject): JSONObject {
        val cfg = applyConfig(args)
        val conn = AcpConn(ctx, cfg, scope, "stream")
        try {
            conn.connect()
            val session = conn.newSession()
            val wd = MainThreadWatchdog()
            wd.start()
            val run = PromptRun()
            runPrompt(session, streamCmd(args), run)
            val main = wd.stop()
            val server = serverStats()
            val clientTransport = conn.transport.stats()
            val cause = conn.closeAndWait()
            val expected = args.optInt("chunks", 5000)
            return JSONObject()
                .put("ok", run.stopReason == "END_TURN" && run.chunks == expected && run.outOfOrder == 0)
                .put("cfg", cfg.toJson())
                .put("cmd", JSONObject(streamCmd(args)))
                .put("prompt", run.json())
                .put("mainThread", main)
                .put("clientTransport", clientTransport)
                .put("serverConnections", server.optJSONArray("open"))
                .put("serverMainThreadBinderCalls", server.optLong("mainThreadBinderCalls"))
                .put("client", clientStats())
                .put("closeCause", cause?.toString() ?: JSONObject.NULL)
        } finally {
            conn.dispose()
            resetConfig()
        }
    }

    /** 流式中途 session/cancel：本轮应以 CANCELLED 结束，同一会话随后还能继续。 */
    private suspend fun cancel(args: JSONObject): JSONObject {
        val cfg = applyConfig(args)
        val conn = AcpConn(ctx, cfg, scope, "cancel")
        try {
            conn.connect()
            val session = conn.newSession()
            val cancelAfter = args.optInt("cancelAfterChunks", 100)
            val cmd = streamCmd(args, JSONObject().put("chunks", 1_000_000).put("intervalMs", 5))
            val run = PromptRun()
            var tCancel = 0L
            var chunksAtCancel = 0
            var cancelCallMs = -1.0
            val wd = MainThreadWatchdog()
            wd.start()
            coroutineScope {
                val cs = this
                runPrompt(session, cmd, run) { r ->
                    if (tCancel == 0L && r.chunks >= cancelAfter) {
                        tCancel = now()
                        chunksAtCancel = r.chunks
                        cs.launch {
                            session.cancel()
                            cancelCallMs = ms(tCancel)
                        }
                    }
                }
            }
            val main = wd.stop()
            val after = PromptRun()
            runPrompt(session, """{"chunks":5,"intervalMs":0}""", after)
            val server = serverStats()
            val cause = conn.closeAndWait()
            return JSONObject()
                .put("ok", run.stopReason == "CANCELLED" && after.stopReason == "END_TURN")
                .put("cmd", JSONObject(cmd))
                .put("cancelled", run.json())
                .put("cancelCallMs", cancelCallMs)
                .put("cancelToStopMs", if (tCancel > 0) ms(tCancel, run.endNs) else -1.0)
                .put("chunksAfterCancel", run.chunks - chunksAtCancel)
                .put("followUp", after.json())
                .put("mainThread", main)
                .put("serverPromptOutcomes", server.optJSONObject("promptOutcomes"))
                .put("serverPromptsActive", server.optInt("promptsActive"))
                .put("closeCause", cause?.toString() ?: JSONObject.NULL)
        } finally {
            conn.dispose()
            resetConfig()
        }
    }

    /** 反复 bind → 对话 → close → unbind，检查两端没有残留通道、协程和线程。 */
    private suspend fun reconnect(args: JSONObject): JSONObject {
        val cfg = applyConfig(args)
        val iterations = args.optInt("iterations", 20)
        val closeFirst = args.optBoolean("closeFirst", true)
        val before = serverStats()
        val clientBefore = clientStats()
        val iters = JSONArray()
        var okCount = 0
        for (i in 0 until iterations) {
            val conn = AcpConn(ctx, cfg, scope, "reconnect-$i")
            val t0 = now()
            try {
                conn.connect()
                val s = conn.newSession()
                val run = PromptRun()
                runPrompt(s, """{"chunks":5,"intervalMs":0}""", run)
                val cause = if (closeFirst) conn.closeAndWait() else null
                val ok = run.stopReason == "END_TURN" && (!closeFirst || cause?.kind == "local")
                if (ok) okCount++
                iters.put(JSONObject().put("i", i).put("ok", ok).put("ms", ms(t0))
                    .put("timings", JSONObject(conn.timings as Map<*, *>)))
            } catch (e: Exception) {
                iters.put(JSONObject().put("i", i).put("ok", false).put("error", err(e)))
            } finally {
                conn.dispose()
            }
            status("reconnect $i")
        }
        delay(1500)
        val after = serverStats()
        resetConfig()
        val noLeak = after.optInt("connectionsOpen") == 0 && after.optInt("liveChannels") == 0 &&
            after.optInt("promptsActive") == 0 && after.optInt("hostJobChildren") == 0 && BinderChannel.liveChannels == 0
        return JSONObject()
            .put("ok", okCount == iterations && noLeak)
            .put("noLeak", noLeak)
            .put("iterations", iterations).put("okCount", okCount).put("closeFirst", closeFirst)
            .put("iters", iters)
            .put("serverBefore", pick(before))
            .put("serverAfter", pick(after))
            .put("clientBefore", clientBefore)
            .put("clientAfter", clientStats())
    }

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

    /** 流式中途 SIGKILL :agent：客户端应经 linkToDeath 感知、结束挂起的 prompt，服务重建后能重新对话。 */
    private suspend fun serverKill(args: JSONObject): JSONObject {
        val cfg = applyConfig(args)
        val before = serverStats()
        val conn = AcpConn(ctx, cfg, scope, "server-kill")
        val conn2 = AcpConn(ctx, cfg, scope, "after-kill")
        val probeBinding = ServiceBinding(ctx, SpikeIds.PROBE_SERVICE)
        try {
            conn.connect()
            val session = conn.newSession()
            check(probeBinding.bind())
            val probe = ISpikeProbe.Stub.asInterface(probeBinding.awaitConnected(10_000) ?: error("probe"))
            val killAfter = args.optInt("killAfterChunks", 50)
            val run = PromptRun()
            var tKill = 0L
            var detectMs = -1.0
            var cause: String? = null
            var promptHung = false
            coroutineScope {
                val job = launch {
                    runPrompt(session, streamCmd(args, JSONObject().put("chunks", 1_000_000).put("intervalMs", 5)), run) { r ->
                        if (tKill == 0L && r.chunks >= killAfter) {
                            tKill = now()
                            probe.killProcess()
                        }
                    }
                }
                val c = withTimeoutOrNull(15_000) { conn.channel.closeCause.await() }
                if (c != null && tKill > 0) detectMs = ms(tKill)
                cause = c?.toString()
                if (withTimeoutOrNull(10_000) { job.join() } == null) {
                    promptHung = true
                    job.cancel()
                }
            }
            val clientLiveAfterDeath = BinderChannel.liveChannels
            // BIND_AUTO_CREATE：进程死后系统会重建服务并再次回调 onServiceConnected
            val restarted = conn.binding.awaitConnected(20_000)
            val restartMs = if (restarted != null && tKill > 0) ms(tKill, conn.binding.connectedAtNs) else -1.0
            var recoveredMs = -1.0
            val after = PromptRun()
            if (restarted != null) {
                conn2.openOn(restarted)
                conn2.client.initialize(com.agentclientprotocol.client.ClientInfo())
                val s2 = conn2.newSession()
                runPrompt(s2, """{"chunks":5,"intervalMs":0}""", after)
                recoveredMs = ms(tKill)
                conn2.closeAndWait()
            }
            conn.dispose()
            conn2.dispose()
            probeBinding.unbind()
            delay(500)
            val server = serverStats()
            return JSONObject()
                .put("ok", cause?.startsWith("peer_died") == true && !promptHung && after.stopReason == "END_TURN" &&
                        server.optInt("pid") != before.optInt("pid") && BinderChannel.liveChannels == 0)
                .put("killedAfterChunks", run.chunks)
                .put("closeCause", cause ?: JSONObject.NULL)
                .put("detectMs", detectMs)
                .put("promptError", err(run.error))
                .put("promptEndMs", if (tKill > 0) ms(tKill, run.endNs) else -1.0)
                .put("promptHung", promptHung)
                .put("serviceDisconnectedMs", if (conn.binding.disconnectedAtNs > 0) ms(tKill, conn.binding.disconnectedAtNs) else -1.0)
                .put("serviceRestartMs", restartMs)
                .put("recoveredMs", recoveredMs)
                .put("afterRestartPrompt", after.json())
                .put("clientLiveChannelsAfterDeath", clientLiveAfterDeath)
                .put("serverBefore", pick(before))
                .put("serverAfter", pick(server))
                .put("clientAfter", clientStats())
        } finally {
            conn.dispose()
            conn2.dispose()
            probeBinding.unbind()
            resetConfig()
        }
    }

    /** 流式中途客户端自杀。主机端随后跑 server-stats，确认服务端关闭了通道、结束了 prompt。 */
    private suspend fun clientKill(args: JSONObject): JSONObject {
        val cfg = applyConfig(args)
        val conn = AcpConn(ctx, cfg, scope, "client-kill")
        conn.connect()
        val session = conn.newSession()
        val killAfter = args.optInt("killAfterChunks", 50)
        val run = PromptRun()
        runPrompt(session, streamCmd(args, JSONObject().put("chunks", 1_000_000).put("intervalMs", 5)), run) { r ->
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
        val conn = AcpConn(ctx, cfg, scope, "oversize")
        try {
            conn.connect()
            val session = conn.newSession()
            val max = cfg.maxMessageChars
            val out = JSONObject().put("maxMessageChars", max)

            // A. 客户端发超长 prompt：BinderAcpTransport 不发，本地合成错误响应
            val a = PromptRun()
            runPrompt(session, "z".repeat(max + 1000), a)
            out.put("A_clientOversizePrompt", JSONObject()
                .put("ok", a.error != null && a.stopReason == null && (a.error?.message ?: "").contains("exceeds"))
                .put("error", err(a.error)).put("ms", (a.endNs - a.startNs) / 1e6))

            // B. 刚好在上限以内的 prompt 能正常发出
            val b = PromptRun()
            runPrompt(session, "w".repeat(max - 1024), b)
            out.put("B_nearLimitPrompt", JSONObject().put("ok", b.stopReason == "END_TURN").put("prompt", b.json()))

            // C. Agent 端要发超长的通知：丢弃该条，本轮其他消息照常
            val c = PromptRun()
            runPrompt(session, JSONObject().put("chunks", 3).put("intervalMs", 0).put("bigChunkChars", max + 100).toString(), c)
            val server = serverStats()
            val dropped = server.optJSONArray("open")?.let { arr ->
                (0 until arr.length()).sumOf { arr.getJSONObject(it).optJSONObject("transport")?.optLong("droppedTooLarge") ?: 0L }
            } ?: -1L
            out.put("C_agentOversizeNotification", JSONObject()
                .put("ok", c.stopReason == "END_TURN" && c.chunks == 3 && dropped >= 1)
                .put("prompt", c.json()).put("serverDroppedTooLarge", dropped))

            // D. 绕过 SDK 直接往 Agent 的接收端塞一条超长消息：Agent 端应判违规并关闭通道
            val peer = conn.channel.peerForTesting!!
            val t0 = now()
            io { peer.send("x".repeat(max + 1)) }
            val cause = withTimeoutOrNull(5_000) { conn.channel.closeCause.await() }
            out.put("D_rawOversizeInjection", JSONObject()
                .put("ok", cause?.kind == "remote" && cause.reason.contains("exceeds"))
                .put("closeCause", cause?.toString() ?: JSONObject.NULL).put("ms", ms(t0)))
            out.put("ok", listOf("A_clientOversizePrompt", "B_nearLimitPrompt", "C_agentOversizeNotification", "D_rawOversizeInjection")
                .all { out.getJSONObject(it).optBoolean("ok") })
            return out
        } finally {
            conn.dispose()
            resetConfig()
        }
    }

    /** 绕过发送端流控往 Agent 的接收端连续塞消息：Agent 端应判“超出窗口”并关闭通道。 */
    private suspend fun windowViolation(args: JSONObject): JSONObject {
        applyConfig(args)
        // 本端关掉入站检查，避免 Agent 回的 ack 超出本端已发条数时本端先关通道
        val conn = AcpConn(ctx, ChannelConfig.fromJson(args.optJSONObject("cfg")).copy(enforceInboundLimits = false), scope, "window")
        try {
            conn.connect()
            val peer = conn.channel.peerForTesting!!
            val units = args.optInt("units", 30_000)
            val n = args.optInt("n", 200)
            val msg = "x".repeat(units)
            var sent = 0
            var sendError: Throwable? = null
            io {
                for (i in 0 until n) {
                    if (conn.channel.closeCauseOrNull != null) break
                    try {
                        peer.send(msg)
                        sent++
                    } catch (e: Exception) {
                        sendError = e
                        break
                    }
                }
            }
            val cause = withTimeoutOrNull(5_000) { conn.channel.closeCause.await() }
            val server = serverStats()
            val lastClose = server.optJSONArray("recentCloses")?.let { if (it.length() > 0) it.getJSONObject(it.length() - 1) else null }
            return JSONObject()
                .put("ok", cause?.kind == "remote" && cause.reason.contains("window"))
                .put("injected", sent).put("units", units)
                .put("sendError", err(sendError))
                .put("closeCause", cause?.toString() ?: JSONObject.NULL)
                .put("serverLastClose", lastClose ?: JSONObject.NULL)
        } finally {
            conn.dispose()
            resetConfig()
        }
    }

    // ------------------------------------------------------------------ 原始 Binder 压测

    private suspend fun <T> withBench(block: suspend (IBench) -> T): T {
        val b = ServiceBinding(ctx, SpikeIds.BENCH_SERVICE)
        check(b.bind()) { "cannot bind bench" }
        try {
            return block(IBench.Stub.asInterface(b.awaitConnected(15_000) ?: error("bench not connected")))
        } finally {
            b.unbind()
        }
    }

    private fun sinkCount(bench: IBench): Long = JSONObject(bench.sinkStats()).optLong("count", -1)

    /** 接收方空闲时，单条 oneway 事务的最大载荷（String、中文 String、byte[] 三种）。 */
    private suspend fun benchSingle(args: JSONObject): JSONObject = withBench { bench ->
        val out = JSONObject()
        val kinds = args.optJSONArray("kinds")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            ?: listOf("STRING", "BYTES", "STRING_CJK")
        for (k in kinds) {
            val kind = BenchSender.Kind.valueOf(k)
            status("bench-single $k")
            val r = io {
                val sink = bench.newSink(0)
                BenchSender.singleMax(sink, kind) { expected ->
                    val deadline = SystemClock.elapsedRealtime() + 5_000
                    while (sinkCount(bench) < expected && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(5)
                    sinkCount(bench) >= expected
                }
            }
            out.put(k, r)
        }
        out
    }

    /** 接收方处理慢时，不加背压连续发送：什么时候失败、失败时在途多少。两个方向都测。 */
    private suspend fun benchBurst(args: JSONObject): JSONObject = withBench { bench ->
        val sizes = args.optJSONArray("sizes")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
            ?: listOf(1024, 8192, 32_768, 65_536)
        val n = args.optInt("n", 5000)
        val delayUs = args.optInt("handlerDelayMicros", 2000)
        val toAgent = JSONArray()
        for (size in sizes) {
            status("burst to agent $size")
            toAgent.put(io {
                val sink = bench.newSink(delayUs)
                BenchSender.burst(sink, BenchSender.Kind.STRING, size, n) { sinkCount(bench) }
            })
        }
        val toClient = JSONArray()
        for (size in args.optJSONArray("reverseSizes")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: listOf(8192, 32_768)) {
            status("burst to client $size")
            val local = BenchSinkImpl(delayUs)
            val r = JSONObject(io {
                bench.blast(local, JSONObject().put("mode", "burst").put("kind", "STRING").put("units", size).put("n", n).toString())
            })
            val processed = local.received
            val failAt = r.optInt("failAt")
            val accepted = if (failAt >= 0) failAt.toLong() else n.toLong()
            r.put("processedAtReturn", processed)
            r.put("inFlightAtReturn", accepted - processed)
            r.put("inFlightBytesAtReturn", (accepted - processed) * BenchSender.parcelBytes(BenchSender.Kind.STRING, size))
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (local.received < accepted && SystemClock.elapsedRealtime() < deadline) delay(20)
            r.put("drained", local.received >= accepted)
            toClient.put(r)
        }
        JSONObject().put("handlerDelayMicros", delayUs).put("n", n).put("clientToAgent", toAgent).put("agentToClient", toClient)
    }

    /** 接收方不慢时的原始 oneway 吞吐。 */
    private suspend fun benchThroughput(args: JSONObject): JSONObject = withBench { bench ->
        val sizes = args.optJSONArray("sizes")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
            ?: listOf(64, 1024, 8192, 32_768)
        val arr = JSONArray()
        for (size in sizes) {
            status("throughput $size")
            val n = minOf(args.optInt("n", 20_000), (32L * 1024 * 1024 / maxOf(1, size)).toInt())
            arr.put(io {
                val sink = bench.newSink(0)
                BenchSender.throughput(sink, BenchSender.Kind.STRING, size, n) { sinkCount(bench) }
            })
        }
        JSONObject().put("results", arr)
    }
}
