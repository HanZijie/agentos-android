package org.agentos.sample.alarm.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agentos.sample.alarm.R
import org.agentos.sample.alarm.data.Alarm
import org.agentos.sample.alarm.data.AlarmRepository
import org.agentos.sample.alarm.ui.theme.LocalAlarmExtras
import org.agentos.sample.alarm.ui.theme.TimeNumeralStyle

/** 列表页：下次响铃卡片 + 闹钟卡片（滑动删除、撤销）+ 空状态。 */
@Composable
fun AlarmListScreen(
    repository: AlarmRepository,
    alarms: List<Alarm>,
    ringing: Alarm?,
    banners: @Composable () -> Unit,
    onNew: () -> Unit,
    onEdit: (Alarm) -> Unit,
    onDismissRinging: () -> Unit,
    onOpenReliability: () -> Unit,
    onOpenLanguage: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val deletedText = stringResource(R.string.deleted_snackbar)
    val undoText = stringResource(R.string.action_undo)

    // 每到整分钟刷新一次“还有多久”
    val minuteTick by produceState(0L) {
        var tick = 0L
        while (true) {
            val now = repository.now()
            delay(((60 - now.second) * 1000L - now.nano / 1_000_000L).coerceAtLeast(500L))
            tick++
            value = tick
        }
    }
    val now = remember(alarms, minuteTick) { repository.now() }
    val next = remember(alarms, minuteTick) { repository.nextAlarm() }

    // 滑动状态是 rememberSaveable：LazyColumn 会按条目 key 保留“已滑到删除位”的状态，撤销后同 id 的卡片
    // 会带着它回来并再次触发删除。删除时给该 id 换一个组合 key，恢复出来的卡片就是全新状态
    val swipeEpochs = remember { mutableStateMapOf<String, Int>() }

    fun delete(alarm: Alarm) {
        scope.launch {
            val deleted = withContext(Dispatchers.IO) { runCatching { repository.delete(alarm.id) }.getOrNull() } ?: return@launch
            swipeEpochs[alarm.id] = (swipeEpochs[alarm.id] ?: 0) + 1
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = deletedText,
                actionLabel = undoText,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) { repository.restore(deleted) }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 0.dp,
                bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                Row(
                    Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 12.dp, bottom = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.list_title),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    OverflowMenu(onOpenReliability, onOpenLanguage)
                }
            }
            item(key = "banners") { banners() }
            item(key = "ringing") {
                AnimatedVisibility(
                    visible = ringing != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    if (ringing != null) RingingBanner(ringing, onDismissRinging)
                }
            }
            item(key = "hero") {
                HeroCard(context = context, now = now, next = next?.let { it.alarm to repository.nextFireOf(it.alarm) })
            }
            if (alarms.isEmpty()) {
                item(key = "empty") { EmptyState() }
            } else {
                items(alarms, key = { it.id }) { alarm ->
                    key(swipeEpochs[alarm.id] ?: 0) {
                        SwipeableAlarmCard(
                            alarm = alarm,
                            nextFire = repository.nextFireOf(alarm),
                            now = now,
                            modifier = Modifier.animateItem(),
                            onClick = { onEdit(alarm) },
                            onToggle = { enabled ->
                                scope.launch(Dispatchers.IO) { runCatching { repository.setEnabled(alarm.id, enabled) } }
                            },
                            onDelete = { delete(alarm) },
                        )
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onNew,
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.fab_new), style = MaterialTheme.typography.labelLarge) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(20.dp),
        )
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 92.dp, start = 16.dp, end = 16.dp),
        )
    }
}

/** 顶栏右侧的“⋮”菜单：保证准时响铃（检查页）、语言（系统的应用语言设置）。 */
@Composable
private fun OverflowMenu(onOpenReliability: () -> Unit, onOpenLanguage: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.menu_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_reliability)) },
                leadingIcon = { Icon(Icons.Rounded.TaskAlt, contentDescription = null) },
                onClick = { expanded = false; onOpenReliability() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_language)) },
                leadingIcon = { Icon(Icons.Rounded.Language, contentDescription = null) },
                onClick = { expanded = false; onOpenLanguage() },
            )
        }
    }
}

