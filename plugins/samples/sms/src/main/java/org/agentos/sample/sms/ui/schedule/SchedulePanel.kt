package org.agentos.sample.sms.ui.schedule

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.agentos.sample.sms.R
import org.agentos.sample.sms.agentos.AgentOsError
import org.agentos.sample.sms.agentos.SmsScheduleUseCase
import org.agentos.sample.sms.agentos.ItemKind
import org.agentos.sample.sms.agentos.ItemStatus
import org.agentos.sample.sms.agentos.PanelRules
import org.agentos.sample.sms.agentos.ScheduleItem
import org.agentos.sample.sms.agentos.ScheduleSource
import org.agentos.sample.sms.agentos.SmsLine
import org.agentos.sample.sms.agentos.SmsSchedulePrompt
import org.agentos.sample.sms.agentos.ScheduleState

private const val CALENDAR_PACKAGE = "org.agentos.sample.calendar"
private const val ALARM_PACKAGE = "org.agentos.sample.alarm"
private const val TODO_PACKAGE = "org.agentos.sample.todo"
private const val AGENTOS_PACKAGE = "org.agentos.app"
private const val AGENTOS_URL = "https://github.com/HanZijie/agentos-android"
private const val PREVIEW_LINES = 5

private class Actions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val close: () -> Unit,
    val openApproval: () -> Unit,
    val setIncludeProcessed: (Boolean) -> Unit,
    /** 把编辑框里的任务说明存起来（空白 / 与默认相同 = 恢复默认），返回实际生效的文字。 */
    val saveInstructions: (String) -> String,
    val defaultInstructions: String,
)

/**
 * “让 AgentOS 安排”的底部面板（docs/third-party-acp.md 5）。放在 SmsRoot 这一层：旋转屏幕、切页签、退出会话都不影响进行中的一轮。
 * 进行中（Checking / WaitingAuthorization / Running）不能下拉或点外面关掉，只能点“停止 / 取消”。
 */
@Composable
fun SchedulePanel(useCase: SmsScheduleUseCase) {
    val state by useCase.state.collectAsState()
    if (state != ScheduleState.Idle) PanelSheet(useCase, state)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PanelSheet(useCase: SmsScheduleUseCase, state: ScheduleState) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = remember(useCase) { { target: SheetValue -> target != SheetValue.Hidden || !useCase.state.value.inFlight } },
    )
    val actions = remember(useCase, sheetState) {
        Actions(
            start = { useCase.start() },
            stop = { useCase.stop() },
            close = { scope.launch { sheetState.hide() }.invokeOnCompletion { useCase.dismiss() } },
            openApproval = { useCase.bringApprovalToFront() },
            setIncludeProcessed = { useCase.setIncludeProcessed(it) },
            saveInstructions = { useCase.setInstructions(it) },
            defaultInstructions = useCase.defaultInstructions,
        )
    }
    ModalBottomSheet(
        onDismissRequest = { if (!useCase.state.value.inFlight) useCase.dismiss() },
        sheetState = sheetState,
        containerColor = scheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 22.dp)
                .animateContentSize(tween(220)),
        ) {
            Header(state)
            Spacer(Modifier.height(14.dp))
            PhaseRail(state)
            Spacer(Modifier.height(18.dp))
            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "agent-phase",
            ) { s -> Body(s, actions) }
        }
    }
}

// ---------------------------------------------------------------- 头部与进度条

