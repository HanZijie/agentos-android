package org.agentos.app.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.agentos.runtime.events.AgentEvent
import org.agentos.runtime.events.AssistantUpdate
import org.agentos.runtime.ports.AgentCore
import org.agentos.runtime.ports.AgentCoreFactory
import org.agentos.runtime.ports.AgentCoreSession
import org.agentos.runtime.ports.AgentCoreState
import org.agentos.runtime.ports.AgentCoreUnavailableException
import org.agentos.runtime.ports.AgentSessionConfig
import org.agentos.runtime.ports.FinishReason
import org.agentos.runtime.ports.PiMessages
import org.agentos.runtime.ports.TurnHost
import org.agentos.runtime.ports.TurnInput
import org.agentos.runtime.ports.TurnOutcome
import java.util.concurrent.ConcurrentHashMap

/**
 * **占位的 Agent core**（B2 的 PiAdapter 进 main 之前）：不调模型，按 prompt 里的脚本流式输出文字。宿主层（RuntimeEngine）、
 * ACP、Store、调度、恢复都是真的，只有“模型”是假的。B2 进 main 后 [AgentProcess] 只换 factory，本文件删除。
 *
 * 遵守 core/runtime ports/AgentCore.kt 文件头的契约：
 * - 事件顺序与 Pi 0.86.1 相同：agent_start → turn_start → user 消息 → assistant 消息（start、text_start、text_delta…、
 *   text_end、done/error）→ turn_end → agent_end；runTurn 在 agent_end 之后返回；
 * - [AgentCoreSession.abort] 让这一轮尽快以 [TurnOutcome.Aborted] 结束（assistant 消息 stopReason=aborted）；
 *   调用 runTurn 的协程被取消时照常抛 CancellationException；
 * - messages 与 pi-ai `Message[]` 同形（开头是 system 消息），可以用 restore 重建；同一会话同时只有一轮；
 * - 没有工具（M1 的工具目录为空），不碰 key。
 *
 * 脚本（tests/device/acp-channel 的 agentCommand）：prompt 文字是 JSON 对象时读 chunks（默认 20）、chunkChars（0 = 每条
 * "chunk i "）、intervalMs（默认 20）、burst、cjk、bigChunkChars（最后再发一条这么长的文字）；不是 JSON 时回显 20 行。
 */
object ScriptedAgentCore : AgentCoreFactory {
    const val MODEL_NAME = "agentos-scripted-placeholder"

    /** 保存进 messages 的 assistant 文字上限（流式输出本身不截断）。 */
    const val MAX_SAVED_TEXT = 16_384

    override fun create(): AgentCore = Core()

