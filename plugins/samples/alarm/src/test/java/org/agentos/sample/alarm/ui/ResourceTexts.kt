package org.agentos.sample.alarm.ui

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * JVM 测试用的 [Texts]：直接读 `src/main/res/values/strings.xml`（中文）和 `values-en/strings.xml`（英文）这两份真实资源，
 * 所以断言的是上线的文案，而不是测试里另抄一份。资源 id → 名字靠反射读生成的 `R.string` / `R.plurals` / `R.array`。
 * 复数按各语言的最小规则选 quantity：英文 1 → one，其余 other；中文只有 other。
 */
class ResourceTexts private constructor(override val locale: Locale, dir: String) : Texts {
    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()
    private val arrays = HashMap<String, List<String>>()
    private val names: Map<Int, String> = idNames("org.agentos.sample.alarm.R\$string") +
        idNames("org.agentos.sample.alarm.R\$plurals") + idNames("org.agentos.sample.alarm.R\$array")

    init {
        val file = resDir().resolve(dir).resolve("strings.xml")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> strings[name] = unescape(node.textContent)
                "plurals" -> plurals[name] = items(node).associate { it.getAttribute("quantity") to unescape(it.textContent) }
                "string-array" -> arrays[name] = items(node).map { unescape(it.textContent) }
            }
        }
    }

    private fun name(id: Int) = names[id] ?: error("unknown resource id $id")

    override fun get(id: Int, vararg args: Any): String =
        String.format(locale, strings[name(id)] ?: error("no string ${name(id)} in $locale"), *args)

    override fun plural(id: Int, quantity: Int, vararg args: Any): String {
        val forms = plurals[name(id)] ?: error("no plurals ${name(id)} in $locale")
        val key = if (locale.language == "en" && quantity == 1) "one" else "other"
        return String.format(locale, forms[key] ?: forms.getValue("other"), *args)
    }

    override fun array(id: Int): List<String> = arrays[name(id)] ?: error("no array ${name(id)} in $locale")

    companion object {
        val zh: Texts by lazy { ResourceTexts(Locale.CHINA, "values") }
        val en: Texts by lazy { ResourceTexts(Locale.US, "values-en") }

        private fun items(parent: Element): List<Element> {
            val list = parent.getElementsByTagName("item")
            return (0 until list.length).map { list.item(it) as Element }
        }

        private fun unescape(raw: String) = raw.trim().replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")

        /** Gradle 的单元测试工作目录是模块目录；从当前目录往上找，IDE 里从仓库根跑也行。 */
        private fun resDir(): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                listOf(File(dir, "src/main/res"), File(dir, "plugins/samples/alarm/src/main/res")).firstOrNull { it.isDirectory }?.let { return it }
                dir = dir.parentFile
            }
            error("alarm src/main/res not found")
        }

        private fun idNames(className: String): Map<Int, String> =
            Class.forName(className).declaredFields
                .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
                .associate { it.getInt(null) to it.name }
    }
}
