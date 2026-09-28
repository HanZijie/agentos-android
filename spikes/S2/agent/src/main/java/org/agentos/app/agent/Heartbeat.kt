package org.agentos.app.agent

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import java.io.File

/**
 * Contract item b: the heartbeat file read by the root supervisor.
 *
 * Path: /data/user_de/0/<package>/files/supervisor/heartbeat (device-protected storage, so the
 * path is fixed and readable before and after the first unlock).
 * Format: ASCII "key=value" lines, at most 512 bytes, written atomically (tmp + rename).
 * The supervisor only trusts integer fields and a small state enum; it never evaluates the file.
 */
object Heartbeat {
    const val VERSION = 1

    @Volatile private var lastTasks = -1
    @Volatile private var lastFg = false
    @Volatile private var lastState = ""

    fun file(ctx: Context): File =
        File(ctx.createDeviceProtectedStorageContext().filesDir, "supervisor/heartbeat")

    @Synchronized
    fun write(ctx: Context, tasks: Int, fg: Boolean, state: String) {
        lastTasks = tasks; lastFg = fg; lastState = state
        val bootCount = try {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (e: Exception) {
            -1
        }
        val text = buildString {
            append("v=").append(VERSION).append('\n')
            append("pid=").append(Process.myPid()).append('\n')
            append("start=").append(Process.getStartElapsedRealtime()).append('\n')
            append("at=").append(SystemClock.elapsedRealtime()).append('\n')
            append("boot=").append(bootCount).append('\n')
            append("tasks=").append(tasks).append('\n')
            append("fg=").append(if (fg) 1 else 0).append('\n')
            append("state=").append(state).append('\n')
        }
        val f = file(ctx)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "heartbeat.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
        }
    }

    /** Re-write with the last values (refreshes "at" while busy). */
    fun refresh(ctx: Context) {
        if (lastTasks >= 0) write(ctx, lastTasks, lastFg, lastState)
    }

    fun read(ctx: Context): String = try {
        file(ctx).readText()
    } catch (e: Exception) {
        "(no heartbeat)"
    }
}
