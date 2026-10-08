package org.agentos.app.agent.acp

import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind

/**
 * Revoking an app cancels its running tasks (RuntimeEngine.cancelOwner, which reads only the owner key = "uid:<uid>").
 * Which uids to cancel for is decided here, as a pure function so the safety rules are unit-tested:
 *
 * - the uids of the channels that are open right now (read BEFORE they are closed), the uids this package was seen with at admission
 *   (a task outlives its channel, F7: the app may have closed the channel long before the user revokes), and the uid the package has
 *   on the device now (covers a restart of :agent, which forgets the seen uids; recovered tasks keep their owner);
 * - never AgentOS's own uid (that owner key is SELF: its tasks are the user's own) and never a system uid (< 10000);
 * - always an APP identity carrying the package, whatever the source said.
 */
object RevocationOwners {
    /** android.os.Process.FIRST_APPLICATION_UID: below it a uid is not a third-party app. */
    const val FIRST_APP_UID = 10_000

    fun of(
        packageName: String,
        open: List<CallerIdentity>,
        seenUids: Set<Int>,
        installedUid: Int?,
        selfUid: Int,
    ): List<CallerIdentity> {
        val uids = LinkedHashSet<Int>()
        for (c in open) if (c.kind == CallerKind.APP) uids += c.uid
        uids += seenUids
        if (installedUid != null) uids += installedUid
        return uids
            .filter { it >= FIRST_APP_UID && it != selfUid }
            .map { CallerIdentity(uid = it, kind = CallerKind.APP, packageName = packageName) }
    }
}
