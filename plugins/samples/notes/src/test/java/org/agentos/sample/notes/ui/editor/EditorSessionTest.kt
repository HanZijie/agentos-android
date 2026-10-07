package org.agentos.sample.notes.ui.editor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.agentos.sample.notes.TestEnv
import org.agentos.sample.notes.data.NoteColor
import org.agentos.sample.notes.data.NoteDraft
import org.agentos.sample.notes.data.NotePatch
import org.agentos.sample.notes.data.NoteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 编辑会话：自动保存、外部（MCP）修改的同步与冲突，全程虚拟时间。 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorSessionTest {
    private val env = TestEnv()
    private val repo get() = env.repo

    private fun TestScope.session(id: String?) = EditorSession(repo, backgroundScope, id, debounceMs = 100).also { runCurrent() }

    private suspend fun TestScope.settle() { advanceTimeBy(150); runCurrent() }

    @Test fun `a new blank note is never created`() = runTest {
        val s = session(null)
        s.setContent("   ")
        settle()
        assertTrue(repo.notes.value.isEmpty())
        assertEquals(EditorSession.SaveState.CLEAN, s.state.value.save)
    }

    @Test fun `typing creates the note after the debounce and keeps updating it`() = runTest {
        val s = session(null)
        s.setContent("hello")
        advanceTimeBy(50)
        assertTrue("not saved before the debounce", repo.notes.value.isEmpty())
        assertEquals(EditorSession.SaveState.DIRTY, s.state.value.save)
        settle()
        val note = repo.notes.value.single()
        assertEquals("hello", note.content)
        assertEquals(note.id, s.state.value.noteId)
        assertEquals(EditorSession.SaveState.CLEAN, s.state.value.save)

        s.setTitle("T")
        s.setTags(listOf("a"))
        s.setColor(NoteColor.RED)
        s.setPinned(true)
        settle()
        val saved = repo.get(note.id)
        assertEquals("T", saved.title)
        assertEquals(listOf("a"), saved.tags)
        assertEquals(NoteColor.RED, saved.color)
        assertTrue(saved.pinned)
        assertEquals(2, repo.notes.value.single().revision.toInt())
        assertNull(s.state.value.conflict)
        assertEquals("our own saves must not look like outside changes", 0, s.state.value.syncedCount)
    }

    @Test fun `flush saves immediately`() = runTest {
        val n = repo.create(NoteDraft(title = "A", content = "one"))
        val s = session(n.id)
        s.setContent("two")
        s.flush()
        assertEquals("two", repo.get(n.id).content)
    }

    @Test fun `an outside change is synced silently when there is nothing unsaved`() = runTest {
        val n = repo.create(NoteDraft(title = "A", content = "one"))
        val s = session(n.id)
        val before = s.state.value.contentVersion
        repo.update(n.id, NotePatch(content = "changed by agent", tags = listOf("x")))
        runCurrent()
        val st = s.state.value
        assertEquals("changed by agent", st.content)
        assertEquals(listOf("x"), st.tags)
        assertEquals(1, st.syncedCount)
        assertEquals(before + 1, st.contentVersion)
        assertNull(st.conflict)
        assertEquals(EditorSession.SaveState.CLEAN, st.save)
    }

    @Test fun `an outside change while editing raises a conflict and autosave never overwrites it`() = runTest {
        val n = repo.create(NoteDraft(title = "A", content = "one"))
        val s = session(n.id)
        s.setContent("my edit")
        repo.update(n.id, NotePatch(content = "agent edit"))
        runCurrent()
        val conflict = s.state.value.conflict as EditorSession.Conflict.Updated
        assertEquals("agent edit", conflict.remote.content)
        assertEquals("my edit", s.state.value.content)
        settle()
        advanceUntilIdle()
        assertEquals("autosave must stay paused", "agent edit", repo.get(n.id).content)
    }

    @Test fun `conflict detected on save when the outside change lands first`() = runTest {
        val n = repo.create(NoteDraft(title = "A", content = "one"))
        val s = session(n.id)
        s.setContent("my edit")
        // 外部写入发生在会话来得及 reconcile 之前（collector 还没跑）：保存时由 expectedRevision 兜住
        repo.update(n.id, NotePatch(content = "agent edit"))
        s.flush()
        assertTrue(s.state.value.conflict is EditorSession.Conflict.Updated)
        assertEquals("agent edit", repo.get(n.id).content)
    }

    @Test fun `keep mine overwrites and resumes autosave`() = runTest {
        val n = repo.create(NoteDraft(title = "A", content = "one"))
        val s = session(n.id)
        s.setContent("my edit")
        repo.update(n.id, NotePatch(content = "agent edit"))
        runCurrent()
        s.keepMine()
        runCurrent()
        assertEquals("my edit", repo.get(n.id).content)
        assertNull(s.state.value.conflict)
        s.setContent("my edit 2")
        settle()
        assertEquals("my edit 2", repo.get(n.id).content)
    }

    @Test fun `take theirs drops local edits`() = runTest {
        val n = repo.create(NoteDraft(title = "A", content = "one"))
        val s = session(n.id)
        s.setContent("my edit")
        repo.update(n.id, NotePatch(content = "agent edit"))
        runCurrent()
        val version = s.state.value.contentVersion
        s.takeTheirs()
        val st = s.state.value
        assertEquals("agent edit", st.content)
        assertEquals(version + 1, st.contentVersion)
        assertNull(st.conflict)
        assertFalse(s.isDirty)
        settle()
        assertEquals("agent edit", repo.get(n.id).content)
    }

    @Test fun `save as copy keeps both versions`() = runTest {
        val n = repo.create(NoteDraft(title = "Plan", content = "one", tags = listOf("t")))
        val s = session(n.id)
        s.setContent("my edit")
        repo.update(n.id, NotePatch(content = "agent edit"))
        runCurrent()
        val copy = s.saveAsCopy(" (copy)")
        runCurrent()
        assertNotNull(copy)
        assertEquals("Plan (copy)", copy!!.title)
        assertEquals("my edit", copy.content)
        assertEquals(listOf("t"), copy.tags)
        assertEquals("agent edit", s.state.value.content)
        assertEquals("agent edit", repo.get(n.id).content)
        assertNull(s.state.value.conflict)
        assertEquals(2, repo.notes.value.size)
    }

    @Test fun `an outside change equal to the local text is not a conflict`() = runTest {
        val n = repo.create(NoteDraft(title = "A", content = "one"))
        val s = session(n.id)
        s.setContent("same")
        repo.update(n.id, NotePatch(content = "same"))
        runCurrent()
        assertNull(s.state.value.conflict)
        assertFalse(s.isDirty)
    }

    @Test fun `trashed from outside makes the note read-only until restored`() = runTest {
        val n = repo.create(NoteDraft(title = "A", content = "one"))
        val s = session(n.id)
        repo.trash(n.id)
        runCurrent()
        assertTrue(s.state.value.readOnly)
        s.setContent("ignored")
        assertEquals("one", s.state.value.content)
        val restored = s.restore()
        runCurrent()
        assertEquals(NoteStatus.ACTIVE, restored!!.status)
        assertFalse(s.state.value.readOnly)
        s.setContent("two")
        settle()
        assertEquals("two", repo.get(n.id).content)
    }

    @Test fun `unsaved text survives being trashed from outside and is saved after restore`() = runTest {
        val n = repo.create(NoteDraft(title = "A", content = "one"))
        val s = session(n.id)
        s.setContent("my edit")
        repo.trash(n.id)
        runCurrent()
        assertTrue(s.state.value.readOnly)
        assertEquals("my edit", s.state.value.content)
        settle()
        assertEquals("nothing is written while in the trash", "one", repo.get(n.id).content)
        s.restore()
        settle()
        assertEquals("my edit", repo.get(n.id).content)
        assertNull(s.state.value.conflict)
    }

    @Test fun `permanent delete from outside offers saving as a new note`() = runTest {
        val n = repo.create(NoteDraft(title = "A", content = "one"))
        val s = session(n.id)
        s.setContent("my edit")
        repo.trash(n.id)
        repo.deletePermanently(n.id)
        runCurrent()
        assertTrue(s.state.value.conflict is EditorSession.Conflict.Deleted)
        settle()
        assertTrue("autosave must not resurrect it", repo.notes.value.isEmpty())
        val created = s.saveAsNew()
        assertEquals("my edit", created!!.content)
        assertNull(s.state.value.conflict)
        assertEquals(created.id, s.state.value.noteId)
        s.setContent("more")
        settle()
        assertEquals("more", repo.get(created.id).content)
    }

    @Test fun `opening an unknown note reports deleted`() = runTest {
        val s = session("ghost")
        assertTrue(s.state.value.conflict is EditorSession.Conflict.Deleted)
    }

    @Test fun `trashed notes open read-only`() = runTest {
        val n = repo.create(NoteDraft(title = "A", content = "one"))
        repo.trash(n.id)
        val s = session(n.id)
        assertTrue(s.state.value.readOnly)
        s.setTitle("x")
        settle()
        assertEquals("A", repo.get(n.id).title)
    }

    @Test fun `toggling a task in the preview edits the source line`() = runTest {
        val n = repo.create(NoteDraft(title = "A", content = "- [ ] one\n- [x] two"))
        val s = session(n.id)
        val version = s.state.value.contentVersion
        s.toggleTask(0)
        assertEquals("- [x] one\n- [x] two", s.state.value.content)
        assertEquals(version + 1, s.state.value.contentVersion)
        settle()
        assertEquals("- [x] one\n- [x] two", repo.get(n.id).content)
        s.toggleTask(7)
        assertEquals("- [x] one\n- [x] two", s.state.value.content)
    }

    @Test fun `a failing store shows an error and retries on the next edit`() = runTest {
        val n = repo.create(NoteDraft(title = "A", content = "one"))
        val s = session(n.id)
        env.store.failSaves = true
        s.setContent("two")
        settle()
        assertEquals(EditorSession.SaveState.ERROR, s.state.value.save)
        env.store.failSaves = false
        s.setContent("three")
        settle()
        assertEquals(EditorSession.SaveState.CLEAN, s.state.value.save)
        assertEquals("three", repo.get(n.id).content)
    }

    @Test fun `close flushes pending edits`() = runTest {
        val n = repo.create(NoteDraft(title = "A", content = "one"))
        val s = session(n.id)
        s.setContent("two")
        s.close()
        runCurrent()
        assertEquals("two", repo.get(n.id).content)
    }
}
