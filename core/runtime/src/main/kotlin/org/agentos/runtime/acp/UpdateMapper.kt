// ACP SDK 0.30.1 把部分模型类的构造参数标为 UnstableApi；版本已锁定（libs.versions.toml），升级时整体复核。
@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.runtime.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.agentos.runtime.events.EventEnvelope
import org.agentos.runtime.events.EventTypes

/**
 * 内部事件 → ACP `session/update`（core/protocol/acp-mapping.md 第 3 节）。一个实例对应一轮 prompt。
 *
 * - 文字 / thinking 增量已由宿主层按 32 ms 合并（events.md 6.3）；这里再按 [maxChunkChars] 切分，保证一条 JSON-RPC 消息
 *   即使在最坏的 JSON 转义（每个字符 6 倍）下也不超过 binder-channel-v1 的单条上限 65,536 字符。
 * - 工具结果的文字按 [maxToolTextChars] 截断后放进 `tool_call_update.content`；完整结果只交给模型。
 * - 其他事件（确认、Hook、工具派发之外的宿主层事件、系统流）不对外映射。
 */
class UpdateMapper(
    private val maxChunkChars: Int = DEFAULT_MAX_CHUNK_CHARS,
    private val maxToolTextChars: Int = DEFAULT_MAX_TOOL_TEXT_CHARS,
) {
    init {
        require(maxChunkChars in 1..10_000) { "a chunk must stay under 65,536 chars even when every char is escaped" }
    }

    fun map(event: EventEnvelope): List<SessionUpdate> {
        val p = event.payload
        return when (event.eventType) {
            EventTypes.MESSAGE_UPDATE -> {
                if (p.str("role") != "assistant") return emptyList()
                val update = p["update"] as? JsonObject ?: return emptyList()
                val delta = update.str("delta") ?: return emptyList()
                when (update.str("type")) {
                    "text_delta" -> chunks(delta).map { SessionUpdate.AgentMessageChunk(ContentBlock.Text(it)) }
                    "thinking_delta" -> chunks(delta).map { SessionUpdate.AgentThoughtChunk(ContentBlock.Text(it)) }
                    else -> emptyList()
                }
            }
            EventTypes.TOOL_EXECUTION_START -> {
                val id = p.str("toolCallId") ?: return emptyList()
                listOf(
                    SessionUpdate.ToolCall(
                        toolCallId = ToolCallId(id),
                        title = p.str("toolName") ?: "tool",
                        kind = ToolKind.OTHER,
                        status = ToolCallStatus.PENDING,
                        rawInput = p["args"],
                    ),
                )
            }
            // 确认通过、真正开始调用工具提供方
            EventTypes.TOOL_DISPATCHED -> {
                val id = p.str("toolCallId") ?: return emptyList()
                listOf(SessionUpdate.ToolCallUpdate(ToolCallId(id), status = ToolCallStatus.IN_PROGRESS))
            }
            EventTypes.TOOL_EXECUTION_UPDATE -> {
                val id = p.str("toolCallId") ?: return emptyList()
                val text = resultText(p["partialResult"])
                listOf(
                    SessionUpdate.ToolCallUpdate(
                        ToolCallId(id),
                        status = ToolCallStatus.IN_PROGRESS,
                        content = text?.let { listOf(ToolCallContent.Content(ContentBlock.Text(it))) },
                    ),
                )
            }
            EventTypes.TOOL_EXECUTION_END -> {
                val id = p.str("toolCallId") ?: return emptyList()
                val isError = (p["isError"] as? JsonPrimitive)?.booleanOrNull == true
                val text = resultText(p["result"])
                listOf(
                    SessionUpdate.ToolCallUpdate(
                        ToolCallId(id),
                        status = if (isError) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED,
                        content = text?.let { listOf(ToolCallContent.Content(ContentBlock.Text(it))) },
                    ),
                )
            }
            else -> emptyList()
        }
    }

    private fun chunks(text: String): List<String> {
        if (text.length <= maxChunkChars) return listOf(text)
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            var end = minOf(i + maxChunkChars, text.length)
            // 不把代理对切开
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            out += text.substring(i, end)
            i = end
        }
        return out
    }

    /** Pi 的 `{content: [...], details}` → 文字；图片记为占位。 */
    private fun resultText(result: JsonElement?): String? {
        val content = (result as? JsonObject)?.get("content") as? JsonArray ?: return null
        val text = content.joinToString("") { b ->
            val o = b as? JsonObject ?: return@joinToString ""
            when (o.str("type")) {
                "text" -> o.str("text").orEmpty()
                "image" -> "[image]"
                else -> ""
            }
        }
        if (text.isEmpty()) return null
        return if (text.length <= maxToolTextChars) text else text.take(maxToolTextChars) + "…[truncated ${text.length} chars]"
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** 8,192 × 6（最坏转义）≈ 49,152 字符，加上信封仍低于 65,536。 */
        const val DEFAULT_MAX_CHUNK_CHARS = 8_192
        const val DEFAULT_MAX_TOOL_TEXT_CHARS = 8_192
    }
}
