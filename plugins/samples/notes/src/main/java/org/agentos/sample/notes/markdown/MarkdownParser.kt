package org.agentos.sample.notes.markdown

/**
 * 够用的 Markdown 解析器（纯 Kotlin，不依赖 Android，也不加任何依赖）。
 *
 * 支持：# 标题、段落（单个换行当作换行，更接近“笔记”的直觉）、``` 围栏代码块、> 引用（可嵌套）、
 * 有序 / 无序列表（可嵌套）、任务列表（- [ ] / - [x]）、--- 分隔线；
 * 行内：**粗体**、*斜体*（也认 _ 和 __）、~~删除线~~、`行内代码`、[链接](url)、![图片](url)（当作链接）、<url> 和裸 http(s) 地址。
 * 不支持：表格、HTML、脚注、Setext 标题。链接只放行 http / https / mailto / tel。
 */
object MarkdownParser {
    private class Line(val no: Int, val text: String) {
        val blank: Boolean get() = text.isBlank()
    }

    private class ItemMatch(val indent: Int, val ordered: Boolean, val number: Int, val text: String)

    private val FENCE = Regex("^( {0,3})(`{3,}|~{3,})\\s*([^\\s`]*).*$")
    private val HEADING = Regex("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))?(?:[ \\t]+#+)?[ \\t]*$")
    private val QUOTE = Regex("^ {0,3}> ?(.*)$")
    private val ITEM = Regex("^( *)([-*+]|(\\d{1,9})[.)])(?: +(.*))?$")
    private val TASK = Regex("^\\[([ xX])](?:\\s+(.*))?$")
    private val ESCAPABLE = "\\`*_{}[]()#+-.!~|>"
    private val SAFE_SCHEMES = listOf("http://", "https://", "mailto:", "tel:")

    /** 行内标记（强调、链接、删除线）最长跨度。再远的当作字面文字：既合理，也让病态输入不会退化成平方级。 */
    private const val MAX_SPAN = 2000

    /** 引用 / 列表的最大嵌套层数，超过的部分按普通文字处理。 */
    private const val MAX_DEPTH = 16

    /** 分隔线：至少三个相同的 - * _，中间可夹空格 / 制表符。手写而不用带重复分组的正则（超长一行会栈溢出）。 */
    private object RULE {
        fun matches(text: String): Boolean {
            var i = 0
            while (i < text.length && i < 4 && text[i] == ' ') i++
            if (i > 3 || i >= text.length) return false
            val marker = text[i]
            if (marker != '-' && marker != '*' && marker != '_') return false
            var count = 0
            while (i < text.length) {
                val c = text[i]
                if (c == marker) count++ else if (c != ' ' && c != '\t') return false
                i++
            }
            return count >= 3
        }
    }

