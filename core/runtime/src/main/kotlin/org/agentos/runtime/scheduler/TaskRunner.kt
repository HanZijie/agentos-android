@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.agentos.runtime.scheduler

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.agentos.runtime.broker.CapabilityBroker
import org.agentos.runtime.broker.ToolContext
import org.agentos.runtime.events.AgentEvent
import org.agentos.runtime.events.AssistantUpdate
import org.agentos.runtime.events.PendingEvent
import org.agentos.runtime.ports.AgentCoreSession
import org.agentos.runtime.ports.ToolCall
import org.agentos.runtime.ports.ToolCallDecision
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.TurnHost
import org.agentos.runtime.ports.TurnInput
import org.agentos.runtime.ports.TurnOutcome
import org.agentos.runtime.store.Store
import org.agentos.runtime.store.StoreTx
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 执行一次任务（一轮 prompt）：把输入交给会话的 Pi `Agent`，把 Pi 事件和宿主层事件按发生顺序写进事件日志。
 *
 * 所有写操作经过一个有序队列（[writes]），由 [writer] 串行提交：
 * - Pi 的文字 / thinking 增量按 events.md 6.3 合并后写入（32 ms 或 8,192 字符）；不写入的增量类型直接丢弃；
 * - Broker 的写操作（tool.dispatched 等）排在同一个队列里，提交后才返回——既保证落盘先于调用工具，也保证顺序；
 * - 每个 turn_end 之后保存一次 Pi messages（检查点），轮次结束时由 Scheduler 保存最终版本。
 *
 * 一次执行被判定为“结果未知”（[fence]）之后，这里不再写任何东西。
 */
