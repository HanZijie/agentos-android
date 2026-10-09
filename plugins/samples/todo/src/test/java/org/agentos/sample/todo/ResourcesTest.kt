package org.agentos.sample.todo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 中英文资源的一致性（docs/next-apps-plan.md 7.2 R1、R9 的本地版；整合人的 tools/check-i18n.py 做全仓库门禁）：
 * key 一一对应、占位符一致、复数只写该语言用到的 quantity、英文资源没有汉字、`src/main` 的 Kotlin 字符串字面量里没有汉字（注释除外）。
 */
class ResourcesTest {
    private val res = File("src/main/res")

    private class Entry(val kind: String, val text: Map<String, String>)

    private fun load(dir: String): Map<String, Entry> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val result = LinkedHashMap<String, Entry>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            val name = e.getAttribute("name")
            when (e.tagName) {
                "string" -> result["string:$name"] = Entry("string", mapOf("" to e.textContent))
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    result["plurals:$name"] = Entry("plurals", (0 until items.length).associate { j ->
                        val item = items.item(j) as Element
                        item.getAttribute("quantity") to item.textContent
                    })
                }
            }
        }
        return result
    }

    private val zh = load("values")
    private val en = load("values-en")
    /** 汉字（不含全角标点，代码里有用作分隔符的全角逗号和感叹号）。 */
    private val han = Regex("[\\u3400-\\u9fff]")

    /** 英文资源里连中文标点（含全角）都不该有。 */
    private val cjk = Regex("[\\u3400-\\u9fff\\uff00-\\uffef\\u3000-\\u303f]")
    private val placeholder = Regex("%(\\d+\\$)?[sd]")

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value.replace(Regex("^%\\d+\\$"), "%") }.sorted().toList()

    @Test fun `both languages have exactly the same keys`() {
        assertEquals(zh.keys, en.keys)
        assertTrue(zh.size > 80)
    }

    @Test fun `placeholders line up per key and per plural quantity`() {
        for ((key, z) in zh) {
            val e = en.getValue(key)
            if (z.kind == "string") {
                assertEquals(key, placeholders(z.text.getValue("")), placeholders(e.text.getValue("")))
            } else {
                // 中文只有 other；英文一个 quantity 的占位符与中文 other 一致
                for ((_, enText) in e.text) assertEquals(key, placeholders(z.text.getValue("other")), placeholders(enText))
            }
        }
    }

    @Test fun `plurals use the quantities each language needs`() {
        for ((key, z) in zh) if (z.kind == "plurals") {
            assertEquals("$key (zh)", setOf("other"), z.text.keys)
            assertEquals("$key (en)", setOf("one", "other"), en.getValue(key).text.keys)
        }
    }

    @Test fun `the English resources contain no Chinese`() {
        for ((key, e) in en) for (text in e.text.values) assertTrue("$key: $text", !cjk.containsMatchIn(text))
    }

    @Test fun `the default resources are Chinese where they are words`() {
        assertEquals("待办", zh.getValue("string:app_name").text.getValue(""))
        assertEquals("Todo", en.getValue("string:app_name").text.getValue(""))
        // 术语表：待办 / 进行中 / 已完成 / 搁置
        assertEquals(
            listOf("待办", "进行中", "已完成", "搁置"),
            listOf("status_todo", "status_doing", "status_done", "status_shelved").map { zh.getValue("string:$it").text.getValue("") },
        )
        assertEquals(
            listOf("To do", "In progress", "Done", "On hold"),
            listOf("status_todo", "status_doing", "status_done", "status_shelved").map { en.getValue("string:$it").text.getValue("") },
        )
    }

    @Test fun `no Chinese text is hard-coded in Kotlin string literals of src-main`() {
        val offenders = ArrayList<String>()
        File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val code = file.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) { m -> "\n".repeat(m.value.count { it == '\n' }) }
            code.lines().forEachIndexed { index, line ->
                val stripped = stripLineComment(line)
                Regex("\"([^\"\\\\]|\\\\.)*\"").findAll(stripped).forEach { m ->
                    if (han.containsMatchIn(m.value)) offenders += "${file.name}:${index + 1}: ${m.value.take(60)}"
                }
            }
        }
        assertEquals("user-visible Chinese belongs in values/strings.xml", emptyList<String>(), offenders)
    }

    /** 去掉行尾的 `//` 注释（引号里的 `//` 不算）。 */
    private fun stripLineComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inString) { i += 2; continue }
            if (c == '"') inString = !inString
            if (!inString && c == '/' && i + 1 < line.length && line[i + 1] == '/') return line.substring(0, i)
            i++
        }
        return line
    }
}
