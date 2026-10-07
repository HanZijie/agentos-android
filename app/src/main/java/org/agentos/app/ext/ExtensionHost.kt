package org.agentos.app.ext

import android.content.Context
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.app.agent.AndroidRuntimeLog
import org.agentos.app.ext.mcp.BinderMcpConnector
import org.agentos.app.ext.registry.ExtRegistry
import org.agentos.app.ext.registry.resolvePluginEnabled
import org.agentos.app.ext.registry.toApprovalMode
import org.agentos.extensions.host.DisabledReason
import org.agentos.extensions.host.ExtensionHostConfig
import org.agentos.extensions.host.ExtensionToolHost
import org.agentos.extensions.host.McpServerConnector
import org.agentos.extensions.host.ServerKey
import org.agentos.extensions.host.ServerState
import org.agentos.extensions.registry.PluginStatus
import org.agentos.extensions.registry.RegistryEvent
import org.agentos.runtime.broker.ApprovalMode
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.broker.PolicyScope
import org.agentos.runtime.broker.RiskPolicy
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.ports.CatalogTool
import org.agentos.runtime.ports.ToolCatalog
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Extension Host 在 `:ext` 进程里的全部状态（docs/extensions.md 第 2、5、9 节）。[ExtensionHostService] 是它的 Binder 外壳。
 *
 * - 插件注册表与用户策略：[ExtRegistry]（A8 的 PluginScanLogic / ApprovalStore；:ext 是策略的唯一写入方）。
 * - 工具目录、连接生命周期、调用的三种结局：A9 的 [ExtensionToolHost]（纯 JVM），连接器是 [BinderMcpConnector]。
 * - [snapshot]：目录 + 策略，**任一变化**版本号加一（ExtensionToolHost 的目录版本只随目录内容变，审批方式改了它不变，
 *   但运行时的策略镜像要更新），经 IExtensionCallback.onCatalogChanged 通知订阅方。
 * - 调用：[callTool] 受理后恰好一次 `deliver`；[cancelTool] 取消（ExtensionToolHost 把取消转给插件）；
 *   调用方（:agent）死亡时它的调用全部取消（[cancelCallsOf]）。
 */
