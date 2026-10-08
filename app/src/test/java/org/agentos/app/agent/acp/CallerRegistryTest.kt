package org.agentos.app.agent.acp

import org.agentos.acp.AcpServiceContract
import org.agentos.extensions.registry.AtomicTextFile
import org.agentos.runtime.ports.CallerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** CallerRegistry（授权记录）与 AcpAccessPolicy（准入）：docs/third-party-acp.md 4.1。 */
class CallerRegistryTest {

    private class MemFile(var text: String? = null, var failWrites: Boolean = false, var failReads: Boolean = false) : AtomicTextFile {
        override fun readText(): String? {
            if (failReads) throw IOException("read failed")
            return text
        }

        override fun writeAtomically(text: String) {
            if (failWrites) throw IOException("disk full")
            this.text = text
        }
    }

    private class Events : CallerListener {
        val pending = ArrayList<CallerEntry>()
        val resolved = ArrayList<Triple<String, CallerState, CallerResolution>>()
        val revoked = ArrayList<Pair<String, String>>()
        override fun onPending(entry: CallerEntry) { pending += entry }
        override fun onResolved(requestId: String, state: CallerState, how: CallerResolution) { resolved += Triple(requestId, state, how) }
        override fun onRevoked(packageName: String, signingDigest: String) { revoked += packageName to signingDigest }
    }

    private class Rig(
        val file: MemFile = MemFile(),
        val backup: MemFile? = MemFile(),
        val quarantine: MemFile = MemFile(),
        val config: CallerConfig = CallerConfig(),
    ) {
        var now = 1_000_000L
        private var ids = 0
        val events = Events()
        fun registry() = CallerRegistry(file, backup, quarantine, config, { now }, { "req-${++ids}" }).also { it.setListener(events) }
    }

    private val notes = "org.agentos.sample.notes"
    private val digestA = "a".repeat(64)
    private val digestB = "b".repeat(64)

    @Test
    fun `an unknown app becomes pending once, repeated opens reuse the request`() {
        val r = Rig(); val reg = r.registry()
        val a1 = reg.admit(notes, digestA, "Notes")
        val a2 = reg.admit(notes, digestA, "Notes")
        assertEquals(Admission.Pending("req-1"), a1)
        assertEquals(a1, a2)
        assertEquals(1, r.events.pending.size) // one card, not one per retry
        assertEquals(CallerState.PENDING, reg.entry(notes)!!.state)
        assertNull(r.file.text) // pending is not persisted
    }

    @Test
    fun `allow then open is admitted with the app label, and the decision survives a restart`() {
        val r = Rig(); val reg = r.registry()
        reg.admit(notes, digestA, "Notes")
        assertTrue(reg.decide("req-1", allow = true))
        assertEquals(Admission.Allowed("Notes"), reg.admit(notes, digestA, "Notes"))
        assertEquals(listOf(Triple("req-1", CallerState.ALLOWED, CallerResolution.ANSWERED)), r.events.resolved)
        val again = r.registry()
        assertEquals(Admission.Allowed("Notes"), again.admit(notes, digestA, "Notes"))
        // the same answer twice is not taken twice
        assertFalse(reg.decide("req-1", allow = true))
    }

    @Test
    fun `deny starts a ten minute cooldown without a new card, then asks again`() {
        val r = Rig(); val reg = r.registry()
        reg.admit(notes, digestA, "Notes")
        assertTrue(reg.decide("req-1", allow = false))
        val t0 = r.now
        r.now = t0 + 9 * 60_000
        val denied = reg.admit(notes, digestA, "Notes")
        assertEquals(Admission.Denied(t0 + 10 * 60_000), denied)
        assertEquals(1, r.events.pending.size)
        r.now = t0 + 10 * 60_000 + 1
        val asked = reg.admit(notes, digestA, "Notes")
        assertTrue(asked is Admission.Pending)
        assertEquals(2, r.events.pending.size)
    }

