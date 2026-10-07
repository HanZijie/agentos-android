package org.agentos.sample.notes.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.agentos.sample.notes.R
import org.agentos.sample.notes.data.Note
import org.agentos.sample.notes.data.NoteColor
import org.agentos.sample.notes.data.NoteStatus
import org.agentos.sample.notes.markdown.MarkdownParser
import org.agentos.sample.notes.ui.Scope
import org.agentos.sample.notes.ui.components.TagPill
import org.agentos.sample.notes.ui.components.cardBorderColor
import org.agentos.sample.notes.ui.components.formatNoteTime
import org.agentos.sample.notes.ui.theme.accentColor
import org.agentos.sample.notes.ui.theme.containerColor

/** 卡片上的摘要：去掉 Markdown 标记的正文开头；开头与标题重复的部分不再重复显示。 */
internal fun cardSummary(note: Note): String {
    val plain = MarkdownParser.plainText(note.content, 180)
    val title = note.displayTitle
    return if (title.isNotEmpty() && plain.startsWith(title)) plain.removePrefix(title).trimStart(' ', '\n', '·', '-', ':', '：') else plain
}

internal class TaskStats(val done: Int, val total: Int)

internal fun taskStats(content: String): TaskStats {
    var done = 0
    var total = 0
    var seen = 0
    for (line in content.lineSequence()) {
        if (seen++ > 400) break
        val t = line.trimStart()
        if (t.length >= 5 && (t[0] == '-' || t[0] == '*' || t[0] == '+') && t[1] == ' ' && t[2] == '[' && t[4] == ']') {
            when (t[3]) {
                ' ' -> total++
                'x', 'X' -> { total++; done++ }
            }
        }
    }
    return TaskStats(done, total)
}

/**
 * 备忘录卡片：颜色底、衬线标题、摘要、任务进度、标签、更新时间。点击打开，长按弹出操作菜单。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun NoteCard(
    note: Note,
    scope: Scope,
    grid: Boolean,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onColor: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    val summary = remember(note.id, note.revision) { cardSummary(note) }
    val tasks = remember(note.id, note.revision) { taskStats(note.content) }
    val title = note.displayTitle.ifEmpty { stringResource(R.string.editor_untitled) }

    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(note.color.containerColor())
                .border(1.dp, cardBorderColor(note.color), shape)
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .padding(horizontal = 15.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (note.pinned && note.status == NoteStatus.ACTIVE) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.PushPin,
                        contentDescription = stringResource(R.string.pinned_mark),
                        modifier = Modifier.padding(top = 2.dp).size(16.dp),
                        tint = if (note.color == NoteColor.DEFAULT) scheme.primary else note.color.accentColor(),
                    )
                }
            }
            if (summary.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface.copy(alpha = 0.72f),
                    maxLines = if (grid) 7 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tasks.total > 0) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Checklist, null, Modifier.size(15.dp), tint = scheme.primary)
                    Spacer(Modifier.width(5.dp))
                    Text("${tasks.done}/${tasks.total}", style = MaterialTheme.typography.labelSmall, color = scheme.onSurface.copy(alpha = 0.7f))
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(scheme.onSurface.copy(alpha = 0.1f)),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(tasks.done.toFloat() / tasks.total).height(4.dp).clip(RoundedCornerShape(2.dp)).background(scheme.primary),
                        )
                    }
                }
            }
            if (note.tags.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    note.tags.take(3).forEach { TagPill(it) }
                    if (note.tags.size > 3) TagPill("+${note.tags.size - 3}", prefix = "")
                }
            }
            Spacer(Modifier.height(10.dp))
            val stamp = if (note.isTrashed && note.trashedAt != null) {
                stringResource(R.string.trashed_at, formatNoteTime(context, note.trashedAt))
            } else {
                formatNoteTime(context, note.updatedAt)
            }
            Text(stamp, style = MaterialTheme.typography.labelSmall, color = scheme.onSurface.copy(alpha = 0.5f))
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            shape = RoundedCornerShape(18.dp),
            containerColor = scheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
        ) {
            fun item(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, destructive: Boolean = false, action: () -> Unit) = Triple(label, icon, Pair(destructive, action))
            val items = when (scope) {
                Scope.NOTES -> listOf(
                    item(if (note.pinned) R.string.menu_unpin else R.string.menu_pin, Icons.Rounded.PushPin, action = onPin),
                    item(R.string.menu_color, Icons.Rounded.Palette, action = onColor),
                    item(R.string.menu_archive, Icons.Rounded.Archive, action = onArchive),
                    item(R.string.menu_trash, Icons.Rounded.Delete, destructive = true, action = onTrash),
                )
                Scope.ARCHIVE -> listOf(
                    item(R.string.menu_color, Icons.Rounded.Palette, action = onColor),
                    item(R.string.menu_unarchive, Icons.Rounded.Unarchive, action = onUnarchive),
                    item(R.string.menu_trash, Icons.Rounded.Delete, destructive = true, action = onTrash),
                )
                Scope.TRASH -> listOf(
                    item(R.string.menu_restore, Icons.Rounded.Restore, action = onRestore),
                    item(R.string.menu_delete_forever, Icons.Rounded.DeleteForever, destructive = true, action = onDeleteForever),
                )
            }
            items.forEach { (label, icon, rest) ->
                val color = if (rest.first) scheme.error else scheme.onSurface
                DropdownMenuItem(
                    text = { Text(stringResource(label), color = color) },
                    leadingIcon = { Icon(icon, null, tint = color) },
                    onClick = {
                        menuOpen = false
                        rest.second()
                    },
                )
            }
        }
    }
}
