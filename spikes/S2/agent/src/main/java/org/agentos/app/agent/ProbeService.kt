package org.agentos.app.agent

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import org.agentos.app.spike.SpikeLog
import org.agentos.spike.s2.IProbe

/** Stand-in for the exported IAcpService: bind latency and tasks started by another App. */
class ProbeService : Service() {
    @Volatile private var pinged = false

    private val binder = object : IProbe.Stub() {
        override fun ping(clientElapsedRealtime: Long): Long {
            val now = SystemClock.elapsedRealtime()
            if (!pinged) {
                pinged = true
                SpikeLog.i(this@ProbeService, "PROBE_FIRST_PING callerUid=${Binder.getCallingUid()}")
            }
            return now
        }

        override fun startTask(durationMs: Long, ballastMb: Int): String {
            val uid = Binder.getCallingUid()
            val id = Binder.clearCallingIdentity()
            try {
                Runtime.add(durationMs, ballastMb)
                Runtime.requestForeground(this@ProbeService, "probe_task uid=$uid")
                return "tasks=${Runtime.count()} fg=${if (Runtime.foreground) 1 else 0} fgError=${Runtime.lastFgError}"
            } finally {
                Binder.restoreCallingIdentity(id)
            }
        }

        override fun status(): String = Heartbeat.read(this@ProbeService)
    }

    override fun onBind(intent: Intent?): IBinder {
        SpikeLog.i(this, "PROBE_BIND")
        pinged = false
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        SpikeLog.i(this, "PROBE_UNBIND")
        return false
    }
}
