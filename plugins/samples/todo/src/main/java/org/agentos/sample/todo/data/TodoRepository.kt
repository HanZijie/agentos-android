package org.agentos.sample.todo.data

import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.agentos.sample.todo.data.TodoException.Kind

/**
 * 待办仓库：进程内单例，界面和 MCP 服务共用同一个对象。
 *
 * - [todos] 是全部待办（含子任务、已完成）的最新快照，StateFlow，按创建时间排序；任何写操作都先落库再更新它，
 *   所以 MCP 改了数据，前台界面马上看到。
 * - 所有写操作串行（一把互斥锁），带输入校验，失败抛 [TodoException]。
 * - 子任务只支持一层：子任务不能再有子任务；有子任务的待办不能变成别人的子任务；删父任务连带删它的子任务。
 * - 完成时间：任何路径变成 done 都写 [Todo.completedAt]（已经是 done 则保持原值），从 done 改回别的状态则清掉。
 */
class TodoRepository(
    private val store: TodoStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString().take(8) },
) {
    private val mutex = Mutex()
    private val _todos = MutableStateFlow<List<Todo>>(emptyList())
    private val _loaded = MutableStateFlow(false)

    val todos: StateFlow<List<Todo>> = _todos.asStateFlow()

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
        _todos.value = all.sortedWith(CREATED)
        _loaded.value = true
    }

    // ---- 读 ----

    suspend fun get(id: String): Todo {
        load()
        return find(id) ?: throw notFound(id)
    }

    suspend fun all(): List<Todo> {
        load()
        return _todos.value
    }

    suspend fun subtasksOf(id: String): List<Todo> {
        load()
        return _todos.value.filter { it.parentId == id }
    }

    // ---- 写 ----

    suspend fun create(draft: TodoDraft): Todo = mutex.withLock {
        loadLocked()
        val title = cleanTitle(draft.title)
        val notes = cleanNotes(draft.notes)
        val tags = TodoQueries.normalizeTags(draft.tags)
        draft.parentId?.let { checkCanBeSubtask(it, selfId = null) }
        val now = clock()
        // 创建时间严格递增（同一毫秒里连续创建也能按创建顺序排）：列表、dump 的稳定顺序靠它
        val created = _todos.value.maxOfOrNull { it.createdAt }?.let { maxOf(now, it + 1) } ?: now
        val todo = Todo(
            id = freshId(),
            title = title,
            notes = notes,
            status = draft.status,
            priority = draft.priority,
            due = draft.due,
            tags = tags,
            parentId = draft.parentId,
            completedAt = if (draft.status == TodoStatus.DONE) now else null,
            createdAt = created,
            updatedAt = created,
        )
        persist(todo)
        todo
    }

    /** 只改给出的字段。没有任何实际变化时不动更新时间也不写库。 */
    suspend fun update(id: String, patch: TodoPatch): Todo = mutex.withLock {
        loadLocked()
        val cur = find(id) ?: throw notFound(id)
        val parentId = when (val p = patch.parentId) {
            Change.Keep -> cur.parentId
            Change.Clear -> null
            is Change.Set -> {
                if (p.value != cur.parentId) checkCanBeSubtask(p.value, selfId = id)
                p.value
            }
        }
        val status = patch.status ?: cur.status
        val next = cur.copy(
            title = patch.title?.let { cleanTitle(it) } ?: cur.title,
            notes = patch.notes?.let { cleanNotes(it) } ?: cur.notes,
            status = status,
            priority = patch.priority ?: cur.priority,
            due = when (val d = patch.due) {
                Change.Keep -> cur.due
                Change.Clear -> null
                is Change.Set -> d.value
            },
            tags = patch.tags?.let { TodoQueries.normalizeTags(it) } ?: cur.tags,
            parentId = parentId,
            completedAt = completedAtFor(cur, status),
        )
        if (next == cur) return@withLock cur
        val saved = next.copy(updatedAt = bump(cur.updatedAt))
        persist(saved)
        saved
    }

    /** 改状态：变成 done 写完成时间，从 done 改回别的清掉；状态没变时什么都不动（幂等）。 */
    suspend fun setStatus(id: String, status: TodoStatus): Todo = update(id, TodoPatch(status = status))

    /** 删除一条待办；它的子任务一起删。返回被删掉的全部（父任务在前），界面用它做“撤销”。 */
    suspend fun delete(id: String): List<Todo> = mutex.withLock {
        loadLocked()
        val cur = find(id) ?: throw notFound(id)
        val gone = listOf(cur) + _todos.value.filter { it.parentId == id }
        withContext(io) { store.delete(gone.map { it.id }) }
        val ids = gone.map { it.id }.toSet()
        _todos.value = _todos.value.filter { it.id !in ids }
        gone
    }

    /**
     * 把之前拿到的待办原样写回（撤销删除 / 撤销状态变化）：同 id 覆盖，保持原来的创建 / 更新 / 完成时间。
     * 指向已经不存在的父任务的子任务变成顶层待办，免得留下悬空引用。
     */
    suspend fun restore(items: List<Todo>) = mutex.withLock {
        loadLocked()
        if (items.isEmpty()) return@withLock
        val existing = _todos.value.map { it.id }.toSet() + items.map { it.id }
        val fixed = items.map { if (it.parentId != null && it.parentId !in existing) it.copy(parentId = null) else it }
        withContext(io) { store.saveAll(fixed) }
        val ids = fixed.map { it.id }.toSet()
        _todos.value = (_todos.value.filter { it.id !in ids } + fixed).sortedWith(CREATED)
    }

    /**
     * 清空全部数据，返回删掉的条数。**不对界面和 MCP 开放**：只给 debug 包的 reset 命令（设备验收前清场）和测试用；
     * release 里没人引用，会被 R8 去掉。
     */
    suspend fun clearAll(): Int = mutex.withLock {
        loadLocked()
        val ids = _todos.value.map { it.id }
        if (ids.isEmpty()) return@withLock 0
        withContext(io) { store.delete(ids) }
        _todos.value = emptyList()
        ids.size
    }

    /** 库里实际的行数（不看内存缓存）。debug 的 reset 用它证明真的清空了。 */
    suspend fun storedCount(): Int = withContext(io) { store.count() }

    // ---- 内部 ----

    private fun find(id: String): Todo? = _todos.value.firstOrNull { it.id == id }

    private suspend fun persist(todo: Todo) {
        withContext(io) { store.save(todo) }
        _todos.value = (_todos.value.filter { it.id != todo.id } + todo).sortedWith(CREATED)
    }

    private fun freshId(): String {
        repeat(20) {
            val id = newId()
            if (find(id) == null) return id
        }
        throw TodoException(Kind.STATE, "Could not allocate an id; try again.")
    }

    private fun completedAtFor(cur: Todo, next: TodoStatus): Long? = when {
        next != TodoStatus.DONE -> null
        cur.status == TodoStatus.DONE -> cur.completedAt ?: clock()
        else -> clock()
    }

    /** 更新时间单调递增，同一毫秒里连续改两次也能排出先后。 */
    private fun bump(previous: Long): Long = maxOf(clock(), previous + 1)

    /** [parentId] 能不能当父任务；[selfId] 非空时是在把已有待办改成它的子任务。 */
    private fun checkCanBeSubtask(parentId: String, selfId: String?) {
        if (parentId == selfId) throw TodoException(Kind.INVALID, "A todo cannot be its own parent.")
        val parent = find(parentId)
            ?: throw TodoException(Kind.NOT_FOUND, "No parent todo with id \"${parentId.take(64)}\".")
        if (parent.parentId != null) {
            throw TodoException(Kind.INVALID, "Subtasks cannot have their own subtasks: \"${parent.title.take(40)}\" is already a subtask (only one level is supported).")
        }
        if (selfId != null && _todos.value.any { it.parentId == selfId }) {
            throw TodoException(Kind.INVALID, "This todo has subtasks of its own, so it cannot become a subtask (only one level is supported).")
        }
        if (_todos.value.count { it.parentId == parentId } >= TodoLimits.MAX_SUBTASKS) {
            throw TodoException(Kind.INVALID, "A todo can have at most ${TodoLimits.MAX_SUBTASKS} subtasks.")
        }
    }

    private fun notFound(id: String) = TodoException(Kind.NOT_FOUND, "No todo with id \"${id.take(64)}\".")

    private fun cleanTitle(raw: String): String {
        val title = raw.replace(Regex("[\\r\\n]+"), " ").trim()
        if (title.isEmpty()) throw TodoException(Kind.INVALID, "title must not be empty.")
        if (title.length > TodoLimits.MAX_TITLE_CHARS) {
            throw TodoException(Kind.INVALID, "title is longer than ${TodoLimits.MAX_TITLE_CHARS} characters.")
        }
        return title
    }

    private fun cleanNotes(raw: String): String {
        val notes = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (notes.length > TodoLimits.MAX_NOTES_CHARS) {
            throw TodoException(Kind.INVALID, "notes is longer than ${TodoLimits.MAX_NOTES_CHARS} characters.")
        }
        return notes
    }

    private companion object {
        val CREATED: Comparator<Todo> = compareBy<Todo> { it.createdAt }.thenBy { it.id }
    }
}
