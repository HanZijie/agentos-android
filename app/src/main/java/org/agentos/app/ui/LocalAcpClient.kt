package org.agentos.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.agentos.acp.AcpServiceContract
import org.agentos.acp.BinderAcpTransport
import org.agentos.app.agent.AcpService
import org.agentos.channel.IAcpService

/** The conversation screen's view of the runtime; [LocalAcpClient] in the App, fakes in tests. */
interface AgentConnection {
    val connection: StateFlow<ChatState.Connection>

    /**
     * Runs one ACP turn (`session/prompt`) on the current session, creating the connection and the
     * session when needed. [onUpdate] receives the streamed updates in order. Never throws except
     * for cancellation of the calling coroutine.
     */
    suspend fun prompt(text: String, onUpdate: (AgentUpdate) -> Unit): TurnOutcome

    /** `session/cancel` for the running turn; the turn then ends with stop reason `cancelled`. */
    suspend fun cancel()

    /** Forget the current session; the next prompt starts a new one. */
    fun newSession()

    /** True once after a prompt had to create a new session while an earlier one existed. */
    fun consumeSessionReplaced(): Boolean

    /** Close the channel and unbind. The runtime keeps running tasks (a disconnect is not a cancel, F7). */
    fun close()
}

/**
 * ACP client of the App's own UI (W8): official Kotlin SDK Client + [BinderAcpTransport] to the
 * exported `IAcpService` in `:agent` (docs/architecture.md 5.3). The runtime accepts this App's UID
 * in M1; any other caller gets `agentos.acp.not_open`.
 */
