@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.acp

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.protocol.AcpExpectedError
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import org.agentos.channel.IAcpService

/**
 * 给后装 App 用的 AgentOS 入口（docs/third-party-acp.md 4.7）。
 *
 * ```
 * if (!AgentOs.isInstalled(context)) { ... }
 * val connection = AgentOs.connect(context, onWaiting = { showWaiting() })      // 可能抛 AgentOsException
 * val session = connection.newSession(listOf(ToolRef("alarm", "alarm_create"), ToolRef("calendar", "event_create")))   // 或 newSession()：不缩小范围
 * session.prompt(text).collect { event -> ... }
 * connection.close()
 * ```
 *
 * 底层是 acp 0.30.1 的客户端和 [BinderAcpTransport]，对上层屏蔽 ACP 的类型。不需要 key，不联网，不读 AgentOS 的任何私有数据。
 * 本库的 Manifest 带 `<queries>`（AgentOS 的包名和 ACP 服务的 action），合并后调用方 App 看得见 AgentOS。
 */
object AgentOs {
    /** 这台手机上有没有 AgentOS，且它提供了 ACP 服务（第三方接入的入口）。 */
    fun isInstalled(context: Context): Boolean = try {
        val intent = Intent(AcpServiceContract.ACTION).setPackage(AcpServiceContract.AGENTOS_PACKAGE)
        context.applicationContext.packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0)).isNotEmpty()
    } catch (e: Exception) {
        false
    }

    /**
     * 连接 AgentOS。第一次使用时 AgentOS 会请用户允许：本函数每秒重试 `open`，最多 [AUTHORIZATION_TIMEOUT_MILLIS]，
     * 等待期间每秒回调 [onWaiting]（在调用方的协程里）。成功后返回已初始化的连接。
     *
     * @throws AgentOsException [AgentOsError.NOT_INSTALLED]、[AgentOsError.AUTHORIZATION_PENDING_TIMEOUT]、[AgentOsError.DENIED]、
     *   [AgentOsError.DISCONNECTED]、[AgentOsError.FAILED]。协程被取消时按取消处理（放弃等待，释放绑定）。
     */
    suspend fun connect(context: Context, onWaiting: (Waiting) -> Unit = {}): AgentOsConnection {
        AcpAndroid.ensureInitialized()
        val app = context.applicationContext
        if (!isInstalled(app)) throw AgentOsException(AgentOsError.NOT_INSTALLED, "AgentOS is not installed")
        val serviceUid = try {
            app.packageManager.getPackageUid(AcpServiceContract.AGENTOS_PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } catch (e: PackageManager.NameNotFoundException) {
            throw AgentOsException(AgentOsError.NOT_INSTALLED, "AgentOS is not installed")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("agentos-sdk"))
        val binding = Binding(app)
        try {
            val binder = binding.bind(CONNECT_TIMEOUT_MILLIS)
                ?: throw AgentOsException(if (binding.bound) AgentOsError.FAILED else AgentOsError.NOT_INSTALLED, "could not bind the AgentOS ACP service")
            val service = IAcpService.Stub.asInterface(binder)
            val transport = try {
                AgentOsMapping.retryWhilePending(AUTHORIZATION_TIMEOUT_MILLIS, RETRY_INTERVAL_MILLIS, SystemClock::elapsedRealtime, onWaiting) {
                    BinderAcpTransport.connect(service, serviceUid, scope, name = "agentos-sdk")
                }
            } catch (e: SecurityException) {
                throw AgentOsMapping.fromOpen(e)
            } catch (e: AgentOsException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw AgentOsException(AgentOsError.DISCONNECTED, "open failed: ${e.javaClass.simpleName}", e)
            }
            val protocol = Protocol(scope, transport, ProtocolOptions(protocolDebugName = "agentos-sdk"))
            val client = Client(protocol)
            BinderAcpTransport.bindTo(protocol, transport)
            protocol.start()
            val agentInfo = try {
                withTimeout(CONNECT_TIMEOUT_MILLIS) {
                    // sessionSetup：让 AgentOS 在每次会话建立的最后发收尾标记，SDK 靠它确定历史和服务器状态收齐了（旧版本的 AgentOS 不认，忽略）
                    client.initialize(ClientInfo(implementation = Implementation(CLIENT_NAME, CLIENT_VERSION)), AgentOsMapping.initializeMeta())
                }
            } catch (e: Exception) {
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                runCatching { transport.close() }
                throw AgentOsException(AgentOsError.DISCONNECTED, "initialize failed: ${e.javaClass.simpleName}", e)
            }
            return AgentOsConnection(binding, transport, protocol, client, scope, AgentOsMapping.supportsSetupMarker(agentInfo._meta))
        } catch (t: Throwable) {
            binding.unbind()
            scope.cancel()
            throw t
        }
    }

    /**
     * 把 AgentOS 待决的授权提示 / 工具确认带到前台。只有调用方 App 在前台时系统才允许这样启动别的 App 的界面；
     * 没有待决时 AgentOS 的入口界面直接结束。AgentOS 没装或系统拒绝时什么也不做。
     */
    fun bringApprovalToFront(context: Context) {
        val intent = Intent(AcpServiceContract.ACTION_SHOW_APPROVAL)
            .setPackage(AcpServiceContract.AGENTOS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // AgentOS 没装，或这个版本没有这个入口
        } catch (e: SecurityException) {
            // 系统不允许从后台启动
        }
    }

    /** [connect] 等用户决定的上限（毫秒）。 */
    const val AUTHORIZATION_TIMEOUT_MILLIS = 90_000L

    private const val RETRY_INTERVAL_MILLIS = 1_000L
    private const val CONNECT_TIMEOUT_MILLIS = 15_000L
    private const val CLIENT_NAME = "agentos-sdk"
    private const val CLIENT_VERSION = "0.1"

    /** bindService 到 AgentOS 的 ACP 服务，挂起到连上。 */
    internal class Binding(private val context: Context) {
        @Volatile var bound = false
            private set
        private val connected = CompletableDeferred<IBinder?>()
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                connected.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit // 通道自己感知对端死亡

            override fun onNullBinding(name: ComponentName?) {
                connected.complete(null)
            }

            override fun onBindingDied(name: ComponentName?) {
                connected.complete(null)
            }
        }

        suspend fun bind(timeoutMillis: Long): IBinder? {
            val intent = Intent(AcpServiceContract.ACTION).setPackage(AcpServiceContract.AGENTOS_PACKAGE)
            bound = try {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                false
            }
            if (!bound) return null
            return withTimeoutOrNull(timeoutMillis) { connected.await() }
        }

        fun unbind() {
            if (bound) {
                bound = false
                runCatching { context.unbindService(connection) }
            }
        }
    }
}

