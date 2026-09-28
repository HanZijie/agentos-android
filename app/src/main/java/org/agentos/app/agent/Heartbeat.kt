package org.agentos.app.agent

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * 心跳文件格式 v1（监督契约 S2 b）：ASCII，每行 `key=value`，不超过 16 行、512 字节。
 * root 监督进程只认整数字段和固定的 state 枚举，这里也只写这些。不许写进心跳：key、prompt、会话内容、错误详情。
 */
object HeartbeatFormat {
    const val VERSION = 1
    const val MAX_BYTES = 512
    val STATES = setOf("starting", "recovering", "idle", "busy", "stopping", "crashed")

    fun render(pid: Int, startMs: Long, atMs: Long, bootCount: Int, tasks: Int, fg: Boolean, state: String): String {
        require(state in STATES) { "unknown heartbeat state $state" }
        require(tasks >= 0) { "tasks must be >= 0" }
        val text = buildString {
            append("v=").append(VERSION).append('\n')
            append("pid=").append(pid).append('\n')
            append("start=").append(startMs).append('\n')
            append("at=").append(atMs).append('\n')
            append("boot=").append(bootCount).append('\n')
            append("tasks=").append(tasks).append('\n')
            append("fg=").append(if (fg) 1 else 0).append('\n')
            append("state=").append(state).append('\n')
        }
        check(text.length <= MAX_BYTES)
        return text
    }

    /** 读回整数和 state 字段（诊断与测试用），与监督进程的解析方式一致：只认 `key=value`。 */
    fun parse(text: String): Map<String, String> =
        text.lineSequence().take(16).mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.toMap()
}

/**
 * 心跳文件的写入：DE 存储的 `files/supervisor/heartbeat`，即
 * `/data/user_de/0/org.agentos.app/files/supervisor/heartbeat`。先写 `heartbeat.tmp` 再 rename 覆盖，
 * 监督进程读到的总是完整文件。
 */
class HeartbeatWriter(context: Context) {
    private val de = context.createDeviceProtectedStorageContext()
    private val resolver = context.contentResolver
    val file: File = File(de.filesDir, "supervisor/heartbeat")

    @Volatile var lastStatus: RuntimeLifecycle.Status? = null
        private set
    @Volatile var writes = 0L
        private set
    @Volatile var lastError: String? = null
        private set

    private val bootCount: Int by lazy {
        try {
            Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT)
        } catch (e: Exception) {
            -1
        }
    }

    @Synchronized
    fun write(status: RuntimeLifecycle.Status, stateOverride: String? = null) {
        val text = HeartbeatFormat.render(
            pid = Process.myPid(),
            startMs = Process.getStartElapsedRealtime(),
            atMs = SystemClock.elapsedRealtime(),
            bootCount = bootCount,
            tasks = status.tasks,
            fg = status.foreground,
            state = stateOverride ?: status.state,
        )
        try {
            val dir = file.parentFile!!
            if (!dir.isDirectory) dir.mkdirs()
            val tmp = File(dir, "heartbeat.tmp")
            tmp.writeText(text, Charsets.US_ASCII)
            if (!tmp.renameTo(file)) file.writeText(text, Charsets.US_ASCII)
            lastStatus = status
            writes++
            lastError = null
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName
            Log.w(TAG, "heartbeat write failed: ${e.javaClass.simpleName}")
        }
    }

    fun read(): String? = try {
        file.readText(Charsets.US_ASCII)
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TAG = "AgentHeartbeat"
    }
}
