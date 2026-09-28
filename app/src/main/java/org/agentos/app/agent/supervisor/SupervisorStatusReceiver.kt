package org.agentos.app.agent.supervisor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Receives the root supervisor's status broadcast (contract item e, docs/spikes/S2.md).
 *
 * Declared in the main process, not exported, and guarded by the signature permission
 * [SupervisorStatus.PERMISSION]: root bypasses both checks (verified on API 35 / 37 emulators, S2 P1),
 * other Apps and the shell are rejected. Keeping it out of `:agent` means a status broadcast never
 * starts the runtime process, which would confuse liveness.
 *
 * The receiver only validates and stores; `:agent` and the UI read [SupervisorStatusStore]. Safe mode
 * handling in the App (not resuming recovered tasks, W11) builds on the stored state.
 */
class SupervisorStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SupervisorStatus.ACTION) return
        val extras = intent.extras ?: return
        @Suppress("DEPRECATION") // typed getters would coerce; we want the raw type to reject mismatches
        val status = SupervisorStatus.fromExtras({ key -> extras.get(key) }, System.currentTimeMillis())
        if (status == null) {
            Log.w(TAG, "ignored supervisor status with unknown protocol or malformed fields")
            return
        }
        val stored = store(context).offer(status)
        Log.i(TAG, "supervisor ${status.state.wire}/${status.reason} seq=${status.seq} boot=${status.bootCount} stored=$stored")
    }

    companion object {
        private const val TAG = "AgentOS.Supervisor"

        fun store(context: Context): SupervisorStatusStore =
            SupervisorStatusStore(File(context.createDeviceProtectedStorageContext().filesDir, "supervisor"))
    }
}
