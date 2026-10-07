package org.agentos.sample.notes.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownEditTest {
    @Test fun `wrap selection then unwrap`() {
        val wrapped = MarkdownEdit.wrap("say hello now", 4, 9, "**", "bold")
        assertEquals(EditResult("say **hello** now", 6, 11), wrapped)
        val back = MarkdownEdit.wrap(wrapped.text, wrapped.selStart, wrapped.selEnd, "**", "bold")
        assertEquals(EditResult("say hello now", 4, 9), back)
    }

    @Test fun `wrap with empty selection inserts a placeholder`() {
        assertEquals(EditResult("a `code` b", 3, 7), MarkdownEdit.wrap("a  b", 2, 2, "`", "code"))
    }

    @Test fun `italic is not unwrapped from inside bold`() {
        val r = MarkdownEdit.wrap("**word**", 2, 6, "*", "x")
        assertEquals("***word***", r.text)
    }

    @Test fun `selection that already carries markers is unwrapped`() {
        assertEquals(EditResult("plain", 0, 5), MarkdownEdit.wrap("~~plain~~", 0, 9, "~~", "x"))
    }

    @Test fun `line prefix toggles on and off for every selected line`() {
        val on = MarkdownEdit.toggleLine("a\nb\nc", 0, 5, LineKind.BULLET)
        assertEquals("- a\n- b\n- c", on.text)
        assertEquals("a\nb\nc", MarkdownEdit.toggleLine(on.text, on.selStart, on.selEnd, LineKind.BULLET).text)
    }

    @Test fun `line prefix replaces another list or heading marker`() {
        assertEquals("1. item", MarkdownEdit.toggleLine("- item", 2, 2, LineKind.NUMBERED).text)
        assertEquals("- [ ] item", MarkdownEdit.toggleLine("## item", 0, 0, LineKind.TASK).text)
        assertEquals("## item", MarkdownEdit.toggleLine("> item", 0, 0, LineKind.HEADING).text)
        assertEquals("1. a\n2. b", MarkdownEdit.toggleLine("a\nb", 0, 3, LineKind.NUMBERED).text)
    }

    @Test fun `only the touched lines change`() {
        val r = MarkdownEdit.toggleLine("one\ntwo\nthree", 5, 5, LineKind.QUOTE)
        assertEquals("one\n> two\nthree", r.text)
    }

    @Test fun `code block wraps selection or inserts an empty block`() {
        assertEquals(EditResult("```\n\n```", 4, 4), MarkdownEdit.codeBlock("", 0, 0))
        val sel = MarkdownEdit.codeBlock("run x=1 now", 4, 7)
        assertEquals("run \n```\nx=1\n```\n now", sel.text)
    }

    @Test fun `link selects the url placeholder`() {
        val r = MarkdownEdit.link("see docs here", 4, 8)
        assertEquals("see [docs](https://) here", r.text)
        assertEquals("https://", r.text.substring(r.selStart, r.selEnd))
    }

    @Test fun `rule is surrounded by blank lines`() {
        assertEquals("a\n\n---\n\nb", MarkdownEdit.rule("ab", 1, 1).text)
        assertEquals("---\n\n", MarkdownEdit.rule("", 0, 0).text)
    }

    @Test fun `enter continues bullets numbers tasks and quotes`() {
        fun enter(old: String, caret: Int) = MarkdownEdit.onEnter(old, caret, caret, old.substring(0, caret) + "\n" + old.substring(caret), caret + 1)
        assertEquals(EditResult("- a\n- ", 6, 6), enter("- a", 3))
        assertEquals(EditResult("9. a\n10. ", 9, 9), enter("9. a", 4))
        assertEquals(EditResult("- [x] a\n- [ ] ", 14, 14), enter("- [x] a", 7))
        assertEquals(EditResult("> q\n> ", 6, 6), enter("> q", 3))
        assertEquals(EditResult("  - n\n  - ", 10, 10), enter("  - n", 5))
    }

    @Test fun `enter on an empty item ends the list`() {
        assertEquals(EditResult("- a\n", 4, 4), MarkdownEdit.onEnter("- a\n- ", 6, 6, "- a\n- \n", 7))
    }

    @Test fun `enter elsewhere is left alone`() {
        assertNull(MarkdownEdit.onEnter("plain", 5, 5, "plain\n", 6))
        assertNull(MarkdownEdit.onEnter("- a", 3, 3, "- ab", 4))
        assertNull(MarkdownEdit.onEnter("- a", 0, 3, "\n", 1))
    }
}
