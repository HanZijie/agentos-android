package org.agentos.sample.todo

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.WeekFields
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import org.agentos.sample.todo.data.Todo
import org.agentos.sample.todo.data.TodoRepository
import org.agentos.sample.todo.data.TodoStore
import org.agentos.sample.todo.tools.TodoTools

/** JVM 测试用的内存存储。 */
class InMemoryTodoStore(initial: List<Todo> = emptyList()) : TodoStore {
    val rows = LinkedHashMap<String, Todo>().apply { initial.forEach { put(it.id, it) } }
    var loadCount = 0
    var saveCount = 0
    var failSaves = false

    override fun loadAll(): List<Todo> { loadCount++; return rows.values.toList() }

    override fun save(todo: Todo) {
        check(!failSaves) { "disk full" }
        saveCount++
        rows[todo.id] = todo
    }

    override fun saveAll(todos: Collection<Todo>) { todos.forEach { save(it) } }

    override fun delete(ids: Collection<String>) { ids.forEach { rows.remove(it) } }

    override fun count(): Int = rows.size
}

/** 测试里的“现在”：2026-10-09（周五）10:00，+08:00。 */
val NOW: Long = OffsetDateTime.parse("2026-10-09T10:00:00+08:00").toInstant().toEpochMilli()

val UTC8: ZoneId = ZoneOffset.ofHours(8)

fun millis(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

/** 固定时钟（测试里手动往后拨）、id 是 t1、t2…，好认。 */
class TestEnv(val store: InMemoryTodoStore = InMemoryTodoStore(), startMillis: Long = NOW) {
    var now: Long = startMillis
    private val seq = AtomicLong(0)
    val repo = TodoRepository(
        store = store,
        io = Dispatchers.Unconfined,
        clock = { now },
        newId = { "t${seq.incrementAndGet()}" },
    )

    fun advance(millis: Long = 1_000) { now += millis }

    /** 工具层：时区默认 +08:00，一周从周一算起（ISO）；测试可以覆盖。 */
    fun tools(zone: ZoneId = UTC8, week: WeekFields = WeekFields.ISO) =
        TodoTools(repo, zone = { zone }, clock = { now }, week = { week })
}
