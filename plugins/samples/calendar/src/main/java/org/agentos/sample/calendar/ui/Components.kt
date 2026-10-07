package org.agentos.sample.calendar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.agentos.sample.calendar.R
import org.agentos.sample.calendar.data.Occurrence
import org.agentos.sample.calendar.data.Palette
import java.time.LocalDate

/** 空状态：画出来的日历页（不依赖图片资源），配标题与提示。 */
@Composable
fun EmptyState(title: String, hint: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CalendarIllustration(Modifier.size(width = 168.dp, height = 132.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun CalendarIllustration(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val back = MaterialTheme.colorScheme.secondaryContainer
    val page = MaterialTheme.colorScheme.surfaceContainerLowest
    val line = MaterialTheme.colorScheme.outlineVariant
    val teal = MaterialTheme.colorScheme.tertiary
    val ink = MaterialTheme.colorScheme.secondary
    Canvas(modifier.semantics { contentDescription = "" }) {
        val w = size.width
        val h = size.height
        // 底部的柔和光斑
        drawCircle(primaryContainer.copy(alpha = 0.55f), radius = h * 0.46f, center = Offset(w * 0.5f, h * 0.55f))
        // 后面一张略微倾斜的纸
        rotate(9f, pivot = Offset(w * 0.5f, h * 0.55f)) {
            drawRoundRect(back, Offset(w * 0.27f, h * 0.14f), Size(w * 0.46f, h * 0.7f), CornerRadius(w * 0.05f))
        }
        // 前面的日历页
        rotate(-5f, pivot = Offset(w * 0.5f, h * 0.55f)) {
            val left = w * 0.25f
            val top = h * 0.12f
            val pw = w * 0.5f
            val ph = h * 0.74f
            drawRoundRect(line, Offset(left - 1.5f, top - 1.5f), Size(pw + 3f, ph + 3f), CornerRadius(w * 0.055f))
            drawRoundRect(page, Offset(left, top), Size(pw, ph), CornerRadius(w * 0.05f))
            // 红色页眉
            val header = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(left, top, left + pw, top + ph * 0.26f, CornerRadius(w * 0.05f), CornerRadius(w * 0.05f), CornerRadius.Zero, CornerRadius.Zero))
            }
            drawPath(header, primary)
            // 装订环
            for (fx in listOf(0.28f, 0.72f)) {
                drawRoundRect(ink, Offset(left + pw * fx - 3f, top - h * 0.05f), Size(6f, h * 0.1f), CornerRadius(3f))
            }
            // 日期格子
            val gx = pw / 4.6f
            val gy = ph * 0.15f
            val originX = left + pw * 0.16f
            val originY = top + ph * 0.38f
            for (r in 0 until 3) for (c in 0 until 3) {
                val highlight = r == 1 && c == 1
                val cx = originX + gx * c + gx * 0.5f
                val cy = originY + gy * r + gy * 0.4f
                if (highlight) {
                    drawCircle(primary.copy(alpha = 0.2f), gx * 0.45f, Offset(cx, cy))
                    drawCircle(primary, gx * 0.2f, Offset(cx, cy))
                } else {
                    drawCircle(line, gx * 0.14f, Offset(cx, cy))
                }
            }
        }
        // 右下角的小对勾徽章
        val bc = Offset(w * 0.73f, h * 0.8f)
        drawCircle(teal, radius = h * 0.13f, center = bc)
        val tick = Path().apply {
            moveTo(bc.x - h * 0.06f, bc.y + h * 0.005f)
            lineTo(bc.x - h * 0.015f, bc.y + h * 0.05f)
            lineTo(bc.x + h * 0.065f, bc.y - h * 0.045f)
        }
        drawPath(tick, Color.White, style = Stroke(width = h * 0.032f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** 列表里的一行日程：左侧时间、色条、标题、地点与日历。 */
@Composable
fun EventRow(
    occurrence: Occurrence,
    data: CalendarData,
    day: LocalDate,
    fmt: Fmt,
    modifier: Modifier = Modifier,
    query: String = "",
    onClick: () -> Unit,
) {
    val s = occurrence.series
    val color = data.colorOf(s)
    val accent = MaterialTheme.colorScheme.primary
    val (start, end) = fmt.rowTimes(occurrence, day, data.zone)
    val cal = data.calendar(s.calendarId)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(62.dp)) {
                Text(start, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                if (end != null) Text(end, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Box(Modifier.width(4.dp).height(38.dp).clip(RoundedCornerShape(2.dp)).background(color))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    highlight(s.title.ifBlank { stringResource(R.string.no_title) }, query, accent),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = buildList {
                    if (s.location.isNotBlank()) add(s.location)
                    if (cal != null && data.calendars.size > 1) add(cal.name)
                }
                if (sub.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.location.isNotBlank()) {
                            Icon(Icons.Rounded.LocationOn, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(2.dp))
                        }
                        Text(
                            highlight(sub.joinToString(" · "), query, accent),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (s.isRecurring) {
                Icon(Icons.Rounded.Repeat, null, Modifier.padding(start = 8.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 一排颜色圆点。[allowNone] 时第一个是“跟随日历”。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorSwatches(selected: Int?, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier, noneColor: Color? = null, size: Dp = 34.dp) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (noneColor != null) {
            Swatch(noneColor, selected == null, size, ring = true) { onSelect(null) }
        }
        for (c in Palette.colors) {
            Swatch(Color(c), selected == c, size) { onSelect(c) }
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, size: Dp, ring: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .then(if (ring) Modifier.border(2.dp, MaterialTheme.colorScheme.surface, CircleShape).border(3.dp, color.copy(alpha = 0.35f), CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(size * 0.55f), tint = Color.White)
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) { Column(Modifier.padding(vertical = 6.dp)) { content() } }
}

/** 左边一枚图标、右边内容的一行。 */
@Composable
fun IconRow(icon: ImageVector, modifier: Modifier = Modifier, minHeight: Dp = 52.dp, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().height(minHeight).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        content()
    }
}

/** 覆盖在首页之上的二级页面的顶栏。 */
@Composable
fun ScreenTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

/** 日期数字：今天是实心朱红圆，选中是描边的圆角方块。 */
@Composable
fun DayNumber(date: LocalDate, today: LocalDate, selected: Boolean, dimmed: Boolean, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    val cs = MaterialTheme.colorScheme
    val isToday = date == today
    val bg = when {
        isToday -> cs.primary
        selected -> cs.primaryContainer
        else -> Color.Transparent
    }
    val fg = when {
        isToday -> cs.onPrimary
        selected -> cs.onPrimaryContainer
        dimmed -> cs.onSurfaceVariant.copy(alpha = 0.45f)
        else -> cs.onSurface
    }
    Box(modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (isToday || selected) FontWeight.Bold else FontWeight.Medium),
            color = fg,
        )
    }
}
