package org.agentos.sample.notes.markdown

/** 行内元素。 */
sealed interface Inline {
    data class Text(val text: String) : Inline
    data class Bold(val children: List<Inline>) : Inline
    data class Italic(val children: List<Inline>) : Inline
    data class Strike(val children: List<Inline>) : Inline
    data class Code(val text: String) : Inline
    data class Link(val children: List<Inline>, val url: String) : Inline
}

/** 块级元素。[ListItem.line] 是该项在源文本里的行号（0 起），用来在预览里点任务复选框时回写。 */
sealed interface Block {
    data class Heading(val level: Int, val inlines: List<Inline>) : Block
    data class Paragraph(val inlines: List<Inline>) : Block
    data class CodeBlock(val language: String, val code: String) : Block
    data class Quote(val blocks: List<Block>) : Block
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<ListItem>) : Block
    data object Rule : Block
}

/** 列表项；[checked] 为 null 表示普通项，true / false 表示任务项（- [x] / - [ ]）。 */
data class ListItem(val blocks: List<Block>, val checked: Boolean?, val line: Int)
