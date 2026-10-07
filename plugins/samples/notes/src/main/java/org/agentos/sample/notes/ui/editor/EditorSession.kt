package org.agentos.sample.notes.ui.editor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.agentos.sample.notes.data.Note
import org.agentos.sample.notes.data.NoteColor
import org.agentos.sample.notes.data.NoteDraft
import org.agentos.sample.notes.data.NoteException
import org.agentos.sample.notes.data.NotePatch
import org.agentos.sample.notes.data.NoteRepository
import org.agentos.sample.notes.data.NoteStatus
import org.agentos.sample.notes.data.NoteText

/**
 * 编辑页的一次编辑会话（不依赖 Compose，可在 JVM 上测试）。
 *
 * - 本地修改先攒在会话里，停手 [debounceMs] 后自动保存；新备忘录在第一次有内容时才真正创建。
 * - 保存带 `expectedRevision`：MCP（或别的写入方）在你编辑时改了这条，仓库会拒绝，会话进入 [Conflict.Updated]
 *   并暂停自动保存，由用户选“载入最新 / 保留我的 / 另存为副本”——永远不会静默覆盖别人的修改。
 * - 没有未保存修改时，外部的修改直接同步进来（[State.syncedCount] 加一，界面弹一次提示）。
 * - 外部把它移进回收站 → 只读并给“恢复”；外部永久删除 → [Conflict.Deleted]，可以把当前内容另存为新备忘录。
 */
