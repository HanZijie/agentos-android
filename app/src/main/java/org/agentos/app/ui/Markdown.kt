package org.agentos.app.ui

/**
 * A small Markdown subset for agent replies, parsed into plain text plus style ranges. Pure Kotlin, no
 * Android types (MarkdownTest); [MarkdownSpans] turns the result into a Spannable for a TextView.
 *
 * Blocks: fenced code (``` or ~~~, any indentation), ATX headings (# … ######), unordered lists (- * +,
 * nested by indentation, task boxes), ordered lists (1. / 1)), continuation lines under a list item,
 * block quotes (>), thematic breaks (--- *** ___). Inline: **bold** / __bold__, *italic* / _italic_
 * (no intraword `_`), ***both***, ~~strike~~, `code`, [text](https://…), <https://…>, bare http(s) URLs,
 * backslash escapes. Not supported (shown as written): tables, HTML, images, setext headings, reference
 * links, emphasis spanning lines. Line breaks are kept as written (chat style); blank-line runs collapse to one.
 *
 * Links: only http and https become links; anything else (javascript:, intent:, file:, …) stays literal text.
 *
 * Streaming ([render] with streaming = true): the text is a prefix of the final reply, so markers at its
 * end are pending, not literal. On the last line an unclosed `**`, `*`, `_`, `~~` or `` ` `` styles the rest
 * of the line (it will most likely close); a marker with nothing after it yet (a trailing `*`, `` ` ``, `[`,
 * `\`, a lone `#`, `-` or `>` line, an opening fence) is hidden until the next chunk shows what it is; a
 * link whose `(url)` has not arrived shows only its text, and a bare URL still arriving is not tappable.
 * So raw markers do not flash on screen and styles do not toggle while the reply grows. An unclosed code
 * fence is a code block to the end of the text (as in CommonMark), so it does not flip either. Once
 * streaming ends, markers that never closed are shown literally.
 */
object Markdown {
    enum class Kind { BOLD, ITALIC, STRIKE, CODE, CODE_BLOCK, HEADING, LIST, QUOTE, RULE, LINK }

    data class Span(
        val kind: Kind,
        val start: Int,
        val end: Int,
        /** HEADING: 1..6. LIST: nesting depth 0..3. */
        val level: Int = 0,
        /** LINK: an http(s) URL. */
        val url: String? = null,
        /** LIST: the marker whose width is the hanging indent ("• ", "12. "). */
        val marker: String? = null,
        /** LIST: true for an item line (starts with [marker]), false for a continuation line under it. */
        val hang: Boolean = true,
    )

    data class Rendered(val text: String, val spans: List<Span>) {
        fun spans(kind: Kind): List<Span> = spans.filter { it.kind == kind }
        fun textOf(span: Span): String = text.substring(span.start, span.end)
    }

    /** True for a URL the conversation screen may offer to open (after asking the user). */
    fun isWebUrl(url: String): Boolean = url.length > 8 && WEB_URL.matches(url)

    fun render(src: String, streaming: Boolean = false): Rendered {
        val b = Builder()
        val lines = src.replace("\r\n", "\n").split('\n')
        val listIndents = ArrayList<Int>()
        var lastItem: Span? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val tail = streaming && i == lines.size - 1

            val fence = fenceOpen(line)
            if (fence != null) {
                val (indent, marker) = fence
                val body = ArrayList<String>()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    if (isFenceClose(lines[j], marker)) {
                        closed = true
                        break
                    }
                    body += dropIndent(lines[j], indent)
                    j++
                }
                // while streaming, the last body line may be the closing fence being typed ("`", "``")
                if (!closed && streaming && body.isNotEmpty() && PARTIAL_FENCE.matches(body.last())) body.removeAt(body.lastIndex)
                if (body.isNotEmpty()) b.raw(body.joinToString("\n")) { s, e -> Span(Kind.CODE_BLOCK, s, e) }
                lastItem = null
                listIndents.clear()
                i = if (closed) j + 1 else j
                continue
            }
            if (tail && PARTIAL_TAIL.matches(line)) break
            if (line.isBlank()) {
                b.blank()
                i++
                continue
            }