    @Test
    fun `an unanswered request counts as a denial after the ttl`() {
        val r = Rig(); val reg = r.registry()
        reg.admit(notes, digestA, "Notes")
        r.now += 101_000
        val a = reg.admit(notes, digestA, "Notes")
        assertTrue(a is Admission.Denied)
        assertEquals(Triple("req-1", CallerState.DENIED, CallerResolution.TIMED_OUT), r.events.resolved.single())
        assertFalse(reg.decide("req-1", allow = true)) // too late
    }

    @Test
    fun `a changed signing digest is a new app and the old grant is revoked`() {
        val r = Rig(); val reg = r.registry()
        reg.admit(notes, digestA, "Notes"); reg.decide("req-1", true)
        val a = reg.admit(notes, digestB, "Notes")
        assertTrue(a is Admission.Pending)
        assertEquals(listOf(notes to digestA), r.events.revoked)
        val e = reg.entry(notes)!!
        assertEquals(digestB, e.signingDigest)
        assertEquals(CallerState.PENDING, e.state)
        // the old digest does not come back as allowed
        assertTrue(reg.admit(notes, digestA, "Notes") is Admission.Pending)
    }

    @Test
    fun `a card nobody answered is withdrawn when it is superseded or removed, and signature changes are flagged`() {
        val r = Rig(); val reg = r.registry()
        assertFalse(reg.admit(notes, digestA, "Notes").let { reg.entry(notes)!!.signatureChanged })
        // the app comes back with another signature before the user answered: the old card is withdrawn, the new one says "changed"
        reg.admit(notes, digestB, "Notes")
        assertEquals(Triple("req-1", CallerState.DENIED, CallerResolution.CANCELLED), r.events.resolved.single())
        assertTrue(reg.entry(notes)!!.signatureChanged)
        assertEquals("req-2", reg.entry(notes)!!.requestId)
        // deleting a pending record withdraws its card too
        assertNull(reg.set(notes, "removed"))
        assertEquals(Triple("req-2", CallerState.DENIED, CallerResolution.CANCELLED), r.events.resolved.last())
        // a plain first request or a re-ask after the cooldown is not a signature change
        reg.admit(notes, digestA, "Notes"); reg.decide("req-3", false)
        r.now += 11 * 60_000
        reg.admit(notes, digestA, "Notes")
        assertFalse(reg.entry(notes)!!.signatureChanged)
    }

    @Test
    fun `revoking sets denied with cooldown and closes channels, removing forgets the app`() {
        val r = Rig(); val reg = r.registry()
        reg.admit(notes, digestA, "Notes"); reg.decide("req-1", true)
        val denied = reg.set(notes, "denied")!!
        assertEquals(CallerState.DENIED, denied.state)
        assertEquals(r.now + 10 * 60_000, denied.deniedUntil)
        assertEquals(listOf(notes to digestA), r.events.revoked)
        assertTrue(reg.admit(notes, digestA, "Notes") is Admission.Denied)
        // settings can turn a denied app back to allowed and clears the cooldown
        assertEquals(CallerState.ALLOWED, reg.set(notes, "allowed")!!.state)
        assertEquals(Admission.Allowed("Notes"), reg.admit(notes, digestA, "Notes"))
        assertNull(reg.set(notes, "removed"))
        assertEquals(2, r.events.revoked.size)
        assertTrue(reg.admit(notes, digestA, "Notes") is Admission.Pending)
    }

    @Test
    fun `set validates the app and the state`() {
        val reg = Rig().registry()
        try { reg.set("nope.app", "allowed"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.startsWith("agentos.acp.not_found")) }
        reg.admit(notes, digestA, "Notes")
        try { reg.set(notes, "maybe"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.startsWith("agentos.acp.bad_state")) }
        try { reg.set(null, "allowed"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.startsWith("agentos.acp.not_found")) }
    }

