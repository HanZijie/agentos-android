package org.agentos.sample.notes.data

/** 与界面无关的文本工具：标题推导、摘要、标签规范化。纯 Kotlin。 */
object NoteText {
    /** 最多取 [max] 个字符，不把代理对（emoji）劈开。 */
    fun safeTake(text: String, max: Int): String {
        if (text.length <= max) return text
        var end = max
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    /** 正文首个非空行，去掉标题 / 列表 / 引用 / 任务框这类 Markdown 前缀，最多 80 个字符。 */
    fun deriveTitle(content: String): String {
        val line = content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("```") } ?: return ""
        // 只看行首一小段：前缀正则带重复分组，超长的病态行会栈溢出
        val head = safeTake(line, 240)
        val stripped = head
            .replace(Regex("^(#{1,6}\\s+|>+\\s*|[-*+]\\s+(\\[[ xX]]\\s+)?|\\d{1,9}[.)]\\s+)+"), "")
            .trim()
        return safeTake(stripped.ifEmpty { head }, 80)
    }

    /** 把换行 / 制表符换成空格并压缩连续空白，用于一行摘要。 */
    fun collapseWhitespace(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** 正文前 [max] 个字符的一行摘要（不超过 [max]，被截断时以 … 结尾）。 */
    fun summary(content: String, max: Int = 200): String {
        // 只取前面一段再压缩，避免对超长正文做整段正则
        val head = collapseWhitespace(safeTake(content, max * 3 + 16))
        return if (head.length <= max) head else safeTake(head, max - 1) + "…"
    }

    /**
     * 标签规范化：去掉首尾空白和开头的 #，丢弃空标签，按大小写不敏感去重（保留第一次出现的写法）。
     * 不合法（含逗号 / 换行、太长、太多）抛 [NoteException]。
     */
    fun normalizeTags(raw: List<String>): List<String> {
        val result = LinkedHashMap<String, String>()
        for (item in raw) {
            val tag = item.trim().trimStart('#').trim()
            if (tag.isEmpty()) continue
            if (tag.any { it == ',' || it == '\n' || it == '\r' || it == '，' }) {
                throw NoteException(NoteException.Kind.INVALID, "Tag \"${safeTake(tag, 20)}\" must not contain commas or line breaks.")
            }
            if (tag.length > NoteLimits.MAX_TAG_CHARS) {
                throw NoteException(NoteException.Kind.INVALID, "Tag \"${safeTake(tag, 20)}…\" is longer than ${NoteLimits.MAX_TAG_CHARS} characters.")
            }
            result.putIfAbsent(tag.lowercase(), tag)
        }
        if (result.size > NoteLimits.MAX_TAGS) {
            throw NoteException(NoteException.Kind.INVALID, "A note can have at most ${NoteLimits.MAX_TAGS} tags.")
        }
        return result.values.toList()
    }

    /** 筛选用的标签比较键。 */
    fun tagKey(tag: String): String = tag.trim().trimStart('#').trim().lowercase()
}
