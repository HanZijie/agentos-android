package org.agentos.sample.sms.agentos

import org.agentos.sample.sms.agentos.PanelRules.Primary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelRulesTest {
    private fun created(kind: ItemKind, id: String, tool: String) = ScheduleItem(id, kind, tool, ItemStatus.CREATED)
    private val both = ScheduleSummary(listOf(created(ItemKind.EVENT, "e1", "event_create"), created(ItemKind.ALARM, "a1", "alarm_create")))

    @Test fun `view links show only for installed apps that got something`() {
        assertEquals(PanelRules.ViewLinks(calendar = true, alarm = true, todo = false), PanelRules.viewLinks(both, calendarInstalled = true, alarmInstalled = true, todoInstalled = true))
        assertEquals(PanelRules.ViewLinks(calendar = false, alarm = true, todo = false), PanelRules.viewLinks(both, calendarInstalled = false, alarmInstalled = true, todoInstalled = true))
        assertEquals(PanelRules.ViewLinks(calendar = true, alarm = false, todo = false), PanelRules.viewLinks(both, calendarInstalled = true, alarmInstalled = false, todoInstalled = true))
        assertFalse(PanelRules.viewLinks(both, calendarInstalled = false, alarmInstalled = false, todoInstalled = false).any)
    }

    @Test fun `a to-do link shows only when to-dos were created and the app is installed`() {
        val withTodo = ScheduleSummary(listOf(created(ItemKind.TODO, "t1", "todo_create"), created(ItemKind.EVENT, "e1", "event_create")))
        assertEquals(PanelRules.ViewLinks(calendar = true, alarm = false, todo = true), PanelRules.viewLinks(withTodo, calendarInstalled = true, alarmInstalled = true, todoInstalled = true))
        assertEquals(PanelRules.ViewLinks(calendar = true, alarm = false, todo = false), PanelRules.viewLinks(withTodo, calendarInstalled = true, alarmInstalled = true, todoInstalled = false))
        assertFalse(PanelRules.viewLinks(both, calendarInstalled = false, alarmInstalled = false, todoInstalled = true).any)
    }

    @Test fun `no link for something that was not created`() {
        val onlyEvent = ScheduleSummary(listOf(created(ItemKind.EVENT, "e1", "event_create"), ScheduleItem("a1", ItemKind.ALARM, "alarm_create", ItemStatus.DENIED)))
        assertEquals(PanelRules.ViewLinks(calendar = true, alarm = false, todo = false), PanelRules.viewLinks(onlyEvent, calendarInstalled = true, alarmInstalled = true, todoInstalled = true))
        assertFalse(PanelRules.viewLinks(ScheduleSummary(emptyList()), calendarInstalled = true, alarmInstalled = true, todoInstalled = true).any)
    }

    @Test fun `no model and denied point to AgentOS when it is installed`() {
        for (e in listOf(AgentOsError.NO_MODEL, AgentOsError.DENIED)) {
            assertEquals(Primary.OPEN_AGENTOS, PanelRules.errorActions(e, false, true, agentOsInstalled = true).primary)
        }
        // NO_MODEL 还可以回来再试；DENIED 要先去 AgentOS 里改，不给“再试一次”
        assertTrue(PanelRules.errorActions(AgentOsError.NO_MODEL, false, true, true).retryToo)
        assertFalse(PanelRules.errorActions(AgentOsError.DENIED, false, true, true).retryToo)
    }

    @Test fun `an authorization timeout is a refusal with a cooldown, so it sends you to AgentOS and does not offer a retry`() {
        val a = PanelRules.errorActions(AgentOsError.AUTHORIZATION_PENDING_TIMEOUT, false, true, agentOsInstalled = true)
        assertEquals(Primary.OPEN_AGENTOS, a.primary)
        assertFalse(a.retryToo)
        assertEquals("without AgentOS installed there is nothing to open", Primary.NONE, PanelRules.errorActions(AgentOsError.AUTHORIZATION_PENDING_TIMEOUT, false, true, agentOsInstalled = false).primary)
    }

    @Test fun `not installed offers to learn more and never retry`() {
        val a = PanelRules.errorActions(AgentOsError.NOT_INSTALLED, false, true, agentOsInstalled = false)
        assertEquals(Primary.LEARN_MORE, a.primary)
        assertFalse(a.retryToo)
    }

    @Test fun `too large cannot be retried, the others can`() {
        assertEquals(Primary.NONE, PanelRules.errorActions(AgentOsError.TOO_LARGE, false, true, true).primary)
        for (e in listOf(AgentOsError.BUSY, AgentOsError.RATE_LIMITED, AgentOsError.DISCONNECTED, AgentOsError.FAILED)) {
            assertEquals(e.name, Primary.RETRY, PanelRules.errorActions(e, false, true, true).primary)
        }
    }

    @Test fun `an interrupted run is never retried automatically or by button`() {
        val a = PanelRules.errorActions(AgentOsError.DISCONNECTED, interrupted = true, hasSourceText = false, agentOsInstalled = true)
        assertEquals(Primary.NONE, a.primary)
        assertFalse(a.retryToo)
        assertFalse(a.hasNextStep)
    }

    @Test fun `without source text there is nothing to retry`() {
        assertEquals(Primary.NONE, PanelRules.errorActions(AgentOsError.FAILED, false, hasSourceText = false, agentOsInstalled = true).primary)
    }

    @Test fun `no open-AgentOS button when AgentOS is missing`() {
        assertEquals(Primary.RETRY, PanelRules.errorActions(AgentOsError.NO_MODEL, false, true, agentOsInstalled = false).primary)
    }
}
