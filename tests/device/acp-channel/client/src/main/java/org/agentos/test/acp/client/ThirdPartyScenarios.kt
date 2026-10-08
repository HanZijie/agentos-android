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
import org.agentos.acp.McpHttpServer
import org.agentos.acp.SessionMode
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
 * - `tp-sessions`：会话的完整生命周期（新建 → 列表 → 另一条连接 load / resume → fork → 模式 → 模型 → 自带 MCP 服务器 → close → delete），
 *   全部经真实的 Binder 和真实的 AgentOS；每一项返回一个布尔，由 third_party.py 核对。args.realMcpUrl 给了时，再接一个公网 Streamable HTTP MCP 服务器，
 *   确认连上、列出工具，并让（脚本化的）模型调一次它的工具（确认由主机端设成自动允许）。
 * - `tp-live`：真实模型（手机上配好的厂商预设）下的会话级模型选择：可选模型、setModel 之后真实模型照常回答、另一条连接 load 看到的是切换后的模型。
 */
class ThirdPartyScenarios(private val ctx: Context, private val scope: CoroutineScope, private val runId: String, private val status: (String) -> Unit) {

    suspend fun run(name: String, args: JSONObject): JSONObject? = when (name) {
        "tp-open" -> open(args)
        "tp-connect" -> connect(args)
        "tp-hold" -> hold(args)
        "tp-detach" -> detach(args)
        "tp-spoof" -> spoof(args)
        "tp-info" -> info()
        "tp-sessions" -> sessions(args)
        "tp-live" -> live(args)
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
                                is AgentOsEvent.Thought, is AgentOsEvent.UserMessage -> Unit // prompt() 默认不发思考；用户消息只在 loadSession 的历史里
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

    // ------------------------------------------------------------------ tp-sessions

    private suspend fun sessions(args: JSONObject): JSONObject {
        val checks = JSONObject()
        val notes = JSONObject()
        fun check(name: String, ok: Boolean) { checks.put(name, ok) }
        suspend fun errorOf(block: suspend () -> Unit): String? = try { block(); null } catch (e: AgentOsException) { e.error.name }
        // 诊断：失败时看得到异常类型和消息，而不是只有一个枚举名
        suspend fun detailOf(block: suspend () -> Unit): String = try { block(); "no exception" } catch (e: AgentOsException) { "${e.error.name}: ${e.message} cause=${e.cause?.javaClass?.name}: ${e.cause?.message?.take(120)}" }
        fun shape(h: List<AgentOsEvent>) = h.groupingBy { it::class.simpleName ?: "?" }.eachCount().toString()

        val first = AgentOs.connect(ctx)
        val second = AgentOs.connect(ctx)
        try {
            val caps = first.capabilities
            notes.put("capabilities", caps.toString())
            check("capabilitiesAllOn", caps.loadSession && caps.resumeSession && caps.forkSession && caps.listSessions && caps.deleteSession && caps.closeSession && caps.mcpHttpServers)

            // ---- 新建、两轮、会话 ID
            val p1 = """{"chunks":3,"chunkChars":4,"text":"A","intervalMs":0}"""
            val p2 = """{"chunks":2,"chunkChars":4,"text":"B","intervalMs":0}"""
            val s1 = first.newSession()
            check("sessionIdShape", s1.sessionId.startsWith("ses_") && s1.sessionId.length == 30)
            check("newHistoryEmpty", s1.history.isEmpty() && s1.activeTaskId == null && s1.mcpServers.isEmpty())
            val t1 = withTimeout(90_000) { s1.prompt(p1).toList() }
            val t2 = withTimeout(90_000) { s1.prompt(p2).toList() }
            check("turnsEnded", (t1.last() as? AgentOsEvent.Done)?.stopReason == "end_turn" && (t2.last() as? AgentOsEvent.Done)?.stopReason == "end_turn")
            check("liveTurnDoesNotEchoUser", t1.none { it is AgentOsEvent.UserMessage } && t1.none { it is AgentOsEvent.Thought })

            // ---- 列表
            val listed = first.listSessions()
            check("listedWithTitle", listed.any { it.sessionId == s1.sessionId && it.title == p1.lineSequence().first().take(80) && it.updatedAt != null })

            // ---- 另一条连接 load：历史按顺序完整，之后能继续
            val loaded = second.loadSession(s1.sessionId)
            notes.put("loadedHistory", shape(loaded.history))
            notes.put("loadedText", loaded.history.filterIsInstance<AgentOsEvent.Text>().joinToString("") { it.chunk }.take(200))
            val users = loaded.history.filterIsInstance<AgentOsEvent.UserMessage>().map { it.text }
            check("loadReplaysUserMessages", users == listOf(p1, p2))
            // 重放出来的答案和当时实时流出来的完全一样（都来自同一份事件日志）
            fun textOf(events: List<AgentOsEvent>) = events.filterIsInstance<AgentOsEvent.Text>().joinToString("") { it.chunk }
            val replayedText = textOf(loaded.history)
            notes.put("liveText", (textOf(t1) + textOf(t2)).take(80))
            check("loadReplaysAnswers", replayedText.isNotEmpty() && replayedText == textOf(t1) + textOf(t2))
            val order = loaded.history.map { it::class.simpleName }
            check("loadHistoryInOrder", order.indexOf("UserMessage") < order.indexOf("Text") && order.lastIndexOf("UserMessage") > order.indexOf("Text"))
            val t3 = withTimeout(90_000) { loaded.prompt(p1).toList() }
            check("loadedSessionContinues", (t3.last() as? AgentOsEvent.Done)?.stopReason == "end_turn" && loaded.sessionId == s1.sessionId)

            // ---- 同一条连接上再 load 同一个会话：历史是最新的，而且返回的会话照常流式输出
            val again = second.loadSession(s1.sessionId)
            check("repeatLoadHasFullHistory", again.history.filterIsInstance<AgentOsEvent.UserMessage>().map { it.text } == listOf(p1, p2, p1))
            val t4 = withTimeout(90_000) { again.prompt(p2).toList() }
            check("repeatLoadStreams", textOf(t4).isNotEmpty() && (t4.last() as? AgentOsEvent.Done)?.stopReason == "end_turn")
            notes.put("repeatLoadText", textOf(t4).take(40))

            // ---- resume：不重放
            val resumed = second.resumeSession(s1.sessionId)
            check("resumeHasNoHistory", resumed.history.isEmpty())

            // ---- fork：独立的新会话，历史是到目前为止的
            val forked = first.forkSession(s1.sessionId)
            check("forkIsNewSession", forked.sessionId != s1.sessionId)
            val forkView = second.loadSession(forked.sessionId)
            check("forkCarriesFinishedTurns", forkView.history.filterIsInstance<AgentOsEvent.UserMessage>().map { it.text } == listOf(p1, p2, p1, p2))
            check("listHasBoth", first.listSessions().map { it.sessionId }.containsAll(listOf(s1.sessionId, forked.sessionId)))

            // ---- 模式
            check("modeStartsDefault", s1.mode == SessionMode.DEFAULT)
            s1.setMode(SessionMode.READ_ONLY)
            check("modeSetReadOnly", s1.mode == SessionMode.READ_ONLY)
            check("modeSeenByLoad", second.loadSession(s1.sessionId).mode == SessionMode.READ_ONLY)
            s1.setMode(SessionMode.CHAT)
            val chatTurn = withTimeout(90_000) { s1.prompt(p2).toList() }
            check("chatModeStillAnswers", (chatTurn.last() as? AgentOsEvent.Done)?.stopReason == "end_turn")
            s1.setMode(SessionMode.DEFAULT)
            check("modeBackToDefault", s1.mode == SessionMode.DEFAULT)

            // ---- 模型：测试用的是自定义端点，没有“同一个 key 下的其他模型”可选
            notes.put("availableModels", s1.availableModels.size)
            check("noModelChoicesOnCustomEndpoint", s1.availableModels.isEmpty() && s1.model == null)
            check("setModelUnsupported", errorOf { s1.setModel("gpt-whatever") } == "UNSUPPORTED")

            // ---- 自带 MCP 服务器：不合规的整批被拒绝，不留下会话
            val before = first.listSessions().size
            val bad = mapOf(
                "http" to McpHttpServer("a", "http://example.com/mcp"),
                "loopback" to McpHttpServer("a", "https://127.0.0.1/mcp"),
                "metadata" to McpHttpServer("a", "https://169.254.169.254/latest/meta-data"),
                "private" to McpHttpServer("a", "https://192.168.1.10/mcp"),
                "userinfo" to McpHttpServer("a", "https://user:pw@example.com/mcp"),
                "crlf" to McpHttpServer("a", "https://example.com/mcp", listOf("X-Test" to "a\r\nHost: evil")),
                "forbiddenHeader" to McpHttpServer("a", "https://example.com/mcp", listOf("Host" to "evil")),
            )
            val rejected = JSONObject()
            for ((label, server) in bad) rejected.put(label, errorOf { first.newSession(mcpServers = listOf(server)) } ?: "ACCEPTED")
            notes.put("rejectedDetail", detailOf { first.newSession(mcpServers = listOf(bad.getValue("http"))) })
            notes.put("rejected", rejected)
            check("badMcpServersRejected", bad.keys.all { rejected.getString(it) == "INVALID_REQUEST" })
            check("rejectedLeftNoSession", first.listSessions().size == before)

            // ---- 自带 MCP 服务器：地址合规但连不上，会话照常建立，状态里说明哪一个
            val down = first.newSession(mcpServers = listOf(McpHttpServer("down", "https://no-such-host.invalid/mcp", listOf("Authorization" to "Bearer SECRET-DEVICE-TOKEN"))))
            notes.put("mcpStatus", down.mcpServers.joinToString { "${it.name}:${it.connected}:${it.reason}" })
            check("unreachableServerReported", down.mcpServers.size == 1 && down.mcpServers[0].name == "down" && !down.mcpServers[0].connected && down.mcpServers[0].reason != null)
            val downTurn = withTimeout(90_000) { down.prompt(p1).toList() }
            check("sessionWorksWithUnreachableServer", (downTurn.last() as? AgentOsEvent.Done)?.stopReason == "end_turn")

            // ---- 真实的公网 MCP 服务器（只有主机端给了地址才测）
            val realUrl = args.optString("realMcpUrl", "")
            if (realUrl.isNotEmpty()) {
                val real = first.newSession(mcpServers = listOf(McpHttpServer("deepwiki", realUrl)))
                val st = real.mcpServers.firstOrNull()
                notes.put("realMcp", st?.let { "${it.name}:${it.connected}:${it.toolCount}:${it.reason}" } ?: "none")
                check("realMcpConnected", st != null && st.connected && st.toolCount >= 1)
                val toolName = args.optString("realMcpTool", "ses__deepwiki__read_wiki_structure")
                val script = JSONObject().put("chunks", 1).put("intervalMs", 0).put("tool", toolName)
                    .put("toolInput", JSONObject(args.optString("realMcpToolInput", "{\"repoName\":\"facebook/react\"}"))).toString()
                val turn = withTimeout(120_000) { real.prompt(script).toList() }
                val calls = turn.filterIsInstance<AgentOsEvent.ToolCall>().filter { it.tool == toolName }
                val last = calls.lastOrNull()
                notes.put("realMcpCall", last?.let { "${it.status}:${(it.resultJson ?: "").length} chars" } ?: "no call")
                check("realMcpToolCompleted", last?.status == org.agentos.acp.ToolStatus.COMPLETED && !last.resultJson.isNullOrBlank())
                check("realMcpTurnEnded", (turn.last() as? AgentOsEvent.Done)?.stopReason == "end_turn")
                first.deleteSession(real.sessionId)
            }

            // ---- close 保留会话；delete 彻底删除；找不到的和别人的一个样
            s1.close()
            val afterClose = second.loadSession(s1.sessionId)
            notes.put("afterCloseHistory", shape(afterClose.history))
            check("closedSessionStillLoads", afterClose.history.isNotEmpty())
            first.deleteSession(forked.sessionId)
            check("deletedGoneFromList", first.listSessions().none { it.sessionId == forked.sessionId })
            check("deletedNotFound", errorOf { second.loadSession(forked.sessionId) } == "SESSION_NOT_FOUND")
            check("neverExistedNotFound", errorOf { second.loadSession("ses_00000000000000000000000000") } == "SESSION_NOT_FOUND")
            check("deleteTwiceNotFound", errorOf { first.deleteSession(forked.sessionId) } == "SESSION_NOT_FOUND")
            first.deleteSession(down.sessionId)
        } catch (e: AgentOsException) {
            notes.put("exception", e.error.name + ": " + (e.message ?: ""))
            check("noException", false)
        } finally {
            first.close()
            second.close()
        }
        var all = true
        for (k in checks.keys()) if (!checks.getBoolean(k)) all = false
        return JSONObject().put("ok", all && !notes.has("exception")).put("checks", checks).put("notes", notes)
    }

    // ------------------------------------------------------------------ tp-live

    /** 真实模型下的会话级模型选择。手机上要先配好一个厂商预设（同一把 key 下有多个模型）。 */
    private suspend fun live(args: JSONObject): JSONObject {
        val checks = JSONObject()
        val notes = JSONObject()
        fun check(name: String, ok: Boolean) { checks.put(name, ok) }
        fun textOf(events: List<AgentOsEvent>) = events.filterIsInstance<AgentOsEvent.Text>().joinToString("") { it.chunk }
        val ask = args.optString("prompt", "只回复两个字：好的")
        val first = AgentOs.connect(ctx)
        val second = AgentOs.connect(ctx)
        try {
            val s = first.newSession(toolScope = emptyList())
            val models = s.availableModels
            notes.put("models", models.joinToString { it.id }).put("current", s.model ?: "null")
            check("hasModelChoices", models.size >= 2)
            check("currentIsTheConfiguredModel", s.model != null && models.any { it.id == s.model })
            val t1 = withTimeout(120_000) { s.prompt(ask).toList() }
            notes.put("answer1", textOf(t1).take(60))
            check("realModelAnswers", textOf(t1).isNotBlank() && (t1.last() as? AgentOsEvent.Done)?.stopReason == "end_turn")

            val other = models.firstOrNull { it.id != s.model }
            if (other != null) {
                s.setModel(other.id)
                check("setModelReflected", s.model == other.id)
                val t2 = withTimeout(120_000) { s.prompt(ask).toList() }
                notes.put("answer2", textOf(t2).take(60)).put("switchedTo", other.id)
                check("switchedModelAnswers", textOf(t2).isNotBlank() && (t2.last() as? AgentOsEvent.Done)?.stopReason == "end_turn")
                val loaded = second.loadSession(s.sessionId)
                check("loadSeesSwitchedModel", loaded.model == other.id)
                check("loadHistoryHasBothTurns", loaded.history.filterIsInstance<AgentOsEvent.UserMessage>().size == 2)
            }
            val bad = try { s.setModel("not-a-real-model"); "ACCEPTED" } catch (e: AgentOsException) { e.error.name }
            check("unknownModelRejected", bad == "INVALID_REQUEST")
            first.deleteSession(s.sessionId)
        } catch (e: AgentOsException) {
            notes.put("exception", e.error.name + ": " + (e.message ?: ""))
            check("noException", false)
        } finally {
            first.close()
            second.close()
        }
        var all = true
        for (k in checks.keys()) if (!checks.getBoolean(k)) all = false
        return JSONObject().put("ok", all && !notes.has("exception")).put("checks", checks).put("notes", notes)
    }
}
