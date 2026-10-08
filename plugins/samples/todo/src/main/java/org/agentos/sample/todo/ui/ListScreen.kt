package org.agentos.sample.todo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.input.ImeAction
import java.time.ZoneId
import java.time.temporal.WeekFields
import org.agentos.sample.todo.R
import org.agentos.sample.todo.data.Priority
import org.agentos.sample.todo.data.Todo
import org.agentos.sample.todo.data.TodoQueries
import org.agentos.sample.todo.data.TodoStatus
import org.agentos.sample.todo.ui.components.ClipboardIllustration
import org.agentos.sample.todo.ui.components.MetaPill
import org.agentos.sample.todo.ui.components.PriorityBar
import org.agentos.sample.todo.ui.components.StatusCircle
import org.agentos.sample.todo.ui.theme.todo

/** 列表页：概览卡、快速添加、筛选 chips、分组列表（逾期 / 今天 / 即将到来 / 无日期 / 搁置 / 已完成）。 */
@Composable
fun ListScreen(
    vm: TodoViewModel,
    onOpen: (String) -> Unit,
    onLanguage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val todos by vm.todos.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val sections by vm.sections.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val collapsed by vm.collapsed.collectAsStateWithLifecycle()
    val expanded by vm.expandedParents.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val formatter = rememberDueFormatter()
    val tags = remember(todos) { TodoSections.tags(todos) }

    val openSections = sections.filter { it.kind != SectionKind.SHELVED && it.kind != SectionKind.DONE }
    val allClear = loaded && todos.isNotEmpty() && openSections.isEmpty() && !filter.isActive

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 12.dp,
            bottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding() + 104.dp,
        ),
    ) {
        item(key = "hero") {
            Hero(todos, now, vm.zoneId(), onLanguage, Modifier.padding(horizontal = 16.dp))
        }
        item(key = "quick") {
            QuickAddBar(
                onSubmit = vm::quickAdd,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
            )
        }
        item(key = "filters") {
            FilterRow(
                filter = filter,
                tags = tags,
                onStatus = vm::setStatusFilter,
                onPriority = vm::setPriorityFilter,
                onTag = vm::setTagFilter,
                onClear = vm::clearFilters,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (loaded && sections.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    filtered = filter.isActive && todos.isNotEmpty(),
                    onClear = vm::clearFilters,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (allClear) {
            item(key = "all-clear") {
                AllClear(Modifier.animateItem())
            }
        }
        for (section in sections) {
            val isCollapsed = section.kind in collapsed
            item(key = "h:${section.kind}") {
                SectionHeader(
                    kind = section.kind,
                    count = section.rows.size,
                    collapsed = isCollapsed,
                    onToggle = { vm.toggleSection(section.kind) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (!isCollapsed) {
                items(section.rows, key = { "r:${it.todo.id}" }) { row ->
                    SwipeRow(
                        todo = row.todo,
                        onComplete = { vm.toggleDone(row.todo) },
                        onDelete = { vm.delete(row.todo.id) },
                        modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        TodoCard(
                            row = row,
                            overdue = section.kind == SectionKind.OVERDUE,
                            today = section.kind == SectionKind.TODAY,
                            expanded = row.todo.id in expanded,
                            now = now,
                            formatter = formatter,
                            onOpen = onOpen,
                            onToggleDone = { vm.toggleDone(it) },
                            onToggleExpand = { vm.toggleParent(row.todo.id) },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 概览卡

@Composable
private fun Hero(todos: List<Todo>, now: Long, zone: ZoneId, onLanguage: () -> Unit, modifier: Modifier = Modifier) {
    val palette = MaterialTheme.todo
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val summary = remember(todos, now, zone, locale) { TodoQueries.summary(todos, now, zone, WeekFields.of(locale)) }
    val open = summary.counts.getValue(TodoStatus.TODO) + summary.counts.getValue(TodoStatus.DOING)
    val topLevel = todos.filter { it.parentId == null && it.status != TodoStatus.SHELVED }
    val doneTop = topLevel.count { it.status == TodoStatus.DONE }
    val parts = buildList {
        if (summary.overdue > 0) add(pluralStringResource(R.plurals.header_overdue, summary.overdue, summary.overdue))
        if (summary.dueToday > 0) add(pluralStringResource(R.plurals.header_due_today, summary.dueToday, summary.dueToday))
    }
    val subtitle = when {
        todos.isEmpty() -> stringResource(R.string.header_empty)
        open == 0 -> stringResource(R.string.header_all_clear)
        parts.isNotEmpty() -> parts.joinToString(" · ")
        else -> pluralStringResource(R.plurals.header_open, open, open)
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(palette.heroStart, palette.heroEnd))),
    ) {
        // 右上角的装饰圆
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color.White.copy(alpha = 0.07f), radius = size.minDimension * 0.62f, center = Offset(size.width * 0.92f, -size.height * 0.1f))
            drawCircle(Color.White.copy(alpha = 0.05f), radius = size.minDimension * 0.4f, center = Offset(size.width * 0.78f, size.height * 1.05f))
        }
        Row(Modifier.padding(start = 22.dp, end = 8.dp, top = 12.dp, bottom = 18.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 12.dp)) {
                Text(
                    stringResource(R.string.app_name),
                    color = Color.White,
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 1,
                )
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.86f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, stringResource(R.string.cd_more), tint = Color.White)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_language)) },
                            leadingIcon = { Icon(Icons.Rounded.Language, null) },
                            onClick = { menu = false; onLanguage() },
                        )
                    }
                }
                if (topLevel.isNotEmpty()) {
                    ProgressRing(done = doneTop, total = topLevel.size, modifier = Modifier.padding(end = 14.dp, top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun ProgressRing(done: Int, total: Int, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(if (total == 0) 0f else done / total.toFloat(), tween(700), label = "ring")
    Box(modifier.size(62.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2f
            val box = Size(size.width - stroke, size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.25f), 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
            drawArc(Color.White, -90f, 360f * fraction, false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("$done/$total", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

// ---------------------------------------------------------------- 快速添加

@Composable
private fun QuickAddBar(onSubmit: (String, Priority, QuickDue) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableStateOf(Priority.MEDIUM) }
    var due by rememberSaveable { mutableStateOf(QuickDue.NONE) }
    var focused by remember { mutableStateOf(false) }
    val submit = {
        if (text.isNotBlank()) {
            onSubmit(text, priority, due)
            text = ""
            priority = Priority.MEDIUM
            due = QuickDue.NONE
        }
    }
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = scheme.surface,
        shadowElevation = 3.dp,
        border = BorderStroke(1.5.dp, if (focused) scheme.primary else scheme.outlineVariant),
    ) {
        Column(Modifier.animateContentSize()) {
            Row(Modifier.padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Add, null, tint = scheme.primary)
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                    placeholder = { Text(stringResource(R.string.quick_add_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                AnimatedVisibility(text.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                    FilledIconButton(onClick = { submit() }) {
                        Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.quick_add_submit))
                    }
                }
            }
            AnimatedVisibility(focused || text.isNotBlank(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {
                                priority = when (priority) {
                                    Priority.MEDIUM -> Priority.HIGH
                                    Priority.HIGH -> Priority.LOW
                                    Priority.LOW -> Priority.MEDIUM
                                }
                            },
                            label = { Text(priority.label(), maxLines = 1) },
                            leadingIcon = { Icon(Icons.Rounded.Flag, null, Modifier.size(18.dp), tint = MaterialTheme.todo.priority(priority)) },
                        )
                        AssistChip(
                            onClick = {
                                due = when (due) {
                                    QuickDue.NONE -> QuickDue.TODAY
                                    QuickDue.TODAY -> QuickDue.TOMORROW
                                    QuickDue.TOMORROW -> QuickDue.NONE
                                }
                            },
                            label = {
                                Text(
                                    stringResource(
                                        when (due) {
                                            QuickDue.NONE -> R.string.due_none
                                            QuickDue.TODAY -> R.string.due_today
                                            QuickDue.TOMORROW -> R.string.due_tomorrow
                                        },
                                    ),
                                    maxLines = 1,
                                )
                            },
                            leadingIcon = { Icon(Icons.Rounded.CalendarToday, null, Modifier.size(18.dp)) },
                        )
                    }
                    Text(
                        stringResource(R.string.quick_add_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 筛选

@Composable
private fun FilterRow(
    filter: ListFilter,
    tags: List<String>,
    onStatus: (TodoStatus?) -> Unit,
    onPriority: (Priority?) -> Unit,
    onTag: (String?) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuChip(
            label = filter.status?.label() ?: stringResource(R.string.filter_status),
            active = filter.status != null,
            options = TodoStatus.entries.map { it.label() to it },
            emptyText = null,
            onSelect = onStatus,
        )
        MenuChip(
            label = filter.priority?.label() ?: stringResource(R.string.filter_priority),
            active = filter.priority != null,
            options = Priority.entries.map { it.label() to it },
            emptyText = null,
            onSelect = onPriority,
            dotColor = { MaterialTheme.todo.priority(it) },
        )
        MenuChip(
            label = filter.tag?.let { "# $it" } ?: stringResource(R.string.filter_tag),
            active = filter.tag != null,
            options = tags.map { "# $it" to it },
            emptyText = stringResource(R.string.filter_no_tags),
            onSelect = onTag,
        )
        AnimatedVisibility(filter.isActive) {
            IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, stringResource(R.string.filter_clear)) }
        }
    }
}

@Composable
private fun <T> MenuChip(
    label: String,
    active: Boolean,
    options: List<Pair<String, T>>,
    emptyText: String?,
    onSelect: (T?) -> Unit,
    dotColor: @Composable ((T) -> Color)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = active,
            onClick = { open = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
            trailingIcon = { Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_all)) },
                onClick = { open = false; onSelect(null) },
            )
            if (options.isEmpty() && emptyText != null) {
                DropdownMenuItem(text = { Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant) }, enabled = false, onClick = {})
            }
            options.forEach { (text, value) ->
                DropdownMenuItem(
                    text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = dotColor?.let { color -> { Icon(Icons.Rounded.Flag, null, Modifier.size(18.dp), tint = color(value)) } },
                    onClick = { open = false; onSelect(value) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 分组标题

@Composable
private fun SectionHeader(kind: SectionKind, count: Int, collapsed: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (kind == SectionKind.OVERDUE) MaterialTheme.todo.overdue else MaterialTheme.colorScheme.onSurfaceVariant
    val angle by animateFloatAsState(if (collapsed) -90f else 0f, label = "chevron")
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 22.dp, end = 12.dp, top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(kind.label(), color = color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(count.toString(), color = color.copy(alpha = 0.75f), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Rounded.ExpandMore,
            stringResource(if (collapsed) R.string.cd_section_expand else R.string.cd_section_collapse),
            Modifier.size(22.dp).rotate(angle),
            tint = color,
        )
    }
}

// ---------------------------------------------------------------- 一行待办（左滑完成、右滑删除）

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(
    todo: Todo,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val complete by rememberUpdatedState(onComplete)
    val delete by rememberUpdatedState(onDelete)
    // 动作触发后让行弹回原位（完成会换分组，删除会从数据里消失）
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> complete()
                SwipeToDismissBoxValue.StartToEnd -> delete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )
    val reopen = todo.status == TodoStatus.DONE
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val palette = MaterialTheme.todo
            val direction = state.dismissDirection
            val color by animateColorAsState(
                when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> if (reopen) MaterialTheme.colorScheme.primary else palette.done
                    SwipeToDismissBoxValue.StartToEnd -> palette.overdue
                    SwipeToDismissBoxValue.Settled -> Color.Transparent
                },
                label = "swipe",
            )
            Row(
                Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(color).padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (direction == SwipeToDismissBoxValue.StartToEnd) {
                    Icon(Icons.Rounded.Delete, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.swipe_delete), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.weight(1f))
                if (direction == SwipeToDismissBoxValue.EndToStart) {
                    Text(
                        stringResource(if (reopen) R.string.swipe_reopen else R.string.swipe_complete),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(if (reopen) Icons.Rounded.Refresh else Icons.Rounded.Check, null, tint = Color.White)
                }
            }
        },
    ) {
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodoCard(
    row: ParentRow,
    overdue: Boolean,
    today: Boolean,
    expanded: Boolean,
    now: Long,
    formatter: DueFormatter,
    onOpen: (String) -> Unit,
    onToggleDone: (Todo) -> Unit,
    onToggleExpand: () -> Unit,
) {
    val todo = row.todo
    val scheme = MaterialTheme.colorScheme
    val palette = MaterialTheme.todo
    val finished = todo.status == TodoStatus.DONE || todo.status == TodoStatus.SHELVED
    val container = if (overdue) lerp(scheme.surface, palette.overdueContainer, 0.7f) else scheme.surface
    val border = if (overdue) palette.overdue.copy(alpha = 0.4f) else scheme.outlineVariant
    Surface(shape = RoundedCornerShape(18.dp), color = container, border = BorderStroke(1.dp, border)) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier.height(IntrinsicSize.Min).clickable { onOpen(todo.id) },
                verticalAlignment = Alignment.Top,
            ) {
                PriorityBar(todo.priority)
                Spacer(Modifier.width(8.dp))
                StatusCircle(
                    status = todo.status,
                    accent = palette.priority(todo.priority),
                    contentDescription = stringResource(if (todo.status == TodoStatus.DONE) R.string.cd_mark_reopen else R.string.cd_mark_done),
                    modifier = Modifier.padding(top = 5.dp),
                    onClick = { onToggleDone(todo) },
                )
                Column(Modifier.weight(1f).padding(top = 12.dp, bottom = 12.dp, start = 2.dp)) {
                    Text(
                        todo.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (finished) scheme.onSurfaceVariant else scheme.onSurface,
                        textDecoration = if (todo.status == TodoStatus.DONE) TextDecoration.LineThrough else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (todo.notes.isNotEmpty()) {
                        Text(
                            todo.notes.replace(Regex("\\s+"), " "),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    val showMeta = todo.due != null || todo.status == TodoStatus.DOING || todo.status == TodoStatus.SHELVED ||
                        todo.tags.isNotEmpty() || row.subtasks.isNotEmpty()
                    if (showMeta) {
                        FlowRow(
                            Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (todo.due != null) {
                                MetaPill(
                                    text = formatter.due(todo.due, now),
                                    icon = if (todo.due.isAllDay) Icons.Rounded.CalendarToday else Icons.Rounded.Schedule,
                                    container = if (overdue) palette.overdue.copy(alpha = 0.14f) else if (today) scheme.primaryContainer else scheme.surfaceVariant,
                                    content = if (overdue) palette.overdue else if (today) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                                )
                            }
                            if (todo.status == TodoStatus.DOING) {
                                MetaPill(todo.status.label(), container = scheme.primaryContainer, content = scheme.onPrimaryContainer)
                            }
                            if (todo.status == TodoStatus.SHELVED) {
                                MetaPill(todo.status.label(), container = scheme.surfaceVariant, content = scheme.onSurfaceVariant)
                            }
                            todo.tags.take(2).forEach { tag ->
                                MetaPill("# $tag", container = Color.Transparent, content = scheme.onSurfaceVariant, outline = scheme.outlineVariant)
                            }
                            if (todo.tags.size > 2) {
                                MetaPill("+${todo.tags.size - 2}", container = Color.Transparent, content = scheme.onSurfaceVariant, outline = scheme.outlineVariant)
                            }
                            if (row.subtasks.isNotEmpty()) {
                                MetaPill("${row.doneCount}/${row.subtasks.size}", icon = Icons.Rounded.Checklist)
                            }
                        }
                    }
                }
                if (row.subtasks.isNotEmpty()) {
                    val angle by animateFloatAsState(if (expanded) 180f else 0f, label = "expand")
                    IconButton(onClick = onToggleExpand, modifier = Modifier.padding(top = 2.dp)) {
                        Icon(
                            Icons.Rounded.ExpandMore,
                            stringResource(if (expanded) R.string.cd_collapse_subtasks else R.string.cd_expand_subtasks),
                            Modifier.rotate(angle),
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Spacer(Modifier.width(10.dp))
                }
            }
            AnimatedVisibility(expanded && row.subtasks.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column {
                    HorizontalDivider(Modifier.padding(start = 53.dp, end = 12.dp), color = scheme.outlineVariant.copy(alpha = 0.7f))
                    row.subtasks.forEach { sub ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(sub.id) }.padding(start = 13.dp, end = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusCircle(
                                status = sub.status,
                                accent = palette.priority(sub.priority),
                                contentDescription = stringResource(if (sub.status == TodoStatus.DONE) R.string.cd_mark_reopen else R.string.cd_mark_done),
                                diameter = 20.dp,
                                onClick = { onToggleDone(sub) },
                            )
                            Text(
                                sub.title,
                                Modifier.weight(1f).padding(vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (sub.status == TodoStatus.DONE || sub.status == TodoStatus.SHELVED) scheme.onSurfaceVariant else scheme.onSurface,
                                textDecoration = if (sub.status == TodoStatus.DONE) TextDecoration.LineThrough else null,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (sub.due != null) {
                                val subOverdue = sub.status.isOpen && org.agentos.sample.todo.data.DueTime.isOverdue(sub.due, now, ZoneId.systemDefault())
                                Text(
                                    formatter.due(sub.due, now),
                                    Modifier.padding(start = 8.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (subOverdue) palette.overdue else scheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 空状态

@Composable
private fun EmptyState(filtered: Boolean, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ClipboardIllustration(checked = 0)
        Text(
            stringResource(if (filtered) R.string.empty_filtered_title else R.string.empty_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            stringResource(if (filtered) R.string.empty_filtered_body else R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (filtered) {
            AssistChip(onClick = onClear, label = { Text(stringResource(R.string.filter_clear)) }, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun AllClear(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ClipboardIllustration(checked = 3, size = 160.dp)
        Text(stringResource(R.string.empty_clear_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
        Text(
            stringResource(R.string.empty_clear_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