            val heading = HEADING.matchEntire(line)
            val quote = if (heading == null) QUOTE.matchEntire(line) else null
            when {
                heading != null -> {
                    val level = heading.groupValues[1].length
                    b.line(heading.groupValues[2], tail) { s, e -> listOf(Span(Kind.HEADING, s, e, level = level)) }
                    lastItem = null
                    listIndents.clear()
                }
                RULE.matches(line) -> {
                    b.raw(" ") { s, e -> Span(Kind.RULE, s, e) }
                    lastItem = null
                    listIndents.clear()
                }
                quote != null -> {
                    var content = quote.groupValues[1]
                    while (true) content = QUOTE.matchEntire(content)?.groupValues?.get(1) ?: break
                    b.line(content, tail) { s, e -> listOf(Span(Kind.QUOTE, s, e)) }
                    lastItem = null
                    listIndents.clear()
                }
                else -> {
                    val item = listItem(line, listIndents)
                    val prev = lastItem
                    if (item != null) {
                        val (level, marker, content) = item
                        lastItem = b.line(content, tail, prefix = marker) { s, e ->
                            listOf(Span(Kind.LIST, s, e, level = level, marker = marker))
                        }.last()
                    } else if (prev != null && width(line.takeWhile { it == ' ' || it == '\t' }) >= 2) {
                        // continuation line of the list item above
                        b.line(line.trim(), tail) { s, e ->
                            listOf(Span(Kind.LIST, s, e, level = prev.level, marker = prev.marker, hang = false))
                        }
                    } else {
                        lastItem = null
                        listIndents.clear()
                        b.line(line.trim(), tail)
                    }
                }
            }
            i++
        }
        return b.build()
    }

    // ---------------------------------------------------------------- blocks

    private const val MAX_LEVEL = 3
    private val BULLETS = listOf("•", "◦", "▪", "▫")

    private val FENCE = Regex("^([ \\t]*)(`{3,}|~{3,})(.*)$")
    private val PARTIAL_FENCE = Regex("^[ \\t]*(`{1,2}|~{1,2})[ \\t]*$")
    /**
     * A last line that is only the start of a marker: hidden while streaming. Not "1.": the digit was
     * already on screen as text, and "1. item" only adds to it.
     */
    private val PARTIAL_TAIL = Regex("^[ \\t]*(`{1,2}|~{1,2}|#{1,6}|[-*+_]|>|([-*_])[ \\t]*\\2)[ \\t]*$")
    private val HEADING = Regex("^ {0,3}(#{1,6})[ \\t]+(.*?)(?:[ \\t]+#+)?[ \\t]*$")
    private val RULE = Regex("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$")
    private val QUOTE = Regex("^ {0,3}>[ \\t]?(.*)$")
    private val BULLET = Regex("^([ \\t]*)([-*+])[ \\t]+(.*)$")
    private val ORDERED = Regex("^([ \\t]*)(\\d{1,9})[.)][ \\t]+(.*)$")
    private val TASK = Regex("^\\[([ xX])][ \\t]+(.*)$")
    private val WEB_URL = Regex("^(?i)https?://[^\\s<>\"`]+$")

    /** (indent, fence) of an opening fence line; a backtick fence's info string may not contain backticks. */
    private fun fenceOpen(line: String): Pair<Int, String>? {
        val m = FENCE.matchEntire(line) ?: return null
        val marker = m.groupValues[2]
        if (marker[0] == '`' && '`' in m.groupValues[3]) return null // ```inline``` on one line
        return m.groupValues[1].length to marker
    }

    private fun isFenceClose(line: String, fence: String): Boolean {
        val t = line.trim()
        return t.length >= fence.length && t.all { it == fence[0] }
    }

    /** (level, marker, content) of a list item line; keeps [indents] as the stack of open list levels. */
    private fun listItem(line: String, indents: MutableList<Int>): Triple<Int, String, String>? {
        val bullet = BULLET.matchEntire(line)
        val ordered = if (bullet == null) ORDERED.matchEntire(line) else null
        val m = bullet ?: ordered ?: return null
        val indent = width(m.groupValues[1])
        while (indents.isNotEmpty() && indents.last() > indent) indents.removeAt(indents.lastIndex)
        if (indents.isEmpty() || indents.last() < indent) indents += indent
        val level = (indents.size - 1).coerceIn(0, MAX_LEVEL)
        if (ordered != null) return Triple(level, ordered.groupValues[2] + ". ", ordered.groupValues[3])
        val content = bullet!!.groupValues[3]
        val task = TASK.matchEntire(content)
        return if (task != null) {
            Triple(level, (if (task.groupValues[1].isBlank()) "☐" else "☑") + " ", task.groupValues[2])
        } else {
            Triple(level, BULLETS[level] + " ", content)
        }
    }

    private fun dropIndent(line: String, indent: Int): String {
        var n = 0
        while (n < indent && n < line.length && (line[n] == ' ' || line[n] == '\t')) n++
        return line.substring(n)
    }

    private fun width(ws: String): Int = ws.sumOf { if (it == '\t') 4 else 1 }

    private class Builder {
        private val out = StringBuilder()
        private val spans = ArrayList<Span>()
        private var any = false
        private var blank = false

        fun blank() {
            if (any) blank = true
        }

        private fun newLine() {
            if (any) {
                out.append('\n')
                if (blank) out.append('\n')
            }
            any = true
            blank = false
        }

        /** One line of inline Markdown; [block] makes the spans covering the whole line (empty ones dropped). */
        fun line(
            inline: String,
            openEnd: Boolean,
            prefix: String = "",
            block: (Int, Int) -> List<Span> = { _, _ -> emptyList() },
        ): List<Span> {
            newLine()
            val start = out.length
            out.append(prefix)
            Inline(inline).parse(0, inline.length, openEnd, out, spans)
            return block(start, out.length).filter { it.end > it.start }.also { spans += it }
        }

        fun raw(text: String, block: (Int, Int) -> Span) {
            newLine()
            val start = out.length
            out.append(text)
            spans += block(start, out.length)
        }

        fun build() = Rendered(out.toString(), spans.toList())
    }

    // ---------------------------------------------------------------- inline

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>"

    private fun isAsciiAlnum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private class Inline(val src: String) {
        /** Parses src[from, to) into [out]. [open]: the range runs to the end of a still-streaming text. */
        fun parse(from: Int, to: Int, open: Boolean, out: StringBuilder, spans: MutableList<Span>) {
            var i = from
            while (i < to) {
                val c = src[i]
                i = when {
                    c == '\\' && i + 1 < to && src[i + 1] in ESCAPABLE -> {
                        out.append(src[i + 1])
                        i + 2
                    }
                    c == '\\' && open && i == to - 1 -> to // may escape the next character
                    c == '`' -> code(i, to, open, out, spans)
                    c == '[' -> link(i, to, open, out, spans)
                    c == '<' -> autolink(i, to, open, out, spans)
                    (c == 'h' || c == 'H') && bareUrlStart(i) -> bareUrl(i, to, open, out, spans)
                    c == '*' || c == '_' -> emphasis(i, to, open, out, spans)
                    c == '~' && i + 1 < to && src[i + 1] == '~' -> strike(i, to, open, out, spans)
                    c == '~' && open && i == to - 1 -> to // may become "~~"
                    else -> {
                        out.append(c)
                        i + 1
                    }
                }
            }
        }

        private fun run(i: Int, to: Int, c: Char): Int {
            var j = i
            while (j < to && src[j] == c) j++
            return j - i
        }

        /** Start of a backtick run of exactly [len] in [from, to), or -1. */
        private fun codeClose(from: Int, to: Int, len: Int): Int {
            var j = from
            while (j < to) {
                if (src[j] == '`') {
                    val r = run(j, to, '`')
                    if (r == len) return j
                    j += r
                } else {
                    j++
                }
            }
            return -1
        }

        private inline fun styled(kind: Kind, out: StringBuilder, spans: MutableList<Span>, url: String? = null, body: () -> Unit) {
            val start = out.length
            body()
            if (out.length > start) spans += Span(kind, start, out.length, url = url)
        }

        private fun code(i: Int, to: Int, open: Boolean, out: StringBuilder, spans: MutableList<Span>): Int {
            val r = run(i, to, '`')
            val close = codeClose(i + r, to, r)
            if (close >= 0) {
                var code = src.substring(i + r, close)
                if (code.length >= 2 && code.startsWith(' ') && code.endsWith(' ') && code.isNotBlank()) code = code.substring(1, code.length - 1)
                styled(Kind.CODE, out, spans) { out.append(code) }
                return close + r
            }
            if (open) {
                // unclosed at the streaming tail: what has arrived is code so far
                styled(Kind.CODE, out, spans) { out.append(src, i + r, to) }
                return to
            }
            out.append(src, i, i + r)
            return i + r
        }

        private fun matching(i: Int, to: Int, openCh: Char, closeCh: Char): Int {
            var depth = 0
            var j = i
            while (j < to) {
                val ch = src[j]
                when {
                    ch == '\\' -> j++
                    ch == openCh -> depth++
                    ch == closeCh -> if (--depth == 0) return j
                }
                j++
            }
            return -1
        }

        private fun link(i: Int, to: Int, open: Boolean, out: StringBuilder, spans: MutableList<Span>): Int {
            val rb = matching(i, to, '[', ']')
            if (rb < 0) {
                if (open) {
                    // "[text" while streaming: show the text, hide "["
                    parse(i + 1, to, true, out, spans)
                    return to
                }
                out.append('[')
                return i + 1
            }
            if (rb + 1 < to && src[rb + 1] == '(') {
                val rp = matching(rb + 1, to, '(', ')')
                if (rp < 0) {
                    if (open) {
                        // "[text](htt" while streaming: show the text, not the half URL
                        parse(i + 1, rb, false, out, spans)
                        return to
                    }
                    out.append('[')
                    return i + 1
                }
                val url = src.substring(rb + 2, rp).trim().substringBefore(' ').removeSurrounding("<", ">")
                if (!isWebUrl(url)) {
                    out.append('[') // not a web link (javascript:, intent:, …): shown as written
                    return i + 1
                }
                val start = out.length
                parse(i + 1, rb, false, out, spans)
                if (out.length == start) out.append(url)
                spans += Span(Kind.LINK, start, out.length, url = url)
                return rp + 1
            }
            if (open && rb + 1 == to) {
                // "[text]" at the tail: "(url)" may follow
                parse(i + 1, rb, false, out, spans)
                return to
            }
            out.append('[')
            return i + 1
        }

        private fun autolink(i: Int, to: Int, open: Boolean, out: StringBuilder, spans: MutableList<Span>): Int {
            val gt = src.indexOf('>', i + 1)
            if (gt in 0 until to) {
                val url = src.substring(i + 1, gt)
                if (isWebUrl(url)) {
                    styled(Kind.LINK, out, spans, url) { out.append(url) }
                    return gt + 1
                }
            } else if (open) {
                val rest = src.substring(i + 1, to).lowercase()
                if (rest.startsWith("http") || "http".startsWith(rest)) {
                    // "<https://exa" while streaming: plain until ">" arrives
                    out.append(src, i + 1, to)
                    return to
                }
            }
            out.append('<')
            return i + 1
        }

        private fun bareUrlStart(i: Int): Boolean =
            (i == 0 || !isAsciiAlnum(src[i - 1])) &&
                (src.startsWith("https://", i, ignoreCase = true) || src.startsWith("http://", i, ignoreCase = true))

        private fun bareUrl(i: Int, to: Int, open: Boolean, out: StringBuilder, spans: MutableList<Span>): Int {
            var e = i
            while (e < to && isUrlChar(src[e])) e++
            if (open && e == to) {
                // still arriving at the streaming tail (even "https://example." may go on): plain, nothing half-done to tap
                out.append(src, i, to)
                return to
            }
            // trailing punctuation belongs to the sentence; a ")" only when unbalanced
            while (e > i) {
                val ch = src[e - 1]
                val s = src.substring(i, e)
                val drop = ch in ".,;:!?'\"*_~" || (ch == ')' && s.count { it == '(' } < s.count { it == ')' })
                if (drop) e-- else break
            }
            val url = src.substring(i, e)
            if (!isWebUrl(url)) {
                out.append(src, i, e)
                return e
            }
            styled(Kind.LINK, out, spans, url) { out.append(url) }
            return e
        }

        private fun isUrlChar(ch: Char): Boolean =
            !ch.isWhitespace() && ch !in "<>\"`" && ch.code < 0x2E80 && ch.code !in 0xFF00..0xFFEF

        private fun canOpen(c: Char, i: Int, r: Int, to: Int): Boolean {
            if (i + r >= to) return false
            val next = src[i + r]
            val prev = if (i > 0) src[i - 1] else ' '
            return !next.isWhitespace() && (c != '_' || !prev.isLetterOrDigit())
        }

        /** Where the closing delimiters for [need] start, or -1. Skips code spans and escapes. */
        private fun closer(from: Int, to: Int, c: Char, need: Int): Int {
            var j = from
            while (j < to) {
                val ch = src[j]
                when {
                    ch == '\\' -> j += 2
                    ch == '`' -> {
                        val r = run(j, to, '`')
                        val close = codeClose(j + r, to, r)
                        j = if (close >= 0) close + r else j + r
                    }
                    ch == c -> {
                        val m = run(j, to, c)
                        val after = if (j + m < to) src[j + m] else ' '
                        val canClose = j > from && !src[j - 1].isWhitespace() && (c != '_' || !after.isLetterOrDigit())
                        if (canClose && (m == need || m >= 3)) return j + m - need
                        j += m
                    }
                    else -> j++
                }
            }
            return -1
        }

        private fun emphasis(i: Int, to: Int, open: Boolean, out: StringBuilder, spans: MutableList<Span>): Int {
            val c = src[i]
            val r = run(i, to, c)
            if (!canOpen(c, i, r, to)) {
                if (open && i + r == to) return to // a marker at the very end of the streaming text: wait for more
                out.append(src, i, i + r)
                return i + r
            }
            if (r >= 3) {
                val close = closer(i + 3, to, c, 3)
                if (close >= 0 || open) {
                    val end = if (close >= 0) close else to
                    out.append(src, i, i + r - 3) // delimiters beyond three stay literal
                    styled(Kind.BOLD, out, spans) { styled(Kind.ITALIC, out, spans) { parse(i + r, end, close < 0, out, spans) } }
                    return if (close >= 0) close + 3 else to
                }
            }
            val need = if (r >= 2) 2 else 1
            val kind = if (need == 2) Kind.BOLD else Kind.ITALIC
            val close = closer(i + r, to, c, need)
            if (close >= 0) {
                out.append(src, i, i + r - need)
                styled(kind, out, spans) { parse(i + r, close, false, out, spans) }
                return close + need
            }
            if (open) {
                out.append(src, i, i + r - need)
                styled(kind, out, spans) { parse(i + r, to, true, out, spans) }
                return to
            }
            out.append(src, i, i + r)
            return i + r
        }

        private fun strike(i: Int, to: Int, open: Boolean, out: StringBuilder, spans: MutableList<Span>): Int {
            val r = run(i, to, '~')
            if (!canOpen('~', i, r, to)) {
                if (open && i + r == to) return to
                out.append(src, i, i + r)
                return i + r
            }
            val close = closer(i + r, to, '~', 2)
            if (close >= 0) {
                styled(Kind.STRIKE, out, spans) { parse(i + r, close, false, out, spans) }
                return close + 2
            }
            if (open) {
                styled(Kind.STRIKE, out, spans) { parse(i + r, to, true, out, spans) }
                return to
            }
            out.append(src, i, i + r)
            return i + r
        }
    }
}
