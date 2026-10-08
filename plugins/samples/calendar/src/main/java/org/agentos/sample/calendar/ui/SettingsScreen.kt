package org.agentos.sample.calendar.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.agentos.sample.calendar.R
import org.agentos.sample.calendar.data.CalendarInfo
import org.agentos.sample.calendar.data.CalendarRepository

/** 设置：应用语言（跳系统的“应用语言”）、日历权限、默认写入日历。 */
@Composable
fun SettingsScreen(
    data: CalendarData,
    fmt: Fmt,
    permission: CalendarPermissionState,
    repo: CalendarRepository,
    onBack: () -> Unit,
    onSetDefault: (CalendarInfo?) -> Unit,
) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val defaultCal = data.calendar(data.defaultWriteId)
    val automatic = repo.configuredDefaultId == null || data.calendar(repo.configuredDefaultId.orEmpty()) == null
    val locale = LocalConfiguration.current.locales[0]
    val autoLabel = stringResource(R.string.settings_default_auto)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(stringResource(R.string.settings_title), onBack)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionCard {
                    SettingRow(
                        stringResource(R.string.settings_language),
                        locale.getDisplayName(locale),
                        onClick = {
                            // 系统的“应用语言”页：默认跟随系统，也可以只给本 App 指定中文 / 英文
                            try {
                                context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                            } catch (_: ActivityNotFoundException) {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                            }
                        },
                    )
                }
                SectionCard {
                    SettingRow(
                        stringResource(R.string.settings_permission),
                        stringResource(if (permission.granted) R.string.settings_permission_granted else R.string.settings_permission_missing),
                        onClick = if (permission.granted) null else permission::requestOrOpenSettings,
                    )
                    Text(
                        stringResource(R.string.settings_permission_hint),
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SectionCard {
                    SettingRow(
                        stringResource(R.string.settings_default_calendar),
                        buildString {
                            append(defaultCal?.name.orEmpty())
                            if (automatic) append(" · ").append(autoLabel)
                        },
                        onClick = { picking = true },
                    )
                    Text(
                        stringResource(R.string.settings_default_calendar_hint),
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(1.dp))
            }
        }
    }
    if (picking) {
        val choices = data.calendars.filter { it.writable }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.settings_default_calendar)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    ChoiceRow(stringResource(R.string.settings_default_auto_choice), null, automatic) { onSetDefault(null); picking = false }
                    for (c in choices) {
                        ChoiceRow(c.name, fmt.originLabel(c), !automatic && c.id == data.defaultWriteId) { onSetDefault(c); picking = false }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: (() -> Unit)?) {
    // 标题在上、当前值在下：英文和 1.3 倍字体下值也不会被挤到截断
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
