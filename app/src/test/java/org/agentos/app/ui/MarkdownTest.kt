package org.agentos.app.ui

import org.agentos.app.ui.Markdown.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Markdown subset of agent replies (pure logic; the Spannable mapping is in MarkdownSpans). */
class MarkdownTest {
    private fun r(src: String, streaming: Boolean = false) = Markdown.render(src, streaming)

    private fun Markdown.Rendered.texts(kind: Kind) = spans(kind).map { textOf(it) }

    @Test
    fun headingsAndInlineStyles() {
        val m = r("# Title\n###### Six\n####### seven\nplain **bold** *it* _it2_ ~~gone~~ `code` ***both***")
        assertEquals("Title\nSix\n####### seven\nplain bold it it2 gone code both", m.text)
        assertEquals(listOf(1, 6), m.spans(Kind.HEADING).map { it.level })
        assertEquals(listOf("Title", "Six"), m.texts(Kind.HEADING))
        assertEquals(listOf("bold", "both"), m.texts(Kind.BOLD))
        assertEquals(listOf("it", "it2", "both"), m.texts(Kind.ITALIC))
        assertEquals(listOf("gone"), m.texts(Kind.STRIKE))
        assertEquals(listOf("code"), m.texts(Kind.CODE))
    }

    @Test
    fun nestingEscapesAndLiteralMarkers() {
        val m = r("**bold _and italic_** and \\*not\\* a * b * c snake_case_name 5*2")
        assertEquals("bold and italic and *not* a * b * c snake_case_name 5*2", m.text)
        assertEquals(listOf("bold and italic"), m.texts(Kind.BOLD))
        assertEquals(listOf("and italic"), m.texts(Kind.ITALIC))
        // CJK: closing after punctuation still closes
        assertEquals(listOf("重点："), r("这是**重点：**后面").texts(Kind.BOLD))
        // inline code keeps markers
        assertEquals(listOf("a **b** c"), r("x `a **b** c` y").texts(Kind.CODE))
        assertEquals(listOf("a`b"), r("``a`b``").texts(Kind.CODE))
    }

    @Test
    fun fencedCodeBlocksAreVerbatim() {
        val m = r("Here:\n```kotlin\nval x = **not bold**\n\n  indented\n```\nafter")
        assertEquals("Here:\nval x = **not bold**\n\n  indented\nafter", m.text)
        assertEquals(listOf("val x = **not bold**\n\n  indented"), m.texts(Kind.CODE_BLOCK))
        assertTrue(m.spans(Kind.BOLD).isEmpty())
        // indented fence inside a list item: the indentation is dropped
        val inList = r("1. Step\n   ```sh\n   ls -la\n   ```")
        assertEquals(listOf("ls -la"), inList.texts(Kind.CODE_BLOCK))
        // an unclosed fence is code to the end, streaming or not
        assertEquals(listOf("a\nb"), r("```\na\nb").texts(Kind.CODE_BLOCK))
        assertEquals(listOf("a\nb"), r("```\na\nb", streaming = true).texts(Kind.CODE_BLOCK))
        // ```inline``` on one line is not a fence
        assertTrue(r("```x```").spans(Kind.CODE_BLOCK).isEmpty())
    }

    @Test
    fun listsNestingOrderedContinuationAndTasks() {
        val m = r("- a\n  - b\n    - c\n- d\n\n1. one\n2) two\n   more about two\n- [ ] todo\n- [x] done")
        val lists = m.spans(Kind.LIST)
        assertEquals(listOf("• a", "◦ b", "▪ c", "• d", "1. one", "2. two", "more about two", "☐ todo", "☑ done"), lists.map { m.textOf(it) })
        assertEquals(listOf(0, 1, 2, 0, 0, 0, 0, 0, 0), lists.map { it.level })
        val cont = lists[6]
        assertFalse(cont.hang)
        assertEquals("2. ", cont.marker)
        assertTrue(lists[5].hang)
    }

    @Test
    fun quotesRulesAndBlankLines() {
        val m = r("> quoted **bold**\n> > deep\n\n\n\n---\nafter")
        assertEquals("quoted bold\ndeep\n\n \nafter", m.text)
        assertEquals(listOf("quoted bold", "deep"), m.texts(Kind.QUOTE))
        assertEquals(listOf("bold"), m.texts(Kind.BOLD))
        assertEquals(1, m.spans(Kind.RULE).size)
        assertEquals("a\n\nb", r("a\n\n\n\nb").text)
    }

