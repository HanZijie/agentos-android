package org.agentos.runtime.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.agentos.runtime.errors.ErrorInfo

/**
 * 内部事件的信封（core/contracts/events.md 第 2 节）。事件日志（store/EventLog，W2）里的一行就是一个信封。
 *
 * - [sequence]：按 [sessionId] 独立、从 1 开始、每条 +1、不留空洞、永不复用；由 EventLog 在写入的同一个事务里分配。
 * - [timestamp]：写入时 HostPort.clock 的墙钟毫秒，只用于展示；先后顺序只看 sequence。
 * - [taskId]：会话级事件（session.*）和系统流事件为 null。
 * - [payload]：不得包含 key、请求头、凭据；大字段按 events.md 第 5 节截断。
 * - [error]：只在 task.failed、tool.settled（失败时）、hook.failed、agent_core.failed 上出现。
 */
@Serializable
data class EventEnvelope(
    @SerialName("v") val protocolVersion: Int = PROTOCOL_VERSION,
    val sessionId: String,
    val taskId: String? = null,
    val sequence: Long,
    val eventType: String,
    val timestamp: Long,
    val payload: JsonObject = EMPTY_PAYLOAD,
    val error: ErrorInfo? = null,
) {
    init {
        require(sequence >= 1) { "sequence starts at 1" }
        require(sessionId.isNotEmpty()) { "sessionId must not be empty" }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        val EMPTY_PAYLOAD = JsonObject(emptyMap())
    }
}

/**
 * 还没分配 sequence 的事件：宿主层各处产生它，交给 EventLog.append（W2），由 EventLog 分配 sequence 和 timestamp。
 */
data class PendingEvent(
    val sessionId: String,
    val taskId: String?,
    val eventType: String,
    val payload: JsonObject = EventEnvelope.EMPTY_PAYLOAD,
    val error: ErrorInfo? = null,
) {
    companion object {
        /** Pi 生命周期事件 → 待写入的事件。 */
        fun of(sessionId: String, taskId: String?, event: AgentEvent): PendingEvent =
            PendingEvent(sessionId, taskId, event.type, event.payload())
    }
}
