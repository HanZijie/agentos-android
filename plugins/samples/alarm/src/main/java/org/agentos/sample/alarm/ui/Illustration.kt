package org.agentos.sample.alarm.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** 空状态插画：一只在夜空里轻轻晃铃的闹钟，周围有闪烁的星和月牙。全部用 Canvas 画，跟随主题配色。 */
@Composable
fun AlarmIllustration(modifier: Modifier = Modifier, size: Dp = 220.dp) {
    val transition = rememberInfiniteTransition(label = "illustration")
    val twinkle by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "twinkle",
    )
    val shake by transition.animateFloat(
        initialValue = -7f,
        targetValue = 7f,
        animationSpec = infiniteRepeatable(tween(260, easing = LinearEasing), RepeatMode.Reverse),
        label = "shake",
    )
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val face = MaterialTheme.colorScheme.surfaceContainerHighest
    val ink = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.secondary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier.size(size)) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val c = Offset(w / 2f, w * 0.54f)
            val r = w * 0.27f

            // 背后的柔光
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(primary.copy(alpha = 0.28f), Color.Transparent),
                    center = c,
                    radius = w * 0.5f,
                ),
                radius = w * 0.5f,
                center = c,
            )

            // 月牙：大圆减去偏移的小圆
            val moonC = Offset(w * 0.80f, w * 0.20f)
            val moon = Path.combine(
                PathOperation.Difference,
                Path().apply { addOval(Rect(center = moonC, radius = w * 0.075f)) },
                Path().apply { addOval(Rect(center = moonC + Offset(w * 0.04f, -w * 0.02f), radius = w * 0.065f)) },
            )
            drawPath(moon, color = secondary.copy(alpha = 0.9f))

            // 星星
            val stars = listOf(
                Offset(w * 0.14f, w * 0.26f) to 1f,
                Offset(w * 0.28f, w * 0.10f) to 0.7f,
                Offset(w * 0.88f, w * 0.52f) to 0.8f,
                Offset(w * 0.10f, w * 0.64f) to 0.6f,
                Offset(w * 0.66f, w * 0.08f) to 0.9f,
            )
            stars.forEachIndexed { i, (p, scale) ->
                val a = if (i % 2 == 0) twinkle else 1.25f - twinkle
                drawStar(p, w * 0.028f * scale, secondary.copy(alpha = a.coerceIn(0.2f, 1f)))
            }

            // 两只铃（随闹钟晃动）
            for (side in listOf(-1, 1)) {
                rotate(degrees = shake * side, pivot = c) {
                    val a = Math.toRadians((-90.0 + side * 42.0))
                    val bellC = Offset(c.x + (r * 1.05f * cos(a)).toFloat(), c.y + (r * 1.05f * sin(a)).toFloat())
                    drawCircle(color = primary, radius = r * 0.42f, center = bellC)
                    drawCircle(color = onPrimary.copy(alpha = 0.18f), radius = r * 0.22f, center = bellC + Offset(-r * 0.08f, -r * 0.08f))
                }
            }

            // 闹钟本体
            drawCircle(color = primary, radius = r, center = c)
            drawCircle(color = face, radius = r * 0.84f, center = c)
            // 刻度
            for (i in 0 until 12) {
                val a = Math.toRadians(i * 30.0 - 90.0)
                val long = i % 3 == 0
                val from = Offset(c.x + (r * (if (long) 0.62f else 0.68f) * cos(a)).toFloat(), c.y + (r * (if (long) 0.62f else 0.68f) * sin(a)).toFloat())
                val to = Offset(c.x + (r * 0.76f * cos(a)).toFloat(), c.y + (r * 0.76f * sin(a)).toFloat())
                drawLine(ink.copy(alpha = if (long) 0.9f else 0.4f), from, to, strokeWidth = if (long) w * 0.012f else w * 0.007f, cap = StrokeCap.Round)
            }
            // 时针 / 分针（停在 7:00）
            val hourA = Math.toRadians(-90.0 + 30.0 * 7)
            drawLine(
                ink, c, Offset(c.x + (r * 0.42f * cos(hourA)).toFloat(), c.y + (r * 0.42f * sin(hourA)).toFloat()),
                strokeWidth = w * 0.02f, cap = StrokeCap.Round,
            )
            drawLine(
                primary, c, Offset(c.x, c.y - r * 0.58f),
                strokeWidth = w * 0.014f, cap = StrokeCap.Round,
            )
            drawCircle(color = primary, radius = w * 0.016f, center = c)

            // 两只脚
            for (side in listOf(-1, 1)) {
                val a = Math.toRadians(90.0 + side * 38.0)
                val from = Offset(c.x + (r * 0.92f * cos(a)).toFloat(), c.y + (r * 0.92f * sin(a)).toFloat())
                val to = Offset(c.x + (r * 1.2f * cos(a)).toFloat() + side * w * 0.025f, c.y + (r * 1.2f * sin(a)).toFloat() + w * 0.02f)
                drawLine(muted.copy(alpha = 0.8f), from, to, strokeWidth = w * 0.03f, cap = StrokeCap.Round)
            }
            // 铃锤
            drawLine(ink.copy(alpha = 0.55f), Offset(c.x, c.y - r * 1.12f), Offset(c.x, c.y - r * 1.28f), strokeWidth = w * 0.02f, cap = StrokeCap.Round)
            drawArc(
                color = ink.copy(alpha = 0.55f),
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(c.x - r * 0.2f, c.y - r * 1.4f),
                size = Size(r * 0.4f, r * 0.2f),
                style = Stroke(width = w * 0.016f, cap = StrokeCap.Round),
            )
        }
    }
}

private fun DrawScope.drawStar(center: Offset, radius: Float, color: Color) {
    // 四角星：两条交叉的细长菱形，用线 + 圆点近似
    drawLine(color, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), strokeWidth = radius * 0.45f, cap = StrokeCap.Round)
    drawLine(color, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), strokeWidth = radius * 0.45f, cap = StrokeCap.Round)
    drawCircle(color, radius * 0.4f, center)
}
