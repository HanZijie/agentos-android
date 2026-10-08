package org.agentos.acp

import android.util.Log
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.JsonRpcError
import com.agentclientprotocol.rpc.JsonRpcErrorCode
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agentos.channel.BinderChannel
import org.agentos.channel.ChannelConfig
import org.agentos.channel.IAcpService
import org.agentos.channel.IChannel
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * ACP SDK 0.30.x 的 [Transport]，承载在 binder-channel-v1 的 [BinderChannel] 上。
 * 客户端（第三方 App、AgentOS 自带界面）和 Agent 端（:agent）用同一个类。
 *
 * - 入站：通道里的字符串在 [decodeDispatcher] 上解码成 JsonRpcMessage，交给 SDK，然后回 ack；
 *   解码失败的消息跳过并计数（与 SDK 的 StdioTransport 一致），不触发 onError
 *   （SDK 的 asMessageChannel 收到 error 会关掉整个消息通道）。
 * - 出站：[send] 只做 JSON 编码（[JsonRpcCodec]）和入队，不阻塞；Binder 调用在通道的写协程里完成，不在主线程。
 * - 超长的出站消息不发（binder-channel-v1 第 7 节）：请求在本地合成一个错误响应，响应改发错误响应，通知丢弃并计数。
 * - 通道因任何原因关闭（本端、对端、对端死亡、违规），都会触发 onClose。
 *
 * 接线时用 [bindTo] 把 SDK 的 [Protocol] 和本 Transport 绑在一起（S3 问题 4）。
 * 建立连接用 [connect]（客户端）和 [accept]（服务端）。
 */
class BinderAcpTransport(
    val channel: BinderChannel,
    parentScope: CoroutineScope,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : BaseTransport() {

    private val name = "BinderAcpTransport(${channel.name})"
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + CoroutineName(name)
    )

    private val decoded = AtomicLong()
    private val decodeErrors = AtomicLong()
    private val droppedTooLarge = AtomicLong()
    private val syntheticErrors = AtomicLong()
    private val droppedClosed = AtomicLong()

    override fun start() {
        check(_state.compareAndSet(Transport.State.CREATED, Transport.State.STARTING)) {
            "Transport is not in CREATED state"
        }
        scope.launch(decodeDispatcher + CoroutineName("$name.read")) {
            _state.compareAndSet(Transport.State.STARTING, Transport.State.STARTED)
            try {
                for (raw in channel.incoming) {
                    val message = try {
                        JsonRpcCodec.decode(raw)
                    } catch (e: Exception) {
                        decodeErrors.incrementAndGet()
                        Log.w(TAG, "$name: undecodable message (${raw.length} chars) skipped: ${e.javaClass.simpleName}")
                        null
                    }
                    if (message != null) {
                        decoded.incrementAndGet()
                        fireMessage(message)
                    }
                    channel.markConsumed(raw.length)
                }
            } finally {
                if (channel.isOpen) channel.close("transport stopped")
                _state.value = Transport.State.CLOSED
                fireClose()
            }
        }
    }

    override fun send(message: JsonRpcMessage) {
        val encoded = JsonRpcCodec.encode(message)
        when (channel.enqueue(encoded)) {
            BinderChannel.EnqueueResult.OK -> Unit
            BinderChannel.EnqueueResult.TOO_LARGE -> onTooLarge(message, encoded.length)
            BinderChannel.EnqueueResult.CLOSED,
            BinderChannel.EnqueueResult.BACKLOG_EXCEEDED -> droppedClosed.incrementAndGet()
        }
    }

    override fun close() {
        when (_state.value) {
            Transport.State.CLOSING, Transport.State.CLOSED -> return
            Transport.State.CREATED -> {
                _state.value = Transport.State.CLOSED
                channel.close("transport closed before start")
                fireClose()
            }
            else -> {
                _state.value = Transport.State.CLOSING
                channel.close("transport closed")
            }
        }
    }

    /**
     * 挂起直到本地出站积压不超过 [maxQueuedChars]（建议 [PRODUCER_HIGH_WATER_CHARS]）。
     * [send] 不能挂起，所以流式输出的生产者（`session/update`）每发一条之前调用它做背压。
     */
    suspend fun awaitWritable(maxQueuedChars: Long = PRODUCER_HIGH_WATER_CHARS) = channel.awaitWritable(maxQueuedChars)

    /** 诊断用的统计（不含消息内容）。 */
    fun stats(): JSONObject = JSONObject()
        .put("state", _state.value.name)
        .put("decoded", decoded.get())
        .put("decodeErrors", decodeErrors.get())
        .put("droppedTooLarge", droppedTooLarge.get())
        .put("syntheticErrors", syntheticErrors.get())
        .put("droppedClosed", droppedClosed.get())
        .put("channel", channel.stats())

    private fun onTooLarge(message: JsonRpcMessage, length: Int) {
        val detail = "$TOO_LARGE_PREFIX message of $length chars exceeds ${channel.config.maxMessageChars}"
        Log.w(TAG, "$name: $detail (${message::class.simpleName})")
        when (message) {
            is JsonRpcRequest -> {
                // 让调用方的 sendRequest 立即以 JsonRpcException 失败，而不是永远等不到响应。
                syntheticErrors.incrementAndGet()
                val error = JsonRpcResponse(
                    id = message.id,
                    error = JsonRpcError(JsonRpcErrorCode.INTERNAL_ERROR.code, detail),
                )
                scope.launch(decodeDispatcher) { fireMessage(error) }
            }
            is JsonRpcResponse -> {
                syntheticErrors.incrementAndGet()
                val error = JsonRpcResponse(
                    id = message.id,
                    error = JsonRpcError(JsonRpcErrorCode.INTERNAL_ERROR.code, detail),
                )
                channel.enqueue(JsonRpcCodec.encode(error))
            }
            is JsonRpcNotification -> droppedTooLarge.incrementAndGet()
        }
    }

    companion object {
        private const val TAG = "BinderAcpTransport"

        /** 超长消息错误响应的 message 前缀（binder-channel-v1 第 7 节）。 */
        const val TOO_LARGE_PREFIX = "binder-channel-v1:"

        /** 生产者背压的建议高水位（字符），binder-channel-v1 第 5、8 节。 */
        const val PRODUCER_HIGH_WATER_CHARS = 16_384L

        init {
            AcpAndroid.ensureInitialized()
        }

        /**
         * 把 Protocol 和 Transport 的生命周期绑在一起：Transport 关闭时关闭 Protocol，
         * 挂起中的请求随之以 CancellationException("Protocol closed") 结束。
         * 调用方可以用 [BinderChannel.closeCause] 区分对端死亡、对端关闭和本端取消。
         */
        fun bindTo(protocol: Protocol, transport: Transport) {
            transport.onClose { protocol.close() }
        }

        /**
         * 客户端：调用 [IAcpService.open] 建立通道。Binder 调用在 IO 线程上进行，可以从主线程的协程里调用。
         *
         * @param serviceUid 服务所在 App 的 UID（`PackageManager.getPackageUid`），入站调用按它校验。
         * @throws SecurityException 服务端拒绝（message 以 [AcpServiceContract.REASON_PREFIX] 开头时，
         *   用 [AcpServiceContract.reasonOf] 取原因码）；对端死亡等 Binder 错误原样抛出。
         */
        suspend fun connect(
            service: IAcpService,
            serviceUid: Int,
            scope: CoroutineScope,
            config: ChannelConfig = ChannelConfig.DEFAULT,
            name: String = "acp-client",
        ): BinderAcpTransport {
            val channel = BinderChannel(name, serviceUid, config, scope)
            val transport = BinderAcpTransport(channel, scope)
            val agentEnd = try {
                withContext(Dispatchers.IO) { service.open(channel.binder) }
                    ?: throw IllegalStateException("IAcpService.open returned null")
            } catch (e: Throwable) {
                channel.close("open failed")
                throw e
            }
            channel.attachPeer(agentEnd)
            return transport
        }

        /**
         * 服务端：在 `IAcpService.open` 里调用。通道绑定 [callerUid]（取自 `Binder.getCallingUid()`），
         * 返回的 Transport 的 `channel.binder` 就是 open 的返回值。
         */
        fun accept(
            client: IChannel,
            callerUid: Int,
            scope: CoroutineScope,
            config: ChannelConfig = ChannelConfig.DEFAULT,
            name: String = "acp-agent(uid=$callerUid)",
        ): BinderAcpTransport {
            val channel = BinderChannel(name, callerUid, config, scope)
            val transport = BinderAcpTransport(channel, scope)
            channel.attachPeer(client)
            return transport
        }
    }
}

