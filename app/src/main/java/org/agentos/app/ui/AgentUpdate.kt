package org.agentos.app.ui

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus

/**
 * What the conversation screen needs from one ACP turn, independent of the SDK types, so the state
 * logic ([ChatReducer]) is plain Kotlin. [fromSdk] maps `session/update` notifications
 * (docs/architecture.md 5.6: agent_message_chunk, agent_thought_chunk, tool_call, tool_call_update).
 */
sealed interface AgentUpdate {
    data class MessageChunk(val text: String) : AgentUpdate
    data class ThoughtChunk(val text: String) : AgentUpdate
    data class ToolCallStarted(
        val callId: String,
        val title: String,
        val kind: String?,
        val status: ToolStatus,
        val detail: String?,
    ) : AgentUpdate

    /** Fields are null when the update does not change them (ACP tool_call_update is a patch). */
    data class ToolCallUpdated(
        val callId: String,
        val title: String?,
        val status: ToolStatus?,
        val detail: String?,
    ) : AgentUpdate

    /** Plans, usage, mode changes …: not shown in M1. */
    data class Ignored(val type: String) : AgentUpdate

    companion object {
        fun fromSdk(update: SessionUpdate): AgentUpdate = when (update) {
            is SessionUpdate.AgentMessageChunk -> MessageChunk(update.content.displayText())
            is SessionUpdate.AgentThoughtChunk -> ThoughtChunk(update.content.displayText())
            is SessionUpdate.ToolCall -> ToolCallStarted(
                callId = update.toolCallId.value,
                title = update.title.ifBlank { "工具调用" },
                kind = update.kind?.name?.lowercase(),
                status = ToolStatus.from(update.status) ?: ToolStatus.PENDING,
                detail = update.content?.detailText(),
            )
            is SessionUpdate.ToolCallUpdate -> ToolCallUpdated(
                callId = update.toolCallId.value,
                title = update.title?.takeIf { it.isNotBlank() },
                status = ToolStatus.from(update.status),
                detail = update.content?.detailText(),
            )
            else -> Ignored(update::class.simpleName ?: "unknown")
        }

        private fun ContentBlock.displayText(): String = when (this) {
            is ContentBlock.Text -> text
            is ContentBlock.Image -> "［图片］"
            is ContentBlock.Audio -> "［音频］"
            is ContentBlock.ResourceLink -> "［链接：$name］"
            is ContentBlock.Resource -> "［资源］"
        }

        private fun List<ToolCallContent>.detailText(): String? =
            filterIsInstance<ToolCallContent.Content>()
                .joinToString("\n") { it.content.displayText() }
                .takeIf { it.isNotBlank() }
    }
}

enum class ToolStatus {
    PENDING, IN_PROGRESS, COMPLETED, FAILED;

    companion object {
        fun from(s: ToolCallStatus?): ToolStatus? = when (s) {
            ToolCallStatus.PENDING -> PENDING
            ToolCallStatus.IN_PROGRESS -> IN_PROGRESS
            ToolCallStatus.COMPLETED -> COMPLETED
            ToolCallStatus.FAILED -> FAILED
            null -> null
        }
    }
}

/** How one turn ended. */
sealed interface TurnOutcome {
    /** `session/prompt` returned; [stopReason] is the ACP stop reason in wire form (end_turn, cancelled …). */
    data class Finished(val stopReason: String) : TurnOutcome

    /** The prompt failed: JSON-RPC error, connection lost, or the service could not be reached. */
    data class Failed(val error: AgentError) : TurnOutcome

    companion object {
        fun stopReasonWire(r: StopReason): String = when (r) {
            StopReason.END_TURN -> "end_turn"
            StopReason.MAX_TOKENS -> "max_tokens"
            StopReason.MAX_TURN_REQUESTS -> "max_turn_requests"
            StopReason.REFUSAL -> "refusal"
            StopReason.CANCELLED -> "cancelled"
        }
    }
}

/** Maps one SDK event of a prompt flow; returns the stop reason for the final response event. */
internal fun Event.toUi(): Pair<AgentUpdate?, String?> = when (this) {
    is Event.SessionUpdateEvent -> AgentUpdate.fromSdk(update) to null
    is Event.PromptResponseEvent -> null to TurnOutcome.stopReasonWire(response.stopReason)
}