internal class TaskRunner(
    val sessionId: String,
    val taskId: String,
    val ownerKey: String,
    private val toolContextCaller: org.agentos.runtime.ports.CallerIdentity,
    private val core: AgentCoreSession,
    private val broker: CapabilityBroker,
    private val store: Store,
    private val config: SchedulerConfig,
) {
    private val writes = Channel<Write>(Channel.UNLIMITED)
    private val fenced = AtomicBoolean(false)

    /** 当前是否在执行工具（取消时区分 phase）。 */
    @Volatile var inTool: Boolean = false
        private set

    /** 最后一条以 stop 结束的 assistant 消息的文字（会话选择元数据）。 */
    @Volatile var finalAnswer: String = ""
        private set

    private sealed interface Write {
        data class Pi(val event: AgentEvent) : Write

        class Commit(val block: (StoreTx) -> Unit, val ack: CompletableDeferred<Unit>) : Write
    }

    /** 标记为结果未知：之后的事件和写操作都丢弃。返回是否是第一次标记。 */
    fun fence(): Boolean = fenced.compareAndSet(false, true).also { if (it) writes.close() }

    val isFenced: Boolean get() = fenced.get()

    /** 执行这一轮，返回 Agent core 的结局。事件在返回前全部提交。 */
    suspend fun run(input: TurnInput): TurnOutcome = coroutineScope {
        val writerJob = launch { writer() }
        val ctx = ToolContext(sessionId, taskId, toolContextCaller) { block ->
            val ack = CompletableDeferred<Unit>()
            if (writes.trySend(Write.Commit(block, ack)).isSuccess) ack.await()
        }
        val host = object : TurnHost {
            override fun onEvent(event: AgentEvent) {
                if (event is AgentEvent.MessageEnd && event.role == "assistant") captureAnswer(event.message)
                writes.trySend(Write.Pi(event))
            }

            override suspend fun beforeToolCall(call: ToolCall): ToolCallDecision = broker.authorize(ctx, call)

            override suspend fun executeTool(call: ToolCall): ToolResult {
                inTool = true
                try {
                    return broker.execute(ctx, call)
                } finally {
                    inTool = false
                }
            }

            override suspend fun afterToolCall(call: ToolCall, result: ToolResult): ToolResult? = broker.afterExecute(ctx, call, result)
        }
        try {
            core.runTurn(input, host)
        } finally {
            writes.close()
            writerJob.join()
        }
    }

    private fun captureAnswer(message: JsonObject) {
        val stop = (message["stopReason"] as? JsonPrimitive)?.contentOrNull
        if (stop != "stop" && stop != "length") return
        val text = runCatching {
            message["content"]!!.jsonArray.mapNotNull { b ->
                val o = b.jsonObject
                if ((o["type"] as? JsonPrimitive)?.contentOrNull == "text") (o["text"] as? JsonPrimitive)?.contentOrNull else null
            }.joinToString("")
        }.getOrDefault("")
        if (text.isNotBlank()) finalAnswer = text
    }

    /** 串行提交写队列；合并文字增量。 */
    private suspend fun writer() {
        var pending: PendingDelta? = null
        suspend fun flush() {
            val p = pending ?: return
            pending = null
            // 一次到达的超长增量（> coalesceMaxChars）切成多条写入同一个事务，日志里的每条增量都不超过上限（events.md 6.3），
            // 也就不会被 EventLog 的单条上限截断
            val events = p.toEvents(sessionId, taskId, config.coalesceMaxChars)
            commit { tx -> events.forEach(tx.events::append) }
        }
        while (true) {
            val item = if (pending != null) {
                val wait = (pending!!.startedAt + config.coalesceWindowMillis - System.currentTimeMillis()).coerceAtLeast(0)
                // 用 select 而不是 withTimeout 包 receive：超时与收到元素只会成立一个，已经取出的元素不会因超时丢失
                val r = select<ChannelResult<Write>?> {
                    writes.onReceiveCatching { it }
                    onTimeout(wait) { null }
                }
                if (r == null) {
                    flush()
                    continue
                }
                r
            } else {
                writes.receiveCatching()
            }
            val w = item.getOrNull()
            if (w == null) {
                flush()
                return
            }
            when (w) {
                is Write.Commit -> {
                    flush()
                    commit(w.block)
                    w.ack.complete(Unit)
                }
                is Write.Pi -> {
                    val e = w.event
                    if (e is AgentEvent.MessageUpdate && e.update.kind in COALESCED && e.update.delta != null) {
                        val p = pending
                        if (p != null && p.matches(e)) {
                            p.text.append(e.update.delta)
                        } else {
                            flush()
                            pending = PendingDelta(e, StringBuilder(e.update.delta), System.currentTimeMillis())
                        }
                        if (pending!!.text.length >= config.coalesceMaxChars) flush()
                    } else if (e is AgentEvent.MessageUpdate && e.update.kind !in PERSISTED_UPDATES) {
                        // start / *_start / *_end / toolcall_delta：信息已由其他事件覆盖，不写入（events.md 第 3 节）
                    } else {
                        flush()
                        commit { tx -> tx.events.append(PendingEvent.of(sessionId, taskId, e)) }
                        if (e is AgentEvent.TurnEnd) checkpoint()
                    }
                }
            }
        }
    }

    private suspend fun checkpoint() {
        if (isFenced) return
        val messages = runCatching { core.messages() }.getOrNull() ?: return
        commit { tx -> tx.sessions.saveMessages(sessionId, messages, tx.now, stable = false) }
    }

    private suspend fun commit(block: (StoreTx) -> Unit) {
        if (isFenced) return
        store.write(block)
    }

    private class PendingDelta(val first: AgentEvent.MessageUpdate, val text: StringBuilder, val startedAt: Long) {
        fun matches(e: AgentEvent.MessageUpdate) =
            e.update.kind == first.update.kind && e.update.contentIndex == first.update.contentIndex && e.role == first.role

        fun toEvents(sessionId: String, taskId: String, maxChars: Int): List<PendingEvent> =
            splitText(text.toString(), maxChars).map { PendingEvent.of(sessionId, taskId, first.copy(update = first.update.copy(delta = it))) }
    }

    companion object {
        private val COALESCED = setOf(AssistantUpdate.TEXT_DELTA, AssistantUpdate.THINKING_DELTA)

        /** 按 [maxChars] 切分，不切开代理对。 */
        internal fun splitText(text: String, maxChars: Int): List<String> {
            if (text.length <= maxChars) return listOf(text)
            val out = ArrayList<String>(text.length / maxChars + 1)
            var start = 0
            while (start < text.length) {
                var end = minOf(start + maxChars, text.length)
                if (end < text.length && end - start > 1 && Character.isHighSurrogate(text[end - 1])) end--
                out += text.substring(start, end)
                start = end
            }
            return out
        }

        /** events.md 第 3 节：写入日志的 message_update 类型。 */
        val PERSISTED_UPDATES = setOf(
            AssistantUpdate.TEXT_DELTA, AssistantUpdate.THINKING_DELTA, AssistantUpdate.TOOLCALL_END,
            AssistantUpdate.DONE, AssistantUpdate.ERROR,
        )
    }
}