/** 到 AgentOS 的一条连接。用完要 [close]（解除绑定）。 */
class AgentOsConnection internal constructor(
    private val binding: AgentOs.Binding,
    private val transport: BinderAcpTransport,
    private val protocol: Protocol,
    private val client: Client,
    private val scope: CoroutineScope,
    /** AgentOS 会在每次会话建立的最后发收尾标记（扩展 sessionSetup）。 */
    private val setupMarker: Boolean = false,
) : AutoCloseable {
    @Volatile private var closed = false

    /**
     * 这条连接上已经建立的会话，按会话 ID。官方 Kotlin 客户端对**同一条连接上的同一个会话 ID** 只认第一次注册的回调和会话对象：
     * 再 load 一次，重放会发给第一次的回调，新返回的会话对象也收不到流式输出。所以同一个会话 ID 在一条连接上只保留一份，
     * 再次 load / resume 只是重新向 AgentOS 要一遍历史和状态，返回的 [AgentOsSession] 用的还是那一份。
     */
    private val held = ConcurrentHashMap<String, Held>()

    private class Held(val session: ClientSession, val collector: SessionCollector)

    internal fun forget(sessionId: String) {
        held.remove(sessionId)
    }

    /** 连接还活着。AgentOS 进程被杀、授权被撤销后为 false。 */
    val isConnected: Boolean get() = !closed && transport.channel.isOpen

    /** 这台手机上的 AgentOS 支持哪些会话能力。旧版本只有 [newSession]。 */
    val capabilities: AgentOsCapabilities
        get() {
            val caps = client.agentInfo.capabilities
            val sc = caps.sessionCapabilities
            return AgentOsCapabilities(
                loadSession = caps.loadSession,
                resumeSession = sc?.resume != null,
                forkSession = sc?.fork != null,
                listSessions = sc?.list != null,
                deleteSession = sc?.delete != null,
                closeSession = sc?.close != null,
                mcpHttpServers = caps.mcpCapabilities.http,
            )
        }

    /**
     * 新建一个会话。[toolScope] 是调用方自己的选择，AgentOS 不强制：
     * - `null`（默认）：不给范围——会话能用 AgentOS 里全部已启用插件的全部工具（用户已经在 AgentOS 里启用了哪些插件，由用户决定）；
     * - 非空列表：这个会话**最多**能用这些工具；只能缩小、不能放大（实际可用 = toolScope 与用户当前启用的工具的交集），
     *   写了不存在的项会被忽略，也不报错。把不可信的文字交给 Agent 时（例如用户的备忘），建议只给任务需要的那几项；
     * - 空列表：要求零个工具，只能聊天。
     *
     * 最多 32 项，每个名字最多 128 字符（否则抛 [IllegalArgumentException]）。工具调用要不要让用户确认，由 AgentOS 的确认规则决定
     * （和 AgentOS 自己的界面一样：写默认每次确认，用户设了“始终允许”就不再问，高风险每次确认）；SDK 不替用户批准任何东西。
     *
     * [mcpServers] 是调用方自己的 MCP 服务器（Streamable HTTP），只对这个会话可见，见 [McpHttpServer]。连不上的服务器不会让会话失败，
     * 看 [AgentOsSession.mcpServers]。
     *
     * @throws AgentOsException [AgentOsError.DISCONNECTED]、[AgentOsError.INVALID_REQUEST]（MCP 服务器的地址或头不合规）、
     *   [AgentOsError.UNSUPPORTED]、[AgentOsError.FAILED] 等
     */
    suspend fun newSession(toolScope: List<ToolRef>? = null, mcpServers: List<McpHttpServer> = emptyList()): AgentOsSession {
        val meta = AgentOsMapping.sessionMeta(toolScope)
        val servers = AgentOsMapping.mcpServers(mcpServers)
        return create(toolScope?.distinct(), servers.isNotEmpty()) { ops ->
            client.newSession(SessionCreationParameters(cwd = "/", mcpServers = servers, _meta = meta)) { _, _ -> ops }
        }
    }

    /**
     * 回到一个以前的会话，并拿到它的历史（[AgentOsSession.history]：用户说过的话、Agent 的回答和思考、工具调用，按顺序；
     * 会话很长时只有最近的若干轮）。之后可以继续 [AgentOsSession.prompt]。
     *
     * - [sessionId] 是 [AgentOsSession.sessionId]，自己存的。只能是**这个 App 自己**创建的会话；别人的、已删除的、不存在的一律 [AgentOsError.SESSION_NOT_FOUND]，
     *   看不出是哪一种；
     * - 会话的工具范围在创建时定下，**这里改不了**；
     * - [mcpServers] 是这次要用的那批，**替换**会话原来挂的（它们只在 AgentOS 的内存里，AgentOS 重启后要重新带上来）；不传 = 这个会话现在没有自带的服务器；
     * - 如果会话里还有一轮没结束（比如上次连接断了、任务在后台跑完了），[AgentOsSession.activeTaskId] 不为 null；它已经提交的部分在 history 里。
     *   这一轮结束前再发 prompt 会排在它后面；想要重来就 [AgentOsSession.cancel]。
     *
     * @throws AgentOsException [AgentOsError.SESSION_NOT_FOUND]、[AgentOsError.UNSUPPORTED]（旧版本的 AgentOS，先看 [capabilities]）等
     */
    suspend fun loadSession(sessionId: String, mcpServers: List<McpHttpServer> = emptyList()): AgentOsSession {
        val servers = AgentOsMapping.mcpServers(mcpServers)
        requireCapability(capabilities.loadSession)
        return reopen(sessionId, replay = true, withServers = servers.isNotEmpty()) { ops ->
            client.loadSession(SessionId(sessionId), SessionCreationParameters(cwd = "/", mcpServers = servers)) { _, _ -> ops }
        }
    }

    /** 同 [loadSession]，但不重放历史（[AgentOsSession.history] 为空）：只想继续对话、不需要显示以前的内容时用，省掉传输。 */
    suspend fun resumeSession(sessionId: String, mcpServers: List<McpHttpServer> = emptyList()): AgentOsSession {
        val servers = AgentOsMapping.mcpServers(mcpServers)
        requireCapability(capabilities.resumeSession)
        return reopen(sessionId, replay = false, withServers = servers.isNotEmpty()) { ops ->
            client.resumeSession(SessionId(sessionId), SessionCreationParameters(cwd = "/", mcpServers = servers)) { _, _ -> ops }
        }
    }

    /**
     * 从一个已有的会话分叉出新会话：带着它到目前为止**已经结束的**对话，之后各走各的（进行中的一轮不带过去）。
     * [toolScope] 只能在原会话的范围之上**再缩小**（取交集），不传就原样继承；模型和模式继承。
     * 新会话不继承原会话的 MCP 服务器（它们的地址和头是原会话的）；要用就在 [mcpServers] 里重新给。
     */
    suspend fun forkSession(sessionId: String, toolScope: List<ToolRef>? = null, mcpServers: List<McpHttpServer> = emptyList()): AgentOsSession {
        val meta = AgentOsMapping.sessionMeta(toolScope)
        val servers = AgentOsMapping.mcpServers(mcpServers)
        requireCapability(capabilities.forkSession)
        return create(toolScope?.distinct(), servers.isNotEmpty()) { ops ->
            client.forkSession(SessionId(sessionId), SessionCreationParameters(cwd = "/", mcpServers = servers, _meta = meta)) { _, _ -> ops }
        }
    }

    /** 这个 App 自己的会话，最近活动的在前（最多 200 个）。别的 App 的看不到；AgentOS 自己的界面看得到全部，这里不会。 */
    suspend fun listSessions(): List<SessionSummary> {
        requireCapability(capabilities.listSessions)
        return rpc { client.listSessions(null, null, null).toList() }
            .map { SessionSummary(it.sessionId.value, it.title, it.updatedAt) }
    }

    /**
     * 删除一个会话和它的一切（历史、上下文、挂着的 MCP 服务器）。正在跑的一轮先被取消。删了就找不回来。
     * @throws AgentOsException [AgentOsError.SESSION_NOT_FOUND]（也包括别人的会话）、[AgentOsError.BUSY]（取消还没完成，稍后再试）
     */
    suspend fun deleteSession(sessionId: String) {
        requireCapability(capabilities.deleteSession)
        rpc { client.deleteSession(SessionId(sessionId)) }
        forget(sessionId)
    }

    /** 断开并解除绑定；进行中的 prompt 以 [AgentOsError.DISCONNECTED] 结束。可以重复调用。 */
    override fun close() {
        if (closed) return
        closed = true
        runCatching { transport.close() }
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { protocol.close() }
            binding.unbind()
            scope.cancel(CancellationException("closed by the app"))
        }
    }

    private fun requireCapability(supported: Boolean) {
        if (!supported) throw AgentOsException(AgentOsError.UNSUPPORTED, "this version of AgentOS does not support this call")
    }

    /** 新建（或分叉）一个会话：创建收集器，等响应和 AgentOS 的收尾标记，把历史、MCP 服务器状态、进行中的任务带到会话对象上。 */
    private suspend fun create(scopeForMapping: List<ToolRef>?, withServers: Boolean, block: suspend (SessionCollector) -> ClientSession): AgentOsSession {
        val collector = SessionCollector(scopeForMapping)
        collector.begin(replay = false, awaitMarker = setupMarker)
        val session = try {
            rpc { withTimeout(timeoutFor(withServers)) { block(collector) } }
        } catch (e: Throwable) {
            collector.abort()
            throw e
        }
        held[session.sessionId.value] = Held(session, collector)
        return AgentOsSession(this, session, session, scopeForMapping, collector.finish(SETUP_WAIT_MILLIS))
    }

    /** 回到一个已有的会话（load / resume）。同一条连接上的同一个会话 ID 复用第一次的会话对象和收集器，见 [held]。 */
    private suspend fun reopen(
        sessionId: String,
        replay: Boolean,
        withServers: Boolean,
        block: suspend (SessionCollector) -> ClientSession,
    ): AgentOsSession {
        val existing = held[sessionId]
        val collector = existing?.collector ?: SessionCollector(null)
        // 同一个会话同时只做一次建立：两次建立共用一个收集器，交错会把对方的历史混进来
        return collector.setupLock.withLock {
            collector.begin(replay, awaitMarker = setupMarker)
            val fresh = try {
                rpc { withTimeout(timeoutFor(withServers)) { block(collector) } }
            } catch (e: Throwable) {
                collector.abort()
                throw e
            }
            val session = existing?.session ?: fresh.also { held[sessionId] = Held(fresh, collector) }
            AgentOsSession(this, session, fresh, null, collector.finish(SETUP_WAIT_MILLIS))
        }
    }

    /** 自带 MCP 服务器时，AgentOS 要并发连它们（最多 15 秒）再回答，所以等得久一点。 */
    private fun timeoutFor(withServers: Boolean) = if (withServers) SESSION_TIMEOUT_MILLIS + MCP_ATTACH_MARGIN_MILLIS else SESSION_TIMEOUT_MILLIS

    /** 一次 RPC 的统一错误处理。 */
    private suspend fun <T> rpc(block: suspend () -> T): T = try {
        block()
    } catch (e: JsonRpcException) {
        throw AgentOsMapping.fromRpc(e)
    } catch (e: AcpExpectedError) {
        throw AgentOsMapping.fromExpected(e)
    } catch (e: AgentOsException) {
        throw e
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) throw e
        throw AgentOsMapping.closed(e)
    } catch (e: Exception) {
        throw AgentOsException(if (isConnected) AgentOsError.FAILED else AgentOsError.DISCONNECTED, "request failed: ${e.javaClass.simpleName}", e)
    }


    private companion object {
        const val SESSION_TIMEOUT_MILLIS = 15_000L

        /** 自带 MCP 服务器时再多等的时间：AgentOS 并发连它们，最多 15 秒。 */
        const val MCP_ATTACH_MARGIN_MILLIS = 20_000L

        /** 响应到了之后，最多再等收尾标记和它说的那么多条通知多久。 */
        const val SETUP_WAIT_MILLIS = 5_000L
    }
}

