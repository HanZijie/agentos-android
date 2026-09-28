package org.agentos.app.agent

import android.content.Context
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.agentos.runtime.ports.StoragePort
import java.io.File

/**
 * `HostPort.storage` 的 Android 实现（W6）：Store 的 SQLite。
 *
 * - 驱动：`BundledSQLiteDriver`（与 core:runtime 的电脑测试同一份 SQLite，不随系统版本变化）。
 * - 位置：`:agent` 的 **CE** 私有目录 `databases/agentos-runtime.db`（会话内容只在用户解锁后可读）。
 *   心跳文件仍在 DE（`files/supervisor/heartbeat`，监督进程要在解锁前读）。AgentService 不是 directBootAware，
 *   所以 `:agent` 只会在解锁后运行，CE 目录一定可用。
 * - schema、迁移、WAL 等 PRAGMA 都由 core:runtime 的 Store 负责，这里只给驱动和路径。
 */
class AndroidStore(context: Context) : StoragePort {
    override val driver: SQLiteDriver = BundledSQLiteDriver()

    private val file: File

    /** 这个进程打开 Store 之前数据库文件是否已经存在（诊断用：false 表示本次启动新建了库）。 */
    val existedAtStart: Boolean

    init {
        val ce = context.applicationContext
        check(!ce.isDeviceProtectedStorage) { "the runtime store must live in credential-encrypted storage" }
        file = ce.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        existedAtStart = file.exists()
    }

    override val databasePath: String get() = file.absolutePath

    /** 数据库、WAL、SHM 三个文件的总字节数。 */
    fun sizeBytes(): Long = SUFFIXES.sumOf { File(file.path + it).takeIf(File::isFile)?.length() ?: 0L }

    companion object {
        const val DB_NAME = "agentos-runtime.db"
        val SUFFIXES = listOf("", "-wal", "-shm", "-journal")
    }
}
