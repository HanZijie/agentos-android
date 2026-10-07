package org.agentos.runtime.broker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.Ids
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.errors.ErrorInfo
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.events.PendingEvent
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CatalogTool
import org.agentos.runtime.ports.ConsentDecision
import org.agentos.runtime.ports.ConsentRequest
import org.agentos.runtime.ports.ContentPart
import org.agentos.runtime.ports.HookDecision
import org.agentos.runtime.ports.HookRequest
import org.agentos.runtime.ports.HostPort
import org.agentos.runtime.ports.ToolCall
import org.agentos.runtime.ports.ToolCallDecision
import org.agentos.runtime.ports.ToolDeclaration
import org.agentos.runtime.ports.ToolInvocation
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.store.StoreTx
import org.agentos.runtime.store.ToolCallState
import java.util.concurrent.ConcurrentHashMap

/**
 * Capability Broker：Pi 的工具调用回到宿主层后，一律经过这里（architecture 4.1）。
 *
 * - [declarations]：交给 Pi 的工具只来自当前目录（HostPort.tools，W14 起由 Extension Host 提供）；
 * - [authorize]：Pi 的 beforeToolCall —— 目录校验、PreToolUse Hook、按风险等级请用户确认；
 * - [execute]：再次按目录校验，**先提交 `tool.dispatched`，再调用**工具提供方，结果写进 `tool.settled`，
 *   据此区分“确定没发出”和“结果未知”（恢复时不重放，F8）；
 * - [afterExecute]：Pi 的 afterToolCall —— 截断过大的结果（PostToolUse Hook 在 W22）。
 *
 * 风险与确认（W16，[RiskPolicy] 与 [ApprovalPolicy]）：
 * - 用户策略（`HostPort.approvals`）禁用的工具，对模型来说就是“不在目录里”（[declarations] 不列出，[authorize] / [execute]
 *   按 TOOL_NOT_IN_CATALOG 拒绝，文字与真的不存在时相同）；
 * - 是否确认由 [RiskPolicy.consentRequirement] 决定：READ 直接执行；HIGH 每次确认；WRITE 默认每次确认，
 *   用户策略设为“始终允许”（依据 `policy`）或本会话内选过“不再询问”（依据 `remembered`）时直接执行；
 *   Hook 的 allow 不能跳过确认，Hook 的 ask 一定确认。
 */
interface CapabilityBroker {
    fun declarations(): List<ToolDeclaration>

    suspend fun authorize(ctx: ToolContext, call: ToolCall): ToolCallDecision

    suspend fun execute(ctx: ToolContext, call: ToolCall): ToolResult

    suspend fun afterExecute(ctx: ToolContext, call: ToolCall, result: ToolResult): ToolResult?
}

/**
 * 一次工具调用所在的任务。[commit] 把写操作排进这个任务的有序写队列（与 Pi 事件同一个队列），
 * 在一个事务里提交后才返回——保证 `tool.dispatched` 在调用工具之前已经落盘，且事件顺序与发生顺序一致。
 */
class ToolContext(
    val sessionId: String,
    val taskId: String,
    val caller: CallerIdentity,
    val commit: suspend (block: (StoreTx) -> Unit) -> Unit,
)

data class BrokerConfig(
    /** 一次工具调用的超时。超时后按“结果未知”处理（已经发出）。 */
    val toolTimeoutMillis: Long = 120_000,
    /** 交回模型的工具结果文字上限（字符），超过就截断。 */
    val maxResultChars: Int = 32_768,
)