@Composable
private fun Header(state: ScheduleState) {
    val scheme = MaterialTheme.colorScheme
    val subtitle = when (state) {
        is ScheduleState.Idle, is ScheduleState.Ready -> R.string.agent_panel_subtitle
        is ScheduleState.Checking -> R.string.agent_checking
        is ScheduleState.WaitingAuthorization -> R.string.agent_header_waiting
        is ScheduleState.Running -> if (state.awaitingApproval) R.string.agent_item_status_awaiting else R.string.agent_running_reading
        is ScheduleState.Done -> when {
            state.stopped -> R.string.agent_done_title_stopped
            state.summary.createdCount > 0 -> R.string.agent_done_title
            else -> R.string.agent_done_nothing_title
        }
        is ScheduleState.Error -> errorTitle(state)
    }
    val failed = state is ScheduleState.Error
    val badge by animateColorAsState(if (failed) scheme.errorContainer else scheme.primaryContainer, tween(250), label = "badge")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(badge), contentAlignment = Alignment.Center) {
            Icon(
                if (failed) Icons.Rounded.Warning else Icons.Rounded.AutoAwesome,
                null,
                tint = if (failed) scheme.onErrorContainer else scheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.agent_panel_title), style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
            AnimatedContent(subtitle, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) }, label = "subtitle") { res ->
                Text(
                    stringResource(res),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** 四段细条：连接、允许、创建、完成。出错时整条变成警示色。 */
@Composable
private fun PhaseRail(state: ScheduleState) {
    val scheme = MaterialTheme.colorScheme
    val step = when (state) {
        is ScheduleState.Idle, is ScheduleState.Ready -> 0
        is ScheduleState.Checking -> 1
        is ScheduleState.WaitingAuthorization -> 2
        is ScheduleState.Running -> 3
        is ScheduleState.Done -> 4
        is ScheduleState.Error -> 0
    }
    val error = state is ScheduleState.Error
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(4) { i ->
            val on = !error && i < step
            val color by animateColorAsState(
                when {
                    error -> scheme.error.copy(alpha = 0.35f)
                    on -> scheme.primary
                    else -> scheme.outlineVariant
                },
                tween(300),
                label = "rail",
            )
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

// ---------------------------------------------------------------- 每个状态的内容

@Composable
private fun Body(state: ScheduleState, actions: Actions) {
    when (state) {
        is ScheduleState.Idle -> Unit
        is ScheduleState.Ready -> ReadyBody(state, actions)
        is ScheduleState.Checking -> CheckingBody(actions)
        is ScheduleState.WaitingAuthorization -> WaitingBody(actions)
        is ScheduleState.Running -> RunningBody(state, actions)
        is ScheduleState.Done -> DoneBody(state, actions)
        is ScheduleState.Error -> ErrorBody(state, actions)
    }
}

@Composable
private fun ReadyBody(state: ScheduleState.Ready, actions: Actions) {
    val source = state.source
    // 编辑框里的草稿：点“开始”时才保存（边打字边保存会在清空时立刻把默认说明填回来）。面板关闭后这个状态随之销毁
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val text = draft ?: source.instructions
    Column {
        SourceCard(source)
        val processedCount = source.candidates.count { it.processed }
        if (processedCount > 0) {
            Spacer(Modifier.height(10.dp))
            IncludeProcessedRow(source.includeProcessed, processedCount, actions.setIncludeProcessed)
        }
        Spacer(Modifier.height(10.dp))
        PromptCard(
            text = text,
            customized = text.trim() != actions.defaultInstructions.trim(),
            editing = editing,
            onToggle = { editing = !editing },
            onChange = { draft = SmsSchedulePrompt.clampInstructions(it) },
            onReset = { draft = actions.defaultInstructions },
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Shield, null, Modifier.padding(top = 2.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.agent_ready_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = actions.close, shape = ButtonShape, modifier = Modifier.weight(1f).height(48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = {
                    // 先把编辑过的任务说明存起来、换进预览，再开始：这一轮用的就是屏幕上看到的这份
                    if (draft != null && draft != source.instructions) actions.saveInstructions(draft.orEmpty())
                    actions.start()
                },
                enabled = source.hasText,
                shape = ButtonShape,
                modifier = Modifier.weight(1.4f).height(48.dp),
            ) {
                Text(stringResource(R.string.agent_start), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 将要发送的短信：像一张便签，左边一条墨色竖线；最多列出前 [PREVIEW_LINES] 条，每条带收到的时间。 */
@Composable
private fun SourceCard(source: ScheduleSource) {
    val scheme = MaterialTheme.colorScheme
    val lines = source.lines
    Surface(shape = RoundedCornerShape(18.dp), color = scheme.surfaceContainer) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(scheme.secondary))
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FormatQuote, null, Modifier.size(16.dp), tint = scheme.secondary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        pluralStringResource(R.plurals.agent_ready_count, lines.size, lines.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.secondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(source.address, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                }
                Spacer(Modifier.height(8.dp))
                if (lines.isEmpty()) {
                    Text(stringResource(R.string.agent_ready_none), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        lines.takeLast(PREVIEW_LINES).forEach { PreviewLine(it) }
                    }
                    val hidden = lines.size - PREVIEW_LINES
                    if (hidden > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(pluralStringResource(R.plurals.agent_ready_more, hidden, hidden), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
                val dropped = source.selection.droppedOlder
                if (dropped > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(pluralStringResource(R.plurals.agent_ready_dropped, dropped, dropped), style = MaterialTheme.typography.labelSmall, color = scheme.error)
                }
            }
        }
    }
}

@Composable
private fun PreviewLine(line: SmsLine) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val stamp = remember(line.dateMillis) {
        android.text.format.DateUtils.formatDateTime(
            context, line.dateMillis,
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_ALL,
        )
    }
    Column {
        Text(
            stamp + " · " + stringResource(if (line.incoming) R.string.ask_dir_received else R.string.ask_dir_sent),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        Text(line.body.trim(), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun IncludeProcessedRow(checked: Boolean, processedCount: Int, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.agent_ready_include), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
            Text(
                pluralStringResource(R.plurals.agent_ready_include_count, processedCount, processedCount),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 提示词：收起时一行（“提示词 · 默认 / 已自定义”），展开后是一个可以直接改的文本框和“恢复默认”。 */
@Composable
private fun PromptCard(text: String, customized: Boolean, editing: Boolean, onToggle: () -> Unit, onChange: (String) -> Unit, onReset: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(18.dp), color = scheme.surfaceContainerLow, border = BorderStroke(1.dp, scheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp), tint = scheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.agent_prompt_title), style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (customized) R.string.agent_prompt_state_custom else R.string.agent_prompt_state_default),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (customized) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onToggle) {
                    Text(stringResource(if (editing) R.string.agent_prompt_collapse else R.string.agent_prompt_edit))
                    Spacer(Modifier.width(2.dp))
                    Icon(if (editing) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
                }
            }
            if (editing) {
                Text(stringResource(R.string.agent_prompt_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = onChange,
                    label = { Text(stringResource(R.string.agent_prompt_label)) },
                    minLines = 5,
                    maxLines = 10,
                    supportingText = {
                        Text(stringResource(R.string.agent_prompt_count, text.length, SmsSchedulePrompt.MAX_INSTRUCTION_CHARS))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onReset, enabled = customized) { Text(stringResource(R.string.agent_prompt_reset)) }
                }
            }
        }
    }
}

@Composable
private fun CheckingBody(actions: Actions) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.agent_checking), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = actions.stop, shape = ButtonShape, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun WaitingBody(actions: Actions) {
    val scheme = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse-alpha")
    Column {
        Surface(shape = RoundedCornerShape(18.dp), color = scheme.secondaryContainer.copy(alpha = 0.6f)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(22.dp).alpha(alpha), tint = scheme.onSecondaryContainer)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.agent_waiting_title), style = MaterialTheme.typography.titleSmall, color = scheme.onSecondaryContainer)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.agent_waiting_message), style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer.copy(alpha = 0.85f))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = actions.openApproval, shape = ButtonShape, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.agent_open_prompt), fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = actions.stop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel), color = scheme.onSurfaceVariant) }
    }
}

@Composable
private fun RunningBody(state: ScheduleState.Running, actions: Actions) {
    val scheme = MaterialTheme.colorScheme
    Column {
        if (state.items.isEmpty() && state.text.isBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.agent_running_reading), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            }
        }
        AgentSays(state.text)
        if (state.items.isNotEmpty()) {
            Spacer(Modifier.height(if (state.text.isBlank()) 0.dp else 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { state.items.forEach { ToolCard(it) } }
        }
        if (state.awaitingApproval) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.agent_running_approval_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = actions.openApproval, shape = ButtonShape, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.agent_open_confirm), fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = actions.stop, shape = ButtonShape, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(stringResource(R.string.agent_stop))
        }
    }
}

