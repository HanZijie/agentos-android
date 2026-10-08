package org.agentos.sample.todo.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.agentos.sample.todo.R
import org.agentos.sample.todo.data.Change
import org.agentos.sample.todo.data.Due
import org.agentos.sample.todo.data.DueTime
import org.agentos.sample.todo.data.Priority
import org.agentos.sample.todo.data.Todo
import org.agentos.sample.todo.data.TodoDraft
import org.agentos.sample.todo.data.TodoLimits
import org.agentos.sample.todo.data.TodoPatch
import org.agentos.sample.todo.data.TodoQueries
import org.agentos.sample.todo.data.TodoStatus
import org.agentos.sample.todo.ui.components.StatusCircle
import org.agentos.sample.todo.ui.theme.todo

/** 旋转 / 进程重建时 `Due?` 的保存方式。 */
private val DueSaver = Saver<Due?, String>(
    save = { due ->
        when (due) {
            null -> ""
            is Due.At -> "at:${due.epochMillis}"
            is Due.Day -> "day:${due.date}"
        }
    },
    restore = { s ->
        when {
            s.startsWith("at:") -> Due.At(s.removePrefix("at:").toLong())
            s.startsWith("day:") -> Due.Day(LocalDate.parse(s.removePrefix("day:")))
            else -> null
        }
    },
)

/**
 * 详情 / 编辑页。[todoId] 为 null 是新建（点“保存”才写入）；否则是编辑已有的：返回时把**改过的字段**写回
 * （只发相对打开时有变化的字段，所以 AgentOS 在编辑期间改了别的字段不会被覆盖）。子任务的增删、勾选是即时生效的。
 */
