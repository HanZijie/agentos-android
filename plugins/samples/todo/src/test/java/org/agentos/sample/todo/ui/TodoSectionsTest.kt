package org.agentos.sample.todo.ui

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.agentos.sample.todo.NOW
import org.agentos.sample.todo.UTC8
import org.agentos.sample.todo.data.Due
import org.agentos.sample.todo.data.Priority
import org.agentos.sample.todo.data.Todo
import org.agentos.sample.todo.data.TodoStatus
import org.agentos.sample.todo.millis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoSectionsTest {
    private var seq = 0L

    private fun todo(
        id: String,
        status: TodoStatus = TodoStatus.TODO,
        priority: Priority = Priority.MEDIUM,
        due: Due? = null,
        parent: String? = null,
        tags: List<String> = emptyList(),
        completedAt: Long? = null,
    ) = Todo(id, id, "", status, priority, due, tags, parent, completedAt ?: if (status == TodoStatus.DONE) NOW else null, ++seq, seq)

    private fun day(iso: String) = Due.Day(LocalDate.parse(iso))
    private fun at(iso: String) = Due.At(millis(iso))

    private val now = millis("2026-10-09T10:00:00+08:00")

    private fun build(todos: List<Todo>, filter: ListFilter = ListFilter(), zone: ZoneId = UTC8) = TodoSections.build(todos, filter, now, zone)

    private fun kinds(sections: List<Section>) = sections.map { it.kind }
    private fun ids(section: Section) = section.rows.map { it.todo.id }

    @Test fun `todos fall into overdue, today, upcoming, no date, on hold and done`() {
        val sections = build(
            listOf(
                todo("overdue-all-day", due = day("2026-10-08")),
                todo("overdue-timed-today", due = at("2026-10-09T09:00:00+08:00")),
                todo("today-all-day", due = day("2026-10-09")),
                todo("today-timed-later", due = at("2026-10-09T18:00:00+08:00")),
                todo("upcoming", due = day("2026-10-20")),
                todo("no-date"),
                todo("doing-no-date", TodoStatus.DOING),
                todo("shelved", TodoStatus.SHELVED, due = day("2026-10-01")),
                todo("done", TodoStatus.DONE, due = day("2026-10-01")),
            ),
        )
        assertEquals(
            listOf(SectionKind.OVERDUE, SectionKind.TODAY, SectionKind.UPCOMING, SectionKind.NO_DATE, SectionKind.SHELVED, SectionKind.DONE),
            kinds(sections),
        )
        val by = sections.associate { it.kind to ids(it) }
        assertEquals(setOf("overdue-all-day", "overdue-timed-today"), by.getValue(SectionKind.OVERDUE).toSet())
        assertEquals(setOf("today-all-day", "today-timed-later"), by.getValue(SectionKind.TODAY).toSet())
        assertEquals(listOf("upcoming"), by.getValue(SectionKind.UPCOMING))
        assertEquals(setOf("no-date", "doing-no-date"), by.getValue(SectionKind.NO_DATE).toSet())
        assertEquals(listOf("shelved"), by.getValue(SectionKind.SHELVED))
        assertEquals(listOf("done"), by.getValue(SectionKind.DONE))
    }

    @Test fun `empty sections are left out, and a done or on-hold section starts collapsed`() {
        assertEquals(listOf(SectionKind.NO_DATE), kinds(build(listOf(todo("a")))))
        assertTrue(SectionKind.DONE.collapsedByDefault && SectionKind.SHELVED.collapsedByDefault)
        assertTrue(listOf(SectionKind.OVERDUE, SectionKind.TODAY, SectionKind.UPCOMING, SectionKind.NO_DATE).none { it.collapsedByDefault })
        assertEquals(emptyList<Section>(), build(emptyList()))
    }

    @Test fun `within a section the order is priority, then due, then creation`() {
        val sections = build(
            listOf(
                todo("low-soon", priority = Priority.LOW, due = day("2026-10-12")),
                todo("high-late", priority = Priority.HIGH, due = day("2026-10-30")),
                todo("high-soon", priority = Priority.HIGH, due = day("2026-10-12")),
            ),
        )
        assertEquals(listOf("high-soon", "high-late", "low-soon"), ids(sections.single()))
    }

    @Test fun `done todos list the most recently completed first`() {
        val sections = build(
            listOf(
                todo("old", TodoStatus.DONE, completedAt = now - 100_000),
                todo("new", TodoStatus.DONE, completedAt = now - 1_000),
            ),
        )
        assertEquals(listOf("new", "old"), ids(sections.single()))
    }

    @Test fun `subtasks hang under their parent whatever their own date or status`() {
        val sections = build(
            listOf(
                todo("parent", due = day("2026-10-20")),
                todo("c-done", TodoStatus.DONE, parent = "parent"),
                todo("c-overdue", due = day("2026-10-01"), parent = "parent"),
            ),
        )
        assertEquals(listOf(SectionKind.UPCOMING), kinds(sections))
        val row = sections.single().rows.single()
        assertEquals(listOf("c-done", "c-overdue"), row.subtasks.map { it.id }) // 创建顺序
        assertEquals(1, row.doneCount)
    }

    @Test fun `filters apply to the todo itself, and a matching subtask keeps its parent visible`() {
        val todos = listOf(
            todo("a", priority = Priority.HIGH),
            todo("b", priority = Priority.LOW),
            todo("parent", priority = Priority.LOW),
            todo("child-high", priority = Priority.HIGH, parent = "parent"),
            todo("child-low", priority = Priority.LOW, parent = "parent"),
        )
        val sections = build(todos, ListFilter(priority = Priority.HIGH))
        assertEquals(listOf("a", "parent"), ids(sections.single()).sorted())
        // 保留的父任务仍显示它的全部子任务
        assertEquals(2, sections.single().rows.single { it.todo.id == "parent" }.subtasks.size)
    }

    @Test fun `status and tag filters`() {
        val todos = listOf(
            todo("a", TodoStatus.DOING, tags = listOf("Work")),
            todo("b", tags = listOf("work")),
            todo("c", TodoStatus.DONE, tags = listOf("work")),
        )
        assertEquals(listOf("a"), build(todos, ListFilter(status = TodoStatus.DOING)).flatMap { s -> ids(s) })
        assertEquals(listOf("c"), build(todos, ListFilter(status = TodoStatus.DONE)).flatMap { s -> ids(s) })
        assertEquals(setOf("a", "b", "c"), build(todos, ListFilter(tag = "WORK")).flatMap { s -> ids(s) }.toSet())
        assertTrue(build(todos, ListFilter(tag = "none")).isEmpty())
        assertEquals(false, ListFilter().isActive)
        assertEquals(true, ListFilter(tag = "x").isActive)
    }

    @Test fun `the zone decides what today is`() {
        // 2026-10-09T23:30+08:00 = 15:30Z；一个 UTC 10 月 9 日 20:00 到期（= 本地 10 月 10 日 04:00）的待办
        val late = millis("2026-10-09T23:30:00+08:00")
        val due = at("2026-10-10T04:00:00+08:00")
        val t = todo("t", due = due)
        assertEquals(SectionKind.UPCOMING, TodoSections.kindOf(t, late, UTC8))
        assertEquals(SectionKind.TODAY, TodoSections.kindOf(t, late, ZoneOffset.UTC))
        // 全天的“今天”随时区
        val allDay = todo("d", due = day("2026-10-09"))
        assertEquals(SectionKind.TODAY, TodoSections.kindOf(allDay, late, UTC8))
        assertEquals(SectionKind.OVERDUE, TodoSections.kindOf(allDay, millis("2026-10-09T20:30:00Z"), UTC8))
        assertEquals(SectionKind.TODAY, TodoSections.kindOf(allDay, millis("2026-10-09T20:30:00Z"), ZoneOffset.UTC))
    }

    @Test fun `tag candidates are the tags in use, most used first`() {
        val todos = listOf(todo("1", tags = listOf("a", "b")), todo("2", tags = listOf("B")), todo("3", tags = listOf("c")))
        assertEquals(listOf("b", "a", "c"), TodoSections.tags(todos).map { it.lowercase() })
    }
}

