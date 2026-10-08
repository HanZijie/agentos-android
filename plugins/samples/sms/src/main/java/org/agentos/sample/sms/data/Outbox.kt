package org.agentos.sample.sms.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** outbox 的存储（SQLite / 内存）。所有方法都是同步的，调用方（[Outbox]）负责串行化。 */
interface OutboxStore {
    /** 新建一条 queued 记录并返回它（id 由存储分配）。 */
    fun insert(to: String, text: String, parts: Int, now: Long): OutboxEntry

    fun get(id: String): OutboxEntry?

    fun update(entry: OutboxEntry)

    /** 从新到旧。 */
    fun list(offset: Int, limit: Int): List<OutboxEntry>

    fun count(): Int

    /** 创建时间 >= [sinceMillis] 的全部记录（不论状态），新到旧。 */
    fun createdSince(sinceMillis: Long): List<OutboxEntry>

    /** 清空，返回删除条数。 */
    fun clear(): Int
}

class InMemoryOutboxStore : OutboxStore {
    private val rows = LinkedHashMap<String, OutboxEntry>()
    private var next = 1L

    override fun insert(to: String, text: String, parts: Int, now: Long): OutboxEntry {
        val entry = OutboxEntry(
            id = (next++).toString(), to = to, text = text, parts = parts, state = OutboxState.QUEUED,
            sentMask = 0, deliveredMask = 0, error = null, createdAt = now, updatedAt = now,
        )
        rows[entry.id] = entry
        return entry
    }

    override fun get(id: String) = rows[id]

    override fun update(entry: OutboxEntry) {
        if (entry.id in rows) rows[entry.id] = entry
    }

    override fun list(offset: Int, limit: Int) = ordered().drop(offset).take(limit)

    override fun count() = rows.size

    override fun createdSince(sinceMillis: Long) = ordered().filter { it.createdAt >= sinceMillis }

    override fun clear(): Int = rows.size.also { rows.clear() }

    private fun ordered() = rows.values.sortedWith(compareByDescending<OutboxEntry> { it.createdAt }.thenByDescending { it.id.toLong() })
}

/**
 * 发送记录。线程安全（SQLite 操作都在同一把锁里）；界面经 [recent] 观察，MCP 工具和系统回调接收器经方法读写，用的是同一个对象。
 */
class Outbox(private val store: OutboxStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val recentFlow = MutableStateFlow<List<OutboxEntry>>(emptyList())

    /** 最近 [RECENT_COUNT] 条，新到旧（给界面）。 */
    val recent: StateFlow<List<OutboxEntry>> = recentFlow.asStateFlow()

    init {
        synchronized(lock) { publish() }
    }

    fun create(to: String, text: String, parts: Int): OutboxEntry = synchronized(lock) {
        require(parts in 1..MAX_PARTS) { "parts out of range: $parts" }
        store.insert(to, text, parts, clock()).also { publish() }
    }

    fun get(id: String): OutboxEntry? = synchronized(lock) { store.get(id) }

    fun page(offset: Int, limit: Int): List<OutboxEntry> = synchronized(lock) { store.list(offset, limit) }

    fun count(): Int = synchronized(lock) { store.count() }

    fun createdSince(sinceMillis: Long): List<OutboxEntry> = synchronized(lock) { store.createdSince(sinceMillis) }

    fun onSent(id: String, part: Int): OutboxEntry? = apply(id) { OutboxMachine.onSent(it, part, clock()) }

    fun onDelivered(id: String, part: Int): OutboxEntry? = apply(id) { OutboxMachine.onDelivered(it, part, clock()) }

    fun onFailed(id: String, reason: String): OutboxEntry? = apply(id) { OutboxMachine.onFailed(it, reason, clock()) }

    /** 只清 outbox（不碰系统短信库），返回删除条数。 */
    fun clear(): Int = synchronized(lock) { store.clear().also { publish() } }

    private fun apply(id: String, transition: (OutboxEntry) -> OutboxEntry): OutboxEntry? = synchronized(lock) {
        val before = store.get(id) ?: return null
        val after = transition(before)
        if (after !== before && after != before) {
            store.update(after)
            publish()
        }
        after
    }

    private fun publish() {
        recentFlow.value = store.list(0, RECENT_COUNT)
    }

    companion object {
        const val RECENT_COUNT = 100
        const val MAX_PARTS = 62
    }
}
