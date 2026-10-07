package org.agentos.sample.alarm.ring

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.agentos.sample.alarm.data.Alarm
import org.agentos.sample.alarm.tools.RingControl

/**
 * 进程内的“正在响的闹钟”：响铃服务写 [state]，响铃页和 MCP 的 alarm_dismiss / alarm_snooze 读它、发指令。
 * 指令一律投递到主线程，由服务执行（MediaPlayer、前台服务状态都在主线程上改）。
 */
object RingController : RingControl {
    internal val state = MutableStateFlow<Alarm?>(null)
    override val ringing: StateFlow<Alarm?> = state

    @Volatile
    internal var service: AlarmRingService? = null

    private val main = Handler(Looper.getMainLooper())

    override fun dismiss(): Alarm? {
        val alarm = state.value ?: return null
        main.post { service?.dismiss() }
        return alarm
    }

    override fun snooze(): Alarm? {
        val alarm = state.value ?: return null
        main.post { service?.snooze() }
        return alarm
    }
}
