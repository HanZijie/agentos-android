package org.agentos.app.spike

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Append-only evidence log in device-protected storage, readable by root for the test driver. */
object SpikeLog {
    private const val TAG = "S2"
    private const val MAX_BYTES = 1_000_000L
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun dir(ctx: Context): File =
        File(ctx.createDeviceProtectedStorageContext().filesDir, "spike").apply { mkdirs() }

    @Synchronized
    fun i(ctx: Context, msg: String) {
        val proc = Application.getProcessName().substringAfter(':', "main")
        val line = "${fmt.format(Date())} up=${SystemClock.elapsedRealtime()} [$proc] $msg"
        Log.i(TAG, line)
        try {
            val f = File(dir(ctx), "events.log")
            if (f.length() > MAX_BYTES) f.renameTo(File(f.parentFile, "events.log.1"))
            f.appendText(line + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "log write failed", e)
        }
    }

    fun tail(ctx: Context, lines: Int): String = try {
        File(dir(ctx), "events.log").readLines().takeLast(lines).joinToString("\n")
    } catch (e: Exception) {
        "(no events)"
    }
}
