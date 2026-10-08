package org.agentos.sample.todo.ui

import java.time.Instant
import java.time.ZoneId
import org.agentos.sample.todo.data.DueTime
import org.agentos.sample.todo.data.Priority
import org.agentos.sample.todo.data.Todo
import org.agentos.sample.todo.data.TodoQueries
import org.agentos.sample.todo.data.TodoStatus

/** 列表的分组，按这个顺序排：逾期、今天、即将到来、无日期、搁置、已完成。 */
enum class SectionKind(val collapsedByDefault: Boolean) {
    OVERDUE(false),
    TODAY(false),
    UPCOMING(false),
    NO_DATE(false),
    SHELVED(true),
    DONE(true),
}

/** 一行顶层待办和它的子任务（按创建顺序）。 */
data class ParentRow(val todo: Todo, val subtasks: List<Todo>) {
    val doneCount: Int get() = subtasks.count { it.status == TodoStatus.DONE }
}

data class Section(val kind: SectionKind, val rows: List<ParentRow>)

/** 筛选 chips 的当前选择。 */
data class ListFilter(val status: TodoStatus? = null, val priority: Priority? = null, val tag: String? = null) {
    val isActive: Boolean get() = status != null || priority != null || tag != null

    fun matches(todo: Todo): Boolean =
        (status == null || todo.status == status) &&
            (priority == null || todo.priority == priority) &&
            (tag == null || todo.tags.any { TodoQueries.tagKey(it) == TodoQueries.tagKey(tag) })
}

/**
 * 把仓库里的全部待办分成列表的几个分组。纯函数，JVM 可测。
 * - 只有顶层待办成行，子任务挂在父任务下面（不论它们自己的日期和状态）。
 * - 分组看待办自己的状态和截止时间：已完成 → 已完成；搁置 → 搁置；其余（待办 / 进行中）无截止 → 无日期，
 *   已过截止 → 逾期（全天的当天不算），截止在今天 → 今天，之后 → 即将到来。“今天”是 [zone] 的本地日历日。
 * - 筛选作用在待办自己上；父任务本身不符合但有子任务符合时也保留（免得符合的子任务被藏起来），它的子任务仍全部显示。
 * - 组内：优先级 → 截止 → 创建时间；已完成按完成时间新的在前。空分组不出现。
 */
object TodoSections {
    fun build(todos: List<Todo>, filter: ListFilter, nowMillis: Long, zone: ZoneId): List<Section> {
        val children = todos.filter { it.parentId != null }.groupBy { it.parentId }
        val order = TodoQueries.order(zone)
        val rows = todos.asSequence()
            .filter { it.parentId == null }
            .filter { top -> !filter.isActive || filter.matches(top) || children[top.id].orEmpty().any(filter::matches) }
            .map { ParentRow(it, children[it.id].orEmpty().sortedWith(compareBy<Todo> { c -> c.createdAt }.thenBy { c -> c.id })) }
            .groupBy { kindOf(it.todo, nowMillis, zone) }
        return SectionKind.entries.mapNotNull { kind ->
            val list = rows[kind] ?: return@mapNotNull null
            val sorted = if (kind == SectionKind.DONE) {
                list.sortedWith(compareByDescending<ParentRow> { it.todo.completedAt ?: 0L }.thenBy { it.todo.id })
            } else {
                list.sortedWith { a, b -> order.compare(a.todo, b.todo) }
            }
            Section(kind, sorted)
        }
    }

    fun kindOf(todo: Todo, nowMillis: Long, zone: ZoneId): SectionKind {
        if (todo.status == TodoStatus.DONE) return SectionKind.DONE
        if (todo.status == TodoStatus.SHELVED) return SectionKind.SHELVED
        val due = todo.due ?: return SectionKind.NO_DATE
        if (DueTime.isOverdue(due, nowMillis, zone)) return SectionKind.OVERDUE
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return if (DueTime.localDate(due, zone) == today) SectionKind.TODAY else SectionKind.UPCOMING
    }

    /** 标签筛选的候选：全部在用的标签，按使用次数降序。 */
    fun tags(todos: List<Todo>): List<String> = TodoQueries.tagCounts(todos).map { it.first }
}

/** 顶部输入框的解析结果。 */
data class QuickAddInput(val title: String, val priority: Priority?)

/**
 * 快速添加里的优先级快捷：单独成词的 `!1` / `!2` / `!3`（全角 `！` 也认）分别是高 / 中 / 低，写在哪都行，多个取最后一个；
 * 从标题里去掉。别的 `!` 照原样留在标题里（“搞定这个!”不受影响）。
 */
object QuickAddParser {
    private val TOKEN = Regex("(^|\\s)[!！]([123])(?=\\s|$)")

    fun parse(text: String): QuickAddInput {
        var priority: Priority? = null
        val stripped = TOKEN.replace(text) { m ->
            priority = when (m.groupValues[2]) {
                "1" -> Priority.HIGH
                "2" -> Priority.MEDIUM
                else -> Priority.LOW
            }
            m.groupValues[1]
        }
        return QuickAddInput(stripped.replace(Regex("\\s{2,}"), " ").trim(), priority)
    }
}