/** Agent 流式说出来的话：小字、可滚动，新的一段来了自动滚到底。 */
@Composable
private fun AgentSays(text: String) {
    if (text.isBlank()) return
    val scheme = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    LaunchedEffect(text.length) { scroll.animateScrollTo(scroll.maxValue) }
    Column {
        Text(stringResource(R.string.agent_running_says), style = MaterialTheme.typography.labelMedium, color = scheme.secondary)
        Spacer(Modifier.height(4.dp))
        Text(
            text.trim(),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().heightIn(max = 96.dp).verticalScroll(scroll),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DoneBody(state: ScheduleState.Done, actions: Actions) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val summary = state.summary
    val calendarApp = remember(summary) { installed(context, CALENDAR_PACKAGE) }
    val alarmApp = remember(summary) { installed(context, ALARM_PACKAGE) }
    val todoApp = remember(summary) { installed(context, TODO_PACKAGE) }
    Column {
        if (summary.createdCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(22.dp), tint = scheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(createdHeadline(summary.eventCount, summary.alarmCount, summary.todoCount), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            }
        } else {
            Text(stringResource(R.string.agent_done_nothing_title), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            Spacer(Modifier.height(10.dp))
            // 没有创建任何东西：显示 Agent 的解释（例如“没有找到明确的时间”）
            Surface(shape = RoundedCornerShape(18.dp), color = scheme.surfaceContainer) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(scheme.secondary))
                    // Agent 的解释可能很长：卡片有高度上限，里面自己滚动，不把整个面板撑满
                    Text(
                        state.text.ifBlank { stringResource(R.string.agent_done_nothing_fallback) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        }
        if (!summary.isEmpty) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { summary.items.forEach { ToolCard(it) } }
        }
        val links = PanelRules.viewLinks(summary, calendarApp, alarmApp, todoApp)
        val showCalendar = links.calendar
        val showAlarm = links.alarm
        val showTodo = links.todo
        if (links.any) {
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showCalendar) {
                    OutlinedButton(onClick = { launch(context, CALENDAR_PACKAGE) }, shape = ButtonShape) {
                        Icon(Icons.Rounded.Event, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.agent_view_calendar))
                    }
                }
                if (showTodo) {
                    OutlinedButton(onClick = { launch(context, TODO_PACKAGE) }, shape = ButtonShape) {
                        Icon(Icons.Rounded.TaskAlt, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.agent_view_todo))
                    }
                }
                if (showAlarm) {
                    OutlinedButton(onClick = { launch(context, ALARM_PACKAGE) }, shape = ButtonShape) {
                        Icon(Icons.Rounded.Alarm, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.agent_view_alarm))
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = actions.close, shape = ButtonShape, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(stringResource(R.string.agent_close), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun createdHeadline(events: Int, alarms: Int, todos: Int): String {
    val parts = buildList {
        if (events > 0) add(pluralStringResource(R.plurals.agent_done_events, events, events))
        if (todos > 0) add(pluralStringResource(R.plurals.agent_done_todos, todos, todos))
        if (alarms > 0) add(pluralStringResource(R.plurals.agent_done_alarms, alarms, alarms))
    }
    return when (parts.size) {
        3 -> stringResource(R.string.agent_done_headline_three, parts[0], parts[1], parts[2])
        2 -> stringResource(R.string.agent_done_headline_both, parts[0], parts[1])
        else -> stringResource(R.string.agent_done_headline_one, parts.firstOrNull() ?: pluralStringResource(R.plurals.agent_done_alarms, 0, 0))
    }
}

@Composable
private fun ErrorBody(state: ScheduleState.Error, actions: Actions) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val agentOsInstalled = remember(state) { installed(context, AGENTOS_PACKAGE) }
    val message = when {
        state.interrupted -> R.string.agent_error_interrupted_message
        else -> errorMessage(state.error)
    }
    Column {
        Row(verticalAlignment = Alignment.Top) {
            Icon(if (state.error == AgentOsError.NOT_INSTALLED) Icons.Rounded.CloudOff else Icons.Rounded.ErrorOutline, null, Modifier.size(22.dp), tint = scheme.error)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(errorTitle(state)), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                if (state.error == AgentOsError.FAILED && !state.detail.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.agent_error_detail, state.detail),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = scheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (!state.summary.isEmpty) {
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.agent_created_before_error), style = MaterialTheme.typography.labelMedium, color = scheme.secondary)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { state.summary.items.forEach { ToolCard(it) } }
        }
        Spacer(Modifier.height(20.dp))
        val actionsFor = PanelRules.errorActions(state.error, state.interrupted, state.source.hasText, agentOsInstalled)
        val openAgentOs = actionsFor.primary == PanelRules.Primary.OPEN_AGENTOS
        val learnMore = actionsFor.primary == PanelRules.Primary.LEARN_MORE
        val retryable = actionsFor.primary == PanelRules.Primary.RETRY
        // 主操作：该做的下一步放第一个且是实心按钮
        when {
            openAgentOs -> Button(onClick = { launch(context, AGENTOS_PACKAGE) }, shape = ButtonShape, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.agent_open_agentos), fontWeight = FontWeight.SemiBold)
            }
            learnMore -> Button(onClick = { openUrl(context, AGENTOS_URL) }, shape = ButtonShape, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.agent_learn_more), fontWeight = FontWeight.SemiBold)
            }
            retryable -> Button(onClick = actions.start, shape = ButtonShape, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.agent_retry), fontWeight = FontWeight.SemiBold)
            }
        }
        if (actionsFor.retryToo) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = actions.start, shape = ButtonShape, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.agent_retry)) }
        }
        TextButton(onClick = actions.close, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (actionsFor.hasNextStep) R.string.action_cancel else R.string.agent_close), color = scheme.onSurfaceVariant)
        }
    }
}