    /** 一轮的脚本。 */
    data class Script(
        val chunks: Int,
        val chunkChars: Int,
        val intervalMs: Long,
        val burst: Int,
        val cjk: Boolean,
        val bigChunkChars: Int,
        val echo: String?,
    ) {
        fun body(i: Int): String = when {
            echo != null -> "echo[$i]: ${echo.take(64)}\n"
            chunkChars > 0 -> (if (cjk) "中" else "x").repeat(chunkChars)
            else -> "chunk $i "
        }

        companion object {
            private val json = Json { ignoreUnknownKeys = true }

            fun parse(text: String): Script {
                val o = text.trim().takeIf { it.startsWith("{") }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                    ?: return Script(20, 0, 20, 1, false, 0, echo = text)
                fun int(k: String, d: Int) = (o[k] as? JsonPrimitive)?.intOrNull ?: d
                fun long(k: String, d: Long) = (o[k] as? JsonPrimitive)?.longOrNull ?: d
                return Script(
                    chunks = int("chunks", 20).coerceAtLeast(0),
                    chunkChars = int("chunkChars", 0).coerceAtLeast(0),
                    intervalMs = long("intervalMs", 20).coerceAtLeast(0),
                    burst = int("burst", 1).coerceAtLeast(1),
                    cjk = (o["cjk"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    bigChunkChars = int("bigChunkChars", 0).coerceAtLeast(0),
                    echo = null,
                )
            }
        }
    }

    private class Core : AgentCore {
        private val s = MutableStateFlow<AgentCoreState>(AgentCoreState.Idle)
        override val state: StateFlow<AgentCoreState> = s.asStateFlow()
        private val sessions = ConcurrentHashMap<String, Session>()

        override suspend fun start() {
            when (val st = s.value) {
                AgentCoreState.Ready -> return
                AgentCoreState.Closed, is AgentCoreState.Failed -> throw AgentCoreUnavailableException("scripted core is $st")
                else -> s.value = AgentCoreState.Ready
            }
        }

        override suspend fun openSession(sessionId: String, config: AgentSessionConfig, restore: PiMessages?): AgentCoreSession {
            if (s.value != AgentCoreState.Ready) throw AgentCoreUnavailableException("scripted core is not ready: ${s.value}")
            val initial = restore?.json?.toMutableList() ?: mutableListOf<JsonElement>(
                buildJsonObject {
                    put("role", "system")
                    put("content", config.systemPrompt)
                    put("timestamp", System.currentTimeMillis())
                },
            )
            val session = Session(sessionId, config, initial)
            check(sessions.putIfAbsent(sessionId, session) == null) { "session $sessionId is already open" }
            return session
        }

        override suspend fun close() {
            if (s.value == AgentCoreState.Closed) return
            sessions.values.forEach { it.signalAbort() }
            sessions.clear()
            s.value = AgentCoreState.Closed
        }

        private inner class Session(
            override val sessionId: String,
            @Volatile private var config: AgentSessionConfig,
            private val messages: MutableList<JsonElement>,
        ) : AgentCoreSession {
            private val lock = Any()
            @Volatile private var abortSignal: CompletableDeferred<Unit>? = null
            @Volatile private var disposed = false

            fun signalAbort() {
                abortSignal?.complete(Unit)
            }

            private fun checkUsable() {
                if (s.value is AgentCoreState.Failed || s.value == AgentCoreState.Closed) throw AgentCoreUnavailableException("scripted core is ${s.value}")
                check(!disposed) { "session $sessionId is disposed" }
            }

            override suspend fun reconfigure(config: AgentSessionConfig) {
                checkUsable()
                check(abortSignal == null) { "cannot reconfigure while a turn is running" }
                this.config = config
            }

            override suspend fun abort() {
                checkUsable()
                signalAbort()
            }

            override suspend fun messages(): PiMessages {
                checkUsable()
                return synchronized(lock) { PiMessages(JsonArray(messages.toList())) }
            }

            override suspend fun dispose() {
                if (disposed) return
                signalAbort()
                disposed = true
                sessions.remove(sessionId, this)
            }

            override suspend fun runTurn(input: TurnInput, host: TurnHost): TurnOutcome {
                checkUsable()
                val signal = CompletableDeferred<Unit>()
                synchronized(lock) {
                    check(abortSignal == null) { "a turn is already running in session $sessionId" }
                    abortSignal = signal
                }
                try {
                    return turn(input, host, signal)
                } finally {
                    synchronized(lock) { abortSignal = null }
                }
            }

            private suspend fun turn(input: TurnInput, host: TurnHost, signal: CompletableDeferred<Unit>): TurnOutcome {
                val now = System.currentTimeMillis()
                val user = buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray { add(textBlock(input.text.take(MAX_SAVED_TEXT))) })
                    put("timestamp", now)
                }
                host.onEvent(AgentEvent.AgentStart)
                host.onEvent(AgentEvent.TurnStart)
                host.onEvent(AgentEvent.MessageStart("user"))
                append(user)
                host.onEvent(AgentEvent.MessageEnd(user))

                host.onEvent(AgentEvent.MessageStart("assistant"))
                update(host, "start", null)
                update(host, "text_start", 0)
                val script = Script.parse(input.text)
                val saved = StringBuilder()
                var total = 0L
                fun emitText(text: String) {
                    update(host, AssistantUpdate.TEXT_DELTA, 0, text)
                    total += text.length
                    if (saved.length < MAX_SAVED_TEXT) saved.append(text.take(MAX_SAVED_TEXT - saved.length))
                }
                var aborted = false
                loop@ for (i in 0 until script.chunks) {
                    if (signal.isCompleted) {
                        aborted = true
                        break@loop
                    }
                    emitText(script.body(i))
                    if (script.intervalMs > 0) {
                        if (i % script.burst == script.burst - 1 && withTimeoutOrNull(script.intervalMs) { signal.await() } != null) {
                            aborted = true
                            break@loop
                        }
                    } else if (i % 64 == 63) {
                        yield()
                    }
                }
                if (!aborted && script.bigChunkChars > 0) emitText("y".repeat(script.bigChunkChars))
                if (!aborted && signal.isCompleted) aborted = true
                update(host, "text_end", 0)

                val stopReason = if (aborted) "aborted" else "stop"
                val text = if (total > saved.length) "$saved…[${total - saved.length} more chars not saved]" else saved.toString()
                val assistant = buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray { add(textBlock(text)) })
                    put("api", config.model.api ?: "openai-completions")
                    put("provider", config.model.provider ?: "agentos")
                    put("model", config.model.id ?: MODEL_NAME)
                    putJsonObject("usage") {
                        put("input", 0)
                        put("output", 0)
                        put("cacheRead", 0)
                        put("cacheWrite", 0)
                        put("totalTokens", 0)
                    }
                    put("stopReason", stopReason)
                    if (aborted) put("errorMessage", "Request was aborted")
                    put("timestamp", System.currentTimeMillis())
                }
                update(host, if (aborted) AssistantUpdate.ERROR else AssistantUpdate.DONE, null, reason = stopReason)
                append(assistant)
                host.onEvent(AgentEvent.MessageEnd(assistant))
                host.onEvent(AgentEvent.TurnEnd(stopReason, if (aborted) "Request was aborted" else null, 0))
                host.onEvent(AgentEvent.AgentEnd(synchronized(lock) { messages.size }))
                return if (aborted) TurnOutcome.Aborted else TurnOutcome.Finished(FinishReason.END_TURN)
            }

            private fun append(message: JsonObject) = synchronized(lock) { messages += message }

            private fun update(host: TurnHost, kind: String, index: Int?, delta: String? = null, reason: String? = null) =
                host.onEvent(AgentEvent.MessageUpdate("assistant", AssistantUpdate(kind, index, delta, reason = reason)))

            private fun textBlock(text: String) = buildJsonObject {
                put("type", "text")
                put("text", text)
            }
        }
    }
}
