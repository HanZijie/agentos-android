package org.agentos.extensions

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolNamingTest {
    private val valid = Regex("[A-Za-z0-9_-]{1,64}")

    @Test
    fun `the plain form is mcp__plugin__server__tool, dots in the plugin name become underscores`() {
        assertEquals("mcp__notes__notes__create_note", ToolNaming.nameOf(ToolId("notes", "notes", "create_note")))
        assertEquals("mcp__com_example_notes__main__list", ToolNaming.nameOf(ToolId("com.example.notes", "main", "list")))
        assertEquals("mcp__my-plugin__srv-1__do-it", ToolNaming.nameOf(ToolId("my-plugin", "srv-1", "do-it")))
    }

    @Test
    fun `characters outside A-Za-z0-9_- become underscores in every segment`() {
        assertEquals("mcp__p__s_x__a_b_c", ToolNaming.nameOf(ToolId("p", "s x", "a.b/c")))
        assertEquals("mcp__p__s__" + "_".repeat(5), ToolNaming.nameOf(ToolId("p", "s", "笔记本工具")))
        assertTrue(valid.matches(ToolNaming.nameOf(ToolId("p", "服务器", "笔记工具 \"x\""))))
    }

    @Test
    fun `a name of exactly 64 characters is kept, one more is cut and gets a 6 character hash`() {
        val prefix = "mcp__p__s__"
        val exact = ToolId("p", "s", "t".repeat(64 - prefix.length))
        assertEquals(64, ToolNaming.nameOf(exact).length)
        assertEquals(prefix + "t".repeat(64 - prefix.length), ToolNaming.nameOf(exact))

        val long = ToolId("p", "s", "t".repeat(64 - prefix.length + 1))
        val name = ToolNaming.nameOf(long)
        assertEquals(64, name.length)
        assertTrue(valid.matches(name))
        assertTrue(Regex(".*_[0-9a-f]{6}").matches(name), name)
        assertTrue(name.startsWith(prefix + "t"))
    }

    @Test
    fun `the same input always gives the same output, and different long originals get different names`() {
        val a = ToolId("p", "s", "x".repeat(100))
        val b = ToolId("p", "s", "x".repeat(100) + "y")
        assertEquals(ToolNaming.nameOf(a), ToolNaming.nameOf(a.copy()))
        // 截断后的前缀相同，只有哈希（取自原文）区分它们
        assertTrue(ToolNaming.nameOf(a) != ToolNaming.nameOf(b))
        // 固定的期望值：哈希算法或命名规则一变就会失败（Hook matcher 按最终名字匹配，不能悄悄变）
        assertEquals("mcp__p__s__" + "x".repeat(46) + "_" + hashOf(a), ToolNaming.nameOf(a))
    }

    private fun hashOf(id: ToolId): String =
        java.security.MessageDigest.getInstance("SHA-256").digest("${id.plugin}\u0000${id.server}\u0000${id.tool}".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(6)

    @Test
    fun `assign keeps unique names as they are`() {
        val ids = listOf(ToolId("notes", "notes", "create"), ToolId("notes", "notes", "list"), ToolId("alarm", "alarm", "set"))
        val names = ToolNaming.assign(ids)
        for (id in ids) assertEquals(ToolNaming.nameOf(id), names.getValue(id))
    }

    @Test
    fun `colliding tools all get a hash suffix, nobody keeps the plain name`() {
        // a.b 和 a_b 两个插件：处理后的名字相同
        val x = ToolId("a.b", "s", "t")
        val y = ToolId("a_b", "s", "t")
        assertEquals(ToolNaming.nameOf(x), ToolNaming.nameOf(y))
        val names = ToolNaming.assign(listOf(x, y))
        assertEquals(2, names.values.toSet().size)
        for (n in names.values) {
            assertTrue(valid.matches(n), n)
            assertTrue(Regex("mcp__a_b__s__t_[0-9a-f]{6}").matches(n), n)
        }
    }

    @Test
    fun `the result depends only on the set, never on the order`() {
        val ids = listOf(
            ToolId("a.b", "s", "t"), ToolId("a_b", "s", "t"), ToolId("a-b", "s", "t"), ToolId("a", "b__s", "t"), ToolId("a__b", "s", "t"),
            ToolId("notes", "notes", "x".repeat(90)), ToolId("notes", "notes", "x".repeat(91)), ToolId("alarm", "a", "set"),
        )
        val expected = ToolNaming.assign(ids)
        repeat(30) { i ->
            val shuffled = ids.shuffled(Random(i))
            assertEquals(expected, ToolNaming.assign(shuffled))
        }
        assertEquals(ids.size, expected.values.toSet().size)
        for (n in expected.values) assertTrue(valid.matches(n), n)
    }

    @Test
    fun `a plain name that looks like a suffixed one does not break uniqueness`() {
        // 先算出 x、y 撞名后各自的带后缀名字，再让第三个工具的原文正好就是 x 的带后缀名字
        val x = ToolId("a.b", "s", "t")
        val y = ToolId("a_b", "s", "t")
        val suffixed = ToolNaming.assign(listOf(x, y)).getValue(x)
        // mcp__a_b__s__t_<hash>：构造 plugin=a_b、server=s、tool=t_<hash> 的工具，它的朴素名字与上面相同
        val z = ToolId("a_b", "s", suffixed.removePrefix("mcp__a_b__s__"))
        assertEquals(suffixed, ToolNaming.nameOf(z))
        val names = ToolNaming.assign(listOf(x, y, z))
        assertEquals(3, names.values.toSet().size)
        // z 的朴素名字被 x 的候选占了，三个都撞：全部带（更长的）后缀，而且互不相同
        for (n in names.values) assertTrue(valid.matches(n), n)
    }

    @Test
    fun `adding an unrelated tool does not change the others`() {
        val ids = listOf(ToolId("notes", "notes", "create"), ToolId("a.b", "s", "t"), ToolId("a_b", "s", "t"))
        val before = ToolNaming.assign(ids)
        val after = ToolNaming.assign(ids + ToolId("alarm", "alarm", "set"))
        for (id in ids) assertEquals(before.getValue(id), after.getValue(id))
    }

    @Test
    fun `identical tool ids in one catalog are a caller error`() {
        assertFailsWith<IllegalArgumentException> { ToolNaming.assign(listOf(ToolId("a", "b", "c"), ToolId("a", "b", "c"))) }
    }

    @Test
    fun `an empty catalog and a single tool are fine`() {
        assertEquals(emptyMap(), ToolNaming.assign(emptyList()))
        val one = ToolId("p", "s", "t")
        assertEquals(mapOf(one to "mcp__p__s__t"), ToolNaming.assign(listOf(one)))
    }

    @Test
    fun `random catalogs always give valid unique names`() {
        val rnd = Random(7)
        val alphabet = "ab._- xX9"
        fun word(max: Int) = (1..rnd.nextInt(1, max)).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        repeat(200) {
            val ids = (1..rnd.nextInt(1, 25)).map { ToolId(word(4), word(4), word(if (rnd.nextInt(5) == 0) 120 else 5)) }.toSet()
            val names = ToolNaming.assign(ids)
            assertEquals(ids.size, names.size)
            assertEquals(ids.size, names.values.toSet().size, "duplicate names for $ids")
            for (n in names.values) assertTrue(valid.matches(n), n)
        }
    }
}
