package org.agentos.sample.notes.ui

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.agentos.sample.notes.data.Note
import org.agentos.sample.notes.data.NoteColor
import org.agentos.sample.notes.data.NoteException
import org.agentos.sample.notes.data.NotePatch
import org.agentos.sample.notes.data.NoteQueries
import org.agentos.sample.notes.data.NoteRepository
import org.agentos.sample.notes.data.NoteSearch
import org.agentos.sample.notes.data.NoteStatus
import org.agentos.sample.notes.data.SearchHit
import org.agentos.sample.notes.data.TagCount
import org.agentos.sample.notes.ui.editor.EditorSession

enum class Scope(val status: NoteStatus) { NOTES(NoteStatus.ACTIVE), ARCHIVE(NoteStatus.ARCHIVED), TRASH(NoteStatus.TRASHED) }

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen

    /** [token] 区分“每次打开”，新建时 [noteId] 为 null。 */
    data class Editor(val noteId: String?, val token: Long) : Screen
}

data class HomeUi(
    val loaded: Boolean = false,
    val scope: Scope = Scope.NOTES,
    val tagFilter: String? = null,
    val tags: List<TagCount> = emptyList(),
    val pinned: List<Note> = emptyList(),
    val others: List<Note> = emptyList(),
    val counts: Map<Scope, Int> = emptyMap(),
) {
    val visibleCount: Int get() = pinned.size + others.size
}

data class SearchUi(
    val query: String = "",
    val tag: String? = null,
    val tags: List<TagCount> = emptyList(),
    val hits: List<SearchHit> = emptyList(),
    val total: Int = 0,
)

sealed interface UiEvent {
    data class Trashed(val id: String) : UiEvent
    data class Archived(val id: String) : UiEvent
    data class Unarchived(val id: String) : UiEvent
    data object Restored : UiEvent
    data object Deleted : UiEvent
    data class TrashEmptied(val count: Int) : UiEvent
    data class Failed(val message: String) : UiEvent
}