private fun errorTitle(state: ScheduleState.Error): Int = if (state.interrupted) R.string.agent_error_interrupted_title else when (state.error) {
    AgentOsError.NOT_INSTALLED -> R.string.agent_error_not_installed_title
    AgentOsError.NO_MODEL -> R.string.agent_error_no_model_title
    AgentOsError.DENIED -> R.string.agent_error_denied_title
    AgentOsError.AUTHORIZATION_PENDING_TIMEOUT -> R.string.agent_error_auth_timeout_title
    AgentOsError.BUSY -> R.string.agent_error_busy_title
    AgentOsError.RATE_LIMITED -> R.string.agent_error_rate_limited_title
    AgentOsError.TOO_LARGE -> R.string.agent_error_too_large_title
    AgentOsError.DISCONNECTED -> R.string.agent_error_disconnected_title
    AgentOsError.FAILED -> R.string.agent_error_failed_title
}

private fun errorMessage(error: AgentOsError): Int = when (error) {
    AgentOsError.NOT_INSTALLED -> R.string.agent_error_not_installed_message
    AgentOsError.NO_MODEL -> R.string.agent_error_no_model_message
    AgentOsError.DENIED -> R.string.agent_error_denied_message
    AgentOsError.AUTHORIZATION_PENDING_TIMEOUT -> R.string.agent_error_auth_timeout_message
    AgentOsError.BUSY -> R.string.agent_error_busy_message
    AgentOsError.RATE_LIMITED -> R.string.agent_error_rate_limited_message
    AgentOsError.TOO_LARGE -> R.string.agent_error_too_large_message
    AgentOsError.DISCONNECTED -> R.string.agent_error_disconnected_message
    AgentOsError.FAILED -> R.string.agent_error_failed_message
}