/** AgentOS ACP 服务的对外约定：发现方式和拒绝原因码。 */
object AcpServiceContract {
    /** AgentOS 导出的 ACP 服务的 intent action；调用方在 Manifest 的 `<queries>` 里声明它。 */
    const val ACTION = "org.agentos.intent.action.ACP"

    /** AgentOS App 的包名。 */
    const val AGENTOS_PACKAGE = "org.agentos.app"

    /** `IAcpService.open` 拒绝时，SecurityException 的 message 形如 `agentos.acp.<code>: <说明>`。 */
    const val REASON_PREFIX = "agentos.acp."

    /** 这个调用方不被接受，而且不会因为用户授权而改变：共享 UID、查不到包、AgentOS 版本太旧（docs/third-party-acp.md 4.1）。 */
    const val REASON_NOT_OPEN = "agentos.acp.not_open"

    /**
     * 这个 App（包名 + 签名摘要）还没有被用户允许：AgentOS 已经记下请求、正在请用户决定。**立刻**返回，不阻塞 open；
     * 调用方每秒重试一次 open，最多 90 秒（SDK 的 [AgentOs.connect] 已经这样做）。没人决定按拒绝记。
     */
    const val REASON_AUTHORIZATION_PENDING = "agentos.acp.authorization_pending"

    /** 用户拒绝了这个 App；拒绝后 10 分钟内的 open 直接返回它、不再弹提示。用户可以在 AgentOS 设置里改成允许。 */
    const val REASON_DENIED = "agentos.acp.denied"

    /**
     * 把 AgentOS 待决的授权提示 / 工具确认带到前台的 Activity 的 action（D 的极小入口 Activity，导出、不收参数，
     * 没有待决时直接结束）。只有前台的 App 能这样启动别的 App 的 Activity。SDK：[AgentOs.bringApprovalToFront]。
     */
    const val ACTION_SHOW_APPROVAL = "org.agentos.intent.action.SHOW_APPROVAL"

    fun message(reason: String, detail: String) = "$reason: $detail"

    /** 从拒绝异常里取原因码；不是 AgentOS 的原因码时返回 null。 */
    fun reasonOf(e: Throwable): String? =
        e.message?.takeIf { it.startsWith(REASON_PREFIX) }?.substringBefore(':')?.trim()
}