@Composable
fun EditorScreen(vm: TodoViewModel, todoId: String?, onClose: () -> Unit, onOpenTodo: (String) -> Unit, modifier: Modifier = Modifier) {
    val todos by vm.todos.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val current = todoId?.let { id -> todos.firstOrNull { it.id == id } }
    // 被 AgentOS 删掉了（或根本不存在）：回到列表
    LaunchedEffect(loaded, current == null, todoId) {
        if (loaded && todoId != null && current == null) onClose()
    }
    if (todoId != null && current == null) {
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val initial = remember(todoId) { current }
    EditorContent(vm, initial, todos, onClose, onOpenTodo, modifier)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditorContent(vm: TodoViewModel, initial: Todo?, todos: List<Todo>, onClose: () -> Unit, onOpenTodo: (String) -> Unit, modifier: Modifier) {
    val isNew = initial == null
    val zone = remember { ZoneId.systemDefault() }
    val formatter = rememberDueFormatter()
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val palette = MaterialTheme.todo
    val now = remember { System.currentTimeMillis() }

    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(initial?.notes.orEmpty()) }
    var status by rememberSaveable { mutableStateOf(initial?.status ?: TodoStatus.TODO) }
    var priority by rememberSaveable { mutableStateOf(initial?.priority ?: Priority.MEDIUM) }
    var due by rememberSaveable(stateSaver = DueSaver) { mutableStateOf(initial?.due) }
    var tags by rememberSaveable { mutableStateOf(ArrayList(initial?.tags.orEmpty())) }
    var tagInput by rememberSaveable { mutableStateOf("") }
    var subtaskInput by rememberSaveable { mutableStateOf("") }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }

    val live = initial?.let { i -> todos.firstOrNull { it.id == i.id } }
    val parent = live?.parentId?.let { pid -> todos.firstOrNull { it.id == pid } }
    val subtasks = if (live != null && live.parentId == null) todos.filter { it.parentId == live.id }.sortedWith(compareBy({ it.createdAt }, { it.id })) else emptyList()

    fun addTag(raw: String) {
        val cleaned = raw.trim().trimStart('#').trim().replace(",", "").replace("，", "")
        if (cleaned.isEmpty() || cleaned.length > TodoLimits.MAX_TAG_CHARS || tags.size >= TodoLimits.MAX_TAGS) return
        if (tags.any { TodoQueries.tagKey(it) == TodoQueries.tagKey(cleaned) }) return
        tags = ArrayList(tags + cleaned)
    }

    fun diff(i: Todo): TodoPatch = TodoPatch(
        title = title.trim().takeIf { it != i.title && it.isNotEmpty() },
        notes = notes.trim().takeIf { it != i.notes },
        status = status.takeIf { it != i.status },
        priority = priority.takeIf { it != i.priority },
        due = if (due != i.due) (due?.let { Change.Set(it) } ?: Change.Clear) else Change.Keep,
        tags = tags.toList().takeIf { it != i.tags },
    )

    // 编辑：返回就是保存；新建：有标题时先问一下免得误丢
    fun leave() {
        if (initial != null) {
            // 没写完的标签也算
            addTag(tagInput)
            vm.update(initial.id, diff(initial))
            onClose()
        } else if (title.isNotBlank()) {
            showDiscard = true
        } else {
            onClose()
        }
    }

    // 去看某个子任务：先把这一页改过的字段写回，再跳转
    fun openSubtask(id: String) {
        if (initial != null) {
            addTag(tagInput)
            vm.update(initial.id, diff(initial))
        }
        onOpenTodo(id)
    }

    fun saveNew() {
        addTag(tagInput)
        vm.create(
            TodoDraft(
                title = title.trim(),
                notes = notes,
                status = status,
                priority = priority,
                due = due,
                tags = tags.toList(),
            ),
            onCreated = onClose,
        )
    }

    BackHandler { leave() }

    Column(
        modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { leave() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back))
            }
            Text(
                stringResource(if (isNew) R.string.editor_title_new else R.string.editor_title_edit),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isNew) {
                TextButton(onClick = { saveNew() }, enabled = title.isNotBlank()) {
                    Text(stringResource(R.string.editor_save), style = MaterialTheme.typography.labelLarge)
                }
            } else {
                IconButton(onClick = { vm.delete(initial.id); onClose() }) {
                    Icon(Icons.Rounded.Delete, stringResource(R.string.editor_delete), tint = palette.overdue)
                }
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (parent != null) {
                Text(
                    stringResource(R.string.editor_part_of, parent.title),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextField(
                value = title,
                onValueChange = { if (it.length <= TodoLimits.MAX_TITLE_CHARS) title = it.replace("\n", " ") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.headlineSmall,
                placeholder = { Text(stringResource(R.string.editor_title_hint), style = MaterialTheme.typography.headlineSmall) },
                maxLines = 4,
                colors = transparentFieldColors(),
            )

            FieldLabel(stringResource(R.string.editor_status))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TodoStatus.entries.forEach { s ->
                    FilterChip(
                        selected = status == s,
                        onClick = { status = s },
                        label = { Text(s.label(), maxLines = 1) },
                        leadingIcon = if (status == s) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null,
                    )
                }
            }

            FieldLabel(stringResource(R.string.editor_priority))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.forEach { p ->
                    FilterChip(
                        selected = priority == p,
                        onClick = { priority = p },
                        label = { Text(p.label(), maxLines = 1) },
                        leadingIcon = { Icon(Icons.Rounded.Flag, null, Modifier.size(18.dp), tint = palette.priority(p)) },
                    )
                }
            }

            FieldLabel(stringResource(R.string.editor_due))
            val today = remember { Instant.ofEpochMilli(now).atZone(zone).toLocalDate() }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { due = withDate(due, today, zone) }, label = { Text(stringResource(R.string.due_today)) })
                AssistChip(onClick = { due = withDate(due, today.plusDays(1), zone) }, label = { Text(stringResource(R.string.due_tomorrow)) })
                AssistChip(
                    onClick = { showDatePicker = true },
                    label = { Text(stringResource(R.string.due_pick_date)) },
                    leadingIcon = { Icon(Icons.Rounded.CalendarToday, null, Modifier.size(18.dp)) },
                )
            }
            val currentDue = due
            if (currentDue != null) {
                Surface(shape = RoundedCornerShape(16.dp), color = scheme.surface, border = BorderStroke(1.dp, scheme.outlineVariant)) {
                    Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CalendarToday, null, Modifier.size(18.dp), tint = scheme.primary)
                            Text(
                                formatter.fullDate(DueTime.localDate(currentDue, zone)),
                                Modifier.weight(1f).padding(start = 10.dp).clickable { showDatePicker = true },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            IconButton(onClick = { due = null }) { Icon(Icons.Rounded.Close, stringResource(R.string.due_clear)) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.due_all_day), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Switch(
                                checked = currentDue.isAllDay,
                                onCheckedChange = { allDay ->
                                    due = if (allDay) {
                                        Due.Day(DueTime.localDate(currentDue, zone))
                                    } else {
                                        withTime(currentDue, LocalTime.of(9, 0), zone)
                                    }
                                },
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        }
                        if (currentDue is Due.At) {
                            Row(
                                Modifier.fillMaxWidth().clickable { showTimePicker = true }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp), tint = scheme.primary)
                                Text(formatter.time(currentDue.epochMillis), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }

            FieldLabel(stringResource(R.string.editor_tags))
            if (tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { tag ->
                        InputChip(
                            selected = false,
                            onClick = { tags = ArrayList(tags - tag) },
                            label = { Text("# $tag", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = { Icon(Icons.Rounded.Close, stringResource(R.string.cd_remove_tag), Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = tagInput,
                onValueChange = { v ->
                    if (v.endsWith(",") || v.endsWith("，")) { addTag(v); tagInput = "" } else tagInput = v
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.tag_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Sell, null, Modifier.size(18.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addTag(tagInput); tagInput = "" }),
            )
            val suggestions = remember(todos, tags) {
                TodoSections.tags(todos).filter { s -> tags.none { TodoQueries.tagKey(it) == TodoQueries.tagKey(s) } }.take(8)
            }
            if (suggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { s ->
                        SuggestionChip(onClick = { addTag(s) }, label = { Text("# $s", maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }

            FieldLabel(stringResource(R.string.editor_notes))
            Surface(shape = RoundedCornerShape(16.dp), color = scheme.surface, border = BorderStroke(1.dp, scheme.outlineVariant)) {
                TextField(
                    value = notes,
                    onValueChange = { if (it.length <= TodoLimits.MAX_NOTES_CHARS) notes = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.editor_notes_hint)) },
                    minLines = 3,
                    colors = transparentFieldColors(),
                )
            }

            // 子任务：只有已保存的顶层待办有（一层）
            if (parent == null) {
                FieldLabel(stringResource(R.string.editor_subtasks))
                if (live == null) {
                    Text(
                        stringResource(R.string.subtask_save_first),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                } else {
                    if (subtasks.isNotEmpty()) {
                        val done = subtasks.count { it.status == TodoStatus.DONE }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                progress = { done / subtasks.size.toFloat() },
                                modifier = Modifier.weight(1f).height(6.dp),
                                color = palette.done,
                                trackColor = scheme.surfaceVariant,
                                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                                gapSize = 0.dp,
                                drawStopIndicator = {},
                            )
                            Text("$done/${subtasks.size}", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                        }
                    }
                    subtasks.forEach { sub ->
                        Row(
                            Modifier.fillMaxWidth().clickable { openSubtask(sub.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusCircle(
                                status = sub.status,
                                accent = palette.priority(sub.priority),
                                contentDescription = stringResource(if (sub.status == TodoStatus.DONE) R.string.cd_mark_reopen else R.string.cd_mark_done),
                                diameter = 22.dp,
                                onClick = { vm.toggleDone(sub) },
                            )
                            Text(
                                sub.title,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (sub.status == TodoStatus.DONE) scheme.onSurfaceVariant else scheme.onSurface,
                                textDecoration = if (sub.status == TodoStatus.DONE) TextDecoration.LineThrough else null,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { vm.delete(sub.id) }) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.cd_delete_subtask), tint = scheme.onSurfaceVariant)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = subtaskInput,
                        onValueChange = { subtaskInput = it.replace("\n", " ") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.subtask_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            vm.addSubtask(live.id, subtaskInput)
                            subtaskInput = ""
                        }),
                    )
                }
            }

            live?.completedAt?.let { done ->
                Text(
                    stringResource(R.string.editor_completed_at, formatter.moment(done, now)),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(24.dp).navigationBarsPadding())
        }
    }

    if (showDatePicker) {
        val startDate = due?.let { DueTime.localDate(it, zone) } ?: LocalDate.now(zone)
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        due = withDate(due, Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate(), zone)
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.dialog_ok)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.dialog_cancel)) } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTimePicker) {
        val at = due as? Due.At
        val local = at?.let { Instant.ofEpochMilli(it.epochMillis).atZone(zone).toLocalTime() } ?: LocalTime.of(9, 0)
        val timeState = rememberTimePickerState(local.hour, local.minute, DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    due?.let { due = withTime(it, LocalTime.of(timeState.hour, timeState.minute), zone) }
                    showTimePicker = false
                }) { Text(stringResource(R.string.dialog_ok)) }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.dialog_cancel)) } },
            title = { Text(stringResource(R.string.due_pick_time)) },
            text = { TimePicker(state = timeState) },
        )
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text(stringResource(R.string.editor_discard_title)) },
            text = { Text(stringResource(R.string.editor_discard_body)) },
            confirmButton = { TextButton(onClick = { showDiscard = false; onClose() }) { Text(stringResource(R.string.editor_discard_confirm)) } },
            dismissButton = { TextButton(onClick = { showDiscard = false }) { Text(stringResource(R.string.editor_keep_editing)) } },
        )
    }
}

/** 换日期：带时刻的保留原来的时刻，全天或没有截止的变成全天。 */
private fun withDate(due: Due?, date: LocalDate, zone: ZoneId): Due = when (due) {
    is Due.At -> {
        val time = Instant.ofEpochMilli(due.epochMillis).atZone(zone).toLocalTime()
        Due.At(date.atTime(time).atZone(zone).toInstant().toEpochMilli())
    }
    else -> Due.Day(date)
}

/** 设时刻：日期取原来截止的本地日期。 */
private fun withTime(due: Due, time: LocalTime, zone: ZoneId): Due =
    Due.At(DueTime.localDate(due, zone).atTime(time).atZone(zone).toInstant().toEpochMilli())

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp),
    )
}

@Composable
private fun transparentFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)
