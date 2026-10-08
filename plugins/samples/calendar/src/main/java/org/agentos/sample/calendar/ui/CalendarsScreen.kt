package org.agentos.sample.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.agentos.sample.calendar.R
import org.agentos.sample.calendar.data.CalendarInfo
import org.agentos.sample.calendar.data.Palette

/**
 * 日历管理：显示 / 隐藏、新建（只能建本机日历）、改名改色和删除（只限本机日历，本机默认日历不能删）。
 * 账号 / 系统日历按账号分组列出，标出来源、只读和“默认写入”；它们的名字、颜色、删除归账号所在的日历 App，这里只能显示 / 隐藏。
 */
@Composable
fun CalendarsScreen(
    data: CalendarData,
    fmt: Fmt,
    vm: CalendarViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onToggle: (CalendarInfo, Boolean) -> Unit,
    onSave: (existing: CalendarInfo?, name: String, color: Int, onError: (String) -> Unit, onDone: () -> Unit) -> Unit,
    onDelete: (CalendarInfo) -> Unit,
    onSetDefault: (CalendarInfo?) -> Unit,
) {
    var dialog by remember { mutableStateOf<CalendarInfo?>(null) }
    var creating by remember { mutableStateOf(false) }
    // 日程数要查库（系统日历尤其），打开页面后在后台逐个读；数据版本变了重读
    val counts by produceState(emptyMap<String, Int>(), data.calendars, data.snapshot.version) {
        value = data.calendars.associate { it.id to vm.eventCount(it.id) }
    }
    // 分组：本机日历 / 每个账号一组（账号名 + 来源）；LOCAL 账号的系统日历归“本机”那一组
    val groups = remember(data.calendars) {
        data.calendars.groupBy { if (it.isAccountCalendar) fmt.originLabel(it) else "" }.toList().sortedBy { (k, _) -> if (k.isEmpty()) "" else "1$k" }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(stringResource(R.string.calendars_title), onBack) {
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, stringResource(R.string.settings_title)) }
                IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, stringResource(R.string.calendar_new)) }
            }
            LazyColumn(
                Modifier.weight(1f).navigationBarsPadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for ((origin, list) in groups) {
                    item(key = "h:$origin") {
                        Text(
                            origin.ifEmpty { stringResource(R.string.source_local) },
                            Modifier.padding(start = 8.dp, top = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    items(list, key = { it.id }) { cal ->
                        val count = counts[cal.id] ?: 0
                        Surface(
                            onClick = { dialog = cal },
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                            modifier = Modifier.fillMaxWidth().animateItem(),
                        ) {
                            Row(Modifier.padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(26.dp).clip(CircleShape).background(Color(cal.color).copy(alpha = if (cal.visible) 1f else 0.3f)))
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(cal.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            pluralStringResource(R.plurals.event_count, count, count),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (cal.id == data.defaultWriteId) Tag(stringResource(R.string.calendar_default_write))
                                        if (!cal.writable) Tag(stringResource(R.string.calendar_read_only))
                                    }
                                }
                                Switch(cal.visible, onCheckedChange = { onToggle(cal, it) })
                            }
                        }
                    }
                }
                item(key = "hint") {
                    Text(
                        stringResource(R.string.calendars_hint),
                        Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (creating) {
        EditCalendarDialog(
            existing = null, eventCount = 0, defaultColor = Palette.colors[data.calendars.size % Palette.colors.size],
            onDismiss = { creating = false },
            onSave = { name, color, onError -> onSave(null, name, color, onError) { creating = false } },
            onDelete = {},
        )
    }
    dialog?.let { cal ->
        if (cal.system) {
            SystemCalendarDialog(
                cal, fmt, isDefaultWrite = cal.id == data.defaultWriteId, onDismiss = { dialog = null },
                onSetDefault = { onSetDefault(cal); dialog = null },
            )
        } else {
            EditCalendarDialog(
                existing = cal, eventCount = counts[cal.id] ?: 0, defaultColor = cal.color,
                isDefaultWrite = cal.id == data.defaultWriteId,
                onSetDefault = { onSetDefault(cal); dialog = null },
                onDismiss = { dialog = null },
                onSave = { name, color, onError -> onSave(cal, name, color, onError) { dialog = null } },
                onDelete = { onDelete(cal); dialog = null },
            )
        }
    }
}

@Composable
private fun Tag(text: String) {
    Surface(Modifier.padding(start = 8.dp), shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
    }
}

/** 本机日历：改名、改颜色、设为默认写入日历、删除（本机默认日历不能删）。 */
@Composable
private fun EditCalendarDialog(
    existing: CalendarInfo?,
    eventCount: Int,
    defaultColor: Int,
    isDefaultWrite: Boolean = false,
    onSetDefault: () -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (String, Int, (String) -> Unit) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var color by remember { mutableStateOf(existing?.color ?: defaultColor) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.calendar_new else R.string.calendar_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text(stringResource(R.string.calendar_name)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                ColorSwatches(color, { color = it ?: color }, Modifier.fillMaxWidth())
                if (existing != null && !isDefaultWrite) {
                    TextButton(onClick = onSetDefault) { Text(stringResource(R.string.calendar_make_default_write)) }
                }
                if (existing != null && !existing.isDefault) {
                    TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.calendar_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, color) { error = it } }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.calendar_delete_title)) },
            text = { Text(stringResource(R.string.calendar_delete_message, existing.name, eventCount)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** 账号 / 系统日历：名字、颜色、删除都不归本 App，只能看出处、设成默认写入日历。 */
@Composable
private fun SystemCalendarDialog(cal: CalendarInfo, fmt: Fmt, isDefaultWrite: Boolean, onDismiss: () -> Unit, onSetDefault: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(cal.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fmt.originLabel(cal), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(if (cal.writable) R.string.calendar_system_note else R.string.calendar_system_note_read_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (cal.writable && !isDefaultWrite) {
                    TextButton(onClick = onSetDefault) { Text(stringResource(R.string.calendar_make_default_write)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}
