package org.agentos.sample.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

/** 日历管理：显示 / 隐藏、改名改色、新建、删除（默认日历不能删）。 */
@Composable
fun CalendarsScreen(
    data: CalendarData,
    onBack: () -> Unit,
    onToggle: (CalendarInfo, Boolean) -> Unit,
    onSave: (existing: CalendarInfo?, name: String, color: Int, onError: (String) -> Unit, onDone: () -> Unit) -> Unit,
    onDelete: (CalendarInfo) -> Unit,
) {
    var dialog by remember { mutableStateOf<CalendarInfo?>(null) }
    var creating by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(stringResource(R.string.calendars_title), onBack) {
                IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, stringResource(R.string.calendar_new)) }
            }
            LazyColumn(
                Modifier.weight(1f).navigationBarsPadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(data.calendars, key = { it.id }) { cal ->
                    val count = data.events.count { it.calendarId == cal.id }
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(cal.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (cal.isDefault) {
                                        Spacer(Modifier.width(8.dp))
                                        Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.secondaryContainer) {
                                            Text(stringResource(R.string.calendar_default), Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                        }
                                    }
                                }
                                Text(pluralStringResource(R.plurals.event_count, count, count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(cal.visible, onCheckedChange = { onToggle(cal, it) })
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
    if (creating || dialog != null) {
        val existing = dialog
        CalendarDialog(
            existing = existing,
            eventCount = existing?.let { e -> data.events.count { it.calendarId == e.id } } ?: 0,
            defaultColor = Palette.colors[data.calendars.size % Palette.colors.size],
            onDismiss = { creating = false; dialog = null },
            onSave = { name, color, onError -> onSave(existing, name, color, onError) { creating = false; dialog = null } },
            onDelete = { existing?.let(onDelete); creating = false; dialog = null },
        )
    }
}

@Composable
private fun CalendarDialog(
    existing: CalendarInfo?,
    eventCount: Int,
    defaultColor: Int,
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
