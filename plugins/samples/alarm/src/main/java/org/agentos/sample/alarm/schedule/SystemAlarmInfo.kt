package org.agentos.sample.alarm.schedule

import android.app.AlarmManager
import android.content.Context

/** 系统眼里的“下一个闹钟”（状态栏闹钟图标对应的那个），任何 App 设的都算。[creatorPackage] 是登记它的 App 的包名，取不到为 null。 */
data class SystemNextAlarm(val triggerAtMillis: Long, val creatorPackage: String?) {
    fun isOwnedBy(packageName: String): Boolean = creatorPackage != null && creatorPackage == packageName
}

/**
 * 工具层需要的“系统范围的下一个闹钟”。Android 实现是 [AndroidSystemAlarmInfo]（`AlarmManager.getNextAlarmClock()`）；
 * 工具测试用假实现，JVM 里不依赖 AlarmManager。
 */
interface SystemAlarmInfo {
    /** 本 App 的包名，用来判断闹钟是不是自己设的。 */
    val ownPackage: String

    /** 系统范围的下一个闹钟；系统里一个闹钟都没有时为 null。 */
    fun next(): SystemNextAlarm?
}

/**
 * `AlarmManager.getNextAlarmClock()`（API 21，不需要权限）：包含 Google 时钟等其他 App 用 `setAlarmClock` 设的闹钟。
 * 创建者包名取 `showIntent` 的 `PendingIntent.getCreatorPackage()`（API 17，minSdk 35 下可用）：
 * 谁设的闹钟，谁提供的 showIntent；没有 showIntent 时拿不到，按“不是本 App 的”处理。
 */
class AndroidSystemAlarmInfo(context: Context) : SystemAlarmInfo {
    private val alarmManager = context.applicationContext.getSystemService(AlarmManager::class.java)
    override val ownPackage: String = context.packageName

    override fun next(): SystemNextAlarm? = alarmManager.nextAlarmClock?.let {
        SystemNextAlarm(it.triggerTime, it.showIntent?.creatorPackage)
    }
}
