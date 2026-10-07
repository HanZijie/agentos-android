package org.agentos.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import org.agentos.channel.BinderChannel
import org.agentos.channel.ChannelConfig
import org.agentos.channel.IChannel
import org.agentos.channel.IMcpService
import org.agentos.plugin.internal.McpPipe
import org.agentos.plugin.internal.SendResult
import org.json.JSONObject

/**
 * MCP 的 Binder 传输：承载在 binder-channel-v1 的 [BinderChannel] 上，每条 JSON-RPC 消息是一次 `IChannel.send`
 * （docs/extensions.md 5.1）。客户端（AgentOS 的 Extension Host、[McpBinderClient]）和服务端（[McpBinderService]）共用。
 *
 * - 顺序、流控、单条上限（[ChannelConfig.maxMessageChars]，与 ACP 相同）、`Binder.getCallingUid()` 校验、
 *   对端死亡（linkToDeath）都由 [BinderChannel] 负责。
 * - 一般不需要直接用它：服务端继承 [McpBinderService]，客户端用 [McpBinderClient.connect] / [McpBinderClient.bind]。
 */
class McpBinderTransport private constructor(val channel: BinderChannel) {

    internal val pipe: McpPipe = object : McpPipe {
        override val incoming: ReceiveChannel<String> get() = channel.incoming
        override fun consumed(length: Int) = channel.markConsumed(length)
        override fun send(text: String): SendResult = when (channel.enqueue(text)) {
            BinderChannel.EnqueueResult.OK -> SendResult.OK
            BinderChannel.EnqueueResult.TOO_LARGE -> SendResult.TOO_LARGE
            BinderChannel.EnqueueResult.CLOSED, BinderChannel.EnqueueResult.BACKLOG_EXCEEDED -> SendResult.CLOSED
        }
        override val maxMessageChars: Int get() = channel.config.maxMessageChars
        override fun close(reason: String) = channel.close(reason)
        override fun closeReason(): String? = channel.closeCauseOrNull?.toString()
    }

    val isOpen: Boolean get() = channel.isOpen

    fun close(reason: String = "closed") = channel.close(reason)

    /** 诊断用的通道统计（不含消息内容）。 */
    fun stats(): JSONObject = channel.stats()

    companion object {
        /**
         * 客户端：调用 [IMcpService.open] 建立通道（Binder 调用在 IO 线程上）。
         * @param serviceUid 提供服务的 App 的 UID，入站消息按它校验。
         */
        suspend fun connect(
            service: IMcpService,
            serviceUid: Int,
            scope: CoroutineScope,
            config: ChannelConfig = ChannelConfig.DEFAULT,
            name: String = "mcp-client",
        ): McpBinderTransport {
            val channel = BinderChannel(name, serviceUid, config, scope)
            val serverEnd = try {
                withContext(Dispatchers.IO) { service.open(channel.binder) }
                    ?: throw IllegalStateException("IMcpService.open returned null")
            } catch (e: Throwable) {
                channel.close("open failed")
                throw e
            }
            channel.attachPeer(serverEnd)
            return McpBinderTransport(channel)
        }

        /** 服务端：在 [IMcpService.open] 里调用；通道绑定 [callerUid]（`Binder.getCallingUid()`）。返回值的 `channel.binder` 就是 open 的返回值。 */
        fun accept(
            client: IChannel,
            callerUid: Int,
            scope: CoroutineScope,
            config: ChannelConfig = ChannelConfig.DEFAULT,
            name: String = "mcp-server(uid=$callerUid)",
        ): McpBinderTransport {
            val channel = BinderChannel(name, callerUid, config, scope)
            channel.attachPeer(client)
            return McpBinderTransport(channel)
        }
    }
}
