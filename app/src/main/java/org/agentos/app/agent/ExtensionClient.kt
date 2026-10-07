package org.agentos.app.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.agentos.app.ext.ExtWire
import org.agentos.app.ext.ExtensionHostService
import org.agentos.internal.IExtensionCallback
import org.agentos.internal.IExtensionHost
import org.agentos.runtime.broker.ApprovalMode
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.broker.RiskPolicy
import org.agentos.runtime.consent.ApprovalWriteResult
import org.agentos.runtime.consent.ApprovalWriter
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.ports.ApprovalPolicyPort
import org.agentos.runtime.ports.RuntimeLog
import org.agentos.runtime.ports.SkillCatalog
import org.agentos.runtime.ports.SkillContent
import org.agentos.runtime.ports.SkillPort
import org.agentos.runtime.ports.ToolCatalog
import org.agentos.runtime.ports.ToolInvocation
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolPort
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.agentos.runtime.ports.info
import org.agentos.runtime.ports.warn
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * `:agent` 侧的 Extension Host 代理（docs/extensions.md 第 9 节）：`HostPort.tools` 与 `HostPort.approvals`。
 * 目录、连接、策略的逻辑都在 `:ext`（ExtensionToolHost + ApprovalStore），这里只做 IExtensionHost 的薄代理：
 *
 * - **绑定**：BIND_AUTO_CREATE，`:ext` 随 `:agent` 存活；`:ext` 被杀后系统按绑定关系重建，重连后重新订阅、重新取目录。
 * - **目录**（[catalog]）：收到 onCatalogChanged 后取 getCatalog；内容变化时本地版本号加一（`:ext` 重建后它的版本号从头开始，
 *   这里不跟着倒退）。`:ext` 重建期间保留最后一份目录：需要工具的步骤先等 `:ext` 回来（最多到这次调用的超时）。
 * - **策略镜像**（[approvals]）：getCatalog 里的 policy；**收到第一份之前 fail closed**（第三方插件一律当作禁用）。
 *   `:ext` 是唯一写入方，这里只读。
 * - **调用**（[invoke]）：callTool 受理后等 onToolResult；三种结局原样（ExtWire 解码）。`:ext` 进程在调用期间死亡 → Unknown
 *   （已受理的调用可能已经发出，F8 不重放）；没受理 → NotDispatched。协程被取消时发 cancelTool，再以 CancellationException 结束。
 * - **任务开始前**（[prepare]，A10 的 ToolPort.prepare）：IExtensionHost.refreshTools（不强制），严格在时限内返回。
 * - **Skills**（[skills]，`HostPort.skills`）：目录随 getCatalog 一起来；读取经 IExtensionHost.readSkill（A10 的 ExtensionSkillPort 在 `:ext`）。
 * - **“始终允许”写回**（[approvalWriter]，A11 的 ApprovalWriter）：IExtensionHost.setToolApprovalBySource，写完立即刷新镜像。
 */