// ---------------------------------------------------------------- 工具卡片

/** 一项的卡片：左边是种类图标，中间是标题 + 时间 / 重复，右下是状态小标签。 */
@Composable
private fun ToolCard(item: ScheduleItem) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val container by animateColorAsState(
        when (item.status) {
            ItemStatus.CREATED -> scheme.primaryContainer.copy(alpha = 0.35f)
            ItemStatus.AWAITING_APPROVAL -> scheme.secondaryContainer.copy(alpha = 0.45f)
            ItemStatus.FAILED -> scheme.errorContainer.copy(alpha = 0.4f)
            else -> scheme.surfaceContainerLow
        },
        tween(250),
        label = "card",
    )
    val dim = item.status == ItemStatus.DENIED || item.status == ItemStatus.CANCELLED
    val title = when (item.kind) {
        ItemKind.EVENT -> item.event?.title ?: stringResource(R.string.agent_item_event)
        ItemKind.ALARM -> item.alarm?.let { it.label.ifBlank { stringResource(R.string.agent_alarm_unlabeled) } } ?: stringResource(R.string.agent_item_alarm)
        ItemKind.TODO -> item.todo?.title ?: stringResource(R.string.agent_item_todo)
        ItemKind.OTHER -> stringResource(R.string.agent_item_other)
    }
    val detail = when (item.kind) {
        ItemKind.EVENT -> item.event?.let { ScheduleFormat.eventWhen(context, it) }
        ItemKind.ALARM -> item.alarm?.let { ScheduleFormat.alarmWhen(context, it) }
        ItemKind.TODO -> item.todo?.let { ScheduleFormat.todoDue(context, it) }
        ItemKind.OTHER -> null
    }
    Surface(shape = RoundedCornerShape(18.dp), color = container, border = BorderStroke(1.dp, scheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(scheme.surface), contentAlignment = Alignment.Center) {
                Icon(
                    when (item.kind) {
                        ItemKind.EVENT -> Icons.Rounded.Event
                        ItemKind.ALARM -> Icons.Rounded.Alarm
                        ItemKind.TODO -> Icons.Rounded.TaskAlt
                        ItemKind.OTHER -> Icons.Rounded.Build
                    },
                    null,
                    Modifier.size(20.dp),
                    tint = if (dim) scheme.onSurfaceVariant else scheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (dim) scheme.onSurfaceVariant else scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
                }
                StatusChip(item.status, Modifier.padding(top = 7.dp))
                if (item.status == ItemStatus.FAILED && !item.message.isNullOrBlank()) {
                    Text(item.message, style = MaterialTheme.typography.bodySmall, color = scheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: ItemStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (status) {
        ItemStatus.AWAITING_APPROVAL -> scheme.secondaryContainer to scheme.onSecondaryContainer
        ItemStatus.CREATING -> scheme.surfaceVariant to scheme.onSurfaceVariant
        ItemStatus.CREATED -> scheme.primaryContainer to scheme.onPrimaryContainer
        ItemStatus.DENIED, ItemStatus.CANCELLED -> scheme.surfaceVariant to scheme.onSurfaceVariant
        ItemStatus.FAILED -> scheme.errorContainer to scheme.onErrorContainer
    }
    val label = when (status) {
        ItemStatus.AWAITING_APPROVAL -> R.string.agent_item_status_awaiting
        ItemStatus.CREATING -> R.string.agent_item_status_creating
        ItemStatus.CREATED -> R.string.agent_item_status_created
        ItemStatus.DENIED -> R.string.agent_item_status_denied
        ItemStatus.FAILED -> R.string.agent_item_status_failed
        ItemStatus.CANCELLED -> R.string.agent_item_status_cancelled
    }
    val pulse = rememberInfiniteTransition(label = "chip")
    val alpha by pulse.animateFloat(0.5f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "chip-alpha")
    Row(
        modifier.clip(RoundedCornerShape(50)).background(bg).padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (status) {
            ItemStatus.CREATING -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.8.dp, color = fg)
            else -> Icon(statusIcon(status), null, Modifier.size(13.dp).then(if (status == ItemStatus.AWAITING_APPROVAL) Modifier.alpha(alpha) else Modifier), tint = fg)
        }
        Spacer(Modifier.width(5.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

private fun statusIcon(status: ItemStatus): ImageVector = when (status) {
    ItemStatus.AWAITING_APPROVAL -> Icons.Rounded.HourglassTop
    ItemStatus.CREATING -> Icons.Rounded.HourglassTop
    ItemStatus.CREATED -> Icons.Rounded.Check
    ItemStatus.DENIED -> Icons.Rounded.Block
    ItemStatus.FAILED -> Icons.Rounded.ErrorOutline
    ItemStatus.CANCELLED -> Icons.Rounded.Close
}

// ---------------------------------------------------------------- 杂项

private val ButtonShape = RoundedCornerShape(16.dp)

private fun installed(context: Context, pkg: String): Boolean = context.packageManager.getLaunchIntentForPackage(pkg) != null

private fun launch(context: Context, pkg: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}