class ExtensionHost(
    private val context: Context,
    val registry: ExtRegistry = ExtRegistry(context),
    connector: McpServerConnector? = null,
    config: ExtensionHostConfig = ExtensionHostConfig(),
) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("ext"))
    private val log = AndroidRuntimeLog { it }

    val connector: McpServerConnector = connector ?: BinderMcpConnector(context, scope, appVersion(context))

    val tools = ExtensionToolHost(
        registry = registry.registry,
        approvals = registry.approvals,
        connector = this.connector,
        parentScope = scope,
        config = config,
        log = log,
        nowMillis = SystemClock::elapsedRealtime,
    )

    /** 目录 + 策略的一致快照；[version] 在 :ext 进程内单调递增（:ext 重建后从 1 重新开始，运行时不依赖它跨进程单调）。 */
    data class Snapshot(val version: Long, val catalog: ToolCatalog, val policy: ApprovalPolicy)

    private val snapshotFlow = MutableStateFlow(Snapshot(0, ToolCatalog.EMPTY, registry.approvals.policy.value))
    val snapshot: StateFlow<Snapshot> = snapshotFlow.asStateFlow()

    /**
     * 见过的工具（按来源），给插件页列出**被禁用的**工具（ExtensionToolHost 的目录只含启用的）。
     * 只在本进程内记得：:ext 重建后，被工具级禁用的工具要等它的服务器再连上一次才重新出现（报告里请 A 提供已知工具的接口）。
     */
    private val known = LinkedHashMap<ToolSource, CatalogTool>()

    private class Call(val job: Job, val owner: IBinder?)
    private val calls = ConcurrentHashMap<String, Call>()

    private val accepted = AtomicLong()
    private val rejected = AtomicLong()
    private val completed = AtomicLong()
    private val notDispatched = AtomicLong()
    private val unknown = AtomicLong()
    private val cancelled = AtomicLong()

    fun start() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            registry.events.collect { e ->
                tools.onRegistryEvent(e)
                if (e is RegistryEvent.Revoke) forget(e.pluginId)
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(tools.catalog, registry.approvals.policy) { c, p -> c to p }.collect { (c, p) ->
                remember(c, p)
                snapshotFlow.update { Snapshot(it.version + 1, c, p) }
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                registry.rescanQuietly()
            } finally {
                firstScan.complete(Unit)
            }
        }
    }

    private val firstScan = CompletableDeferred<Unit>()

    /**
     * 插件管理的调用先等 `:ext` 启动后的第一次扫描（最多 [FIRST_SCAN_WAIT_MS]）：否则 `:ext` 刚被拉起时插件页会看到空列表。
     * 目录和调用不等（目录在扫描、连接完成后经 onCatalogChanged 推送）。
     */
    fun awaitFirstScan() {
        if (firstScan.isCompleted) return
        runBlocking { withTimeoutOrNull(FIRST_SCAN_WAIT_MS) { firstScan.await() } }
    }

    override fun close() {
        tools.close()
        scope.cancel()
    }

    // ------------------------------------------------------------------ 插件管理

    fun pluginsJson(): String = JSONArray(registry.registry.value.plugins.map { registry.pluginJson(it, serverViews(it.id)) }).toString()

    fun pluginJson(id: String?): String = registry.plugin(id).let { registry.pluginJson(it, serverViews(it.id)) }.toString()

    private fun serverViews(pluginId: String): Map<String, ExtRegistry.ServerView> {
        val catalog = tools.catalog.value
        return tools.serverStates.value.filterKeys { it.pluginId == pluginId }.entries.associate { (key, state) ->
            val count = catalog.tools.count { it.provider == pluginId && it.source?.server == key.server }
            key.server to ExtRegistry.ServerView(stateName(state), stateError(state), count)
        }
    }

    /**
     * 一个插件的全部工具（含被禁用的）。插件可用、已启用但还没有任何工具时，先等一次刷新（最多 [LIST_REFRESH_TIMEOUT_MS]）。
     */
    fun listTools(pluginId: String?): String {
        val p = registry.plugin(pluginId)
        if (p.status != PluginStatus.READY) throw ExtError.unavailable("plugin $pluginId is ${p.status.name.lowercase()}")
        val name = p.name
        if (name != null && registry.approvals.policy.value.resolvePluginEnabled(name) && knownFor(p.id).isEmpty()) {
            runBlocking { tools.refreshNow(LIST_REFRESH_TIMEOUT_MS) }
        }
        val policy = registry.approvals.policy.value
        remember(tools.catalog.value, policy)
        return JsonArray(knownFor(p.id).map { ExtWire.toolJson(it, policy) }).toString()
    }

    fun setToolEnabled(toolName: String?, enabled: Boolean): String {
        val t = findTool(toolName)
        val s = t.source ?: throw ExtError.notFound(toolName)
        // 启用写 null（清掉工具级的禁用，沿用服务器 / 插件级）；禁用写 false
        val policy = registry.updatePolicy { it.withEnabled(PolicyScope.Tool(s.plugin, s.server, s.tool), if (enabled) null else false) }
        return ExtWire.toolJson(t, policy).toString()
    }

    fun setToolApproval(toolName: String?, mode: String?): String {
        ExtError.checkMode(mode)
        val t = findTool(toolName)
        val s = t.source ?: throw ExtError.notFound(toolName)
        val approval = mode.toApprovalMode()
        if (approval == ApprovalMode.ALWAYS && !RiskPolicy.mayAlwaysAllow(t.risk)) throw ExtError.highRisk(t.name)
        val policy = registry.updatePolicy { it.withApproval(PolicyScope.Tool(s.plugin, s.server, s.tool), approval, t.risk) }
        return ExtWire.toolJson(t, policy).toString()
    }

    private fun findTool(name: String?): CatalogTool {
        if (name.isNullOrEmpty()) throw ExtError.notFound(name)
        tools.catalog.value[name]?.let { return it }
        return synchronized(known) { known.values.firstOrNull { it.name == name } } ?: throw ExtError.notFound(name)
    }

    private fun knownFor(pluginId: String): List<CatalogTool> = synchronized(known) { known.values.filter { it.provider == pluginId } }.sortedBy { it.name }

    /** 目录变了：记下新出现的工具；不在目录里、但按策略是启用的（服务器不再提供、插件不可用）就忘掉。 */
    private fun remember(catalog: ToolCatalog, policy: ApprovalPolicy) {
        val ready = registry.registry.value.plugins.filter { it.status == PluginStatus.READY }.map { it.id }.toSet()
        synchronized(known) {
            for (t in catalog.tools) t.source?.let { known[it] = t }
            val inCatalog = catalog.tools.mapNotNull { it.source }.toSet()
            known.entries.removeAll { (src, t) -> t.provider !in ready || (src !in inCatalog && policy.resolve(src).enabled) }
        }
    }

    private fun forget(pluginId: String) {
        synchronized(known) { known.entries.removeAll { it.value.provider == pluginId } }
    }

    // ------------------------------------------------------------------ 运行时

    /** IExtensionHost.refreshTools：[ExtensionToolHost.refreshNow]，上限 [MAX_REFRESH_TIMEOUT_MS]。 */
    fun refreshTools(timeoutMs: Long, force: Boolean): String {
        val r = runBlocking { tools.refreshNow(timeoutMs.coerceIn(0, MAX_REFRESH_TIMEOUT_MS), force) }
        fun keys(list: List<ServerKey>) = JSONArray(list.map { JSONObject().put("pluginId", it.pluginId).put("server", it.server) })
        return JSONObject().put("refreshed", keys(r.refreshed)).put("failed", keys(r.failed)).put("timedOut", r.timedOut).toString()
    }

    fun catalogJson(): String = snapshot.value.let { ExtWire.catalogJson(it.version, it.catalog, it.policy) }

    /**
     * 受理一次调用：工具不在目录里、callId 重复时返回 false（不会有回调）；受理后 [deliver] 恰好调用一次（在后台线程）。
     * 请求格式不对、工具在目录里但没有回调（[owner] 为 null）抛 IllegalArgumentException（agentos.ext.bad_request）。
     */
    fun callTool(callId: String?, requestJson: String?, owner: IBinder?, deliver: (String) -> Unit): Boolean {
        if (callId.isNullOrEmpty()) throw ExtError.badRequest("callId is empty")
        val inv = ExtWire.decodeRequest(requestJson)
        if (tools.catalog.value[inv.name] == null) {
            rejected.incrementAndGet()
            return false
        }
        // 受理之后要有地方交结果
        if (owner == null) throw ExtError.badRequest("callback is null")
        val delivered = AtomicBoolean(false)
        fun finish(r: ToolInvocationResult) {
            if (!delivered.compareAndSet(false, true)) return
            calls.remove(callId)
            when (r) {
                is ToolInvocationResult.Completed -> completed
                is ToolInvocationResult.NotDispatched -> notDispatched
                is ToolInvocationResult.Unknown -> unknown
            }.incrementAndGet()
            try {
                deliver(ExtWire.outcomeJson(r))
            } catch (e: Exception) {
                Log.w(TAG, "could not deliver the result of $callId: ${e.javaClass.simpleName}")
            }
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val r = try {
                tools.invoke(inv)
            } catch (e: CancellationException) {
                cancelled.incrementAndGet()
                cancelledOutcome()
            } catch (e: Exception) {
                Log.e(TAG, "tool invocation failed unexpectedly", e)
                ToolInvocationResult.Unknown(ErrorCode.TOOL_RESULT_UNKNOWN.info("The extension host failed while calling the tool (${e.javaClass.simpleName}); the result is unknown."))
            }
            finish(r)
        }
        // 还没开始就被取消（close 时）也要回一次
        job.invokeOnCompletion { cause -> if (cause != null) finish(cancelledOutcome()) }
        if (calls.putIfAbsent(callId, Call(job, owner)) != null) {
            delivered.set(true)
            job.cancel()
            rejected.incrementAndGet()
            return false
        }
        accepted.incrementAndGet()
        job.start()
        return true
    }

    fun cancelTool(callId: String?) {
        calls[callId ?: return]?.job?.cancel()
    }

    /** 调用方进程死亡：它的调用全部取消（结果已经没人要了；取消会转成 MCP 的取消通知）。 */
    fun cancelCallsOf(owner: IBinder) {
        calls.values.filter { it.owner == owner }.forEach { it.job.cancel() }
    }

    fun diagnosticsJson(subscribers: Int): String {
        val snap = snapshot.value
        return registry.diagnostics()
            .put("implementation", "c7b")
            .put("catalogVersion", snap.version)
            .put("tools", snap.catalog.tools.size)
            .put("subscribers", subscribers)
            .put("calls", JSONObject()
                .put("inFlight", calls.size).put("accepted", accepted.get()).put("rejected", rejected.get())
                .put("completed", completed.get()).put("notDispatched", notDispatched.get()).put("unknown", unknown.get())
                .put("cancelled", cancelled.get()))
            .put("servers", JSONArray(tools.serverStates.value.map { (k, s) -> stateJson(k, s) }))
            .toString()
    }

    companion object {
        private const val TAG = "ExtensionHost"
        const val LIST_REFRESH_TIMEOUT_MS = 10_000L
        const val MAX_REFRESH_TIMEOUT_MS = 30_000L
        const val FIRST_SCAN_WAIT_MS = 10_000L

        fun stateName(s: ServerState): String = when (s) {
            ServerState.Idle -> "idle"
            ServerState.Connected -> "connected"
            is ServerState.Unreachable -> "unreachable"
            is ServerState.Disabled -> "disabled"
        }

        fun stateError(s: ServerState): String? = when (s) {
            is ServerState.Unreachable -> s.reason
            is ServerState.Disabled -> s.reason.wire()
            else -> null
        }

        private fun DisabledReason.wire() = name.lowercase()

        /** onConnectionState / 诊断里的一项。 */
        fun stateJson(k: ServerKey, s: ServerState): JSONObject = JSONObject()
            .put("pluginId", k.pluginId).put("server", k.server).put("state", stateName(s)).put("error", stateError(s) ?: JSONObject.NULL)

        private fun cancelledOutcome() = ToolInvocationResult.Unknown(
            ErrorCode.TOOL_RESULT_UNKNOWN.info("The call was cancelled; it may already have reached the tool.")
                .copy(details = buildJsonObject { put("cancelled", true) }),
        )

        private fun appVersion(context: Context): String = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0"
    }
}
