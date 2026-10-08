package org.agentos.test.acp.client

import android.content.Context
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.agentos.acp.AcpServiceContract
import org.agentos.acp.AgentOs
import org.agentos.acp.AgentOsConnection
import org.agentos.acp.AgentOsError
import org.agentos.acp.AgentOsEvent
import org.agentos.acp.AgentOsException
import org.agentos.acp.ToolRef
import org.agentos.channel.ChannelConfig
import org.agentos.test.acp.AcpConn
import org.agentos.test.acp.TestIds
import org.json.JSONArray
import org.json.JSONObject

/**
 * 第三方 App 接入 ACP 的设备场景（docs/third-party-acp.md；tests/device/acp-channel/third_party.py 驱动）。
 * 这个 App 就是“后装的第三方”：它用 [AgentOs]（真实的 SDK）连 AgentOS，授权由主机端脚本经 AcpCallerDebugReceiver 代替用户点。
 *
 * - `tp-open`：直接调 `IAcpService.open` 一次，返回拒绝的原因码（或成功）。不重试，用来看准入的每一种结果和冷却时间。
 * - `tp-connect`：[AgentOs.connect]（带重试），把每次 Waiting 回调记下来；成功后可选发一轮 prompt（args.prompt）。
 * - `tp-hold`：连上后保持连接，直到通道关闭（撤销时 AgentOS 关它）或超时，返回关闭的原因和耗时。运行时会先发一个 `tp-hold-ready` 阶段，
 *   主机端据此在连接已建好之后撤销。
 * - `tp-detach`：发一个长 prompt，看到第一段输出后自己关通道（不取消任务）；返回时任务还在 AgentOS 里跑、通道已经没有了。
 * - `tp-spoof`：用别的 App 的包名、名字去“冒充”：open 不带任何名字，initialize 的 clientInfo 里写别人的包名；AgentOS 只看 UID。
 */
class ThirdPartyScenarios(private val ctx: Context, private val scope: CoroutineScope, private val runId: String, private val status: (String) -> Unit) {

    suspend fun run(name: String, args: JSONObject): JSONObject? = when (name) {
        "tp-open" -> open(args)
        "tp-connect" -> connect(args)
        "tp-hold" -> hold(args)
        "tp-detach" -> detach(args)
        "tp-spoof" -> spoof(args)
        "tp-info" -> info()
        else -> null
    }

    private fun now() = SystemClock.elapsedRealtime()

    /** 这个 App 自己的信息（主机端核对 AgentOS 记下的包名、签名摘要和 App 名）。 */
    private fun info(): JSONObject {
        val pm = ctx.packageManager
        val label = pm.getApplicationLabel(ctx.applicationInfo).toString()
        return JSONObject().put("ok", true).put("package", ctx.packageName).put("uid", Process.myUid()).put("label", label)
            .put("installed", AgentOs.isInstalled(ctx))
    }

    // ------------------------------------------------------------------ tp-open

    private suspend fun open(args: JSONObject): JSONObject {
        val expected = args.optString("expect", "")
        val c = AcpConn(ctx, TestIds.APP_ACP, ChannelConfig.DEFAULT, scope, "tp-open")
        try {
            check(c.binding.bind()) { "bindService returned false (missing <queries>?)" }
            val b = c.binding.awaitConnected(15_000) ?: error("service not connected")
            val t0 = now()
            val error = runCatching { c.openOn(b) }.exceptionOrNull()
            val ms = now() - t0
            val reason = error?.let { AcpServiceContract.reasonOf(it) }
            val outcome = if (error == null) "opened" else reason?.removePrefix(AcpServiceContract.REASON_PREFIX) ?: "error"
            return JSONObject().put("ok", expected.isEmpty() || expected == outcome)
                .put("outcome", outcome).put("message", (error?.message ?: "").take(160)).put("openMs", ms)
                .put("expected", expected.ifEmpty { JSONObject.NULL })
        } finally {
            c.dispose()
        }
    }

    // ------------------------------------------------------------------ tp-connect

    private suspend fun connect(args: JSONObject): JSONObject {
        val waits = JSONArray()
        val t0 = now()
        val prompt = args.optString("prompt", "")
        // toolScope：缺省 = 不给范围（null）；"none" = 空列表（零工具）；否则 "plugin:tool,plugin:tool"
        val scopeArg = args.optString("scope", "")
        val toolScope: List<ToolRef>? = when {
            scopeArg.isEmpty() -> null
            scopeArg == "none" -> emptyList()
            else -> scopeArg.split(',').map { it.trim().split(':').let { p -> ToolRef(p[0], p[1]) } }
        }
        val connection: AgentOsConnection = try {
            withTimeout(args.optLong("timeoutMs", 100_000)) { AgentOs.connect(ctx) { w -> waits.put(w.elapsedMillis) } }
        } catch (e: AgentOsException) {
            return JSONObject().put("ok", args.optString("expectError") == e.error.name).put("error", e.error.name).put("message", (e.message ?: "").take(160))
                .put("waits", waits.length()).put("connectMs", now() - t0)
        } catch (e: TimeoutCancellationException) {
            return JSONObject().put("ok", false).put("error", "TEST_TIMEOUT").put("waits", waits.length())
        }
        val connectMs = now() - t0
        try {
            val out = JSONObject().put("ok", true).put("connectMs", connectMs).put("waits", waits.length())
                .put("firstWaitMs", if (waits.length() > 0) waits.getLong(0) else JSONObject.NULL)
            if (prompt.isNotEmpty()) {
                val session = connection.newSession(toolScope)
                val events = JSONArray()
                val text = StringBuilder()
                var done: String? = null
                var error: String? = null
                try {
                    withTimeout(args.optLong("promptTimeoutMs", 120_000)) {
                        session.prompt(prompt).toList().forEach { e ->
                            when (e) {
                                is AgentOsEvent.Text -> text.append(e.chunk)
                                is AgentOsEvent.ToolCall -> events.put(JSONObject().put("id", e.id).put("tool", e.tool).put("status", e.status.name).put("ref", e.ref?.let { "${it.plugin}:${it.tool}" } ?: JSONObject.NULL))
                                is AgentOsEvent.Done -> done = e.stopReason
                            }
                        }
                    }
                } catch (e: AgentOsException) {
                    error = e.error.name
                }
                out.put("stopReason", done ?: JSONObject.NULL).put("promptError", error ?: JSONObject.NULL)
                    .put("text", text.toString().take(300)).put("toolEvents", events)
                if (args.optString("expectError").isNotEmpty()) out.put("ok", error == args.optString("expectError"))
                else out.put("ok", error == null && done != null)
            }
            return out
        } finally {
            connection.close()
        }
    }

