package org.agentos.sample.notes.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

enum class EmptyKind { NOTES, ARCHIVE, TRASH, SEARCH, TAG }

/** 空状态：插画（Canvas 画的几何图形，跟随主题色）+ 标题 + 说明，可带一个操作。 */
@Composable
fun EmptyState(
    kind: EmptyKind,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyIllustration(kind, Modifier.size(176.dp))
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun EmptyIllustration(kind: EmptyKind, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val palette = IllustrationColors(
        ink = scheme.primary,
        soft = scheme.primaryContainer,
        warm = scheme.secondaryContainer,
        warmInk = scheme.secondary,
        paper = scheme.surface,
        line = scheme.outlineVariant,
        shade = scheme.surfaceContainerHigh,
        rose = scheme.tertiaryContainer,
    )
    Canvas(modifier) {
        when (kind) {
            EmptyKind.NOTES -> drawNotes(palette)
            EmptyKind.ARCHIVE -> drawArchive(palette)
            EmptyKind.TRASH -> drawTrash(palette)
            EmptyKind.SEARCH -> drawSearch(palette)
            EmptyKind.TAG -> drawTag(palette)
        }
    }
}

private class IllustrationColors(
    val ink: Color,
    val soft: Color,
    val warm: Color,
    val warmInk: Color,
    val paper: Color,
    val line: Color,
    val shade: Color,
    val rose: Color,
)

private fun DrawScope.sparkle(center: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x, center.y, center.x + radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y + radius)
        quadraticTo(center.x, center.y, center.x - radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y - radius)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawNotes(c: IllustrationColors) {
    val s = size.minDimension
    val cx = size.width / 2
    val cy = size.height / 2
    // 地面的影子
    drawOval(c.shade, Offset(cx - s * 0.36f, cy + s * 0.36f), Size(s * 0.72f, s * 0.08f))
    // 后两张纸
    rotate(-9f, Offset(cx, cy)) {
        drawRoundRect(c.warm, Offset(cx - s * 0.27f, cy - s * 0.36f), Size(s * 0.54f, s * 0.70f), CornerRadius(s * 0.06f))
    }
    rotate(7f, Offset(cx, cy)) {
        drawRoundRect(c.soft, Offset(cx - s * 0.27f, cy - s * 0.36f), Size(s * 0.54f, s * 0.70f), CornerRadius(s * 0.06f))
    }
    // 前面的纸
    val left = cx - s * 0.27f
    val top = cy - s * 0.34f
    drawRoundRect(c.paper, Offset(left, top), Size(s * 0.54f, s * 0.70f), CornerRadius(s * 0.06f))
    drawRoundRect(c.line, Offset(left, top), Size(s * 0.54f, s * 0.70f), CornerRadius(s * 0.06f), style = Stroke(width = s * 0.012f))
    val stroke = s * 0.028f
    val lines = listOf(0.50f, 0.36f, 0.43f)
    lines.forEachIndexed { i, w ->
        val y = top + s * (0.17f + i * 0.095f)
        drawLine(c.ink.copy(alpha = 0.75f), Offset(left + s * 0.07f, y), Offset(left + s * 0.07f + s * w * 0.8f, y), strokeWidth = stroke, cap = StrokeCap.Round)
    }
    // 对勾徽章
    val badge = Offset(left + s * 0.43f, top + s * 0.50f)
    drawCircle(c.warmInk, s * 0.085f, badge)
    val check = Path().apply {
        moveTo(badge.x - s * 0.035f, badge.y + s * 0.002f)
        lineTo(badge.x - s * 0.008f, badge.y + s * 0.03f)
        lineTo(badge.x + s * 0.04f, badge.y - s * 0.03f)
    }
    drawPath(check, c.paper, style = Stroke(width = s * 0.024f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    // 铅笔
    rotate(38f, Offset(cx + s * 0.30f, cy + s * 0.18f)) {
        val px = cx + s * 0.27f
        val py = cy - s * 0.02f
        drawRoundRect(c.warmInk, Offset(px, py), Size(s * 0.055f, s * 0.30f), CornerRadius(s * 0.012f))
        drawRect(c.warm, Offset(px, py + s * 0.04f), Size(s * 0.055f, s * 0.022f))
        val tip = Path().apply {
            moveTo(px, py + s * 0.30f)
            lineTo(px + s * 0.055f, py + s * 0.30f)
            lineTo(px + s * 0.0275f, py + s * 0.355f)
            close()
        }
        drawPath(tip, c.ink)
    }
    sparkle(Offset(cx - s * 0.36f, cy - s * 0.30f), s * 0.05f, c.warmInk)
    sparkle(Offset(cx + s * 0.36f, cy - s * 0.22f), s * 0.035f, c.ink)
}

private fun DrawScope.drawArchive(c: IllustrationColors) {
    val s = size.minDimension
    val cx = size.width / 2
    val cy = size.height / 2
    drawOval(c.shade, Offset(cx - s * 0.36f, cy + s * 0.34f), Size(s * 0.72f, s * 0.08f))
    // 露出来的纸
    rotate(-5f, Offset(cx, cy - s * 0.05f)) {
        drawRoundRect(c.paper, Offset(cx - s * 0.20f, cy - s * 0.34f), Size(s * 0.40f, s * 0.36f), CornerRadius(s * 0.04f))
        drawRoundRect(c.line, Offset(cx - s * 0.20f, cy - s * 0.34f), Size(s * 0.40f, s * 0.36f), CornerRadius(s * 0.04f), style = Stroke(width = s * 0.01f))
        drawLine(c.ink.copy(alpha = 0.6f), Offset(cx - s * 0.13f, cy - s * 0.25f), Offset(cx + s * 0.08f, cy - s * 0.25f), strokeWidth = s * 0.022f, cap = StrokeCap.Round)
        drawLine(c.ink.copy(alpha = 0.4f), Offset(cx - s * 0.13f, cy - s * 0.18f), Offset(cx + s * 0.02f, cy - s * 0.18f), strokeWidth = s * 0.022f, cap = StrokeCap.Round)
    }
    // 盒身
    drawRoundRect(c.soft, Offset(cx - s * 0.30f, cy - s * 0.04f), Size(s * 0.60f, s * 0.38f), CornerRadius(s * 0.05f))
    // 盒盖
    drawRoundRect(c.ink, Offset(cx - s * 0.34f, cy - s * 0.13f), Size(s * 0.68f, s * 0.13f), CornerRadius(s * 0.04f))
    // 拉手
    drawRoundRect(c.paper, Offset(cx - s * 0.08f, cy + s * 0.07f), Size(s * 0.16f, s * 0.045f), CornerRadius(s * 0.022f))
    sparkle(Offset(cx + s * 0.34f, cy - s * 0.28f), s * 0.05f, c.warmInk)
    sparkle(Offset(cx - s * 0.36f, cy - s * 0.18f), s * 0.03f, c.ink)
}

private fun DrawScope.drawTrash(c: IllustrationColors) {
    val s = size.minDimension
    val cx = size.width / 2
    val cy = size.height / 2
    drawOval(c.shade, Offset(cx - s * 0.30f, cy + s * 0.35f), Size(s * 0.60f, s * 0.07f))
    val body = Path().apply {
        moveTo(cx - s * 0.24f, cy - s * 0.12f)
        lineTo(cx + s * 0.24f, cy - s * 0.12f)
        lineTo(cx + s * 0.20f, cy + s * 0.33f)
        quadraticTo(cx + s * 0.195f, cy + s * 0.37f, cx + s * 0.15f, cy + s * 0.37f)
        lineTo(cx - s * 0.15f, cy + s * 0.37f)
        quadraticTo(cx - s * 0.195f, cy + s * 0.37f, cx - s * 0.20f, cy + s * 0.33f)
        close()
    }
    drawPath(body, c.soft)
    drawPath(body, c.ink, style = Stroke(width = s * 0.02f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    listOf(-0.09f, 0f, 0.09f).forEach { dx ->
        drawLine(c.ink.copy(alpha = 0.55f), Offset(cx + s * dx, cy - s * 0.04f), Offset(cx + s * dx * 0.9f, cy + s * 0.29f), strokeWidth = s * 0.022f, cap = StrokeCap.Round)
    }
    // 盖子
    rotate(-12f, Offset(cx - s * 0.30f, cy - s * 0.17f)) {
        drawRoundRect(c.ink, Offset(cx - s * 0.32f, cy - s * 0.21f), Size(s * 0.64f, s * 0.07f), CornerRadius(s * 0.03f))
        drawRoundRect(c.ink, Offset(cx - s * 0.06f, cy - s * 0.27f), Size(s * 0.12f, s * 0.07f), CornerRadius(s * 0.03f))
    }
    // 一张被扔进去的纸
    rotate(14f, Offset(cx + s * 0.02f, cy - s * 0.20f)) {
        drawRoundRect(c.paper, Offset(cx - s * 0.10f, cy - s * 0.36f), Size(s * 0.20f, s * 0.24f), CornerRadius(s * 0.025f))
        drawRoundRect(c.line, Offset(cx - s * 0.10f, cy - s * 0.36f), Size(s * 0.20f, s * 0.24f), CornerRadius(s * 0.025f), style = Stroke(width = s * 0.009f))
        drawLine(c.ink.copy(alpha = 0.5f), Offset(cx - s * 0.06f, cy - s * 0.29f), Offset(cx + s * 0.05f, cy - s * 0.29f), strokeWidth = s * 0.018f, cap = StrokeCap.Round)
    }
    sparkle(Offset(cx + s * 0.34f, cy - s * 0.25f), s * 0.045f, c.warmInk)
}

private fun DrawScope.drawSearch(c: IllustrationColors) {
    val s = size.minDimension
    val cx = size.width / 2
    val cy = size.height / 2
    drawOval(c.shade, Offset(cx - s * 0.30f, cy + s * 0.35f), Size(s * 0.60f, s * 0.07f))
    // 背后的纸
    rotate(-8f, Offset(cx, cy)) {
        drawRoundRect(c.warm, Offset(cx - s * 0.24f, cy - s * 0.32f), Size(s * 0.48f, s * 0.62f), CornerRadius(s * 0.05f))
        listOf(0.46f, 0.34f, 0.40f).forEachIndexed { i, w ->
            val y = cy - s * 0.22f + i * s * 0.085f
            drawLine(c.warmInk.copy(alpha = 0.5f), Offset(cx - s * 0.17f, y), Offset(cx - s * 0.17f + s * w * 0.6f, y), strokeWidth = s * 0.022f, cap = StrokeCap.Round)
        }
    }
    // 放大镜
    val center = Offset(cx - s * 0.04f, cy - s * 0.04f)
    drawCircle(c.paper.copy(alpha = 0.55f), s * 0.20f, center)
    drawCircle(c.ink, s * 0.20f, center, style = Stroke(width = s * 0.05f))
    drawLine(c.ink, Offset(center.x + s * 0.14f, center.y + s * 0.14f), Offset(center.x + s * 0.34f, center.y + s * 0.34f), strokeWidth = s * 0.07f, cap = StrokeCap.Round)
    val glint = Path().apply {
        moveTo(center.x - s * 0.12f, center.y - s * 0.02f)
        quadraticTo(center.x - s * 0.11f, center.y - s * 0.11f, center.x - s * 0.02f, center.y - s * 0.12f)
    }
    drawPath(glint, c.paper, style = Stroke(width = s * 0.02f, cap = StrokeCap.Round))
    sparkle(Offset(cx + s * 0.34f, cy - s * 0.26f), s * 0.045f, c.warmInk)
    sparkle(Offset(cx - s * 0.36f, cy + s * 0.2f), s * 0.03f, c.ink)
}

private fun DrawScope.drawTag(c: IllustrationColors) {
    val s = size.minDimension
    val cx = size.width / 2
    val cy = size.height / 2
    drawOval(c.shade, Offset(cx - s * 0.30f, cy + s * 0.33f), Size(s * 0.60f, s * 0.07f))
    rotate(-20f, Offset(cx, cy)) {
        val tag = Path().apply {
            moveTo(cx - s * 0.30f, cy - s * 0.14f)
            lineTo(cx + s * 0.12f, cy - s * 0.14f)
            lineTo(cx + s * 0.32f, cy)
            lineTo(cx + s * 0.12f, cy + s * 0.14f)
            lineTo(cx - s * 0.30f, cy + s * 0.14f)
            close()
        }
        drawPath(tag, c.soft)
        drawPath(tag, c.ink, style = Stroke(width = s * 0.02f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(c.paper, s * 0.035f, Offset(cx + s * 0.16f, cy))
        drawCircle(c.ink, s * 0.035f, Offset(cx + s * 0.16f, cy), style = Stroke(width = s * 0.012f))
        drawLine(c.ink.copy(alpha = 0.6f), Offset(cx - s * 0.22f, cy - s * 0.04f), Offset(cx - s * 0.02f, cy - s * 0.04f), strokeWidth = s * 0.022f, cap = StrokeCap.Round)
        drawLine(c.ink.copy(alpha = 0.4f), Offset(cx - s * 0.22f, cy + s * 0.05f), Offset(cx - s * 0.08f, cy + s * 0.05f), strokeWidth = s * 0.022f, cap = StrokeCap.Round)
    }
    sparkle(Offset(cx + s * 0.30f, cy - s * 0.26f), s * 0.05f, c.warmInk)
    sparkle(Offset(cx - s * 0.34f, cy + s * 0.22f), s * 0.03f, c.ink)
}
