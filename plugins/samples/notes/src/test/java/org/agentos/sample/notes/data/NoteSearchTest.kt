package org.agentos.sample.notes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSearchTest {
    private fun note(
        id: String,
        title: String = "",
        content: String = "",
        tags: List<String> = emptyList(),
        status: NoteStatus = NoteStatus.ACTIVE,
        updated: Long = 1,
        pinned: Boolean = false,
    ) = Note(id, title, content, tags, NoteColor.DEFAULT, pinned, status, 0, updated, null, 1)

    @Test fun `matches title body and tags case-insensitively`() {
        val notes = listOf(
            note("t", title = "Grocery LIST"),
            note("b", title = "x", content = "remember the grocery run"),
            note("g", title = "y", content = "nothing", tags = listOf("Grocery")),
            note("n", title = "z", content = "unrelated"),
        )
        val hits = NoteSearch.search(notes, "grocery")
        assertEquals(setOf("t", "b", "g"), hits.map { it.note.id }.toSet())
        assertEquals(setOf(MatchField.TITLE), hits.first { it.note.id == "t" }.matchedIn)
        assertEquals(setOf(MatchField.CONTENT), hits.first { it.note.id == "b" }.matchedIn)
        assertEquals(setOf(MatchField.TAG), hits.first { it.note.id == "g" }.matchedIn)
    }

    @Test fun `title matches rank above body matches`() {
        val notes = listOf(
            note("body", title = "other", content = "alpha", updated = 9),
            note("title", title = "alpha", content = "x", updated = 1),
        )
        assertEquals(listOf("title", "body"), NoteSearch.search(notes, "alpha").map { it.note.id })
    }

    @Test fun `several words all have to match anywhere`() {
        val notes = listOf(
            note("1", title = "Trip", content = "book flights to Tokyo"),
            note("2", title = "Trip", content = "book hotel"),
            note("3", title = "Tokyo", content = "flights", tags = listOf("Trip")),
        )
        assertEquals(setOf("1", "3"), NoteSearch.search(notes, "tokyo  flights").map { it.note.id }.toSet())
        assertTrue(NoteSearch.search(notes, "tokyo hotel").isEmpty())
    }

    @Test fun `blank query matches nothing`() {
        assertTrue(NoteSearch.search(listOf(note("1", "a", "b")), "   ").isEmpty())
    }

    @Test fun `trash is excluded unless asked for and tag filter applies`() {
        val notes = listOf(
            note("live", content = "needle", tags = listOf("a")),
            note("arch", content = "needle", status = NoteStatus.ARCHIVED),
            note("bin", content = "needle", status = NoteStatus.TRASHED),
        )
        assertEquals(setOf("live", "arch"), NoteSearch.search(notes, "needle").map { it.note.id }.toSet())
        assertEquals(setOf("live", "arch", "bin"), NoteSearch.search(notes, "needle", includeTrashed = true).map { it.note.id }.toSet())
        assertEquals(listOf("live"), NoteSearch.search(notes, "needle", tag = "#A").map { it.note.id })
    }

    @Test fun `snippet is centered on the match with ellipses and highlight ranges`() {
        val text = "a".repeat(100) + " the needle is here " + "b".repeat(200)
        val hit = NoteSearch.search(listOf(note("1", content = text)), "needle").single()
        val s = hit.snippet
        assertTrue(s.text.startsWith("…"))
        assertTrue(s.text.endsWith("…"))
        assertTrue(s.text.length < 140)
        assertEquals(1, s.ranges.size)
        assertEquals("needle", s.text.substring(s.ranges[0].first, s.ranges[0].last + 1))
    }

    @Test fun `snippet at the start has no leading ellipsis and flattens newlines`() {
        val hit = NoteSearch.search(listOf(note("1", content = "line one\nneedle\tline")), "needle").single()
        assertEquals("line one needle line", hit.snippet.text)
        assertEquals("needle", hit.snippet.text.substring(hit.snippet.ranges[0].first, hit.snippet.ranges[0].last + 1))
    }

    @Test fun `snippet ranges are merged when terms overlap or touch`() {
        val hit = NoteSearch.search(listOf(note("1", content = "foobar baz")), "foo bar").single()
        assertEquals(listOf("foobar"), hit.snippet.ranges.map { hit.snippet.text.substring(it.first, it.last + 1) })
    }

    @Test fun `snippet ranges cover every term`() {
        val hit = NoteSearch.search(listOf(note("1", content = "Kotlin coroutines and kotlin flow")), "kotlin flow").single()
        val marked = hit.snippet.ranges.map { hit.snippet.text.substring(it.first, it.last + 1) }
        assertEquals(listOf("Kotlin", "kotlin", "flow"), marked)
    }

    @Test fun `title only match still gives the beginning of the body as snippet without highlights`() {
        val hit = NoteSearch.search(listOf(note("1", title = "Budget", content = "Rent 1200\nFood 400")), "budget").single()
        assertEquals("Rent 1200 Food 400", hit.snippet.text)
        assertTrue(hit.snippet.ranges.isEmpty())
        assertEquals("Budget", hit.note.displayTitle.substring(hit.titleRanges[0].first, hit.titleRanges[0].last + 1))
    }

    @Test fun `snippet does not split surrogate pairs`() {
        val emoji = "😀"
        val text = emoji.repeat(60) + "needle" + emoji.repeat(60)
        val s = NoteSearch.search(listOf(note("1", content = text)), "needle").single().snippet
        val body = s.text.removePrefix("…").removeSuffix("…")
        assertTrue(Character.isHighSurrogate(body.first()))
        assertTrue(Character.isLowSurrogate(body.last()))
        assertEquals("needle", s.text.substring(s.ranges[0].first, s.ranges[0].last + 1))
    }

    @Test fun `chinese text is searched without word segmentation`() {
        val hit = NoteSearch.search(listOf(note("1", title = "周报", content = "本周完成了备忘录的搜索高亮")), "搜索").single()
        assertEquals("搜索", hit.snippet.text.substring(hit.snippet.ranges[0].first, hit.snippet.ranges[0].last + 1))
        assertNull(NoteSearch.search(listOf(note("1", title = "周报", content = "abc")), "搜索").firstOrNull())
    }
}
