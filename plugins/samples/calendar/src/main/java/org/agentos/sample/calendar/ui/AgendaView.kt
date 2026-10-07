package org.agentos.sample.calendar.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.agentos.sample.calendar.R
import org.agentos.sample.calendar.data.Occurrence

/** 议程：从今天起按日期分组的日程流，滚到底自动再加载两个月；日期头吸顶。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgendaTab(
    data: CalendarData,
    fmt: Fmt,
    onOpenEvent: (Occurrence) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var horizon by remember { mutableIntStateOf(60) }
    val byDay = remember(data, horizon) { data.byDay(data.today, data.today.plusDays(horizon.toLong() - 1)) }
    val days = remember(byDay) { byDay.keys.sorted() }
    val list = rememberLazyListState()

    if (days.isEmpty() && horizon >= 360) {
        EmptyState(stringResource(R.string.empty_agenda_title), stringResource(R.string.empty_agenda_hint), modifier.fillMaxSize()) {
            Button(onClick = onAdd) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_add_event))
            }
        }
        return
    }
    if (days.isEmpty()) {
        LaunchedEffect(horizon) { horizon = (horizon * 2).coerceAtMost(720) }
    }

    LazyColumn(modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 112.dp)) {
        if (!byDay.containsKey(data.today) && days.isNotEmpty()) {
            item(key = "today-empty") {
                Text(
                    stringResource(R.string.today_empty_row),
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        for (d in days) {
            stickyHeader(key = "h${d.toEpochDay()}") { AgendaDayHeader(d, data, fmt) }
            val events = byDay.getValue(d)
            items(events.size, key = { "${d.toEpochDay()}-${events[it].id}" }) { i ->
                EventRow(events[i], data, d, fmt, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).animateItem(), onClick = { onOpenEvent(events[i]) })
            }
        }
        if (days.isNotEmpty() && horizon < 720) {
            item(key = "more") {
                LaunchedEffect(horizon) { horizon = (horizon + 60).coerceAtMost(720) }
                Spacer(Modifier.height(1.dp))
            }
        }
    }
}

@Composable
private fun AgendaDayHeader(day: java.time.LocalDate, data: CalendarData, fmt: Fmt) {
    val isToday = day == data.today
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(44.dp)) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Column {
            Text(
                fmt.relativeDay(day, data.today) ?: fmt.weekdayLong(day),
                style = MaterialTheme.typography.titleSmall,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(fmt.dayMedium(day), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
