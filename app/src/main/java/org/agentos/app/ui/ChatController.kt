package org.agentos.app.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * State holder of the conversation screen (the "ViewModel"): lives for the main process, so a
 * rotation keeps the conversation; the Activity only renders [state] and forwards input.
 * All state changes go through [ChatReducer]; this class adds the ordering rules:
 * one turn at a time, cancel only while a turn runs, new conversation only when idle.
 *
 * Streamed text is coalesced: message and thought chunks are applied at most once per [frameMs]
 * (a model can send hundreds of small chunks a second; the screen re-renders and re-parses Markdown
 * per state). Anything else (tool calls, the end of the turn) first applies the pending text, so the
 * order on screen is unchanged. Everything runs on the main thread.
 */
class ChatController(
    private val agent: AgentConnection,
    private val scope: CoroutineScope,
    private val frameMs: Long = FRAME_MS,
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()
    private var turnJob: Job? = null
    private val pending = ArrayList<AgentUpdate>()
    private var flushJob: Job? = null

    init {
        scope.launch {
            agent.connection.collect { c -> _state.update { ChatReducer.connection(it, c) } }
        }
    }

    /** Sends [text] as a new turn. Ignored while a turn is running or when [text] is blank. */
    fun send(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.busy) return false
        _state.update { ChatReducer.userSent(it, trimmed) }
        turnJob = scope.launch {
            val outcome = agent.prompt(trimmed) { u -> onUpdate(u) }
            flush()
            if (agent.consumeSessionReplaced()) {
                _state.update {
                    ChatReducer.notice(it, ChatItem.Notice.Kind.INFO, "已开始新的会话", "之前的会话在运行时重启后不再可用")
                }
            }
            _state.update { ChatReducer.finished(it, outcome) }
        }
        return true
    }

    private fun onUpdate(u: AgentUpdate) {
        when (u) {
            is AgentUpdate.MessageChunk, is AgentUpdate.ThoughtChunk -> {
                val last = pending.lastOrNull()
                when {
                    last is AgentUpdate.MessageChunk && u is AgentUpdate.MessageChunk -> pending[pending.lastIndex] = AgentUpdate.MessageChunk(last.text + u.text)
                    last is AgentUpdate.ThoughtChunk && u is AgentUpdate.ThoughtChunk -> pending[pending.lastIndex] = AgentUpdate.ThoughtChunk(last.text + u.text)
                    else -> pending += u
                }
                if (flushJob == null) {
                    flushJob = scope.launch {
                        delay(frameMs)
                        flushJob = null
                        flush()
                    }
                }
            }
            else -> {
                flush()
                _state.update { ChatReducer.update(it, u) }
            }
        }
    }

    /** Applies the coalesced chunks now. */
    private fun flush() {
        flushJob?.cancel()
        flushJob = null
        if (pending.isEmpty()) return
        val batch = pending.toList()
        pending.clear()
        _state.update { s -> batch.fold(s) { acc, u -> ChatReducer.update(acc, u) } }
    }

    /** `session/cancel`; the turn ends when the runtime answers with stop reason `cancelled`. */
    fun cancel() {
        if (!_state.value.busy || _state.value.turn == ChatState.Turn.CANCELLING) return
        _state.update { ChatReducer.cancelRequested(it) }
        scope.launch { agent.cancel() }
    }

    /** Starts a new conversation (new ACP session). Only while idle. */
    fun newConversation(): Boolean {
        if (_state.value.busy) return false
        agent.newSession()
        _state.update { ChatReducer.cleared(it) }
        return true
    }

    fun toggleThought(id: Long) = _state.update { ChatReducer.toggleThought(it, id) }

    fun toggleTool(id: Long) = _state.update { ChatReducer.toggleTool(it, id) }

    /**
     * The conversation screen is gone for good (finished, not rotated): close the channel. A running
     * turn keeps running in the runtime (a disconnect is not a cancel, F7); this screen stops
     * listening and says so when it comes back.
     */
    fun onScreenFinished() {
        val wasBusy = _state.value.busy
        flush()
        turnJob?.cancel()
        agent.close()
        if (wasBusy) {
            _state.update {
                ChatReducer.notice(
                    ChatReducer.finished(it, TurnOutcome.Finished("end_turn")),
                    ChatItem.Notice.Kind.INFO, "界面关闭时这一轮还在进行",
                    "运行时会继续把它做完；M2 起可以回到原来的会话查看结果",
                )
            }
        }
    }

    companion object {
        /** One screen update per 50 ms at most while text streams (about three display frames). */
        const val FRAME_MS = 50L

        @Volatile private var instance: ChatController? = null

        /** Process-wide instance (main process). */
        fun get(context: Context): ChatController = instance ?: synchronized(this) {
            instance ?: run {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                ChatController(LocalAcpClient(context.applicationContext, scope), scope).also { instance = it }
            }
        }
    }
}
