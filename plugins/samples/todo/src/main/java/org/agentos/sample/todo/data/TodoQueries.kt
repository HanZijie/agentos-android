package org.agentos.sample.todo.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/** `todo_list` 的筛选条件。全部是可选的；空条件 + [includeDone] = false 就是“除已完成之外的全部”。 */
data class TodoFilter(
    val status: TodoStatus? = null,
    val priority: Priority? = null,
    val tag: String? = null,
    val dueBefore: Due? = null,
    val dueAfter: Due? = null,
    val overdueOnly: Boolean = false,
    val parentId: String? = null,
    /** 没有显式 [status] 时，是否包含已完成的。给了 [status] 就以 [status] 为准，忽略它。 */
    val includeDone: Boolean = false,
)

/** 一条搜索命中：命中的位置（title / notes / tags）。 */
data class SearchHit(val todo: Todo, val matchedIn: List<String>)

/**
 * “今天、本周、逾期”的汇总（`todo_summary` 与界面顶部的概览共用）。
 * 口径（测试里逐条写明）：
 * - 时区：设备本地时区（调用方传入）。“今天”是本地日历日；全天待办按它自己的日期，带时刻的待办先换算到本地日期。
 * - 周：包含今天的那个日历周，起点由 [WeekFields] 决定（工具默认 `WeekFields.of(Locale.getDefault())`，即系统 / 区域的一周首日）。
 * - 只算“还在做”的（待办、进行中）；已完成和搁置的只出现在 [counts] 里。
 * - [overdue]：已经过了截止时间的。[dueToday]：今天到期且还没过期的（带时刻的，今天稍晚；全天的，今天）。
 *   [dueThisWeek]：本周内到期且还没过期的，**包含今天**。三者不重叠地回答“已经错过 / 今天要做 / 本周要做”。
 */
data class TodoSummaryResult(
    val counts: Map<TodoStatus, Int>,
    val total: Int,
    val overdue: Int,
    val dueToday: Int,
    val dueThisWeek: Int,
    val today: LocalDate,
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
)

object TodoQueries {
    /** 优先级（高在前）→ 截止时刻（早在前，没有截止的在后）→ 创建时间 → id。 */
    fun order(zone: ZoneId): Comparator<Todo> = Comparator { a, b ->
        val byPriority = a.priority.rank.compareTo(b.priority.rank)
        if (byPriority != 0) return@Comparator byPriority
        val ad = a.due?.let { DueTime.sortMillis(it, zone) }
        val bd = b.due?.let { DueTime.sortMillis(it, zone) }
        val byDue = when {
            ad == null && bd == null -> 0
            ad == null -> 1
            bd == null -> -1
            else -> ad.compareTo(bd)
        }
        if (byDue != 0) return@Comparator byDue
        val byCreated = a.createdAt.compareTo(b.createdAt)
        if (byCreated != 0) byCreated else a.id.compareTo(b.id)
    }

    fun filter(all: List<Todo>, f: TodoFilter, nowMillis: Long, zone: ZoneId): List<Todo> {
        val tagKey = f.tag?.let { tagKey(it) }
        return all.asSequence()
            .filter { t ->
                when {
                    f.status != null -> t.status == f.status
                    !f.includeDone -> t.status != TodoStatus.DONE
                    else -> true
                }
            }
            .filter { f.priority == null || it.priority == f.priority }
            .filter { tagKey == null || it.tags.any { tag -> tagKey(tag) == tagKey } }
            .filter { f.parentId == null || it.parentId == f.parentId }
            .filter { !f.overdueOnly || (it.status.isOpen && it.due != null && DueTime.isOverdue(it.due, nowMillis, zone)) }
            .filter { f.dueBefore == null || (it.due != null && DueTime.compare(it.due, f.dueBefore, zone) <= 0) }
            .filter { f.dueAfter == null || (it.due != null && DueTime.compare(it.due, f.dueAfter, zone) >= 0) }
            .sortedWith(order(zone))
            .toList()
    }

