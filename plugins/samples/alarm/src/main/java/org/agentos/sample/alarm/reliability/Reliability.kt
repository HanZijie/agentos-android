package org.agentos.sample.alarm.reliability

/** “保证准时响铃”检查页的四项检查。顺序就是页面上的顺序。 */
enum class CheckId { EXACT_ALARM, NOTIFICATIONS, FULL_SCREEN, BATTERY }

/** 有后台限制传闻的国内厂商系统（只用来显示文字指引，v1 不做按品牌的私有深链）。 */
enum class Vendor { XIAOMI, HUAWEI, HONOR, OPPO, VIVO, ONEPLUS }

/** 从系统读到的状态；纯数据，JVM 测试直接构造。[manufacturer] 是 `Build.MANUFACTURER`。 */
data class ReliabilityInputs(
    val exactAlarms: Boolean,
    val notifications: Boolean,
    val fullScreen: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val manufacturer: String?,
)

/**
 * 一项检查的结果。[important] = false 的项没通过时只是“建议”：
 * 电池优化在原生 Android 上不影响 `setAlarmClock`（Doze 里照响），只有在会冻结后台的国内厂商系统上才算重要。
 */
data class Check(val id: CheckId, val ok: Boolean, val important: Boolean)

data class ReliabilityReport(val checks: List<Check>, val vendor: Vendor?) {
    /** 重要且没通过的项数（页面顶部“有 N 项需要处理”）。 */
    val problemCount: Int get() = checks.count { it.important && !it.ok }

    /** 重要的项全部通过。 */
    val allReady: Boolean get() = problemCount == 0

    fun check(id: CheckId): Check = checks.first { it.id == id }
}

object Reliability {
    /** 按 `Build.MANUFACTURER` 判断厂商；不认识的（含 Google、三星）返回 null。子品牌归到母公司的系统：红米 / POCO → 小米，realme → OPPO，iQOO → vivo。 */
    fun vendorOf(manufacturer: String?): Vendor? = when (manufacturer?.trim()?.lowercase()?.replace(" ", "")) {
        "xiaomi", "redmi", "poco" -> Vendor.XIAOMI
        "huawei" -> Vendor.HUAWEI
        "honor" -> Vendor.HONOR
        "oppo", "realme" -> Vendor.OPPO
        "vivo", "iqoo" -> Vendor.VIVO
        "oneplus" -> Vendor.ONEPLUS
        else -> null
    }

    fun evaluate(inputs: ReliabilityInputs): ReliabilityReport {
        val vendor = vendorOf(inputs.manufacturer)
        return ReliabilityReport(
            checks = listOf(
                Check(CheckId.EXACT_ALARM, inputs.exactAlarms, important = true),
                Check(CheckId.NOTIFICATIONS, inputs.notifications, important = true),
                Check(CheckId.FULL_SCREEN, inputs.fullScreen, important = true),
                Check(CheckId.BATTERY, inputs.ignoringBatteryOptimizations, important = vendor != null),
            ),
            vendor = vendor,
        )
    }
}
