package org.agentos.sample.calendar.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.agentos.sample.calendar.R
import org.agentos.sample.calendar.data.EventSeries
import org.agentos.sample.calendar.data.Occurrences
import java.time.Instant
import java.time.ZoneId

/** 日程详情：色块标题、时间、地点、日历、提醒、重复、备注；编辑与删除在顶栏。 */
@Composable
fun DetailScreen(
    data: CalendarData,
    fmt: Fmt,
    occurrenceId: String,
    onBack: () -> Unit,
    onEdit: (EventSeries) -> Unit,
    onDelete: (EventSeries) -> Unit,
) {
    val (seriesId, key) = Occurrences.splitId(occurrenceId)
    val series = data.events.firstOrNull { it.id == seriesId }
    // 事件在别处（比如 MCP）被删掉了：自动退出
    LaunchedEffect(series == null) { if (series == null) onBack() }
    if (series == null) return
    val occurrence = remember(series, key, data.zone) {
        (if (key != null) Occurrences.findByKey(series, key, data.zone) else null) ?: Occurrences.first(series, data.zone)
    }
    val color = data.colorOf(series)
    val tint by animateColorAsState(color.copy(alpha = 0.16f), label = "tint")
    var confirmDelete by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar("", onBack) {
                IconButton(onClick = { onEdit(series) }) { Icon(Icons.Rounded.Edit, stringResource(R.string.action_edit)) }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete)) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = tint, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp)) {
                        Box(Modifier.width(5.dp).height(64.dp).clip(MaterialTheme.shapes.extraSmall).background(color))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(series.title.ifBlank { stringResource(R.string.no_title) }, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(6.dp))
                            Text(fmt.rangeText(occurrence, data.zone), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                SectionCard {
                    if (series.isRecurring) {
                        val until = series.recurrenceUntilUtc?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of(series.zoneId)).toLocalDate() }
                        DetailRow(
                            Icons.Rounded.Repeat,
                            fmt.recurrenceLabel(series.recurrence),
                            if (until != null) stringResource(R.string.repeat_until_date, fmt.dayMedium(until)) else stringResource(R.string.repeat_forever),
                        )
                    }
                    if (series.location.isNotBlank()) DetailRow(Icons.Rounded.LocationOn, series.location)
                    val cal = data.calendar(series.calendarId)
                    if (cal != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(16.dp))
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(cal.color)))
                            Spacer(Modifier.width(10.dp))
                            Text(cal.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (series.reminders.isNotEmpty()) {
                        DetailRow(Icons.Rounded.Notifications, series.reminders.joinToString(stringResource(R.string.list_separator)) { fmt.reminderLabel(it, series.allDay) })
                    }
                    if (series.description.isNotBlank()) DetailRow(Icons.AutoMirrored.Rounded.Subject, series.description)
                }
                if (series.isRecurring) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Info, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.series_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (confirmDelete) DeleteEventDialog(series, onDismiss = { confirmDelete = false }, onConfirm = { confirmDelete = false; onDelete(series) })
}

@Composable
private fun DetailRow(icon: ImageVector, primary: String, secondary: String? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(primary, style = MaterialTheme.typography.bodyLarge)
            if (secondary != null) Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
