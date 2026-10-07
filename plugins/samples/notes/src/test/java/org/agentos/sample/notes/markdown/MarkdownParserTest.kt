package org.agentos.sample.notes.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    private fun blocks(src: String) = MarkdownParser.parse(src)
    private fun inl(src: String) = MarkdownParser.parseInlines(src)
    private fun t(s: String) = Inline.Text(s)

    @Test fun `headings levels and not a heading without space`() {
        val b = blocks("# One\n###### Six\n#tag\n####### seven")
        assertEquals(Block.Heading(1, listOf(t("One"))), b[0])
        assertEquals(Block.Heading(6, listOf(t("Six"))), b[1])
        assertTrue(b[2] is Block.Paragraph)
        assertEquals("#tag\n####### seven", MarkdownParser.inlinesText((b[2] as Block.Paragraph).inlines))
    }

    @Test fun `closing hashes are stripped`() {
        assertEquals(Block.Heading(2, listOf(t("Title"))), blocks("## Title ##").single())
    }

    @Test fun `paragraph keeps single newlines as line breaks`() {
        val p = blocks("line one\nline two\n\nsecond").map { (it as Block.Paragraph).inlines }
        assertEquals(listOf(t("line one\nline two")), p[0])
        assertEquals(listOf(t("second")), p[1])
    }

    @Test fun `bold italic strike and code`() {
        assertEquals(listOf(Inline.Bold(listOf(t("b")))), inl("**b**"))
        assertEquals(listOf(Inline.Bold(listOf(t("b")))), inl("__b__"))
        assertEquals(listOf(Inline.Italic(listOf(t("i")))), inl("*i*"))
        assertEquals(listOf(Inline.Italic(listOf(t("i")))), inl("_i_"))
        assertEquals(listOf(Inline.Bold(listOf(Inline.Italic(listOf(t("both")))))), inl("***both***"))
        assertEquals(listOf(Inline.Strike(listOf(t("gone")))), inl("~~gone~~"))
        assertEquals(listOf(t("run "), Inline.Code("a*b*c"), t(" now")), inl("run `a*b*c` now"))
    }

    @Test fun `nested emphasis and mixed text`() {
        assertEquals(
            listOf(t("a "), Inline.Bold(listOf(t("b "), Inline.Italic(listOf(t("c"))), t(" d"))), t(" e")),
            inl("a **b *c* d** e"),
        )
    }

    @Test fun `snake case and unmatched markers stay literal`() {
        assertEquals(listOf(t("snake_case_name")), inl("snake_case_name"))
        assertEquals("2 * 3 = 6", MarkdownParser.inlinesText(inl("2 * 3 = 6")))
        assertEquals("**open", MarkdownParser.inlinesText(inl("**open")))
        assertEquals("a*b", MarkdownParser.inlinesText(inl("a\\*b")))
    }

    @Test fun `links autolinks and unsafe schemes`() {
        assertEquals(listOf(Inline.Link(listOf(t("site")), "https://example.com/a?b=1")), inl("[site](https://example.com/a?b=1)"))
        assertEquals(listOf(t("see "), Inline.Link(listOf(t("https://a.org/x")), "https://a.org/x"), t(".")), inl("see https://a.org/x."))
        assertEquals(listOf(Inline.Link(listOf(t("https://a.org")), "https://a.org")), inl("<https://a.org>"))
        // javascript: 之类不生成链接，只留文字
        assertEquals(listOf(t("click")), inl("[click](javascript:alert(1))"))
        assertEquals(listOf(Inline.Link(listOf(t("pic")), "https://i.org/p.png")), inl("![pic](https://i.org/p.png)"))
        assertEquals("[no url]", MarkdownParser.inlinesText(inl("[no url]")))
    }

    @Test fun `fenced code block keeps content verbatim and handles missing close`() {
        val b = blocks("```kotlin\nval a = 1 // **not bold**\n\n    indented\n```\nafter")
        assertEquals(Block.CodeBlock("kotlin", "val a = 1 // **not bold**\n\n    indented"), b[0])
        assertTrue(b[1] is Block.Paragraph)
        assertEquals(Block.CodeBlock("", "open\nforever"), blocks("~~~\nopen\nforever").single())
    }

    @Test fun `quote with nested blocks`() {
        val q = blocks("> # Title\n> text\n>\n> - item").single() as Block.Quote
        assertEquals(Block.Heading(1, listOf(t("Title"))), q.blocks[0])
        assertTrue(q.blocks[1] is Block.Paragraph)
        assertTrue(q.blocks[2] is Block.ListBlock)
    }

    @Test fun `thematic breaks`() {
        assertEquals(listOf(Block.Rule, Block.Rule, Block.Rule), blocks("---\n***\n___"))
        assertEquals(Block.Rule, blocks("- - -").single())
    }

    @Test fun `bullet and ordered lists`() {
        val b = blocks("- a\n- b\n* c\n\n3. x\n4. y")
        val bullets = b[0] as Block.ListBlock
        assertEquals(false, bullets.ordered)
        assertEquals(3, bullets.items.size)
        val ordered = b[1] as Block.ListBlock
        assertEquals(true, ordered.ordered)
        assertEquals(3, ordered.start)
        assertEquals(2, ordered.items.size)
    }

    @Test fun `task items record checked state and their source line`() {
        val src = "intro\n- [ ] todo\n- [x] done\n- [X] also\n- plain"
        val list = blocks(src)[1] as Block.ListBlock
        assertEquals(listOf(false, true, true, null), list.items.map { it.checked })
        assertEquals(listOf(1, 2, 3, 4), list.items.map { it.line })
        assertEquals(listOf(t("todo")), ((list.items[0].blocks.single()) as Block.Paragraph).inlines)
    }

    @Test fun `nested lists by indentation`() {
        val list = blocks("- a\n  - a1\n  - a2\n    - deep\n- b").single() as Block.ListBlock
        assertEquals(2, list.items.size)
        val inner = list.items[0].blocks[1] as Block.ListBlock
        assertEquals(2, inner.items.size)
        assertTrue(inner.items[1].blocks[1] is Block.ListBlock)
        assertEquals(listOf(1, 2), (list.items[0].blocks[1] as Block.ListBlock).items.map { it.line })
    }

    @Test fun `task line numbers survive nesting inside quotes and lists`() {
        val src = "> - [ ] q\n- a\n  - [x] nested"
        val q = blocks(src)[0] as Block.Quote
        assertEquals(0, (q.blocks.single() as Block.ListBlock).items.single().line)
        val nested = ((blocks(src)[1] as Block.ListBlock).items.single().blocks[1] as Block.ListBlock).items.single()
        assertEquals(2, nested.line)
        assertEquals(true, nested.checked)
    }

    @Test fun `a list can interrupt a paragraph but a year number cannot`() {
        val b = blocks("intro\n- a\n- b")
        assertTrue(b[0] is Block.Paragraph && b[1] is Block.ListBlock)
        val c = blocks("In\n2024. it happened")
        assertEquals(1, c.size)
    }

    @Test fun `toggle task flips the checkbox on the given line only`() {
        val src = "a\n- [ ] one\n- [x] two\n1. [ ] three\n- plain"
        assertEquals("a\n- [x] one\n- [x] two\n1. [ ] three\n- plain", MarkdownParser.toggleTask(src, 1))
        assertEquals("a\n- [ ] one\n- [ ] two\n1. [ ] three\n- plain", MarkdownParser.toggleTask(src, 2))
        assertEquals("a\n- [ ] one\n- [x] two\n1. [x] three\n- plain", MarkdownParser.toggleTask(src, 3))
        assertNull(MarkdownParser.toggleTask(src, 4))
        assertNull(MarkdownParser.toggleTask(src, 99))
    }

    @Test fun `crlf and tabs are handled`() {
        val list = blocks("- a\r\n\t- b\r\n").single() as Block.ListBlock
        assertEquals(1, list.items.size)
        assertTrue(list.items[0].blocks[1] is Block.ListBlock)
    }

    @Test fun `plain text strips markup for card summaries`() {
        val src = "# Title\nSome **bold** and [link](https://a.org).\n- [x] done\n- open\n> quote\n```\ncode here\n```"
        assertEquals("Title Some bold and link. ☑ done • open quote code here", MarkdownParser.plainText(src, 200))
        assertEquals("Title Some…", MarkdownParser.plainText(src, 10))
        assertEquals("", MarkdownParser.plainText("---\n\n"))
    }

    @Test fun `empty and whitespace input`() {
        assertTrue(blocks("").isEmpty())
        assertTrue(blocks("  \n\n \t").isEmpty())
    }

    @Test fun `pathological input does not blow up`() {
        val src = "*".repeat(5000) + "\n" + "[".repeat(2000) + "\n" + "`".repeat(3001) + "\n" + "_a ".repeat(3000)
        assertTrue(blocks(src).isNotEmpty())
        // 超长的分隔线 / 深层嵌套 / 超长单行都只能慢一点，不能栈溢出
        assertEquals(Block.Rule, blocks("-".repeat(50_000)).single())
        assertTrue(blocks("> ".repeat(5000) + "x").isNotEmpty())
        assertTrue(blocks((0 until 3000).joinToString("\n") { " ".repeat(it * 2) + "- item" }).isNotEmpty())
        assertTrue(blocks("*a ".repeat(60_000)).isNotEmpty())
    }
}
