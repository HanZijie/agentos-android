package org.agentos.app.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.agentos.app.agent.AgentControlService
import org.agentos.internal.IAgentControl

/**
 * Main process → `:agent` over the internal, non-exported IAgentControl (architecture 5.4).
 * Binds for one call batch and unbinds; binding starts `:agent` when it is not running.
 * Calls run on Dispatchers.IO. RemoteException / the documented IllegalArgumentException /
 * IllegalStateException ("agentos.byok.<code>: …") propagate to the caller.
 */
class AgentControlClient(private val context: Context) {
    suspend fun <T> use(timeoutMs: Long = 15_000, block: (IAgentControl) -> T): T {
        val connected = CompletableDeferred<IBinder?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                connected.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
            override fun onNullBinding(name: ComponentName?) {
                connected.complete(null)
            }
        }
        val intent = Intent(context, AgentControlService::class.java)
        check(context.bindService(intent, conn, Context.BIND_AUTO_CREATE)) { "cannot bind AgentControlService" }
        try {
            val binder = withTimeoutOrNull(timeoutMs) { connected.await() } ?: error("AgentControlService not connected")
            val control = IAgentControl.Stub.asInterface(binder)
            return withContext(Dispatchers.IO) { block(control) }
        } finally {
            runCatching { context.unbindService(conn) }
        }
    }

    companion object {
        /** IAgentControl version that added BYOK (C3). */
        const val BYOK_VERSION = 2
        /** IAgentControl version that is expected to add the desktop access switch (W9, A). */
        const val DESKTOP_VERSION = 3
        /** IAgentControl version that added the Jev (auto-select session) key and endpoint (D5.1). */
        const val JEV_VERSION = 4
    }
}
