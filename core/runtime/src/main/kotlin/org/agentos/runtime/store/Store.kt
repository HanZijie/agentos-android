package org.agentos.runtime.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.agentos.runtime.events.EventEnvelope
import org.agentos.runtime.events.PendingEvent
import org.agentos.runtime.ports.Clock
import org.agentos.runtime.ports.StoragePort
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 运行时的 Store：会话、任务、工具调用、事件日志、各会话的 Pi messages，全部在一个 SQLite 数据库里。
 *
 * - 所有写操作在 [write] 里完成，一个 [write] 就是一个事务：状态转换与它的事件一起提交（events.md 6.2）。
 * - 提交之后才通知 [watch] 的订阅者（先写日志，再通知）。订阅者自己按游标从日志里读，不会漏事件。
 */
class Store private constructor(
    private val db: Db,
    private val clock: Clock,
) {
    private val watchers = ConcurrentHashMap<String, MutableStateFlow<Long>>()

    /** 在一个事务里执行 [block]，提交后返回。事务里的事件在提交后通知订阅者。 */
    suspend fun <T> write(block: (StoreTx) -> T): T {
        val appended = HashMap<String, Long>()
        val result = db.write { scope -> block(StoreTx(scope, clock) { s, seq -> appended[s] = seq }) }
        appended.forEach { (session, seq) -> watcher(session).value = seq }
        return result
    }

    suspend fun <T> read(block: (StoreTx) -> T): T =
        db.read { scope -> block(StoreTx(scope, clock) { _, _ -> error("read-only transaction cannot append events") }) }

    /** 追加一条事件（单独一个事务）。 */
    suspend fun append(event: PendingEvent): EventEnvelope = write { it.events.append(event) }

    /** 会话的最新已提交 sequence；有新事件提交时更新。 */
    fun watch(sessionId: String): StateFlow<Long> = watcher(sessionId).asStateFlow()

    private fun watcher(sessionId: String) = watchers.getOrPut(sessionId) { MutableStateFlow(0L) }

    suspend fun close() = db.close()

    companion object {
        /** 打开（必要时创建）数据库并迁移到当前 schema。 */
        suspend fun open(storage: StoragePort, clock: Clock): Store {
            File(storage.databasePath).parentFile?.mkdirs()
            val db = Db.open { storage.driver.open(storage.databasePath) }
            db.write { Schema.migrate(it, clock.nowMillis()) }
            val store = Store(db, clock)
            // 用已提交的 sequence 初始化 watcher，订阅者从这里开始等
            store.read { tx -> tx.sessions.listAll() }.forEach { store.watcher(it.id).value = it.lastSequence }
            return store
        }
    }
}

/** 一个事务里可以用的全部存储操作。 */
class StoreTx internal constructor(
    scope: DbScope,
    clock: Clock,
    onAppend: (String, Long) -> Unit,
) {
    val events = EventLog(scope, clock::nowMillis, onAppend)
    val sessions = SessionStore(scope)
    val tasks = TaskStore(scope)
    val now: Long = clock.nowMillis()
}
