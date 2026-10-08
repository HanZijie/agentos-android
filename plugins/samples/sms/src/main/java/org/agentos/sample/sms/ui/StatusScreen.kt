package org.agentos.sample.sms.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.agentos.sample.sms.R
import org.agentos.sample.sms.data.SmsAccess
import org.agentos.sample.sms.data.SmsMode
import org.agentos.sample.sms.ui.components.SectionCard
import org.agentos.sample.sms.ui.theme.LocalSmsExtras

/** 权限与状态页：当前模式、两个权限、授权按钮、“允许受限制的设置”图文引导、能力说明与数据去向。 */
@Composable
fun StatusScreen(access: SmsAccess, padding: PaddingValues, onGrant: () -> Unit, onOpenAppInfo: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Hero(access) }
        if (access.mode != SmsMode.FULL) {
            item {
                Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.perm_grant_button), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item { RestrictedGuide(onOpenAppInfo) }
        }
        item {
            SectionCard {
                Text(stringResource(R.string.status_agent_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.status_agent_can), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.status_agent_cannot),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { DisclosureCard() }
    }
}

@Composable
private fun Hero(access: SmsAccess) {
    val extras = LocalSmsExtras.current
    val shape = MaterialTheme.shapes.extraLarge
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(extras.heroStart, extras.heroEnd)))
            .padding(horizontal = 22.dp, vertical = 22.dp),
    ) {
        AnimatedContent(
            targetState = access,
            transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(160)) },
            label = "hero",
        ) { a ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(extras.onHero.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (a.mode == SmsMode.FULL) Icons.Rounded.Sms else Icons.Rounded.Edit, contentDescription = null, tint = extras.onHero)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        stringResource(
                            when (a.mode) {
                                SmsMode.FULL -> R.string.status_title_full
                                SmsMode.PARTIAL -> R.string.status_title_partial
                                SmsMode.COMPOSE_ONLY -> R.string.status_title_compose
                            },
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        color = extras.onHero,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(
                        when (a.mode) {
                            SmsMode.FULL -> R.string.status_body_full
                            SmsMode.PARTIAL -> R.string.status_body_partial
                            SmsMode.COMPOSE_ONLY -> R.string.status_body_compose
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.onHeroVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PermissionChip(R.string.perm_read, a.canRead, Modifier.weight(1f))
                    PermissionChip(R.string.perm_send, a.canSend, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PermissionChip(label: Int, granted: Boolean, modifier: Modifier = Modifier) {
    val extras = LocalSmsExtras.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = extras.onHero.copy(alpha = 0.16f),
        contentColor = extras.onHero,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(stringResource(label), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(if (granted) R.string.perm_granted else R.string.perm_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.onHeroVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** “允许受限制的设置”的图文引导：三步，中间一步配一张应用信息页的示意图。 */
@Composable
private fun RestrictedGuide(onOpenAppInfo: () -> Unit) {
    SectionCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.guide_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.guide_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Spacer(Modifier.height(14.dp))
        GuideStep(1, stringResource(R.string.guide_step1)) {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onOpenAppInfo) {
                Text(stringResource(R.string.guide_open_info), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(12.dp))
        GuideStep(2, stringResource(R.string.guide_step2)) {
            Spacer(Modifier.height(10.dp))
            AppInfoMock()
        }
        Spacer(Modifier.height(12.dp))
        GuideStep(3, stringResource(R.string.guide_step3))
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.guide_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun GuideStep(number: Int, text: String, extra: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 3.dp),
            )
            extra()
        }
    }
}

/** 应用信息页的示意图：右上角 ⋮ 展开的菜单里有“允许受限制的设置”。纯示意，不可点。 */
@Composable
private fun AppInfoMock() {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(132.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, outline),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.guide_mock_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.MoreVert, contentDescription = null, modifier = Modifier.size(20.dp)) }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Box(Modifier.width(90.dp).height(8.dp).clip(CircleShape).background(outline))
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.width(56.dp).height(6.dp).clip(CircleShape).background(outline))
                    }
                }
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 10.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 6.dp,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary),
        ) {
            Text(
                stringResource(R.string.guide_mock_menu),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 数据去向（S6）：和第一次打开的对话框、设置页同一段话。 */
@Composable
fun DisclosureCard() {
    SectionCard(container = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            stringResource(R.string.disclosure_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.disclosure_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
