package org.agentos.sample.notes.markdown

/** 文本编辑结果：新正文和新的选区。 */
data class EditResult(val text: String, val selStart: Int, val selEnd: Int)

enum class LineKind(val prefix: String) {
    HEADING("## "),
    BULLET("- "),
    NUMBERED("1. "),
    TASK("- [ ] "),
    QUOTE("> "),
}

/** 编辑页工具栏用的 Markdown 文本变换（纯 Kotlin，可单测）。选区用 [start, end)，start 可大于 end。 */
object MarkdownEdit {
    private val LINE_PREFIX = Regex("^(#{1,6} |[-*+] \\[[ xX]] |[-*+] |\\d{1,9}[.)] |> )")
    private val CONTINUE = Regex("^( *)([-*+] \\[[ xX]] |[-*+] |(\\d{1,9})([.)]) |> )(.*)$")

    /** 给选区加 / 去掉成对标记（粗体 `**`、斜体 `*`、行内代码 `` ` ``、删除线 `~~`）。 */
    fun wrap(text: String, start: Int, end: Int, marker: String, placeholder: String): EditResult {
        val s = minOf(start, end).coerceIn(0, text.length)
        val e = maxOf(start, end).coerceIn(0, text.length)
        val m = marker.length
        // 选区外侧已经有标记：去掉
        if (s >= m && e + m <= text.length && text.startsWith(marker, s - m) && text.startsWith(marker, e) && !doubled(text, marker, s - m, e)) {
            val out = text.substring(0, s - m) + text.substring(s, e) + text.substring(e + m)
            return EditResult(out, s - m, e - m)
        }
        // 选区本身带着标记：去掉
        if (e - s >= 2 * m && text.startsWith(marker, s) && text.startsWith(marker, e - m)) {
            val out = text.substring(0, s) + text.substring(s + m, e - m) + text.substring(e)
            return EditResult(out, s, e - 2 * m)
        }
        if (s == e) {
            val out = text.substring(0, s) + marker + placeholder + marker + text.substring(e)
            return EditResult(out, s + m, s + m + placeholder.length)
        }
        val out = text.substring(0, s) + marker + text.substring(s, e) + marker + text.substring(e)
        return EditResult(out, s + m, e + m)
    }

    /** 单个 `*` 在 `**` 里面时不算“已经是斜体”。 */
    private fun doubled(text: String, marker: String, markerStart: Int, selEnd: Int): Boolean {
        if (marker.length != 1) return false
        val c = marker[0]
        val before = markerStart > 0 && text[markerStart - 1] == c
        val after = selEnd + 1 < text.length && text[selEnd + 1] == c
        return before || after
    }

    /** 给选区覆盖的每一行加 / 去掉行首标记；已经是别的行首标记（标题、列表、引用）时先换掉。 */
    fun toggleLine(text: String, start: Int, end: Int, kind: LineKind): EditResult {
        val s = minOf(start, end).coerceIn(0, text.length)
        var e = maxOf(start, end).coerceIn(0, text.length)
        if (e > s && text[e - 1] == '\n') e--
        val lineStart = text.lastIndexOf('\n', s - 1) + 1
        val lineEnd = text.indexOf('\n', e).let { if (it < 0) text.length else it }
        val lines = text.substring(lineStart, lineEnd).split("\n")
        val allHave = lines.all { it.isNotBlank() && hasKind(it, kind) }
        var firstDelta = 0
        val rebuilt = lines.mapIndexed { index, line ->
            val bare = line.replace(LINE_PREFIX, "")
            val next = if (allHave) {
                bare
            } else {
                val p = if (kind == LineKind.NUMBERED) "${index + 1}. " else kind.prefix
                p + bare
            }
            if (index == 0) firstDelta = next.length - line.length
            next
        }
        val joined = rebuilt.joinToString("\n")
        val out = text.substring(0, lineStart) + joined + text.substring(lineEnd)
        val newStart = maxOf(lineStart, s + firstDelta)
        val newEnd = maxOf(newStart, e + (joined.length - (lineEnd - lineStart)))
        return EditResult(out, newStart, newEnd)
    }

