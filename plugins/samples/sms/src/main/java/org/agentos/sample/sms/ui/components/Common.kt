package org.agentos.sample.sms.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.agentos.sample.sms.ui.theme.LocalSmsExtras

/** 带标题的圆角卡片。 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) { content() }
    }
}

/** 小标签：状态 / 权限。文字 + 色块，不只靠颜色。 */
@Composable
fun StatusPill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = container, contentColor = content, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 空状态：插画 + 标题 + 说明 + 可选按钮。 */
@Composable
fun EmptyState(
    illustration: @Composable () -> Unit,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        illustration()
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = onAction) { Text(actionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** 空会话列表：两个对话气泡。 */
@Composable
fun ChatsIllustration(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer
    val variant = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.size(width = 168.dp, height = 120.dp)) {
        val u = size.width / 168f
        // 对方的气泡（左上）
        drawRoundRect(variant, Offset(10 * u, 8 * u), Size(100 * u, 48 * u), CornerRadius(16 * u))
        val tailIn = Path().apply {
            moveTo(26 * u, 54 * u); lineTo(22 * u, 70 * u); lineTo(44 * u, 56 * u); close()
        }
        drawPath(tailIn, variant)
        for (i in 0..2) drawCircle(primary.copy(alpha = 0.55f), 5 * u, Offset((38 + i * 18) * u, 32 * u))
        // 自己的气泡（右下）
        drawRoundRect(container, Offset(58 * u, 62 * u), Size(100 * u, 48 * u), CornerRadius(16 * u))
        val tailOut = Path().apply {
            moveTo(142 * u, 108 * u); lineTo(148 * u, 118 * u); lineTo(124 * u, 110 * u); close()
        }
        drawPath(tailOut, container)
        drawRoundRect(primary, Offset(74 * u, 80 * u), Size(60 * u, 6 * u), CornerRadius(3 * u))
        drawRoundRect(primary.copy(alpha = 0.5f), Offset(74 * u, 92 * u), Size(40 * u, 6 * u), CornerRadius(3 * u))
    }
}

/** 没有权限：一把锁。 */
@Composable
fun LockIllustration(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer
    val warn = LocalSmsExtras.current.warn
    Canvas(modifier.size(120.dp)) {
        val u = size.width / 120f
        drawCircle(container, 56 * u, center)
        val stroke = Stroke(width = 9 * u, cap = StrokeCap.Round)
        val shackle = Path().apply {
            moveTo(44 * u, 54 * u)
            lineTo(44 * u, 44 * u)
            cubicTo(44 * u, 24 * u, 76 * u, 24 * u, 76 * u, 44 * u)
            lineTo(76 * u, 54 * u)
        }
        drawPath(shackle, primary, style = stroke)
        drawRoundRect(primary, Offset(34 * u, 52 * u), Size(52 * u, 40 * u), CornerRadius(10 * u))
        drawCircle(Color.White, 5.5f * u, Offset(60 * u, 68 * u))
        drawRoundRect(Color.White, Offset(58 * u, 70 * u), Size(4 * u, 12 * u), CornerRadius(2 * u))
        drawCircle(warn, 7 * u, Offset(92 * u, 30 * u))
    }
}

/** Agent 发送记录为空：一架纸飞机。 */
@Composable
fun PlaneIllustration(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer
    val variant = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.size(width = 168.dp, height = 112.dp)) {
        val u = size.width / 168f
        drawCircle(container, 50 * u, Offset(84 * u, 58 * u))
        // 虚线航迹
        for (i in 0..4) {
            val x = (14 + i * 11) * u
            drawLine(variant, Offset(x, (96 - i * 6) * u), Offset(x + 5 * u, (93 - i * 6) * u), strokeWidth = 3 * u, cap = StrokeCap.Round)
        }
        // 纸飞机
        val body = Path().apply {
            moveTo(70 * u, 66 * u); lineTo(128 * u, 34 * u); lineTo(104 * u, 86 * u); lineTo(92 * u, 70 * u); close()
        }
        drawPath(body, primary)
        val fold = Path().apply {
            moveTo(92 * u, 70 * u); lineTo(128 * u, 34 * u); lineTo(96 * u, 78 * u); close()
        }
        drawPath(fold, Color.Black.copy(alpha = 0.18f))
    }
}

/** 圆形字母头像（地址首字符；数字号码用电话图标由调用方处理）。 */
@Composable
fun Avatar(label: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 44.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1)
    }
}

/** 一行带圆角底的横向分组（给“权限”两格并排）。 */
@Composable
fun RowCell(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}
