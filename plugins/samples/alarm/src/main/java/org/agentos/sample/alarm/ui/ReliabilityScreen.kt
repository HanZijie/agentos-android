package org.agentos.sample.alarm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.agentos.sample.alarm.R
import org.agentos.sample.alarm.reliability.Check
import org.agentos.sample.alarm.reliability.CheckId
import org.agentos.sample.alarm.reliability.Reliability
import org.agentos.sample.alarm.reliability.SystemSettings
import org.agentos.sample.alarm.reliability.Vendor

/**
 * “保证准时响铃”检查页：精确闹钟、通知、全屏通知、电池优化四项，每项有状态和“去开启”（跳系统设置页）；
 * 国内厂商机型另有“自启动 / 后台活动 / 多任务加锁”的文字指引。v1 只做文字，不做按品牌的私有深链。
 * 回到前台（从系统设置回来）时自动重新检查。英文更长：标题允许两行，正文自由换行，按钮放在文字下面而不是挤在旁边。
 */
@Composable
fun ReliabilityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resumeTick = rememberResumeTick()
    val report = remember(resumeTick) { Reliability.evaluate(SystemSettings.read(context)) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.reliability_back))
            }
            Text(
                text = stringResource(R.string.reliability_title),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp, end = 12.dp),
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryCard(report.problemCount)
            Text(
                text = stringResource(R.string.reliability_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            report.checks.forEach { check ->
                val text = checkText(check.id)
                CheckCard(
                    check = check,
                    title = stringResource(text.title),
                    body = stringResource(text.body),
                    onOpen = { SystemSettings.open(context, text.settings(context)) },
                )
            }
            report.vendor?.let { VendorCard(it) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SummaryCard(problems: Int) {
    val ready = problems == 0
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (ready) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, contentDescription = null)
            Spacer(Modifier.width(14.dp))
            Text(
                text = if (ready) stringResource(R.string.reliability_all_ready) else pluralStringResource(R.plurals.reliability_problems, problems, problems),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun CheckCard(check: Check, title: String, body: String, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Icon(
                imageVector = if (check.ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                contentDescription = null,
                tint = when {
                    check.ok -> colors.primary
                    check.important -> colors.error
                    else -> colors.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        when {
                            check.ok -> R.string.check_status_on
                            check.important -> R.string.check_status_off
                            else -> R.string.check_status_off_optional
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (check.ok) colors.primary else if (check.important) colors.error else colors.onSurfaceVariant,
                )
                if (!check.ok) {
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onOpen) { Text(stringResource(R.string.perm_grant)) }
                }
            }
        }
    }
}

@Composable
private fun VendorCard(vendor: Vendor) {
    val strings = vendorStrings(vendor)
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.vendor_section_title, stringResource(strings.name)), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.vendor_section_note), style = MaterialTheme.typography.bodySmall)
            VendorStep(R.string.vendor_label_autostart, strings.autostart)
            VendorStep(R.string.vendor_label_background, strings.background)
            VendorStep(R.string.vendor_label_lock, strings.lock)
        }
    }
}

@Composable
private fun VendorStep(label: Int, body: Int) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(stringResource(body), style = MaterialTheme.typography.bodySmall)
    }
}

private class CheckText(val title: Int, val body: Int, val settings: (android.content.Context) -> android.content.Intent)

private fun checkText(id: CheckId): CheckText = when (id) {
    CheckId.EXACT_ALARM -> CheckText(R.string.check_exact_title, R.string.check_exact_body, SystemSettings::exactAlarm)
    CheckId.NOTIFICATIONS -> CheckText(R.string.check_notifications_title, R.string.check_notifications_body, SystemSettings::notifications)
    CheckId.FULL_SCREEN -> CheckText(R.string.check_fullscreen_title, R.string.check_fullscreen_body, SystemSettings::fullScreen)
    CheckId.BATTERY -> CheckText(R.string.check_battery_title, R.string.check_battery_body) { SystemSettings.batteryOptimizationList() }
}

private class VendorStrings(val name: Int, val autostart: Int, val background: Int, val lock: Int)

private fun vendorStrings(vendor: Vendor): VendorStrings = when (vendor) {
    Vendor.XIAOMI -> VendorStrings(R.string.vendor_name_xiaomi, R.string.vendor_xiaomi_autostart, R.string.vendor_xiaomi_background, R.string.vendor_xiaomi_lock)
    Vendor.HUAWEI -> VendorStrings(R.string.vendor_name_huawei, R.string.vendor_huawei_autostart, R.string.vendor_huawei_background, R.string.vendor_huawei_lock)
    Vendor.HONOR -> VendorStrings(R.string.vendor_name_honor, R.string.vendor_honor_autostart, R.string.vendor_honor_background, R.string.vendor_honor_lock)
    Vendor.OPPO -> VendorStrings(R.string.vendor_name_oppo, R.string.vendor_oppo_autostart, R.string.vendor_oppo_background, R.string.vendor_oppo_lock)
    Vendor.VIVO -> VendorStrings(R.string.vendor_name_vivo, R.string.vendor_vivo_autostart, R.string.vendor_vivo_background, R.string.vendor_vivo_lock)
    Vendor.ONEPLUS -> VendorStrings(R.string.vendor_name_oneplus, R.string.vendor_oneplus_autostart, R.string.vendor_oneplus_background, R.string.vendor_oneplus_lock)
}
