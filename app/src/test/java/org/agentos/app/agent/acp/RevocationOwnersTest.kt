package org.agentos.app.agent.acp

import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RevocationOwners: whose tasks a revoke cancels. The point is what it must never include. */
class RevocationOwnersTest {
    private val pkg = "org.example.notes"
    private val self = 10100

    private fun app(uid: Int) = CallerIdentity(uid, CallerKind.APP, label = "Notes", packageName = pkg)

    private fun keys(l: List<CallerIdentity>) = l.map { it.ownerKey }

    @Test
    fun `open channels, seen uids and the installed uid are all covered, once each`() {
        val r = RevocationOwners.of(pkg, listOf(app(10200)), setOf(10200, 10201), 10202, self)
        assertEquals(listOf("uid:10200", "uid:10201", "uid:10202"), keys(r))
        assertTrue(r.all { it.kind == CallerKind.APP && it.packageName == pkg })
    }

    @Test
    fun `a task outlives its channel, so a package with no open channel is still found through the seen uid`() {
        assertEquals(listOf("uid:10200"), keys(RevocationOwners.of(pkg, emptyList(), setOf(10200), null, self)))
        assertEquals(listOf("uid:10200"), keys(RevocationOwners.of(pkg, emptyList(), emptySet(), 10200, self))) // after an :agent restart
    }

    @Test
    fun `AgentOS's own uid is never an owner to cancel, whatever source it came from`() {
        assertTrue(RevocationOwners.of(pkg, listOf(app(self)), setOf(self), self, self).isEmpty())
        assertEquals(listOf("uid:10200"), keys(RevocationOwners.of(pkg, listOf(app(10200), app(self)), setOf(self), self, self)))
    }

    @Test
    fun `system and shell uids are never owners to cancel`() {
        assertTrue(RevocationOwners.of(pkg, listOf(app(1000), app(2000), app(0)), setOf(9999), 1010, self).isEmpty())
        assertEquals(listOf("uid:10000"), keys(RevocationOwners.of(pkg, emptyList(), setOf(10000), null, self)))
    }

    @Test
    fun `identities from other kinds of callers on the open list are ignored, and the result is always APP`() {
        val desktop = CallerIdentity(10300, CallerKind.DESKTOP)
        val selfIdentity = CallerIdentity(10100, CallerKind.SELF)
        assertTrue(RevocationOwners.of(pkg, listOf(desktop, selfIdentity), emptySet(), null, self).isEmpty())
    }

    @Test
    fun `nothing known means nothing to cancel`() {
        assertTrue(RevocationOwners.of(pkg, emptyList(), emptySet(), null, self).isEmpty())
    }
}