    private fun hasKind(line: String, kind: LineKind): Boolean = when (kind) {
        LineKind.HEADING -> Regex("^#{1,6} ").containsMatchIn(line)
        LineKind.BULLET -> Regex("^[-*+] (?!\\[[ xX]] )").containsMatchIn(line)
        LineKind.NUMBERED -> Regex("^\\d{1,9}[.)] ").containsMatchIn(line)
        LineKind.TASK -> Regex("^[-*+] \\[[ xX]] ").containsMatchIn(line)
        LineKind.QUOTE -> line.startsWith("> ")
    }

    /** 围栏代码块：有选区就包起来，没有就插入空块并把光标放在里面。 */
    fun codeBlock(text: String, start: Int, end: Int): EditResult {
        val s = minOf(start, end).coerceIn(0, text.length)
        val e = maxOf(start, end).coerceIn(0, text.length)
        val lead = if (s == 0 || text[s - 1] == '\n') "" else "\n"
        val tail = if (e >= text.length || text[e] == '\n') "" else "\n"
        val inner = text.substring(s, e)
        val block = "$lead```\n$inner\n```$tail"
        val out = text.substring(0, s) + block + text.substring(e)
        val caret = s + lead.length + 4
        return EditResult(out, caret, caret + inner.length)
    }

    /** 链接：选中的文字当链接文字，没选就给个占位；返回时选中 url 部分方便直接改。 */
    fun link(text: String, start: Int, end: Int, label: String = "link"): EditResult {
        val s = minOf(start, end).coerceIn(0, text.length)
        val e = maxOf(start, end).coerceIn(0, text.length)
        val shown = if (s == e) label else text.substring(s, e)
        val url = "https://"
        val out = text.substring(0, s) + "[" + shown + "](" + url + ")" + text.substring(e)
        val urlStart = s + shown.length + 3
        return EditResult(out, urlStart, urlStart + url.length)
    }

    /** 分隔线：独占一行，前后各留一个空行。 */
    fun rule(text: String, start: Int, end: Int): EditResult {
        val s = minOf(start, end).coerceIn(0, text.length)
        val e = maxOf(start, end).coerceIn(0, text.length)
        val lead = when {
            s == 0 -> ""
            s >= 2 && text[s - 1] == '\n' && text[s - 2] == '\n' -> ""
            text[s - 1] == '\n' -> "\n"
            else -> "\n\n"
        }
        val block = "$lead---\n\n"
        val out = text.substring(0, s) + block + text.substring(e)
        val caret = s + block.length
        return EditResult(out, caret, caret)
    }

    /**
     * 在列表 / 引用行末按回车：自动续上下一项的标记；空项再按回车则结束列表。
     * 只在“光标处刚插入了一个换行”时返回结果，否则返回 null（照常处理）。
     */
    fun onEnter(oldText: String, oldSelStart: Int, oldSelEnd: Int, newText: String, newSelStart: Int): EditResult? {
        if (oldSelStart != oldSelEnd) return null
        if (newText.length != oldText.length + 1 || newSelStart != oldSelStart + 1) return null
        if (oldSelStart > oldText.length || newText[oldSelStart] != '\n') return null
        if (newText.substring(0, oldSelStart) != oldText.substring(0, oldSelStart)) return null
        val lineStart = oldText.lastIndexOf('\n', oldSelStart - 1).let { if (oldSelStart == 0) 0 else it + 1 }
        val line = oldText.substring(lineStart, oldSelStart)
        val m = CONTINUE.matchEntire(line) ?: return null
        val indent = m.groupValues[1]
        val marker = m.groupValues[2]
        val rest = m.groupValues[5]
        if (rest.isBlank()) {
            // 空项：去掉标记，结束列表
            val out = oldText.substring(0, lineStart) + oldText.substring(oldSelStart)
            return EditResult(out, lineStart, lineStart)
        }
        val next = when {
            m.groupValues[3].isNotEmpty() -> "${m.groupValues[3].toInt() + 1}${m.groupValues[4]} "
            marker.contains('[') -> marker.replace(Regex("\\[[xX]]"), "[ ]")
            else -> marker
        }
        val insert = "\n$indent$next"
        val out = oldText.substring(0, oldSelStart) + insert + oldText.substring(oldSelStart)
        val caret = oldSelStart + insert.length
        return EditResult(out, caret, caret)
    }
}
