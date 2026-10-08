package org.agentos.extensions.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.agentos.extensions.host.ConnectFailed
import org.agentos.extensions.host.ConnectionLost
import org.agentos.extensions.host.McpCallResult
import org.agentos.extensions.host.McpContentPart
import org.agentos.extensions.host.McpLinkException
import org.agentos.extensions.host.McpServerLink
import org.agentos.extensions.host.McpToolInfo
import org.agentos.extensions.host.NotSent
import org.agentos.extensions.host.ServerError
import org.agentos.extensions.host.TimedOut
import org.agentos.runtime.broker.RiskPolicy
import org.agentos.runtime.broker.ToolAnnotations
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CatalogTool
import org.agentos.runtime.ports.ContentPart
import org.agentos.runtime.ports.SessionMcpRejected
import org.agentos.runtime.ports.SessionMcpResult
import org.agentos.runtime.ports.SessionMcpServer
import org.agentos.runtime.ports.SessionToolPort
import org.agentos.runtime.ports.ToolInvocation
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolResult
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** [SessionToolHost] 的参数。时间都是毫秒。 */
data class SessionToolHostConfig(
    /** 一个会话最多挂几个服务器（[SessionUrlPolicy.maxServers]）。 */
    val maxServersPerSession: Int = 4,
    /** 一个服务器最多几个工具；**超过的服务器整个拒绝**（`too_many_tools`），不是只留前 N 个。 */
    val maxToolsPerServer: Int = 64,
    /** 同一个调用方（[CallerIdentity.ownerKey]）所有会话加起来最多挂几个服务器。 */
    val maxServersPerOwner: Int = 8,
    /** 全部会话加起来最多几个服务器。 */
    val maxServersTotal: Int = 64,
    val connectTimeoutMillis: Long = 10_000,
    val listTimeoutMillis: Long = 10_000,
    /** [SessionToolPort.attach] 总共的上限（并发连接）。 */
    val attachTimeoutMillis: Long = 15_000,
    /** 交回模型的结果文字上限（字符，含截断说明）。 */
    val maxResultChars: Int = 32_768,
    val maxDescriptionChars: Int = 1_024,
    val maxTitleChars: Int = 128,
    /** 输入 schema 序列化后的上限；超过的工具不列出。 */
    val maxSchemaChars: Int = 16_384,
    /** 只给测试：允许回环地址和 http（[SessionUrlPolicy.allowLoopbackHttp]）。 */
    val allowLoopbackHttp: Boolean = false,
    /** 连接失败之后，[SessionToolPort.prepare] 至少隔这么久才再试。 */
    val retryAfterFailureMillis: Long = 30_000,
) {
    init {
        require(maxServersPerSession > 0 && maxToolsPerServer > 0 && maxServersPerOwner > 0 && maxServersTotal > 0) { "limits must be positive" }
        require(maxResultChars > 128) { "maxResultChars is too small for the truncation notice" }
    }
}

