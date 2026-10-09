package org.agentos.sample.calendar.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.ViewWeek
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.agentos.sample.calendar.R
import org.agentos.sample.calendar.data.Occurrence
import org.agentos.sample.calendar.reminder.ReminderNotifications
import java.time.LocalDate
import java.time.YearMonth

private data class TitleKey(val top: String, val big: String, val order: Long)

@Composable
fun HomeScreen(
    vm: CalendarViewModel,
    data: CalendarData,
    fmt: Fmt,
    permission: CalendarPermissionState,
    onOpen: (Occurrence) -> Unit,
    onAdd: (startMs: Long?, allDay: Boolean) -> Unit,
    onSearch: () -> Unit,
    onCalendars: () -> Unit,
) {
    val context = LocalContext.current
    var visibleMonth by remember { mutableStateOf(YearMonth.from(vm.selectedDate)) }
    var visibleWeek by remember { mutableStateOf(weekStartOf(vm.selectedDate, data.firstDayOfWeek)) }
    var canPost by remember { mutableStateOf(ReminderNotifications.canPost(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canPost = ReminderNotifications.canPost(context) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canPost = it }
    val anyReminders = data.hasLocalReminders

    val title = when (vm.tab) {
        HomeTab.Month -> TitleKey(fmt.yearText(visibleMonth), fmt.monthName(visibleMonth), visibleMonth.year * 12L + visibleMonth.monthValue)
        HomeTab.Week -> TitleKey(fmt.yearText(YearMonth.from(visibleWeek)), fmt.weekRange(visibleWeek), visibleWeek.toEpochDay())
        HomeTab.Agenda -> TitleKey(fmt.dayMedium(data.today), stringResource(R.string.tab_agenda), 0)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(76.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(
                        targetState = title,
                        modifier = Modifier.weight(1f),
                        transitionSpec = {
                            val dir = if (targetState.order >= initialState.order) 1 else -1
                            (slideInVertically(tween(260)) { dir * it / 3 } + fadeIn(tween(220))) togetherWith
                                (slideOutVertically(tween(200)) { -dir * it / 3 } + fadeOut(tween(120)))
                        },
                        label = "title",
                    ) { t ->
                        Column {
                            Text(t.top, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(t.big, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        }
                    }
                    TodayButton(data.today) { vm.selectedDate = data.today }
                    IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, stringResource(R.string.action_search)) }
                    IconButton(onClick = onCalendars) { Icon(Icons.Rounded.Layers, stringResource(R.string.action_calendars)) }
                }
                if (!permission.granted) CalendarPermissionBanner(permission, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                if (!canPost && anyReminders) {
                    Surface(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.NotificationsOff, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.notif_banner), Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            TextButton(onClick = { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(stringResource(R.string.notif_banner_action)) }
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                for ((tab, icon, label) in listOf(
                    Triple(HomeTab.Month, Icons.Rounded.CalendarMonth, R.string.tab_month),
                    Triple(HomeTab.Week, Icons.Rounded.ViewWeek, R.string.tab_week),
                    Triple(HomeTab.Agenda, Icons.Rounded.ViewAgenda, R.string.tab_agenda),
                )) {
                    NavigationBarItem(
                        selected = vm.tab == tab,
                        onClick = { vm.tab = tab },
                        icon = { Icon(icon, null) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onAdd(null, false) },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(stringResource(R.string.action_new), fontWeight = FontWeight.SemiBold) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AnimatedContent(vm.tab, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "tab") { tab ->
                when (tab) {
                    HomeTab.Month -> MonthTab(
                        data, fmt, vm.selectedDate,
                        onSelectDate = { vm.selectedDate = it },
                        onMonthShown = { visibleMonth = it },
                        onOpenEvent = onOpen,
                    )
                    HomeTab.Week -> WeekTab(
                        data, fmt, vm.selectedDate,
                        onSelectDate = { vm.selectedDate = it },
                        onWeekShown = { visibleWeek = it },
                        onOpenEvent = onOpen,
                        onCreateAt = { onAdd(it, false) },
                    )
                    HomeTab.Agenda -> AgendaTab(data, fmt, vm.agendaDays, { vm.agendaDays = it }, onOpen, onAdd = { onAdd(null, false) })
                }
            }
        }
    }
}

/** “今天”按钮：圆角方块里放今天的日期数字，像一张小日历。 */
@Composable
private fun TodayButton(today: LocalDate, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(end = 4.dp).size(38.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(today.dayOfMonth.toString(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
