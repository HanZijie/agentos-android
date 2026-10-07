package org.agentos.sample.notes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTextTest {
    @Test fun `derive title strips markdown prefixes and skips blank lines`() {
        assertEquals("Weekly plan", NoteText.deriveTitle("\n\n## Weekly plan\nbody"))
        assertEquals("buy milk", NoteText.deriveTitle("- [ ] buy milk"))
        assertEquals("quoted", NoteText.deriveTitle("> quoted"))
        assertEquals("first", NoteText.deriveTitle("1. first"))
        assertEquals("", NoteText.deriveTitle("   \n "))
        assertEquals(80, NoteText.deriveTitle("x".repeat(300)).length)
        // 病态长行不能让前缀正则栈溢出
        assertEquals(true, NoteText.deriveTitle("> ".repeat(100_000)).length <= 80)
    }

    @Test fun `summary collapses whitespace and is capped at 200 with an ellipsis`() {
        assertEquals("a b c", NoteText.summary("a\n\n b\t c"))
        val long = "字".repeat(500)
        val s = NoteText.summary(long)
        assertEquals(200, s.length)
        assertTrue(s.endsWith("…"))
        assertEquals("x".repeat(200), NoteText.summary("x".repeat(200)))
    }

    @Test fun `summary does not cut an emoji in half`() {
        val s = NoteText.summary("😀".repeat(300), 11)
        assertTrue(s.endsWith("…"))
        assertFalse(Character.isHighSurrogate(s.dropLast(1).last()))
    }

    @Test fun `tags are trimmed deduplicated and validated`() {
        assertEquals(listOf("a", "B"), NoteText.normalizeTags(listOf(" #a ", "A", "", "B")))
        for (bad in listOf(listOf("x,y"), listOf("x\ny"), listOf("t".repeat(33)))) {
            try {
                NoteText.normalizeTags(bad)
                throw AssertionError("expected failure for $bad")
            } catch (e: NoteException) {
                assertEquals(NoteException.Kind.INVALID, e.kind)
            }
        }
    }
}
