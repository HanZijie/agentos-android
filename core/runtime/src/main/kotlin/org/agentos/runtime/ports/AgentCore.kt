package org.agentos.runtime.ports

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.agentos.runtime.errors.ErrorInfo
import org.agentos.runtime.events.AgentEvent

/*
 * AgentCore：宿主层对 Agent 循环的全部依赖（docs/architecture.md 4.1 的“Pi 适配层”）。
 *
 * - 实现：B lane 的 core/runtime/pi/PiAdapter（W3），底下是 JsEngine + 常驻泵 + pi-agent.js（docs/spikes/S8.md）。
 * - 测试：testFixtures 里的 FakeAgentCore，可以编排文字增量、thinking、tool_use、错误、挂起等待 abort、泵故障。
 * - 调用方：宿主层的 scheduler（W2）按“一个会话同时最多一轮”调用；ACP 层（W4）不直接碰 AgentCore。
 *
 * 契约要点（B 实现时逐条满足，FakeAgentCore 也按这些行为）：
 *
 * 1. 一个 AgentCore 承载全部会话（S8 结论 b）。openSession 按 sessionId 新建 Pi `Agent`；传入 restore 时用保存的
 *    messages 重建，重建后上下文与原会话逐字节一致（system prompt 是 messages 开头的 role:"system" 消息，原样传回，不会重复）。
 * 2. runTurn 提交一轮输入，**等到 Pi 发出 agent_end 之后**才返回 TurnOutcome（ACP v1：一轮结束才返回 stopReason）。
 *    这一轮里的生命周期事件按发生顺序交给 TurnHost.onEvent；工具调用、beforeToolCall、afterToolCall 都回到 TurnHost。
 * 3. 同一会话同一时间最多一个 runTurn（宿主层保证；实现发现违反时抛 IllegalStateException）。不同会话的 runTurn 可以并发，
 *    互不等待；abort、messages 可以在 runTurn 进行中从别的协程调用，不排在这一轮后面。
 * 4. abort：尽快让这一轮以 [TurnOutcome.Aborted] 结束（Pi 走 aborted 收尾，不用 interruptEvaluation 打断求值）；
 *    正在执行的 TurnHost.executeTool 协程被取消（宿主层据此向 Extension Host 转发取消）。没有进行中的一轮时是空操作。
 *    调用 runTurn 的协程被取消时，实现同样要 abort 这一轮、不再回调 TurnHost，然后以 CancellationException 结束。
 * 5. 工具轮次上限由适配层计数（默认 12，见 [AgentSessionConfig.maxToolRounds]）：超过就 abort，返回 [TurnOutcome.ToolRoundLimit]。
 *    abort 和轮次上限之后，messages 仍然必须能继续下一轮：每个 toolCall 都有对应的 toolResult（被中止的记为 isError）。
 * 6. 模型调用失败（Pi 的 stopReason = "error"）返回 [TurnOutcome.Failed]，错误码按 core/contracts/errors.md 分类
 *    （网络出口 HostFetch 用 ModelFailures 分类，适配层把分类结果带到这里）。不抛异常。
 * 7. 泵故障（quickjs-kt 终止根求值、JS 引擎崩溃）按运行时崩溃处理（F8）：[AgentCore.state] 变为 Failed，
 *    所有进行中的 runTurn 以 [TurnOutcome.CoreLost] 返回（约 1 ms 内，不能挂住），之后这个实例上的所有调用都抛
 *    [AgentCoreUnavailableException]。宿主层用 [AgentCoreFactory] 新建实例，用 Store 里的 messages 重建会话；
 *    进行中的轮次标记为需要恢复，不自动重放。
 * 8. JS 里拿不到 key：模型请求经宿主层的网络出口（net/HostFetch），key 由 HostPort.secrets 按 endpoint 注入。
 *    这个接口里的任何类型都不携带凭据。
 * 9. TurnHost.onEvent 在实现的事件线程上**按顺序同步**调用，宿主层保证它只做转交（投进 Channel）、不阻塞、不做 I/O；
 *    实现不能在 JS 线程上等待宿主层的挂起操作。
 */

/** Agent core 的实例状态。 */
sealed interface AgentCoreState {
    /** 还没 start。 */
    data object Idle : AgentCoreState

    data object Starting : AgentCoreState

    /** 可以 openSession / runTurn。 */
    data object Ready : AgentCoreState

    /** 泵已退出或引擎崩溃；这个实例不能再用，由宿主层新建。 */
    data class Failed(val error: ErrorInfo) : AgentCoreState

    /** 已 close。 */
    data object Closed : AgentCoreState
}