class DefaultCapabilityBroker(
    private val host: HostPort,
    private val config: BrokerConfig = BrokerConfig(),
) : CapabilityBroker {
    /** 用户选了“本会话内不再询问”的工具：sessionId → 工具名。 */
    private val rememberedAllow = ConcurrentHashMap<String, MutableSet<String>>()

    /** PreToolUse Hook 改写后的参数：toolCallId → 参数。 */
    private val rewrittenInput = ConcurrentHashMap<String, JsonObject>()

    override fun declarations(): List<ToolDeclaration> {
        val policy = host.approvals.policy.value
        return host.tools.catalog.value.tools
            .filter { policy.resolve(it.source).enabled }
            .map { ToolDeclaration(it.name, it.description, it.inputSchema, it.title) }
    }

    /** 目录里有、且用户策略没有禁用的工具；否则 null。 */
    private fun availableTool(name: String): Pair<CatalogTool, ToolPolicy>? {
        val tool = host.tools.catalog.value[name] ?: return null
        val policy = host.approvals.policy.value.resolve(tool.source)
        return if (policy.enabled) tool to policy else null
    }

    override suspend fun authorize(ctx: ToolContext, call: ToolCall): ToolCallDecision {
        val (tool, policy) = availableTool(call.name)
            ?: return reject(ctx, call, ErrorCode.TOOL_NOT_IN_CATALOG, "Tool ${call.name} is not available.")

        val hook = host.hooks.dispatch(
            HookRequest(
                hookEvent = "PreToolUse",
                sessionId = ctx.sessionId,
                taskId = ctx.taskId,
                input = buildJsonObject {
                    put("tool_name", call.name)
                    put("tool_input", call.arguments)
                    put("tool_use_id", call.toolCallId)
                },
            ),
        )
        if (hook.matched > 0) {
            ctx.commit { tx ->
                tx.events.append(
                    PendingEvent(
                        ctx.sessionId, ctx.taskId, EventTypes.HOOK_DECIDED,
                        buildJsonObject {
                            put("hookEvent", "PreToolUse")
                            put("decision", hook.decision.name.lowercase())
                            hook.reason?.let { put("reason", it) }
                            put("inputUpdated", hook.updatedInput != null)
                            put("contextAdded", hook.additionalContext != null)
                        },
                    ),
                )
            }
        }
        if (hook.decision == HookDecision.DENY) {
            return reject(ctx, call, ErrorCode.TOOL_BLOCKED, hook.reason ?: "This tool call was blocked by a hook.", ToolCallState.REJECTED)
        }
        hook.updatedInput?.let { rewrittenInput[call.toolCallId] = it }

        val remembered = rememberedAllow[ctx.sessionId]?.contains(tool.name) == true
        return when (val need = RiskPolicy.consentRequirement(tool.risk, policy.approval, remembered, hookAsk = hook.decision == HookDecision.ASK)) {
            ConsentRequirement.ASK -> askUser(ctx, call, tool)
            ConsentRequirement.NOT_NEEDED_READ -> ToolCallDecision.Allow
            ConsentRequirement.ALWAYS_BY_POLICY, ConsentRequirement.REMEMBERED_IN_SESSION -> {
                recordConsent(ctx, call, tool, null, "allow", need.reason!!, false)
                ToolCallDecision.Allow
            }
        }
    }

    private suspend fun askUser(ctx: ToolContext, call: ToolCall, tool: CatalogTool): ToolCallDecision {
        val requestId = Ids.request()
        ctx.commit { tx ->
            tx.events.append(
                PendingEvent(
                    ctx.sessionId, ctx.taskId, EventTypes.CONSENT_REQUESTED,
                    buildJsonObject {
                        put("requestId", requestId)
                        put("toolCallId", call.toolCallId)
                        put("toolName", call.name)
                        put("risk", tool.risk.name.lowercase())
                        put("callerUid", ctx.caller.uid)
                    },
                ),
            )
        }
        val decision = host.consent.request(
            ConsentRequest(
                requestId = requestId,
                sessionId = ctx.sessionId,
                taskId = ctx.taskId,
                toolCallId = call.toolCallId,
                toolName = tool.name,
                toolTitle = tool.title,
                risk = tool.risk,
                caller = ctx.caller,
                argumentsPreview = call.arguments.toString().take(PREVIEW_CHARS),
                rememberable = RiskPolicy.maySessionRemember(tool.risk),
                source = tool.source,
            ),
        )
        return when (decision) {
            is ConsentDecision.Allow -> {
                val remember = decision.rememberForSession && RiskPolicy.maySessionRemember(tool.risk)
                if (remember) rememberedAllow.getOrPut(ctx.sessionId) { ConcurrentHashMap.newKeySet() }.add(tool.name)
                recordConsent(ctx, call, tool, requestId, "allow", "user", remember)
                ToolCallDecision.Allow
            }
            is ConsentDecision.Deny -> {
                val reason = decision.reason.name.lowercase()
                recordConsent(ctx, call, tool, requestId, "deny", reason, false)
                val text = if (decision.reason == ConsentDecision.DenyReason.TIMEOUT) {
                    "The user did not respond to the confirmation in time."
                } else {
                    "The user declined this tool call."
                }
                reject(ctx, call, ErrorCode.TOOL_DENIED, text, ToolCallState.REJECTED)
            }
        }
    }

    private suspend fun recordConsent(
        ctx: ToolContext,
        call: ToolCall,
        tool: CatalogTool,
        requestId: String?,
        decision: String,
        reason: String,
        remember: Boolean,
    ) = ctx.commit { tx ->
        tx.events.append(
            PendingEvent(
                ctx.sessionId, ctx.taskId, EventTypes.CONSENT_RESOLVED,
                buildJsonObject {
                    requestId?.let { put("requestId", it) }
                    put("toolCallId", call.toolCallId)
                    put("toolName", tool.name)
                    put("decision", decision)
                    put("reason", reason)
                    put("remember", remember)
                },
            ),
        )
    }

    override suspend fun execute(ctx: ToolContext, call: ToolCall): ToolResult {
        val tool = availableTool(call.name)?.first
        if (tool == null) {
            val decision = reject(ctx, call, ErrorCode.TOOL_NOT_IN_CATALOG, "Tool ${call.name} is not available.")
            return ToolResult.text(decision.reason, isError = true)
        }
        val arguments = rewrittenInput.remove(call.toolCallId) ?: call.arguments

        ctx.commit { tx ->
            tx.tasks.recordDispatched(ctx.taskId, ctx.sessionId, call.toolCallId, call.name, tool.provider, tx.now)
            tx.events.append(
                PendingEvent(
                    ctx.sessionId, ctx.taskId, EventTypes.TOOL_DISPATCHED,
                    buildJsonObject {
                        put("toolCallId", call.toolCallId)
                        put("name", call.name)
                        put("provider", tool.provider)
                        put("risk", tool.risk.name.lowercase())
                    },
                ),
            )
        }

        val invocation = ToolInvocation(ctx.sessionId, ctx.taskId, call.toolCallId, call.name, arguments, ctx.caller, config.toolTimeoutMillis)
        val outcome = try {
            withTimeoutOrNull(config.toolTimeoutMillis) { host.tools.invoke(invocation) }
                ?: ToolInvocationResult.Unknown(ErrorCode.TOOL_TIMEOUT.info("Tool ${call.name} did not respond in ${config.toolTimeoutMillis} ms."))
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                settle(ctx, call, ToolCallState.CANCELLED, isError = true, ErrorCode.TOOL_RESULT_UNKNOWN.info("Tool call was cancelled after dispatch."))
            }
            throw e
        } catch (e: Exception) {
            ToolInvocationResult.Unknown(ErrorCode.TOOL_RESULT_UNKNOWN.info("Tool call failed: ${e.javaClass.simpleName}"))
        }

        return when (outcome) {
            is ToolInvocationResult.Completed -> {
                settle(ctx, call, ToolCallState.COMPLETED, outcome.result.isError, null)
                outcome.result
            }
            is ToolInvocationResult.NotDispatched -> {
                settle(ctx, call, ToolCallState.NOT_DISPATCHED, isError = true, outcome.error)
                errorResult(outcome.error)
            }
            is ToolInvocationResult.Unknown -> {
                settle(ctx, call, ToolCallState.UNKNOWN, isError = true, outcome.error)
                errorResult(outcome.error)
            }
        }
    }

    override suspend fun afterExecute(ctx: ToolContext, call: ToolCall, result: ToolResult): ToolResult? {
        val total = result.content.sumOf { (it as? ContentPart.Text)?.text?.length ?: 0 }
        if (total <= config.maxResultChars) return null
        var budget = config.maxResultChars
        val content = result.content.map { part ->
            if (part !is ContentPart.Text) return@map part
            val kept = part.text.take(budget.coerceAtLeast(0))
            budget -= kept.length
            ContentPart.Text(kept)
        } + ContentPart.Text("\n[agentos:${ErrorCode.TOOL_RESULT_TOO_LARGE.wire}] Result truncated from $total to ${config.maxResultChars} characters.")
        return result.copy(content = content)
    }

    /** 会话结束时清掉“本会话内不再询问”。 */
    fun forgetSession(sessionId: String) {
        rememberedAllow.remove(sessionId)
    }

    private suspend fun settle(ctx: ToolContext, call: ToolCall, state: ToolCallState, isError: Boolean, error: ErrorInfo?) =
        ctx.commit { tx ->
            tx.tasks.recordSettled(ctx.taskId, ctx.sessionId, call.toolCallId, call.name, state, tx.now)
            tx.events.append(
                PendingEvent(
                    ctx.sessionId, ctx.taskId, EventTypes.TOOL_SETTLED,
                    buildJsonObject {
                        put("toolCallId", call.toolCallId)
                        put("outcome", state.wire)
                        put("isError", isError)
                    },
                    error,
                ),
            )
        }

    private suspend fun reject(
        ctx: ToolContext,
        call: ToolCall,
        code: ErrorCode,
        message: String,
        state: ToolCallState = ToolCallState.REJECTED,
    ): ToolCallDecision.Block {
        settle(ctx, call, state, isError = true, code.info(message))
        return ToolCallDecision.Block(modelText(code, message))
    }

    companion object {
        const val PREVIEW_CHARS = 2_000

        /** errors.md 第 6 节：交回模型的错误文字。 */
        fun modelText(code: ErrorCode, message: String) = "[agentos:${code.wire}] $message"

        fun errorResult(error: ErrorInfo) = ToolResult.text(modelText(error.code, error.message), isError = true)
    }
}
