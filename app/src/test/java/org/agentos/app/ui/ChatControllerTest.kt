package org.agentos.app.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ChatController with a scripted connection (no Android, virtual time). */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {
    private class FakeAgent : AgentConnection {
        override val connection: StateFlow<ChatState.Connection> = MutableStateFlow(ChatState.Connection.CONNECTED)
        val prompts = mutableListOf<String>()
        var cancels = 0
        var newSessions = 0
        var closed = false
        var replacedOnce = false
        /** Completes the running turn. */
        var finish = CompletableDeferred<TurnOutcome>()
        var emit: ((AgentUpdate) -> Unit)? = null

        override suspend fun prompt(text: String, onUpdate: (AgentUpdate) -> Unit): TurnOutcome {
            prompts += text
            emit = onUpdate
            return finish.await().also { finish = CompletableDeferred() }
        }

        override suspend fun cancel() {
            cancels++
            finish.complete(TurnOutcome.Finished("cancelled"))
        }

        override fun newSession() {
            newSessions++
        }

        override fun consumeSessionReplaced(): Boolean = replacedOnce.also { replacedOnce = false }

        override fun close() {
            closed = true
        }
    }

    /**
     * Runs [block] with a controller whose scope is on the test scheduler but not backgroundScope:
     * advanceUntilIdle() does not wait for background work, so the turn would never run.
     */
    private fun withController(block: suspend TestScope.(ChatController, FakeAgent) -> Unit) = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val agent = FakeAgent()
        try {
            block(ChatController(agent, scope), agent)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun oneTurnStreamsAndEnds() = withController { c, agent ->
        assertTrue(c.send("  hello  "))
        advanceUntilIdle()
        assertEquals(listOf("hello"), agent.prompts)
        assertTrue(c.state.value.busy)
        agent.emit!!(AgentUpdate.MessageChunk("Hi"))
        agent.emit!!(AgentUpdate.MessageChunk(" there"))
        assertEquals("Hi there", (c.state.value.items.last() as ChatItem.Agent).text)
        agent.finish.complete(TurnOutcome.Finished("end_turn"))
        advanceUntilIdle()
        assertFalse(c.state.value.busy)
        assertEquals(ChatState.Connection.CONNECTED, c.state.value.connection)
    }

    @Test
    fun blankOrConcurrentSendsAreRejected() = withController { c, agent ->
        assertFalse(c.send("   "))
        assertTrue(c.send("one"))
        advanceUntilIdle()
        assertFalse("one turn at a time", c.send("two"))
        assertFalse("no new conversation while busy", c.newConversation())
        assertEquals(listOf("one"), agent.prompts)
    }

    @Test
    fun cancelSendsSessionCancelOnceAndEndsWithCancelled() = withController { c, agent ->
        c.send("long task")
        advanceUntilIdle()
        c.cancel()
        c.cancel() // second tap while cancelling is ignored
        assertEquals(ChatState.Turn.CANCELLING, c.state.value.turn)
        advanceUntilIdle()
        assertEquals(1, agent.cancels)
        assertEquals(ChatState.Turn.IDLE, c.state.value.turn)
        assertEquals("已取消", (c.state.value.items.last() as ChatItem.Notice).title)
    }

    @Test
    fun replacedSessionIsAnnounced() = withController { c, agent ->
        agent.replacedOnce = true
        c.send("after restart")
        advanceUntilIdle()
        agent.finish.complete(TurnOutcome.Finished("end_turn"))
        advanceUntilIdle()
        assertTrue(c.state.value.items.any { it is ChatItem.Notice && it.title == "已开始新的会话" })
    }

    @Test
    fun newConversationClearsAndStartsANewSession() = withController { c, agent ->
        c.send("x")
        advanceUntilIdle()
        agent.finish.complete(TurnOutcome.Finished("end_turn"))
        advanceUntilIdle()
        assertTrue(c.newConversation())
        assertEquals(1, agent.newSessions)
        assertTrue(c.state.value.items.isEmpty())
    }

    @Test
    fun closingTheScreenMidTurnDetachesWithoutCancelling() = withController { c, agent ->
        c.send("keep going")
        advanceUntilIdle()
        agent.emit!!(AgentUpdate.MessageChunk("partial"))
        c.onScreenFinished()
        advanceUntilIdle()
        assertTrue(agent.closed)
        assertEquals("a disconnect is not a cancel (F7)", 0, agent.cancels)
        assertFalse(c.state.value.busy)
        assertEquals("界面关闭时这一轮还在进行", (c.state.value.items.last() as ChatItem.Notice).title)
    }
}
