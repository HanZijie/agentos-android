package org.agentos.sample.notes.data

import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.agentos.sample.notes.data.NoteException.Kind

/**
 * 备忘录仓库：进程内单例，界面和 MCP 服务共用同一个对象。
 *
 * - [notes] 是全部备忘录（含归档、回收站）的最新快照，StateFlow；任何写操作都先落库再更新它，
 *   所以 MCP 改了数据，前台界面马上看到。
 * - 所有写操作串行（一把互斥锁），带输入校验，失败抛 [NoteException]。
 * - [update] / [append] 可带 `expectedRevision`：编辑页自动保存时用它发现“别处刚改过”，不静默覆盖。
 * - 状态机：正常 ⇄ 归档；正常 / 归档 → 回收站；回收站 → 正常（restore）；只有回收站里的才能永久删除。
 */
class NoteRepository(
    private val store: NoteStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    private val _loaded = MutableStateFlow(false)

    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    /** 第一次从库里读完之前为 false，界面据此避免空状态闪一下。 */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** 幂等：只在第一次真正读库。所有读写入口都会先调它，所以 MCP 在界面之前被调用也没问题。 */
    suspend fun load() {
        if (_loaded.value) return
        mutex.withLock { loadLocked() }
    }

    private suspend fun loadLocked() {
        if (_loaded.value) return
        val all = withContext(io) { store.loadAll() }
        _notes.value = all.sortedWith(RECENT_FIRST)
        _loaded.value = true
    }

    // ---- 读 ----

    suspend fun get(id: String): Note {
        load()
        return find(id) ?: throw notFound(id)
    }

    suspend fun list(status: NoteStatus, tag: String? = null, pinned: Boolean? = null): List<Note> {
        load()
        return NoteQueries.filter(_notes.value, status, tag, pinned)
    }

    suspend fun search(query: String, tag: String? = null, includeTrashed: Boolean = false): List<SearchHit> {
        load()
        return NoteSearch.search(_notes.value, query, tag, includeTrashed)
    }

    suspend fun tags(): List<TagCount> {
        load()
        return NoteQueries.tagCounts(_notes.value)
    }

    // ---- 写 ----

    suspend fun create(draft: NoteDraft): Note = mutex.withLock {
        loadLocked()
        val title = cleanTitle(draft.title)
        val content = cleanContent(draft.content)
        if (title.isBlank() && content.isBlank()) {
            throw NoteException(Kind.INVALID, "A note needs a title or some content.")
        }
        val tags = NoteText.normalizeTags(draft.tags)
        val now = clock()
        val note = Note(
            id = newId(),
            title = title,
            content = content,
            tags = tags,
            color = draft.color,
            pinned = draft.pinned,
            status = if (draft.status == NoteStatus.TRASHED) NoteStatus.ACTIVE else draft.status,
            createdAt = now,
            updatedAt = now,
            trashedAt = null,
            revision = 1,
        )
        persist(note)
        note
    }

    /** 只改给出的字段。没有任何实际变化时不动版本号也不写库。回收站里的备忘录不能改，先 [restore]。 */
    suspend fun update(id: String, patch: NotePatch, expectedRevision: Long? = null): Note = mutex.withLock {
        loadLocked()
        val cur = find(id) ?: throw notFound(id)
        checkRevision(cur, expectedRevision)
        if (cur.isTrashed) throw trashedError()
        val title = patch.title?.let { cleanTitle(it) } ?: cur.title
        val content = patch.content?.let { cleanContent(it) } ?: cur.content
        val tags = patch.tags?.let { NoteText.normalizeTags(it) } ?: cur.tags
        val status = when (patch.archived) {
            true -> NoteStatus.ARCHIVED
            false -> NoteStatus.ACTIVE
            null -> cur.status
        }
        val next = cur.copy(
            title = title,
            content = content,
            tags = tags,
            color = patch.color ?: cur.color,
            pinned = patch.pinned ?: cur.pinned,
            status = status,
        )
        if (next == cur) return@withLock cur
        val saved = next.copy(updatedAt = bump(cur.updatedAt), revision = cur.revision + 1)
        persist(saved)
        saved
    }

    /** 在正文末尾追加 [text]；正文为空时不加分隔符。 */
    suspend fun append(id: String, text: String, separator: String = "\n", expectedRevision: Long? = null): Note = mutex.withLock {
        loadLocked()
        val cur = find(id) ?: throw notFound(id)
        checkRevision(cur, expectedRevision)
        if (cur.isTrashed) throw trashedError()
        if (text.isEmpty()) throw NoteException(Kind.INVALID, "text must not be empty.")
        val addition = cleanContent(text)
        val sep = cleanContent(separator)
        val content = cleanContent(if (cur.content.isEmpty()) addition else cur.content + sep + addition)
        val saved = cur.copy(content = content, updatedAt = bump(cur.updatedAt), revision = cur.revision + 1)
        persist(saved)
        saved
    }

    /** 放进回收站（可恢复）。已经在回收站里则报错。 */
    suspend fun trash(id: String): Note = mutex.withLock {
        loadLocked()
        val cur = find(id) ?: throw notFound(id)
        if (cur.isTrashed) throw NoteException(Kind.STATE, "The note is already in the trash.")
        val now = clock()
        val saved = cur.copy(status = NoteStatus.TRASHED, trashedAt = now, updatedAt = bump(cur.updatedAt), revision = cur.revision + 1)
        persist(saved)
        saved
    }

    /** 从回收站或归档恢复为正常。本来就是正常的则报错。 */
    suspend fun restore(id: String): Note = mutex.withLock {
        loadLocked()
        val cur = find(id) ?: throw notFound(id)
        if (cur.status == NoteStatus.ACTIVE) throw NoteException(Kind.STATE, "The note is not in the trash or the archive, so there is nothing to restore.")
        val saved = cur.copy(status = NoteStatus.ACTIVE, trashedAt = null, updatedAt = bump(cur.updatedAt), revision = cur.revision + 1)
        persist(saved)
        saved
    }

    /** 永久删除。只允许删回收站里的备忘录。返回被删掉的那条。 */
    suspend fun deletePermanently(id: String): Note = mutex.withLock {
        loadLocked()
        val cur = find(id) ?: throw notFound(id)
        if (!cur.isTrashed) {
            throw NoteException(Kind.STATE, "Only notes in the trash can be deleted permanently; move it to the trash first (note_trash).")
        }
        withContext(io) { store.delete(listOf(id)) }
        _notes.value = _notes.value.filter { it.id != id }
        cur
    }

    /** 清空回收站，返回永久删除的条数。 */
    suspend fun emptyTrash(): Int = mutex.withLock {
        loadLocked()
        val ids = _notes.value.filter { it.isTrashed }.map { it.id }
        if (ids.isEmpty()) return@withLock 0
        withContext(io) { store.delete(ids) }
        val gone = ids.toSet()
        _notes.value = _notes.value.filter { it.id !in gone }
        ids.size
    }

    // ---- 内部 ----

    private fun find(id: String): Note? = _notes.value.firstOrNull { it.id == id }

    private suspend fun persist(note: Note) {
        withContext(io) { store.save(note) }
        _notes.value = (_notes.value.filter { it.id != note.id } + note).sortedWith(RECENT_FIRST)
    }

    /** 更新时间单调递增，同一毫秒里连续改两次也能排出先后。 */
    private fun bump(previous: Long): Long = maxOf(clock(), previous + 1)

    private fun checkRevision(cur: Note, expected: Long?) {
        if (expected != null && expected != cur.revision) {
            throw NoteException(Kind.CONFLICT, "The note was changed elsewhere (revision ${cur.revision}, expected $expected).", cur)
        }
    }

    private fun notFound(id: String) = NoteException(Kind.NOT_FOUND, "No note with id \"${NoteText.safeTake(id, 64)}\".")

    private fun trashedError() = NoteException(Kind.STATE, "The note is in the trash and cannot be modified; restore it first (note_restore).")

    private fun cleanTitle(raw: String): String {
        val title = raw.replace(Regex("[\\r\\n]+"), " ").trim()
        if (title.length > NoteLimits.MAX_TITLE_CHARS) {
            throw NoteException(Kind.INVALID, "title is longer than ${NoteLimits.MAX_TITLE_CHARS} characters.")
        }
        return title
    }

    private fun cleanContent(raw: String): String {
        val content = raw.replace("\r\n", "\n").replace('\r', '\n')
        if (content.length > NoteLimits.MAX_CONTENT_CHARS) {
            throw NoteException(Kind.INVALID, "content is longer than ${NoteLimits.MAX_CONTENT_CHARS} characters.")
        }
        return content
    }

    private companion object {
        val RECENT_FIRST: Comparator<Note> = compareByDescending<Note> { it.updatedAt }.thenByDescending { it.createdAt }.thenBy { it.id }
    }
}
