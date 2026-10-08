package org.agentos.sample.todo.ui

import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.agentos.sample.todo.data.Due
import org.agentos.sample.todo.data.Priority
import org.agentos.sample.todo.data.Todo
import org.agentos.sample.todo.data.TodoDraft
import org.agentos.sample.todo.data.TodoPatch
import org.agentos.sample.todo.data.TodoRepository
import org.agentos.sample.todo.data.TodoStatus

/** 需要在底部提示条里给出“撤销”的操作（界面按它的类型选文案）。[restore] 是要写回去的原样数据。 */
sealed interface UndoEvent {
    val restore: List<Todo>

    data class Completed(val before: Todo) : UndoEvent { override val restore get() = listOf(before) }
    data class Reopened(val before: Todo) : UndoEvent { override val restore get() = listOf(before) }
    data class Deleted(val items: List<Todo>) : UndoEvent { override val restore get() = items }

    /** 没有撤销的失败提示。 */
    data object Failed : UndoEvent { override val restore get() = emptyList<Todo>() }
}

/** 顶部输入框的“截止”快捷。 */
enum class QuickDue { NONE, TODAY, TOMORROW }

/**
 * 列表和编辑页共用的 ViewModel。数据全部来自进程内的 [TodoRepository]（和 MCP 服务是同一个对象），
 * 所以 AgentOS 经 MCP 改了数据，[sections] 立刻重算、界面立刻刷新。
 */
class TodoViewModel(
    private val repository: TodoRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {
    val todos: StateFlow<List<Todo>> = repository.todos
    val loaded: StateFlow<Boolean> = repository.loaded

    val filter = MutableStateFlow(ListFilter())
    val collapsed = MutableStateFlow(SectionKind.entries.filter { it.collapsedByDefault }.toSet())
    val expandedParents = MutableStateFlow(emptySet<String>())

    /** 每分钟走一次的“现在”：逾期、今天这些分组跟着时间变，跨午夜也会重排。 */
    val now: StateFlow<Long> = flow {
        while (true) {
            val t = clock()
            emit(t)
            delay(60_000L - t % 60_000L + 50L)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), clock())

    val sections: StateFlow<List<Section>> = combine(todos, filter, now) { all, f, t -> TodoSections.build(all, f, t, zone()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UndoEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch { repository.load() }
    }

    fun zoneId(): ZoneId = zone()

    // ---- 筛选 / 展开 ----

    fun setStatusFilter(status: TodoStatus?) {
        filter.update { it.copy(status = status) }
        // 选了“已完成 / 搁置”就直接把对应分组展开
        val kind = when (status) {
            TodoStatus.DONE -> SectionKind.DONE
            TodoStatus.SHELVED -> SectionKind.SHELVED
            else -> null
        }
        if (kind != null) collapsed.update { it - kind }
    }

    fun setPriorityFilter(priority: Priority?) = filter.update { it.copy(priority = priority) }

    fun setTagFilter(tag: String?) = filter.update { it.copy(tag = tag) }

    fun clearFilters() {
        filter.value = ListFilter()
    }

    fun toggleSection(kind: SectionKind) = collapsed.update { if (kind in it) it - kind else it + kind }

    fun toggleParent(id: String) = expandedParents.update { if (id in it) it - id else it + id }

    // ---- 写 ----

    /** 顶部输入框回车：解析 `!1/!2/!3`，建一条顶层待办。标题为空什么都不做。 */
    fun quickAdd(text: String, priority: Priority, quickDue: QuickDue) {
        val parsed = QuickAddParser.parse(text)
        if (parsed.title.isEmpty()) return
        val today = java.time.Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate()
        val due: Due? = when (quickDue) {
            QuickDue.NONE -> null
            QuickDue.TODAY -> Due.Day(today)
            QuickDue.TOMORROW -> Due.Day(today.plusDays(1))
        }
        launchGuarded {
            repository.create(TodoDraft(title = parsed.title, priority = parsed.priority ?: priority, due = due))
        }
    }

    /** 点圆圈 / 左滑：完成 ⇄ 重新打开，带撤销。 */
    fun toggleDone(todo: Todo, withUndo: Boolean = true) {
        launchGuarded {
            val before = repository.get(todo.id)
            if (before.status == TodoStatus.DONE) {
                repository.setStatus(todo.id, TodoStatus.TODO)
                if (withUndo) _events.emit(UndoEvent.Reopened(before))
            } else {
                repository.setStatus(todo.id, TodoStatus.DONE)
                if (withUndo) _events.emit(UndoEvent.Completed(before))
            }
        }
    }

    fun delete(id: String) {
        launchGuarded { _events.emit(UndoEvent.Deleted(repository.delete(id))) }
    }

    fun undo(event: UndoEvent) {
        launchGuarded { repository.restore(event.restore) }
    }

    fun create(draft: TodoDraft, onCreated: () -> Unit = {}) {
        launchGuarded {
            repository.create(draft)
            onCreated()
        }
    }

    fun update(id: String, patch: TodoPatch) {
        if (patch.isEmpty) return
        launchGuarded { repository.update(id, patch) }
    }

    fun addSubtask(parentId: String, title: String) {
        if (title.isBlank()) return
        launchGuarded { repository.create(TodoDraft(title = title, parentId = parentId)) }
    }

    private fun launchGuarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.emit(UndoEvent.Failed)
            }
        }
    }
}
