package org.agentos.sample.alarm.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.filter
import org.agentos.sample.alarm.AlarmGraph
import org.agentos.sample.alarm.ui.theme.RingTheme

/** 响铃全屏页（锁屏上可见，点亮屏幕）。闹钟停了（关闭、贪睡、被 MCP 关掉、超时）就自己关闭。 */
class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 响铃页永远是深色背景：状态栏、导航栏图标用浅色
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val ring = AlarmGraph.get(this).ring

        setContent {
            RingTheme {
                val alarm by ring.state.collectAsState()
                LaunchedEffect(Unit) {
                    ring.state.filter { it == null }.collect { finish() }
                }
                alarm?.let { current ->
                    RingScreen(
                        alarm = current,
                        onSnooze = { ring.snooze() },
                        onDismiss = { ring.dismiss() },
                    )
                }
            }
        }
    }
}
