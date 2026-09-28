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
import org.agentos.channel.BinderChannel
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * ACP SDK 0.30.x 的 [Transport]，承载在 binder-channel-v1 的 [BinderChannel] 上。
 * 客户端（第三方 App、AgentOS 自带界面）和 Agent 端（:agent）用同一个类。
 *
 * - 入站：通道里的字符串在 [decodeDispatcher] 上解码成 JsonRpcMessage，交给 SDK，然后回 ack；
 * - 出站：[send] 只做 JSON 编码和入队，不阻塞；Binder 调用在通道的写协程里完成，不在主线程；
 * - 超长的出站消息不发：请求在本地合成一个错误响应，响应改发错误响应，通知丢弃并计数；
 * - 通道因任何原因关闭（本端、对端、对端死亡、违规），都会触发 onClose。
 *
 * 注意：SDK 的 [Protocol] 不会在 Transport 关闭时结束挂起的请求，接线时要用 [bindTo]。
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
                        Log.w(TAG, "$name: undecodable message (${raw.length} chars) skipped: $e")
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

    /** 挂起直到本地出站积压不超过 [maxQueuedChars]。流式输出的生产者用它做背压。 */
    suspend fun awaitWritable(maxQueuedChars: Long) = channel.awaitWritable(maxQueuedChars)

    fun stats(): JSONObject = JSONObject()
        .put("state", _state.value.name)
        .put("decoded", decoded.get())
        .put("decodeErrors", decodeErrors.get())
        .put("droppedTooLarge", droppedTooLarge.get())
        .put("syntheticErrors", syntheticErrors.get())
        .put("droppedClosed", droppedClosed.get())
        .put("channel", channel.stats())

    private fun onTooLarge(message: JsonRpcMessage, length: Int) {
        val detail = "binder-channel-v1: message of $length chars exceeds ${channel.config.maxMessageChars}"
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
    }
}
