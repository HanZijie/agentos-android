package org.agentos.extensions.skills

/**
 * SKILL.md 开头的 frontmatter（`---` 包起来的 YAML）里的 `name` 和 `description`。
 *
 * **容错是第一要求**：第三方文件什么样都可能有（缺失、没结束、超长、非法 YAML、二进制垃圾），解析绝不抛异常，
 * 也不影响别的 Skill；读不出的部分留空，原因写进 [Parsed.problems]（中文，给插件页显示）。
 *
 * 只实现够用的 YAML 子集：顶层的 `key: value`；值可以是普通标量（行内 ` #` 注释去掉）、单引号、双引号（常见转义）、
 * 块标量（`>`、`|`，带 `-` `+` 也可以）、缩进的续行。其他结构（列表、嵌套）忽略。其他键忽略。重复的键取第一个。
 */
object SkillFrontmatter {
    class Parsed(val name: String?, val description: String?, val problems: List<String>)

    private val KEY_LINE = Regex("^([A-Za-z_][A-Za-z0-9_-]*)[ \\t]*:(?:[ \\t]+(.*))?$")
    private val BLOCK_INDICATOR = Regex("^[>|][+-]?[0-9]?[ \\t]*(#.*)?$")

    fun parse(raw: String): Parsed {
        val problems = ArrayList<String>()
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        if (lines.isEmpty() || lines[0].trimEnd() != "---") {
            return Parsed(null, null, listOf("SKILL.md 没有 frontmatter（文件要以 --- 开头，写上 name 和 description）"))
        }
        val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" || lines[it].trimEnd() == "..." }
        if (end == null) return Parsed(null, null, listOf("SKILL.md 的 frontmatter 没有结束（缺少第二个 ---），或者太长"))
        val body = lines.subList(1, end)
        val values = LinkedHashMap<String, String>()
        var i = 0
        while (i < body.size) {
            val line = body[i]
            i++
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            if (line[0] == ' ' || line[0] == '\t') continue // 不属于任何键的缩进行（嵌套、列表）：忽略
            val m = KEY_LINE.matchEntire(line.trimEnd())
            if (m == null) continue
            val key = m.groupValues[1]
            val rest = m.groupValues[2]
            // 这个键后面缩进的行，或者块标量/多行引号的续行
            val continuation = ArrayList<String>()
            while (i < body.size && (body[i].isBlank() || body[i][0] == ' ' || body[i][0] == '\t')) {
                continuation += body[i]
                i++
            }
            val value = scalar(rest, continuation, problems, key)
            if (key !in values) values[key] = value else problems += "frontmatter 里的 $key 重复了，只用第一个"
        }
        val name = values["name"]?.takeIf { it.isNotBlank() }
        val description = values["description"]?.takeIf { it.isNotBlank() }
        if (name == null) problems += "frontmatter 里没有 name"
        if (description == null) problems += "frontmatter 里没有 description"
        return Parsed(name, description, problems)
    }

    private fun scalar(rest: String, continuation: List<String>, problems: MutableList<String>, key: String): String {
        val v = rest.trim()
        if (BLOCK_INDICATOR.matches(v)) {
            val literal = v.startsWith("|")
            val lines = continuation.map { it.trim() }
            return if (literal) lines.joinToString("\n").trim() else foldLines(lines)
        }
        if (v.startsWith("\"")) {
            val joined = (listOf(v) + continuation.map { it.trim() }).joinToString(" ")
            val parsed = doubleQuoted(joined)
            if (parsed == null) {
                problems += "frontmatter 里 $key 的双引号没有闭合"
                return joined.removePrefix("\"").trim()
            }
            return parsed
        }
        if (v.startsWith("'")) {
            val joined = (listOf(v) + continuation.map { it.trim() }).joinToString(" ")
            val parsed = singleQuoted(joined)
            if (parsed == null) {
                problems += "frontmatter 里 $key 的单引号没有闭合"
                return joined.removePrefix("'").trim()
            }
            return parsed
        }
        // 普通标量：去掉行内注释，缩进的续行接在后面
        val first = stripComment(v)
        return foldLines(listOf(first) + continuation.map { stripComment(it.trim()) })
    }

    private fun foldLines(lines: List<String>): String = lines.filter { it.isNotEmpty() }.joinToString(" ").trim()

    private fun stripComment(s: String): String {
        val i = s.indexOf(" #")
        return (if (i >= 0) s.substring(0, i) else s).trim()
    }

    /** 以 `"` 开头的双引号标量；没有闭合返回 null。引号之后的内容（注释）忽略。 */
    private fun doubleQuoted(s: String): String? {
        val out = StringBuilder()
        var i = 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> return out.toString()
                c == '\\' && i + 1 < s.length -> {
                    i++
                    when (val e = s[i]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'u' -> {
                            val hex = s.substring(i + 1, minOf(i + 5, s.length))
                            val cp = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                            if (cp != null) {
                                out.append(cp.toChar())
                                i += 4
                            } else {
                                out.append("\\u")
                            }
                        }
                        else -> out.append('\\').append(e)
                    }
                }
                else -> out.append(c)
            }
            i++
        }
        return null
    }

    private fun singleQuoted(s: String): String? {
        val out = StringBuilder()
        var i = 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\'') {
                if (i + 1 < s.length && s[i + 1] == '\'') {
                    out.append('\'')
                    i += 2
                    continue
                }
                return out.toString()
            }
            out.append(c)
            i++
        }
        return null
    }
}
