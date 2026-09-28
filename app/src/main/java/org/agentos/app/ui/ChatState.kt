package org.agentos.app.ui

/**
 * Everything the conversation screen shows. Facts about sessions and tasks live in the runtime
 * (architecture principle 3); this is only the view of the current conversation in this process.
 */
data class ChatState(
    val items: List<ChatItem> = emptyList(),
    val turn: Turn = Turn.IDLE,
    val connection: Connection = Connection.DISCONNECTED,
    val nextId: Long = 1,
) {
    enum class Turn { IDLE, SENDING, RUNNING, CANCELLING }
    enum class Connection { DISCONNECTED, CONNECTING, CONNECTED }

    val busy: Boolean get() = turn != Turn.IDLE
}

sealed interface ChatItem {
    val id: Long

    data class User(override val id: Long, val text: String) : ChatItem

    /** One stretch of agent output; a tool call in between starts a new one. */
    data class Agent(
        override val id: Long,
        val text: String = "",
        val thought: String = "",
        val streaming: Boolean = true,
        val thoughtExpanded: Boolean = false,
    ) : ChatItem

    data class Tool(
        override val id: Long,
        val callId: String,
        val title: String,
        val kind: String?,
        val status: ToolStatus,
        val detail: String?,
        val expanded: Boolean = false,
    ) : ChatItem

    data class Notice(override val id: Long, val kind: Kind, val title: String, val hint: String? = null) : ChatItem {
        enum class Kind { INFO, CANCELLED, ERROR }
    }
}

/** Pure state transitions; unit-tested without Android (ChatReducerTest). */
object ChatReducer {
    fun userSent(s: ChatState, text: String): ChatState =
        s.copy(items = s.items + ChatItem.User(s.nextId, text), turn = ChatState.Turn.SENDING, nextId = s.nextId + 1)

    fun connection(s: ChatState, c: ChatState.Connection): ChatState = s.copy(connection = c)

    fun running(s: ChatState): ChatState =
        if (s.turn == ChatState.Turn.SENDING) s.copy(turn = ChatState.Turn.RUNNING) else s

    fun cancelRequested(s: ChatState): ChatState =
        if (s.busy) s.copy(turn = ChatState.Turn.CANCELLING) else s

    fun update(s: ChatState, u: AgentUpdate): ChatState {
        val st = running(s)
        return when (u) {
            is AgentUpdate.MessageChunk -> st.appendToAgent { it.copy(text = it.text + u.text) }
            is AgentUpdate.ThoughtChunk -> st.appendToAgent { it.copy(thought = it.thought + u.text) }
            is AgentUpdate.ToolCallStarted -> st.upsertTool(u.callId) { existing, id ->
                existing?.copy(title = u.title, kind = u.kind ?: existing.kind, status = u.status, detail = u.detail ?: existing.detail)
                    ?: ChatItem.Tool(id, u.callId, u.title, u.kind, u.status, u.detail)
            }
            is AgentUpdate.ToolCallUpdated -> st.upsertTool(u.callId) { existing, id ->
                existing?.copy(
                    title = u.title ?: existing.title,
                    status = u.status ?: existing.status,
                    detail = u.detail ?: existing.detail,
                ) ?: ChatItem.Tool(id, u.callId, u.title ?: "工具调用", null, u.status ?: ToolStatus.PENDING, u.detail)
            }
            is AgentUpdate.Ignored -> st
        }
    }

    fun finished(s: ChatState, outcome: TurnOutcome): ChatState {
        val closed = s.copy(items = s.items.map { if (it is ChatItem.Agent && it.streaming) it.copy(streaming = false) else it }, turn = ChatState.Turn.IDLE)
        val notice: ChatItem.Notice? = when (outcome) {
            is TurnOutcome.Finished -> when (outcome.stopReason) {
                "end_turn" -> null
                "cancelled" -> ChatItem.Notice(s.nextId, ChatItem.Notice.Kind.CANCELLED, "已取消")
                "max_turn_requests" -> ChatItem.Notice(s.nextId, ChatItem.Notice.Kind.INFO, "工具调用轮次超过上限，已停止", "可以接着说“继续”")
                "max_tokens" -> ChatItem.Notice(s.nextId, ChatItem.Notice.Kind.INFO, "回复达到了长度上限", "可以接着说“继续”")
                "refusal" -> ChatItem.Notice(s.nextId, ChatItem.Notice.Kind.INFO, "模型拒绝回答这个请求")
                else -> ChatItem.Notice(s.nextId, ChatItem.Notice.Kind.INFO, "本轮结束（${outcome.stopReason}）")
            }
            is TurnOutcome.Failed -> ChatItem.Notice(s.nextId, ChatItem.Notice.Kind.ERROR, outcome.error.title, outcome.error.hint)
        }
        return if (notice == null) closed else closed.copy(items = closed.items + notice, nextId = s.nextId + 1)
    }

    fun notice(s: ChatState, kind: ChatItem.Notice.Kind, title: String, hint: String? = null): ChatState =
        s.copy(items = s.items + ChatItem.Notice(s.nextId, kind, title, hint), nextId = s.nextId + 1)

    fun toggleThought(s: ChatState, id: Long): ChatState =
        s.copy(items = s.items.map { if (it is ChatItem.Agent && it.id == id) it.copy(thoughtExpanded = !it.thoughtExpanded) else it })

    fun toggleTool(s: ChatState, id: Long): ChatState =
        s.copy(items = s.items.map { if (it is ChatItem.Tool && it.id == id) it.copy(expanded = !it.expanded) else it })

    fun cleared(s: ChatState): ChatState = ChatState(connection = s.connection, nextId = s.nextId)

    /** Appends to the agent item of the current turn if it is the last item, otherwise starts one. */
    private fun ChatState.appendToAgent(change: (ChatItem.Agent) -> ChatItem.Agent): ChatState {
        val last = items.lastOrNull()
        return if (last is ChatItem.Agent && last.streaming) {
            copy(items = items.dropLast(1) + change(last))
        } else {
            copy(items = items + change(ChatItem.Agent(nextId)), nextId = nextId + 1)
        }
    }

    /** Tool calls are matched by callId within the current turn (since the last user message). */
    private fun ChatState.upsertTool(callId: String, make: (ChatItem.Tool?, Long) -> ChatItem.Tool): ChatState {
        val turnStart = items.indexOfLast { it is ChatItem.User }
        val index = items.indexOfLast { it is ChatItem.Tool && it.callId == callId }
        if (index >= 0 && index > turnStart) {
            val existing = items[index] as ChatItem.Tool
            return copy(items = items.toMutableList().also { it[index] = make(existing, existing.id) })
        }
        // A tool call ends the current agent stretch: text after it goes into a new bubble.
        val sealed = items.map { if (it is ChatItem.Agent && it.streaming) it.copy(streaming = false) else it }
        return copy(items = sealed + make(null, nextId), nextId = nextId + 1)
    }
}
