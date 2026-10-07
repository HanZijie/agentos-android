package org.agentos.extensions.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SkillPathsTest {
    @Test
    fun `a missing path is SKILL md, relative paths are resolved under the skill directory`() {
        assertEquals("skills/notes/SKILL.md", SkillPaths.resolve("notes", null))
        assertEquals("skills/notes/SKILL.md", SkillPaths.resolve("notes", ""))
        assertEquals("skills/notes/references/guide.md", SkillPaths.resolve("notes", "references/guide.md"))
        assertEquals("skills/notes/a b.md", SkillPaths.resolve("notes", "a b.md"))
        assertEquals("skills/notes/笔记.md", SkillPaths.resolve("notes", "笔记.md"))
        assertEquals("skills/notes/%2e%2e/x", SkillPaths.resolve("notes", "%2e%2e/x"), "percent signs are literal characters, never decoded")
    }

    @Test
    fun `everything that could leave the skill directory is refused`() {
        val bad = listOf(
            "../other/SKILL.md", "..", "a/../../b", "a/..", "../", "./SKILL.md", ".", "a/./b",
            "/etc/passwd", "/", "C:\\Windows\\win.ini", "c:/x", "a\\b", "..\\x",
            "a//b", "a/", "//x",
            "a\u0000b", "a\nb", "a\tb", "a\u007fb", "\u0001",
            "a\u202eb", "a\u200bb", "a\u2028b",
            "x".repeat(257), (1..9).joinToString("/") { "d" },
        )
        for (p in bad) assertFailsWith<IllegalArgumentException>("should refuse ${p.take(30).replace("\u0000", "<NUL>")}") { SkillPaths.resolve("notes", p) }
    }

    @Test
    fun `a bad skill directory is refused too`() {
        for (d in listOf("", ".", "..", "a/b")) assertFailsWith<IllegalArgumentException> { SkillPaths.resolve(d, null) }
    }

    @Test
    fun `limits are exact`() {
        SkillPaths.resolve("n", "x".repeat(256))
        SkillPaths.resolve("n", (1..8).joinToString("/") { "d" })
    }
}
