package org.agentos.sample.alarm.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * R1 / R9 在本模块的守门：`values/`（中文）和 `values-en/`（英文）的 key、占位符、复数量词一一对应；英文里没有中文；
 * 新增 key 忘了写某一种语言，这里先红。（全仓库的门禁是 tools/check-i18n.py。）
 */
class ResourceParityTest {
    private class Entry(val type: String, val name: String, val text: String, val quantities: Set<String>)

    private fun load(dir: String): Map<String, Entry> {
        val res = generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "src/main/res"), File(it, "plugins/samples/alarm/src/main/res")) }
            .first { it.isDirectory }
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml")).documentElement
        val out = LinkedHashMap<String, Entry>()
        for (i in 0 until root.childNodes.length) {
            val node = root.childNodes.item(i) as? Element ?: continue
            val items = node.getElementsByTagName("item")
            val itemElements = (0 until items.length).map { items.item(it) as Element }
            val quantities = if (node.tagName == "plurals") itemElements.map { it.getAttribute("quantity") }.toSet() else emptySet()
            val text = if (node.tagName == "string") node.textContent else itemElements.joinToString("\n") { it.textContent }
            val key = "${node.tagName}:${node.getAttribute("name")}"
            out[key] = Entry(node.tagName, node.getAttribute("name"), text, quantities)
            if (node.tagName == "string-array") out["$key#size"] = Entry("size", key, itemElements.size.toString(), emptySet())
        }
        return out
    }

    private val placeholder = Regex("%(\\d+\\$)?[sd]")
    // 去重：英文复数有 one / other 两条，中文只有 other 一条，各条里的占位符相同
    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.toSortedSet().toList()

    @Test
    fun chineseAndEnglishHaveTheSameKeys() {
        val zh = load("values")
        val en = load("values-en")
        assertEquals("keys only in zh: ${zh.keys - en.keys}", emptySet<String>(), zh.keys - en.keys)
        assertEquals("keys only in en: ${en.keys - zh.keys}", emptySet<String>(), en.keys - zh.keys)
    }

    @Test
    fun placeholdersMatchPerKey() {
        val zh = load("values")
        val en = load("values-en")
        for ((key, z) in zh) {
            val e = en.getValue(key)
            assertEquals("placeholders of $key", placeholders(z.text), placeholders(e.text))
        }
    }

    @Test
    fun englishPluralsHaveOneAndOtherAndChineseOnlyOther() {
        val zh = load("values").filterValues { it.type == "plurals" }
        val en = load("values-en").filterValues { it.type == "plurals" }
        assertTrue(zh.isNotEmpty())
        for ((key, z) in zh) {
            assertEquals("zh $key", setOf("other"), z.quantities)
            assertEquals("en $key", setOf("one", "other"), en.getValue(key).quantities)
        }
    }

    @Test
    fun englishResourcesContainNoChinese() {
        for ((key, e) in load("values-en")) {
            assertFalse("$key has CJK text: ${e.text}", e.text.any { it.code in 0x3400..0x9FFF || it.code in 0xFF00..0xFFEF })
        }
    }
}
