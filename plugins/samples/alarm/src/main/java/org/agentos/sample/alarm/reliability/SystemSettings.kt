package org.agentos.sample.alarm.reliability

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * 检查页和列表页提示条共用的“读系统状态”和“跳系统设置”。提示条和检查页只是同一份状态的两个入口，逻辑只写这一份。
 */
object SystemSettings {
    private const val TAG = "AlarmSettings"

    fun notificationsGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun fullScreenAllowed(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun read(context: Context): ReliabilityInputs = ReliabilityInputs(
        exactAlarms = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
        notifications = notificationsGranted(context),
        fullScreen = fullScreenAllowed(context),
        ignoringBatteryOptimizations = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
        manufacturer = DeviceInfo.manufacturer(),
    )

    // ---- 各项的设置页 ----

    fun notifications(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun fullScreen(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri())

    fun exactAlarm(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())

    /**
     * 电池优化：跳系统的“电池优化”**列表页**（ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS，不需要任何权限），由用户自己在列表里选本 App。
     * 不用 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS：它要声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限，
     * Google Play 的政策只允许核心功能确实受 Doze 影响的 App 使用；闹钟走 setAlarmClock，Doze 里本来就照响，没有正当理由申请。
     */
    fun batteryOptimizationList(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** 系统“应用语言”设置页（API 33+，minSdk 35 直接可用）。 */
    fun appLanguage(context: Context): Intent =
        Intent(Settings.ACTION_APP_LOCALE_SETTINGS, "package:${context.packageName}".toUri())

    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

    /** 打开设置页；这个机型没有对应页面（部分国产系统删减过）时退回本 App 的应用信息页，再不行就忽略，不崩。 */
    fun open(context: Context, primary: Intent) {
        for (intent in listOf(primary, appDetails(context))) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                Log.w(TAG, "cannot open ${intent.action}: ${e.javaClass.simpleName}")
            }
        }
    }
}

/** 设备信息。[manufacturerOverride] 只给 debug 构建的自测入口用（截图、验证国产机型的指引文字），正常运行永远是 null。 */
object DeviceInfo {
    @Volatile
    var manufacturerOverride: String? = null

    fun manufacturer(): String? = manufacturerOverride ?: Build.MANUFACTURER
}
