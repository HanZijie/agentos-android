package org.agentos.sample.alarm.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import java.time.DayOfWeek
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agentos.sample.alarm.R
import org.agentos.sample.alarm.data.Alarm
import org.agentos.sample.alarm.data.AlarmDraft
import org.agentos.sample.alarm.data.AlarmException
import org.agentos.sample.alarm.data.AlarmPatch
import org.agentos.sample.alarm.data.AlarmRepository
import org.agentos.sample.alarm.data.EVERY_DAY
import org.agentos.sample.alarm.data.NextFire
import org.agentos.sample.alarm.data.WEEKDAYS
import org.agentos.sample.alarm.data.WEEKEND
import org.agentos.sample.alarm.data.toDays
import org.agentos.sample.alarm.data.toMask
import org.agentos.sample.alarm.ui.theme.TimeNumeralStyle

private val SNOOZE_OPTIONS = listOf(5, 10, 15, 20, 30)

/** 新建 / 编辑页。[initial] 为 null 表示新建（默认取下一个整点）。保存成功后回调 [onClose]。 */
@Composable
fun AlarmEditorScreen(
    repository: AlarmRepository,
    initial: Alarm?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val is24 = remember { android.text.format.DateFormat.is24HourFormat(context) }
    val defaults = remember { repository.now().plusHours(1).withMinute(0) }

    var hour by rememberSaveable { mutableIntStateOf(initial?.hour ?: defaults.hour) }
    var minute by rememberSaveable { mutableIntStateOf(initial?.minute ?: 0) }
    var label by rememberSaveable { mutableStateOf(initial?.label ?: "") }
    var daysMask by rememberSaveable { mutableIntStateOf(initial?.days?.toMask() ?: 0) }
    var vibrate by rememberSaveable { mutableStateOf(initial?.vibrate ?: true) }
    var snooze by rememberSaveable { mutableIntStateOf(initial?.snoozeMinutes ?: Alarm.DEFAULT_SNOOZE_MINUTES) }
    var ringtone by rememberSaveable { mutableStateOf(initial?.ringtoneUri) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val days = daysMask.toDays()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val pickRingtone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            ringtone = uri?.toString()
        }
    }

    fun save() {
        if (saving) return
        saving = true
        focus.clearFocus()
        keyboard?.hide()
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                try {
                    if (initial == null) {
                        repository.create(AlarmDraft(hour, minute, label, days, true, vibrate, snooze, ringtone))
                    } else {
                        // 编辑后视为想让它响：同时打开开关
                        repository.update(
                            initial.id,
                            AlarmPatch(hour, minute, label, days, true, vibrate, snooze, ringtone ?: ""),
                        )
                    }
                    null
                } catch (e: AlarmException) {
                    e.message
                }
            }
            saving = false
            if (failure == null) onClose() else error = failure
        }
    }

    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val compact = windowHeight < 720.dp

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { keyboard?.hide(); onClose() }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.editor_close))
            }
            Text(
                text = stringResource(if (initial == null) R.string.editor_new_title else R.string.editor_edit_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Button(
                onClick = ::save,
                enabled = !saving,
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
            ) { Text(stringResource(R.string.editor_save), style = MaterialTheme.typography.labelLarge) }
            Spacer(Modifier.width(8.dp))
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            // 时间滚轮 + “还有多久”
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(vertical = if (compact) 8.dp else 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TimeWheels(hour, minute, is24, compact) { h, m -> hour = h; minute = m }
                    val now = repository.now()
                    val fire = NextFire.nextOccurrence(hour, minute, days, now)
                    Text(
                        text = formatRingsIn(context, now, fire),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.editor_repeat))
            RepeatPicker(days) { daysMask = it.toMask() }

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { if (it.length <= Alarm.MAX_LABEL_LENGTH) label = it },
                placeholder = { Text(stringResource(R.string.editor_label_hint)) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    SettingRow(
                        icon = { Icon(Icons.Rounded.MusicNote, contentDescription = null) },
                        title = stringResource(R.string.editor_sound),
                        value = ringtoneTitle(context, ringtone),
                        onClick = { pickRingtone.launch(ringtoneIntent(ringtone)) },
                    )
                    SettingRow(
                        icon = { Icon(Icons.Rounded.Vibration, contentDescription = null) },
                        title = stringResource(R.string.editor_vibrate),
                        trailing = {
                            Switch(
                                checked = vibrate,
                                onCheckedChange = { vibrate = it },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            )
                        },
                        onClick = { vibrate = !vibrate },
                    )
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Snooze, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(16.dp))
                            Text(stringResource(R.string.editor_snooze), style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(12.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val options = if (snooze in SNOOZE_OPTIONS) SNOOZE_OPTIONS else (SNOOZE_OPTIONS + snooze).sorted()
                            options.forEach { minutes ->
                                FilterChip(
                                    selected = snooze == minutes,
                                    onClick = { snooze = minutes },
                                    label = { Text(minutes.toString()) },
                                    shape = MaterialTheme.shapes.medium,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.editor_error_prefix, it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (initial != null) {
                Spacer(Modifier.height(24.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { repository.delete(initial.id) } }
                            onClose()
                        }
                    },
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.editor_delete))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TimeWheels(hour: Int, minute: Int, is24: Boolean, compact: Boolean, onChange: (Int, Int) -> Unit) {
    val rows = if (compact) 3 else 5
    val itemHeight = if (compact) 52.dp else 60.dp
    val hourDescription = stringResource(R.string.cd_hour)
    val minuteDescription = stringResource(R.string.cd_minute)
    // 滚轮的回调各只带自己那一列，这里合成完整的 (hour, minute)
    var h by remember { mutableIntStateOf(hour) }
    var m by remember { mutableIntStateOf(minute) }
    LaunchedEffect(h, m) { onChange(h, m) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (is24) {
            WheelPicker(
                count = 24, selected = hour, rows = rows, itemHeight = itemHeight,
                onSelectedChange = { h = it }, label = { "%02d".format(it) },
                modifier = Modifier.semantics { contentDescription = hourDescription },
            )
        } else {
            val pm = h >= 12
            WheelPicker(
                count = 12, selected = hour % 12, rows = rows, itemHeight = itemHeight,
                onSelectedChange = { idx -> h = (if (pm) 12 else 0) + idx },
                label = { if (it == 0) "12" else it.toString() },
                modifier = Modifier.semantics { contentDescription = hourDescription },
            )
        }
        Text(":", style = TimeNumeralStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp))
        WheelPicker(
            count = 60, selected = minute, rows = rows, itemHeight = itemHeight,
            onSelectedChange = { m = it }, label = { "%02d".format(it) },
            modifier = Modifier.semantics { contentDescription = minuteDescription },
        )
        if (!is24) {
            Spacer(Modifier.width(8.dp))
            val am = stringResource(R.string.editor_am)
            val pmLabel = stringResource(R.string.editor_pm)
            WheelPicker(
                count = 2, selected = if (hour >= 12) 1 else 0, rows = rows, itemHeight = itemHeight, width = 84.dp, cyclic = false,
                onSelectedChange = { idx -> h = (h % 12) + if (idx == 1) 12 else 0 },
                label = { if (it == 0) am else pmLabel },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
    )
}

/** 预设（仅一次 / 每天 / 工作日 / 周末）+ 七个圆形星期开关。 */
@Composable
private fun RepeatPicker(days: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    val presets = listOf(
        stringResource(R.string.repeat_once) to emptySet<DayOfWeek>(),
        stringResource(R.string.repeat_daily) to EVERY_DAY,
        stringResource(R.string.repeat_weekdays) to WEEKDAYS,
        stringResource(R.string.repeat_weekends) to WEEKEND,
    )
    Column {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            presets.forEach { (name, set) ->
                FilterChip(
                    selected = days == set,
                    onClick = { onChange(set) },
                    label = { Text(name, maxLines = 1, softWrap = false) },
                    shape = MaterialTheme.shapes.medium,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
        val letters = stringArrayResource(R.array.weekday_short)
        val names = stringArrayResource(R.array.weekday_long)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DayOfWeek.entries.forEach { day ->
                val on = day in days
                val bg by animateColorAsState(
                    if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    label = "day-bg",
                )
                val scale by animateFloatAsState(if (on) 1f else 0.92f, spring(), label = "day-scale")
                Box(
                    Modifier
                        .size(44.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(CircleShape)
                        .background(bg)
                        .toggleable(
                            value = on,
                            role = Role.Checkbox,
                            onValueChange = { onChange(if (on) days - day else days + day) },
                        )
                        .semantics { contentDescription = names[day.value - 1] },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        letters[day.value - 1],
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    icon: @Composable () -> Unit,
    title: String,
    onClick: () -> Unit,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false),
                )
            }
            trailing?.invoke()
        }
    }
}

private fun ringtoneIntent(current: String?) = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
    putExtra(
        RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
        current?.toUri() ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
    )
}

private fun ringtoneTitle(context: Context, uri: String?): String {
    if (uri == null) return context.getString(R.string.editor_sound_default)
    return runCatching { RingtoneManager.getRingtone(context, uri.toUri())?.getTitle(context) }.getOrNull()
        ?: context.getString(R.string.editor_sound_default)
}
