package org.agentos.sample.todo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.agentos.sample.todo.ui.theme.todo

/**
 * 空状态插画（Canvas 画的，跟随主题色）：一块夹板，上面三行待办。
 * [checked] 是打了勾的行数：空状态 0 行（等着添加），“全部完成”3 行；周围的小星星和圆点是装饰。
 */
@Composable
fun ClipboardIllustration(checked: Int, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val scheme = MaterialTheme.colorScheme
    val palette = MaterialTheme.todo
    val blob = scheme.primaryContainer.copy(alpha = 0.7f)
    val board = scheme.surface
    val edge = scheme.primary
    val line = scheme.outlineVariant
    val done = palette.done
    val ring = scheme.outline
    val sparkA = palette.medium
    val sparkB = palette.high
    val sparkC = palette.low
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 200f

        // 背景的圆形色块
        drawCircle(blob, radius = 88f * u, center = Offset(100f * u, 104f * u))
        drawCircle(scheme.primary.copy(alpha = 0.10f), radius = 70f * u, center = Offset(100f * u, 104f * u))

        // 夹板
        val left = 55f * u
        val top = 36f * u
        val w = 90f * u
        val h = 128f * u
        drawRoundRect(Color.Black.copy(alpha = 0.08f), Offset(left + 3f * u, top + 6f * u), Size(w, h), CornerRadius(14f * u))
        drawRoundRect(board, Offset(left, top), Size(w, h), CornerRadius(14f * u))
        drawRoundRect(edge, Offset(left, top), Size(w, h), CornerRadius(14f * u), style = Stroke(width = 3f * u))
        // 夹子
        drawRoundRect(edge, Offset(left + 28f * u, top - 9f * u), Size(34f * u, 20f * u), CornerRadius(7f * u))
        drawRoundRect(board, Offset(left + 36f * u, top - 3f * u), Size(18f * u, 6f * u), CornerRadius(3f * u))

        // 三行
        for (i in 0 until 3) {
            val cy = top + (48f + i * 30f) * u
            val cx = left + 22f * u
            val on = i < checked
            if (on) {
                drawCircle(done, radius = 9f * u, center = Offset(cx, cy))
                val p = Path().apply {
                    moveTo(cx - 4.2f * u, cy + 0.4f * u)
                    lineTo(cx - 1.2f * u, cy + 3.4f * u)
                    lineTo(cx + 4.6f * u, cy - 3.2f * u)
                }
                drawPath(p, Color.White, style = Stroke(width = 2.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else {
                drawCircle(ring, radius = 8.2f * u, center = Offset(cx, cy), style = Stroke(width = 2.2f * u))
            }
            val x0 = cx + 17f * u
            val x1 = left + w - 14f * u - (if (i == 2) 14f * u else 0f)
            drawLine(if (on) line else scheme.outline.copy(alpha = 0.55f), Offset(x0, cy), Offset(x1, cy), strokeWidth = 5f * u, cap = StrokeCap.Round)
        }

        // 小装饰
        star(Offset(38f * u, 56f * u), 9f * u, sparkA)
        star(Offset(168f * u, 78f * u), 7f * u, sparkC)
        star(Offset(158f * u, 160f * u), 10f * u, sparkB)
        drawCircle(sparkB.copy(alpha = 0.85f), radius = 3.5f * u, center = Offset(44f * u, 150f * u))
        drawCircle(sparkA.copy(alpha = 0.85f), radius = 3f * u, center = Offset(172f * u, 120f * u))
        drawCircle(sparkC.copy(alpha = 0.85f), radius = 2.6f * u, center = Offset(30f * u, 102f * u))
    }
}

/** 四角星。 */
private fun DrawScope.star(center: Offset, r: Float, color: Color) {
    val k = r * 0.28f
    val p = Path().apply {
        moveTo(center.x, center.y - r)
        lineTo(center.x + k, center.y - k)
        lineTo(center.x + r, center.y)
        lineTo(center.x + k, center.y + k)
        lineTo(center.x, center.y + r)
        lineTo(center.x - k, center.y + k)
        lineTo(center.x - r, center.y)
        lineTo(center.x - k, center.y - k)
        close()
    }
    drawPath(p, color)
}
