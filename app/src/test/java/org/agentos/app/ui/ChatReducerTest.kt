package org.agentos.app.ui

import org.agentos.app.i18n.ResStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReducerTest {
    private fun sent(text: String = "hi") = ChatReducer.userSent(ChatState(), text)
    private fun ChatState.u(vararg updates: AgentUpdate) = updates.fold(this) { s, u -> ChatReducer.update(s, u) }

    @Test
    fun streamedChunksAccumulateInOneBubble() {
        val s = sent().u(AgentUpdate.MessageChunk("Hel"), AgentUpdate.MessageChunk("lo"))
        assertEquals(ChatState.Turn.RUNNING, s.turn)
        val agent = s.items.last() as ChatItem.Agent
        assertEquals("Hello", agent.text)
        assertTrue(agent.streaming)
        assertEquals(2, s.items.size)
    }

    @Test
    fun thoughtGoesIntoTheSameBubbleCollapsed() {
        val s = sent().u(AgentUpdate.ThoughtChunk("let me "), AgentUpdate.ThoughtChunk("think"), AgentUpdate.MessageChunk("Answer"))
        val agent = s.items.last() as ChatItem.Agent
        assertEquals("let me think", agent.thought)
        assertEquals("Answer", agent.text)
        assertFalse(agent.thoughtExpanded)
        val toggled = ChatReducer.toggleThought(s, agent.id).items.last() as ChatItem.Agent
        assertTrue(toggled.thoughtExpanded)
    }

    @Test
    fun toolCallSplitsTheAgentOutputAndIsUpdatedInPlace() {
        val s = sent().u(
            AgentUpdate.MessageChunk("Let me check."),
            AgentUpdate.ToolCallStarted("t1", "读取日历", "read", ToolStatus.PENDING, null),
            AgentUpdate.ToolCallUpdated("t1", null, ToolStatus.IN_PROGRESS, null),
            AgentUpdate.ToolCallUpdated("t1", null, ToolStatus.COMPLETED, "3 events"),
            AgentUpdate.MessageChunk("You have 3 events."),
        )
        val kinds = s.items.map { it::class.simpleName }
        assertEquals(listOf("User", "Agent", "Tool", "Agent"), kinds)
        val first = s.items[1] as ChatItem.Agent
        assertFalse("the stretch before the tool call is closed", first.streaming)
        val tool = s.items[2] as ChatItem.Tool
        assertEquals(ToolStatus.COMPLETED, tool.status)
        assertEquals("读取日历", tool.title)
        assertEquals("3 events", tool.detail)
        assertEquals("You have 3 events.", (s.items[3] as ChatItem.Agent).text)
    }

    @Test
    fun updateForAnUnknownToolCallCreatesIt() {
        // A notes: the SDK may deliver notifications of an earlier moment in the next turn's flow
        val s = sent().u(AgentUpdate.ToolCallUpdated("late", null, ToolStatus.FAILED, null))
        val tool = s.items.last() as ChatItem.Tool
        // no name came with the update: the placeholder is picked when the item is drawn, in the language of the screen
        assertEquals("", tool.title)
        assertEquals("工具调用", tool.displayTitle(ResStrings.zh))
        assertEquals("Tool call", tool.displayTitle(ResStrings.en))
        assertEquals("fs_read", tool.copy(title = "fs_read").displayTitle(ResStrings.en))
        assertEquals(ToolStatus.FAILED, tool.status)
    }

    @Test
    fun sameToolCallIdInANewTurnIsANewItem() {
        val t1 = sent("a").u(AgentUpdate.ToolCallStarted("x", "one", null, ToolStatus.COMPLETED, null))
        val done = ChatReducer.finished(t1, TurnOutcome.Finished("end_turn"), ResStrings.zh)
        val t2 = ChatReducer.userSent(done, "b").u(AgentUpdate.ToolCallStarted("x", "two", null, ToolStatus.PENDING, null))
        assertEquals(2, t2.items.count { it is ChatItem.Tool })
    }

    @Test
    fun endTurnClosesStreamingWithoutANotice() {
        val s = ChatReducer.finished(sent().u(AgentUpdate.MessageChunk("done")), TurnOutcome.Finished("end_turn"), ResStrings.zh)
        assertEquals(ChatState.Turn.IDLE, s.turn)
        assertFalse((s.items.last() as ChatItem.Agent).streaming)
        assertTrue(s.items.none { it is ChatItem.Notice })
    }

    @Test
    fun cancelFlow() {
        val running = sent().u(AgentUpdate.MessageChunk("part"))
        val cancelling = ChatReducer.cancelRequested(running)
        assertEquals(ChatState.Turn.CANCELLING, cancelling.turn)
        // chunks still arriving while cancelling do not flip the state back to RUNNING
        assertEquals(ChatState.Turn.CANCELLING, ChatReducer.update(cancelling, AgentUpdate.MessageChunk("x")).turn)
        for ((strings, title) in listOf(ResStrings.zh to "已取消", ResStrings.en to "Cancelled")) {
            val done = ChatReducer.finished(cancelling, TurnOutcome.Finished("cancelled"), strings)
            assertEquals(ChatState.Turn.IDLE, done.turn)
            val notice = done.items.last() as ChatItem.Notice
            assertEquals(ChatItem.Notice.Kind.CANCELLED, notice.kind)
            assertEquals(title, notice.title)
            // cancel while idle is a no-op
            assertEquals(done, ChatReducer.cancelRequested(done))
        }
    }

    @Test
    fun otherStopReasonsExplainThemselves() {
        val zh = ResStrings.zh
        val max = ChatReducer.finished(sent(), TurnOutcome.Finished("max_turn_requests"), zh).items.last() as ChatItem.Notice
        assertEquals("工具调用轮次超过上限，已停止", max.title)
        assertEquals("可以接着说“继续”", max.hint)
        val tokens = ChatReducer.finished(sent(), TurnOutcome.Finished("max_tokens"), zh).items.last() as ChatItem.Notice
        assertEquals("回复达到了长度上限", tokens.title)
        val refusal = ChatReducer.finished(sent(), TurnOutcome.Finished("refusal"), zh).items.last() as ChatItem.Notice
        assertEquals("模型拒绝回答这个请求", refusal.title)
        assertEquals("本轮结束（pause_turn）", (ChatReducer.finished(sent(), TurnOutcome.Finished("pause_turn"), zh).items.last() as ChatItem.Notice).title)
        val en = ResStrings.en
        val maxEn = ChatReducer.finished(sent(), TurnOutcome.Finished("max_turn_requests"), en).items.last() as ChatItem.Notice
        assertEquals("Stopped: tool-call rounds exceeded the limit", maxEn.title)
        assertEquals("You can say “continue” to carry on", maxEn.hint)
        val tokensEn = ChatReducer.finished(sent(), TurnOutcome.Finished("max_tokens"), en).items.last() as ChatItem.Notice
        assertEquals("The reply reached the length limit", tokensEn.title)
        val refusalEn = ChatReducer.finished(sent(), TurnOutcome.Finished("refusal"), en).items.last() as ChatItem.Notice
        assertEquals("The model declined this request", refusalEn.title)
        assertEquals("Turn ended (pause_turn)", (ChatReducer.finished(sent(), TurnOutcome.Finished("pause_turn"), en).items.last() as ChatItem.Notice).title)
        // an end of turn leaves no notice in either language
        for (s in listOf(zh, en)) assertTrue(ChatReducer.finished(sent(), TurnOutcome.Finished("end_turn"), s).items.none { it is ChatItem.Notice })
        // a failure keeps the words the error already carries, whichever overload closes the turn
        val err = AgentError("t", "h", false, "c")
        assertEquals(ChatReducer.finished(sent(), TurnOutcome.Failed(err)), ChatReducer.finished(sent(), TurnOutcome.Failed(err), en))
    }

    @Test
    fun failureBecomesAnErrorNotice() {
        val err = AgentErrors.fromRpc(-32051, "model_auth_failed: 401", "model_auth_failed", false)
        val s = ChatReducer.finished(sent().u(AgentUpdate.MessageChunk("par")), TurnOutcome.Failed(err))
        val notice = s.items.last() as ChatItem.Notice
        assertEquals(ChatItem.Notice.Kind.ERROR, notice.kind)
        assertEquals("模型服务拒绝了 key", notice.title)
        assertEquals("到设置页检查 key 是否正确、是否有这个模型的权限", notice.hint)
        assertFalse((s.items[1] as ChatItem.Agent).streaming)
    }

    @Test
    fun idsAreUniqueAndClearKeepsCounting() {
        val s = sent().u(AgentUpdate.MessageChunk("a"), AgentUpdate.ToolCallStarted("t", "t", null, ToolStatus.PENDING, null), AgentUpdate.MessageChunk("b"))
        assertEquals(s.items.size, s.items.map { it.id }.toSet().size)
        val cleared = ChatReducer.cleared(s)
        assertTrue(cleared.items.isEmpty())
        assertTrue(cleared.nextId >= s.nextId)
        assertNull(ChatReducer.userSent(cleared, "x").items.firstOrNull { it.id < s.nextId })
    }
}
