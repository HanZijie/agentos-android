package org.agentos.sample.calendar.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.agentos.sample.calendar.R
import org.agentos.sample.calendar.data.EventSeries
import org.agentos.sample.calendar.data.Recurrence
import org.agentos.sample.calendar.reminder.ReminderNotifications
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** 编辑中的日程草稿：界面上每个控件对应一个可观察字段。 */
private class Draft(val original: EventSeries?, data: CalendarData, defaultCalendarId: String, prefillStartMs: Long?, prefillAllDay: Boolean, selectedDate: LocalDate) {
    var title by mutableStateOf("")
    var allDay by mutableStateOf(false)
    var startDate by mutableStateOf(LocalDate.now())
    var startTime by mutableStateOf(LocalTime.of(9, 0))
    var endDate by mutableStateOf(LocalDate.now())
    var endTime by mutableStateOf(LocalTime.of(10, 0))
    var location by mutableStateOf("")
    var description by mutableStateOf("")
    var calendarId by mutableStateOf(defaultCalendarId)
    var color by mutableStateOf<Int?>(null)
    var reminders by mutableStateOf<List<Int>>(emptyList())
    var recurrence by mutableStateOf(Recurrence.NONE)
    var until by mutableStateOf<LocalDate?>(null)
    var titleError by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    init {
        val zone = data.zone
        val o = original
        if (o != null) {
            title = o.title
            allDay = o.allDay
            if (o.allDay) {
                startDate = o.startDate
                endDate = o.endDate
            } else {
                val s = Instant.ofEpochMilli(o.startUtc).atZone(zone)
                val e = Instant.ofEpochMilli(o.endUtc).atZone(zone)
                startDate = s.toLocalDate(); startTime = s.toLocalTime().withSecond(0).withNano(0)
                endDate = e.toLocalDate(); endTime = e.toLocalTime().withSecond(0).withNano(0)
            }
            location = o.location
            description = o.description
            calendarId = o.calendarId
            color = o.color
            reminders = o.reminders
            recurrence = o.recurrence
            until = o.recurrenceUntilUtc?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of(o.zoneId)).toLocalDate() }
        } else {
            allDay = prefillAllDay
            val start: LocalDateTime = if (prefillStartMs != null) {
                Instant.ofEpochMilli(prefillStartMs).atZone(zone).toLocalDateTime()
            } else if (selectedDate == data.today) {
                LocalDateTime.now(zone).plusHours(1).withMinute(0).withSecond(0).withNano(0)
            } else {
                selectedDate.atTime(9, 0)
            }
            startDate = if (prefillStartMs == null && selectedDate != data.today) selectedDate else start.toLocalDate()
            startTime = start.toLocalTime()
            val end = start.plusHours(1)
            endDate = end.toLocalDate()
            endTime = end.toLocalTime()
            if (prefillAllDay) endDate = startDate
            reminders = if (prefillAllDay) listOf(0) else listOf(10)
        }
    }

    /** 开始时间变了：结束时间保持原来的时长跟着走。 */
    fun moveStart(newDate: LocalDate = startDate, newTime: LocalTime = startTime) {
        if (allDay) {
            val span = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate)
            startDate = newDate
            endDate = newDate.plusDays(span)
        } else {
            val old = LocalDateTime.of(startDate, startTime)
            val dur = java.time.Duration.between(old, LocalDateTime.of(endDate, endTime))
            startDate = newDate
            startTime = newTime
            val end = LocalDateTime.of(newDate, newTime).plus(dur)
            endDate = end.toLocalDate()
            endTime = end.toLocalTime()
        }
    }

    fun snapshot(): String = listOf(title, allDay, startDate, startTime, endDate, endTime, location, description, calendarId, color, reminders, recurrence, until).joinToString("|")
}

