@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.agentos.runtime.testing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.errors.ErrorInfo
import org.agentos.runtime.events.AgentEvent
import org.agentos.runtime.events.AssistantUpdate
import org.agentos.runtime.events.RuntimeJson
import org.agentos.runtime.ports.AgentCore
import org.agentos.runtime.ports.AgentCoreFactory
import org.agentos.runtime.ports.AgentCoreSession
import org.agentos.runtime.ports.AgentCoreState
import org.agentos.runtime.ports.AgentCoreUnavailableException
import org.agentos.runtime.ports.AgentSessionConfig
import org.agentos.runtime.ports.ContentPart
import org.agentos.runtime.ports.FinishReason
import org.agentos.runtime.ports.PiMessages
import org.agentos.runtime.ports.ToolCall
import org.agentos.runtime.ports.ToolCallDecision
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.TurnHost
import org.agentos.runtime.ports.TurnInput
import org.agentos.runtime.ports.TurnOutcome
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * AgentCore 的假实现：不调模型，按 [FakeTurnScript] 编排事件和 tool_use，行为遵守 ports/AgentCore.kt 文件头的契约：
 *
 * - 事件顺序与 Pi 0.86.1 一致（agent_start → turn_start → user 消息 → assistant 消息的增量 → 工具 → turn_end → … → agent_end）；
 * - 工具调用按 tool_execution_start → beforeToolCall → executeTool → afterToolCall → tool_execution_end 回到 TurnHost；
 * - messages 与 pi-ai 的 `Message[]` 同形，开头是 role:"system" 消息，可以用 restore 重建；
 * - abort 立即打断文字流、等待和进行中的 executeTool（取消其协程），本轮以 Aborted 结束，被中止的工具记为 isError 的 toolResult；
 * - 工具轮次上限：第 maxToolRounds + 1 次带工具的往返不执行工具，本轮以 ToolRoundLimit 结束；
 * - [crash] 或剧本里的 CrashCore 模拟泵故障：state 变为 Failed，进行中的轮次以 CoreLost 返回，之后的调用抛 AgentCoreUnavailableException。
 */
