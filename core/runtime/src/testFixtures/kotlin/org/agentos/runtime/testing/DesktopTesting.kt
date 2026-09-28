package org.agentos.runtime.testing

import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import com.agentclientprotocol.rpc.decodeJsonRpcMessage
import org.agentos.runtime.acp.LineCodec
import org.agentos.runtime.desktop.DesktopEndpoint
import org.agentos.runtime.desktop.DesktopListener
import org.agentos.runtime.desktop.DesktopListenerFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 电脑上的 [LineCodec]：与 sdk:acp-android 的 `org.agentos.acp.JsonRpcCodec`（Android 上的电脑端网关用它）实现相同，
 * 按具体类型的 serializer 编码，没有 SDK StdioTransport 那个多余的 `"type"` 字段。
 * core:runtime 是纯 JVM 模块，不能依赖 Android 库，所以测试这边保留一份。
 */
object TestLineCodec : LineCodec {
    override fun encode(message: JsonRpcMessage): String = when (message) {
        is JsonRpcRequest -> ACPJson.encodeToString(JsonRpcRequest.serializer(), message)
        is JsonRpcNotification -> ACPJson.encodeToString(JsonRpcNotification.serializer(), message)
        is JsonRpcResponse -> ACPJson.encodeToString(JsonRpcResponse.serializer(), message)
    }

    override fun decode(line: String): JsonRpcMessage = decodeJsonRpcMessage(line)
}

/**
 * 电脑上代替抽象 socket 的监听端：本机回环地址上的 TCP 端口。对端 UID 一律报 [peerUid]
 * （默认 2000，与 adb forward 过来的 adbd 相同）。
 *
 * @param port 0 表示第一次由系统挑一个空闲端口；之后重新打开（开关关了又开）沿用同一个端口。
 */
class TcpDesktopListenerFactory(port: Int = 0, private val peerUid: Int = SHELL_UID) : DesktopListenerFactory {
    @Volatile var port: Int = port
        private set

    override fun open(): DesktopListener {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50)
        port = server.localPort
        return object : DesktopListener {
            override fun accept(): DesktopEndpoint = SocketEndpoint(server.accept(), peerUid)

            override fun close() = server.close()
        }
    }

    private class SocketEndpoint(private val socket: Socket, override val peerUid: Int) : DesktopEndpoint {
        override val input: InputStream = socket.getInputStream()
        override val output: OutputStream = socket.getOutputStream()

        override fun close() {
            runCatching { socket.close() }
        }
    }

    companion object {
        const val SHELL_UID = 2000
    }
}
