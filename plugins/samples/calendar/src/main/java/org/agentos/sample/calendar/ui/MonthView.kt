package org.agentos.sample.calendar.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.agentos.sample.calendar.R
import org.agentos.sample.calendar.data.MonthGrid
import org.agentos.sample.calendar.data.Occurrence
import org.agentos.sample.calendar.ui.theme.LocalExtraColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

private const val PAGES = 2400
private const val MID = PAGES / 2

/** 月视图：上面是可左右滑动的月份网格（日期下带色条），下面是选中那天的日程。 */
@Composable
fun MonthTab(
    data: CalendarData,
    fmt: Fmt,
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    onMonthShown: (YearMonth) -> Unit,
    onOpenEvent: (Occurrence) -> Unit,
    modifier: Modifier = Modifier,
) {
    val base = remember { YearMonth.from(data.today) }
    fun pageOf(m: YearMonth) = MID + ChronoUnit.MONTHS.between(base, m).toInt()
    val pager = rememberPagerState(initialPage = pageOf(YearMonth.from(selectedDate))) { PAGES }

    // 滑动 → 顶栏标题（过半即换）、选中日期（停稳后落到新月份的同一天）
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { onMonthShown(base.plusMonths((it - MID).toLong())) }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val month = base.plusMonths((page - MID).toLong())
            if (YearMonth.from(selectedDate) != month) onSelectDate(month.atDay(minOf(selectedDate.dayOfMonth, month.lengthOfMonth())))
        }
    }
    // 选中日期在别的月份（点了“今天”、点了灰色的邻月日期）→ 翻到那一页
    LaunchedEffect(selectedDate) {
        val target = pageOf(YearMonth.from(selectedDate))
        if (pager.currentPage != target) pager.animateScrollToPage(target)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // 宽屏按宽度定高；小屏要给下面的“当天日程”留出至少 ~230dp，网格单元相应压矮（最矮 44dp）
        val cellHeight = minOf((maxWidth / 7) * 1.1f, (maxHeight - 36.dp - 230.dp) / 6).coerceAtLeast(44.dp)
        Column(Modifier.fillMaxSize()) {
            WeekdayHeader(data.firstDayOfWeek, fmt)
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(cellHeight * 6), beyondViewportPageCount = 1) { page ->
                val month = base.plusMonths((page - MID).toLong())
                MonthGridPage(month, data, selectedDate, cellHeight, onSelectDate)
            }
            Spacer(Modifier.height(6.dp))
            DayPanel(data, fmt, selectedDate, onOpenEvent, Modifier.weight(1f))
        }
    }
}

@Composable
private fun WeekdayHeader(first: DayOfWeek, fmt: Fmt) {
    val weekend = LocalExtraColors.current.weekend
    Row(Modifier.fillMaxWidth().padding(horizontal = 0.dp)) {
        for (d in MonthGrid.weekdayHeaders(first)) {
            Text(
                fmt.weekdayNarrow(d),
                Modifier.weight(1f).padding(vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) weekend else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MonthGridPage(
    month: YearMonth,
    data: CalendarData,
    selected: LocalDate,
    cellHeight: androidx.compose.ui.unit.Dp,
    onSelect: (LocalDate) -> Unit,
) {
    val cells = remember(month, data.firstDayOfWeek) { MonthGrid.cells(month, data.firstDayOfWeek) }
    val byDay = remember(data, cells) { data.byDay(cells.first(), cells.last()) }
    Column(Modifier.fillMaxWidth()) {
        for (week in 0 until 6) {
            Row(Modifier.fillMaxWidth().height(cellHeight)) {
                for (i in 0 until 7) {
                    val date = cells[week * 7 + i]
                    DayCell(
                        date = date,
                        today = data.today,
                        selected = date == selected,
                        dimmed = YearMonth.from(date) != month,
                        events = byDay[date].orEmpty(),
                        data = data,
                        modifier = Modifier.weight(1f),
                        compact = cellHeight < 58.dp,
                        onClick = { onSelect(date) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    today: LocalDate,
    selected: Boolean,
    dimmed: Boolean,
    events: List<Occurrence>,
    data: CalendarData,
    modifier: Modifier,
    compact: Boolean,
    onClick: () -> Unit,
) {
    Column(modifier.fillMaxSize().clickable(onClick = onClick).padding(top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        DayNumber(date, today, selected, dimmed, size = if (compact) 28.dp else 32.dp)
        Spacer(Modifier.height(if (compact) 2.dp else 3.dp))
        val maxBars = if (compact) 2 else 3
        Column(Modifier.fillMaxWidth(0.56f), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val shown = events.take(maxBars)
            for (o in shown) {
                Box(
                    Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(data.colorOf(o.series).copy(alpha = if (dimmed) 0.4f else 1f)),
                )
            }
            if (events.size > maxBars) {
                Box(Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (dimmed) 0.3f else 0.8f)))
            }
        }
    }
}

@Composable
private fun DayPanel(data: CalendarData, fmt: Fmt, day: LocalDate, onOpen: (Occurrence) -> Unit, modifier: Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        AnimatedContent(
            targetState = day,
            transitionSpec = {
                val forward = targetState.isAfter(initialState)
                (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { if (forward) it / 8 else -it / 8 }) togetherWith
                    (fadeOut(tween(120)) + slideOutHorizontally(tween(200)) { if (forward) -it / 8 else it / 8 })
            },
            label = "dayPanel",
        ) { d ->
            val events = remember(data, d) { data.byDay(d, d)[d].orEmpty() }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(fmt.dayLong(d), style = MaterialTheme.typography.titleMedium)
                        val rel = fmt.relativeDay(d, data.today)
                        if (rel != null) Text(rel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (events.isNotEmpty()) {
                        Text(
                            pluralStringResource(R.plurals.event_count, events.size, events.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (events.isEmpty()) {
                    CompactEmpty(stringResource(R.string.empty_day_title), stringResource(R.string.empty_day_hint))
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(events, key = { it.id }) { o ->
                            EventRow(o, data, d, fmt, Modifier.animateItem(), onClick = { onOpen(o) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompactEmpty(title: String, hint: String) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CalendarIllustration(Modifier.size(width = 112.dp, height = 88.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
