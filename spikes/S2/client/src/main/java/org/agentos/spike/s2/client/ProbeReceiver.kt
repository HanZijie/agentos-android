package org.agentos.spike.s2.client

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.agentos.spike.s2.IProbe
import java.io.File

/**
 * PROBE: bind the agent's ProbeService and measure bind -> first response (S2 case B1), optionally
 *        starting a task from this (background) App (case T1).
 *        extras: task_ms (long; >0 duration, -1 until stopped, 0 none), ballast_mb (int), hold_ms (long)
 * SPOOF: act as "another App" and try to fake the supervisor (case P1).
 */
class ProbeReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "S2PROBE"
        const val AGENT_PKG = "org.agentos.spike.s2"
        const val PROBE_SVC = "org.agentos.app.agent.ProbeService"
        const val AGENT_SVC = "org.agentos.app.agent.AgentService"
        val STATUS_RECEIVERS = listOf(
            "org.agentos.app.agent.supervisor.SupervisorStatusReceiver",
            "org.agentos.app.agent.supervisor.SupervisorStatusReceiverExported",
        )

        fun record(ctx: Context, line: String) {
            Log.i(TAG, line)
            try {
                File(ctx.filesDir, "probe.log").appendText("${System.currentTimeMillis()} $line\n")
            } catch (_: Exception) {
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "org.agentos.spike.s2.client.PROBE" -> probe(context.applicationContext, intent, goAsync())
            "org.agentos.spike.s2.client.SPOOF" -> spoof(context.applicationContext)
        }
    }

    private fun probe(ctx: Context, intent: Intent, pending: PendingResult) {
        val taskMs = intent.getLongExtra("task_ms", 0L)
        val ballastMb = intent.getIntExtra("ballast_mb", 0)
        val holdMs = intent.getLongExtra("hold_ms", 0L)
        val main = Handler(Looper.getMainLooper())
        val t0 = SystemClock.elapsedRealtime()
        var done = false
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val t1 = SystemClock.elapsedRealtime()
                Thread {
                    val probe = IProbe.Stub.asInterface(service)
                    val line = try {
                        probe.ping(t1)
                        val t2 = SystemClock.elapsedRealtime()
                        val task = when {
                            taskMs == 0L -> "none"
                            else -> probe.startTask(if (taskMs < 0) 0 else taskMs, ballastMb)
                        }
                        "PROBE_RESULT bind_ms=${t1 - t0} first_response_ms=${t2 - t0} task=[$task]"
                    } catch (e: Exception) {
                        "PROBE_ERROR bind_ms=${t1 - t0} error=$e"
                    }
                    record(ctx, line)
                    main.postDelayed({ finish(ctx, this, pending) { done = true } }, holdMs)
                }.start()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                record(ctx, "PROBE_DISCONNECTED after_ms=${SystemClock.elapsedRealtime() - t0}")
            }
        }
        val ok = ctx.bindService(
            Intent().setComponent(ComponentName(AGENT_PKG, PROBE_SVC)), conn, Context.BIND_AUTO_CREATE
        )
        if (!ok) {
            record(ctx, "PROBE_ERROR bindService returned false")
            pending.finish()
            return
        }
        main.postDelayed({
            if (!done) {
                record(ctx, "PROBE_TIMEOUT after_ms=${SystemClock.elapsedRealtime() - t0}")
                finish(ctx, conn, pending) { done = true }
            }
        }, 20_000L)
    }

    private fun finish(ctx: Context, conn: ServiceConnection, pending: PendingResult, mark: () -> Unit) {
        mark()
        try {
            ctx.unbindService(conn)
        } catch (_: Exception) {
        }
        try {
            pending.finish()
        } catch (_: Exception) {
        }
    }

    private fun spoof(ctx: Context) {
        for (rcv in STATUS_RECEIVERS) {
            val i = Intent("org.agentos.action.SUPERVISOR_STATUS")
                .setComponent(ComponentName(AGENT_PKG, rcv))
                .putExtra("org.agentos.extra.PROTOCOL", 1)
                .putExtra("org.agentos.extra.STATE", "safe_mode")
                .putExtra("org.agentos.extra.REASON", "spoofed_by_client")
                .putExtra("org.agentos.extra.SEQ", 999999L)
            try {
                ctx.sendBroadcast(i)
                record(ctx, "SPOOF_BROADCAST sent to=$rcv (delivery must be denied by the system)")
            } catch (e: Exception) {
                record(ctx, "SPOOF_BROADCAST threw to=$rcv $e")
            }
        }
        try {
            ctx.startForegroundService(
                Intent("org.agentos.action.SUPERVISOR_START").setComponent(ComponentName(AGENT_PKG, AGENT_SVC))
            )
            record(ctx, "SPOOF_START_SERVICE unexpectedly allowed")
        } catch (e: Exception) {
            record(ctx, "SPOOF_START_SERVICE denied: $e")
        }
    }
}