class ExtensionClient(private val context: Context, private val log: RuntimeLog) : ToolPort, AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("ext-client"))
    private val hostFlow = MutableStateFlow<IExtensionHost?>(null)
    private val catalogFlow = MutableStateFlow(ToolCatalog.EMPTY)
    private val policyFlow = MutableStateFlow(FAIL_CLOSED)
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ToolInvocationResult>>()
    private val skillsFlow = MutableStateFlow(SkillCatalog.EMPTY)
    private val fetchLock = Any()

    override val catalog: StateFlow<ToolCatalog> = catalogFlow.asStateFlow()

    /** `HostPort.approvals`：`:ext` 的用户策略的只读镜像。 */
    val approvals: ApprovalPolicyPort = object : ApprovalPolicyPort {
        override val policy: StateFlow<ApprovalPolicy> = policyFlow.asStateFlow()
        override fun toString() = "ExtensionClient.approvals"
    }

    /** `HostPort.skills`：Skill 目录随 getCatalog 来，读取经 IExtensionHost.readSkill。 */
    val skills: SkillPort = object : SkillPort {
        override val catalog: StateFlow<SkillCatalog> = skillsFlow.asStateFlow()

        override suspend fun read(skillId: String, path: String?): SkillContent {
            val h = hostFlow.value ?: withTimeoutOrNull(HOST_WAIT_MS) { hostFlow.filterNotNull().first() }
                ?: throw NoSuchElementException("skills are not available right now (the extension host is not running)")
            val text = try {
                withContext(Dispatchers.IO) { h.readSkill(skillId, path ?: "") }
            } catch (e: IllegalArgumentException) {
                val msg = e.message.orEmpty().substringAfter(": ", e.message.orEmpty())
                if (e.message.orEmpty().startsWith("agentos.ext.bad_path")) throw IllegalArgumentException(msg)
                throw NoSuchElementException(msg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw NoSuchElementException("skills are not available right now (${e.javaClass.simpleName})")
            }
            val o = JSONObject(text)
            return SkillContent(o.getString("text"), o.optBoolean("truncated"))
        }

        override fun toString() = "ExtensionClient.skills"
    }

    /** A11 的 ApprovalWriter：确认框里的“始终允许”写到 `:ext` 的 ApprovalStore（唯一写入方）。 */
    val approvalWriter: ApprovalWriter = object : ApprovalWriter {
        override val available: Boolean
            get() = hostFlow.value != null && policyReceived && policyFlow.value.unlistedPluginsEnabled

        override suspend fun setAlways(source: ToolSource, risk: ToolRisk): ApprovalWriteResult {
            if (!RiskPolicy.mayAlwaysAllow(risk)) return ApprovalWriteResult.Failed("a $risk tool cannot be set to always allow")
            val h = hostFlow.value ?: return ApprovalWriteResult.Failed("the extension host is not connected")
            return try {
                withContext(Dispatchers.IO) {
                    h.setToolApprovalBySource(source.plugin, source.server, source.tool, ApprovalMode.ALWAYS.wire)
                    fetchCatalog() // 镜像立即更新：同一任务的下一次调用就按“始终允许”
                }
                ApprovalWriteResult.Saved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ApprovalWriteResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }

        override fun toString() = "ExtensionClient.approvalWriter"
    }

    @Volatile private var bound = false
    @Volatile private var closed = false
    @Volatile private var policyReceived = false
    @Volatile private var lastError: String? = null
    @Volatile private var lastCatalogAt = 0L
    @Volatile private var remoteVersion = -1L
    private val connects = AtomicLong()
    private val disconnects = AtomicLong()
    private val fetches = AtomicLong()
    private val calls = AtomicLong()
    private val callsLostToDeath = AtomicLong()
    private val prepares = AtomicLong()

    private val callback = object : IExtensionCallback.Stub() {
        override fun onCatalogChanged(version: Long) {
            refreshRequests.trySend(Unit)
        }

        override fun onToolResult(callId: String?, outcomeJson: String?) {
            pending.remove(callId ?: return)?.complete(ExtWire.decodeOutcome(outcomeJson))
        }

        override fun onConnectionState(stateJson: String?) {
            // 只进诊断页（D）；这里不需要
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val h = IExtensionHost.Stub.asInterface(service ?: return)
            connects.incrementAndGet()
            scope.launch {
                try {
                    h.subscribe(callback)
                    hostFlow.value = h
                    refreshRequests.trySend(Unit)
                } catch (e: Exception) {
                    lastError = "subscribe: ${e.javaClass.simpleName}"
                    log.warn(TAG, "could not subscribe to the extension host: ${e.javaClass.simpleName}")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // :ext 进程死了；BIND_AUTO_CREATE 的绑定还在，系统重建后会再回调 onServiceConnected
            lost("the extension host process died")
        }

        override fun onBindingDied(name: ComponentName?) {
            // 绑定作废（App 升级等）：要重新 bind
            lost("the binding to the extension host died")
            if (!closed) {
                runCatching { context.unbindService(this) }
                bound = false
                bind()
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            lastError = "null binding"
            log.warn(TAG, "the extension host returned a null binding")
        }
    }

    fun start() {
        bind()
        scope.launch(CoroutineName("ext-catalog")) {
            for (r in refreshRequests) fetchCatalog()
        }
    }

    private fun bind() {
        if (bound || closed) return
        bound = try {
            context.bindService(Intent(context, ExtensionHostService::class.java), connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            lastError = "bind: ${e.javaClass.simpleName}"
            false
        }
        if (!bound) log.warn(TAG, "could not bind the extension host")
    }

    private fun lost(why: String) {
        if (hostFlow.value != null) disconnects.incrementAndGet()
        hostFlow.value = null
        // 已受理、还没回结果的调用：可能已经发给插件了 → 结果未知
        for (id in pending.keys.toList()) {
            pending.remove(id)?.let { d ->
                if (d.complete(hostDied(why))) callsLostToDeath.incrementAndGet()
            }
        }
    }

    private fun hostDied(why: String) = ToolInvocationResult.Unknown(
        ErrorCode.TOOL_RESULT_UNKNOWN.info("$why after the call was accepted; the result is unknown."),
    )

    private fun fetchCatalog() = synchronized(fetchLock) { fetchCatalogLocked() }

    private fun fetchCatalogLocked() {
        val h = hostFlow.value ?: return
        val text = try {
            h.catalog
        } catch (e: Exception) {
            lastError = "getCatalog: ${e.javaClass.simpleName}"
            return // :ext 死了：重连后会再取
        }
        val decoded = try {
            ExtWire.decodeCatalog(text)
        } catch (e: Exception) {
            lastError = "catalog unreadable: ${e.javaClass.simpleName}"
            log.warn(TAG, "the extension host returned an unreadable catalog: ${e.javaClass.simpleName}")
            return
        }
        fetches.incrementAndGet()
        remoteVersion = decoded.version
        lastCatalogAt = SystemClock.elapsedRealtime()
        // 先更新策略，再更新目录：新出现的工具在策略到位之后才进目录
        policyFlow.value = decoded.policy
        policyReceived = true
        val current = catalogFlow.value
        if (current.tools != decoded.tools) {
            catalogFlow.value = ToolCatalog(current.version + 1, decoded.tools)
            log.info(TAG, "tool catalog v${current.version + 1}: ${decoded.tools.size} tool(s)")
        }
        val sk = skillsFlow.value
        if (sk.skills != decoded.skills) skillsFlow.value = SkillCatalog(sk.version + 1, decoded.skills)
    }

    /**
     * ToolPort.prepare：等 `:ext` 连上（启动中）、再等还没有缓存的服务器取完工具列表，然后取一次目录。必须在 [timeoutMillis] 内返回：
     * Binder 调用不能被取消，所以放在后台，这里只按时限等它。
     */
    override suspend fun prepare(timeoutMillis: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        fun left() = deadline - SystemClock.elapsedRealtime()
        val h = hostFlow.value ?: withTimeoutOrNull(left() / 2) { hostFlow.filterNotNull().first() } ?: return
        val budget = left() - PREPARE_MARGIN_MS
        if (budget <= 0) return
        val job = scope.async {
            runCatching { h.refreshTools(budget, false) }
            fetchCatalog()
        }
        withTimeoutOrNull(left().coerceAtLeast(0)) { job.await() }
        prepares.incrementAndGet()
    }

    override suspend fun invoke(invocation: ToolInvocation): ToolInvocationResult {
        calls.incrementAndGet()
        // :ext 不在（启动中、被杀后重建中）：等它回来，最多到这次调用的超时（没有超时时用 HOST_WAIT_MS）
        val wait = if (invocation.timeoutMillis > 0) minOf(invocation.timeoutMillis, HOST_WAIT_MS) else HOST_WAIT_MS
        val h = hostFlow.value ?: withTimeoutOrNull(wait) { hostFlow.filterNotNull().first() }
            ?: return ToolInvocationResult.NotDispatched(ErrorCode.TOOL_UNAVAILABLE.info("The extension host is not available."))
        val callId = UUID.randomUUID().toString()
        val result = CompletableDeferred<ToolInvocationResult>()
        pending[callId] = result
        val accepted = try {
            withContext(Dispatchers.IO) { h.callTool(callId, ExtWire.requestJson(invocation), callback) }
        } catch (e: CancellationException) {
            pending.remove(callId)
            withContext(NonCancellable + Dispatchers.IO) { runCatching { h.cancelTool(callId) } }
            throw e
        } catch (e: IllegalArgumentException) {
            // :ext 在受理之前拒绝了（请求格式不对）：确定没发出
            pending.remove(callId)
            return ToolInvocationResult.NotDispatched(ErrorCode.TOOL_UNAVAILABLE.info("The extension host rejected the call: ${e.message}"))
        } catch (e: IllegalStateException) {
            pending.remove(callId)
            return ToolInvocationResult.NotDispatched(ErrorCode.TOOL_UNAVAILABLE.info("The extension host rejected the call: ${e.message}"))
        } catch (e: SecurityException) {
            pending.remove(callId)
            return ToolInvocationResult.NotDispatched(ErrorCode.TOOL_UNAVAILABLE.info("The extension host rejected the call."))
        } catch (e: DeadObjectException) {
            // 拿不准 :ext 是在受理之前还是之后死的：按结果未知（F8：宁可不重放）
            pending.remove(callId)
            return hostDied("the extension host process died")
        } catch (e: Exception) {
            pending.remove(callId)
            return hostDied("calling the extension host failed (${e.javaClass.simpleName})")
        }
        if (!accepted) {
            pending.remove(callId)
            return ToolInvocationResult.NotDispatched(ErrorCode.TOOL_NOT_IN_CATALOG.info("Tool ${invocation.name} is not available."))
        }
        return try {
            coroutineScope {
                // callTool 返回之前 :ext 就死了的话 lost() 已经清过 pending：这里补上
                val watcher = launch { hostFlow.first { it !== h }; if (result.complete(hostDied("the extension host process died"))) callsLostToDeath.incrementAndGet() }
                val safety = if (invocation.timeoutMillis > 0) invocation.timeoutMillis + RESULT_GRACE_MS else 0L
                val r = if (safety > 0) {
                    withTimeoutOrNull(safety) { result.await() } ?: ToolInvocationResult.Unknown(
                        ErrorCode.TOOL_TIMEOUT.info("The extension host did not return a result for ${invocation.name} in time; the result is unknown."),
                    )
                } else {
                    result.await()
                }
                watcher.cancel()
                r
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { h.cancelTool(callId) } }
            throw e
        } finally {
            pending.remove(callId)
        }
    }

    /** IExtensionHost.refreshTools：等还没有缓存的服务器取一次工具列表（任务开始前；现在只给测试和诊断用）。 */
    suspend fun refreshNow(timeoutMillis: Long, force: Boolean = false): JSONObject? {
        val h = hostFlow.value ?: withTimeoutOrNull(timeoutMillis) { hostFlow.filterNotNull().first() } ?: return null
        return try {
            JSONObject(withContext(Dispatchers.IO) { h.refreshTools(timeoutMillis, force) }).also { fetchCatalog() }
        } catch (e: Exception) {
            lastError = "refreshTools: ${e.javaClass.simpleName}"
            null
        }
    }

    fun stats(): JSONObject = JSONObject()
        .put("bound", bound)
        .put("connected", hostFlow.value != null)
        .put("connects", connects.get())
        .put("disconnects", disconnects.get())
        .put("catalogFetches", fetches.get())
        .put("catalogVersion", catalogFlow.value.version)
        .put("remoteVersion", remoteVersion)
        .put("tools", catalogFlow.value.tools.size)
        .put("skills", skillsFlow.value.skills.size)
        .put("prepares", prepares.get())
        .put("policyReceived", policyReceived)
        .put("policyFailClosed", !policyFlow.value.unlistedPluginsEnabled)
        .put("calls", calls.get())
        .put("pending", pending.size)
        .put("callsLostToDeath", callsLostToDeath.get())
        .put("lastError", lastError ?: JSONObject.NULL)

    override fun close() {
        closed = true
        hostFlow.value?.let { h -> runCatching { h.unsubscribe(callback) } }
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        lost("the extension host was closed")
        scope.cancel()
    }

    override fun toString() = "ExtensionClient"

    companion object {
        private const val TAG = "ExtClient"

        /** `:ext` 不在时，一次调用最多等它这么久。 */
        const val HOST_WAIT_MS = 15_000L

        /** 调用自带超时之外再等这么久（`:ext` 自己先按超时结束；这只是防 `:ext` 卡住的兜底）。 */
        const val RESULT_GRACE_MS = 30_000L

        /** prepare 给 `:ext` 的时限比自己的少这么多：留出取目录和 Binder 往返的时间。 */
        const val PREPARE_MARGIN_MS = 300L

        /** 收到第一份策略之前：第三方插件一律当作禁用（与 ApprovalStore 的 fail closed 相同）。 */
        val FAIL_CLOSED: ApprovalPolicy = ApprovalPolicy.DEFAULT.copy(unlistedPluginsEnabled = false)
    }
}