class FakeAgentCore(
    private val scripts: (FakeTurnContext) -> FakeTurnScript = FakeScripts.echo(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AgentCore {
    private val _state = MutableStateFlow<AgentCoreState>(AgentCoreState.Idle)
    override val state: StateFlow<AgentCoreState> = _state.asStateFlow()

    private val sessions = ConcurrentHashMap<String, FakeSession>()
    private val toolIds = AtomicLong()

    /** 每次 openSession / reconfigure 收到的会话配置（按时间顺序），测试用来看系统提示和工具目录。 */
    val configs: MutableList<AgentSessionConfig> = java.util.Collections.synchronizedList(mutableListOf())

    /** 所有会话累计的 runTurn 次数，测试用来断言“没有被重放”。 */
    val turnsStarted = AtomicInteger()

    override suspend fun start() {
        when (val s = _state.value) {
            AgentCoreState.Ready -> return
            is AgentCoreState.Failed, AgentCoreState.Closed -> throw AgentCoreUnavailableException("fake core is $s")
            else -> _state.value = AgentCoreState.Ready
        }
    }

    override suspend fun openSession(sessionId: String, config: AgentSessionConfig, restore: PiMessages?): AgentCoreSession {
        ensureReady()
        configs += config
        val initial: MutableList<JsonElement> = restore?.json?.toMutableList<JsonElement>() ?: mutableListOf(systemMessage(config.systemPrompt))
        val session = FakeSession(sessionId, config, initial)
        check(sessions.putIfAbsent(sessionId, session) == null) { "session $sessionId is already open" }
        return session
    }

    override suspend fun close() {
        if (_state.value == AgentCoreState.Closed) return
        sessions.values.forEach { it.interrupt(Interrupt.Abort) }
        sessions.clear()
        _state.value = AgentCoreState.Closed
    }

    /** 模拟泵故障（F8）。 */
    fun crash(message: String = "fake pump exited") {
        val error = ErrorCode.AGENT_CORE_FAILED.info(message)
        _state.value = AgentCoreState.Failed(error)
        sessions.values.forEach { it.interrupt(Interrupt.Lost(error)) }
    }

    /** 当前打开的会话 ID。 */
    fun openSessionIds(): Set<String> = sessions.keys.toSet()

    private fun ensureReady() {
        when (val s = _state.value) {
            AgentCoreState.Ready -> Unit
            else -> throw AgentCoreUnavailableException("fake core is not ready: $s")
        }
    }

    private fun systemMessage(prompt: String): JsonObject = buildJsonObject {
        put("role", "system")
        put("content", prompt)
        put("timestamp", clock())
    }

    private sealed interface Interrupt {
        data object Abort : Interrupt

        data class Lost(val error: ErrorInfo) : Interrupt
    }

    private class Interrupted(val reason: Interrupt) : Exception(null, null, false, false)

    private inner class FakeSession(
        override val sessionId: String,
        @Volatile private var config: AgentSessionConfig,
        initialMessages: MutableList<JsonElement>,
    ) : AgentCoreSession {
        private val messages = initialMessages
        private val lock = Any()
        private var turnIndex = 0

        @Volatile private var running: CompletableDeferred<Interrupt>? = null

        @Volatile private var disposed = false

        fun interrupt(reason: Interrupt) {
            running?.complete(reason)
        }

        override suspend fun reconfigure(config: AgentSessionConfig) {
            checkUsable()
            check(running == null) { "cannot reconfigure while a turn is running" }
            configs += config
            this.config = config
        }

        override suspend fun abort() {
            checkUsable()
            interrupt(Interrupt.Abort)
        }

        override suspend fun messages(): PiMessages {
            checkUsable()
            return synchronized(lock) { PiMessages(JsonArray(messages.toList())) }
        }

        override suspend fun dispose() {
            if (disposed) return
            interrupt(Interrupt.Abort)
            disposed = true
            sessions.remove(sessionId, this)
        }

        private fun checkUsable() {
            if (_state.value is AgentCoreState.Failed || _state.value == AgentCoreState.Closed) {
                throw AgentCoreUnavailableException("fake core is ${_state.value}")
            }
            check(!disposed) { "session $sessionId is disposed" }
        }

        private fun append(message: JsonObject) = synchronized(lock) { messages += message }

        override suspend fun runTurn(input: TurnInput, host: TurnHost): TurnOutcome {
            checkUsable()
            val signal = CompletableDeferred<Interrupt>()
            synchronized(lock) {
                check(running == null) { "a turn is already running in session $sessionId" }
                running = signal
            }
            turnsStarted.incrementAndGet()
            val script = scripts(FakeTurnContext(sessionId, turnIndex++, input, config))
            try {
                return TurnRunner(input, host, signal, script).run()
            } catch (e: Interrupted) {
                // 只有泵故障会走到这里：不再发事件
                return TurnOutcome.CoreLost((e.reason as Interrupt.Lost).error)
            } finally {
                synchronized(lock) { running = null }
            }
        }

        private inner class TurnRunner(
            private val input: TurnInput,
            private val host: TurnHost,
            private val signal: CompletableDeferred<Interrupt>,
            private val script: FakeTurnScript,
        ) {
            private var toolRounds = 0

            suspend fun run(): TurnOutcome {
                emit(AgentEvent.AgentStart)
                val user = buildJsonObject {
                    put("role", "user")
                    put(
                        "content",
                        buildJsonArray {
                            add(RuntimeJson.encodeToJsonElement(ContentPart.serializer(), ContentPart.Text(input.text)))
                            input.images.forEach { add(RuntimeJson.encodeToJsonElement(ContentPart.serializer(), it)) }
                        },
                    )
                    put("timestamp", clock())
                }
                val rounds = script.rounds.toMutableList()
                var roundIndex = 0
                var firstRound = true
                while (true) {
                    val steps = rounds.getOrNull(roundIndex) ?: listOf(FakeStep.Text(FakeTurnScript.FINAL_TEXT))
                    roundIndex++
                    emit(AgentEvent.TurnStart)
                    if (firstRound) {
                        emit(AgentEvent.MessageStart("user"))
                        append(user)
                        emit(AgentEvent.MessageEnd(user))
                        firstRound = false
                    }
                    when (val r = modelRound(steps)) {
                        is RoundResult.Done -> return r.outcome
                        is RoundResult.ToolsRun -> continue
                    }
                }
            }

            /** 一次模型往返：流式产出一条 assistant 消息；有工具调用就执行。 */
            private suspend fun modelRound(steps: List<FakeStep>): RoundResult {
                val content = mutableListOf<JsonObject>()
                val toolCalls = mutableListOf<ToolCall>()
                emit(AgentEvent.MessageStart("assistant"))
                update("start", null)
                var finish: String = "stop"
                var error: ErrorInfo? = null
                var aborted = false
                try {
                    for (step in steps) {
                        when (step) {
                            is FakeStep.Text -> stream(step.text, step.chunkChars, step.intervalMs, "text", content)
                            is FakeStep.Thinking -> stream(step.text, step.chunkChars, step.intervalMs, "thinking", content)
                            is FakeStep.ToolUse -> {
                                val id = step.id ?: "call_${toolIds.incrementAndGet()}"
                                val index = content.size
                                val block = buildJsonObject {
                                    put("type", "toolCall")
                                    put("id", id)
                                    put("name", step.name)
                                    put("arguments", step.arguments)
                                }
                                update("toolcall_start", index)
                                update("toolcall_delta", index, step.arguments.toString())
                                emit(AgentEvent.MessageUpdate("assistant", AssistantUpdate("toolcall_end", index, toolCall = block)))
                                content += block
                                toolCalls += ToolCall(id, step.name, step.arguments)
                            }
                            is FakeStep.Delay -> interruptible { delay(step.millis) }
                            FakeStep.AwaitAbort -> interruptible { CompletableDeferred<Unit>().await() }
                            is FakeStep.Fail -> {
                                error = step.error
                                finish = "error"
                                break
                            }
                            FakeStep.MaxTokens -> {
                                finish = "length"
                                break
                            }
                            is FakeStep.CrashCore -> {
                                crash(step.message)
                                interruptible { CompletableDeferred<Unit>().await() }
                            }
                        }
                    }
                    if (toolCalls.isNotEmpty() && finish == "stop") finish = "toolUse"
                } catch (e: Interrupted) {
                    if (e.reason is Interrupt.Lost) throw e
                    aborted = true
                    finish = "aborted"
                }

                val assistant = assistantMessage(content, finish, error?.message ?: if (aborted) "Request was aborted" else null)
                if (finish == "error" || finish == "aborted") {
                    emit(AgentEvent.MessageUpdate("assistant", AssistantUpdate(AssistantUpdate.ERROR, reason = finish)))
                } else {
                    emit(AgentEvent.MessageUpdate("assistant", AssistantUpdate(AssistantUpdate.DONE, reason = finish)))
                }
                append(assistant)
                emit(AgentEvent.MessageEnd(assistant))

                return when {
                    aborted -> {
                        closeToolCalls(toolCalls, "Tool call aborted")
                        endAgent("aborted", "Request was aborted", toolCalls.size)
                        RoundResult.Done(TurnOutcome.Aborted)
                    }
                    error != null -> {
                        endAgent("error", error.message, 0)
                        RoundResult.Done(TurnOutcome.Failed(error))
                    }
                    finish == "length" -> {
                        endAgent("length", null, 0)
                        RoundResult.Done(TurnOutcome.Finished(FinishReason.MAX_TOKENS, fakeUsage()))
                    }
                    toolCalls.isEmpty() -> {
                        endAgent("stop", null, 0)
                        RoundResult.Done(TurnOutcome.Finished(FinishReason.END_TURN, fakeUsage()))
                    }
                    toolRounds + 1 > config.maxToolRounds -> {
                        closeToolCalls(toolCalls, "Tool round limit reached")
                        endAgent("aborted", "Tool round limit reached", toolCalls.size)
                        RoundResult.Done(TurnOutcome.ToolRoundLimit(toolRounds + 1, config.maxToolRounds))
                    }
                    else -> {
                        toolRounds++
                        val results = runTools(toolCalls)
                        emit(AgentEvent.TurnEnd("toolUse", null, results))
                        if (signal.isCompleted) {
                            // 工具执行期间被 abort：Pi 在下一次往返前结束
                            endAgentOnly()
                            RoundResult.Done(outcomeFor(signal.getCompleted()))
                        } else {
                            RoundResult.ToolsRun
                        }
                    }
                }
            }

            private fun outcomeFor(reason: Interrupt): TurnOutcome = when (reason) {
                Interrupt.Abort -> TurnOutcome.Aborted
                is Interrupt.Lost -> throw Interrupted(reason)
            }

            /** 按 Pi 的顺序执行本条消息里的工具调用；返回工具结果条数。 */
            private suspend fun runTools(calls: List<ToolCall>): Int {
                var count = 0
                for (call in calls) {
                    emit(AgentEvent.ToolExecutionStart(call.toolCallId, call.name, call.arguments))
                    var result: ToolResult
                    if (signal.isCompleted) {
                        result = ToolResult.text("Tool call aborted", isError = true)
                    } else {
                        result = when (val d = host.beforeToolCall(call)) {
                            ToolCallDecision.Allow -> try {
                                interruptible { host.executeTool(call) }
                            } catch (e: Interrupted) {
                                if (e.reason is Interrupt.Lost) throw e
                                ToolResult.text("Tool call aborted", isError = true)
                            }
                            is ToolCallDecision.Block -> ToolResult.text(d.reason, isError = true)
                        }
                        if (!signal.isCompleted) host.afterToolCall(call, result)?.let { result = it }
                    }
                    emit(
                        AgentEvent.ToolExecutionEnd(
                            call.toolCallId,
                            call.name,
                            buildJsonObject {
                                put("content", RuntimeJson.encodeToJsonElement(ContentPart.serializer().list(), result.content))
                                result.details?.let { put("details", it) }
                            },
                            result.isError,
                        ),
                    )
                    appendToolResult(call, result)
                    count++
                }
                return count
            }

            private fun closeToolCalls(calls: List<ToolCall>, reason: String) {
                calls.forEach { appendToolResult(it, ToolResult.text(reason, isError = true), emitEvents = true) }
            }

            private fun appendToolResult(call: ToolCall, result: ToolResult, emitEvents: Boolean = true) {
                val msg = buildJsonObject {
                    put("role", "toolResult")
                    put("toolCallId", call.toolCallId)
                    put("toolName", call.name)
                    put("content", RuntimeJson.encodeToJsonElement(ContentPart.serializer().list(), result.content))
                    result.details?.let { put("details", it) }
                    put("isError", result.isError)
                    put("timestamp", clock())
                }
                if (emitEvents) emit(AgentEvent.MessageStart("toolResult"))
                append(msg)
                if (emitEvents) emit(AgentEvent.MessageEnd(msg))
            }

            private fun endAgent(stopReason: String, errorMessage: String?, toolResults: Int) {
                emit(AgentEvent.TurnEnd(stopReason, errorMessage, toolResults))
                endAgentOnly()
            }

            private fun endAgentOnly() {
                emit(AgentEvent.AgentEnd(synchronized(lock) { messages.size }))
            }

            private suspend fun stream(text: String, chunkChars: Int, intervalMs: Long, kind: String, content: MutableList<JsonObject>) {
                val index = content.size
                val sb = StringBuilder()
                update("${kind}_start", index)
                try {
                    for (chunk in text.chunked(chunkChars.coerceAtLeast(1))) {
                        if (signal.isCompleted) throw Interrupted(signal.getCompleted())
                        update("${kind}_delta", index, chunk)
                        sb.append(chunk)
                        if (intervalMs > 0) interruptible { delay(intervalMs) } else yield()
                    }
                    update("${kind}_end", index)
                } finally {
                    content += if (kind == "text") {
                        buildJsonObject {
                            put("type", "text")
                            put("text", sb.toString())
                        }
                    } else {
                        buildJsonObject {
                            put("type", "thinking")
                            put("thinking", sb.toString())
                        }
                    }
                }
            }

            /** 运行 [block]，abort 或泵故障时取消它并抛 [Interrupted]。 */
            private suspend fun <T> interruptible(block: suspend () -> T): T {
                if (signal.isCompleted) throw Interrupted(signal.getCompleted())
                return coroutineScope {
                    val work = async(start = CoroutineStart.UNDISPATCHED) { block() }
                    select {
                        work.onAwait { it }
                        signal.onAwait { reason ->
                            work.cancel()
                            throw Interrupted(reason)
                        }
                    }
                }
            }

            private fun update(kind: String, index: Int?, delta: String? = null) =
                emit(AgentEvent.MessageUpdate("assistant", AssistantUpdate(kind, index, delta)))

            private fun emit(event: AgentEvent) {
                if (signal.isCompleted && signal.getCompleted() is Interrupt.Lost) throw Interrupted(signal.getCompleted())
                host.onEvent(event)
            }

            private fun assistantMessage(content: List<JsonObject>, stopReason: String, errorMessage: String?): JsonObject =
                buildJsonObject {
                    put("role", "assistant")
                    put("content", JsonArray(content))
                    put("api", config.model.api ?: "anthropic-messages")
                    put("provider", config.model.provider ?: "fake")
                    put("model", config.model.id ?: "fake-model")
                    put("usage", fakeUsage())
                    put("stopReason", stopReason)
                    errorMessage?.let { put("errorMessage", it) }
                    put("timestamp", clock())
                }
        }
    }

    companion object {
        /** 每次 create 都返回一个用同样剧本的新实例。 */
        fun factory(scripts: (FakeTurnContext) -> FakeTurnScript = FakeScripts.echo()): AgentCoreFactory =
            AgentCoreFactory { FakeAgentCore(scripts) }
    }
}

private sealed interface RoundResult {
    data class Done(val outcome: TurnOutcome) : RoundResult

    data object ToolsRun : RoundResult
}

private fun <T> kotlinx.serialization.KSerializer<T>.list() = kotlinx.serialization.builtins.ListSerializer(this)
