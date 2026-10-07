package org.agentos.sample.notes

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import org.agentos.sample.notes.data.Note
import org.agentos.sample.notes.data.NoteRepository
import org.agentos.sample.notes.data.NoteStore

/** JVM 测试用的内存存储。 */
class InMemoryNoteStore(initial: List<Note> = emptyList()) : NoteStore {
    val rows = LinkedHashMap<String, Note>().apply { initial.forEach { put(it.id, it) } }
    var loadCount = 0
    var failSaves = false

    override fun loadAll(): List<Note> { loadCount++; return rows.values.toList() }

    override fun save(note: Note) {
        check(!failSaves) { "disk full" }
        rows[note.id] = note
    }

    override fun delete(ids: Collection<String>) { ids.forEach { rows.remove(it) } }
}

/** 固定起点、每次调用前进 1 秒的时钟；id 是 n1、n2…，测试里好认。 */
class TestEnv(val store: InMemoryNoteStore = InMemoryNoteStore(), startMillis: Long = 1_800_000_000_000L) {
    private val now = AtomicLong(startMillis)
    private val seq = AtomicLong(0)
    fun tick(millis: Long = 1_000) { now.addAndGet(millis) }
    val repo = NoteRepository(
        store = store,
        io = Dispatchers.Unconfined,
        clock = { now.addAndGet(1_000) },
        newId = { "n${seq.incrementAndGet()}" },
    )
}
