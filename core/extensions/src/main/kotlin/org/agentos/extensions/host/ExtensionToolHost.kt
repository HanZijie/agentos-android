package org.agentos.extensions.host

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.agentos.extensions.McpServerDecl
import org.agentos.extensions.ToolId
import org.agentos.extensions.ToolNaming
import org.agentos.extensions.registry.PluginRecord
import org.agentos.extensions.registry.PluginRegistry
import org.agentos.extensions.registry.PluginStatus
import org.agentos.extensions.registry.RegistryEvent
import org.agentos.runtime.broker.RiskPolicy
import org.agentos.runtime.broker.ToolAnnotations
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.ports.ApprovalPolicyPort
import org.agentos.runtime.ports.CatalogTool
import org.agentos.runtime.ports.ContentPart
import org.agentos.runtime.ports.RuntimeLog
import org.agentos.runtime.ports.ToolCatalog
import org.agentos.runtime.ports.ToolInvocation
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolPort
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.agentos.runtime.ports.warn
import java.util.concurrent.atomic.AtomicInteger

/** [ExtensionToolHost] 的参数。时间都是毫秒。 */
data class ExtensionHostConfig(
    /** 没有调用、没有在途请求这么久之后断开连接并 unbind（docs/extensions.md 5.1：30 秒）。 */
    val idleTimeoutMillis: Long = 30_000,
    /** [McpServerConnector.connect] 的上限；超时按 [ConnectFailed] 处理。 */
    val connectTimeoutMillis: Long = 15_000,
    /** `tools/list` 的上限。 */
    val listTimeoutMillis: Long = 10_000,
    /** 每个服务器同时在途的调用数上限；超过的调用排队等待（等待计入调用方自己的超时）。 */
    val maxInFlightPerServer: Int = 8,
    /** 交回模型的结果文字上限（字符，含截断说明），与 `BrokerConfig.maxResultChars` 默认值对齐，Broker 不会再截一次。 */
    val maxResultChars: Int = 32_768,
    /** 工具描述的上限（第三方文本，不可信）。 */
    val maxDescriptionChars: Int = 1_024,
    val maxTitleChars: Int = 128,
    /** 单个服务器最多列出的工具数；多出的忽略（防止恶意服务器撑爆目录和提示）。 */
    val maxToolsPerServer: Int = 128,
    /** 输入 schema 序列化后的上限；超过的工具不列出。 */
    val maxSchemaChars: Int = 16_384,
    /** 连接失败之后，自动刷新（[refreshNow] 不带 `force`）至少隔这么久才再试一次。 */
    val retryAfterFailureMillis: Long = 30_000,
)

/** [ExtensionToolHost.refreshNow] 的结果。 */
data class RefreshResult(
    /** 这次刷新成功的服务器。 */
    val refreshed: List<ServerKey>,
    /** 这次刷新失败（连不上、列表失败）的服务器。 */
    val failed: List<ServerKey>,
    /** 到了上限还有服务器没刷新完（它们继续在后台刷新，目录会在完成时更新）。 */
    val timedOut: Boolean,
)

