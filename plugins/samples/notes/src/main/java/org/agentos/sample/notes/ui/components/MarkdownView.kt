package org.agentos.sample.notes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.agentos.sample.notes.markdown.Block
import org.agentos.sample.notes.markdown.Inline
import org.agentos.sample.notes.markdown.ListItem
import org.agentos.sample.notes.markdown.MarkdownParser

/**
 * Markdown 预览。解析在 markdown 包（纯 Kotlin），这里只负责把 AST 画出来。
 * 任务列表的复选框可以点：回调 [onToggleTask] 带该项在源文本里的行号。
 */
@Composable
fun MarkdownView(
    source: String,
    modifier: Modifier = Modifier,
    onToggleTask: ((line: Int) -> Unit)? = null,
) {
    val blocks = remember(source) { MarkdownParser.parse(source) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { RenderBlock(it, onToggleTask, depth = 0) }
    }
}

@Composable
private fun RenderBlock(block: Block, onToggleTask: ((Int) -> Unit)?, depth: Int) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    when (block) {
        is Block.Heading -> {
            val style = when (block.level) {
                1 -> type.headlineMedium
                2 -> type.headlineSmall
                3 -> type.titleLarge
                else -> type.titleMedium
            }
            Text(
                inlineText(block.inlines, style.copy(color = scheme.onSurface)),
                style = style,
                color = scheme.onSurface,
                modifier = Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp),
            )
        }
        is Block.Paragraph -> Text(inlineText(block.inlines, type.bodyLarge), style = type.bodyLarge, color = scheme.onSurface)
        is Block.CodeBlock -> CodeBlock(block)
        is Block.Quote -> {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(scheme.primary.copy(alpha = 0.55f)))
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.blocks.forEach { RenderBlock(it, onToggleTask, depth + 1) }
                }
            }
        }
        is Block.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            block.items.forEachIndexed { index, item -> ListRow(block, item, index, onToggleTask, depth) }
        }
        Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp), color = scheme.outlineVariant)
    }
}

@Composable
private fun ListRow(list: Block.ListBlock, item: ListItem, index: Int, onToggleTask: ((Int) -> Unit)?, depth: Int) {
    val scheme = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyLarge
    Row(verticalAlignment = Alignment.Top) {
        val markerModifier = Modifier.width(if (list.ordered) 30.dp else 24.dp)
        when {
            item.checked != null -> TaskBox(
                checked = item.checked,
                modifier = Modifier.padding(top = 0.dp, end = 10.dp),
                onClick = onToggleTask?.let { toggle -> { toggle(item.line) } },
            )
            list.ordered -> Text("${list.start + index}.", style = body, color = scheme.primary, modifier = markerModifier, fontWeight = FontWeight.SemiBold)
            else -> Box(markerModifier, contentAlignment = Alignment.CenterStart) {
                Text(if (depth % 2 == 0) "•" else "◦", style = body, color = scheme.primary)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val struck = item.checked == true
            item.blocks.forEach { b ->
                if (b is Block.Paragraph && struck) {
                    Text(
                        inlineText(b.inlines, body),
                        style = body.copy(textDecoration = TextDecoration.LineThrough),
                        color = scheme.onSurfaceVariant,
                    )
                } else {
                    RenderBlock(b, onToggleTask, depth + 1)
                }
            }
        }
    }
}

@Composable
private fun TaskBox(checked: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (checked) scheme.primary else Color.Transparent)
            .border(1.8.dp, if (checked) scheme.primary else scheme.outline, RoundedCornerShape(6.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Checkbox, onClick = onClick)
                } else {
                    Modifier.semantics { role = Role.Checkbox }
                },
            )
            .semantics { stateDescription = if (checked) "checked" else "unchecked" },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Rounded.Check, null, Modifier.size(15.dp), tint = scheme.onPrimary)
    }
}

@Composable
private fun CodeBlock(block: Block.CodeBlock) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerHigh)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (block.language.isNotBlank()) {
            Text(block.language, style = MaterialTheme.typography.labelSmall, color = scheme.primary)
            Spacer(Modifier.height(4.dp))
        }
        Text(
            block.code.ifEmpty { " " },
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp),
            color = scheme.onSurface,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

/** 把行内元素变成带样式的 AnnotatedString：粗斜体、删除线、等宽代码（带底色）、可点击的链接。 */
@Composable
private fun inlineText(inlines: List<Inline>, base: TextStyle): AnnotatedString {
    val scheme = MaterialTheme.colorScheme
    val codeBg = scheme.onSurface.copy(alpha = 0.09f)
    val linkColor = scheme.primary
    return remember(inlines, base, codeBg, linkColor) {
        buildAnnotatedString { appendInlines(inlines, codeBg, linkColor) }
    }
}

private fun AnnotatedString.Builder.appendInlines(inlines: List<Inline>, codeBg: Color, linkColor: Color) {
    for (inline in inlines) {
        when (inline) {
            is Inline.Text -> append(inline.text)
            is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlines(inline.children, codeBg, linkColor) }
            is Inline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(inline.children, codeBg, linkColor) }
            is Inline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(inline.children, codeBg, linkColor) }
            is Inline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.9.em, background = codeBg)) { append(inline.text) }
            is Inline.Link -> withLink(
                LinkAnnotation.Url(
                    inline.url,
                    TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                ),
            ) { appendInlines(inline.children, codeBg, linkColor) }
        }
    }
}