/**
 * 会话级工具宿主（[SessionToolPort] 的实现，语义见 SessionTools.kt）：调用方在 ACP `mcpServers` 里带来的 Streamable HTTP MCP 服务器，
 * 只对它自己的会话可见，只在内存里。
 *
 * ## 挂载（[attach]）
 * 1. [SessionUrlPolicy] 整批校验（失败抛 [SessionMcpRejected]，不连任何东西，**不动**原来挂的那批）；
 * 2. 配额：同一调用方所有会话的服务器总数 ≤ [SessionToolHostConfig.maxServersPerOwner]，全部会话 ≤ [SessionToolHostConfig.maxServersTotal]
 *    （这个会话原来的那批不计入；超了抛 `SessionMcpRejected("quota")`，同样不动原来的）；
 * 3. 通过后**替换**原来的那批（先关旧链接），并发连接并取工具列表，总共不超过 [SessionToolHostConfig.attachTimeoutMillis]。
 *    每个服务器一个 [SessionMcpResult]；失败（`connect_failed`、`timeout`、`list_failed`、`too_many_tools`）不影响其他服务器，也不抛。
 *    调用 [attach] 的协程被取消时，已经开始的连接继续在后台完成（会话保持挂载，由 [detach] 释放）。
 *
 * ## 工具目录（[tools]）
 * - 名字 `ses__<服务器>__<工具>`（[SessionToolNaming]：≤ 64 字符，超长或撞名加哈希，不会以 `mcp__` 开头，不会等于 `read_skill`）；
 *   `provider = "session:<服务器>"`，`source = null`（不进用户策略，也没有“始终允许”）；
 * - `risk = RiskPolicy.effectiveRisk(null, 注解)`：默认写级，`destructiveHint == true` 升为高风险，**永远不会是读级**（`readOnlyHint` 不降级）；
 * - 描述、title 截断并去掉控制字符；输入 schema 不是 `type: object` 或序列化后超过上限的工具丢弃；同一服务器重名的工具只留第一个；
 * - 只列**现在连着**的服务器的工具（掉线的服务器的工具不列出，重连后回来；名字按所有已知工具一起算，掉线不会让别的工具改名）。
 *
 * ## 任务开始前（[prepare]）和调用（[invoke]）
 * - [prepare]：重连掉线（`link.closed` 已完成）或上次连接失败的服务器并刷新工具；连接失败后 [SessionToolHostConfig.retryAfterFailureMillis]
 *   内不再试（掉线不算失败，立刻重连）；`list_changed` 触发后台刷新；
 * - [invoke]：分类与 `ExtensionToolHost` 相同：找不到工具或链接已关 → NotDispatched（`tool_not_in_catalog`）；NotSent / ConnectFailed → NotDispatched
 *   （`tool_unavailable`）；ConnectionLost / TimedOut → Unknown（`tool_result_unknown` / `tool_timeout`，不重放）；服务端 JSON-RPC 错误 → Completed(isError)；
 *   结果文字超过上限截断并加 `[agentos:tool_result_too_large]` 说明，图片保留，其他类型换成一句“omitted a <type> content part”。
 *
 * ## 秘密
 * `SessionMcpServer.url/headers` 只存在 [attach] 传进来的对象里，传给 [SessionMcpConnector]；不写日志、不进异常消息、[toString]、
 * [SessionMcpResult]、[CatalogTool]。底层异常的消息一律不转述：失败只留短代码。
 *
 * 所有方法可以从多个协程并发调用：状态由一把锁保护，锁里不做挂起操作。
 *
 * @param scope 后台任务（连接、重连、刷新、监视链接）的父作用域；宿主层自己加一个 SupervisorJob
 * @param connector 建立连接；测试注入假的
 * @param nowMillis 只用于“连接失败之后多久可以再试”；测试注入虚拟时间
 */
