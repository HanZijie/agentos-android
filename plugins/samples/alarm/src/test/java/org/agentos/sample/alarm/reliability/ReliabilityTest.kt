package org.agentos.sample.alarm.reliability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** “保证准时响铃”检查页的状态判定（纯函数）：四项检查、哪些算重要、按 Build.MANUFACTURER 认厂商。 */
class ReliabilityTest {
    private fun inputs(
        exact: Boolean = true,
        notifications: Boolean = true,
        fullScreen: Boolean = true,
        battery: Boolean = true,
        manufacturer: String? = "Google",
    ) = ReliabilityInputs(exact, notifications, fullScreen, battery, manufacturer)

    @Test
    fun everythingGrantedIsReady() {
        val report = Reliability.evaluate(inputs())
        assertTrue(report.allReady)
        assertEquals(0, report.problemCount)
        assertEquals(listOf(CheckId.EXACT_ALARM, CheckId.NOTIFICATIONS, CheckId.FULL_SCREEN, CheckId.BATTERY), report.checks.map { it.id })
        assertTrue(report.checks.all { it.ok })
    }

    @Test
    fun eachMissingPermissionIsReportedOnItsOwn() {
        assertEquals(listOf(CheckId.EXACT_ALARM), Reliability.evaluate(inputs(exact = false)).checks.filter { !it.ok }.map { it.id })
        assertEquals(listOf(CheckId.NOTIFICATIONS), Reliability.evaluate(inputs(notifications = false)).checks.filter { !it.ok }.map { it.id })
        assertEquals(listOf(CheckId.FULL_SCREEN), Reliability.evaluate(inputs(fullScreen = false)).checks.filter { !it.ok }.map { it.id })
        val all = Reliability.evaluate(inputs(exact = false, notifications = false, fullScreen = false))
        assertEquals(3, all.problemCount)
        assertFalse(all.allReady)
    }

    @Test
    fun batteryOptimizationIsOnlyAdviceOnStockAndroid() {
        // 原生 / 三星 / Pixel：setAlarmClock 在 Doze 里照响，电池优化没关不算“需要处理”
        val stock = Reliability.evaluate(inputs(battery = false, manufacturer = "Google"))
        assertFalse(stock.check(CheckId.BATTERY).ok)
        assertFalse(stock.check(CheckId.BATTERY).important)
        assertEquals(0, stock.problemCount)
        assertTrue(stock.allReady)
        assertNull(stock.vendor)
    }

    @Test
    fun batteryOptimizationCountsOnVendorSystemsKnownForKillingBackgroundApps() {
        val xiaomi = Reliability.evaluate(inputs(battery = false, manufacturer = "Xiaomi"))
        assertTrue(xiaomi.check(CheckId.BATTERY).important)
        assertEquals(1, xiaomi.problemCount)
        assertFalse(xiaomi.allReady)
        assertEquals(Vendor.XIAOMI, xiaomi.vendor)
        // 已经放开：厂商机型也算就绪，但仍带出厂商指引（自启动 / 后台活动 / 加锁没法检测）
        val ready = Reliability.evaluate(inputs(battery = true, manufacturer = "HUAWEI"))
        assertTrue(ready.allReady)
        assertEquals(Vendor.HUAWEI, ready.vendor)
    }

    @Test
    fun manufacturerNamesMapToVendors() {
        val expected = mapOf(
            "Xiaomi" to Vendor.XIAOMI, "xiaomi" to Vendor.XIAOMI, "Redmi" to Vendor.XIAOMI, "POCO" to Vendor.XIAOMI,
            "HUAWEI" to Vendor.HUAWEI,
            "HONOR" to Vendor.HONOR, "Honor" to Vendor.HONOR,
            "OPPO" to Vendor.OPPO, "realme" to Vendor.OPPO,
            "vivo" to Vendor.VIVO, "iQOO" to Vendor.VIVO,
            "OnePlus" to Vendor.ONEPLUS, " One Plus " to Vendor.ONEPLUS,
        )
        for ((name, vendor) in expected) assertEquals(name, vendor, Reliability.vendorOf(name))
    }

    @Test
    fun otherManufacturersHaveNoVendorGuidance() {
        for (name in listOf("Google", "samsung", "motorola", "Sony", "", "  ", null, "Xiaomi Inc.")) {
            assertNull(name, Reliability.vendorOf(name))
        }
    }
}
