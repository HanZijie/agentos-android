package org.agentos.runtime

import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.transport.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 守住 gradle/libs.versions.toml 里的 ACP SDK 版本锁定（docs/implementation-plan.md 第 2 节）。
 *
 * BinderAcpTransport（W5）和 W4 的 Agent 端都依赖 0.30.x 的 Transport 形状：
 * `send(JsonRpcMessage)` / `onMessage((JsonRpcMessage) -> Unit)`。
 * master 分支已改成 TransportFrame / onFrame；如果有人升级了 SDK，这里先失败。
 */
class AcpSdkVersionLockTest {

    @Test
    fun `acp sdk is pinned to 0_30_1`() {
        // 按实际加载的 jar 判断。注意：Maven Central 上的 acp-jvm-0.30.1.jar（sha1 cff1bba9…）
        // 里的 LIB_VERSION 常量是 "0.30.1-dev-67"，不能单独拿它判断版本。
        val jar = Transport::class.java.protectionDomain.codeSource.location.path.substringAfterLast('/')
        assertEquals("acp-jvm-0.30.1.jar", jar)
        assertTrue(com.agentclientprotocol.acp.LIB_VERSION.startsWith("0.30.1"), "LIB_VERSION")
    }

    @Test
    fun `transport still uses JsonRpcMessage and onMessage`() {
        val methods = Transport::class.java.methods.associateBy { it.name }

        val send = methods["send"]
        val onMessage = methods["onMessage"]
        assertTrue(send != null && send.parameterTypes.single() == JsonRpcMessage::class.java, "Transport.send(JsonRpcMessage)")
        assertTrue(onMessage != null, "Transport.onMessage(handler)")
        assertTrue("onFrame" !in methods, "0.30.x 没有 onFrame；出现说明 SDK 被换成了新 Transport API")
    }
}
