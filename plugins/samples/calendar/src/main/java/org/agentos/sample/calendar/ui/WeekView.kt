package org.agentos.sample.calendar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.agentos.sample.calendar.data.Occurrence
import org.agentos.sample.calendar.ui.theme.LocalExtraColors
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

private const val WEEK_PAGES = 5200
private const val WEEK_MID = WEEK_PAGES / 2
private val HOUR_HEIGHT = 56.dp
private val LABEL_WIDTH = 46.dp

fun weekStartOf(date: LocalDate, first: DayOfWeek): LocalDate = date.with(TemporalAdjusters.previousOrSame(first))

/** 周视图：七列时间轴，全天日程在上方一行；点空白处按半小时取整新建，点日程看详情。 */
@Composable
fun WeekTab(
    data: CalendarData,
    fmt: Fmt,
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    onWeekShown: (LocalDate) -> Unit,
    onOpenEvent: (Occurrence) -> Unit,
    onCreateAt: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val base = remember { weekStartOf(data.today, data.firstDayOfWeek) }
    fun pageOf(date: LocalDate) = WEEK_MID + ChronoUnit.WEEKS.between(base, weekStartOf(date, data.firstDayOfWeek)).toInt()
    val pager = rememberPagerState(initialPage = pageOf(selectedDate)) { WEEK_PAGES }
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    LaunchedEffect(Unit) {
        val hour = if (weekStartOf(data.today, data.firstDayOfWeek) == weekStartOf(selectedDate, data.firstDayOfWeek)) {
            (Instant.now().atZone(data.zone).hour - 1).coerceAtLeast(0)
        } else 7
        scroll.scrollTo(with(density) { (HOUR_HEIGHT * hour).roundToPx() })
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { onWeekShown(base.plusWeeks((it - WEEK_MID).toLong())) }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val start = base.plusWeeks((page - WEEK_MID).toLong())
            if (weekStartOf(selectedDate, data.firstDayOfWeek) != start) {
                onSelectDate(start.plusDays(((selectedDate.dayOfWeek.value - data.firstDayOfWeek.value + 7) % 7).toLong()))
            }
        }
    }
    LaunchedEffect(selectedDate) {
        val target = pageOf(selectedDate)
        if (pager.currentPage != target) pager.animateScrollToPage(target)
    }

    HorizontalPager(state = pager, modifier = modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
        val start = base.plusWeeks((page - WEEK_MID).toLong())
        WeekPage(start, data, fmt, scroll, selectedDate, onSelectDate, onOpenEvent, onCreateAt)
    }
}