    /**
     * 标题、备注、标签的不分大小写包含匹配；空白分隔的多个词需要**全部**命中（每个词可以命中任何位置）。
     * 标题命中的排前面，其次按 [order]。[status] 为 null 时包含所有状态。
     */
    fun search(all: List<Todo>, query: String, status: TodoStatus?, zone: ZoneId): List<SearchHit> {
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val order = order(zone)
        return all.asSequence()
            .filter { status == null || it.status == status }
            .mapNotNull { t ->
                val title = t.title.lowercase(Locale.ROOT)
                val notes = t.notes.lowercase(Locale.ROOT)
                val tags = t.tags.joinToString("\n") { it.lowercase(Locale.ROOT) }
                if (!words.all { w -> w in title || w in notes || w in tags }) return@mapNotNull null
                val where = buildList {
                    if (words.any { it in title }) add("title")
                    if (words.any { it in notes }) add("notes")
                    if (words.any { it in tags }) add("tags")
                }
                SearchHit(t, where)
            }
            .sortedWith(compareBy<SearchHit> { "title" !in it.matchedIn }.thenComparator { a, b -> order.compare(a.todo, b.todo) })
            .toList()
    }

    fun summary(all: List<Todo>, nowMillis: Long, zone: ZoneId, week: WeekFields): TodoSummaryResult {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val firstDay: DayOfWeek = week.firstDayOfWeek
        val weekStart = today.minusDays(((today.dayOfWeek.value - firstDay.value + 7) % 7).toLong())
        val weekEnd = weekStart.plusDays(6)
        var overdue = 0
        var dueToday = 0
        var dueThisWeek = 0
        for (t in all) {
            val due = t.due ?: continue
            if (!t.status.isOpen) continue
            if (DueTime.isOverdue(due, nowMillis, zone)) {
                overdue++
                continue
            }
            val date = DueTime.localDate(due, zone)
            if (date == today) dueToday++
            if (!date.isBefore(today) && !date.isAfter(weekEnd)) dueThisWeek++
        }
        return TodoSummaryResult(
            counts = TodoStatus.entries.associateWith { s -> all.count { it.status == s } },
            total = all.size,
            overdue = overdue,
            dueToday = dueToday,
            dueThisWeek = dueThisWeek,
            today = today,
            weekStart = weekStart,
            weekEnd = weekEnd,
        )
    }

    fun tagKey(tag: String): String = tag.trim().trimStart('#').trim().lowercase(Locale.ROOT)

    /** 全部在用的标签（保留第一次出现的写法，大小写不敏感去重），按出现次数降序、再按名字。 */
    fun tagCounts(all: List<Todo>): List<Pair<String, Int>> {
        val names = LinkedHashMap<String, String>()
        val counts = HashMap<String, Int>()
        for (t in all) for (tag in t.tags) {
            val k = tagKey(tag)
            names.putIfAbsent(k, tag)
            counts[k] = (counts[k] ?: 0) + 1
        }
        return names.map { (k, name) -> name to counts.getValue(k) }.sortedWith(compareBy({ -it.second }, { it.first.lowercase(Locale.ROOT) }))
    }

    /** 标签规范化：去首尾空白和开头的 #，丢弃空标签，大小写不敏感去重（保留第一次的写法）。不合法抛 [TodoException]。 */
    fun normalizeTags(raw: List<String>): List<String> {
        val result = LinkedHashMap<String, String>()
        for (item in raw) {
            val tag = item.trim().trimStart('#').trim()
            if (tag.isEmpty()) continue
            if (tag.any { it == ',' || it == '，' || it == '\n' || it == '\r' }) {  // i18n-ok: rejects the full-width comma in a tag (input validation), not UI text
                throw TodoException(TodoException.Kind.INVALID, "Tag \"${tag.take(20)}\" must not contain commas or line breaks.")
            }
            if (tag.length > TodoLimits.MAX_TAG_CHARS) {
                throw TodoException(TodoException.Kind.INVALID, "Tag \"${tag.take(20)}…\" is longer than ${TodoLimits.MAX_TAG_CHARS} characters.")
            }
            result.putIfAbsent(tagKey(tag), tag)
        }
        if (result.size > TodoLimits.MAX_TAGS) {
            throw TodoException(TodoException.Kind.INVALID, "A todo can have at most ${TodoLimits.MAX_TAGS} tags.")
        }
        return result.values.toList()
    }
}
