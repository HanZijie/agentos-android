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
import org.agentos.sample.calendar.data.EventSeries
import java.time.LocalDate

/** 根：首页常驻，二级页面（详情、编辑、日历管理、搜索）从右侧滑入覆盖其上。 */
@Composable
fun CalendarRoot(vm: CalendarViewModel, openRequest: String?, onOpenRequestHandled: () -> Unit) {
    val repo = vm.repo
    val resources = LocalResources.current
    val calendars by repo.calendars.collectAsStateWithLifecycle()
    val events by repo.events.collectAsStateWithLifecycle()
    var today by remember { mutableStateOf(LocalDate.now(repo.zone)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { today = LocalDate.now(repo.zone) }
    val firstDay = remember { CalendarData.systemFirstDayOfWeek() }
    val data = remember(calendars, events, today) { CalendarData(calendars, events, repo.zone, today, firstDay) }
    val fmt = rememberFmt()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun toast(text: String, action: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(text, actionLabel = action, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onAction()
        }
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
        vm.io<EventSeries>(onError = { toast(it) }, onDone = { removed ->
            if (vm.stack.lastOrNull() is Route.Editor) vm.pop()
            toast(resources.getString(R.string.event_deleted, removed.title), resources.getString(R.string.action_undo)) {
                vm.io<EventSeries>(onError = { toast(it) }) { restoreEvent(removed) }
            }
        }) { deleteEvent(s.id) }
    }

    Box(Modifier.fillMaxSize()) {
        HomeScreen(
            vm, data, fmt,
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
                is Route.Detail -> DetailScreen(
                    data, fmt, route.occurrenceId,
                    onBack = { vm.pop() },
                    onEdit = { vm.push(Route.Editor(it.id)) },
                    onDelete = ::deleteEvent,
                )
                is Route.Editor -> EditorScreen(
                    data, fmt, route.eventId, route.prefillStartMs, route.prefillAllDay, vm.selectedDate,
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
                Route.Calendars -> CalendarsScreen(
                    data, onBack = { vm.pop() },
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
                )
                Route.Search -> SearchScreen(data, fmt, onBack = { vm.pop() }, onOpen = { vm.push(Route.Detail(it.id)) })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
    }
}