    @Test
    fun `a failed write keeps the old state and reports registry_unavailable`() {
        val r = Rig(); val reg = r.registry()
        reg.admit(notes, digestA, "Notes")
        r.file.failWrites = true
        try { reg.decide("req-1", true); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.startsWith("agentos.acp.registry_unavailable")) }
        assertEquals(CallerState.PENDING, reg.entry(notes)!!.state)
        r.file.failWrites = false
        assertTrue(reg.decide("req-1", true))
    }

    @Test
    fun `a corrupt file uses the backup, without one it starts empty so every app is asked again`() {
        val good = Rig(); val reg = good.registry()
        reg.admit(notes, digestA, "Notes"); reg.decide("req-1", true)
        val goodText = good.file.text!!

        val withBackup = Rig(file = MemFile("not json"), backup = MemFile(goodText))
        val fromBackup = withBackup.registry()
        assertTrue(fromBackup.health is CallerHealth.Corrupt)
        assertEquals(CallerHealth.Using.BACKUP, (fromBackup.health as CallerHealth.Corrupt).using)
        assertEquals(Admission.Allowed("Notes"), fromBackup.admit(notes, digestA, "Notes"))
        assertEquals("not json", withBackup.quarantine.text) // kept for diagnosis

        val noBackup = Rig(file = MemFile("{\"version\":1,\"callers\":\"x\"}"), backup = null)
        val empty = noBackup.registry()
        assertEquals(CallerHealth.Using.EMPTY, (empty.health as CallerHealth.Corrupt).using)
        assertTrue(empty.admit(notes, digestA, "Notes") is Admission.Pending) // never treated as allowed

        val unreadable = Rig(file = MemFile("{}", failReads = true), backup = null).registry()
        assertTrue(unreadable.health is CallerHealth.Corrupt)
    }

    @Test
    fun `an unknown persisted state is read as denied, never as allowed`() {
        val text = """{"version":1,"callers":[{"packageName":"$notes","signingDigest":"$digestA","label":"Notes","state":"superuser","firstSeenAt":1}]}"""
        val reg = Rig(file = MemFile(text)).registry()
        assertEquals(CallerState.DENIED, reg.entry(notes)!!.state)
    }

    @Test
    fun `too many pending requests and a full registry refuse new apps`() {
        val r = Rig(config = CallerConfig(maxPending = 2, maxEntries = 3)); val reg = r.registry()
        assertTrue(reg.admit("a.one", digestA, "One") is Admission.Pending)
        assertTrue(reg.admit("a.two", digestA, "Two") is Admission.Pending)
        assertTrue(reg.admit("a.three", digestA, "Three") is Admission.Refused)
        reg.decide("req-1", true); reg.decide("req-2", true)
        assertTrue(reg.admit("a.three", digestA, "Three") is Admission.Pending)
        reg.decide("req-3", true)
        assertTrue(reg.admit("a.four", digestA, "Four") is Admission.Refused) // full of allowed apps, nothing to evict
    }

    @Test
    fun `usage counts prompts and the list json has fixed keys`() {
        val r = Rig(); val reg = r.registry()
        reg.admit(notes, digestA, "Notes"); reg.decide("req-1", true)
        reg.recordPrompt(notes, digestA); r.now += 30 * 60_000; reg.recordPrompt(notes, digestA)
        r.now += 40 * 60_000 // the first prompt is now older than an hour
        assertEquals(1, reg.promptsLastHour(notes))
        val j = reg.toJson(reg.entry(notes)!!, activeChannels = 1, activeTasks = 0)
        assertEquals(
            listOf("packageName", "label", "signingDigest", "state", "requestId", "firstSeenAt", "decidedAt", "lastUsedAt", "deniedUntil", "requestedAt", "usage"),
            j.keys.toList(),
        )
        assertEquals(listOf("promptsTotal", "promptsLastHour", "activeChannels", "activeTasks"), (j["usage"] as kotlinx.serialization.json.JsonObject).keys.toList())
        assertEquals(kotlinx.serialization.json.JsonNull, j["requestId"])
        assertEquals(kotlinx.serialization.json.JsonNull, j["deniedUntil"])
        // prompts of an app that is not allowed (or has another digest) are not counted
        reg.recordPrompt(notes, digestB)
        assertEquals(2L, reg.entry(notes)!!.promptsTotal)
    }

    @Test
    fun `list is ordered by last use then label`() {
        val r = Rig(); val reg = r.registry()
        for ((i, p) in listOf("a.zeta", "a.alpha", "a.used").withIndex()) { reg.admit(p, digestA, p.substringAfter('.')); reg.decide("req-${i + 1}", true) }
        reg.recordPrompt("a.used", digestA)
        assertEquals(listOf("a.used", "a.alpha", "a.zeta"), reg.entries().map { it.packageName })
    }

    // ------------------------------------------------------------------ AcpAccessPolicy

    private val myUid = 10100
    private fun resolver(vararg known: Pair<Int, ResolvedCaller>) = CallerResolver { uid -> known.toMap()[uid] }
    private val notesApp = ResolvedCaller(notes, digestA, "Notes")

    @Test
    fun `agentos itself is SELF, everyone else is APP with the app label`() {
        val reg = Rig().registry()
        val self = AcpAccessPolicy.decide(myUid, myUid, resolver(), reg) as AcpDecision.Open
        assertEquals(CallerKind.SELF, self.caller.kind)
        assertNull(self.app)

        val pending = AcpAccessPolicy.decide(10200, myUid, resolver(10200 to notesApp), reg) as AcpDecision.Reject
        assertEquals(AcpServiceContract.REASON_AUTHORIZATION_PENDING, pending.reason)
        assertTrue(pending.message.startsWith("agentos.acp.authorization_pending:"))
        reg.decide(reg.pending().single().requestId, true)
        val open = AcpAccessPolicy.decide(10200, myUid, resolver(10200 to notesApp), reg) as AcpDecision.Open
        assertEquals(CallerKind.APP, open.caller.kind) // never SELF: SELF can see every session
        assertEquals("Notes", open.caller.label)
        assertEquals(10200, open.caller.uid)
        assertEquals("uid:10200", open.caller.ownerKey)
        assertEquals(notes, open.app!!.packageName)
    }

    @Test
    fun `a uid that is not exactly one app is not_open, a denied app gets denied`() {
        val reg = Rig().registry()
        val shared = AcpAccessPolicy.decide(10300, myUid, resolver(), reg) as AcpDecision.Reject
        assertEquals(AcpServiceContract.REASON_NOT_OPEN, shared.reason)
        assertTrue(reg.entries().isEmpty()) // nothing remembered for callers we cannot identify

        AcpAccessPolicy.decide(10200, myUid, resolver(10200 to notesApp), reg)
        reg.decide(reg.pending().single().requestId, false)
        val denied = AcpAccessPolicy.decide(10200, myUid, resolver(10200 to notesApp), reg) as AcpDecision.Reject
        assertEquals(AcpServiceContract.REASON_DENIED, denied.reason)
    }

    @Test
    fun `a self reported name has no effect, only the uid decides`() {
        // two apps, the second one claims the first one's package name in its request: open() carries no name at all,
        // and the resolver is the only source of identity
        val reg = Rig().registry()
        val good = ResolvedCaller(notes, digestA, "Notes")
        val evil = ResolvedCaller("com.evil.notes", digestB, "Notes")
        AcpAccessPolicy.decide(10200, myUid, resolver(10200 to good), reg); reg.decide(reg.pending().single().requestId, true)
        val r = resolver(10200 to good, 10201 to evil)
        assertTrue(AcpAccessPolicy.decide(10200, myUid, r, reg) is AcpDecision.Open)
        val d = AcpAccessPolicy.decide(10201, myUid, r, reg)
        assertTrue(d is AcpDecision.Reject) // same label, different package + digest: asked on its own
        assertNotNull(reg.entry("com.evil.notes"))
        assertEquals(CallerState.ALLOWED, reg.entry(notes)!!.state)
    }

    @Test
    fun `cleanLabel strips control characters and bidi marks and truncates`() {
        assertEquals("Notes pro", PackageCallerResolver.cleanLabel("Notes\n\u202Epro"))
        assertEquals(PackageCallerResolver.MAX_LABEL_CHARS, PackageCallerResolver.cleanLabel("x".repeat(500)).length)
        assertEquals("", PackageCallerResolver.cleanLabel("\u0000\u200B  \t"))
    }
}