class EditorSession(
    private val repo: NoteRepository,
    private val scope: CoroutineScope,
    initialNoteId: String?,
    private val debounceMs: Long = 700,
) {
    enum class SaveState { CLEAN, DIRTY, SAVING, ERROR }

    sealed interface Conflict {
        /** 别处改过：[remote] 是当时看到的最新版本。 */
        data class Updated(val remote: Note) : Conflict

        /** 别处永久删除了。 */
        data object Deleted : Conflict
    }

    data class State(
        val noteId: String? = null,
        val title: String = "",
        val content: String = "",
        val tags: List<String> = emptyList(),
        val color: NoteColor = NoteColor.DEFAULT,
        val pinned: Boolean = false,
        val status: NoteStatus = NoteStatus.ACTIVE,
        val save: SaveState = SaveState.CLEAN,
        val conflict: Conflict? = null,
        /** 静默同步了几次外部修改（界面据此提示）。 */
        val syncedCount: Int = 0,
        /** 正文被程序替换的次数（载入最新、预览里勾选任务）：界面据此重置输入框。 */
        val contentVersion: Int = 0,
        val updatedAt: Long? = null,
        val createdAt: Long? = null,
    ) {
        val readOnly: Boolean get() = status == NoteStatus.TRASHED
    }

    private val _state = MutableStateFlow(State(noteId = initialNoteId))
    val state: StateFlow<State> = _state.asStateFlow()

    private var baseRevision: Long? = null
    private var localVersion = 0
    private var savedVersion = 0
    private var busy = false
    private var debounceJob: Job? = null
    private val collectJob: Job

    init {
        if (initialNoteId != null) {
            val existing = repo.notes.value.firstOrNull { it.id == initialNoteId }
            if (existing != null) {
                _state.value = fromNote(existing, State(noteId = initialNoteId))
                baseRevision = existing.revision
            } else {
                _state.value = State(noteId = initialNoteId, conflict = Conflict.Deleted)
            }
        }
        collectJob = scope.launch { repo.notes.collect { onRepoChanged() } }
    }

    val isDirty: Boolean get() = localVersion != savedVersion

    // ---------------------------------------------------------------- 本地编辑

    fun setTitle(value: String) = edit(_state.value.title != value) { it.copy(title = value) }

    fun setContent(value: String) = edit(_state.value.content != value) { it.copy(content = value) }

    fun setTags(value: List<String>) = edit(_state.value.tags != value) { it.copy(tags = value) }

    fun setColor(value: NoteColor) = edit(_state.value.color != value) { it.copy(color = value) }

    fun setPinned(value: Boolean) = edit(_state.value.pinned != value) { it.copy(pinned = value) }

    /** 预览里点任务复选框：改第 [line] 行（0 起）的 [ ] / [x]，并让输入框重置。 */
    fun toggleTask(line: Int) {
        val next = org.agentos.sample.notes.markdown.MarkdownParser.toggleTask(_state.value.content, line) ?: return
        edit(true) { it.copy(content = next, contentVersion = it.contentVersion + 1) }
    }

    private inline fun edit(changed: Boolean, transform: (State) -> State) {
        if (!changed || _state.value.readOnly) return
        localVersion++
        _state.update { transform(it).copy(save = SaveState.DIRTY) }
        scheduleSave()
    }

    private fun scheduleSave() {
        debounceJob?.cancel()
        if (_state.value.conflict != null || _state.value.readOnly) return
        debounceJob = scope.launch {
            delay(debounceMs)
            save()
        }
    }

    /** 立即保存（离开页面、切到后台时调）。有冲突时不保存。 */
    suspend fun flush() {
        debounceJob?.cancel()
        save()
    }

    fun close() {
        debounceJob?.cancel()
        scope.launch {
            try {
                save()
            } finally {
                collectJob.cancel()
            }
        }
    }

    // ---------------------------------------------------------------- 保存

    private suspend fun save() {
        if (busy) return
        val s = _state.value
        if (s.conflict != null || s.readOnly || localVersion == savedVersion) return
        val version = localVersion
        if (s.noteId == null && s.title.isBlank() && s.content.isBlank()) {
            savedVersion = version
            _state.update { it.copy(save = SaveState.CLEAN) }
            return
        }
        busy = true
        _state.update { it.copy(save = SaveState.SAVING) }
        try {
            val saved = if (s.noteId == null) {
                repo.create(NoteDraft(s.title, s.content, s.tags, s.color, s.pinned))
            } else {
                repo.update(
                    s.noteId,
                    NotePatch(title = s.title, content = s.content, tags = s.tags, color = s.color, pinned = s.pinned),
                    expectedRevision = baseRevision,
                )
            }
            baseRevision = saved.revision
            savedVersion = version
            _state.update {
                it.copy(
                    noteId = saved.id,
                    save = if (localVersion == version) SaveState.CLEAN else SaveState.DIRTY,
                    updatedAt = saved.updatedAt,
                    createdAt = saved.createdAt,
                    status = saved.status,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoteException) {
            when (e.kind) {
                NoteException.Kind.CONFLICT -> e.current?.let { remoteChangedWhileDirty(it) } ?: _state.update { it.copy(save = SaveState.ERROR) }
                NoteException.Kind.NOT_FOUND -> _state.update { it.copy(conflict = Conflict.Deleted, save = SaveState.DIRTY) }
                NoteException.Kind.STATE -> _state.update { it.copy(status = NoteStatus.TRASHED, save = SaveState.DIRTY) }
                NoteException.Kind.INVALID -> _state.update { it.copy(save = SaveState.ERROR) }
            }
        } catch (e: Exception) {
            _state.update { it.copy(save = SaveState.ERROR) }
        } finally {
            busy = false
        }
        // 保存期间可能又改了、或外部又变了：再对一遍
        onRepoChanged()
        if (isDirty && _state.value.conflict == null && !_state.value.readOnly && _state.value.save == SaveState.DIRTY) scheduleSave()
    }

    // ---------------------------------------------------------------- 外部修改

    private fun onRepoChanged() {
        if (busy) return
        val id = _state.value.noteId ?: return
        val remote = repo.notes.value.firstOrNull { it.id == id }
        if (remote == null) {
            if (_state.value.conflict !is Conflict.Deleted) _state.update { it.copy(conflict = Conflict.Deleted) }
            return
        }
        if (remote.revision == baseRevision) return
        reconcile(remote)
    }

    private fun reconcile(remote: Note) {
        val s = _state.value
        if (remote.isTrashed) {
            baseRevision = remote.revision
            if (!isDirty) {
                _state.value = fromNote(remote, s).copy(syncedCount = s.syncedCount, contentVersion = bumpIfChanged(s, remote))
            } else {
                _state.update { it.copy(status = NoteStatus.TRASHED, updatedAt = remote.updatedAt) }
            }
            return
        }
        if (!isDirty || sameFields(remote, s)) {
            val changed = !sameFields(remote, s)
            baseRevision = remote.revision
            savedVersion = localVersion
            _state.value = fromNote(remote, s).copy(
                syncedCount = if (changed) s.syncedCount + 1 else s.syncedCount,
                contentVersion = bumpIfChanged(s, remote),
            )
            return
        }
        remoteChangedWhileDirty(remote)
    }

    private fun remoteChangedWhileDirty(remote: Note) {
        debounceJob?.cancel()
        if (remote.isTrashed) {
            baseRevision = remote.revision
            _state.update { it.copy(status = NoteStatus.TRASHED, save = SaveState.DIRTY) }
        } else {
            _state.update { it.copy(conflict = Conflict.Updated(remote), save = SaveState.DIRTY) }
        }
    }

    private fun bumpIfChanged(s: State, remote: Note) = if (s.content != remote.content) s.contentVersion + 1 else s.contentVersion

    private fun sameFields(n: Note, s: State) =
        n.title == s.title && n.content == s.content && n.tags == s.tags && n.color == s.color && n.pinned == s.pinned && n.status == s.status

    private fun fromNote(n: Note, base: State) = base.copy(
        noteId = n.id,
        title = n.title,
        content = n.content,
        tags = n.tags,
        color = n.color,
        pinned = n.pinned,
        status = n.status,
        save = SaveState.CLEAN,
        conflict = null,
        updatedAt = n.updatedAt,
        createdAt = n.createdAt,
    )

    // ---------------------------------------------------------------- 冲突处理

    /** 放弃本地未保存的修改，载入最新版本。 */
    fun takeTheirs() {
        val s = _state.value
        val id = s.noteId ?: return
        val latest = repo.notes.value.firstOrNull { it.id == id } ?: (s.conflict as? Conflict.Updated)?.remote ?: return
        debounceJob?.cancel()
        baseRevision = latest.revision
        savedVersion = localVersion
        _state.value = fromNote(latest, s).copy(syncedCount = s.syncedCount, contentVersion = s.contentVersion + 1)
    }

    /** 保留本地版本，覆盖对方的修改。 */
    fun keepMine() {
        val s = _state.value
        val id = s.noteId ?: return
        if (s.conflict !is Conflict.Updated) return
        baseRevision = repo.notes.value.firstOrNull { it.id == id }?.revision ?: s.conflict.remote.revision
        _state.update { it.copy(conflict = null) }
        scope.launch { save() }
    }

    /** 把本地版本另存为新备忘录（标题加 [suffix]），再载入对方的最新版本。返回新备忘录。 */
    suspend fun saveAsCopy(suffix: String): Note? {
        val s = _state.value
        val copy = try {
            val title = s.title.ifBlank { NoteText.deriveTitle(s.content) }.ifBlank { null }
            repo.create(NoteDraft(title = (title ?: "") + suffix, content = s.content, tags = s.tags, color = s.color))
        } catch (e: NoteException) {
            return null
        }
        takeTheirs()
        return copy
    }

    /** 外部永久删除后，把当前内容另存为新备忘录并继续在新的上面编辑。 */
    suspend fun saveAsNew(): Note? {
        val s = _state.value
        busy = true
        try {
            val created = try {
                repo.create(NoteDraft(s.title, s.content, s.tags, s.color, s.pinned))
            } catch (e: NoteException) {
                return null
            }
            baseRevision = created.revision
            savedVersion = localVersion
            _state.update {
                it.copy(noteId = created.id, conflict = null, status = created.status, save = SaveState.CLEAN, updatedAt = created.updatedAt, createdAt = created.createdAt)
            }
            return created
        } finally {
            busy = false
        }
    }

    /** 从回收站恢复后继续编辑（外部移进回收站时的“恢复”）。 */
    suspend fun restore(): Note? {
        val id = _state.value.noteId ?: return null
        busy = true
        try {
            val restored = try {
                repo.restore(id)
            } catch (e: NoteException) {
                return null
            }
            baseRevision = restored.revision
            _state.update { it.copy(status = restored.status, updatedAt = restored.updatedAt) }
            if (isDirty) {
                _state.update { it.copy(save = SaveState.DIRTY) }
            } else {
                _state.value = fromNote(restored, _state.value).copy(contentVersion = _state.value.contentVersion + 1)
            }
            return restored
        } finally {
            busy = false
            if (isDirty) scheduleSave()
        }
    }
}
