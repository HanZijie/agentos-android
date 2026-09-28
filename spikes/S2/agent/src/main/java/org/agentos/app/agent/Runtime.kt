package org.agentos.app.agent

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.agentos.app.spike.SpikeLog
import java.io.File

/**
 * Process-level runtime stand-in for the :agent process. Holds fake tasks, persists them so a
 * restarted process can run a "recovery" pass, and keeps the heartbeat in sync.
 *
 * Unlike the real runtime (which must not replay started tasks, architecture F8), the spike
 * resumes recovered tasks so that the kill / crash-loop cases can be repeated on one task.
 */
object Runtime {
    private const val TICK_MS = 1_000L
    private const val HB_REFRESH_MS = 30_000L

    class Task(val id: Long, val startedAt: Long, val durationMs: Long, val ballastMb: Int) {
        var ballast: Array<ByteArray>? = null
        fun expired(now: Long) = durationMs > 0 && now >= startedAt + durationMs
    }

    private lateinit var app: Context
    private val handler = Handler(Looper.getMainLooper())
    private val tasks = mutableListOf<Task>()
    private var loaded = false
    private var ticking = false
    private var lastRefresh = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile var service: AgentService? = null
    @Volatile var foreground = false
    @Volatile var lastFgError = ""

    fun onProcessStart(ctx: Context) {
        app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                SpikeLog.i(app, "CRASH ${e.javaClass.simpleName}: ${e.message}")
                Heartbeat.write(app, count(), foreground, "crashed")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
        SpikeLog.i(app, "PROCESS_START pid=${android.os.Process.myPid()}")
        logExitReasons()
    }

    /** Recovery pass: runs once per process, before any task is accepted. */
    @Synchronized
    fun ensureLoaded(reason: String) {
        if (loaded) return
        loaded = true
        Heartbeat.write(app, 0, foreground, "recovering")
        val now = System.currentTimeMillis()
        var recovered = 0
        tasksFile().takeIf { it.exists() }?.readLines()?.forEach { line ->
            val p = line.trim().split(' ')
            if (p.size != 4) return@forEach
            val t = Task(p[0].toLong(), p[1].toLong(), p[2].toLong(), p[3].toInt())
            if (t.expired(now)) {
                SpikeLog.i(app, "TASK_EXPIRED_WHILE_DEAD id=${t.id}")
            } else {
                allocate(t)
                tasks += t
                recovered++
                SpikeLog.i(app, "TASK_RECOVERED id=${t.id} ballastMb=${t.ballastMb}")
            }
        }
        persist()
        SpikeLog.i(app, "RECOVERY_DONE reason=$reason recovered=$recovered")
        onChanged()
    }

    @Synchronized
    fun add(durationMs: Long, ballastMb: Int): Task {
        ensureLoaded("task")
        val t = Task(System.currentTimeMillis(), System.currentTimeMillis(), durationMs, ballastMb)
        allocate(t)
        tasks += t
        persist()
        SpikeLog.i(app, "TASK_ADDED id=${t.id} durationMs=$durationMs ballastMb=$ballastMb")
        onChanged()
        return t
    }

    @Synchronized
    fun clear() {
        ensureLoaded("clear")
        SpikeLog.i(app, "TASKS_CLEARED n=${tasks.size}")
        tasks.clear()
        persist()
        onChanged()
    }

    @Synchronized
    fun count() = tasks.size

    /**
     * Ask for foreground from inside :agent. From the background this is expected to fail with
     * ForegroundServiceStartNotAllowedException unless the App is exempt from battery
     * optimizations; the heartbeat then shows tasks>0 fg=0 and the supervisor promotes it.
     */
    fun requestForeground(ctx: Context, why: String) {
        if (foreground) return
        try {
            ctx.startForegroundService(
                Intent(ctx, AgentService::class.java).setAction(AgentService.ACTION_SYNC)
            )
            lastFgError = ""
            SpikeLog.i(app, "FGS_SELF_START ok why=$why")
        } catch (e: ForegroundServiceStartNotAllowedException) {
            lastFgError = "not_allowed"
            SpikeLog.i(app, "FGS_SELF_START denied why=$why: ${e.message}")
            writeHeartbeat()
        } catch (e: Exception) {
            lastFgError = e.javaClass.simpleName
            SpikeLog.i(app, "FGS_SELF_START error why=$why: $e")
            writeHeartbeat()
        }
    }

    @Synchronized
    fun onChanged() {
        writeHeartbeat()
        if (tasks.isNotEmpty()) {
            if (wakeLock == null) {
                val pm = app.getSystemService(PowerManager::class.java)
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agentos:s2-task").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (!ticking) {
                ticking = true
                handler.postDelayed(tick, TICK_MS)
            }
        } else {
            wakeLock?.release()
            wakeLock = null
            service?.onIdle()
        }
    }

    @Synchronized
    fun writeHeartbeat() {
        val state = when {
            !loaded -> "starting"
            tasks.isEmpty() -> "idle"
            else -> "busy"
        }
        Heartbeat.write(app, tasks.size, foreground, state)
        lastRefresh = android.os.SystemClock.elapsedRealtime()
    }

    private val tick = object : Runnable {
        override fun run() {
            synchronized(this@Runtime) {
                ticking = false
                val now = System.currentTimeMillis()
                val done = tasks.filter { it.expired(now) }
                if (done.isNotEmpty()) {
                    done.forEach { SpikeLog.i(app, "TASK_DONE id=${it.id}") }
                    tasks.removeAll(done)
                    persist()
                    onChanged()
                    return
                }
                if (android.os.SystemClock.elapsedRealtime() - lastRefresh >= HB_REFRESH_MS) {
                    writeHeartbeat()
                }
                if (tasks.isNotEmpty()) {
                    ticking = true
                    handler.postDelayed(this, TICK_MS)
                }
            }
        }
    }

    private fun allocate(t: Task) {
        if (t.ballastMb <= 0) return
        // 1 MiB chunks, every page touched so the memory shows up in PSS.
        t.ballast = Array(t.ballastMb) { ByteArray(1 shl 20).also { b -> for (i in b.indices step 4096) b[i] = 1 } }
    }

    private fun tasksFile() = File(SpikeLog.dir(app), "tasks")

    private fun persist() {
        tasksFile().writeText(tasks.joinToString("") { "${it.id} ${it.startedAt} ${it.durationMs} ${it.ballastMb}\n" })
    }

    private fun logExitReasons() {
        val seenFile = File(SpikeLog.dir(app), "exit-seen")
        val seen = seenFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
        val am = app.getSystemService(ActivityManager::class.java)
        val infos = am.getHistoricalProcessExitReasons(app.packageName, 0, 20)
            .filter { it.processName.endsWith(":agent") && it.timestamp > seen }
            .sortedBy { it.timestamp }
        for (info in infos) {
            SpikeLog.i(
                app,
                "EXIT_INFO pid=${info.pid} reason=${reasonName(info.reason)} status=${info.status} " +
                    "importance=${info.importance} pssKb=${info.pss} rssKb=${info.rss} desc=${info.description}"
            )
        }
        infos.lastOrNull()?.let { seenFile.writeText(it.timestamp.toString()) }
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "code_$r"
    }
}