    @Test
    fun linksAreOnlyWebLinks() {
        val m = r("see [docs](https://example.com/a_b) and <https://x.org> or https://y.net/p?q=1.")
        assertEquals("see docs and https://x.org or https://y.net/p?q=1.", m.text)
        assertEquals(listOf("https://example.com/a_b", "https://x.org", "https://y.net/p?q=1"), m.spans(Kind.LINK).map { it.url })
        assertEquals(listOf("docs", "https://x.org", "https://y.net/p?q=1"), m.texts(Kind.LINK))
        // not web links: shown as written, nothing to tap
        for (bad in listOf("[x](javascript:alert(1))", "[x](intent://scan/#Intent;end)", "<file:///etc/hosts>", "[x](content://a/b)")) {
            val b = r(bad)
            assertTrue(bad, b.spans(Kind.LINK).isEmpty())
        }
        // CJK around bare URLs; balanced parentheses stay, an unbalanced trailing one does not
        assertEquals(listOf("https://example.com/p"), r("访问https://example.com/p。然后").spans(Kind.LINK).map { it.url })
        assertEquals(listOf("https://en.wikipedia.org/wiki/A_(b)"), r("(see https://en.wikipedia.org/wiki/A_(b))").spans(Kind.LINK).map { it.url })
        assertEquals(listOf("https://x.com/a"), r("(see https://x.com/a)").spans(Kind.LINK).map { it.url })
        assertTrue(Markdown.isWebUrl("https://a.b/c"))
        assertFalse(Markdown.isWebUrl("https://"))
        assertFalse(Markdown.isWebUrl("ftp://a.b/c"))
    }

    @Test
    fun streamingHidesPendingMarkersAndStylesUnclosedOnes() {
        assertEquals("Some", r("Some **", streaming = true).text.trimEnd())
        r("Some **bol", streaming = true).let {
            assertEquals("Some bol", it.text)
            assertEquals(listOf("bol"), it.texts(Kind.BOLD))
        }
        r("run `ls -", streaming = true).let { assertEquals(listOf("ls -"), it.texts(Kind.CODE)) }
        assertEquals("see docs", r("see [docs](https://exa", streaming = true).text)
        assertTrue(r("see [docs](https://exa", streaming = true).spans(Kind.LINK).isEmpty())
        // a bare URL still arriving is text, not a half link
        assertTrue(r("go https://example.com/pa", streaming = true).spans(Kind.LINK).isEmpty())
        // lone marker lines wait for the next chunk
        for (tail in listOf("#", "##", "-", "*", ">", "`", "``", "--", "**")) {
            assertEquals(tail, "para", r("para\n$tail", streaming = true).text)
        }
        // finished text: unclosed markers are literal
        assertEquals("Some **bol", r("Some **bol").text)
        assertEquals("run `ls -", r("run `ls -").text)
    }

    /**
     * The no-flicker property on a typical reply: rendering every prefix while streaming, what is on screen
     * only grows (each text is a prefix of the next), and a character's styles are never taken away.
     */
    @Test
    fun streamingPrefixesOnlyGrow() {
        val reply = """
            ## 安装步骤

            先确认 **Node ≥ 22**，再运行 `npm ci`。详见 [文档](https://example.com/docs) 或 https://example.com/faq。

            1. 下载仓库
               用 `git clone`，不要用 *zip 包*。
            2. 安装依赖：
               ```bash
               npm ci --omit=dev
               ```
            - 注意 ~~旧版~~ 不再支持
              - 子项 __重点__

            > 提示：失败时看 `logcat`。

            ---
            完成 ***好了***
        """.trimIndent()
        var prev = r("", streaming = true)
        for (k in 1..reply.length) {
            val cur = r(reply.substring(0, k), streaming = true)
            assertTrue("prefix $k: «${prev.text}» → «${cur.text}»", cur.text.startsWith(prev.text.trimEnd()))
            for (c in 0 until prev.text.trimEnd().length) {
                val before = kindsAt(prev, c)
                val after = kindsAt(cur, c)
                assertTrue("prefix $k char $c '${prev.text[c]}': $before → $after", after.containsAll(before))
            }
            prev = cur
        }
        assertEquals(r(reply).text, r(reply, streaming = true).text)
    }

    private fun kindsAt(m: Markdown.Rendered, c: Int): Set<Kind> =
        m.spans.filter { c >= it.start && c < it.end }.map { it.kind }.toSet()
}
