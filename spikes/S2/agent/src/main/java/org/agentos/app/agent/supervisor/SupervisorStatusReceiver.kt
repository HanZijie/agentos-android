package org.agentos.app.agent.supervisor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.agentos.app.spike.SpikeLog
import java.io.File

/**
 * Contract item e: receives the root supervisor's status broadcast.
 *
 * Registered not exported and guarded by a signature permission; root bypasses both checks
 * (ActivityManager.checkComponentPermission -> canAccessUnexportedComponents), other Apps are
 * rejected. Runs in the main process so a status broadcast never spawns :agent.
 */
open class SupervisorStatusReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "org.agentos.action.SUPERVISOR_STATUS"
        const val PROTOCOL = 1
        private const val P = "org.agentos.extra."
        val STRING_EXTRAS = listOf("STATE", "REASON", "MODULE_VERSION")
        val LONG_EXTRAS = listOf("SEQ", "SINCE")
        val INT_EXTRAS = listOf("PROTOCOL", "DEATHS", "BOOT_COUNT", "MODULE_VERSION_CODE", "RUNTIME_PID")

        fun statusFile(ctx: Context) =
            File(ctx.createDeviceProtectedStorageContext().filesDir, "supervisor/status")
    }

    protected open val variant = "not_exported"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) {
            SpikeLog.i(context, "STATUS_IGNORED variant=$variant action=${intent.action}")
            return
        }
        val fields = linkedMapOf<String, String>()
        STRING_EXTRAS.forEach { k -> intent.getStringExtra(P + k)?.let { fields[k.lowercase()] = it } }
        LONG_EXTRAS.forEach { k -> if (intent.hasExtra(P + k)) fields[k.lowercase()] = intent.getLongExtra(P + k, -1).toString() }
        INT_EXTRAS.forEach { k -> if (intent.hasExtra(P + k)) fields[k.lowercase()] = intent.getIntExtra(P + k, -1).toString() }
        val protocol = fields["protocol"]?.toIntOrNull()
        SpikeLog.i(context, "STATUS_RECEIVED variant=$variant " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" })
        if (protocol != PROTOCOL) return
        val f = statusFile(context)
        f.parentFile?.mkdirs()
        f.writeText(fields.entries.joinToString("") { "${it.key}=${it.value}\n" } + "variant=$variant\n")
    }
}

class SupervisorStatusReceiverExported : SupervisorStatusReceiver() {
    override val variant = "exported"
}
