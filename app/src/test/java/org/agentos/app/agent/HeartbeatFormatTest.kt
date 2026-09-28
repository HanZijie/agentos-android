package org.agentos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 心跳格式 v1（监督契约 S2 b）：只有整数和固定枚举，不超过 16 行、512 字节。 */
class HeartbeatFormatTest {
    @Test
    fun rendersContractFields() {
        val text = HeartbeatFormat.render(pid = 12345, startMs = 81234, atMs = 95321, bootCount = 17, tasks = 1, fg = true, state = "busy")
        assertEquals("v=1\npid=12345\nstart=81234\nat=95321\nboot=17\ntasks=1\nfg=1\nstate=busy\n", text)
        val f = HeartbeatFormat.parse(text)
        assertEquals("1", f["tasks"])
        assertEquals("1", f["fg"])
        assertEquals("busy", f["state"])
    }

    @Test
    fun staysSmallAndAscii() {
        val text = HeartbeatFormat.render(Int.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, false, "recovering")
        assertTrue(text.length <= HeartbeatFormat.MAX_BYTES)
        assertTrue(text.lines().size <= 16)
        assertTrue(text.all { it.code in 0x0a..0x7e })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownState() {
        HeartbeatFormat.render(1, 1, 1, 1, 0, false, "busy; rm -rf /")
    }

    @Test
    fun everyLifecycleStateIsAContractState() {
        for (s in listOf("starting", "recovering", "idle", "busy", "crashed")) assertTrue(s in HeartbeatFormat.STATES)
    }
}
