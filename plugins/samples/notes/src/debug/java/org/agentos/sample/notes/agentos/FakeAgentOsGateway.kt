package org.agentos.sample.notes.agentos

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 可脚本化的假网关（只在 debug 包和单元测试里，release 里没有）：界面、状态机、用例先对着它做完。
 * 脚本分三段：connect、newSession、prompt，每段是一串步骤，按顺序执行。
 */
sealed interface FakeStep {
    /** 等一会儿（虚拟时间下也有效）。 */
    data class Delay(val ms: Long) : FakeStep

    /** 仅 connect：告诉上层“在等用户授权”。 */
    data object AuthWait : FakeStep

    /** 仅 prompt：发一个事件。 */
    data class Emit(val event: GatewayEvent) : FakeStep

    /** 以 [AgentOsException] 失败。 */
    data class Fail(val error: AgentOsError, val message: String? = null) : FakeStep

    /** 停在这里不再往下走（直到被取消），用来在某个状态上截图。 */
    data object Hang : FakeStep
}

data class FakeScript(
    val installed: Boolean = true,
    val connect: List<FakeStep> = emptyList(),
    val newSession: List<FakeStep> = emptyList(),
    val prompt: List<FakeStep> = emptyList(),
)

class FakeAgentOsGateway(private val script: FakeScript) : AgentOsGateway {
    @Volatile var closeCount = 0
        private set
    @Volatile var cancelCount = 0
        private set
    @Volatile var frontCount = 0
        private set
    @Volatile var connectCount = 0
        private set
    @Volatile var scope: List<ToolRef>? = null
        private set
    @Volatile var promptedText: String? = null
        private set
    @Volatile var promptCount = 0
        private set

    override fun isAvailable(): Boolean = script.installed

    override suspend fun connect(onWaiting: (Waiting) -> Unit) {
        connectCount++
        for (step in script.connect) {
            when (step) {
                FakeStep.AuthWait -> onWaiting(Waiting.AUTHORIZATION)
                else -> run(step, null)
            }
        }
    }

    override suspend fun newSession(toolScope: List<ToolRef>) {
        scope = toolScope
        for (step in script.newSession) run(step, null)
    }

    override fun prompt(text: String): Flow<GatewayEvent> = flow {
        promptCount++
        promptedText = text
        for (step in script.prompt) run(step) { emit(it) }
    }

    override suspend fun cancel() {
        cancelCount++
    }

    override fun bringApprovalToFront() {
        frontCount++
    }

    override fun close() {
        closeCount++
    }

    private suspend fun run(step: FakeStep, emit: (suspend (GatewayEvent) -> Unit)?) {
        when (step) {
            is FakeStep.Delay -> delay(step.ms)
            FakeStep.AuthWait -> Unit
            is FakeStep.Emit -> emit?.invoke(step.event)
            is FakeStep.Fail -> throw AgentOsException(step.error, step.message)
            FakeStep.Hang -> awaitCancellation()
        }
    }
}