/** 宿主层用它在启动时和泵故障后新建 AgentCore。W6 接线时提供 PiAdapter 的工厂，测试提供 FakeAgentCore 的工厂。 */
fun interface AgentCoreFactory {
    fun create(): AgentCore
}

interface AgentCore {
    val state: StateFlow<AgentCoreState>

    /** 加载 bundle、启动泵。幂等；失败时抛异常，state 变为 Failed。 */
    suspend fun start()

    /**
     * 新建会话对应的 Pi `Agent`，或用 [restore] 里保存的 messages 重建。
     * 同一个 sessionId 已经打开时抛 IllegalStateException（宿主层先 dispose 再重开）。
     */
    suspend fun openSession(sessionId: String, config: AgentSessionConfig, restore: PiMessages? = null): AgentCoreSession

    /** 结束泵、释放引擎。进行中的轮次以 Aborted 结束。幂等。 */
    suspend fun close()
}

interface AgentCoreSession {
    val sessionId: String

    /**
     * 更换下一轮用的模型、system prompt 或工具目录（例如 Extension Host 通知目录变化）。只能在没有进行中的一轮时调用，
     * 否则抛 IllegalStateException。实现可以用 0.86.1 的中途 system 消息（toolsAdded / toolsRemoved）表达变化。
     */
    suspend fun reconfigure(config: AgentSessionConfig)

    /** 提交一轮输入，等到 agent_end 后返回。见文件头的契约 2–7。 */
    suspend fun runTurn(input: TurnInput, host: TurnHost): TurnOutcome

    /** 让进行中的一轮尽快以 Aborted 结束；不等它结束就返回。没有进行中的一轮时是空操作。 */
    suspend fun abort()

    /**
     * 当前完整的 messages（含开头的 system 消息），供持久化。宿主层在每轮结束后、以及每个 turn_end 之后各取一次写进 Store。
     * 进行中的 assistant 消息在 message_end 之前不在里面。
     */
    suspend fun messages(): PiMessages

    /** 释放这个会话的 Pi `Agent`。进行中的一轮先 abort。幂等。 */
    suspend fun dispose()
}

/** 泵故障后，或实例已 close 后，调用 AgentCore / AgentCoreSession 的方法时抛出。 */
class AgentCoreUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * 一个会话的 Agent 配置。
 *
 * @property model 模型：pi-ai 的 Model 对象，来自 model-catalog.json 或自定义兼容端点，不含凭据。
 * @property systemPrompt 基础 system prompt（Skill 目录等由宿主层拼进来，W20）。
 * @property tools 当前的工具目录（Broker 过滤后）；只有这里声明的工具能被模型调用。
 * @property maxToolRounds 工具轮次上限：一轮 prompt 里带工具调用的模型往返最多这么多次，超过返回 ToolRoundLimit。
 */
data class AgentSessionConfig(
    val model: ModelSpec,
    val systemPrompt: String,
    val tools: List<ToolDeclaration> = emptyList(),
    val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
) {
    init {
        require(maxToolRounds >= 1) { "maxToolRounds must be >= 1" }
        require(tools.map { it.name }.toSet().size == tools.size) { "duplicate tool names" }
    }

    companion object {
        /** docs/architecture.md 4.1：工具轮次上限 12 轮。 */
        const val DEFAULT_MAX_TOOL_ROUNDS = 12
    }
}

/**
 * 模型选择：[model] 原样作为 Pi `create` 命令的 model 传给 JS（S8：“pi-ai 的 Model 对象，原样传给运行时”）。
 * [thinkingLevel]：Pi 的 thinkingLevel（off / minimal / low / medium / high）。
 */
data class ModelSpec(
    val model: JsonObject,
    val thinkingLevel: String = "off",
) {
    val api: String? get() = (model["api"] as? JsonPrimitive)?.contentOrNull
    val id: String? get() = (model["id"] as? JsonPrimitive)?.contentOrNull
    val provider: String? get() = (model["provider"] as? JsonPrimitive)?.contentOrNull
    val baseUrl: String? get() = (model["baseUrl"] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** 打包进 pi-agent.js 的两个协议族。 */
        val SUPPORTED_APIS: Set<String> = setOf("anthropic-messages", "openai-completions")
    }
}

/** 交给 Pi 的工具声明。[inputSchema] 是 JSON Schema（object）。执行一律回到 TurnHost.executeTool。 */
data class ToolDeclaration(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val label: String? = null,
)

/** 一轮的用户输入。ACP 的 resource_link 等由宿主层先转成文字（W4）。 */
data class TurnInput(
    val text: String,
    val images: List<ContentPart.Image> = emptyList(),
)