class LocalAcpClient(private val context: Context, parent: CoroutineScope) : AgentConnection {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + CoroutineName("local-acp"))
    private val mutex = Mutex()
    private val state = MutableStateFlow(ChatState.Connection.DISCONNECTED)
    override val connection: StateFlow<ChatState.Connection> = state.asStateFlow()

    private var conn: Conn? = null
    private var session: ClientSession? = null
    private var hadSession = false
    @Volatile private var replaced = false

    private class ConnectFailure(val error: AgentError) : Exception(error.code)

    private class Conn(
        val binding: Binding,
        val transport: BinderAcpTransport,
        val protocol: Protocol,
        val client: Client,
        val scope: CoroutineScope,
    ) {
        val open: Boolean get() = transport.channel.isOpen
    }

    override suspend fun prompt(text: String, onUpdate: (AgentUpdate) -> Unit): TurnOutcome {
        val (c, s) = try {
            ensureSession()
        } catch (e: ConnectFailure) {
            return TurnOutcome.Failed(e.error)
        }
        var stopReason: String? = null
        return try {
            s.prompt(listOf(ContentBlock.Text(text))).collect { event ->
                val (update, stop) = event.toUi()
                if (update != null) onUpdate(update)
                if (stop != null) stopReason = stop
            }
            TurnOutcome.Finished(stopReason ?: "end_turn")
        } catch (e: JsonRpcException) {
            val error = rpcError(e)
            if (error.code == "session_not_found" || error.code == "session_terminal") dropSession(s)
            TurnOutcome.Failed(error)
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e // the caller cancelled us
            // "Protocol closed": the channel went away under the request (BinderAcpTransport.bindTo)
            val cause = c.transport.channel.closeCauseOrNull
                ?: withTimeoutOrNull(1_000) { c.transport.channel.closeCause.await() }
            dropConnection(c)
            TurnOutcome.Failed(AgentErrors.fromClose(cause))
        } catch (e: Exception) {
            Log.w(TAG, "prompt failed: ${e.javaClass.simpleName}")
            if (!c.open) dropConnection(c)
            TurnOutcome.Failed(AgentErrors.unexpected(e))
        }
    }

    override suspend fun cancel() {
        val s = session ?: return
        try {
            s.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "session/cancel failed: ${e.javaClass.simpleName}")
        }
    }

    override fun newSession() {
        session = null
        hadSession = false
    }

    override fun consumeSessionReplaced(): Boolean = replaced.also { replaced = false }

    override fun close() {
        val c = conn
        conn = null
        session = null
        c?.let { shutdown(it, "ui closed") }
        state.value = ChatState.Connection.DISCONNECTED
    }

    private suspend fun ensureSession(): Pair<Conn, ClientSession> = mutex.withLock {
        val c = conn?.takeIf { it.open } ?: connect().also { conn = it }
        val existing = session
        if (existing != null) return c to existing
        val s = try {
            withTimeout(TIMEOUT_MS) {
                c.client.newSession(SessionCreationParameters(cwd = "/", mcpServers = emptyList())) { _, _ -> NoPermissionUi }
            }
        } catch (e: JsonRpcException) {
            throw ConnectFailure(rpcError(e))
        } catch (e: Exception) {
            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            dropConnection(c)
            throw ConnectFailure(AgentErrors.fromClose(c.transport.channel.closeCauseOrNull))
        }
        if (hadSession) replaced = true
        hadSession = true
        session = s
        c to s
    }

    private suspend fun connect(): Conn {
        state.value = ChatState.Connection.CONNECTING
        val connScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + CoroutineName("local-acp-conn"))
        val binding = Binding(context)
        try {
            val binder = binding.bind(TIMEOUT_MS) ?: throw ConnectFailure(
                if (binding.bound) AgentErrors.connectFailed(null) else AgentErrors.serviceMissing()
            )
            val transport = try {
                BinderAcpTransport.connect(IAcpService.Stub.asInterface(binder), Process.myUid(), connScope, name = "agentos-ui")
            } catch (e: SecurityException) {
                throw ConnectFailure(AgentErrors.connectFailed(AcpServiceContract.reasonOf(e) ?: "security"))
            } catch (e: Exception) {
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                throw ConnectFailure(AgentErrors.connectFailed(null))
            }
            val protocol = Protocol(connScope, transport, ProtocolOptions(protocolDebugName = "agentos-ui"))
            val client = Client(protocol)
            BinderAcpTransport.bindTo(protocol, transport)
            val c = Conn(binding, transport, protocol, client, connScope)
            transport.onClose {
                // runtime died or closed us: the session is gone with the connection
                if (conn === c) {
                    conn = null
                    session = null
                    state.value = ChatState.Connection.DISCONNECTED
                }
            }
            protocol.start()
            try {
                withTimeout(TIMEOUT_MS) {
                    client.initialize(ClientInfo(implementation = Implementation(CLIENT_NAME, CLIENT_VERSION)))
                }
            } catch (e: Exception) {
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                val cause = transport.channel.closeCauseOrNull
                shutdown(c, "initialize failed")
                throw ConnectFailure(if (cause != null) AgentErrors.fromClose(cause) else AgentErrors.connectFailed(null))
            }
            state.value = ChatState.Connection.CONNECTED
            return c
        } catch (e: Throwable) {
            if (state.value == ChatState.Connection.CONNECTING) state.value = ChatState.Connection.DISCONNECTED
            binding.unbind()
            connScope.cancel()
            throw e
        }
    }

    private fun dropSession(s: ClientSession) {
        if (session === s) session = null
    }

    private fun dropConnection(c: Conn) {
        if (conn === c) {
            conn = null
            session = null
            state.value = ChatState.Connection.DISCONNECTED
        }
        shutdown(c, "connection lost")
    }

    private fun shutdown(c: Conn, why: String) {
        scope.launch {
            runCatching { c.protocol.close() }
            runCatching { c.transport.close() }
            c.binding.unbind()
            c.scope.cancel(CancellationException(why))
        }
    }

    private fun rpcError(e: JsonRpcException): AgentError {
        val data = e.data as? JsonObject
        val code = data?.get("agentosCode")?.jsonPrimitive?.contentOrNull
        val retryable = data?.get("retryable")?.jsonPrimitive?.booleanOrNull
        val retryAfter = (data?.get("details") as? JsonObject)?.get("retryAfterSeconds")?.jsonPrimitive?.longOrNull
        return AgentErrors.fromRpc(e.code, e.message, code, retryable, retryAfter)
    }

    /** bindService to the App's own AcpService in :agent, suspending until connected. */
    private class Binding(private val context: Context) {
        @Volatile var bound = false
            private set
        private val connected = CompletableDeferred<IBinder?>()
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                connected.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit // the channel sees the death

            override fun onNullBinding(name: ComponentName?) {
                connected.complete(null)
            }
        }

        suspend fun bind(timeoutMs: Long): IBinder? {
            val intent = Intent(AcpServiceContract.ACTION).setComponent(ComponentName(context, AcpService::class.java))
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) return null
            return withTimeoutOrNull(timeoutMs) { connected.await() }
        }

        fun unbind() {
            if (bound) {
                bound = false
                runCatching { context.unbindService(connection) }
            }
        }
    }

    /**
     * Tool confirmations are asked by AgentOS's own consent UI (F5, W16); as an ACP client this UI never
     * grants anything on the user's behalf, so a permission request is answered "cancelled".
     */
    private object NoPermissionUi : ClientSessionOperations {
        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: JsonElement?,
        ): RequestPermissionResponse = RequestPermissionResponse(RequestPermissionOutcome.Cancelled)

        override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit
    }

    companion object {
        private const val TAG = "AgentOS.LocalAcp"
        private const val TIMEOUT_MS = 15_000L
        const val CLIENT_NAME = "agentos-app-ui"
        const val CLIENT_VERSION = "0.1"
    }
}
