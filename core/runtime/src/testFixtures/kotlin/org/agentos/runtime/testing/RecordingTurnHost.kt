package org.agentos.runtime.testing

import kotlinx.coroutines.delay
import org.agentos.runtime.events.AgentEvent
import org.agentos.runtime.ports.ToolCall
import org.agentos.runtime.ports.ToolCallDecision
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.TurnHost
import java.util.Collections

/**
 * 记录一切的 TurnHost：测试 FakeAgentCore，也给 B lane 测 PiAdapter 用（同一组断言可以对真假两个实现各跑一遍）。
 *
 * @param tools 工具名 → 实现；没有的工具返回 isError 的结果。
 * @param decide beforeToolCall 的决定，默认放行。
 * @param after afterToolCall 的改写，默认不改。
 */
class RecordingTurnHost(
    private val tools: Map<String, suspend (ToolCall) -> ToolResult> = emptyMap(),
    private val decide: suspend (ToolCall) -> ToolCallDecision = { ToolCallDecision.Allow },
    private val after: suspend (ToolCall, ToolResult) -> ToolResult? = { _, _ -> null },
) : TurnHost {
    val events: MutableList<AgentEvent> = Collections.synchronizedList(mutableListOf())

    /** 按发生顺序记录回调：before:<id>、execute:<id>、after:<id>、cancelled:<id>。 */
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun onEvent(event: AgentEvent) {
        events += event
    }

    override suspend fun beforeToolCall(call: ToolCall): ToolCallDecision {
        calls += "before:${call.toolCallId}"
        return decide(call)
    }

    override suspend fun executeTool(call: ToolCall): ToolResult {
        calls += "execute:${call.toolCallId}"
        val impl = tools[call.name] ?: return ToolResult.text("unknown tool ${call.name}", isError = true)
        try {
            return impl(call)
        } catch (e: kotlinx.coroutines.CancellationException) {
            calls += "cancelled:${call.toolCallId}"
            throw e
        }
    }

    override suspend fun afterToolCall(call: ToolCall, result: ToolResult): ToolResult? {
        calls += "after:${call.toolCallId}"
        return after(call, result)
    }

    fun eventTypes(): List<String> = synchronized(events) { events.map { it.type } }

    /** 所有 text_delta 拼起来的文字。 */
    fun streamedText(): String = synchronized(events) {
        events.filterIsInstance<AgentEvent.MessageUpdate>()
            .filter { it.update.kind == "text_delta" }
            .joinToString("") { it.update.delta.orEmpty() }
    }

    companion object {
        /** 一个永远不返回、直到被取消的工具。 */
        val HANGING_TOOL: suspend (ToolCall) -> ToolResult = {
            delay(Long.MAX_VALUE)
            error("unreachable")
        }
    }
}