/** 文字和图片内容块，JSON 形状与 pi-ai 的 TextContent / ImageContent 相同。 */
@Serializable
sealed interface ContentPart {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentPart

    /** [data] 是 base64。 */
    @Serializable
    @SerialName("image")
    data class Image(val data: String, val mimeType: String) : ContentPart {
        override fun toString(): String = "Image(mimeType=$mimeType, ${data.length} base64 chars)"
    }
}

/** 模型发起的一次工具调用。[arguments] 已经过 Pi 按工具 schema 校验。 */
data class ToolCall(
    val toolCallId: String,
    val name: String,
    val arguments: JsonObject,
)

/** beforeToolCall 的决定。Block 时 Pi 不执行工具，把 [ToolCallDecision.Block.reason] 作为错误结果交回模型。 */
sealed interface ToolCallDecision {
    data object Allow : ToolCallDecision

    /** @property terminate Pi 的提示：这一批工具调用全部被拦截且都带 terminate 时，本轮提前结束。 */
    data class Block(val reason: String, val terminate: Boolean = false) : ToolCallDecision
}

/** 工具结果，对应 Pi 的 AgentToolResult + isError。 */
data class ToolResult(
    val content: List<ContentPart>,
    val isError: Boolean = false,
    val details: JsonElement? = null,
) {
    companion object {
        fun text(text: String, isError: Boolean = false) = ToolResult(listOf(ContentPart.Text(text)), isError)
    }
}

/**
 * 一轮进行中，Agent core 回到宿主层的全部入口。由宿主层按任务实现（知道 sessionId、taskId、调用方 UID），每轮传一个。
 *
 * 调用顺序与 Pi 0.86.1 的 agent-loop 一致：tool_execution_start → beforeToolCall → executeTool → afterToolCall → tool_execution_end。
 */
interface TurnHost {
    /** Pi 生命周期事件，按顺序同步调用；实现只做转交，不阻塞（契约 9）。 */
    fun onEvent(event: AgentEvent)

    /** Pi 的 beforeToolCall：风险策略、确认、PreToolUse Hook（W16、W22）。M1 返回 Allow。 */
    suspend fun beforeToolCall(call: ToolCall): ToolCallDecision

    /**
     * 执行工具（Broker → Extension Host，W16）。协程被取消表示 Pi 这一轮被 abort，实现要把取消转给工具提供方。
     * 失败一律作为 isError 的结果返回，不抛异常（取消除外）。
     */
    suspend fun executeTool(call: ToolCall): ToolResult

    /** Pi 的 afterToolCall：PostToolUse Hook、截断过大的结果。返回 null 表示不改。 */
    suspend fun afterToolCall(call: ToolCall, result: ToolResult): ToolResult?
}

/** 一轮的结局。宿主层据此写 task.* 事件并映射成 ACP 的 stopReason（core/protocol/acp-mapping.md）。 */
sealed interface TurnOutcome {
    /** 正常结束。[usage] 是最后一条 assistant 消息的 pi-ai Usage（可选）。 */
    data class Finished(val reason: FinishReason, val usage: JsonObject? = null) : TurnOutcome

    /** 被 abort（用户取消、执行超时等，原因由宿主层自己记录）。 */
    data object Aborted : TurnOutcome

    /** 工具轮次超过上限，已 abort。 */
    data class ToolRoundLimit(val rounds: Int, val limit: Int) : TurnOutcome

    /** 模型调用失败（Pi stopReason = "error"），错误已分类。 */
    data class Failed(val error: ErrorInfo) : TurnOutcome

    /** 泵故障，这一轮在 Agent core 里的结局未知（契约 7）。宿主层按恢复流程处理，不自动重放。 */
    data class CoreLost(val error: ErrorInfo) : TurnOutcome
}

/** 正常结束的原因，对应 Pi 最后一条 assistant 消息的 stopReason。 */
enum class FinishReason {
    /** Pi `stop` → ACP `end_turn`。 */
    END_TURN,

    /** Pi `length` → ACP `max_tokens`。 */
    MAX_TOKENS,

    /** 模型拒绝回答 → ACP `refusal`（Pi 0.86.1 目前没有单独的 stopReason，预留）。 */
    REFUSAL,
}

/**
 * Pi 的 messages（pi-ai `Message[]`，开头是 role:"system" 消息）。宿主层原样保存、原样传回，不解释内容；
 * 在 Store 里按会话保存（W2），用于 openSession(restore = …)。
 */
@JvmInline
value class PiMessages(val json: JsonArray) {
    val size: Int get() = json.size

    companion object {
        val EMPTY = PiMessages(JsonArray(emptyList()))
    }
}