/** 新建 / 编辑日程。[onSaved] 带回开始日期，让首页跳到那一天。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    data: CalendarData,
    fmt: Fmt,
    eventId: String?,
    prefillStartMs: Long?,
    prefillAllDay: Boolean,
    selectedDate: LocalDate,
    save: (EventSeries, onError: (String) -> Unit, onDone: (EventSeries) -> Unit) -> Unit,
    delete: (EventSeries) -> Unit,
    onClose: () -> Unit,
    onSaved: (LocalDate) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val original = remember(eventId) { eventId?.let { id -> data.events.firstOrNull { it.id == id.substringBefore('@') } } }
    val draft = remember(eventId) {
        Draft(original, data, data.calendars.firstOrNull { it.isDefault }?.id ?: data.calendars.first().id, prefillStartMs, prefillAllDay, selectedDate)
    }
    val initial = remember(draft) { draft.snapshot() }
    val dirty = draft.snapshot() != initial
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var reminderDialog by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val titleFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { if (original == null) titleFocus.requestFocus() }

    fun close() {
        if (dirty) confirmDiscard = true else onClose()
    }
    BackHandler { close() }

    fun submit() {
        val zone = data.zone
        draft.error = null
        if (draft.title.isBlank()) {
            draft.titleError = true
            titleFocus.requestFocus()
            return
        }
        val base = EventSeries(
            id = original?.id ?: "", calendarId = draft.calendarId, title = draft.title, description = draft.description, location = draft.location,
            allDay = draft.allDay, startUtc = 0, endUtc = 0, zoneId = zone.id, color = draft.color, reminders = draft.reminders,
            recurrence = draft.recurrence, createdAt = original?.createdAt ?: 0,
        )
        val built = if (draft.allDay) {
            if (draft.endDate.isBefore(draft.startDate)) { draft.error = resources.getString(R.string.error_end_before_start); return }
            base.copy(startDay = draft.startDate.toEpochDay(), endDay = draft.endDate.toEpochDay(), zoneId = original?.takeIf { it.allDay }?.zoneId ?: zone.id)
        } else {
            val s = LocalDateTime.of(draft.startDate, draft.startTime).atZone(zone).toInstant().toEpochMilli()
            val e = LocalDateTime.of(draft.endDate, draft.endTime).atZone(zone).toInstant().toEpochMilli()
            if (e < s) { draft.error = resources.getString(R.string.error_end_before_start); return }
            // 时间没动就保留日程原来的时区；动了就记成设备当前时区
            val keepZone = original != null && !original.allDay && original.startUtc == s && original.endUtc == e
            base.copy(startUtc = s, endUtc = e, zoneId = if (keepZone) original.zoneId else zone.id)
        }
        val evZone = ZoneId.of(built.zoneId)
        val until = draft.until
        val withUntil = if (draft.recurrence != Recurrence.NONE && until != null) {
            if (until.isBefore(draft.startDate)) { draft.error = resources.getString(R.string.error_until_before_start); return }
            built.copy(recurrenceUntilUtc = until.plusDays(1).atStartOfDay(evZone).toInstant().toEpochMilli() - 1)
        } else built
        save(withUntil, { draft.error = it }) { saved ->
            if (saved.reminders.isNotEmpty() && !ReminderNotifications.canPost(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            onSaved(draft.startDate)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::close) { Icon(Icons.Rounded.Close, stringResource(R.string.action_close)) }
                Text(
                    stringResource(if (original == null) R.string.editor_new else R.string.editor_edit),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = ::submit, modifier = Modifier.padding(end = 12.dp)) { Text(stringResource(R.string.action_save)) }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnimatedVisibility(draft.error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Text(draft.error.orEmpty(), Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                // 标题
                val accent = draft.color?.let { Color(it) } ?: data.calendar(draft.calendarId)?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).width(5.dp).height(44.dp).clip(MaterialTheme.shapes.extraSmall).background(accent))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        BasicTextField(
                            value = draft.title,
                            onValueChange = { draft.title = it; draft.titleError = false },
                            modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                            textStyle = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            decorationBox = { inner ->
                                Box(Modifier.padding(vertical = 8.dp)) {
                                    if (draft.title.isEmpty()) Text(stringResource(R.string.field_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                    inner()
                                }
                            },
                        )
                        AnimatedVisibility(draft.titleError) {
                            Text(stringResource(R.string.error_title_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                // 时间
                SectionCard {
                    IconRow(Icons.Rounded.AccessTime) {
                        Text(stringResource(R.string.field_all_day), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(draft.allDay, onCheckedChange = {
                            draft.allDay = it
                            if (it) draft.endDate = maxOf(draft.endDate, draft.startDate)
                        })
                    }
                    DateTimeRow(stringResource(R.string.field_starts), draft.startDate, draft.startTime, draft.allDay, fmt, {
                        picker = Picker.StartDate
                    }, { picker = Picker.StartTime })
                    DateTimeRow(stringResource(R.string.field_ends), draft.endDate, draft.endTime, draft.allDay, fmt, {
                        picker = Picker.EndDate
                    }, { picker = Picker.EndTime })
                }

                // 地点与备注
                SectionCard {
                    IconRow(Icons.Rounded.LocationOn) {
                        PlainField(draft.location, { draft.location = it }, stringResource(R.string.field_location), singleLine = true)
                    }
                    HorizontalDivider(Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.AutoMirrored.Rounded.Subject, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        PlainField(draft.description, { draft.description = it }, stringResource(R.string.field_notes), singleLine = false)
                    }
                }

                // 日历与颜色
                SectionCard {
                    var menu by remember { mutableStateOf(false) }
                    val cal = data.calendar(draft.calendarId)
                    Box {
                        IconRow(Icons.Rounded.CalendarMonth, Modifier.clickable { menu = true }) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(cal?.color ?: 0)))
                            Spacer(Modifier.width(10.dp))
                            Text(cal?.name.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(menu, { menu = false }) {
                            for (c in data.calendars) {
                                DropdownMenuItem(
                                    text = { Text(c.name) },
                                    leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(c.color))) },
                                    onClick = { draft.calendarId = c.id; menu = false },
                                )
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Palette, null, Modifier.padding(top = 6.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        ColorSwatches(draft.color, { draft.color = it }, Modifier.weight(1f), noneColor = Color(cal?.color ?: 0xFF888888.toInt()), size = 32.dp)
                    }
                }

                // 提醒与重复
                SectionCard {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Notifications, null, Modifier.padding(top = 8.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (m in draft.reminders) {
                                InputChip(
                                    selected = true,
                                    onClick = { draft.reminders = draft.reminders - m },
                                    label = { Text(fmt.reminderLabel(m, draft.allDay)) },
                                    trailingIcon = { Icon(Icons.Rounded.Close, null, Modifier.size(16.dp)) },
                                )
                            }
                            if (draft.reminders.size < EventSeries.MAX_REMINDERS) {
                                AssistChip(
                                    onClick = { reminderDialog = true },
                                    label = { Text(stringResource(R.string.add_reminder)) },
                                    leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) },
                                )
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    var repeatMenu by remember { mutableStateOf(false) }
                    Box {
                        IconRow(Icons.Rounded.Repeat, Modifier.clickable { repeatMenu = true }) {
                            Text(fmt.recurrenceLabel(draft.recurrence), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Icon(Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(repeatMenu, { repeatMenu = false }) {
                            for (r in Recurrence.entries) {
                                DropdownMenuItem(text = { Text(fmt.recurrenceLabel(r)) }, onClick = { draft.recurrence = r; repeatMenu = false })
                            }
                        }
                    }
                    AnimatedVisibility(draft.recurrence != Recurrence.NONE, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { picker = Picker.Until }.height(48.dp).padding(start = 54.dp, end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.repeat_until), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                draft.until?.let { fmt.dayMedium(it) } ?: stringResource(R.string.repeat_forever),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                if (original != null) {
                    TextButton(onClick = { confirmDelete = true }, Modifier.align(Alignment.CenterHorizontally)) {
                        Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ---- 选择器与对话框 ----
    when (val p = picker) {
        Picker.StartDate, Picker.EndDate, Picker.Until -> {
            val initial = when (p) {
                Picker.StartDate -> draft.startDate
                Picker.EndDate -> draft.endDate
                else -> draft.until ?: draft.startDate
            }
            val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { picker = null },
                confirmButton = {
                    TextButton(onClick = {
                        val ms = state.selectedDateMillis
                        if (ms != null) {
                            val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                            when (p) {
                                Picker.StartDate -> draft.moveStart(newDate = d)
                                Picker.EndDate -> draft.endDate = d
                                else -> draft.until = d
                            }
                        }
                        picker = null
                    }) { Text(stringResource(R.string.action_ok)) }
                },
                dismissButton = {
                    if (p == Picker.Until) TextButton(onClick = { draft.until = null; picker = null }) { Text(stringResource(R.string.repeat_forever)) }
                    else TextButton(onClick = { picker = null }) { Text(stringResource(R.string.action_cancel)) }
                },
            ) { DatePicker(state) }
        }
        Picker.StartTime, Picker.EndTime -> {
            val initial = if (p == Picker.StartTime) draft.startTime else draft.endTime
            val is24 = android.text.format.DateFormat.is24HourFormat(context)
            val state = rememberTimePickerState(initial.hour, initial.minute, is24)
            AlertDialog(
                onDismissRequest = { picker = null },
                confirmButton = {
                    TextButton(onClick = {
                        val t = LocalTime.of(state.hour, state.minute)
                        if (p == Picker.StartTime) draft.moveStart(newTime = t) else draft.endTime = t
                        picker = null
                    }) { Text(stringResource(R.string.action_ok)) }
                },
                dismissButton = { TextButton(onClick = { picker = null }) { Text(stringResource(R.string.action_cancel)) } },
                text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state) } },
            )
        }
        null -> Unit
    }

    if (reminderDialog) {
        val presets = if (draft.allDay) listOf(0, 1440, 2880, 10080) else listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080)
        AlertDialog(
            onDismissRequest = { reminderDialog = false },
            confirmButton = { TextButton(onClick = { reminderDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
            title = { Text(stringResource(R.string.add_reminder)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (m in presets.filter { it !in draft.reminders }) {
                        Text(
                            fmt.reminderLabel(m, draft.allDay),
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable {
                                draft.reminders = (draft.reminders + m).sorted()
                                reminderDialog = false
                            }.padding(horizontal = 12.dp, vertical = 14.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_message)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text(stringResource(R.string.action_discard), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_keep_editing)) } },
        )
    }
    if (confirmDelete && original != null) {
        DeleteEventDialog(original, onDismiss = { confirmDelete = false }, onConfirm = { confirmDelete = false; delete(original) })
    }
}

private enum class Picker { StartDate, StartTime, EndDate, EndTime, Until }

@Composable
private fun DateTimeRow(label: String, date: LocalDate, time: LocalTime, allDay: Boolean, fmt: Fmt, onDate: () -> Unit, onTime: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 54.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ValueChip(fmt.dayMedium(date), onDate)
        if (!allDay) {
            Spacer(Modifier.width(8.dp))
            ValueChip(fmt.time(time), onTime)
        }
    }
}

@Composable
private fun ValueChip(text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PlainField(value: String, onChange: (String) -> Unit, hint: String, singleLine: Boolean) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.weight(1f),
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                inner()
            }
        },
    )
}

@Composable
fun DeleteEventDialog(event: EventSeries, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_event_title)) },
        text = {
            Text(
                if (event.isRecurring) stringResource(R.string.delete_series_message, event.title)
                else stringResource(R.string.delete_event_message, event.title),
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
