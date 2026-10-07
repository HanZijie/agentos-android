package org.agentos.sample.notes.data

/** 一段命中片段：[text] 是展示用的一行文字（可能以 … 开头 / 结尾），[ranges] 是其中应高亮的区间。 */
data class Snippet(val text: String, val ranges: List<IntRange>)

enum class MatchField { TITLE, TAG, CONTENT }

data class SearchHit(
    val note: Note,
    val matchedIn: Set<MatchField>,
    /** 正文里的命中片段；只有标题 / 标签命中时是正文开头一段，没有高亮。 */
    val snippet: Snippet,
    /** 标题里应高亮的区间（对应 [Note.displayTitle]）。 */
    val titleRanges: List<IntRange>,
    val score: Int,
)

/** 搜索：标题 / 正文 / 标签的包含匹配（不区分大小写）。多个词用空白分隔，需要全部命中（可分布在不同字段）。 */
object NoteSearch {
    private const val BEFORE = 36
    private const val AFTER = 84

    fun terms(query: String): List<String> =
        query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    fun search(
        notes: List<Note>,
        query: String,
        tag: String? = null,
        includeTrashed: Boolean = false,
    ): List<SearchHit> {
        val terms = terms(query)
        if (terms.isEmpty()) return emptyList()
        val tagKey = tag?.let { NoteText.tagKey(it) }?.takeIf { it.isNotEmpty() }
        return notes.asSequence()
            .filter { includeTrashed || !it.isTrashed }
            .filter { tagKey == null || it.tags.any { t -> NoteText.tagKey(t) == tagKey } }
            .mapNotNull { match(it, terms, query.trim()) }
            .sortedWith(compareByDescending<SearchHit> { it.score }.thenByDescending { it.note.updatedAt })
            .toList()
    }

    private fun match(note: Note, terms: List<String>, phrase: String): SearchHit? {
        val title = note.displayTitle
        val tagsText = note.tags.joinToString("\n")
        // 每个词必须出现在 标题 / 标签 / 正文 的某一处
        for (term in terms) {
            val found = title.contains(term, ignoreCase = true) ||
                tagsText.contains(term, ignoreCase = true) ||
                note.content.contains(term, ignoreCase = true)
            if (!found) return null
        }
        val fields = LinkedHashSet<MatchField>()
        var score = 0
        val titleRanges = ranges(title, terms)
        if (titleRanges.isNotEmpty()) {
            fields += MatchField.TITLE
            score += 30 * terms.size
            if (title.contains(phrase, ignoreCase = true)) score += 70
        }
        val tagHit = note.tags.any { tag -> terms.any { tag.contains(it, ignoreCase = true) } }
        if (tagHit) {
            fields += MatchField.TAG
            score += 40
            if (note.tags.any { it.equals(phrase, ignoreCase = true) }) score += 30
        }
        val contentRanges = ranges(note.content, terms)
        if (contentRanges.isNotEmpty()) {
            fields += MatchField.CONTENT
            score += 10 + minOf(contentRanges.size, 10)
        }
        if (note.pinned) score += 1
        return SearchHit(note, fields, snippet(note.content, terms, phrase, contentRanges), titleRanges, score)
    }

    /** 全部词在 [text] 里的出现区间（合并重叠，升序）。 */
    fun ranges(text: String, terms: List<String>): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val raw = ArrayList<IntRange>()
        for (term in terms) {
            var from = 0
            while (from <= text.length - term.length) {
                val at = text.indexOf(term, from, ignoreCase = true)
                if (at < 0) break
                raw += at until at + term.length
                from = at + term.length
            }
        }
        if (raw.isEmpty()) return emptyList()
        raw.sortBy { it.first }
        val merged = ArrayList<IntRange>()
        for (r in raw) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) {
                if (r.last > last.last) merged[merged.lastIndex] = last.first..r.last
            } else {
                merged += r
            }
        }
        return merged
    }

    private fun snippet(content: String, terms: List<String>, phrase: String, contentRanges: List<IntRange>): Snippet {
        if (content.isEmpty()) return Snippet("", emptyList())
        if (contentRanges.isEmpty()) {
            val head = oneLine(NoteText.safeTake(content, AFTER + BEFORE))
            return Snippet(head.trim() + if (content.length > AFTER + BEFORE) "…" else "", emptyList())
        }
        // 优先围绕整句的出现位置，其次是第一个命中
        val phraseAt = content.indexOf(phrase, ignoreCase = true).takeIf { it >= 0 && terms.size > 1 }
        val anchor = phraseAt ?: contentRanges.first().first
        var start = maxOf(0, anchor - BEFORE)
        var end = minOf(content.length, anchor + AFTER)
        if (start > 0 && Character.isLowSurrogate(content[start])) start++
        if (end < content.length && Character.isLowSurrogate(content[end])) end--
        val body = oneLine(content.substring(start, end))
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < content.length) "…" else ""
        val shift = prefix.length - start
        val highlights = contentRanges
            .filter { it.last >= start && it.first < end }
            .map { (maxOf(it.first, start) + shift)..(minOf(it.last, end - 1) + shift) }
        return Snippet(prefix + body + suffix, highlights)
    }

    /** 逐字符把换行 / 制表符换成空格，长度不变，区间不用重算。 */
    private fun oneLine(text: String): String = buildString(text.length) {
        for (c in text) append(if (c == '\n' || c == '\r' || c == '\t') ' ' else c)
    }
}
