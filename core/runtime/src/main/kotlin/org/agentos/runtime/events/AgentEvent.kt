package org.agentos.runtime.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pi Agent core 的生命周期事件（`@earendil-works/pi-agent-core` 0.86.1 的 `AgentEvent`），
 * 采用 S8 在 JS 侧压缩后的形状（docs/spikes/S8.md「给 W3 / W6 的设计要点」）：
 * `message_update` 只带增量，不带整条 partial message。
 *
 * JSON 形状见 core/contracts/events.md 第 3 节。B lane 的 PiEventMapper 把 JS 发来的 JSON 用 [decode] 转成这里的类型；
 * 宿主层把它们写进事件日志（eventType = [type]，payload = [payload]）。
 */
@Serializable
sealed interface AgentEvent {
    /** 事件名，也是事件日志里的 eventType。 */
    val type: String

    @Serializable
    @SerialName(EventTypes.AGENT_START)
    data object AgentStart : AgentEvent {
        override val type get() = EventTypes.AGENT_START
    }

    /** 一次 prompt 的 Agent 循环结束。之后适配层才让 runTurn 返回（ACP v1：本轮结束才返回 stopReason）。 */
    @Serializable
    @SerialName(EventTypes.AGENT_END)
    data class AgentEnd(
        /** 结束时会话里的 messages 条数。 */
        val messages: Int? = null,
    ) : AgentEvent {
        override val type get() = EventTypes.AGENT_END
    }

    @Serializable
    @SerialName(EventTypes.TURN_START)
    data object TurnStart : AgentEvent {
        override val type get() = EventTypes.TURN_START
    }

    /** 一次模型往返（含这次往返触发的工具调用）结束。 */
    @Serializable
    @SerialName(EventTypes.TURN_END)
    data class TurnEnd(
        /** Pi 的 stopReason：stop / length / toolUse / error / aborted。 */
        val stopReason: String? = null,
        val errorMessage: String? = null,
        /** 这次往返产生的工具结果条数。 */
        val toolResults: Int = 0,
    ) : AgentEvent {
        override val type get() = EventTypes.TURN_END
    }

    @Serializable
    @SerialName(EventTypes.MESSAGE_START)
    data class MessageStart(
        /** user / assistant / toolResult / system */
        val role: String? = null,
    ) : AgentEvent {
        override val type get() = EventTypes.MESSAGE_START
    }

    @Serializable
    @SerialName(EventTypes.MESSAGE_UPDATE)
    data class MessageUpdate(
        val role: String? = null,
        val update: AssistantUpdate,
    ) : AgentEvent {
        override val type get() = EventTypes.MESSAGE_UPDATE
    }

    /** 一条消息完整结束；[message] 是 Pi 的完整消息对象（pi-ai `Message`）。 */
    @Serializable
    @SerialName(EventTypes.MESSAGE_END)
    data class MessageEnd(
        val message: JsonObject,
    ) : AgentEvent {
        override val type get() = EventTypes.MESSAGE_END

        val role: String? get() = (message["role"] as? JsonPrimitive)?.contentOrNull
    }

    @Serializable
    @SerialName(EventTypes.TOOL_EXECUTION_START)
    data class ToolExecutionStart(
        val toolCallId: String,
        val toolName: String,
        val args: JsonElement? = null,
    ) : AgentEvent {
        override val type get() = EventTypes.TOOL_EXECUTION_START
    }

    @Serializable
    @SerialName(EventTypes.TOOL_EXECUTION_UPDATE)
    data class ToolExecutionUpdate(
        val toolCallId: String,
        val toolName: String,
        val partialResult: JsonElement? = null,
    ) : AgentEvent {
        override val type get() = EventTypes.TOOL_EXECUTION_UPDATE
    }

    /** [result] 是 Pi 的 `AgentToolResult`（`{content, details}`），已经过 afterToolCall。 */
    @Serializable
    @SerialName(EventTypes.TOOL_EXECUTION_END)
    data class ToolExecutionEnd(
        val toolCallId: String,
        val toolName: String,
        val result: JsonElement? = null,
        val isError: Boolean = false,
    ) : AgentEvent {
        override val type get() = EventTypes.TOOL_EXECUTION_END
    }

    /** 本版本不认识的 Pi 事件：原样保留，写进日志，不对外映射。 */
    data class Other(
        override val type: String,
        val raw: JsonObject,
    ) : AgentEvent

    companion object {
        private val known = setOf(
            EventTypes.AGENT_START, EventTypes.AGENT_END, EventTypes.TURN_START, EventTypes.TURN_END,
            EventTypes.MESSAGE_START, EventTypes.MESSAGE_UPDATE, EventTypes.MESSAGE_END,
            EventTypes.TOOL_EXECUTION_START, EventTypes.TOOL_EXECUTION_UPDATE, EventTypes.TOOL_EXECUTION_END,
        )

        /** 解码 S8 压缩形状的 Pi 事件（带 `type`）。未知的 `type` 得到 [Other]，不抛异常。 */
        fun decode(json: JsonObject): AgentEvent {
            val type = json["type"]?.jsonPrimitive?.contentOrNull ?: return Other("unknown", json)
            if (type !in known) return Other(type, json)
            return RuntimeJson.decodeFromJsonElement(serializer(), json)
        }

        /** 编码为带 `type` 的 JSON，与 [decode] 互逆。 */
        fun encode(event: AgentEvent): JsonObject = when (event) {
            is Other -> event.raw
            else -> RuntimeJson.encodeToJsonElement(serializer(), event).jsonObject
        }
    }
}

/**
 * `message_update` 里的增量（pi-ai 的 `AssistantMessageEvent` 去掉 `partial` 之后）。
 *
 * [kind]：start、text_start、text_delta、text_end、thinking_start、thinking_delta、thinking_end、
 * toolcall_start、toolcall_delta、toolcall_end、done、error。
 */
@Serializable
data class AssistantUpdate(
    @SerialName("type") val kind: String,
    val contentIndex: Int? = null,
    val delta: String? = null,
    /** 只在 toolcall_end 上：完整的 `{type:"toolCall", id, name, arguments}`。 */
    val toolCall: JsonObject? = null,
    /** 只在 done / error 上：Pi 的 stopReason。 */
    val reason: String? = null,
) {
    companion object {
        const val TEXT_DELTA = "text_delta"
        const val THINKING_DELTA = "thinking_delta"
        const val TOOLCALL_DELTA = "toolcall_delta"
        const val TOOLCALL_END = "toolcall_end"
        const val DONE = "done"
        const val ERROR = "error"
    }
}

/** 事件日志里的 payload：事件 JSON 去掉 `type`（它已经在信封的 eventType 里）。 */
fun AgentEvent.payload(): JsonObject {
    val full = AgentEvent.encode(this)
    return buildJsonObject { full.forEach { (k, v) -> if (k != "type") put(k, v) } }
}
