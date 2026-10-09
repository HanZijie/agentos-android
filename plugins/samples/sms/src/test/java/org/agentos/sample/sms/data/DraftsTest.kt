package org.agentos.sample.sms.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DraftsTest {
    private var now = 1_000L
    private val store = InMemoryDraftStore()
    private val drafts = Drafts(store) { now }

    @Test fun `drafts are kept newest first with increasing ids`() {
        drafts.add("1380013800", "a")
        now += 1_000
        val b = drafts.add("1390013900", null)
        assertEquals("2", b.id)
        assertEquals(listOf("2", "1"), drafts.recent.value.map { it.id })
        assertNull(drafts.recent.value.first().text)
        assertEquals(2, store.load().size) // persisted through the store
    }

    @Test fun `the same recipient and text is kept once, as the newest`() {
        drafts.add("1380013800", "same")
        now += 1_000
        drafts.add("1380013800", "same")
        assertEquals(1, drafts.count())
        assertEquals(2_000, drafts.recent.value.single().createdAt)
    }

    @Test fun `only the latest few are kept`() {
        repeat(Drafts.MAX + 3) { drafts.add("138001380$it", "t$it") }
        assertEquals(Drafts.MAX, drafts.count())
        assertEquals("t${Drafts.MAX + 2}", drafts.recent.value.first().text)
    }

    @Test fun `dismissing one and clearing all`() {
        val a = drafts.add("1380013800", "a")
        drafts.add("1390013900", "b")
        drafts.remove(a.id)
        assertEquals(listOf("b"), drafts.recent.value.map { it.text })
        assertEquals(1, drafts.clear())
        assertEquals(0, drafts.count())
        assertEquals(0, store.load().size)
    }

    @Test fun `a new Drafts object reloads what the store holds`() {
        drafts.add("1380013800", "kept")
        assertEquals(listOf("kept"), Drafts(store) { now }.recent.value.map { it.text })
    }
}