/** 一个会话。同一个 App 同时只能有一个进行中的 prompt。 */
class AgentOsSession internal constructor(
    private val connection: AgentOsConnection,
    /** 收发用的会话对象（同一条连接上同一个会话 ID 只有一份，见 [AgentOsConnection]）。 */
    private val session: ClientSession,
    /** 最新一次建立会话的响应带来的状态（模式、可选的模型）；第一次建立时就是 [session]。再次 load 时 [session] 里缓存的是第一次的旧值。 */
    state: ClientSession,
    private val toolScope: List<ToolRef>?,
    collected: Collected,
) {
    @Volatile private var modeNow: SessionMode =
        if (state.modesSupported) SessionMode.of(state.currentMode.value.value) ?: SessionMode.DEFAULT else SessionMode.DEFAULT
    private val modelsNow: List<ModelOption> = if (state.modelsSupported) state.availableModels.map { ModelOption(it.modelId.value, it.name) } else emptyList()
    @Volatile private var modelNow: String? = if (modelsNow.isEmpty()) null else state.currentModel.value.value

    /**
     * 会话 ID：存下来，以后用 [AgentOsConnection.loadSession] 回到这个会话。形如 `ses_…`；只有创建它的 App 能再用，对别人没有意义，
     * 也不是密钥（权限由 AgentOS 按调用方的 UID 判断，知道 ID 并不能拿到别人的会话）。
     */
    val sessionId: String = session.sessionId.value

    /** [AgentOsConnection.loadSession] 重放回来的历史，按顺序；新建、分叉、[AgentOsConnection.resumeSession] 的会话是空的。 */
    val history: List<AgentOsEvent> = collected.history

    /**
     * 挂上去的 MCP 服务器各自的状态（每个 [McpHttpServer] 一项）；`connected = false` 的服务器，它的工具这次用不了
     * （AgentOS 每个任务开始前会再试着连一次）。没挂过为空。
     */
    val mcpServers: List<McpServerStatus> = collected.servers

    /** 建立会话时这个会话里还没结束的任务（`loadSession` / `resumeSession`）；没有为 null。 */
    val activeTaskId: String? = collected.activeTask

    /** 当前模式。 */
    val mode: SessionMode get() = modeNow

    /**
     * 给会话换模式，下一轮起生效。模式**只能在会话创建时的工具范围之上再收一层**，不会放宽：回到 [SessionMode.DEFAULT] 也只是回到那个范围。
     * @throws AgentOsException [AgentOsError.INVALID_REQUEST]
     */
    suspend fun setMode(mode: SessionMode) {
        if (!session.modesSupported) throw AgentOsException(AgentOsError.UNSUPPORTED, "this version of AgentOS has no session modes")
        rpc { session.setMode(SessionModeId(mode.wire)) }
        modeNow = mode
    }

    /** 可以给这个会话选的模型：用户在 AgentOS 里配置的那个 key 下可用的。用户用的是自定义端点，或没有可选的，为空。 */
    val availableModels: List<ModelOption> get() = modelsNow

    /** 当前用的模型 [ModelOption.id]；没有可选的模型时为 null。 */
    val model: String? get() = modelNow

    /**
     * 给会话换模型，下一轮起生效。只能选 [availableModels] 里的：不能指定别的端点，也不能带自己的 key。
     * @throws AgentOsException [AgentOsError.INVALID_REQUEST]
     */
    suspend fun setModel(id: String) {
        if (modelsNow.isEmpty()) throw AgentOsException(AgentOsError.UNSUPPORTED, "there are no models to choose from in this session")
        rpc { session.setModel(ModelId(id)) }
        modelNow = id
    }

    /**
     * 发一轮 prompt，返回这一轮的事件流：[AgentOsEvent.Text]、[AgentOsEvent.ToolCall]（同一个 id 多次）、最后一个 [AgentOsEvent.Done]。
     * 流是冷的：开始收集才发送；取消收集等于 [cancel]。失败时流以 [AgentOsException] 结束：[AgentOsError.NO_MODEL]（AgentOS 没配置模型）、
     * [AgentOsError.BUSY]、[AgentOsError.RATE_LIMITED]、[AgentOsError.TOO_LARGE]（文字超过 16,000 字符）、[AgentOsError.DISCONNECTED]、[AgentOsError.FAILED]。
     *
     * [includeThoughts] 为 true 时，模型的思考过程也以 [AgentOsEvent.Thought] 发出（默认不发）。
     *
     * 工具调用是否让用户在 AgentOS 里确认，由 AgentOS 的确认规则决定（见 [AgentOsConnection.newSession]）；要确认的，用户没有回答会超时按拒绝处理
     * （[ToolStatus.DENIED]）。
     */
    fun prompt(text: String, includeThoughts: Boolean = false): Flow<AgentOsEvent> = flow {
        if (text.length > AgentOsMapping.MAX_PROMPT_CHARS) throw AgentOsException(AgentOsError.TOO_LARGE, "the text is longer than ${AgentOsMapping.MAX_PROMPT_CHARS} characters")
        val mapper = AgentOsMapping.PromptMapper(toolScope, includeThoughts = includeThoughts)
        var done = false
        try {
            session.prompt(listOf(ContentBlock.Text(text))).collect { event ->
                when (event) {
                    is Event.SessionUpdateEvent -> mapper.map(event.update)?.let { emit(it) }
                    is Event.PromptResponseEvent -> {
                        done = true
                        emit(AgentOsEvent.Done(AgentOsMapping.stopReasonWire(event.response.stopReason)))
                    }
                }
            }
        } catch (e: JsonRpcException) {
            throw AgentOsMapping.fromRpc(e)
        } catch (e: AcpExpectedError) {
            throw AgentOsMapping.fromExpected(e)
        } catch (e: AgentOsException) {
            throw e
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                // 收集方取消了：告诉 AgentOS 停下这一轮
                withContext(NonCancellable) { runCatching { session.cancel() } }
                throw e
            }
            // "Protocol closed"：通道在请求下面断了（授权被撤销、AgentOS 进程被杀）
            throw AgentOsMapping.closed(e)
        } catch (e: Exception) {
            throw AgentOsException(if (connection.isConnected) AgentOsError.FAILED else AgentOsError.DISCONNECTED, "prompt failed: ${e.javaClass.simpleName}", e)
        }
        if (!done && !connection.isConnected) throw AgentOsMapping.closed(null)
    }

    /** 取消进行中的 prompt（没有进行中的什么也不做）。 */
    suspend fun cancel() {
        try {
            session.cancel()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
        } catch (e: Exception) {
            // 连接已经没了：没有什么可取消的
        }
    }

    /**
     * 关闭这个会话：取消进行中的 prompt，释放它在 AgentOS 里占的内存（包括挂着的 MCP 服务器的连接）。
     * **会话和历史保留**，以后可以 [AgentOsConnection.loadSession] 回来；要彻底删掉用 [AgentOsConnection.deleteSession]。
     */
    suspend fun close() {
        rpc { session.close() }
        connection.forget(sessionId)
    }

    private suspend fun <T> rpc(block: suspend () -> T): T = try {
        block()
    } catch (e: JsonRpcException) {
        throw AgentOsMapping.fromRpc(e)
    } catch (e: AcpExpectedError) {
        throw AgentOsMapping.fromExpected(e)
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) throw e
        throw AgentOsMapping.closed(e)
    } catch (e: Exception) {
        throw AgentOsException(if (connection.isConnected) AgentOsError.FAILED else AgentOsError.DISCONNECTED, "request failed: ${e.javaClass.simpleName}", e)
    }
}
