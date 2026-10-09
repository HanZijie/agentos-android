package org.agentos.sample.todo.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.agentos.sample.todo.data.Priority
import org.agentos.sample.todo.data.TodoStatus
import org.agentos.sample.todo.ui.theme.todo

/** 行左侧的优先级色条。 */
@Composable
fun PriorityBar(priority: Priority, modifier: Modifier = Modifier) {
    Box(modifier.width(5.dp).fillMaxHeight().background(MaterialTheme.todo.priority(priority)))
}

/**
 * 状态圆圈：待办是空心圆，进行中是半填充，搁置是虚线圈，已完成是实心绿圈 + 一笔一笔画出来的对勾。
 * [onClick] 为 null 时只展示。点击区域比圆大（40dp）。
 */
@Composable
fun StatusCircle(
    status: TodoStatus,
    accent: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 26.dp,
    onClick: (() -> Unit)? = null,
) {
    val done = MaterialTheme.todo.done
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val progress by animateFloatAsState(if (status == TodoStatus.DONE) 1f else 0f, tween(280, easing = FastOutSlowInEasing), label = "check")
    val hit = if (diameter < 30.dp) 40.dp else diameter + 14.dp
    Box(
        modifier
            .size(hit)
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Checkbox, onClick = onClick) else Modifier)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter)) {
            val w = size.minDimension
            val stroke = w * 0.085f
            val ring = when (status) {
                TodoStatus.TODO -> if (progress > 0f) done else accent.copy(alpha = 0.9f)
                TodoStatus.DOING -> primary
                TodoStatus.SHELVED -> outline.copy(alpha = 0.7f)
                TodoStatus.DONE -> done
            }
            if (progress > 0f) {
                drawCircle(done.copy(alpha = progress), radius = w / 2f)
            }
            if (progress < 1f) {
                drawCircle(
                    ring.copy(alpha = ring.alpha * (1f - progress)),
                    radius = (w - stroke) / 2f,
                    style = Stroke(
                        width = stroke,
                        pathEffect = if (status == TodoStatus.SHELVED) PathEffect.dashPathEffect(floatArrayOf(w * 0.14f, w * 0.12f)) else null,
                    ),
                )
            }
            if (status == TodoStatus.DOING) {
                // 半填充：下半圆实心，一眼看出“做到一半”
                drawArc(
                    color = primary,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(stroke * 1.9f, stroke * 1.9f),
                    size = Size(w - stroke * 3.8f, w - stroke * 3.8f),
                )
            }
            if (progress > 0f) {
                val path = Path().apply {
                    moveTo(w * 0.27f, w * 0.53f)
                    lineTo(w * 0.43f, w * 0.69f)
                    lineTo(w * 0.74f, w * 0.34f)
                }
                val measure = PathMeasure().apply { setPath(path, false) }
                val drawn = Path()
                measure.getSegment(0f, measure.length * progress, drawn, true)
                drawPath(drawn, Color.White, style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** 小胶囊：图标 + 文字。截止、进度、标签、状态共用。 */
@Composable
fun MetaPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    outline: Color? = null,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .then(if (outline != null) Modifier.border(1.dp, outline, RoundedCornerShape(50)) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(13.dp), tint = content)
        Text(text, color = content, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
