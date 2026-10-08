package org.agentos.sample.todo.data

import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.agentos.sample.todo.InMemoryTodoStore
import org.agentos.sample.todo.NOW
import org.agentos.sample.todo.TestEnv
import org.agentos.sample.todo.data.TodoException.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TodoRepositoryTest {
    private val env = TestEnv()
    private val repo = env.repo

    private suspend fun fails(kind: Kind, block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: TodoException) {
            assertEquals(e.message, kind, e.kind)
            assertTrue("one line: ${e.message}", !e.message!!.contains('\n'))
            return e.message!!
        }
        fail("expected a $kind failure")
        error("unreachable")
    }

    // ---------------------------------------------------------------- 创建与校验

    @Test fun `create applies defaults and persists`() = runTest {
        val t = repo.create(TodoDraft("  Write PRD  "))
        assertEquals("Write PRD", t.title)
        assertEquals(TodoStatus.TODO, t.status)
        assertEquals(Priority.MEDIUM, t.priority)
        assertNull(t.due)
        assertNull(t.completedAt)
        assertNull(t.parentId)
        assertEquals(NOW, t.createdAt)
        assertEquals(t, env.store.rows[t.id])
        assertEquals(listOf(t), repo.todos.value)
    }

    @Test fun `title is required, single line and at most 200 characters`() = runTest {
        fails(Kind.INVALID) { repo.create(TodoDraft("   ")) }
        assertEquals("a b", repo.create(TodoDraft("a\r\nb")).title)
        assertEquals(200, repo.create(TodoDraft("x".repeat(200))).title.length)
        assertTrue(fails(Kind.INVALID) { repo.create(TodoDraft("x".repeat(201))) }.contains("200"))
    }

    @Test fun `notes are normalised and limited`() = runTest {
        assertEquals("a\nb", repo.create(TodoDraft("t", notes = " a\r\nb ")).notes)
        assertEquals(TodoLimits.MAX_NOTES_CHARS, repo.create(TodoDraft("t", notes = "n".repeat(TodoLimits.MAX_NOTES_CHARS))).notes.length)
        fails(Kind.INVALID) { repo.create(TodoDraft("t", notes = "n".repeat(TodoLimits.MAX_NOTES_CHARS + 1))) }
    }

    @Test fun `tags are trimmed, deduplicated case-insensitively and validated`() = runTest {
        assertEquals(listOf("Work", "home"), repo.create(TodoDraft("t", tags = listOf(" #Work ", "work", "", "home"))).tags)
        fails(Kind.INVALID) { repo.create(TodoDraft("t", tags = listOf("a,b"))) }
        fails(Kind.INVALID) { repo.create(TodoDraft("t", tags = listOf("x".repeat(33)))) }
        fails(Kind.INVALID) { repo.create(TodoDraft("t", tags = (1..21).map { "tag$it" })) }
    }

    @Test fun `creating as done records completed_at`() = runTest {
        val t = repo.create(TodoDraft("t", status = TodoStatus.DONE))
        assertEquals(NOW, t.completedAt)
    }

    // ---------------------------------------------------------------- 子任务

    @Test fun `a subtask points at an existing top-level parent`() = runTest {
        val parent = repo.create(TodoDraft("parent"))
        val child = repo.create(TodoDraft("child", parentId = parent.id))
        assertEquals(parent.id, child.parentId)
        assertEquals(listOf(child), repo.subtasksOf(parent.id))
        fails(Kind.NOT_FOUND) { repo.create(TodoDraft("orphan", parentId = "nope")) }
    }

    @Test fun `only one level of subtasks is allowed`() = runTest {
        val parent = repo.create(TodoDraft("parent"))
        val child = repo.create(TodoDraft("child", parentId = parent.id))
        val message = fails(Kind.INVALID) { repo.create(TodoDraft("grandchild", parentId = child.id)) }
        assertTrue(message, message.contains("Subtasks cannot have their own subtasks"))
        assertEquals(2, repo.todos.value.size)
    }

    @Test fun `a parent holds at most 100 subtasks`() = runTest {
        val parent = repo.create(TodoDraft("parent"))
        repeat(TodoLimits.MAX_SUBTASKS) { repo.create(TodoDraft("c$it", parentId = parent.id)) }
        fails(Kind.INVALID) { repo.create(TodoDraft("one too many", parentId = parent.id)) }
    }

    @Test fun `moving a todo under another one follows the same rules`() = runTest {
        val a = repo.create(TodoDraft("a"))
        val b = repo.create(TodoDraft("b"))
        val c = repo.create(TodoDraft("c", parentId = a.id))
        // b 变成 a 的子任务
        assertEquals(a.id, repo.update(b.id, TodoPatch(parentId = Change.Set(a.id))).parentId)
        // a 有子任务，不能变成别人的子任务；也不能是自己的子任务
        fails(Kind.INVALID) { repo.update(a.id, TodoPatch(parentId = Change.Set(b.id))) }
        fails(Kind.INVALID) { repo.update(a.id, TodoPatch(parentId = Change.Set(a.id))) }
        // 子任务下面不能再挂
        fails(Kind.INVALID) { repo.update(repo.create(TodoDraft("d")).id, TodoPatch(parentId = Change.Set(c.id))) }
        // 脱离父任务
        assertNull(repo.update(c.id, TodoPatch(parentId = Change.Clear)).parentId)
        fails(Kind.NOT_FOUND) { repo.update(b.id, TodoPatch(parentId = Change.Set("nope"))) }
    }

    @Test fun `deleting a parent deletes its subtasks and reports them all`() = runTest {
        val parent = repo.create(TodoDraft("parent"))
        val c1 = repo.create(TodoDraft("c1", parentId = parent.id))
        val c2 = repo.create(TodoDraft("c2", parentId = parent.id))
        val other = repo.create(TodoDraft("other"))
        val gone = repo.delete(parent.id)
        assertEquals(listOf(parent.id, c1.id, c2.id), gone.map { it.id })
        assertEquals(listOf(other), repo.todos.value)
        assertEquals(setOf(other.id), env.store.rows.keys)
    }

    @Test fun `deleting a subtask leaves its parent alone`() = runTest {
        val parent = repo.create(TodoDraft("parent"))
        val child = repo.create(TodoDraft("child", parentId = parent.id))
        assertEquals(listOf(child.id), repo.delete(child.id).map { it.id })
        assertEquals(listOf(parent.id), repo.todos.value.map { it.id })
    }

    @Test fun `deleting an unknown id fails`() = runTest {
        fails(Kind.NOT_FOUND) { repo.delete("nope") }
    }

    // ---------------------------------------------------------------- 更新

    @Test fun `update changes only the given fields and keeps the rest`() = runTest {
        val t = repo.create(TodoDraft("t", notes = "n", priority = Priority.LOW, due = Due.Day(LocalDate.of(2026, 10, 12)), tags = listOf("a")))
        env.advance()
        val u = repo.update(t.id, TodoPatch(title = "t2", priority = Priority.HIGH))
        assertEquals("t2", u.title)
        assertEquals(Priority.HIGH, u.priority)
        assertEquals("n", u.notes)
        assertEquals(t.due, u.due)
        assertEquals(listOf("a"), u.tags)
        assertTrue(u.updatedAt > t.updatedAt)
        assertEquals(t.createdAt, u.createdAt)
    }

    @Test fun `due can be set, replaced and cleared`() = runTest {
        val t = repo.create(TodoDraft("t"))
        val d = Due.At(NOW + 3_600_000)
        assertEquals(d, repo.update(t.id, TodoPatch(due = Change.Set(d))).due)
        assertNull(repo.update(t.id, TodoPatch(due = Change.Clear)).due)
    }

    @Test fun `an update that changes nothing writes nothing and keeps updated_at`() = runTest {
        val t = repo.create(TodoDraft("t", priority = Priority.HIGH))
        val saves = env.store.saveCount
        env.advance()
        val same = repo.update(t.id, TodoPatch(title = "t", priority = Priority.HIGH))
        assertEquals(t, same)
        assertEquals(saves, env.store.saveCount)
    }

    @Test fun `update validates the same way as create and reports unknown ids`() = runTest {
        val t = repo.create(TodoDraft("t"))
        fails(Kind.INVALID) { repo.update(t.id, TodoPatch(title = "  ")) }
        fails(Kind.INVALID) { repo.update(t.id, TodoPatch(tags = listOf("a,b"))) }
        fails(Kind.NOT_FOUND) { repo.update("nope", TodoPatch(title = "x")) }
        assertEquals("t", repo.get(t.id).title)
    }

    // ---------------------------------------------------------------- 状态流转

    @Test fun `done records completed_at, leaving done clears it, done twice keeps the first time`() = runTest {
        val t = repo.create(TodoDraft("t"))
        assertNull(t.completedAt)

        env.advance(60_000)
        val doing = repo.setStatus(t.id, TodoStatus.DOING)
        assertEquals(TodoStatus.DOING, doing.status)
        assertNull(doing.completedAt)

        env.advance(60_000)
        val done = repo.setStatus(t.id, TodoStatus.DONE)
        assertEquals(env.now, done.completedAt)

        val completedAt = done.completedAt
        env.advance(60_000)
        val again = repo.setStatus(t.id, TodoStatus.DONE)
        assertEquals(completedAt, again.completedAt)
        assertEquals(done, again)

        env.advance(60_000)
        val reopened = repo.setStatus(t.id, TodoStatus.TODO)
        assertNull(reopened.completedAt)

        env.advance(60_000)
        val doneAgain = repo.setStatus(t.id, TodoStatus.DONE)
        assertEquals(env.now, doneAgain.completedAt)
    }

    @Test fun `done goes straight to shelved and clears completed_at`() = runTest {
        val t = repo.create(TodoDraft("t", status = TodoStatus.DONE))
        assertNotNull(t.completedAt)
        assertNull(repo.setStatus(t.id, TodoStatus.SHELVED).completedAt)
    }

    @Test fun `status through update behaves like set_status`() = runTest {
        val t = repo.create(TodoDraft("t"))
        assertEquals(NOW, repo.update(t.id, TodoPatch(status = TodoStatus.DONE)).completedAt)
        assertNull(repo.update(t.id, TodoPatch(status = TodoStatus.DOING)).completedAt)
    }

    @Test fun `completing a parent does not touch its subtasks`() = runTest {
        val p = repo.create(TodoDraft("p"))
        val c = repo.create(TodoDraft("c", parentId = p.id))
        repo.setStatus(p.id, TodoStatus.DONE)
        assertEquals(TodoStatus.TODO, repo.get(c.id).status)
    }

    // ---------------------------------------------------------------- 撤销 / 清空 / 加载

    @Test fun `restore puts deleted todos back exactly as they were`() = runTest {
        val p = repo.create(TodoDraft("p", due = Due.At(NOW)))
        env.advance()
        val c = repo.create(TodoDraft("c", parentId = p.id))
        val before = repo.todos.value
        val gone = repo.delete(p.id)
        assertTrue(repo.todos.value.isEmpty())
        repo.restore(gone)
        assertEquals(before, repo.todos.value)
        assertEquals(setOf(p.id, c.id), env.store.rows.keys)
    }

    @Test fun `restore overwrites a changed todo (undo of a status change)`() = runTest {
        val t = repo.create(TodoDraft("t"))
        val done = repo.setStatus(t.id, TodoStatus.DONE)
        assertNotNull(done.completedAt)
        repo.restore(listOf(t))
        assertEquals(t, repo.get(t.id))
    }

    @Test fun `restoring a subtask whose parent is gone makes it top-level`() = runTest {
        val p = repo.create(TodoDraft("p"))
        val c = repo.create(TodoDraft("c", parentId = p.id))
        repo.delete(p.id)
        repo.restore(listOf(c))
        assertNull(repo.get(c.id).parentId)
    }

    @Test fun `clearAll empties the cache and the store and reports how many`() = runTest {
        repo.create(TodoDraft("a"))
        val p = repo.create(TodoDraft("b"))
        repo.create(TodoDraft("c", parentId = p.id))
        assertEquals(3, repo.clearAll())
        assertTrue(repo.todos.value.isEmpty())
        assertEquals(0, repo.storedCount())
        assertEquals(0, repo.clearAll())
    }

    @Test fun `the store is read once and reads work before any explicit load`() = runTest {
        val existing = Todo("x1", "old", "", TodoStatus.TODO, Priority.LOW, null, emptyList(), null, null, 1, 1)
        val e = TestEnv(InMemoryTodoStore(listOf(existing)))
        assertEquals(existing, e.repo.get("x1"))
        e.repo.create(TodoDraft("new"))
        assertEquals(1, e.store.loadCount)
        assertEquals(2, e.repo.todos.value.size)
    }

    @Test fun `ids are never reused while a todo holds them`() = runTest {
        var n = 0
        val ids = listOf("same", "same", "same", "other")
        val r = TodoRepository(InMemoryTodoStore(), Dispatchers.Unconfined, { NOW }, { ids[n++ % ids.size] })
        val a = r.create(TodoDraft("a"))
        val b = r.create(TodoDraft("b"))
        assertEquals("same", a.id)
        assertEquals("other", b.id)
    }

    @Test fun `a failing store leaves the cache untouched`() = runTest {
        val t = repo.create(TodoDraft("t"))
        env.store.failSaves = true
        try {
            repo.update(t.id, TodoPatch(title = "changed"))
            fail("expected the store failure to surface")
        } catch (_: IllegalStateException) {
        }
        assertEquals("t", repo.get(t.id).title)
    }
}