@Composable
private fun WeekPage(
    start: LocalDate,
    data: CalendarData,
    fmt: Fmt,
    scroll: androidx.compose.foundation.ScrollState,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onOpen: (Occurrence) -> Unit,
    onCreateAt: (Long) -> Unit,
) {
    val days = remember(start) { List(7) { start.plusDays(it.toLong()) } }
    val byDay = remember(data, start) { data.byDay(days.first(), days.last()) }
    val allDay = remember(byDay) { days.associateWith { byDay[it].orEmpty().filter { o -> o.allDay } } }
    val timed = remember(byDay) { days.associateWith { byDay[it].orEmpty().filter { o -> !o.allDay } } }
    val weekend = LocalExtraColors.current.weekend

    Column(Modifier.fillMaxSize()) {
        // 星期 + 日期
        Row(Modifier.fillMaxWidth().padding(start = LABEL_WIDTH).padding(bottom = 4.dp)) {
            for (d in days) {
                Column(Modifier.weight(1f).clickable { onSelect(d) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        fmt.weekdayShort(d.dayOfWeek),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (d == data.today) MaterialTheme.colorScheme.primary else if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) weekend else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    DayNumber(d, data.today, d == selected, dimmed = false, size = 32.dp)
                }
            }
        }
        // 全天日程
        val maxAllDay = allDay.values.maxOf { it.size }
        if (maxAllDay > 0) {
            Row(Modifier.fillMaxWidth().padding(start = LABEL_WIDTH).padding(bottom = 6.dp)) {
                for (d in days) {
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
                        val list = allDay.getValue(d)
                        for (o in list.take(2)) {
                            val c = data.colorOf(o.series)
                            Box(
                                Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(5.dp)).background(c.copy(alpha = 0.9f)).clickable { onOpen(o) }.padding(horizontal = 4.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(o.series.title, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (list.size > 2) Text("+${list.size - 2}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
        // 时间轴
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            TimeGrid(days, timed, data, fmt, onOpen, onCreateAt)
        }
    }
}

@Composable
private fun TimeGrid(
    days: List<LocalDate>,
    timed: Map<LocalDate, List<Occurrence>>,
    data: CalendarData,
    fmt: Fmt,
    onOpen: (Occurrence) -> Unit,
    onCreateAt: (Long) -> Unit,
) {
    val extra = LocalExtraColors.current
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().height(HOUR_HEIGHT * 24 + 12.dp).padding(bottom = 12.dp)) {
        Box(Modifier.width(LABEL_WIDTH).fillMaxHeight()) {
            for (h in 1..23) {
                Text(
                    fmt.hourLabel(h),
                    Modifier.offset(y = HOUR_HEIGHT * h - 8.dp).width(LABEL_WIDTH - 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val colWidth = maxWidth / days.size
            Canvas(Modifier.fillMaxSize()) {
                val hourPx = HOUR_HEIGHT.toPx()
                for (h in 0..24) drawLine(extra.gridLine, Offset(0f, h * hourPx), Offset(size.width, h * hourPx), strokeWidth = 1f)
                for (i in 0..days.size) drawLine(extra.gridLine, Offset(i * size.width / days.size, 0f), Offset(i * size.width / days.size, size.height), strokeWidth = 1f)
            }
            Row(Modifier.fillMaxSize()) {
                for (d in days) {
                    DayColumn(d, timed[d].orEmpty(), data, colWidth, Modifier.weight(1f), onOpen, onCreateAt)
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    date: LocalDate,
    events: List<Occurrence>,
    data: CalendarData,
    width: Dp,
    modifier: Modifier,
    onOpen: (Occurrence) -> Unit,
    onCreateAt: (Long) -> Unit,
) {
    val zone = data.zone
    val dayStart = remember(date, zone) { date.atStartOfDay(zone).toInstant().toEpochMilli() }
    val dayEnd = remember(date, zone) { date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }
    val placed = remember(events, dayStart, dayEnd) {
        val spans = events.map { o ->
            val s = ((maxOf(o.startMs, dayStart) - dayStart) / 60_000L).toInt().coerceIn(0, 1439)
            val e = ((minOf(o.endMs, dayEnd) - dayStart) / 60_000L).toInt().coerceIn(s + 1, 1440)
            s to maxOf(e, minOf(s + 30, 1440))
        }
        events.zip(spans).zip(WeekLayout.lanes(spans)).map { (oe, lane) -> Triple(oe.first, oe.second, lane) }
    }
    val hourPx = with(LocalDensity.current) { HOUR_HEIGHT.toPx() }
    Box(
        modifier.fillMaxHeight().pointerInput(date, hourPx) {
            detectTapGestures { pos ->
                val minute = ((pos.y / hourPx) * 60f / 30f).toInt() * 30
                onCreateAt(dayStart + minute.coerceIn(0, 23 * 60 + 30) * 60_000L)
            }
        },
    ) {
        for ((o, span, lane) in placed) {
            val color = data.colorOf(o.series)
            val h = HOUR_HEIGHT * ((span.second - span.first) / 60f)
            val w = width / lane.count
            Box(
                Modifier
                    .offset(x = w * lane.index, y = HOUR_HEIGHT * (span.first / 60f))
                    .width(w)
                    .height(h)
                    .padding(horizontal = 1.dp, vertical = 0.5.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color.copy(alpha = 0.2f))
                    .clickable { onOpen(o) },
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(color))
                Text(
                    o.series.title,
                    Modifier.padding(start = 6.dp, end = 2.dp, top = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp, lineHeight = 12.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = (h.value / 13f).toInt().coerceIn(1, 8),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 当前时间线
        if (date == data.today) {
            val nowMin = ((System.currentTimeMillis() - dayStart) / 60_000L).toInt().coerceIn(0, 1440)
            val line = LocalExtraColors.current.nowLine
            Box(Modifier.offset(y = HOUR_HEIGHT * (nowMin / 60f) - 4.dp).fillMaxWidth().height(8.dp)) {
                Box(Modifier.align(Alignment.CenterStart).size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(line))
                Box(Modifier.align(Alignment.Center).fillMaxWidth().height(2.dp).background(line))
            }
        }
    }
}
