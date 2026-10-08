package org.agentos.extensions.session

import okhttp3.OkHttpClient
import org.agentos.extensions.host.McpServerLink
import org.agentos.runtime.ports.SessionMcpServer

/**
 * 为一个会话级 MCP 服务器建立连接。**连接完成时 MCP 初始化已经做完**，返回的 [McpServerLink] 可以直接用。
 * 测试注入假的；默认实现是 [StreamableHttpConnector]（见 [streamableHttp]）。
 */
fun interface SessionMcpConnector {
    /**
     * @param connectTimeoutMillis 建立连接（含初始化）的上限；实现自己守，宿主层也会用同样的时限强制取消
     * @throws org.agentos.extensions.host.ConnectFailed 连不上（异常消息里不得有 URL、头、响应正文）
     */
    suspend fun connect(server: SessionMcpServer, connectTimeoutMillis: Long): McpServerLink

    companion object {
        /** 默认实现：Streamable HTTP。[policy] 决定能连哪些地址（生产用默认值：只有公网 https）。 */
        fun streamableHttp(
            policy: SessionUrlPolicy = SessionUrlPolicy(),
            client: OkHttpClient = StreamableHttpLink.defaultClient(),
        ): SessionMcpConnector = StreamableHttpConnector(policy, client)
    }
}

/** 用 [StreamableHttpLink] 连接。每次连接前再按 [policy] 校验一次 URL 和头，并用它的 DNS 解析（防 DNS rebinding）。 */
class StreamableHttpConnector(
    private val policy: SessionUrlPolicy = SessionUrlPolicy(),
    private val client: OkHttpClient = StreamableHttpLink.defaultClient(),
) : SessionMcpConnector {
    override suspend fun connect(server: SessionMcpServer, connectTimeoutMillis: Long): McpServerLink =
        StreamableHttpLink.connect(server.url, server.headers, policy, connectTimeoutMillis, client)

    override fun toString() = "StreamableHttpConnector"
}