class QuickAddParserTest {
    @Test fun `plain text becomes the title with no priority`() {
        assertEquals(QuickAddInput("买牛奶", null), QuickAddParser.parse("买牛奶"))
        assertEquals(QuickAddInput("Call the plumber", null), QuickAddParser.parse("  Call the plumber  "))
    }

    @Test fun `!1 !2 !3 set high, medium, low wherever they stand and leave the title`() {
        assertEquals(QuickAddInput("Write PRD", Priority.HIGH), QuickAddParser.parse("Write PRD !1"))
        assertEquals(QuickAddInput("Write PRD", Priority.MEDIUM), QuickAddParser.parse("!2 Write PRD"))
        assertEquals(QuickAddInput("Write PRD now", Priority.LOW), QuickAddParser.parse("Write PRD !3 now"))
        assertEquals(QuickAddInput("写 PRD 大纲", Priority.HIGH), QuickAddParser.parse("写 PRD ！1 大纲"))
        assertEquals(QuickAddInput("a b", Priority.HIGH), QuickAddParser.parse("a !1 b"))
    }

    @Test fun `the last priority token wins`() {
        assertEquals(Priority.LOW, QuickAddParser.parse("x !1 !3").priority)
    }

    @Test fun `other exclamation marks stay in the title`() {
        assertEquals(QuickAddInput("Ship it!", null), QuickAddParser.parse("Ship it!"))
        assertEquals(QuickAddInput("Ship!1", null), QuickAddParser.parse("Ship!1"))
        assertEquals(QuickAddInput("Wow !4", null), QuickAddParser.parse("Wow !4"))
    }

    @Test fun `a title made only of a priority token is empty`() {
        val parsed = QuickAddParser.parse("!1")
        assertEquals("", parsed.title)
        assertEquals(Priority.HIGH, parsed.priority)
        assertNull(QuickAddParser.parse("").priority)
    }
}