    fun parse(source: String): List<Block> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n")
            .mapIndexed { i, t -> Line(i, expandLeadingTabs(t)) }
        return parseBlocks(lines, 0)
    }

    fun parseInlines(source: String): List<Inline> = InlineParser(source).parse()

    /** 把一条备忘录的开头渲染成一行纯文本（去掉标记），用于卡片摘要；只解析前面 [maxSourceChars] 个字符。 */
    fun plainText(content: String, maxChars: Int = 160, maxSourceChars: Int = 1200): String {
        val head = if (content.length > maxSourceChars) content.substring(0, maxSourceChars) else content
        val sb = StringBuilder()
        fun add(text: String) {
            val t = text.trim()
            if (t.isEmpty()) return
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t)
        }
        fun walk(blocks: List<Block>) {
            for (block in blocks) {
                if (sb.length >= maxChars) return
                when (block) {
                    is Block.Heading -> add(inlinesText(block.inlines))
                    is Block.Paragraph -> add(inlinesText(block.inlines).replace(Regex("\\s+"), " "))
                    is Block.CodeBlock -> add(block.code.replace(Regex("\\s+"), " "))
                    is Block.Quote -> walk(block.blocks)
                    is Block.ListBlock -> block.items.forEach { item ->
                        val mark = when (item.checked) { true -> "☑"; false -> "☐"; null -> "•" }
                        val before = sb.length
                        walk(item.blocks)
                        if (sb.length > before) sb.insert(before + if (before > 0) 1 else 0, "$mark ")
                    }
                    Block.Rule -> Unit
                }
            }
        }
        walk(parse(head))
        return if (sb.length <= maxChars) sb.toString() else sb.substring(0, maxChars).trimEnd() + "…"
    }

    fun inlinesText(inlines: List<Inline>): String = buildString {
        for (inline in inlines) when (inline) {
            is Inline.Text -> append(inline.text)
            is Inline.Bold -> append(inlinesText(inline.children))
            is Inline.Italic -> append(inlinesText(inline.children))
            is Inline.Strike -> append(inlinesText(inline.children))
            is Inline.Code -> append(inline.text)
            is Inline.Link -> append(inlinesText(inline.children))
        }
    }

    /**
     * 切换 [source] 中第 [line] 行（0 起）上的任务复选框：[ ] ↔ [x]。该行不是任务项则返回 null。
     */
    fun toggleTask(source: String, line: Int): String? {
        val lines = source.split("\n").toMutableList()
        if (line !in lines.indices) return null
        val m = Regex("^(\\s*(?:[-*+]|\\d{1,9}[.)])\\s+\\[)([ xX])(].*)$").matchEntire(lines[line]) ?: return null
        lines[line] = m.groupValues[1] + (if (m.groupValues[2] == " ") "x" else " ") + m.groupValues[3]
        return lines.joinToString("\n")
    }

    // ---------------------------------------------------------------- 块

    private fun expandLeadingTabs(text: String): String {
        if (!text.startsWith("\t") && !text.contains("\t")) return text
        val n = text.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) text.length else it }
        return text.substring(0, n).replace("\t", "    ") + text.substring(n)
    }

    private fun indentOf(text: String): Int = text.indexOfFirst { it != ' ' }.let { if (it < 0) text.length else it }

    private fun matchItem(text: String): ItemMatch? {
        val m = ITEM.matchEntire(text) ?: return null
        val ordered = m.groupValues[3].isNotEmpty()
        return ItemMatch(m.groupValues[1].length, ordered, m.groupValues[3].toIntOrNull() ?: 1, m.groupValues[4])
    }

    private fun isClosingFence(text: String, marker: String): Boolean {
        val t = text.trim()
        return t.length >= marker.length && t.all { it == marker[0] }
    }

    /** 是否能打断一个段落：围栏、标题、分隔线、引用、无序列表项、以 1 开头的有序列表项。 */
    private fun interruptsParagraph(text: String): Boolean {
        if (FENCE.matches(text) || HEADING.matches(text) || RULE.matches(text) || QUOTE.matches(text)) return true
        val item = matchItem(text) ?: return false
        return !item.ordered || item.number == 1
    }

    private fun parseBlocks(lines: List<Line>, depth: Int): List<Block> {
        if (depth > MAX_DEPTH) {
            // 嵌套太深（病态输入）：当作普通文字，不再递归
            val raw = lines.joinToString("\n") { it.text.trim() }.trim()
            return if (raw.isEmpty()) emptyList() else listOf(Block.Paragraph(listOf(Inline.Text(raw))))
        }
        val out = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.blank) { i++; continue }

            val fence = FENCE.matchEntire(line.text)
            if (fence != null) {
                val indent = fence.groupValues[1].length
                val marker = fence.groupValues[2]
                val body = ArrayList<String>()
                var j = i + 1
                while (j < lines.size && !isClosingFence(lines[j].text, marker)) {
                    body += lines[j].text.let { t -> t.drop(minOf(indent, indentOf(t))) }
                    j++
                }
                out += Block.CodeBlock(fence.groupValues[3], body.joinToString("\n"))
                i = if (j < lines.size) j + 1 else j
                continue
            }

            val heading = HEADING.matchEntire(line.text)
            if (heading != null) {
                out += Block.Heading(heading.groupValues[1].length, parseInlines(heading.groupValues[2].trim()))
                i++
                continue
            }

            if (RULE.matches(line.text)) { out += Block.Rule; i++; continue }

            if (QUOTE.matches(line.text)) {
                val inner = ArrayList<Line>()
                var j = i
                while (j < lines.size) {
                    val q = QUOTE.matchEntire(lines[j].text) ?: break
                    inner += Line(lines[j].no, q.groupValues[1])
                    j++
                }
                out += Block.Quote(parseBlocks(inner, depth + 1))
                i = j
                continue
            }

            if (matchItem(line.text) != null) {
                val (list, next) = parseList(lines, i, depth)
                out += list
                i = next
                continue
            }

            // 段落：到空行或可打断的块为止
            val para = ArrayList<String>()
            var j = i
            while (j < lines.size && !lines[j].blank && (j == i || !interruptsParagraph(lines[j].text))) {
                para += lines[j].text.trim()
                j++
            }
            out += Block.Paragraph(parseInlines(para.joinToString("\n")))
            i = j
        }
        return out
    }

    private fun parseList(lines: List<Line>, start: Int, depth: Int): Pair<Block.ListBlock, Int> {
        val first = matchItem(lines[start].text)!!
        val base = first.indent
        val items = ArrayList<ListItem>()
        var i = start
        while (i < lines.size) {
            val m = matchItem(lines[i].text) ?: break
            if (m.indent < base || m.indent >= base + 2 || m.ordered != first.ordered) break
            if (RULE.matches(lines[i].text)) break

            var text = m.text
            var checked: Boolean? = null
            TASK.matchEntire(text)?.let { t ->
                checked = t.groupValues[1] != " "
                text = t.groupValues[2]
            }

            // 收集缩进更深的行作为子内容（含其间的空行）
            val child = ArrayList<Line>()
            var j = i + 1
            while (j < lines.size) {
                val l = lines[j]
                if (l.blank) {
                    var k = j + 1
                    while (k < lines.size && lines[k].blank) k++
                    if (k < lines.size && indentOf(lines[k].text) >= base + 2) {
                        for (b in j until k) child += lines[b]
                        j = k
                        continue
                    }
                    break
                }
                if (indentOf(l.text) >= base + 2) { child += l; j++ } else break
            }
            val strip = child.filter { !it.blank }.minOfOrNull { indentOf(it.text) } ?: 0
            val itemLines = ArrayList<Line>(child.size + 1)
            itemLines += Line(lines[i].no, text)
            child.forEach { itemLines += Line(it.no, it.text.drop(minOf(strip, indentOf(it.text)))) }
            items += ListItem(parseBlocks(itemLines, depth + 1), checked, lines[i].no)
            i = j

            // 项与项之间允许空行：下一个非空行还是同一列表的项就接着读
            var k = i
            while (k < lines.size && lines[k].blank) k++
            if (k > i) {
                val next = if (k < lines.size) matchItem(lines[k].text) else null
                if (next != null && next.indent >= base && next.indent < base + 2 && next.ordered == first.ordered && !RULE.matches(lines[k].text)) i = k else break
            }
        }
        return Block.ListBlock(first.ordered, first.number, items) to i
    }

    // ---------------------------------------------------------------- 行内

    private class InlineParser(private val src: String) {
        private val out = ArrayList<Inline>()
        private val buf = StringBuilder()

        fun parse(): List<Inline> {
            var i = 0
            while (i < src.length) {
                i = step(i)
            }
            flush()
            return out
        }

        private fun flush() {
            if (buf.isNotEmpty()) {
                out += Inline.Text(buf.toString())
                buf.setLength(0)
            }
        }

        private fun step(i: Int): Int {
            val c = src[i]
            when {
                c == '\\' && i + 1 < src.length && src[i + 1] in ESCAPABLE -> {
                    buf.append(src[i + 1])
                    return i + 2
                }
                c == '`' -> return code(i)
                c == '!' && i + 1 < src.length && src[i + 1] == '[' -> link(i + 1)?.let { return it }
                c == '[' -> link(i)?.let { return it }
                c == '<' -> autolink(i)?.let { return it }
                c == '*' || c == '_' -> emphasis(i)?.let { return it }
                c == '~' && src.startsWith("~~", i) -> strike(i)?.let { return it }
                c == 'h' && (src.startsWith("http://", i) || src.startsWith("https://", i)) &&
                    (i == 0 || !src[i - 1].isLetterOrDigit()) -> bareUrl(i)?.let { return it }
            }
            buf.append(c)
            return i + 1
        }

        private fun code(i: Int): Int {
            var n = 0
            while (i + n < src.length && src[i + n] == '`') n++
            val ticks = "`".repeat(n)
            var from = i + n
            while (true) {
                val at = src.indexOf(ticks, from)
                if (at < 0) { buf.append(ticks); return i + n }
                // 结束的反引号串必须正好 n 个
                val after = at + n
                if ((at > 0 && src[at - 1] == '`') || (after < src.length && src[after] == '`')) {
                    from = at + 1
                    while (from < src.length && src[from] == '`') from++
                    continue
                }
                var body = src.substring(i + n, at).replace('\n', ' ')
                if (body.length >= 2 && body.startsWith(" ") && body.endsWith(" ") && body.isNotBlank()) body = body.substring(1, body.length - 1)
                flush()
                out += Inline.Code(body)
                return after
            }
        }

        private fun findMatching(from: Int, open: Char, close: Char): Int {
            var depth = 0
            var i = from
            val limit = minOf(src.length, from + MAX_SPAN)
            while (i < limit) {
                val c = src[i]
                if (c == '\\') { i += 2; continue }
                if (c == open) depth++
                else if (c == close) { depth--; if (depth == 0) return i }
                i++
            }
            return -1
        }

        private fun safeUrl(raw: String): String? {
            var dest = raw.trim()
            if (dest.startsWith("<")) dest = dest.removePrefix("<").substringBefore('>')
            dest = dest.substringBefore(' ').substringBefore('\n').trim()
            if (dest.isEmpty()) return null
            return dest.takeIf { d -> SAFE_SCHEMES.any { d.startsWith(it, ignoreCase = true) } }
        }

        private fun link(open: Int): Int? {
            val close = findMatching(open, '[', ']')
            if (close < 0 || close + 1 >= src.length || src[close + 1] != '(') return null
            val end = findMatching(close + 1, '(', ')')
            if (end < 0) return null
            val label = src.substring(open + 1, close)
            val url = safeUrl(src.substring(close + 2, end))
            flush()
            val children = if (label.isBlank()) listOf(Inline.Text(url ?: "")) else InlineParser(label).parse()
            if (url == null) out += children else out += Inline.Link(children, url)
            return end + 1
        }

        private fun autolink(i: Int): Int? {
            val end = src.indexOf('>', i + 1)
            if (end < 0) return null
            val inner = src.substring(i + 1, end)
            if (inner.any { it.isWhitespace() }) return null
            val url = SAFE_SCHEMES.firstOrNull { inner.startsWith(it, ignoreCase = true) }?.let { inner } ?: return null
            flush()
            out += Inline.Link(listOf(Inline.Text(url)), url)
            return end + 1
        }

        private fun bareUrl(i: Int): Int? {
            var end = i
            while (end < src.length && !src[end].isWhitespace() && src[end] != '<' && src[end] != '>') end++
            while (end > i && src[end - 1] in ".,;:!?)]}'\"，。；：！？）】") end--
            if (end - i <= "https://".length) return null
            val url = src.substring(i, end)
            flush()
            out += Inline.Link(listOf(Inline.Text(url)), url)
            return end
        }

        private fun strike(i: Int): Int? {
            val end = src.indexOf("~~", i + 2)
            if (end < 0 || end == i + 2 || end - i > MAX_SPAN) return null
            val inner = src.substring(i + 2, end)
            if (inner.first().isWhitespace() || inner.last().isWhitespace()) return null
            flush()
            out += Inline.Strike(InlineParser(inner).parse())
            return end + 2
        }

        private fun emphasis(i: Int): Int? {
            val c = src[i]
            var run = 0
            while (i + run < src.length && src[i + run] == c) run++
            if (run > 3) {
                buf.append(src, i, i + run)
                return i + run
            }
            val after = i + run
            if (after >= src.length || src[after].isWhitespace()) return null
            // _ 不在单词中间开启（snake_case）
            if (c == '_' && i > 0 && src[i - 1].isLetterOrDigit()) return null
            val close = findCloser(c, run, after) ?: return null
            val inner = src.substring(after, close)
            flush()
            val children = InlineParser(inner).parse()
            out += when (run) {
                1 -> Inline.Italic(children)
                2 -> Inline.Bold(children)
                else -> Inline.Bold(listOf(Inline.Italic(children)))
            }
            return close + run
        }

        /** 找与开启标记长度相同的结束标记：前一个字符不是空白，且这一串正好 [run] 个。 */
        private fun findCloser(c: Char, run: Int, from: Int): Int? {
            var j = from
            val limit = minOf(src.length, from + MAX_SPAN)
            while (j < limit) {
                if (src[j] == '\\') { j += 2; continue }
                if (src[j] == '`') {
                    // 跳过行内代码里的标记
                    var n = 0
                    while (j + n < src.length && src[j + n] == '`') n++
                    val end = src.indexOf("`".repeat(n), j + n)
                    j = if (end < 0) j + n else end + n
                    continue
                }
                if (src[j] == c) {
                    var n = 0
                    while (j + n < src.length && src[j + n] == c) n++
                    val prev = src[j - 1]
                    if (n == run && !prev.isWhitespace() && j > from) {
                        val next = if (j + n < src.length) src[j + n] else ' '
                        if (c != '_' || !next.isLetterOrDigit()) return j
                    }
                    j += n
                    continue
                }
                j++
            }
            return null
        }
    }
}
