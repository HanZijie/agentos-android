package org.agentos.sample.alarm.tools

import kotlinx.coroutines.flow.StateFlow
import org.agentos.sample.alarm.data.Alarm

/**
 * 工具层需要的“正在响的闹钟”控制。Android 实现是 [org.agentos.sample.alarm.ring.RingController]；
 * 工具测试用假实现。
 */
interface RingControl {
    /** 正在响的闹钟；没有则为 null。界面据此显示响铃页。 */
    val ringing: StateFlow<Alarm?>

    /** 关闭正在响的闹钟；返回被关闭的闹钟，没有在响返回 null。 */
    fun dismiss(): Alarm?

    /** 让正在响的闹钟贪睡；返回该闹钟，没有在响返回 null。[minutes] 非空时只对这一次贪睡生效，空则用闹钟自己的贪睡时长。 */
    fun snooze(minutes: Int? = null): Alarm?
}
