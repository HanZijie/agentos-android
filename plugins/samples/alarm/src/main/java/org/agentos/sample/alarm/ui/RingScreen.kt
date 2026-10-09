package org.agentos.sample.alarm.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.AlarmOff
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.agentos.sample.alarm.R
import org.agentos.sample.alarm.data.Alarm
import org.agentos.sample.alarm.ui.theme.LocalAlarmExtras

/** 响铃全屏页：夜间配色、脉动光环、大号时间；贪睡与关闭两个大按钮，拇指够得着。 */
@Composable
fun RingScreen(alarm: Alarm, onSnooze: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val extras = LocalAlarmExtras.current
    val parts = timeParts(context, alarm.hour, alarm.minute)

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.background, extras.heroStart.copy(alpha = 0.55f), extras.heroEnd.copy(alpha = 0.75f)),
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.7f))
            PulsingBell()
            Spacer(Modifier.height(28.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                parts.prefix?.let {
                    Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = parts.main,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                parts.suffix?.let {
                    Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = alarm.label.ifBlank { stringResource(R.string.alarm_default_title) },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.extraLarge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.fillMaxWidth().height(68.dp),
            ) {
                Icon(Icons.Rounded.AlarmOff, contentDescription = null, modifier = Modifier.size(26.dp))
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.ring_dismiss), style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onSnooze,
                shape = MaterialTheme.shapes.extraLarge,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) {
                Icon(Icons.Rounded.Snooze, contentDescription = null)
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.ring_snooze, alarm.snoozeMinutes), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 三圈向外扩散的光环 + 中央的闹钟图标。 */
@Composable
private fun PulsingBell() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "phase",
    )
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            for (i in 0 until 3) {
                val p = (phase + i / 3f) % 1f
                drawCircle(
                    color = primary.copy(alpha = (1f - p) * 0.55f),
                    radius = size.minDimension * (0.28f + 0.22f * p),
                    center = center,
                    style = Stroke(width = 3f + 3f * (1f - p)),
                )
            }
            drawCircle(color = primary.copy(alpha = 0.22f), radius = size.minDimension * 0.26f, center = center)
        }
        Icon(
            Icons.Rounded.Alarm,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(52.dp),
        )
    }
}
