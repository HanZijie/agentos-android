package org.agentos.app.agent.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SupervisorStatusTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Extras exactly as module/service.sh sends them (am broadcast --ei / --el / --es). */
    private fun extras(
        protocol: Any? = 1,
        state: Any? = "ok",
        reason: Any? = "boot",
        seq: Any? = 3L,
        boot: Any? = 7,
    ): (String) -> Any? {
        val m = mapOf(
            SupervisorStatus.EXTRA_PROTOCOL to protocol,
            SupervisorStatus.EXTRA_STATE to state,
            SupervisorStatus.EXTRA_REASON to reason,
            SupervisorStatus.EXTRA_SEQ to seq,
            SupervisorStatus.EXTRA_SINCE to 1_790_000_000_000L,
            SupervisorStatus.EXTRA_DEATHS to 2,
            SupervisorStatus.EXTRA_BOOT_COUNT to boot,
            SupervisorStatus.EXTRA_MODULE_VERSION to "0.1.0",
            SupervisorStatus.EXTRA_MODULE_VERSION_CODE to 1,
            SupervisorStatus.EXTRA_RUNTIME_PID to 1234,
        )
        return { key -> m[key] }
    }

    private fun parse(e: (String) -> Any?) = SupervisorStatus.fromExtras(e, receivedAtMillis = 42L)

    @Test
    fun parsesTheSupervisorsBroadcast() {
        val s = parse(extras())!!
        assertEquals(SupervisorStatus.State.OK, s.state)
        assertEquals("boot", s.reason)
        assertEquals(3L, s.seq)
        assertEquals(7, s.bootCount)
        assertEquals(2, s.deaths)
        assertEquals("0.1.0", s.moduleVersion)
        assertEquals(1234, s.runtimePid)
        assertEquals(42L, s.receivedAtMillis)
    }

    @Test
    fun parsesSafeMode() {
        val s = parse(extras(state = "safe_mode", reason = "crash_loop"))!!
        assertTrue(s.inSafeMode)
        assertEquals("crash_loop", s.reason)
    }

    @Test
    fun rejectsUnknownProtocolStateOrMissingSeq() {
        assertNull(parse(extras(protocol = 2)))
        assertNull(parse(extras(protocol = null)))
        assertNull(parse(extras(protocol = "1"))) // wrong type: sent with --es instead of --ei
        assertNull(parse(extras(state = "exploded")))
        assertNull(parse(extras(seq = null)))
        assertNull(parse(extras(seq = 0L)))
    }

    @Test
    fun unexpectedReasonTextIsNotTrusted() {
        assertEquals("unknown", parse(extras(reason = "crash loop; rm -rf /"))!!.reason)
        assertEquals("unknown", parse(extras(reason = null))!!.reason)
    }

    @Test
    fun orderingIsBootCountThenSeq() {
        val a = parse(extras(seq = 5L, boot = 7))!!
        val b = parse(extras(seq = 6L, boot = 7))!!
        val nextBoot = parse(extras(seq = 1L, boot = 8))!!
        assertTrue(b.isNewerThan(a))
        assertFalse(a.isNewerThan(b))
        assertFalse(a.isNewerThan(a))
        assertTrue(nextBoot.isNewerThan(b)) // SEQ restarts at 1 after a reboot
        assertFalse(b.isNewerThan(nextBoot))
        assertTrue(a.isNewerThan(null))
    }

    @Test
    fun propertiesRoundTrip() {
        val s = parse(extras(state = "backoff", reason = "runtime_died"))!!
        assertEquals(s, SupervisorStatus.fromProperties(s.toProperties()))
        assertNull(SupervisorStatus.fromProperties(""))
        assertNull(SupervisorStatus.fromProperties("protocol=9\nstate=ok\nseq=1\n"))
    }

    @Test
    fun storeKeepsOnlyNewerReports() {
        val store = SupervisorStatusStore(tmp.newFolder("supervisor"))
        assertNull(store.read())
        val first = parse(extras(seq = 2L))!!
        assertTrue(store.offer(first))
        assertFalse(store.offer(parse(extras(seq = 1L))!!)) // late delivery of an older report
        assertEquals(first, store.read())
        val safe = parse(extras(state = "safe_mode", reason = "crash_loop", seq = 3L))!!
        assertTrue(store.offer(safe))
        assertEquals(safe, store.read())
        assertFalse(tmp.root.resolve("supervisor/status.tmp").exists())
    }

    @Test
    fun supervisorMissingAfter30sWithoutAReportFromThisBoot() {
        val lastBoot = parse(extras(boot = 6))!!
        val thisBoot = parse(extras(boot = 7))!!
        assertFalse(SupervisorStatus.supervisorMissing(null, currentBootCount = 7, runtimeUpMillis = 10_000))
        assertTrue(SupervisorStatus.supervisorMissing(null, currentBootCount = 7, runtimeUpMillis = 30_000))
        assertTrue(SupervisorStatus.supervisorMissing(lastBoot, currentBootCount = 7, runtimeUpMillis = 60_000))
        assertFalse(SupervisorStatus.supervisorMissing(thisBoot, currentBootCount = 7, runtimeUpMillis = 60_000))
        assertNotNull(thisBoot)
    }
}