/**
 * 界面状态：导航栈、首页 / 搜索的派生列表、编辑会话。全部数据来自进程内单例的 [NoteRepository]，
 * 所以 MCP 工具对数据的任何修改都会通过它的 StateFlow 立刻反映到这里。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(private val repo: NoteRepository, private val prefs: SharedPreferences) : ViewModel() {

    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Home))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    private val _scope = MutableStateFlow(Scope.NOTES)
    private val _tagFilter = MutableStateFlow<String?>(null)
    private val _grid = MutableStateFlow(prefs.getBoolean(PREF_GRID, true))
    val gridMode: StateFlow<Boolean> = _grid.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _searchTag = MutableStateFlow<String?>(null)

    private val _editor = MutableStateFlow<EditorSession?>(null)
    val editor: StateFlow<EditorSession?> = _editor.asStateFlow()

    private val eventChannel = Channel<UiEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var tokenSeq = 0L

    val loaded: StateFlow<Boolean> = repo.loaded

    val home: StateFlow<HomeUi> = combine(repo.notes, repo.loaded, _scope, _tagFilter) { notes, loaded, scope, tag ->
        val counts = Scope.entries.associateWith { s -> notes.count { it.status == s.status } }
        val tags = if (scope == Scope.TRASH) emptyList() else NoteQueries.tagCounts(notes, setOf(scope.status))
        val effectiveTag = tag?.takeIf { t -> tags.any { it.name.equals(t, ignoreCase = true) } }
        val list = NoteQueries.filter(notes, scope.status, effectiveTag)
        val (pinned, others) = if (scope == Scope.NOTES) list.partition { it.pinned } else emptyList<Note>() to list
        HomeUi(loaded, scope, effectiveTag, tags, pinned, others, counts)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    val search: StateFlow<SearchUi> = combine(repo.notes, _query, _searchTag) { notes, query, tag ->
        Triple(notes, query, tag)
    }.mapLatest { (notes, query, tag) ->
        val tags = NoteQueries.tagCounts(notes)
        val effectiveTag = tag?.takeIf { t -> tags.any { it.name.equals(t, ignoreCase = true) } }
        val hits = if (query.isBlank()) emptyList() else NoteSearch.search(notes, query, effectiveTag)
        SearchUi(query, effectiveTag, tags, hits.take(MAX_SEARCH_RESULTS), hits.size)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUi())

    // ---------------------------------------------------------------- 导航

    fun openEditor(noteId: String?) {
        _editor.value?.close()
        _editor.value = EditorSession(repo, viewModelScope, noteId)
        _stack.update { it + Screen.Editor(noteId, ++tokenSeq) }
    }

    fun openSearch() {
        _query.value = ""
        _searchTag.value = null
        _stack.update { it + Screen.Search }
    }

    /** 返回上一屏；已经在首页返回 false（交给系统）。 */
    fun back(): Boolean {
        val stack = _stack.value
        if (stack.size <= 1) return false
        if (stack.last() is Screen.Editor) {
            _editor.value?.close()
            _editor.value = null
        }
        _stack.value = stack.dropLast(1)
        return true
    }

    fun selectScope(scope: Scope) {
        if (_scope.value == scope) return
        _tagFilter.value = null
        _scope.value = scope
    }

    fun selectTag(tag: String?) { _tagFilter.value = tag }

    fun setQuery(text: String) { _query.value = text }

    fun selectSearchTag(tag: String?) { _searchTag.value = tag }

    fun toggleGrid() {
        val next = !_grid.value
        _grid.value = next
        prefs.edit().putBoolean(PREF_GRID, next).apply()
    }

    // ---------------------------------------------------------------- 列表上的操作

    private fun launchOp(block: suspend () -> UiEvent?) {
        viewModelScope.launch {
            val event = try {
                block()
            } catch (e: NoteException) {
                UiEvent.Failed(e.message ?: "")
            }
            if (event != null) eventChannel.send(event)
        }
    }

    fun trash(id: String) = launchOp { repo.trash(id); UiEvent.Trashed(id) }

    fun archive(id: String) = launchOp { repo.update(id, NotePatch(archived = true)); UiEvent.Archived(id) }

    fun unarchive(id: String) = launchOp { repo.update(id, NotePatch(archived = false)); UiEvent.Unarchived(id) }

    fun restore(id: String) = launchOp { repo.restore(id); UiEvent.Restored }

    /** 撤销“移到回收站 / 归档”的操作，不再弹提示。 */
    fun undo(id: String) = launchOp { repo.restore(id); null }

    fun deleteForever(id: String) = launchOp { repo.deletePermanently(id); UiEvent.Deleted }

    fun emptyTrash() = launchOp { UiEvent.TrashEmptied(repo.emptyTrash()) }

    fun setPinned(id: String, pinned: Boolean) = launchOp { repo.update(id, NotePatch(pinned = pinned)); null }

    fun setColor(id: String, color: NoteColor) = launchOp { repo.update(id, NotePatch(color = color)); null }

    // ---------------------------------------------------------------- 编辑页上的操作

    fun trashFromEditor() = leaveEditorAfter { id -> repo.trash(id); UiEvent.Trashed(id) }

    fun archiveFromEditor(archive: Boolean) = leaveEditorAfter { id ->
        repo.update(id, NotePatch(archived = archive))
        if (archive) UiEvent.Archived(id) else UiEvent.Unarchived(id)
    }

    fun deleteForeverFromEditor() = leaveEditorAfter { id -> repo.deletePermanently(id); UiEvent.Deleted }

    private fun leaveEditorAfter(block: suspend (String) -> UiEvent) {
        val session = _editor.value ?: return
        viewModelScope.launch {
            session.flush()
            val id = session.state.value.noteId
            val event = if (id == null) null else try {
                block(id)
            } catch (e: NoteException) {
                UiEvent.Failed(e.message ?: "")
            }
            if (_stack.value.lastOrNull() is Screen.Editor) back()
            if (event != null) eventChannel.send(event)
        }
    }

    override fun onCleared() {
        _editor.value?.close()
    }

    private companion object {
        const val PREF_GRID = "grid_mode"
        const val MAX_SEARCH_RESULTS = 100
    }
}
