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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.agentos.sample.calendar.R
import org.agentos.sample.calendar.data.EventSeries
import org.agentos.sample.calendar.data.Occurrence
import org.agentos.sample.calendar.data.Recurrence
import java.time.Instant
import java.time.ZoneId

/** 日程详情：色块标题、时间、地点、日历、提醒、重复、备注；编辑与删除在顶栏。 */
@Composable
fun DetailScreen(
    data: CalendarData,
    fmt: Fmt,
    occurrence: Occurrence,
    onBack: () -> Unit,
    onEdit: (EventSeries) -> Unit,
    onDelete: (EventSeries) -> Unit,
) {
    val series = occurrence.series
    val custom = series.recurrence == Recurrence.CUSTOM
    val color = data.colorOf(series)
    val tint by animateColorAsState(color.copy(alpha = 0.16f), label = "tint")
    var confirmDelete by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar("", onBack) {
                // custom 重复规则这里改不了（改了会破坏系列），只能看和删
                if (!custom) IconButton(onClick = { onEdit(series) }) { Icon(Icons.Rounded.Edit, stringResource(R.string.action_edit)) }
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
                            when {
                                custom -> series.rrule
                                until != null -> stringResource(R.string.repeat_until_date, fmt.dayMedium(until))
                                else -> stringResource(R.string.repeat_forever)
                            },
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
                            Column(Modifier.weight(1f)) {
                                Text(cal.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(fmt.originLabel(cal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (series.reminders.isNotEmpty()) {
                        DetailRow(Icons.Rounded.Notifications, series.reminders.joinToString(stringResource(R.string.list_separator)) { fmt.reminderLabel(it, series.allDay) })
                    }
                    if (series.description.isNotBlank()) DetailRow(Icons.AutoMirrored.Rounded.Subject, series.description)
                }
                if (series.isRecurring) {
                    InfoNote(stringResource(if (custom) R.string.series_custom_note else R.string.series_note))
                }
                if (data.calendar(series.calendarId)?.system == true && series.reminders.isNotEmpty()) {
                    InfoNote(stringResource(R.string.reminders_by_system_note))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (confirmDelete) {
        DeleteEventDialog(series, data.calendar(series.calendarId), fmt, onDismiss = { confirmDelete = false }, onConfirm = { confirmDelete = false; onDelete(series) })
    }
}

@Composable
private fun InfoNote(text: String) {
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.Info, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
