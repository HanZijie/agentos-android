package org.agentos.sample.todo.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.WeekFields
import java.util.Locale
import org.agentos.sample.todo.NOW
import org.agentos.sample.todo.UTC8
import org.agentos.sample.todo.millis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoQueriesTest {
    private var seq = 0L

    private fun todo(
        id: String,
        status: TodoStatus = TodoStatus.TODO,
        priority: Priority = Priority.MEDIUM,
        due: Due? = null,
        tags: List<String> = emptyList(),
        parent: String? = null,
        title: String = id,
        notes: String = "",
    ) = Todo(id, title, notes, status, priority, due, tags, parent, if (status == TodoStatus.DONE) NOW else null, ++seq, seq)

    private fun day(iso: String) = Due.Day(LocalDate.parse(iso))
    private fun at(iso: String) = Due.At(millis(iso))

    private fun ids(list: List<Todo>) = list.map { it.id }

    // ---------------------------------------------------------------- 排序

    @Test fun `order is priority, then due soonest first, undated last, then creation`() {
        val all = listOf(
            todo("low-soon", priority = Priority.LOW, due = day("2026-10-10")),
            todo("high-late", priority = Priority.HIGH, due = day("2026-10-20")),
            todo("high-none"),
            todo("high-soon", priority = Priority.HIGH, due = at("2026-10-10T09:00:00+08:00")),
            todo("med-none-a", priority = Priority.MEDIUM),
            todo("med-none-b", priority = Priority.MEDIUM),
        ).map { if (it.id == "high-none") it.copy(priority = Priority.HIGH) else it }
        val sorted = all.sortedWith(TodoQueries.order(UTC8))
        assertEquals(listOf("high-soon", "high-late", "high-none", "med-none-a", "med-none-b", "low-soon"), ids(sorted))
    }

    @Test fun `an all-day due sorts as the start of its local day`() {
        val allDay = todo("all-day", due = day("2026-10-10"))
        val early = todo("early", due = at("2026-10-10T00:30:00+08:00"))
        val late = todo("late", due = at("2026-10-09T23:30:00+08:00"))
        assertEquals(listOf("late", "all-day", "early"), ids(listOf(allDay, early, late).sortedWith(TodoQueries.order(UTC8))))
    }

    // ---------------------------------------------------------------- 筛选

    private val now = millis("2026-10-09T10:00:00+08:00")

    private fun filter(all: List<Todo>, f: TodoFilter = TodoFilter(), zone: ZoneId = UTC8) = ids(TodoQueries.filter(all, f, now, zone))

    @Test fun `done todos are hidden unless asked for, and an explicit status wins`() {
        val all = listOf(todo("a"), todo("b", TodoStatus.DONE), todo("c", TodoStatus.SHELVED), todo("d", TodoStatus.DOING))
        assertEquals(listOf("a", "c", "d"), filter(all).sorted())
        assertEquals(listOf("a", "b", "c", "d"), filter(all, TodoFilter(includeDone = true)).sorted())
        assertEquals(listOf("b"), filter(all, TodoFilter(status = TodoStatus.DONE)))
        assertEquals(listOf("c"), filter(all, TodoFilter(status = TodoStatus.SHELVED, includeDone = false)))
    }

    @Test fun `priority, tag (case-insensitive) and parent filters`() {
        val all = listOf(
            todo("a", priority = Priority.HIGH, tags = listOf("Work")),
            todo("b", priority = Priority.LOW, tags = listOf("home")),
            todo("c", parent = "a", tags = listOf("work")),
        )
        assertEquals(listOf("a"), filter(all, TodoFilter(priority = Priority.HIGH)))
        assertEquals(listOf("a", "c"), filter(all, TodoFilter(tag = "WORK")).sorted())
        assertEquals(listOf("c"), filter(all, TodoFilter(parentId = "a")))
        assertEquals(emptyList<String>(), filter(all, TodoFilter(parentId = "nope")))
    }

    @Test fun `overdue_only keeps open todos past their due time and nothing else`() {
        val all = listOf(
            todo("past-timed", due = at("2026-10-09T09:00:00+08:00")),
            todo("later-today-timed", due = at("2026-10-09T18:00:00+08:00")),
            todo("yesterday", due = day("2026-10-08")),
            todo("today-all-day", due = day("2026-10-09")),
            todo("doing-past", TodoStatus.DOING, due = day("2026-10-01")),
            todo("done-past", TodoStatus.DONE, due = day("2026-10-01")),
            todo("shelved-past", TodoStatus.SHELVED, due = day("2026-10-01")),
            todo("no-due"),
        )
        assertEquals(listOf("doing-past", "past-timed", "yesterday"), filter(all, TodoFilter(overdueOnly = true, includeDone = true)).sorted())
    }

    @Test fun `date bounds are inclusive and compare by the local calendar date`() {
        val all = listOf(
            todo("oct9-all-day", due = day("2026-10-09")),
            todo("oct10-all-day", due = day("2026-10-10")),
            todo("oct10-late-timed", due = at("2026-10-10T23:59:00+08:00")),
            todo("oct11-timed", due = at("2026-10-11T10:00:00+08:00")),
            todo("oct12-all-day", due = day("2026-10-12")),
            todo("none"),
        )
        assertEquals(
            listOf("oct10-all-day", "oct10-late-timed", "oct11-timed", "oct9-all-day"),
            filter(all, TodoFilter(dueBefore = DueTime.parse("2026-10-11"))).sorted(),
        )
        assertEquals(
            listOf("oct11-timed", "oct12-all-day"),
            filter(all, TodoFilter(dueAfter = DueTime.parse("2026-10-11"))).sorted(),
        )
        assertEquals(
            listOf("oct10-all-day", "oct10-late-timed"),
            filter(all, TodoFilter(dueAfter = DueTime.parse("2026-10-10"), dueBefore = DueTime.parse("2026-10-10"))).sorted(),
        )
    }

    @Test fun `date bounds follow the device zone for timed todos`() {
        // 2026-10-10T00:30+08:00 在 UTC 里是 10 月 9 日 16:30
        val t = todo("t", due = at("2026-10-10T00:30:00+08:00"))
        assertEquals(listOf("t"), filter(listOf(t), TodoFilter(dueAfter = DueTime.parse("2026-10-10")), UTC8))
        assertEquals(emptyList<String>(), filter(listOf(t), TodoFilter(dueAfter = DueTime.parse("2026-10-10")), ZoneOffset.UTC))
        assertEquals(listOf("t"), filter(listOf(t), TodoFilter(dueBefore = DueTime.parse("2026-10-09")), ZoneOffset.UTC))
    }

    @Test fun `an instant bound compares instants for timed todos and dates for all-day ones`() {
        val all = listOf(
            todo("timed-before", due = at("2026-10-11T09:30:00+08:00")),
            todo("timed-after", due = at("2026-10-11T10:30:00+08:00")),
            todo("all-day-same-date", due = day("2026-10-11")),
            todo("all-day-next", due = day("2026-10-12")),
        )
        val bound = DueTime.parse("2026-10-11T10:00:00+08:00")
        assertEquals(listOf("all-day-same-date", "timed-before"), filter(all, TodoFilter(dueBefore = bound)).sorted())
        assertEquals(listOf("all-day-next", "all-day-same-date", "timed-after"), filter(all, TodoFilter(dueAfter = bound)).sorted())
    }

    // ---------------------------------------------------------------- 搜索

    @Test fun `search needs every word, ignores case and looks at title, notes and tags`() {
        val all = listOf(
            todo("1", title = "Write the PRD", notes = "for search"),
            todo("2", title = "Call plumber", tags = listOf("Home")),
            todo("3", title = "Review", notes = "the prd draft", tags = listOf("work")),
            todo("4", title = "Unrelated"),
        )
        assertEquals(listOf("1", "3"), ids(TodoQueries.search(all, "prd", null, UTC8).map { it.todo }))
        assertEquals(listOf("1"), ids(TodoQueries.search(all, "WRITE  prd", null, UTC8).map { it.todo }))
        assertEquals(listOf("2"), ids(TodoQueries.search(all, "home", null, UTC8).map { it.todo }))
        assertEquals(emptyList<String>(), ids(TodoQueries.search(all, "prd plumber", null, UTC8).map { it.todo }))
        assertEquals(emptyList<String>(), ids(TodoQueries.search(all, "   ", null, UTC8).map { it.todo }))
    }

    @Test fun `search reports where it matched and puts title matches first`() {
        val all = listOf(
            todo("notes-only", title = "Alpha", notes = "has the keyword inside"),
            todo("title", title = "keyword first"),
            todo("tag", title = "Gamma", tags = listOf("keyword")),
        )
        val hits = TodoQueries.search(all, "keyword", null, UTC8)
        assertEquals("title", hits.first().todo.id)
        assertEquals(listOf("title"), hits.first().matchedIn)
        assertEquals(listOf("notes"), hits.single { it.todo.id == "notes-only" }.matchedIn)
        assertEquals(listOf("tags"), hits.single { it.todo.id == "tag" }.matchedIn)
    }

    @Test fun `search covers every status unless one is given`() {
        val all = listOf(todo("a", title = "plan"), todo("b", TodoStatus.DONE, title = "plan done"))
        assertEquals(setOf("a", "b"), TodoQueries.search(all, "plan", null, UTC8).map { it.todo.id }.toSet())
        assertEquals(listOf("b"), TodoQueries.search(all, "plan", TodoStatus.DONE, UTC8).map { it.todo.id })
    }

    // ---------------------------------------------------------------- todo_summary 的口径

    /**
     * 口径：时区是设备本地时区；“今天”是本地日历日；周是包含今天的日历周，起点由 WeekFields 决定；只算待办 / 进行中；
     * overdue = 已过截止（全天的从次日起算）；due_today = 今天到期且没过期；due_this_week = 从今天到本周最后一天到期且没过期（含今天）。
     */
    private val crossMidnightNow = millis("2026-10-09T23:30:00+08:00") // = 2026-10-09T15:30:00Z

    private val summaryTodos = listOf(
        todo("A-tomorrow-00:10", due = at("2026-10-10T00:10:00+08:00")), // UTC 里是 10 月 9 日 16:10：同一个 UTC 日期
        todo("B-23:00-past", due = at("2026-10-09T23:00:00+08:00")),
        todo("C-all-day-today", due = day("2026-10-09")),
        todo("D-all-day-yesterday", due = day("2026-10-08")),
        todo("E-23:45-later", due = at("2026-10-09T23:45:00+08:00")),
        todo("F-next-monday", due = day("2026-10-12")),
        todo("G-done", TodoStatus.DONE, due = day("2026-10-01")),
        todo("H-shelved", TodoStatus.SHELVED, due = day("2026-10-01")),
        todo("I-doing-no-due", TodoStatus.DOING),
        todo("J-sunday", due = day("2026-10-11")),
    )

    @Test fun `summary at 23-30 local time near midnight in the +08-00 zone, Monday-first week`() {
        val s = TodoQueries.summary(summaryTodos, crossMidnightNow, UTC8, WeekFields.ISO)
        assertEquals(LocalDate.of(2026, 10, 9), s.today)
        assertEquals(LocalDate.of(2026, 10, 5), s.weekStart)
        assertEquals(LocalDate.of(2026, 10, 11), s.weekEnd)
        assertEquals(2, s.overdue) // B（23:00 已过）和 D（昨天的全天）
        assertEquals(2, s.dueToday) // C（全天今天）和 E（23:45）；A 在本地已经是明天
        assertEquals(4, s.dueThisWeek) // A、C、E、J（周日仍在周一起算的这一周里）；F 是下周一
        assertEquals(10, s.total)
        assertEquals(7, s.counts.getValue(TodoStatus.TODO))
        assertEquals(1, s.counts.getValue(TodoStatus.DOING))
        assertEquals(1, s.counts.getValue(TodoStatus.DONE))
        assertEquals(1, s.counts.getValue(TodoStatus.SHELVED))
    }

    @Test fun `the same instant in UTC lands in different buckets because the local day is different`() {
        // 同一时刻 15:30Z：UTC 里今天才刚过一半，A（16:10Z）和 E（15:45Z）都还没到
        val s = TodoQueries.summary(summaryTodos, crossMidnightNow, ZoneOffset.UTC, WeekFields.ISO)
        assertEquals(LocalDate.of(2026, 10, 9), s.today)
        assertEquals(2, s.overdue) // B（15:00Z 已过）和 D
        assertEquals(3, s.dueToday) // A、C、E 都在 UTC 的 10 月 9 日且没过期
        assertEquals(4, s.dueThisWeek) // A、C、E、J；F 仍是下周
    }

    @Test fun `just after midnight an all-day due of yesterday flips to overdue in the zone that already crossed it`() {
        val instant = millis("2026-10-09T20:30:00Z") // +08:00 是 10 月 10 日 04:30，UTC 还是 10 月 9 日
        val allDayOct9 = listOf(todo("x", due = day("2026-10-09")))
        val ahead = TodoQueries.summary(allDayOct9, instant, UTC8, WeekFields.ISO)
        assertEquals(LocalDate.of(2026, 10, 10), ahead.today)
        assertEquals(1, ahead.overdue)
        assertEquals(0, ahead.dueToday)
        val behind = TodoQueries.summary(allDayOct9, instant, ZoneOffset.UTC, WeekFields.ISO)
        assertEquals(LocalDate.of(2026, 10, 9), behind.today)
        assertEquals(0, behind.overdue)
        assertEquals(1, behind.dueToday)
    }

    @Test fun `a Sunday-first week ends on Saturday so a Sunday due falls into the next week`() {
        val s = TodoQueries.summary(summaryTodos, crossMidnightNow, UTC8, WeekFields.SUNDAY_START)
        assertEquals(LocalDate.of(2026, 10, 4), s.weekStart)
        assertEquals(LocalDate.of(2026, 10, 10), s.weekEnd)
        assertEquals(3, s.dueThisWeek) // A、C、E；J（周日 10 月 11 日）已经是下一周
    }

    @Test fun `week start follows the locale, and a Sunday starts the new week where weeks begin on Sunday`() {
        val friday = millis("2026-10-09T12:00:00+08:00")
        val sunday = millis("2026-10-11T12:00:00+08:00")
        val us = WeekFields.of(Locale.US) // 周日起
        val fr = WeekFields.of(Locale.FRANCE) // 周一起
        assertEquals(LocalDate.of(2026, 10, 4), TodoQueries.summary(emptyList(), friday, UTC8, us).weekStart)
        assertEquals(LocalDate.of(2026, 10, 5), TodoQueries.summary(emptyList(), friday, UTC8, fr).weekStart)
        assertEquals(LocalDate.of(2026, 10, 11), TodoQueries.summary(emptyList(), sunday, UTC8, us).weekStart)
        assertEquals(LocalDate.of(2026, 10, 17), TodoQueries.summary(emptyList(), sunday, UTC8, us).weekEnd)
        assertEquals(LocalDate.of(2026, 10, 5), TodoQueries.summary(emptyList(), sunday, UTC8, fr).weekStart)
    }

    @Test fun `done and shelved todos never count as overdue or due, but are in the totals`() {
        val s = TodoQueries.summary(
            listOf(
                todo("d", TodoStatus.DONE, due = day("2026-10-01")),
                todo("s", TodoStatus.SHELVED, due = day("2026-10-09")),
            ),
            now, UTC8, WeekFields.ISO,
        )
        assertEquals(0, s.overdue)
        assertEquals(0, s.dueToday)
        assertEquals(0, s.dueThisWeek)
        assertEquals(2, s.total)
    }

    // ---------------------------------------------------------------- 标签

    @Test fun `tag counts are case-insensitive, most used first`() {
        val all = listOf(todo("1", tags = listOf("Work", "home")), todo("2", tags = listOf("work")), todo("3", tags = listOf("Work")))
        assertEquals(listOf("Work" to 3, "home" to 1), TodoQueries.tagCounts(all))
    }

    @Test fun `weekdays sanity check for the fixture date`() {
        assertTrue(LocalDate.of(2026, 10, 9).dayOfWeek == java.time.DayOfWeek.FRIDAY)
    }
}
