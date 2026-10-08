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
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
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
 * val session = connection.newSession(listOf(ToolRef("alarm", "alarm_create"), ToolRef("calendar", "event_create")))
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
            try {
                withTimeout(CONNECT_TIMEOUT_MILLIS) {
                    client.initialize(ClientInfo(implementation = Implementation(CLIENT_NAME, CLIENT_VERSION)))
                }
            } catch (e: Exception) {
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                runCatching { transport.close() }
                throw AgentOsException(AgentOsError.DISCONNECTED, "initialize failed: ${e.javaClass.simpleName}", e)
            }
            return AgentOsConnection(binding, transport, protocol, client, scope)
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
) : AutoCloseable {
    @Volatile private var closed = false

    /** 连接还活着。AgentOS 进程被杀、授权被撤销后为 false。 */
    val isConnected: Boolean get() = !closed && transport.channel.isOpen

    /**
     * 新建一个会话。[toolScope] 是这个会话**最多**能用的工具；只能缩小、不能放大（实际可用 = toolScope 与用户当前启用的工具的交集），
     * 写了不存在的项会被忽略，也不报错。空列表 = 没有任何工具，只能聊天。最多 32 项，每个名字最多 128 字符（否则抛 [IllegalArgumentException]）。
     *
     * @throws AgentOsException [AgentOsError.DISCONNECTED]、[AgentOsError.FAILED] 等
     */
    suspend fun newSession(toolScope: List<ToolRef>): AgentOsSession {
        val meta = AgentOsMapping.scopeMeta(toolScope)
        val session = try {
            withTimeout(SESSION_TIMEOUT_MILLIS) {
                client.newSession(SessionCreationParameters(cwd = "/", mcpServers = emptyList(), _meta = meta)) { _, _ -> NoPermissionUi }
            }
        } catch (e: JsonRpcException) {
            throw AgentOsMapping.fromRpc(e)
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            throw AgentOsMapping.closed(e)
        } catch (e: Exception) {
            throw AgentOsException(AgentOsError.FAILED, "session/new failed: ${e.javaClass.simpleName}", e)
        }
        return AgentOsSession(this, session, toolScope.distinct())
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

    /**
     * AgentOS 的工具确认由 AgentOS 自己的界面问用户（F5）；作为 ACP 客户端，SDK 不替用户批准任何东西，
     * 所以 ACP 的权限请求一律回答“取消”。
     */
    private object NoPermissionUi : ClientSessionOperations {
        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: JsonElement?,
        ): RequestPermissionResponse = RequestPermissionResponse(RequestPermissionOutcome.Cancelled)

        override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit
    }

    private companion object {
        const val SESSION_TIMEOUT_MILLIS = 15_000L
    }
}

/** 一个会话。同一个 App 同时只能有一个进行中的 prompt。 */
class AgentOsSession internal constructor(
    private val connection: AgentOsConnection,
    private val session: ClientSession,
    private val toolScope: List<ToolRef>,
) {
    /**
     * 发一轮 prompt，返回这一轮的事件流：[AgentOsEvent.Text]、[AgentOsEvent.ToolCall]（同一个 id 多次）、最后一个 [AgentOsEvent.Done]。
     * 流是冷的：开始收集才发送；取消收集等于 [cancel]。失败时流以 [AgentOsException] 结束：[AgentOsError.NO_MODEL]（AgentOS 没配置模型）、
     * [AgentOsError.BUSY]、[AgentOsError.RATE_LIMITED]、[AgentOsError.TOO_LARGE]（文字超过 16,000 字符）、[AgentOsError.DISCONNECTED]、[AgentOsError.FAILED]。
     *
     * 第三方会话里**每一次**工具调用都会让用户在 AgentOS 里确认（[ToolStatus.PENDING_APPROVAL]），用户没有回答会超时按拒绝处理。
     */
    fun prompt(text: String): Flow<AgentOsEvent> = flow {
        if (text.length > AgentOsMapping.MAX_PROMPT_CHARS) throw AgentOsException(AgentOsError.TOO_LARGE, "the text is longer than ${AgentOsMapping.MAX_PROMPT_CHARS} characters")
        val mapper = AgentOsMapping.PromptMapper(toolScope)
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
}
