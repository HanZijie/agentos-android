package org.agentos.extensions.skills

import org.agentos.extensions.ExtMessages
import org.agentos.runtime.i18n.MessageRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillFrontmatterTest {
    private fun parse(text: String) = SkillFrontmatter.parse(text)

    @Test
    fun `plain, quoted and block scalars`() {
        val p = parse("---\nname: notes\ndescription: Keep notes: create, search # a comment\n---\nbody")
        assertEquals("notes", p.name)
        assertEquals("Keep notes: create, search", p.description)
        assertTrue(p.problems.isEmpty())

        val q = parse("---\nname: \"no\\\"tes\"\ndescription: 'it''s fine'\n---\n")
        assertEquals("no\"tes", q.name)
        assertEquals("it's fine", q.description)

        val folded = parse("---\nname: a\ndescription: >\n  first line\n  second line\n\n  third\n---\n")
        assertEquals("first line second line third", folded.description)
        val literal = parse("---\nname: a\ndescription: |-\n  first\n  second\n---\n")
        assertEquals("first\nsecond", literal.description)
    }

    @Test
    fun `continuation lines, escapes, CRLF and a BOM`() {
        val p = parse("\uFEFF---\r\nname: a\r\ndescription: starts here\r\n  and goes on\r\n---\r\n")
        assertEquals("starts here and goes on", p.description)
        val u = parse("---\nname: a\ndescription: \"caf\\u00e9 \\n x\"\n---\n")
        assertEquals("café \n x", u.description)
        val multi = parse("---\nname: a\ndescription: \"one\n  two\"\n---\n")
        assertEquals("one two", multi.description)
    }

    @Test
    fun `lists, nested maps, unknown keys and comments are ignored`() {
        val p = parse("---\n# comment\nname: a\ntags:\n  - x\n  - y\nmeta:\n  nested: 1\ndescription: d\nlicense: MIT\n---\n")
        assertEquals("a", p.name)
        assertEquals("d", p.description)
        assertTrue(p.problems.isEmpty())
    }

    @Test
    fun `a missing or unfinished frontmatter is a problem, never an exception`() {
        for (text in listOf("", "no frontmatter at all", "# Title\n---\nname: a\n---", "---\nname: a\ndescription: d\n", "---\n")) {
            val p = parse(text)
            assertNull(p.name, text)
            assertTrue(p.problems.isNotEmpty(), text)
        }
        assertEquals(listOf(MessageRef.of(ExtMessages.SKILL_NO_FRONTMATTER)), parse("no frontmatter at all").problems)
        assertEquals(listOf(MessageRef.of(ExtMessages.SKILL_FRONTMATTER_UNCLOSED)), parse("---\nname: a\ndescription: d\n").problems)
    }

    @Test
    fun `garbage inside the frontmatter does not throw and keeps what is readable`() {
        val p = parse("---\n: : :\n\u0001\u0002\nname: ok\n- - -\n{{{\ndescription: \"unterminated\n---\n")
        assertEquals("ok", p.name)
        assertTrue(p.problems.contains(MessageRef.of(ExtMessages.SKILL_DOUBLE_QUOTE_UNCLOSED, "description")))
        // 二进制垃圾也不抛
        val junk = String(ByteArray(2_000) { (it * 31).toByte() }, Charsets.ISO_8859_1)
        parse("---\n$junk\n---\n")
        parse(junk)
    }

    @Test
    fun `duplicate keys use the first one and report it`() {
        val p = parse("---\nname: first\nname: second\ndescription: d\n---\n")
        assertEquals("first", p.name)
        assertTrue(p.problems.contains(MessageRef.of(ExtMessages.SKILL_KEY_DUPLICATE, "name")))
    }

    @Test
    fun `missing name or description is reported`() {
        val noName = parse("---\ndescription: d\n---\n")
        assertNull(noName.name)
        assertTrue(noName.problems.contains(MessageRef.of(ExtMessages.SKILL_NAME_MISSING)))
        val noDesc = parse("---\nname: a\n---\n")
        assertNull(noDesc.description)
        assertTrue(noDesc.problems.contains(MessageRef.of(ExtMessages.SKILL_DESCRIPTION_MISSING)))
        assertNull(parse("---\nname:\ndescription: d\n---\n").name)
    }

    @Test
    fun `the three sample SKILL md files parse`() {
        fun sample(n: String) = SkillFrontmatterTest::class.java.getResourceAsStream("/samples/$n/SKILL.md")!!.readBytes().toString(Charsets.UTF_8)
        for (n in listOf("alarm", "calendar", "notes")) {
            val p = parse(sample(n))
            assertEquals(n, p.name)
            assertTrue(!p.description.isNullOrBlank() && p.problems.isEmpty(), n)
        }
        assertTrue(parse(sample("calendar")).description!!.startsWith("Create and look up events"))
    }
}
