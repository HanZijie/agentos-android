package org.agentos.sample.notes.data

import kotlinx.coroutines.test.runTest
import org.agentos.sample.notes.InMemoryNoteStore
import org.agentos.sample.notes.TestEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NoteRepositoryTest {
    private suspend fun expect(kind: NoteException.Kind, block: suspend () -> Unit): NoteException {
        try {
            block()
        } catch (e: NoteException) {
            assertEquals(e.message, kind, e.kind)
            return e
        }
        fail("expected NoteException($kind)")
        throw AssertionError()
    }

    @Test fun `create stores a note and publishes it`() = runTest {
        val env = TestEnv()
        val note = env.repo.create(NoteDraft(title = " Hello ", content = "Body\r\nline2", tags = listOf("#Work", "work", " ideas "), color = NoteColor.BLUE, pinned = true))
        assertEquals("n1", note.id)
        assertEquals("Hello", note.title)
        assertEquals("Body\nline2", note.content)
        assertEquals(listOf("Work", "ideas"), note.tags)
        assertEquals(NoteStatus.ACTIVE, note.status)
        assertEquals(1L, note.revision)
        assertTrue(note.pinned)
        assertEquals(listOf(note), env.repo.notes.value)
        assertEquals(note, env.store.rows["n1"])
    }

    @Test fun `create rejects empty and oversized input`() = runTest {
        val env = TestEnv()
        expect(NoteException.Kind.INVALID) { env.repo.create(NoteDraft(title = " ", content = "  ")) }
        expect(NoteException.Kind.INVALID) { env.repo.create(NoteDraft(content = "x".repeat(NoteLimits.MAX_CONTENT_CHARS + 1))) }
        expect(NoteException.Kind.INVALID) { env.repo.create(NoteDraft(title = "t".repeat(NoteLimits.MAX_TITLE_CHARS + 1), content = "a")) }
        expect(NoteException.Kind.INVALID) { env.repo.create(NoteDraft(content = "a", tags = listOf("a,b"))) }
        expect(NoteException.Kind.INVALID) { env.repo.create(NoteDraft(content = "a", tags = List(NoteLimits.MAX_TAGS + 1) { "t$it" })) }
        assertTrue(env.repo.notes.value.isEmpty())
    }

    @Test fun `title falls back to first content line`() = runTest {
        val env = TestEnv()
        val note = env.repo.create(NoteDraft(content = "\n## Weekly plan\n- a"))
        assertEquals("", note.title)
        assertEquals("Weekly plan", note.displayTitle)
    }

    @Test fun `update changes only given fields and bumps revision`() = runTest {
        val env = TestEnv()
        val n = env.repo.create(NoteDraft(title = "A", content = "one", tags = listOf("x")))
        val u = env.repo.update(n.id, NotePatch(content = "two", pinned = true))
        assertEquals("A", u.title)
        assertEquals("two", u.content)
        assertEquals(listOf("x"), u.tags)
        assertTrue(u.pinned)
        assertEquals(2L, u.revision)
        assertTrue(u.updatedAt > n.updatedAt)
        assertEquals(n.createdAt, u.createdAt)
        val t = env.repo.update(n.id, NotePatch(tags = listOf("y", "z")))
        assertEquals(listOf("y", "z"), t.tags)
        assertEquals(3L, t.revision)
    }

    @Test fun `update with identical values is a no-op`() = runTest {
        val env = TestEnv()
        val n = env.repo.create(NoteDraft(title = "A", content = "one"))
        val same = env.repo.update(n.id, NotePatch(title = "A", content = "one", pinned = false, archived = false))
        assertEquals(n, same)
        assertEquals(1L, same.revision)
    }

    @Test fun `update unknown id is not found`() = runTest {
        val env = TestEnv()
        expect(NoteException.Kind.NOT_FOUND) { env.repo.update("nope", NotePatch(title = "x")) }
        expect(NoteException.Kind.NOT_FOUND) { env.repo.get("nope") }
        expect(NoteException.Kind.NOT_FOUND) { env.repo.trash("nope") }
        expect(NoteException.Kind.NOT_FOUND) { env.repo.restore("nope") }
        expect(NoteException.Kind.NOT_FOUND) { env.repo.deletePermanently("nope") }
        expect(NoteException.Kind.NOT_FOUND) { env.repo.append("nope", "x") }
    }

    @Test fun `expected revision mismatch is a conflict that carries the current note`() = runTest {
        val env = TestEnv()
        val n = env.repo.create(NoteDraft(title = "A", content = "one"))
        env.repo.update(n.id, NotePatch(content = "changed by agent"))
        val e = expect(NoteException.Kind.CONFLICT) { env.repo.update(n.id, NotePatch(content = "mine"), expectedRevision = n.revision) }
        assertEquals("changed by agent", e.current?.content)
        assertEquals("changed by agent", env.repo.get(n.id).content)
        // 版本对得上就能写
        val ok = env.repo.update(n.id, NotePatch(content = "mine"), expectedRevision = e.current!!.revision)
        assertEquals("mine", ok.content)
    }

    @Test fun `append joins with separator and handles empty body`() = runTest {
        val env = TestEnv()
        val n = env.repo.create(NoteDraft(title = "Log", content = "a"))
        assertEquals("a\nb", env.repo.append(n.id, "b").content)
        assertEquals("a\nb\n\n- c", env.repo.append(n.id, "- c", separator = "\n\n").content)
        val empty = env.repo.create(NoteDraft(title = "Only title", content = ""))
        assertEquals("first", env.repo.append(empty.id, "first").content)
        expect(NoteException.Kind.INVALID) { env.repo.append(n.id, "") }
    }

    @Test fun `trash restore and permanent delete form a state machine`() = runTest {
        val env = TestEnv()
        val n = env.repo.create(NoteDraft(title = "A", content = "x"))

        // 活动的备忘录不能永久删除，不能 restore
        expect(NoteException.Kind.STATE) { env.repo.deletePermanently(n.id) }
        expect(NoteException.Kind.STATE) { env.repo.restore(n.id) }
        assertNotNull(env.store.rows[n.id])

        val t = env.repo.trash(n.id)
        assertEquals(NoteStatus.TRASHED, t.status)
        assertNotNull(t.trashedAt)
        assertEquals(2L, t.revision)
        // 已经在回收站里：再 trash 报错；不能改、不能追加
        expect(NoteException.Kind.STATE) { env.repo.trash(n.id) }
        expect(NoteException.Kind.STATE) { env.repo.update(n.id, NotePatch(title = "B")) }
        expect(NoteException.Kind.STATE) { env.repo.append(n.id, "more") }

        val r = env.repo.restore(n.id)
        assertEquals(NoteStatus.ACTIVE, r.status)
        assertNull(r.trashedAt)

        env.repo.trash(n.id)
        env.repo.deletePermanently(n.id)
        assertNull(env.store.rows[n.id])
        assertTrue(env.repo.notes.value.isEmpty())
        expect(NoteException.Kind.NOT_FOUND) { env.repo.get(n.id) }
    }

    @Test fun `archive and restore from archive`() = runTest {
        val env = TestEnv()
        val n = env.repo.create(NoteDraft(title = "A", content = "x"))
        val a = env.repo.update(n.id, NotePatch(archived = true))
        assertEquals(NoteStatus.ARCHIVED, a.status)
        assertTrue(env.repo.list(NoteStatus.ACTIVE).isEmpty())
        assertEquals(listOf("n1"), env.repo.list(NoteStatus.ARCHIVED).map { it.id })
        // 归档的可以直接编辑
        assertEquals("y", env.repo.update(n.id, NotePatch(content = "y")).content)
        assertEquals(NoteStatus.ACTIVE, env.repo.restore(n.id).status)
        // 归档 → 回收站 → 恢复 回到正常
        env.repo.update(n.id, NotePatch(archived = true))
        env.repo.trash(n.id)
        assertEquals(NoteStatus.ACTIVE, env.repo.restore(n.id).status)
        // 归档状态下 archived=false 取消归档；回收站里改 archived 不行
        env.repo.update(n.id, NotePatch(archived = true))
        assertEquals(NoteStatus.ACTIVE, env.repo.update(n.id, NotePatch(archived = false)).status)
        env.repo.trash(n.id)
        expect(NoteException.Kind.STATE) { env.repo.update(n.id, NotePatch(archived = false)) }
    }

    @Test fun `empty trash removes only trashed notes`() = runTest {
        val env = TestEnv()
        val keep = env.repo.create(NoteDraft(title = "keep", content = "x"))
        val a = env.repo.create(NoteDraft(title = "a", content = "x"))
        val b = env.repo.create(NoteDraft(title = "b", content = "x"))
        env.repo.trash(a.id)
        env.repo.trash(b.id)
        assertEquals(2, env.repo.emptyTrash())
        assertEquals(listOf(keep.id), env.repo.notes.value.map { it.id })
        assertEquals(listOf(keep.id), env.store.rows.keys.toList())
        assertEquals(0, env.repo.emptyTrash())
    }

    @Test fun `list orders pinned first then recent and filters`() = runTest {
        val env = TestEnv()
        val a = env.repo.create(NoteDraft(title = "a", content = "x", tags = listOf("work")))
        val b = env.repo.create(NoteDraft(title = "b", content = "x", pinned = true))
        val c = env.repo.create(NoteDraft(title = "c", content = "x", tags = listOf("Work")))
        assertEquals(listOf(b.id, c.id, a.id), env.repo.list(NoteStatus.ACTIVE).map { it.id })
        assertEquals(listOf(c.id, a.id), env.repo.list(NoteStatus.ACTIVE, tag = "#WORK").map { it.id })
        assertEquals(listOf(b.id), env.repo.list(NoteStatus.ACTIVE, pinned = true).map { it.id })
        env.repo.update(a.id, NotePatch(content = "touched"))
        assertEquals(listOf(b.id, a.id, c.id), env.repo.list(NoteStatus.ACTIVE).map { it.id })
    }

    @Test fun `trash list is ordered by deletion time`() = runTest {
        val env = TestEnv()
        val a = env.repo.create(NoteDraft(title = "a", content = "x"))
        val b = env.repo.create(NoteDraft(title = "b", content = "x"))
        env.repo.trash(b.id)
        env.repo.trash(a.id)
        assertEquals(listOf(a.id, b.id), env.repo.list(NoteStatus.TRASHED).map { it.id })
    }

    @Test fun `tags count non trashed notes`() = runTest {
        val env = TestEnv()
        env.repo.create(NoteDraft(title = "1", content = "x", tags = listOf("a", "b")))
        env.repo.create(NoteDraft(title = "2", content = "x", tags = listOf("A")))
        val gone = env.repo.create(NoteDraft(title = "3", content = "x", tags = listOf("b", "c")))
        env.repo.trash(gone.id)
        // 数量不分大小写合并；显示的写法取最近更新的那条备忘录里的（这里是 "A"）
        assertEquals(listOf(TagCount("A", 2), TagCount("b", 1)), env.repo.tags())
    }

    @Test fun `loads once from the store and publishes loaded`() = runTest {
        val seed = Note("s1", "Seed", "c", emptyList(), NoteColor.DEFAULT, false, NoteStatus.ACTIVE, 1, 2, null, 7)
        val env = TestEnv(InMemoryNoteStore(listOf(seed)))
        assertFalse(env.repo.loaded.value)
        assertEquals(seed, env.repo.get("s1"))
        assertTrue(env.repo.loaded.value)
        env.repo.load()
        env.repo.create(NoteDraft(title = "x", content = "y"))
        assertEquals(1, env.store.loadCount)
        assertEquals(2, env.repo.notes.value.size)
    }

    @Test fun `a failing store leaves the in-memory state untouched`() = runTest {
        val env = TestEnv()
        val n = env.repo.create(NoteDraft(title = "A", content = "x"))
        env.store.failSaves = true
        try {
            env.repo.update(n.id, NotePatch(content = "y"))
            fail("expected store failure")
        } catch (_: IllegalStateException) {
        }
        assertEquals("x", env.repo.notes.value.single().content)
        assertEquals("x", env.store.rows[n.id]!!.content)
    }
}