    // ------------------------------------------------------------------ tp-hold

    private suspend fun hold(args: JSONObject): JSONObject {
        val connection = try {
            AgentOs.connect(ctx)
        } catch (e: AgentOsException) {
            return JSONObject().put("ok", false).put("error", e.error.name)
        }
        try {
            // 建一个会话并发一个会一直跑的 prompt，这样撤销时既有通道也有进行中的 prompt
            val session = connection.newSession(emptyList())
            org.agentos.test.acp.Results.emit(runId, JSONObject().put("scenario", "tp-hold").put("phase", "ready").put("pid", Process.myPid()))
            val t0 = now()
            var closedAfter: Long? = null
            var promptError: String? = null
            val deadline = t0 + args.optLong("holdMs", 60_000)
            // 在后台跑 prompt；通道关闭时 flow 以 DISCONNECTED 结束
            val job = scope.launch {
                try {
                    session.prompt(args.optString("prompt", "hold")).toList()
                } catch (e: AgentOsException) {
                    promptError = e.error.name
                }
            }
            while (now() < deadline && connection.isConnected) kotlinx.coroutines.delay(50)
            if (!connection.isConnected) closedAfter = now() - t0
            job.join()
            return JSONObject().put("ok", closedAfter != null).put("closedAfterMs", closedAfter ?: JSONObject.NULL)
                .put("promptError", promptError ?: JSONObject.NULL).put("isConnected", connection.isConnected)
        } finally {
            connection.close()
        }
    }

    // ------------------------------------------------------------------ tp-detach

    /**
     * 发一个会一直跑的 prompt，看到第一段输出后**自己**把通道关掉（App 退出、崩溃都是这样），不取消任务（F7：关通道不取消任务）。
     * 返回时通道已经没有了、任务还在 AgentOS 里跑：撤销时只看“开着的通道”就找不到它的任务。
     */
    private suspend fun detach(args: JSONObject): JSONObject {
        val connection = try {
            AgentOs.connect(ctx)
        } catch (e: AgentOsException) {
            return JSONObject().put("ok", false).put("error", e.error.name)
        }
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = scope.launch {
            try {
                connection.newSession(emptyList()).prompt(args.optString("prompt", "detach")).collect { started.complete(Unit) }
            } catch (e: AgentOsException) {
                started.complete(Unit)
            }
        }
        val sawOutput = kotlinx.coroutines.withTimeoutOrNull(args.optLong("startTimeoutMs", 30_000)) { started.await(); true } ?: false
        connection.close()
        job.cancel()
        return JSONObject().put("ok", sawOutput).put("sawOutput", sawOutput).put("isConnected", connection.isConnected)
    }

    // ------------------------------------------------------------------ tp-spoof

    /**
     * open 只带一个 IChannel，没有任何名字；initialize 的 clientInfo 是调用方自己写的，我们写成别的 App 的包名和名字。
     * 期望：AgentOS 按 UID 解析出的还是本 App（AcpCallerDebugReceiver list 里只有本 App 的包名，没有被冒充的包名）。
     */
    private suspend fun spoof(args: JSONObject): JSONObject {
        val claimed = args.optString("claimPackage", "org.agentos.sample.notes")
        val c = AcpConn(ctx, TestIds.APP_ACP, ChannelConfig.DEFAULT, scope, "tp-spoof")
        try {
            check(c.binding.bind()) { "bindService returned false" }
            val b = c.binding.awaitConnected(15_000) ?: error("service not connected")
            val error = runCatching { c.openOn(b) }.exceptionOrNull()
            val reason = error?.let { AcpServiceContract.reasonOf(it) }
            var initialized = false
            if (error == null) {
                initialized = runCatching {
                    withTimeout(15_000) {
                        c.client.initialize(
                            com.agentclientprotocol.client.ClientInfo(
                                implementation = com.agentclientprotocol.model.Implementation(claimed, "9.9"),
                            ),
                        )
                    }
                }.isSuccess
            }
            return JSONObject().put("ok", true).put("opened", error == null).put("reason", reason ?: JSONObject.NULL)
                .put("initialized", initialized).put("claimed", claimed).put("myPackage", ctx.packageName)
        } finally {
            c.dispose()
        }
    }
}
