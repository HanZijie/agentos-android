package org.agentos.sample.calendar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.agentos.sample.calendar.R
import org.agentos.sample.calendar.data.CalendarIds
import org.agentos.sample.calendar.data.EventSeries
import java.time.LocalDate

/** 根：首页常驻，二级页面（详情、编辑、日历管理、设置、搜索）从右侧滑入覆盖其上。 */
@Composable
fun CalendarRoot(vm: CalendarViewModel, openRequest: String?, onOpenRequestHandled: () -> Unit) {
    val repo = vm.repo
    val resources = LocalResources.current
    val calendars by repo.calendars.collectAsStateWithLifecycle()
    val snapshot by repo.window.collectAsStateWithLifecycle()
    val access by repo.systemAccess.collectAsStateWithLifecycle()
    val defaultWriteId by repo.defaultWriteId.collectAsStateWithLifecycle()
    var today by remember { mutableStateOf(LocalDate.now(repo.zone)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        today = LocalDate.now(repo.zone)
        // 从系统日历 App / 设置回来：别处可能改过数据（Provider 的变化通知在 API 36 上要晚好几秒才到，这里兜底）
        repo.refresh()
    }
    val firstDay = remember { CalendarData.systemFirstDayOfWeek() }
    val data = remember(calendars, snapshot, today, access, defaultWriteId) { CalendarData(calendars, snapshot, repo.zone, today, firstDay, access, defaultWriteId) }
    val fmt = rememberFmt()
    val permission = rememberCalendarPermission(repo)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun toast(text: String, action: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(text, actionLabel = action, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onAction()
        }
    }

    // 界面需要哪段时间的数据：随标签页、选中日期、议程展开长度变化；仓库在后台加载，完成后 snapshot 更新
    LaunchedEffect(vm.tab, vm.selectedDate, vm.agendaDays, today, firstDay) {
        val (from, to) = WindowRange.millis(WindowRange.dates(vm.tab, vm.selectedDate, today, vm.agendaDays, firstDay), repo.zone)
        repo.setWindow(from, to)
    }

    // 通知点进来：直接打开那条日程
    LaunchedEffect(openRequest) {
        if (openRequest != null) {
            while (vm.stack.isNotEmpty()) vm.pop()
            vm.push(Route.Detail(openRequest))
            onOpenRequestHandled()
        }
    }

    BackHandler(enabled = vm.stack.isNotEmpty()) { vm.pop() }

    fun deleteEvent(s: EventSeries) {
        val cal = data.calendar(s.calendarId)
        vm.io<EventSeries>(onError = { toast(it) }, onDone = { removed ->
            if (vm.stack.lastOrNull() is Route.Editor) vm.pop()
            when {
                // 本机日历：可以撤销（原样放回）
                !CalendarIds.isSystem(removed.calendarId) ->
                    toast(resources.getString(R.string.event_deleted, removed.title), resources.getString(R.string.action_undo)) {
                        vm.io<EventSeries>(onError = { toast(it) }) { restoreEvent(removed) }
                    }
                // 账号日历：删除已经交给系统同步，重新插入会丢参会人和同步记录，所以不提供撤销
                cal?.isAccountCalendar == true -> toast(resources.getString(R.string.event_deleted_account, removed.title, cal.account.ifBlank { fmt.sourceLabel(cal) }))
                else -> toast(resources.getString(R.string.event_deleted_system, removed.title))
            }
        }) { deleteEvent(s.id) }
    }

    Box(Modifier.fillMaxSize()) {
        HomeScreen(
            vm, data, fmt, permission,
            onOpen = { vm.push(Route.Detail(it.id)) },
            onAdd = { startMs, allDay -> vm.push(Route.Editor(null, startMs, allDay)) },
            onSearch = { vm.push(Route.Search) },
            onCalendars = { vm.push(Route.Calendars) },
        )
        AnimatedContent(
            targetState = vm.stack.lastOrNull(),
            transitionSpec = {
                if (targetState == null) {
                    fadeIn(tween(1)) togetherWith (slideOutHorizontally(tween(260)) { it / 4 } + fadeOut(tween(220)))
                } else {
                    (slideInHorizontally(tween(300)) { it / 3 } + fadeIn(tween(240))) togetherWith fadeOut(tween(160))
                }
            },
            modifier = Modifier.fillMaxSize(),
            label = "routes",
        ) { route ->
            when (route) {
                null -> Box(Modifier.fillMaxSize())
                is Route.Detail -> {
                    // 数据版本变了（别处改了 / 云端同步）就重读；读不到了（别处删了）自动退出
                    val loaded by produceState<Loaded<org.agentos.sample.calendar.data.Occurrence>>(Loaded.Loading, route.occurrenceId, snapshot.version) {
                        value = vm.loadOccurrence(route.occurrenceId)?.let { Loaded.Ok(it) } ?: Loaded.Gone
                    }
                    when (val l = loaded) {
                        Loaded.Loading -> Box(Modifier.fillMaxSize())
                        Loaded.Gone -> LaunchedEffect(Unit) { vm.pop() }
                        is Loaded.Ok -> DetailScreen(
                            data, fmt, l.value,
                            onBack = { vm.pop() },
                            onEdit = { vm.push(Route.Editor(it.id)) },
                            onDelete = ::deleteEvent,
                        )
                    }
                }
                is Route.Editor -> {
                    val original by produceState<Loaded<EventSeries?>>(if (route.eventId == null) Loaded.Ok(null) else Loaded.Loading, route.eventId) {
                        if (route.eventId != null) value = vm.loadEvent(route.eventId.substringBefore('@'))?.let { Loaded.Ok(it) } ?: Loaded.Gone
                    }
                    when (val l = original) {
                        Loaded.Loading -> Box(Modifier.fillMaxSize())
                        Loaded.Gone -> LaunchedEffect(Unit) { vm.pop() }
                        is Loaded.Ok -> EditorScreen(
                            data, fmt, l.value, route.prefillStartMs, route.prefillAllDay, vm.selectedDate,
                            defaultCalendarId = data.defaultWriteId.ifEmpty { repo.defaultWriteCalendar().id },
                            save = { draft, onError, onDone ->
                                vm.io<EventSeries>(onError = onError, onDone = onDone) { saveEvent(draft) }
                            },
                            delete = ::deleteEvent,
                            onClose = { vm.pop() },
                            onSaved = { date ->
                                vm.pop()
                                vm.selectedDate = date
                                toast(resources.getString(R.string.event_saved))
                            },
                        )
                    }
                }
                Route.Calendars -> CalendarsScreen(
                    data, fmt, vm,
                    onBack = { vm.pop() },
                    onSettings = { vm.push(Route.Settings) },
                    onToggle = { cal, visible -> vm.io<Unit>(onError = { toast(it) }) { updateCalendar(cal.id, visible = visible) } },
                    onSave = { existing, name, color, onError, onDone ->
                        vm.io<Unit>(onError = onError, onDone = { onDone() }) {
                            if (existing == null) createCalendar(name, color) else updateCalendar(existing.id, name = name, color = color)
                            Unit
                        }
                    },
                    onDelete = { cal ->
                        vm.io<Int>(onError = { toast(it) }, onDone = { n -> toast(resources.getString(R.string.calendar_deleted, cal.name, n)) }) { deleteCalendar(cal.id) }
                    },
                    onSetDefault = { cal -> vm.io<Unit>(onError = { toast(it) }) { setDefaultWriteCalendar(cal?.id) } },
                )
                Route.Settings -> SettingsScreen(
                    data, fmt, permission, repo,
                    onBack = { vm.pop() },
                    onSetDefault = { cal -> vm.io<Unit>(onError = { toast(it) }) { setDefaultWriteCalendar(cal?.id) } },
                )
                Route.Search -> SearchScreen(data, fmt, vm, onBack = { vm.pop() }, onOpen = { vm.push(Route.Detail(it.id)) })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
    }
}
