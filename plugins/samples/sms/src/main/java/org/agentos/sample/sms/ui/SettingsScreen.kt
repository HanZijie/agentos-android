package org.agentos.sample.sms.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.agentos.sample.sms.BuildConfig
import org.agentos.sample.sms.R
import org.agentos.sample.sms.data.SmsSettingsValues
import org.agentos.sample.sms.rules.RateLimit
import org.agentos.sample.sms.ui.components.SectionCard

/** 设置页：安全开关（验证码、短号、发送频率）、数据去向、语言入口、关于。 */
@Composable
fun SettingsScreen(
    settings: SmsSettingsValues,
    padding: PaddingValues,
    onUpdate: ((SmsSettingsValues) -> SmsSettingsValues) -> Unit,
    onOpenLanguage: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        }
        item {
            SectionCard {
                Text(stringResource(R.string.settings_safety), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                // 界面上的开关是“允许读取验证码”，开 = 不遮蔽
                SwitchRow(
                    title = stringResource(R.string.setting_codes_title),
                    body = stringResource(if (settings.maskCodes) R.string.setting_codes_off else R.string.setting_codes_on),
                    checked = !settings.maskCodes,
                    onChange = { allow -> onUpdate { it.copy(maskCodes = !allow) } },
                )
                Spacer(Modifier.height(14.dp))
                SwitchRow(
                    title = stringResource(R.string.setting_short_title),
                    body = stringResource(R.string.setting_short_body),
                    checked = settings.allowShortNumbers,
                    onChange = { allow -> onUpdate { it.copy(allowShortNumbers = allow) } },
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.setting_rate_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.setting_rate_body, settings.rateLimit),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { onUpdate { it.copy(rateLimit = it.rateLimit - 1) } },
                        enabled = settings.rateLimit > RateLimit.MIN_LIMIT,
                    ) { Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.rate_decrease)) }
                    Text(
                        settings.rateLimit.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.width(36.dp),
                        maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    FilledTonalIconButton(
                        onClick = { onUpdate { it.copy(rateLimit = it.rateLimit + 1) } },
                        enabled = settings.rateLimit < RateLimit.MAX_LIMIT,
                    ) { Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.rate_increase)) }
                }
            }
        }
        item { DisclosureCard() }
        item {
            SectionCard(Modifier.clickable { onOpenLanguage() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.settings_language_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            SectionCard {
                Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
