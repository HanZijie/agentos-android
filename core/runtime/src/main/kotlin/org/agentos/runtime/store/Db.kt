package org.agentos.runtime.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 一个 SQLite 连接，所有读写都在它自己的单线程上串行执行（SQLiteConnection 不是线程安全的）。
 * 写操作一律在 `BEGIN IMMEDIATE … COMMIT` 里完成；异常时回滚。
 */
internal class Db private constructor(
    private val conn: SQLiteConnection,
    private val executor: ExecutorService,
) {
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    @Volatile private var closed = false

    suspend fun <T> read(block: (DbScope) -> T): T = withContext(dispatcher) {
        check(!closed) { "store is closed" }
        block(DbScope(conn))
    }

    /** 在一个事务里执行 [block]；提交成功后才返回。 */
    suspend fun <T> write(block: (DbScope) -> T): T = withContext(dispatcher) {
        check(!closed) { "store is closed" }
        conn.execSQL("BEGIN IMMEDIATE")
        val result = try {
            block(DbScope(conn))
        } catch (e: Throwable) {
            runCatching { conn.execSQL("ROLLBACK") }
            throw e
        }
        conn.execSQL("COMMIT")
        result
    }

    suspend fun close() {
        if (closed) return
        withContext(dispatcher) {
            closed = true
            conn.close()
        }
        executor.shutdown()
    }

    companion object {
        fun open(connect: () -> SQLiteConnection): Db {
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "agentos-store").apply { isDaemon = true } }
            val conn = executor.submit<SQLiteConnection> {
                connect().also { c ->
                    // WAL：读写不互相阻塞；FULL：每次提交都落盘（进程被杀、重启都不丢已提交的事件）
                    c.prepare("PRAGMA journal_mode=WAL").use { it.step() }
                    c.execSQL("PRAGMA synchronous=FULL")
                    c.execSQL("PRAGMA foreign_keys=ON")
                    c.execSQL("PRAGMA busy_timeout=5000")
                }
            }.get()
            return Db(conn, executor)
        }
    }
}

/** 事务或读操作里可用的 SQL 辅助。参数按顺序绑定：String、Int、Long、Boolean、Double、null。 */
internal class DbScope(private val conn: SQLiteConnection) {
    fun exec(sql: String, vararg args: Any?) {
        conn.prepare(sql).use { st ->
            bind(st, args)
            while (st.step()) Unit
        }
    }

    /** 执行写语句，返回受影响的行数。 */
    fun update(sql: String, vararg args: Any?): Int {
        exec(sql, *args)
        return conn.prepare("SELECT changes()").use { st -> st.step(); st.getLong(0).toInt() }
    }

    fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T> = conn.prepare(sql).use { st ->
        bind(st, args)
        val row = Row(st)
        buildList { while (st.step()) add(map(row)) }
    }

    fun <T> queryOne(sql: String, vararg args: Any?, map: (Row) -> T): T? = query(sql, *args, map = map).firstOrNull()

    fun execScript(sql: String) {
        sql.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { conn.execSQL(it) }
    }

    private fun bind(st: SQLiteStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, v ->
            val idx = i + 1
            when (v) {
                null -> st.bindNull(idx)
                is String -> st.bindText(idx, v)
                is Int -> st.bindLong(idx, v.toLong())
                is Long -> st.bindLong(idx, v)
                is Boolean -> st.bindLong(idx, if (v) 1 else 0)
                is Double -> st.bindDouble(idx, v)
                is Enum<*> -> st.bindText(idx, v.name.lowercase())
                else -> error("unsupported SQL argument type ${v.javaClass}")
            }
        }
    }
}

/** 查询结果的一行（列号从 0 开始）。 */
internal class Row(private val st: SQLiteStatement) {
    fun string(i: Int): String = st.getText(i)

    fun stringOrNull(i: Int): String? = if (st.isNull(i)) null else st.getText(i)

    fun long(i: Int): Long = st.getLong(i)

    fun longOrNull(i: Int): Long? = if (st.isNull(i)) null else st.getLong(i)

    fun int(i: Int): Int = st.getLong(i).toInt()

    fun bool(i: Int): Boolean = st.getLong(i) != 0L
}
