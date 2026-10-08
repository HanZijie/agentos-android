package org.agentos.app.i18n

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * JVM 测试用的 [Strings]：读**真实的** `app/src/main/res/values/strings.xml`（中文）和 `values-en/strings.xml`（英文），
 * 按 `R.string.*` 的 id 反查资源名。所以资源改了（改错占位符、漏了英文、有人把模板改成别的引号）测试会直接发现，
 * 不是拿一份手写的假文案自我满足。
 *
 * - `ResStrings.zh` 只读 `values/`，`ResStrings.en` 只读 `values-en/`，**英文缺 key 时失败**（不像设备那样回落到中文：测试要抓的就是漏翻译）。
 * - 转义和格式化按 aapt / `Resources.getString` 的规则：`\n`、`\'`、`\"`、`\\`、`\@`、`\uXXXX`，空白折叠，`String.format(locale, …)`。
 * - 复数：英文 `one`（数量 1）/ `other`，中文只有 `other`。
 */
class ResStrings private constructor(
    val locale: Locale,
    private val strings: Map<String, String>,
    private val plurals: Map<String, Map<String, String>>,
) : Strings {

    override fun get(id: Int, vararg args: Any): String = format(template(id), args)

    override fun plural(id: Int, quantity: Int, vararg args: Any): String {
        val name = Names.plurals[id] ?: error("no R.plurals with id $id")
        val forms = plurals[name] ?: error("plurals/$name is missing in the $locale resources")
        val quantityName = if (locale.language == "en" && quantity == 1) "one" else "other"
        return format(forms[quantityName] ?: forms["other"] ?: error("plurals/$name has no 'other'"), args)
    }

    /** 模板原文（占位符还在）：测试看占位符个数、定界符用。 */
    fun template(id: Int): String {
        val name = Names.strings[id] ?: error("no R.string with id $id")
        return strings[name] ?: error("string/$name is missing in the $locale resources")
    }

    /** 同 [template]，按资源名。 */
    fun template(name: String): String = strings[name] ?: error("string/$name is missing in the $locale resources")

    val stringNames: Set<String> get() = strings.keys

    private fun format(template: String, args: Array<out Any>): String =
        if (args.isEmpty()) template else String.format(locale, template, *args)

    /** `R.string` / `R.plurals` 的 id → 名字（反射：app 模块的 R 类在单元测试的 classpath 上）。 */
    private object Names {
        val strings: Map<Int, String> = fieldsOf("org.agentos.app.R\$string")
        val plurals: Map<Int, String> = fieldsOf("org.agentos.app.R\$plurals")

        private fun fieldsOf(className: String): Map<Int, String> {
            // R.plurals does not exist until the app has a plurals resource
            val cls = try {
                Class.forName(className)
            } catch (e: ClassNotFoundException) {
                return emptyMap()
            }
            return cls.fields.filter { it.type == Int::class.javaPrimitiveType }.associate { it.getInt(null) to it.name }
        }
    }

    companion object {
        val zh: ResStrings by lazy { load(Locale.SIMPLIFIED_CHINESE, "values") }
        val en: ResStrings by lazy { load(Locale.ENGLISH, "values-en") }

        private fun resDir(): File =
            listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory } ?: error("cannot find app/src/main/res from ${File(".").absoluteFile}")

        fun load(locale: Locale, dir: String): ResStrings {
            val file = File(resDir(), "$dir/strings.xml")
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strings = LinkedHashMap<String, String>()
            val plurals = LinkedHashMap<String, Map<String, String>>()
            val root = doc.documentElement.childNodes
            for (i in 0 until root.length) {
                val node = root.item(i) as? Element ?: continue
                when (node.tagName) {
                    "string" -> if (node.getAttribute("translatable") != "false") strings[node.getAttribute("name")] = unescape(node.textContent)
                    "plurals" -> {
                        val forms = LinkedHashMap<String, String>()
                        val items = node.childNodes
                        for (j in 0 until items.length) {
                            val item = items.item(j) as? Element ?: continue
                            if (item.tagName == "item") forms[item.getAttribute("quantity")] = unescape(item.textContent)
                        }
                        plurals[node.getAttribute("name")] = forms
                    }
                }
            }
            return ResStrings(locale, strings, plurals)
        }

        /** aapt 的字符串规则：双引号里保留空白，外面折叠；反斜杠转义。 */
        internal fun unescape(raw: String): String {
            val out = StringBuilder()
            var quoted = false
            var pendingSpace = false
            var i = 0
            val s = raw.trim()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\\' && i + 1 < s.length -> {
                        i++
                        when (val e = s[i]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'u' -> {
                                out.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                                i += 4
                            }
                            else -> out.append(e) // \' \" \\ \@ \?
                        }
                        pendingSpace = false
                    }
                    c == '"' -> quoted = !quoted
                    !quoted && c.isWhitespace() -> pendingSpace = true
                    else -> {
                        if (pendingSpace && out.isNotEmpty()) out.append(' ')
                        pendingSpace = false
                        out.append(c)
                    }
                }
                i++
            }
            return out.toString()
        }
    }
}
