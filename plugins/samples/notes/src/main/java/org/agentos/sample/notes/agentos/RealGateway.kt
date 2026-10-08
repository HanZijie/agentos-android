package org.agentos.sample.notes.agentos

import android.content.Context

/**
 * 真网关的入口：release 和 debug（没打开假网关开关时）都从这里拿。
 *
 * **SDK 到之前的占位**：`sdk:acp-android` 的 `AgentOs`（docs/third-party-acp.md 4.7）还没合进 main，所以这里先返回一个
 * 永远报“没装 AgentOS”的网关。SDK 合入后，把这个函数改成 `RealAgentOsGateway(context)`（见 README“接真 SDK”一节），
 * 其余代码不用动。
 */
fun createRealGateway(@Suppress("UNUSED_PARAMETER") context: Context): AgentOsGateway = UnconnectedAgentOsGateway()

/** SDK 之前的占位：不可用。 */
internal class UnconnectedAgentOsGateway : AgentOsGateway {
    override fun isAvailable(): Boolean = false
    override suspend fun connect(onWaiting: (Waiting) -> Unit) = throw AgentOsException(AgentOsError.NOT_INSTALLED)
    override suspend fun newSession(toolScope: List<ToolRef>) = throw AgentOsException(AgentOsError.NOT_INSTALLED)
    override fun prompt(text: String): kotlinx.coroutines.flow.Flow<GatewayEvent> = throw AgentOsException(AgentOsError.NOT_INSTALLED)
    override suspend fun cancel() = Unit
    override fun bringApprovalToFront() = Unit
    override fun close() = Unit
}
