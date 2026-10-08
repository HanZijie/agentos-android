// ACP SDK 0.30.1 把部分模型类的构造参数标为 UnstableApi；版本已锁定（libs.versions.toml），升级时整体复核。
@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.runtime.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.agentos.runtime.events.EventEnvelope
import org.agentos.runtime.events.EventTypes

/**
 * 历史重放（ACP `session/load`）：把事件日志里一个会话已经提交的事件按顺序变成 `session/update`，
 * 让重新连上来的客户端看到完整的对话。一个实例对应一次重放，事件必须按 sequence 顺序喂进来。
 *
 * - 用户输入：`task.queued` 的 `input`（提交时原样存下的 ContentBlock 数组）→ `user_message_chunk`；
 * - 助手文字、思考、工具调用：和实时的一轮用同一个 [UpdateMapper]，所以重放出来的和当时流出来的一模一样；
 * - 一轮没有正常收尾（失败、取消、结果未知）时，它里面还停在“进行中”的工具调用补一条 `failed`，不让客户端看到永远转圈的工具；
 * - 确认、Hook、调度这些宿主层事件不重放（实时的一轮也不发）。
 *
 * 只重放 [taskIds] 里的任务（null = 全部）：会话很长时，调用方只取最近的若干轮（[AcpConfig.maxReplayTurns]），
 * 重放量有上限，不会因为一个会话历史很长而占住连接。
 */
internal class HistoryReplayer(private val taskIds: Set<String>? = null) {
    private val mapper = UpdateMapper()

    /** 每个任务里已经开始、还没结束的工具调用。 */
    private val openTools = HashMap<String, LinkedHashSet<String>>()

    fun map(e: EventEnvelope): List<SessionUpdate> {
        val task = e.taskId ?: return emptyList()
        if (taskIds != null && task !in taskIds) return emptyList()
        return when (e.eventType) {
            EventTypes.TASK_QUEUED -> userMessage(e)
            EventTypes.TOOL_EXECUTION_START -> {
                (e.payload["toolCallId"] as? JsonPrimitive)?.contentOrNull?.let { openTools.getOrPut(task) { LinkedHashSet() }.add(it) }
                mapper.map(e)
            }
            EventTypes.TOOL_EXECUTION_END -> {
                (e.payload["toolCallId"] as? JsonPrimitive)?.contentOrNull?.let { openTools[task]?.remove(it) }
                mapper.map(e)
            }
            EventTypes.TASK_COMPLETED, EventTypes.TASK_CANCELLED, EventTypes.TASK_FAILED, EventTypes.TASK_RECOVERY_REQUIRED -> closeOpenTools(task, e.eventType)
            else -> mapper.map(e)
        }
    }

    private fun userMessage(e: EventEnvelope): List<SessionUpdate> {
        val input = e.payload["input"] as? JsonArray ?: return emptyList()
        return input.mapNotNull { el ->
            val block = try {
                ACPJson.decodeFromJsonElement(ContentBlock.serializer(), el)
            } catch (ex: Exception) {
                // 看不懂的块（以后的版本多出来的类型）：不让整段历史重放失败，跳过
                return@mapNotNull null
            }
            SessionUpdate.UserMessageChunk(block)
        }
    }

    private fun closeOpenTools(task: String, terminal: String): List<SessionUpdate> {
        val open = openTools.remove(task) ?: return emptyList()
        if (open.isEmpty()) return emptyList()
        val why = if (terminal == EventTypes.TASK_COMPLETED) "the tool call did not report a result" else "the turn ended before the tool call finished"
        return open.map { id ->
            SessionUpdate.ToolCallUpdate(
                ToolCallId(id),
                status = ToolCallStatus.FAILED,
                content = listOf(ToolCallContent.Content(ContentBlock.Text("[agentos:tool_result_unknown] $why"))),
            )
        }
    }
}
