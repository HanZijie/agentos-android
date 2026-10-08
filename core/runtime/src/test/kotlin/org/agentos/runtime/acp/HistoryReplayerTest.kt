@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.runtime.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.events.EventEnvelope
import org.agentos.runtime.events.EventTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [HistoryReplayer]：事件日志 → `session/update` 的重放规则（core/protocol/acp-mapping.md 第 4a 节）。 */
class HistoryReplayerTest {
    private var seq = 0L

    private fun event(type: String, task: String?, payload: JsonObject = JsonObject(emptyMap())) =
        EventEnvelope(sessionId = "ses_1", taskId = task, sequence = ++seq, eventType = type, timestamp = 1_700_000_000_000L + seq, payload = payload)

    private fun queued(task: String, vararg blocks: JsonObject) = event(EventTypes.TASK_QUEUED, task, buildJsonObject { put("input", JsonArray(blocks.toList())) })

    private fun text(t: String) = buildJsonObject { put("type", "text"); put("text", t) }

    private fun delta(task: String, kind: String, d: String) =
        event(EventTypes.MESSAGE_UPDATE, task, buildJsonObject { put("role", "assistant"); put("update", buildJsonObject { put("type", kind); put("delta", d) }) })

    private fun toolStart(task: String, id: String) = event(EventTypes.TOOL_EXECUTION_START, task, buildJsonObject { put("toolCallId", id); put("toolName", "add"); put("args", buildJsonObject { put("a", 1) }) })

    private fun toolEnd(task: String, id: String, isError: Boolean = false) = event(
        EventTypes.TOOL_EXECUTION_END, task,
        buildJsonObject {
            put("toolCallId", id)
            put("isError", isError)
            put("result", buildJsonObject { put("content", buildJsonArray { add(text("3")) }) })
        },
    )

    private fun replay(taskIds: Set<String>? = null, vararg events: EventEnvelope): List<SessionUpdate> {
        val r = HistoryReplayer(taskIds)
        return events.flatMap { r.map(it) }
    }

    @Test
    fun `a turn becomes the user message, the thinking, the answer and the tool call in order`() {
        val updates = replay(
            null,
            queued("t1", text("what is 1+2?")),
            event(EventTypes.TASK_STARTED, "t1"),
            delta("t1", "thinking_delta", "let me add"),
            toolStart("t1", "c1"),
            toolEnd("t1", "c1"),
            delta("t1", "text_delta", "3"),
            event(EventTypes.TASK_COMPLETED, "t1"),
        )
        assertEquals(
            listOf("UserMessageChunk", "AgentThoughtChunk", "ToolCall", "ToolCallUpdate", "AgentMessageChunk"),
            updates.map { it::class.simpleName },
        )
        assertEquals(ToolCallStatus.COMPLETED, (updates[3] as SessionUpdate.ToolCallUpdate).status)
    }

    @Test
    fun `blocks the replay does not understand are skipped, the rest of the history is not lost`() {
        val updates = replay(null, queued("t1", text("kept"), buildJsonObject { put("type", "hologram"); put("data", "x") }))
        val only = updates.single()
        assertIs<SessionUpdate.UserMessageChunk>(only)
        assertEquals("kept", (only.content as ContentBlock.Text).text)
    }

    @Test
    fun `a tool call that never finished is closed as failed when its turn ends`() {
        for (end in listOf(EventTypes.TASK_FAILED, EventTypes.TASK_CANCELLED, EventTypes.TASK_RECOVERY_REQUIRED, EventTypes.TASK_COMPLETED)) {
            val updates = replay(null, queued("t1", text("go")), toolStart("t1", "c1"), toolStart("t1", "c2"), toolEnd("t1", "c1"), event(end, "t1"))
            val closing = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().filter { it.status == ToolCallStatus.FAILED }
            assertEquals(1, closing.size, end)
            assertEquals("c2", closing.single().toolCallId.value, end)
        }
    }

    @Test
    fun `a turn that is still running is replayed as far as it got, with no invented ending`() {
        val updates = replay(null, queued("t1", text("go")), toolStart("t1", "c1"), delta("t1", "text_delta", "part"))
        assertEquals(listOf("UserMessageChunk", "ToolCall", "AgentMessageChunk"), updates.map { it::class.simpleName })
    }

    @Test
    fun `only the wanted tasks are replayed, and events that belong to no task are not`() {
        val updates = replay(
            setOf("t2"),
            event(EventTypes.SESSION_CREATED, null),
            queued("t1", text("old")),
            delta("t1", "text_delta", "old answer"),
            queued("t2", text("recent")),
            delta("t2", "text_delta", "recent answer"),
        )
        assertEquals(2, updates.size)
        assertEquals("recent", ((updates[0] as SessionUpdate.UserMessageChunk).content as ContentBlock.Text).text)
    }

    @Test
    fun `consent, hook and scheduling events are not replayed`() {
        val updates = replay(
            null,
            event(EventTypes.TASK_STARTED, "t1"),
            event(EventTypes.CONSENT_REQUESTED, "t1"),
            event(EventTypes.CONSENT_RESOLVED, "t1"),
            event(EventTypes.HOOK_DECIDED, "t1"),
            event(EventTypes.TASK_COMPLETED, "t1"),
        )
        assertTrue(updates.isEmpty(), updates.toString())
    }
}