/**
 * Extension Host 的纯逻辑（docs/extensions.md 5.1、5.3、5.4、9）：把已启用插件的 MCP 服务器汇总成**工具目录**，
 * 管理到服务器的**连接生命周期**，并作为 [ToolPort] 执行调用。不依赖 Android：连接经 [McpServerConnector]（Android 上是 Binder），
 * 插件来自 [registry]（A8），用户策略来自 [approvals]（A7/A8）。
 *
 * ## 目录（[catalog]）
 * - 只列 **插件可用（READY）∧ 插件启用 ∧ 服务器启用 ∧ 工具启用** 的工具（启用按 `ApprovalPolicy.resolve`）；
 *   从没成功取到过工具列表的服务器不列出。
 * - 工具名用 [ToolNaming.assign] 对**所有**READY 插件的所有已知工具一起算（包括被用户策略禁用的：禁用工具、服务器、插件时缓存都保留），
 *   所以用户来回切换启用状态不会让别的工具改名（插件被移除、签名变化、Revoke 之后缓存丢弃，名字才可能变）；`CatalogTool.source = ToolSource(插件名, 服务器名, 原始工具名)`，`provider = 插件 ID`。
 * - `risk = RiskPolicy.effectiveRisk(声明, 注解)`：MCP 工具没有声明（默认写），`destructiveHint=true` 升为高风险；
 *   **AgentOS 自带插件**（受信任）的 `readOnlyHint=true` 工具声明为读，其余来源的 readOnlyHint 一律不降低风险。
 * - 描述、title 截断（[ExtensionHostConfig.maxDescriptionChars]、[ExtensionHostConfig.maxTitleChars]）；单个服务器的工具数、
 *   schema 大小有上限；同一服务器重名的工具只留第一个。
 * - [ToolCatalog.version] 在**内容变化**时递增（启用、禁用、list_changed、刷新、插件移除、签名变化、Revoke）。
 *
 * ## 连接生命周期
 * - 启动后和注册表、策略变化后，对没有缓存的可用服务器**先连一次**取工具列表（后台进行，缓存下来）；之后第一次调用时也会连接。
 * - **空闲 [ExtensionHostConfig.idleTimeoutMillis]（30 秒）没有在途调用就断开**（Android 上 unbind，App 进程可以被回收），
 *   下次用到再连；缓存的工具列表保留。每次（重新）连接后在后台重新取一次工具列表；收到 `list_changed` 时刷新。
 * - 连不上时，该服务器的工具**按最后一次成功的缓存保留在目录里**，调用时以 NotDispatched（`tool_unavailable`）拒绝；
 *   状态是 [ServerState.Unreachable]。从没成功过则不列出。
 * - [serverStates] 给插件页：Idle / Connected / Unreachable(原因) / Disabled(原因)。
 * - [refreshNow]：运行时在任务开始前调用，等“还没有缓存的服务器”刷新完（有上限），不会为已有缓存的服务器重连。
 *
 * ## 调用（[invoke]）：三种结局
 * 1. **NotDispatched**（确定没发出）：工具不在目录里或被策略禁用；服务器连不上（[ConnectFailed]）；连接已关闭、请求没写出去（[NotSent]）。
 * 2. **Unknown**（已发出，没拿到结果，**不重放**）：连接断了（[ConnectionLost]，含 App 进程死亡、被 Revoke）→ `tool_result_unknown`；
 *    超时（[TimedOut]）→ `tool_timeout`。
 * 3. **Completed**：拿到结果；`isError` 原样保留（工具自己执行失败）；服务端回 JSON-RPC 错误（[ServerError]）也是 Completed，
 *    `isError = true`、文字 `[mcp error …]`。结果里的文字不可信，原样交给模型（Broker 的 afterExecute 与 Hook 在它之后）。
 *    超过 [ExtensionHostConfig.maxResultChars] 的文字截断并加说明；图片以外的非文字内容换成一句“省略了 <类型> 内容”。
 *
 * 协程被取消时，取消传给链接层（链接向服务端发 `notifications/cancelled`），然后以 CancellationException 结束。
 * 每个服务器同时在途的调用不超过 [ExtensionHostConfig.maxInFlightPerServer]。
 *
 * ## Revoke（[onRegistryEvent]）
 * 收到 [RegistryEvent.Revoke]：立即关闭该插件的连接、丢弃缓存、把它的工具从目录移除；进行中的调用返回 Unknown。
 *
 * @param nowMillis 单调的毫秒时钟，只用于“连接失败后多久可以自动重试”；测试注入虚拟时间
 */