class SessionToolHost(
    scope: CoroutineScope,
    private val config: SessionToolHostConfig = SessionToolHostConfig(),
    private val connector: SessionMcpConnector = SessionMcpConnector.streamableHttp(SessionUrlPolicy(config.maxServersPerSession, config.allowLoopbackHttp)),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : SessionToolPort, AutoCloseable {

    private val hostScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + CoroutineName("session-tool-host"))
    private val policy = SessionUrlPolicy(config.maxServersPerSession, config.allowLoopbackHttp)
    private val lock = Any()
    private val sessions = HashMap<String, SessionState>() // guarded by lock

    private class Route(val rt: ServerRt, val tool: String)

    private class View(val perServer: List<Pair<ServerRt, List<CatalogTool>>>, val routes: Map<String, Route>) {
        companion object {
            val EMPTY = View(emptyList(), emptyMap())
        }
    }

    /** 一个服务器的运行时状态。除 [link] 外都由 [lock] 保护。[server] 里有 URL 和头：不进任何输出。 */
    private class ServerRt(val server: SessionMcpServer) {
        val name: String get() = server.name

        @Volatile var link: McpServerLink? = null
        var tools: List<McpToolInfo>? = null
        var lastFailureAt: Long? = null
        var lastReason: String? = null
        var job: Deferred<Boolean>? = null
        var watchers: List<Job> = emptyList()

        fun live(): Boolean = link?.let { !it.closed.isCompleted } == true

        override fun toString() = "ServerRt(name=$name)"
    }

    private class SessionState(val id: String, val owner: String, val servers: List<ServerRt>) {
        var active = true // guarded by lock

        @Volatile var view: View = View.EMPTY

        override fun toString() = "SessionState(servers=${servers.size})"
    }

    // ------------------------------------------------------------------ SessionToolPort

    override suspend fun attach(sessionId: String, owner: CallerIdentity, servers: List<SessionMcpServer>): List<SessionMcpResult> {
        policy.validate(servers)
        if (servers.isEmpty()) {
            detach(sessionId)
            return emptyList()
        }
        val ownerKey = owner.ownerKey
        val state = SessionState(sessionId, ownerKey, servers.map { ServerRt(SessionMcpServer(it.name, it.url, it.headers.toList())) })
        val old = synchronized(lock) {
            val others = sessions.values.filter { it.id != sessionId }
            if (others.filter { it.owner == ownerKey }.sumOf { it.servers.size } + servers.size > config.maxServersPerOwner) {
                throw SessionMcpRejected("quota", "too many MCP servers attached by this caller (at most ${config.maxServersPerOwner})")
            }
            if (others.sumOf { it.servers.size } + servers.size > config.maxServersTotal) {
                throw SessionMcpRejected("quota", "too many MCP servers attached in total (at most ${config.maxServersTotal})")
            }
            sessions.put(sessionId, state)
        }
        old?.let { drop(it) }

        val jobs = state.servers.map { startConnect(state, it) }
        withTimeoutOrNull(config.attachTimeoutMillis) { jobs.joinAll() }
        return state.servers.mapIndexed { i, rt ->
            synchronized(lock) {
                if (rt.live() && rt.tools != null) {
                    SessionMcpResult(rt.name, connected = true, toolCount = rt.tools?.size ?: 0)
                } else {
                    if (jobs[i].isActive) {
                        jobs[i].cancel()
                        rt.lastFailureAt = nowMillis()
                        rt.lastReason = "timeout"
                    }
                    SessionMcpResult(rt.name, connected = false, reason = if (state.active) rt.lastReason ?: "connect_failed" else "detached")
                }
            }
        }
    }

    override fun detach(sessionId: String) {
        val state = synchronized(lock) { sessions.remove(sessionId) } ?: return
        drop(state)
    }

    override fun tools(sessionId: String): List<CatalogTool> {
        val state = synchronized(lock) { sessions[sessionId] } ?: return emptyList()
        return state.view.perServer.filter { it.first.live() }.flatMap { it.second }.sortedBy { it.name }
    }

    override suspend fun prepare(sessionId: String, timeoutMillis: Long) {
        val state = synchronized(lock) { sessions[sessionId] } ?: return
        val now = nowMillis()
        val jobs = ArrayList<Deferred<Boolean>>()
        for (rt in state.servers) {
            val wanted = synchronized(lock) {
                rt.job?.isActive == true || (!rt.live() && (rt.lastFailureAt?.let { now - it >= config.retryAfterFailureMillis } ?: true))
            }
            if (wanted) jobs += startConnect(state, rt)
        }
        if (jobs.isEmpty()) return
        withTimeoutOrNull(timeoutMillis) { jobs.joinAll() }
    }

    override suspend fun invoke(invocation: ToolInvocation): ToolInvocationResult {
        val state = synchronized(lock) { sessions[invocation.sessionId] } ?: return notInCatalog(invocation)
        val route = state.view.routes[invocation.name] ?: return notInCatalog(invocation)
        val link = route.rt.link?.takeIf { !it.closed.isCompleted } ?: return notInCatalog(invocation)
        return call(link, route.tool, invocation)
    }

    /** 释放所有会话，取消后台任务。 */
    override fun close() {
        val all = synchronized(lock) { sessions.values.toList().also { sessions.clear() } }
        all.forEach { drop(it) }
        hostScope.cancel()
    }

    override fun toString() = "SessionToolHost(sessions=${synchronized(lock) { sessions.size }})"

    // ------------------------------------------------------------------ 调用

    private sealed interface Outcome {
        class Ok(val result: McpCallResult) : Outcome

        class Failed(val error: McpLinkException) : Outcome

        object Lost : Outcome
    }

    private suspend fun call(link: McpServerLink, tool: String, invocation: ToolInvocation): ToolInvocationResult {
        val outcome: Outcome = coroutineScope {
            // 调用本身和“连接被关掉”赛跑：detach、会话过期时即使链接没有让挂起的调用抛异常，也能在这里结束
            val running = async<Outcome> {
                try {
                    Outcome.Ok(link.callTool(tool, invocation.arguments, invocation.timeoutMillis))
                } catch (e: McpLinkException) {
                    Outcome.Failed(e)
                }
            }
            val result = select {
                running.onAwait { it }
                link.closed.onAwait { Outcome.Lost }
            }
            running.cancel()
            result
        }
        // 消息是固定的文字：不转述底层异常的 message
        return when (outcome) {
            is Outcome.Ok -> ToolInvocationResult.Completed(convert(outcome.result))
            is Outcome.Lost -> unknown(ErrorCode.TOOL_RESULT_UNKNOWN, "The connection to the session tool server was closed after the request was sent; the result is unknown.")
            is Outcome.Failed -> when (val e = outcome.error) {
                is ConnectFailed, is NotSent -> notDispatched(ErrorCode.TOOL_UNAVAILABLE, "The session tool server is not reachable; the request was not sent.")
                is ConnectionLost -> unknown(ErrorCode.TOOL_RESULT_UNKNOWN, "The connection to the session tool server was lost after the request was sent; the result is unknown.")
                is TimedOut -> unknown(ErrorCode.TOOL_TIMEOUT, "Tool ${invocation.name} did not respond in ${e.timeoutMillis} ms; the result is unknown.")
                is ServerError -> ToolInvocationResult.Completed(ToolResult.text("[mcp error ${e.code}]" + shortCode(e.detail), isError = true))
            }
        }
    }

    /** 只有短代码形状（`invalid_params`、`http_502`）的 detail 才转述；其他一律不带，免得服务端的原文进模型上下文。 */
    private fun shortCode(detail: String) = if (SHORT_CODE.matches(detail)) " $detail" else ""

    private fun notInCatalog(invocation: ToolInvocation) =
        notDispatched(ErrorCode.TOOL_NOT_IN_CATALOG, "Tool ${invocation.name} is not available.")

    private fun notDispatched(code: ErrorCode, message: String) = ToolInvocationResult.NotDispatched(code.info(message))

    private fun unknown(code: ErrorCode, message: String) = ToolInvocationResult.Unknown(code.info(message))

    /** MCP 结果 → [ToolResult]：文字、图片原样；其他类型换一句说明；结构化结果在没有文字时当文字；超长文字截断。 */
    private fun convert(r: McpCallResult): ToolResult {
        val parts = ArrayList<ContentPart>()
        for (c in r.content) {
            when (c) {
                is McpContentPart.Text -> parts += ContentPart.Text(c.text)
                is McpContentPart.Image -> parts += ContentPart.Image(c.data, c.mimeType)
                is McpContentPart.Other -> parts += ContentPart.Text("[agentos: omitted a ${safeType(c.type)} content part]")
            }
        }
        if (parts.none { it is ContentPart.Text } && r.structuredContent != null) parts += ContentPart.Text(Json.encodeToString(JsonElement.serializer(), r.structuredContent))
        if (parts.isEmpty()) parts += ContentPart.Text("")
        return ToolResult(truncate(parts), isError = r.isError, details = r.structuredContent)
    }

    private fun safeType(type: String) = type.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_-./+" }.take(32).ifEmpty { "unknown" }

    private fun truncate(parts: List<ContentPart>): List<ContentPart> {
        val total = parts.sumOf { (it as? ContentPart.Text)?.text?.length ?: 0 }
        val max = config.maxResultChars
        if (total <= max) return parts
        fun marker(kept: Int) = "\n[agentos:${ErrorCode.TOOL_RESULT_TOO_LARGE.wire}] Result truncated from $total to $kept characters."
        var budget = (max - marker(max).length).coerceAtLeast(0)
        val kept = budget
        val out = ArrayList<ContentPart>()
        for (p in parts) {
            if (p !is ContentPart.Text) {
                out += p
                continue
            }
            val text = p.text.take(budget)
            budget -= text.length
            out += ContentPart.Text(text)
        }
        out += ContentPart.Text(marker(kept))
        return out
    }

    // ------------------------------------------------------------------ 连接

    /** 连接并取工具列表（同一个服务器的并发请求共用一个任务）。完成值是是否成功；失败原因记在 [ServerRt.lastReason]。 */
    private fun startConnect(state: SessionState, rt: ServerRt): Deferred<Boolean> = synchronized(lock) {
        if (!state.active) return@synchronized CompletableDeferred(false)
        rt.job?.takeIf { it.isActive }?.let { return@synchronized it }
        hostScope.async { connectAndList(state, rt) }.also { rt.job = it }
    }

    private suspend fun connectAndList(state: SessionState, rt: ServerRt): Boolean {
        // 链接一建好就放进 handoff：withTimeout 返回时如果协程恰好被取消，返回值会被丢掉，链接就没人关了
        val handoff = AtomicReference<McpServerLink?>()
        try {
            withTimeout(config.connectTimeoutMillis) { handoff.set(connector.connect(rt.server, config.connectTimeoutMillis)) }
        } catch (e: TimeoutCancellationException) {
            handoff.get()?.let { runCatching { it.close() } }
            return failed(state, rt, "timeout")
        } catch (e: CancellationException) {
            handoff.get()?.let { runCatching { it.close() } }
            throw e
        } catch (e: Exception) {
            handoff.get()?.let { runCatching { it.close() } }
            return failed(state, rt, "connect_failed")
        }
        val link = handoff.get() ?: return failed(state, rt, "connect_failed")
        var adopted = false
        try {
            val listed = try {
                withTimeout(config.listTimeoutMillis) { link.listTools(config.listTimeoutMillis) }
            } catch (e: TimeoutCancellationException) {
                return failed(state, rt, "timeout")
            } catch (e: CancellationException) {
                throw e
            } catch (e: TimedOut) {
                return failed(state, rt, "timeout")
            } catch (e: Exception) {
                return failed(state, rt, "list_failed")
            }
            val clean = sanitize(listed)
            if (clean.size > config.maxToolsPerServer) return failed(state, rt, "too_many_tools")
            adopted = adopt(state, rt, link, clean)
            return adopted
        } finally {
            if (!adopted) runCatching { link.close() }
        }
    }

    private fun failed(state: SessionState, rt: ServerRt, reason: String): Boolean {
        synchronized(lock) {
            if (state.active) {
                rt.lastFailureAt = nowMillis()
                rt.lastReason = reason
            }
        }
        return false
    }

    /** 新链接上岗：换下旧链接，换上工具，开始监视。会话已经被 detach 或替换时返回 false（调用方关掉这条链接）。 */
    private fun adopt(state: SessionState, rt: ServerRt, link: McpServerLink, tools: List<McpToolInfo>): Boolean {
        val stale = synchronized(lock) {
            if (!state.active) return false
            val previous = rt.link
            rt.watchers.forEach { it.cancel() }
            rt.link = link
            rt.tools = tools
            rt.lastFailureAt = null
            rt.lastReason = null
            rt.watchers = watch(state, rt, link)
            rebuildView(state)
            previous
        }
        stale?.let { runCatching { it.close() } }
        return true
    }

    /** 链接的两个观察者：链接关闭（对端断开、会话过期）、`list_changed`。 */
    private fun watch(state: SessionState, rt: ServerRt, link: McpServerLink): List<Job> = listOf(
        hostScope.launch {
            link.closed.await()
            synchronized(lock) {
                if (rt.link === link) {
                    // 掉线不是连接失败：不设退避，下一次 prepare 立刻重连
                    rt.link = null
                    rt.lastReason = "connection_lost"
                    rt.watchers.forEach { it.cancel() }
                    rt.watchers = emptyList()
                }
            }
        },
        hostScope.launch { link.toolsChanged.collectLatest { refresh(state, rt, link) } },
    )

    /** `list_changed`：重新取工具列表。失败时保留旧的；变得太多时按 attach 的规则拒绝这个服务器。 */
    private suspend fun refresh(state: SessionState, rt: ServerRt, link: McpServerLink) {
        val listed = try {
            withTimeout(config.listTimeoutMillis) { link.listTools(config.listTimeoutMillis) }
        } catch (e: TimeoutCancellationException) {
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        val clean = sanitize(listed)
        val tooMany = clean.size > config.maxToolsPerServer
        val applied = synchronized(lock) {
            if (!state.active || rt.link !== link) {
                false
            } else {
                if (tooMany) {
                    rt.link = null
                    rt.tools = emptyList()
                    rt.lastFailureAt = nowMillis()
                    rt.lastReason = "too_many_tools"
                } else {
                    rt.tools = clean
                }
                rebuildView(state)
                true
            }
        }
        if (applied && tooMany) runCatching { link.close() }
    }

    /** detach、被替换、关闭：取消后台任务，断开所有链接，丢掉内存里的 URL 和头（[ServerRt.server] 随对象一起不可达）。 */
    private fun drop(state: SessionState) {
        val links = ArrayList<McpServerLink>()
        synchronized(lock) {
            state.active = false
            for (rt in state.servers) {
                rt.job?.cancel()
                rt.job = null
                rt.watchers.forEach { it.cancel() }
                rt.watchers = emptyList()
                rt.link?.let { links += it }
                rt.link = null
            }
        }
        for (link in links) runCatching { link.close() }
    }

    // ------------------------------------------------------------------ 目录

    /** 去掉空名、过长的名字、schema 不是 object 或过大的工具，重名只留第一个。 */
    private fun sanitize(listed: List<McpToolInfo>): List<McpToolInfo> {
        val seen = HashSet<String>()
        val out = ArrayList<McpToolInfo>()
        for (t in listed) {
            if (t.name.isEmpty() || t.name.length > MAX_TOOL_NAME_CHARS) continue
            if ((t.inputSchema["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "object") continue
            if (t.inputSchema.toString().length > config.maxSchemaChars) continue
            if (seen.add(t.name)) out += t
        }
        return out
    }

    /** 重算这个会话的目录和路由（持有 [lock] 时调用）。名字对所有已知工具一起算，不管服务器现在是否连着。 */
    private fun rebuildView(state: SessionState) {
        val ids = ArrayList<Pair<String, String>>()
        for (rt in state.servers) rt.tools?.forEach { ids += rt.name to it.name }
        val names = SessionToolNaming.assign(ids)
        val routes = HashMap<String, Route>()
        val perServer = state.servers.map { rt ->
            rt to (rt.tools ?: emptyList()).map { info ->
                val name = names.getValue(rt.name to info.name)
                routes[name] = Route(rt, info.name)
                val ann = info.annotations
                CatalogTool(
                    name = name,
                    description = clean(info.description ?: info.title ?: "", config.maxDescriptionChars),
                    inputSchema = info.inputSchema,
                    risk = RiskPolicy.effectiveRisk(null, ToolAnnotations(ann?.readOnlyHint, ann?.destructiveHint)),
                    provider = "session:${rt.name}",
                    title = info.title?.let { clean(it, config.maxTitleChars) }?.takeIf { it.isNotEmpty() },
                    source = null,
                )
            }
        }
        state.view = View(perServer, routes)
    }

    /** 第三方文字：控制字符（含换行）折叠成一个空格（紧跟其后的空格并入），去掉零宽和双向控制符，截断到 [max]（不拆代理对）。 */
    private fun clean(text: String, max: Int): String {
        val sb = StringBuilder()
        var gap = false
        for (ch in text) {
            when {
                Character.isISOControl(ch) || ch == '\u2028' || ch == '\u2029' -> gap = true
                Character.getType(ch) == Character.FORMAT.toInt() -> Unit
                ch == ' ' && gap -> Unit
                else -> {
                    if (gap && sb.isNotEmpty()) sb.append(' ')
                    gap = false
                    sb.append(ch)
                }
            }
            if (sb.length >= max) break
        }
        if (sb.length > max) sb.setLength(max)
        if (sb.isNotEmpty() && Character.isHighSurrogate(sb.last())) sb.setLength(sb.length - 1)
        return sb.toString().trim()
    }

    private companion object {
        const val MAX_TOOL_NAME_CHARS = 128
        val SHORT_CODE = Regex("[a-z0-9_]{1,40}")
    }
}
