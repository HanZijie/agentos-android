package org.agentos.app.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.ConsentDecision
import org.agentos.runtime.ports.ConsentRequest
import org.agentos.runtime.ports.HookDecision
import org.agentos.runtime.ports.HookRequest
import org.agentos.runtime.ports.ToolInvocation
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** HostPortImpl 里 M1 还没开放的端口：行为明确，不会假装成功。 */
class NotOpenPortsTest {
    private val caller = CallerIdentity(10123, CallerKind.SELF, "AgentOS")

    @Test
    fun toolsAreEmptyAndNotDispatched() = runBlocking {
        assertTrue(NotOpen.TOOLS.catalog.value.tools.isEmpty())
        val r = NotOpen.TOOLS.invoke(ToolInvocation("s", "t", "c", "fs_read", JsonObject(emptyMap()), caller, 1_000))
        assertTrue(r is ToolInvocationResult.NotDispatched)
        assertEquals(ErrorCode.TOOL_NOT_IN_CATALOG, (r as ToolInvocationResult.NotDispatched).error.code)
    }

    @Test
    fun consentDeniesAsUnavailable() = runBlocking {
        val d = NotOpen.CONSENT.request(
            ConsentRequest("r", "s", "t", "c", "fs_write", null, ToolRisk.WRITE, caller, "{}", rememberable = true),
        )
        assertEquals(ConsentDecision.Deny(ConsentDecision.DenyReason.UNAVAILABLE), d)
    }

    @Test
    fun skillsAndHooks() = runBlocking {
        assertTrue(NotOpen.SKILLS.catalog.value.skills.isEmpty())
        try {
            NotOpen.SKILLS.read("any")
            fail("skills should not be readable")
        } catch (e: NoSuchElementException) {
            assertTrue(e.message!!.contains("not open"))
        }
        assertEquals(HookDecision.NO_OPINION, NotOpen.HOOKS.dispatch(HookRequest("PreToolUse", "s", null, JsonObject(emptyMap()))).decision)
    }
}