class ExtensionToolHost(
    private val registry: StateFlow<PluginRegistry>,
    private val approvals: ApprovalPolicyPort,
    private val connector: McpServerConnector,
    parentScope: CoroutineScope,
    private val config: ExtensionHostConfig = ExtensionHostConfig(),
    private val log: RuntimeLog = RuntimeLog.NONE,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ToolPort, AutoCloseable {

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + CoroutineName("extension-host"))
    private val lock = Any()
    private val servers = HashMap<ServerKey, ServerRt>()

    private val catalogFlow = MutableStateFlow(ToolCatalog.EMPTY)
    private val statesFlow = MutableStateFlow<Map<ServerKey, ServerState>>(emptyMap())

    /** 调用名 → 解析结果，随目录一起更新（调用时再按当前策略确认一次）。 */
    @Volatile private var routes: Map<String, Route> = emptyMap()

    override val catalog: StateFlow<ToolCatalog> = catalogFlow.asStateFlow()

    /** 每个 MCP 服务器现在的状态（含不可用插件的服务器，状态是 Disabled）。 */
    val serverStates: StateFlow<Map<ServerKey, ServerState>> = statesFlow.asStateFlow()

    @Volatile private var closed = false

    private class Route(val key: ServerKey, val pluginName: String, val tool: String)

    private inner class ServerRt(val key: ServerKey, var plugin: PluginRecord, var decl: McpServerDecl) {
        val connectMutex = Mutex()
        val permits = Semaphore(config.maxInFlightPerServer)
        val inFlight = AtomicInteger()

        @Volatile var link: McpServerLink? = null
        var tools: List<McpToolInfo>? = null
        var state: ServerState = ServerState.Idle
        var idleJob: Job? = null
        var linkJobs: List<Job> = emptyList()
        var refreshJob: Deferred<Boolean>? = null
        /** 最近一次连接或取列表失败的时间（[nowMillis]）；没有失败过为 null。 */
        var lastFailureAt: Long? = null

        /** 连接被主动关闭（Revoke、禁用、移除）时加一：连接中的任务据此丢弃自己的结果。 */
        @Volatile var generation = 0
        var disabled = false

        /** 被禁用期间缓存可能过期：重新启用后要再取一次（取到之前不影响命名，工具名按已知的缓存算）。 */
        var stale = false
    }

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { registry.collectLatest { reconcile() } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { approvals.policy.collectLatest { reconcile() } }
    }

    // ------------------------------------------------------------------ 对外

    /**
     * 等“还没有缓存的可用服务器”刷新完，最多 [timeoutMillis]；运行时在任务开始前调用，保证模型看到的目录不是空的。
     * 已经有缓存的服务器**不会**为此重连（它们按空闲回收的规则在用到时再连）；[force] 为 true 时所有可用服务器都刷新。
     * 连接失败的服务器在 [ExtensionHostConfig.retryAfterFailureMillis] 内不重试（[force] 除外），避免每个任务都白等一个连接超时。
     * 到了上限没刷新完的继续在后台刷新，目录会在完成时更新。
     */
    suspend fun refreshNow(timeoutMillis: Long, force: Boolean = false): RefreshResult {
        val now = nowMillis()
        val targets = synchronized(lock) {
            servers.values.filter { rt ->
                !rt.disabled && (force || ((rt.tools == null || rt.stale) && rt.lastFailureAt.let { it == null || now - it >= config.retryAfterFailureMillis }))
            }
        }
        if (targets.isEmpty()) return RefreshResult(emptyList(), emptyList(), false)
        val jobs = targets.associateWith { rt -> startRefresh(rt) }
        val done = withTimeoutOrNull(timeoutMillis) { jobs.values.forEach { it.await() } } != null
        val refreshed = ArrayList<ServerKey>()
        val failed = ArrayList<ServerKey>()
        for ((rt, job) in jobs) {
            if (!job.isCompleted) continue
            if (job.await()) refreshed += rt.key else failed += rt.key
        }
        return RefreshResult(refreshed, failed, timedOut = !done)
    }

    /** [ToolPort.prepare]：任务开始前等没有缓存的服务器刷新完（[refreshNow]，不强制）。 */
    override suspend fun prepare(timeoutMillis: Long) {
        refreshNow(timeoutMillis)
    }

    /** 处理注册表事件：[RegistryEvent.Revoke] 立即关闭该插件的连接、丢弃缓存、把它的工具从目录移除。其他事件不需要处理（注册表本身会变）。 */
    fun onRegistryEvent(event: RegistryEvent) {
        if (event !is RegistryEvent.Revoke) return
        val affected = synchronized(lock) { servers.values.filter { it.key.pluginId == event.pluginId } }
        for (rt in affected) {
            synchronized(lock) {
                dropConnection(rt)
                rt.tools = null
                rt.lastFailureAt = null
                rt.state = ServerState.Idle
            }
        }
        rebuildCatalog()
        publish()
    }

    override suspend fun invoke(invocation: ToolInvocation): ToolInvocationResult {
        val route = routeFor(invocation.name) ?: return notDispatched(ErrorCode.TOOL_NOT_IN_CATALOG, "Tool ${invocation.name} is not available.")
        val rt = synchronized(lock) { servers[route.key] } ?: return notDispatched(ErrorCode.TOOL_NOT_IN_CATALOG, "Tool ${invocation.name} is not available.")
        rt.permits.acquire()
        try {
            rt.inFlight.incrementAndGet()
            cancelIdle(rt)
            try {
                // 排队期间目录或策略可能变了：发出之前再确认一次
                val still = routeFor(invocation.name)
                if (still == null || still.key != route.key) return notDispatched(ErrorCode.TOOL_NOT_IN_CATALOG, "Tool ${invocation.name} is not available.")
                val link = try {
                    ensureLink(rt, background = true)
                } catch (e: ConnectFailed) {
                    return notDispatched(ErrorCode.TOOL_UNAVAILABLE, "Tool provider ${route.pluginName} is not reachable: ${e.message}")
                }
                return call(link, route, invocation)
            } finally {
                rt.inFlight.decrementAndGet()
                scheduleIdle(rt)
            }
        } finally {
            rt.permits.release()
        }
    }

    override fun close() {
        closed = true
        val all = synchronized(lock) { servers.values.toList() }
        for (rt in all) synchronized(lock) { dropConnection(rt) }
        scope.cancel()
    }

    // ------------------------------------------------------------------ 调用

    private sealed interface Outcome {
        class Ok(val result: McpCallResult) : Outcome

        class Failed(val error: McpLinkException) : Outcome

        class Lost(val reason: String) : Outcome
    }

    private suspend fun call(link: McpServerLink, route: Route, invocation: ToolInvocation): ToolInvocationResult {
        val outcome: Outcome = coroutineScope {
            // 调用本身和“连接被关掉”赛跑：Revoke、进程死亡时即使链接没有让挂起的调用抛异常，也能在这里结束
            val call = async<Outcome> {
                try {
                    Outcome.Ok(link.callTool(route.tool, invocation.arguments, invocation.timeoutMillis))
                } catch (e: McpLinkException) {
                    Outcome.Failed(e)
                }
            }
            val result = select {
                call.onAwait { it }
                link.closed.onAwait { reason -> Outcome.Lost(reason) }
            }
            call.cancel()
            result
        }
        return when (outcome) {
            is Outcome.Ok -> ToolInvocationResult.Completed(convert(outcome.result))
            is Outcome.Lost -> unknown(ErrorCode.TOOL_RESULT_UNKNOWN, "The connection to ${route.pluginName} was closed after the request was sent (${outcome.reason}); the result is unknown.")
            is Outcome.Failed -> when (val e = outcome.error) {
                is ConnectFailed, is NotSent -> notDispatched(ErrorCode.TOOL_UNAVAILABLE, "Tool provider ${route.pluginName} is not reachable: ${e.message}")
                is ConnectionLost -> unknown(ErrorCode.TOOL_RESULT_UNKNOWN, "The connection to ${route.pluginName} was lost after the request was sent; the result is unknown.")
                is TimedOut -> unknown(ErrorCode.TOOL_TIMEOUT, "Tool ${invocation.name} did not respond in ${e.timeoutMillis} ms; the result is unknown.")
                is ServerError -> ToolInvocationResult.Completed(ToolResult.text("[mcp error ${e.code}] ${e.detail.take(500)}", isError = true))
            }
        }
    }

    private fun notDispatched(code: ErrorCode, message: String) = ToolInvocationResult.NotDispatched(code.info(message))

    private fun unknown(code: ErrorCode, message: String) = ToolInvocationResult.Unknown(code.info(message))

    /** MCP 结果 → [ToolResult]：文字、图片原样；其他类型换一句说明；结构化结果在没有文字时当文字；超长文字截断。 */
    private fun convert(r: McpCallResult): ToolResult {
        val parts = ArrayList<ContentPart>()
        for (c in r.content) {
            when (c) {
                is McpContentPart.Text -> parts += ContentPart.Text(c.text)
                is McpContentPart.Image -> parts += ContentPart.Image(c.data, c.mimeType)
                is McpContentPart.Other -> parts += ContentPart.Text("[the tool returned ${c.type} content, omitted]")
            }
        }
        if (parts.none { it is ContentPart.Text } && r.structuredContent != null) parts += ContentPart.Text(Json.encodeToString(JsonElement.serializer(), r.structuredContent))
        if (parts.isEmpty()) parts += ContentPart.Text("")
        return ToolResult(truncate(parts), isError = r.isError, details = r.structuredContent)
    }

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

    /**
     * 返回可用的链接，没有就连一个。并发的调用共用同一次连接。连不上抛 [ConnectFailed]（任何连接阶段的异常都归到它）。
     * [background]：这次调用新建了连接时，在后台取一次工具列表（调用不等它）；刷新任务自己取，传 false。
     */
    private suspend fun ensureLink(rt: ServerRt, background: Boolean): McpServerLink {
        liveLink(rt)?.let { return it }
        var created = false
        val link = rt.connectMutex.withLock {
            liveLink(rt)?.let { return@withLock it }
            val generation = rt.generation
            val (plugin, decl) = synchronized(lock) { rt.plugin to rt.decl }
            val connected = try {
                withTimeout(config.connectTimeoutMillis) { connector.connect(plugin, decl) }
            } catch (e: TimeoutCancellationException) {
                throw failedConnect(rt, "connecting timed out after ${config.connectTimeoutMillis} ms", null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: McpLinkException) {
                throw failedConnect(rt, e.message ?: e.javaClass.simpleName, e)
            } catch (e: Exception) {
                throw failedConnect(rt, "connect failed: ${e.javaClass.simpleName}", e)
            }
            val adopted = synchronized(lock) {
                if (closed || rt.generation != generation || servers[rt.key] !== rt || rt.disabled) {
                    false
                } else {
                    rt.link = connected
                    rt.state = ServerState.Connected
                    rt.linkJobs = watch(rt, connected)
                    true
                }
            }
            if (!adopted) {
                runCatching { connected.close() }
                throw ConnectFailed("the server was disabled or removed while connecting")
            }
            created = true
            publish()
            connected
        }
        if (created && background) scope.launch { refreshTools(rt, link) }
        return link
    }

    private fun liveLink(rt: ServerRt): McpServerLink? = rt.link?.takeIf { !it.closed.isCompleted }

    private fun failedConnect(rt: ServerRt, reason: String, cause: Throwable?): ConnectFailed {
        synchronized(lock) {
            rt.link = null
            rt.state = ServerState.Unreachable(reason)
            rt.lastFailureAt = nowMillis()
        }
        publish()
        return ConnectFailed(reason, cause)
    }

    /** 连接的两个观察者：连接关闭（进程死亡等）、`list_changed`。 */
    private fun watch(rt: ServerRt, link: McpServerLink): List<Job> = listOf(
        scope.launch {
            val reason = link.closed.await()
            val unexpected = synchronized(lock) {
                if (rt.link === link) {
                    rt.link = null
                    rt.state = ServerState.Unreachable("connection closed: $reason")
                    rt.lastFailureAt = nowMillis()
                    true
                } else {
                    false
                }
            }
            if (unexpected) {
                log.warn(TAG, "connection to ${rt.key.pluginId}/${rt.key.server} closed: $reason")
                publish()
            }
        },
        scope.launch { link.toolsChanged.collectLatest { refreshTools(rt, link) } },
    )

    /** 取工具列表放进缓存；失败时保留旧缓存，状态记成不可达。返回是否成功。 */
    private suspend fun refreshTools(rt: ServerRt, link: McpServerLink): Boolean {
        val listed = try {
            link.listTools(config.listTimeoutMillis)
        } catch (e: McpLinkException) {
            synchronized(lock) {
                if (rt.link === link) {
                    rt.state = ServerState.Unreachable("listing tools failed: ${e.message}")
                    rt.lastFailureAt = nowMillis()
                }
            }
            publish()
            return false
        }
        val accepted = synchronized(lock) {
            if (rt.link !== link) {
                false
            } else {
                rt.tools = sanitize(listed)
                rt.stale = false
                true
            }
        }
        if (accepted) {
            rebuildCatalog()
            publish()
        }
        return accepted
    }

    /** 去掉重名、空名、过大的工具，限制个数。 */
    private fun sanitize(listed: List<McpToolInfo>): List<McpToolInfo> {
        val seen = HashSet<String>()
        val out = ArrayList<McpToolInfo>()
        for (t in listed) {
            if (t.name.isEmpty() || !seen.add(t.name)) continue
            if (t.inputSchema.toString().length > config.maxSchemaChars) continue
            out += t
            if (out.size >= config.maxToolsPerServer) break
        }
        return out
    }

    /** 在后台刷新一个服务器（连接 + 取列表）；同一个服务器的并发刷新共用一个任务。完成值是是否成功。 */
    private fun startRefresh(rt: ServerRt): Deferred<Boolean> = synchronized(lock) {
        rt.refreshJob?.takeIf { it.isActive }?.let { return@synchronized it }
        val job = scope.async {
            try {
                val link = ensureLink(rt, background = false)
                refreshTools(rt, link)
            } catch (e: ConnectFailed) {
                false
            } finally {
                scheduleIdle(rt)
            }
        }
        rt.refreshJob = job
        job
    }

    // ------------------------------------------------------------------ 空闲回收

    private fun cancelIdle(rt: ServerRt) {
        synchronized(lock) {
            rt.idleJob?.cancel()
            rt.idleJob = null
        }
    }

    private fun scheduleIdle(rt: ServerRt) {
        if (closed) return
        synchronized(lock) {
            rt.idleJob?.cancel()
            if (rt.link == null || rt.inFlight.get() > 0) {
                rt.idleJob = null
                return
            }
            rt.idleJob = scope.launch {
                delay(config.idleTimeoutMillis)
                val closedNow = synchronized(lock) {
                    if (rt.inFlight.get() == 0 && rt.link != null) {
                        dropConnection(rt)
                        rt.state = ServerState.Idle
                        true
                    } else {
                        false
                    }
                }
                if (closedNow) publish()
            }
        }
    }

    /** 关闭连接、取消它的观察者（持有 [lock] 时调用）。进行中的调用通过 `link.closed` 结束。 */
    private fun dropConnection(rt: ServerRt) {
        rt.generation++
        val link = rt.link
        rt.link = null
        rt.idleJob?.cancel()
        rt.idleJob = null
        rt.linkJobs.forEach { it.cancel() }
        rt.linkJobs = emptyList()
        rt.refreshJob?.cancel()
        rt.refreshJob = null
        if (link != null) runCatching { link.close() }
    }

    // ------------------------------------------------------------------ 注册表与策略

    /** 注册表或策略变了：对齐服务器集合，关掉不再需要的连接，为新的可用服务器启动刷新，重建目录。 */
    private fun reconcile() {
        if (closed) return
        val reg = registry.value
        val policy = approvals.policy.value
        val toRefresh = ArrayList<ServerRt>()
        synchronized(lock) {
            val wanted = HashMap<ServerKey, Pair<PluginRecord, McpServerDecl>>()
            for (rec in reg.plugins) {
                if (rec.status != PluginStatus.READY) continue
                for (decl in rec.activeServers) wanted[ServerKey(rec.id, decl.name)] = rec to decl
            }
            for (key in servers.keys.toList()) {
                if (key !in wanted) {
                    val gone = servers.remove(key)!!
                    dropConnection(gone)
                }
            }
            for ((key, pair) in wanted) {
                val (rec, decl) = pair
                val existing = servers[key]
                val rt = if (existing == null) {
                    ServerRt(key, rec, decl).also { servers[key] = it }
                } else {
                    // 升级、改名、服务声明变了：连接和缓存都作废
                    if (existing.plugin.identity != rec.identity || existing.plugin.name != rec.name || existing.decl != decl) {
                        dropConnection(existing)
                        existing.tools = null
                        existing.state = ServerState.Idle
                        existing.lastFailureAt = null
                    }
                    existing.plugin = rec
                    existing.decl = decl
                    existing
                }
                val enabled = policy.resolve(ToolSource(rec.name ?: "", decl.name, "")).enabled
                if (!enabled && !rt.disabled) {
                    // 刚被禁用：关连接。缓存留着（工具名按它算，用户来回切换不会让别的工具改名），重新启用后再取一次
                    dropConnection(rt)
                    rt.state = ServerState.Idle
                    rt.stale = true
                }
                rt.disabled = !enabled
                if (enabled && (rt.tools == null || rt.stale) && rt.link == null && rt.refreshJob?.isActive != true) toRefresh += rt
            }
        }
        rebuildCatalog()
        publish()
        for (rt in toRefresh) startRefresh(rt)
    }

    private fun routeFor(name: String): Route? {
        val route = routes[name] ?: return null
        val rt = synchronized(lock) { servers[route.key] } ?: return null
        if (rt.disabled) return null
        val allowed = approvals.policy.value.resolve(ToolSource(route.pluginName, route.key.server, route.tool)).enabled
        return if (allowed) route else null
    }

    // ------------------------------------------------------------------ 目录

    private fun rebuildCatalog() {
        val policy = approvals.policy.value
        data class Known(val rt: ServerRt, val info: McpToolInfo)

        val known = ArrayList<Known>()
        synchronized(lock) {
            for (rt in servers.values.sortedBy { it.key.pluginId + "/" + it.key.server }) {
                val tools = rt.tools ?: continue
                for (t in tools) known += Known(rt, t)
            }
        }
        val names = ToolNaming.assign(known.map { ToolId(it.rt.plugin.name ?: "", it.rt.key.server, it.info.name) })
        val tools = ArrayList<CatalogTool>()
        val newRoutes = HashMap<String, Route>()
        for (k in known) {
            val pluginName = k.rt.plugin.name ?: continue
            val source = ToolSource(pluginName, k.rt.key.server, k.info.name)
            if (k.rt.disabled || !policy.resolve(source).enabled) continue
            val name = names.getValue(ToolId(pluginName, k.rt.key.server, k.info.name))
            val ann = k.info.annotations
            val declared: ToolRisk? = if (k.rt.plugin.builtin && ann?.readOnlyHint == true) ToolRisk.READ else null
            val risk = RiskPolicy.effectiveRisk(declared, ToolAnnotations(ann?.readOnlyHint, ann?.destructiveHint))
            tools += CatalogTool(
                name = name,
                description = (k.info.description ?: k.info.title ?: "").take(config.maxDescriptionChars),
                inputSchema = k.info.inputSchema,
                risk = risk,
                provider = k.rt.key.pluginId,
                title = k.info.title?.take(config.maxTitleChars),
                source = source,
            )
            newRoutes[name] = Route(k.rt.key, pluginName, k.info.name)
        }
        tools.sortBy { it.name }
        synchronized(lock) {
            routes = newRoutes
            val current = catalogFlow.value
            if (current.tools != tools) catalogFlow.value = ToolCatalog(current.version + 1, tools)
        }
    }

    private fun publish() {
        val reg = registry.value
        val states = LinkedHashMap<ServerKey, ServerState>()
        synchronized(lock) {
            for (rec in reg.plugins.sortedBy { it.id }) {
                for (decl in rec.servers) {
                    val key = ServerKey(rec.id, decl.name)
                    val rt = servers[key]
                    states[key] = when {
                        rec.status != PluginStatus.READY -> ServerState.Disabled(DisabledReason.PLUGIN_NOT_READY)
                        rt == null -> ServerState.Disabled(DisabledReason.PLUGIN_NOT_READY)
                        rt.disabled -> ServerState.Disabled(DisabledReason.USER_POLICY)
                        else -> rt.state
                    }
                }
            }
        }
        statesFlow.value = states
    }

    private companion object {
        const val TAG = "ExtensionHost"
    }
}