/** 顶部渐变卡片：下次响铃的倒计时。 */
@Composable
private fun HeroCard(context: Context, now: ZonedDateTime, next: Pair<Alarm, ZonedDateTime?>?) {
    val extras = LocalAlarmExtras.current
    val fireAt = next?.second
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(extras.heroStart, extras.heroEnd)))
            .padding(horizontal = 24.dp, vertical = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.hero_next_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = extras.onHeroVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (next != null && fireAt != null) {
                    Text(
                        text = formatRingsIn(context, now, fireAt),
                        style = MaterialTheme.typography.headlineSmall,
                        color = extras.onHero,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.hero_when,
                            dayLabel(context, now, fireAt),
                            formatClock(context, fireAt.hour, fireAt.minute),
                        ) + next.first.label.takeIf { it.isNotBlank() }?.let { "  ·  $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.onHeroVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.hero_none_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = extras.onHero,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.hero_none_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.onHeroVariant,
                    )
                }
            }
            // 窄屏（< 400dp）把右侧装饰图标让给文字，免得标题被折成两行
            val widthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
            if (widthDp >= 400.dp) {
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(extras.onHero.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Alarm, contentDescription = null, tint = extras.onHero, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun RingingBanner(alarm: Alarm, onDismiss: () -> Unit) {
    val title = alarm.label.ifBlank { stringResource(R.string.alarm_default_title) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Notifications, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.ringing_banner, title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AlarmIllustration()
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwipeableAlarmCard(
    alarm: Alarm,
    nextFire: ZonedDateTime?,
    now: ZonedDateTime,
    modifier: Modifier,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    // 滑到底（settle 在 EndToStart）就删除；列表项随即被移出，状态一并丢弃
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete()
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val active = state.targetValue == SwipeToDismissBoxValue.EndToStart
            val color by animateColorAsState(
                if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.errorContainer,
                animationSpec = tween(180),
                label = "swipe-bg",
            )
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(color).padding(end = 28.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.cd_delete),
                    tint = if (active) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
        },
    ) {
        AlarmCard(alarm, nextFire, now, onClick, onToggle)
    }
}

@Composable
private fun AlarmCard(
    alarm: Alarm,
    nextFire: ZonedDateTime?,
    now: ZonedDateTime,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val active = alarm.enabled || alarm.snoozedUntil != null
    val contentAlpha by animateFloatAsState(
        if (active) 1f else 0.5f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "card-alpha",
    )
    val container by animateColorAsState(
        if (active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "card-bg",
    )
    val parts = remember(alarm.hour, alarm.minute) { timeParts(context, alarm.hour, alarm.minute) }
    val label = alarm.label.ifBlank { null }
    val title = label ?: stringResource(R.string.no_label)

    val toggleDescription = stringResource(R.string.cd_toggle_alarm, title)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = container,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(start = 22.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).alpha(contentAlpha)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        // 中文习惯“上午 7:30”：标记在前还是在后由系统的时间格式决定（见 Format.kt）
                        parts.prefix?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 9.dp, end = 6.dp),
                            )
                        }
                        Text(
                            text = parts.main,
                            style = TimeNumeralStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        parts.suffix?.let {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = it,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 9.dp),
                            )
                        }
                    }
                    Text(
                        text = if (label == null) repeatSummary(context, alarm.days) else "$title  ·  ${repeatSummary(context, alarm.days)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = alarm.enabled || alarm.snoozedUntil != null,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = toggleDescription
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().alpha(contentAlpha),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DayDots(alarm.days, Modifier.weight(1f))
            }
            AnimatedVisibility(visible = active && nextFire != null) {
                if (nextFire != null) {
                    Text(
                        text = if (alarm.snoozedUntil != null && alarm.snoozedUntil > now.toInstant().toEpochMilli()) {
                            stringResource(R.string.snoozed_until, formatDuration(context, java.time.Duration.between(now, nextFire)))
                        } else {
                            formatRingsIn(context, now, nextFire)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

/** 一周七个字的小圆点：重复日高亮，其余淡灰。只响一次的闹钟不显示。 */
@Composable
private fun DayDots(days: Set<DayOfWeek>, modifier: Modifier = Modifier) {
    if (days.isEmpty()) return
    val letters = stringArrayResource(R.array.weekday_short)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { day ->
            val on = day in days
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letters[day.value - 1],
                    fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
