package org.agentos.runtime.events

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.agentos.runtime.errors.ErrorCode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentEventTest {
    private fun obj(json: String): JsonObject = RuntimeJson.parseToJsonElement(json).jsonObject

    /** S8 的 entry.js `compactEvent` 产生的形状（docs/spikes/S8.md）。 */
    private val s8Samples = listOf(
        """{"type":"agent_start"}""",
        """{"type":"turn_start"}""",
        """{"type":"message_start","role":"assistant"}""",
        """{"type":"message_update","role":"assistant","update":{"type":"text_delta","contentIndex":0,"delta":"你好 👋"}}""",
        """{"type":"message_update","role":"assistant","update":{"type":"toolcall_end","contentIndex":1,"toolCall":{"type":"toolCall","id":"c1","name":"add","arguments":{"a":2,"b":3}}}}""",
        """{"type":"message_update","role":"assistant","update":{"type":"done","contentIndex":0,"reason":"toolUse"}}""",
        """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"hi"}],"stopReason":"stop"}}""",
        """{"type":"tool_execution_start","toolCallId":"c1","toolName":"add","args":{"a":2,"b":3}}""",
        """{"type":"tool_execution_update","toolCallId":"c1","toolName":"add","partialResult":{"n":1}}""",
        """{"type":"tool_execution_end","toolCallId":"c1","toolName":"add","result":{"content":[{"type":"text","text":"5"}]},"isError":false}""",
        """{"type":"turn_end","stopReason":"toolUse","toolResults":1}""",
        """{"type":"agent_end","messages":5}""",
    )

    @Test
    fun `S8 compact events decode and re-encode unchanged`() {
        for (sample in s8Samples) {
            val json = obj(sample)
            val event = AgentEvent.decode(json)
            assertTrue(event !is AgentEvent.Other, "known event: $sample")
            assertEquals(json, AgentEvent.encode(event), sample)
            assertEquals(json["type"], JsonPrimitive(event.type))
        }
    }

    @Test
    fun `unknown Pi events are preserved as Other`() {
        val json = obj("""{"type":"compaction_start","reason":"threshold"}""")
        val event = AgentEvent.decode(json)
        assertIs<AgentEvent.Other>(event)
        assertEquals("compaction_start", event.type)
        assertEquals(json, AgentEvent.encode(event))
    }

    @Test
    fun `payload drops the type field and PendingEvent carries it as eventType`() {
        val event = AgentEvent.decode(obj(s8Samples[3]))
        val pending = PendingEvent.of("ses_1", "tsk_1", event)
        assertEquals(EventTypes.MESSAGE_UPDATE, pending.eventType)
        assertFalse("type" in pending.payload)
        assertEquals("assistant", (pending.payload["role"] as JsonPrimitive).content)
    }

    @Test
    fun `envelope serializes like the events_md example`() {
        val envelope = EventEnvelope(
            sessionId = "ses_01J9Z6Q7F3",
            taskId = "tsk_01J9Z6Q9K2",
            sequence = 57,
            eventType = EventTypes.TASK_FAILED,
            timestamp = 1790620003456,
            payload = obj("""{"attempt":1,"attemptState":"failed"}"""),
            error = ErrorCode.MODEL_RATE_LIMITED.info("provider returned 429"),
        )
        val json = RuntimeJson.encodeToString(EventEnvelope.serializer(), envelope)
        assertEquals(
            """{"v":1,"sessionId":"ses_01J9Z6Q7F3","taskId":"tsk_01J9Z6Q9K2","sequence":57,"eventType":"task.failed",""" +
                """"timestamp":1790620003456,"payload":{"attempt":1,"attemptState":"failed"},""" +
                """"error":{"code":"model_rate_limited","message":"provider returned 429","retryable":true}}""",
            json,
        )
        assertEquals(envelope, RuntimeJson.decodeFromString(EventEnvelope.serializer(), json))
        assertFailsWith<IllegalArgumentException> { envelope.copy(sequence = 0) }
    }

    @Test
    fun `every event type is documented in events_md`() {
        val doc = File("../contracts/events.md").readText()
        (EventTypes.PI_LIFECYCLE + EventTypes.HOST + EventTypes.SYSTEM_STREAM).forEach { name ->
            assertTrue("`$name`" in doc, "events.md documents `$name`")
        }
        assertTrue(EventTypes.PI_LIFECYCLE.intersect(EventTypes.HOST).isEmpty())
    }
}
