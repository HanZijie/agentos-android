package org.agentos.sample.notes.ui.components

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.agentos.sample.notes.R
import org.agentos.sample.notes.data.NoteColor
import org.agentos.sample.notes.ui.theme.accentColor
import org.agentos.sample.notes.ui.theme.containerColor

/** 在 [text] 里把 [ranges] 标成高亮（搜索命中）。 */
fun highlighted(text: String, ranges: List<IntRange>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    for (r in ranges) {
        val start = r.first.coerceIn(0, text.length)
        val end = (r.last + 1).coerceIn(start, text.length)
        if (end > start) addStyle(style, start, end)
    }
}

@Composable
fun highlightStyle(): SpanStyle = SpanStyle(
    background = MaterialTheme.colorScheme.secondaryContainer,
    color = MaterialTheme.colorScheme.onSecondaryContainer,
    fontWeight = FontWeight.SemiBold,
)

/** “刚刚 / 5 分钟前 / 昨天 / 10月5日”。一周内用相对时间，更早用日期，都跟随系统语言。 */
fun formatNoteTime(context: Context, millis: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - millis
    if (diff in 0 until 60_000) return context.getString(R.string.time_just_now)
    if (diff in 0 until 7 * DateUtils.DAY_IN_MILLIS) {
        return DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }
    val sameYear = java.util.Calendar.getInstance().apply { timeInMillis = millis }.get(java.util.Calendar.YEAR) ==
        java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.YEAR)
    val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or (if (sameYear) 0 else DateUtils.FORMAT_SHOW_YEAR)
    return DateUtils.formatDateTime(context, millis, flags)
}

/** 小标签胶囊：# 加标签名。[onRemove] 不为空时右边带一个 ×。 */
@Composable
fun TagPill(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    removeDescription: String? = null,
    selected: Boolean = false,
    emphasized: Boolean = false,
    prefix: String = "#",
) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        selected -> scheme.primary
        emphasized -> scheme.primaryContainer
        else -> scheme.onSurface.copy(alpha = 0.08f)
    }
    val fg = when {
        selected -> scheme.onPrimary
        emphasized -> scheme.onPrimaryContainer
        else -> scheme.onSurface.copy(alpha = 0.72f)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 9.dp, end = if (onRemove != null) 4.dp else 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$prefix$text", style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onRemove != null) {
            Box(
                Modifier.padding(start = 2.dp).size(18.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onRemove)
                    .semantics { if (removeDescription != null) contentDescription = removeDescription },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, null, Modifier.size(12.dp), tint = fg)
            }
        }
    }
}

@StringRes
fun NoteColor.labelRes(): Int = when (this) {
    NoteColor.DEFAULT -> R.string.color_default
    NoteColor.YELLOW -> R.string.color_yellow
    NoteColor.ORANGE -> R.string.color_orange
    NoteColor.RED -> R.string.color_red
    NoteColor.PURPLE -> R.string.color_purple
    NoteColor.BLUE -> R.string.color_blue
    NoteColor.TEAL -> R.string.color_teal
    NoteColor.GREEN -> R.string.color_green
    NoteColor.GRAY -> R.string.color_gray
}

/** 圆形色块：填充用卡片底色，描边用强调色；选中时画对勾。 */
@Composable
fun ColorSwatch(color: NoteColor, selected: Boolean, size: androidx.compose.ui.unit.Dp = 44.dp, onClick: (() -> Unit)? = null) {
    val fill = color.containerColor()
    val accent = color.accentColor()
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .border(BorderStroke(if (selected) 2.5.dp else 1.5.dp, if (selected) MaterialTheme.colorScheme.primary else accent.copy(alpha = 0.7f)), CircleShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.RadioButton, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, null, Modifier.size(size * 0.5f), tint = MaterialTheme.colorScheme.primary)
        } else if (color == NoteColor.DEFAULT) {
            Box(Modifier.size(size * 0.38f).clip(CircleShape).background(accent.copy(alpha = 0.35f)))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ColorPickerSheet(selected: NoteColor, onPick: (NoteColor) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.color_sheet_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(18.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                maxItemsInEachRow = 5,
            ) {
                NoteColor.entries.forEach { color ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                        ColorSwatch(color, color == selected, 48.dp) { onPick(color) }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(color.labelRes()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    cancelText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmText,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancelText, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
    )
}

/** 便签颜色的卡片底色在默认色时用的描边。 */
@Composable
fun cardBorderColor(color: NoteColor): Color =
    if (color == NoteColor.DEFAULT) MaterialTheme.colorScheme.outlineVariant else Color.Transparent

/** 反色底的圆角提示条（撤销等操作）。 */
@Composable
fun NotesSnackbar(data: androidx.compose.material3.SnackbarData) {
    val scheme = MaterialTheme.colorScheme
    androidx.compose.material3.Snackbar(
        snackbarData = data,
        shape = RoundedCornerShape(16.dp),
        containerColor = scheme.inverseSurface,
        contentColor = scheme.inverseOnSurface,
        actionColor = scheme.inversePrimary,
        dismissActionContentColor = scheme.inverseOnSurface,
    )
}
