package org.agentos.sample.sms.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.util.Locale
import org.agentos.sample.sms.R
import org.agentos.sample.sms.data.DraftEntry
import org.agentos.sample.sms.data.OutboxEntry
import org.agentos.sample.sms.data.OutboxState
import org.agentos.sample.sms.rules.RateLimit
import org.agentos.sample.sms.ui.components.PlaneIllustration
import org.agentos.sample.sms.ui.components.EmptyState
import org.agentos.sample.sms.ui.components.SectionCard
import org.agentos.sample.sms.ui.components.StatusPill
import org.agentos.sample.sms.ui.theme.LocalSmsExtras

/** “Agent 发送记录”：来自 outbox（本 App 发出的），显示状态、段数、时间和失败原因。 */
@Composable
fun AgentScreen(
    entries: List<OutboxEntry>,
    drafts: List<DraftEntry>,
    rateLimit: Int,
    padding: PaddingValues,
    onOpenDraft: (DraftEntry) -> Unit,
    onDismissDraft: (DraftEntry) -> Unit,
) {
    if (entries.isEmpty() && drafts.isEmpty()) {
        Box(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                illustration = { PlaneIllustration() },
                title = stringResource(R.string.agent_empty_title),
                body = stringResource(R.string.agent_empty_body),
            )
        }
        return
    }
    val now = System.currentTimeMillis()
    val recent = entries.count { it.createdAt > now - RateLimit.WINDOW_MILLIS }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(stringResource(R.string.agent_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        }
        if (drafts.isNotEmpty()) {
            item {
                Text(stringResource(R.string.drafts_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
                Text(
                    stringResource(R.string.drafts_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            items(drafts, key = { "draft" + it.id }) { DraftCard(it, onOpenDraft, onDismissDraft) }
        }
        item {
            Text(
                stringResource(R.string.agent_summary, recent, rateLimit),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Text(
                stringResource(R.string.agent_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
        items(entries, key = { it.id }) { EntryCard(it) }
    }
}

@Composable
private fun DraftCard(draft: DraftEntry, onOpen: (DraftEntry) -> Unit, onDismiss: (DraftEntry) -> Unit) {
    SectionCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(
            draft.to,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            draft.text ?: stringResource(R.string.draft_no_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onOpen(draft) }) { Text(stringResource(R.string.draft_open), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = { onDismiss(draft) }) { Text(stringResource(R.string.draft_dismiss), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun EntryCard(entry: OutboxEntry) {
    val extras = LocalSmsExtras.current
    val (label, icon, container, content) = stateStyle(entry.state)
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.to,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                StatusPill(stringResource(label), container, content)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(entry.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                MessageTime.format(entry.createdAt, System.currentTimeMillis(), ZoneId.systemDefault(), Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.size(10.dp))
            Text(
                pluralStringResource(R.plurals.parts_count, entry.parts, entry.parts),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (entry.state == OutboxState.FAILED && entry.error != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.failed_reason, entry.error),
                style = MaterialTheme.typography.bodySmall,
                color = extras.warn,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class StateStyle(val label: Int, val icon: ImageVector, val container: Color, val content: Color)

@Composable
private fun stateStyle(state: OutboxState): StateStyle {
    val scheme = MaterialTheme.colorScheme
    return when (state) {
        OutboxState.QUEUED -> StateStyle(R.string.state_queued, Icons.Rounded.Schedule, scheme.secondaryContainer, scheme.onSecondaryContainer)
        OutboxState.SENT -> StateStyle(R.string.state_sent, Icons.Rounded.Done, scheme.primaryContainer, scheme.onPrimaryContainer)
        OutboxState.DELIVERED -> StateStyle(R.string.state_delivered, Icons.Rounded.DoneAll, scheme.primary, scheme.onPrimary)
        OutboxState.FAILED -> StateStyle(R.string.state_failed, Icons.Rounded.ErrorOutline, scheme.errorContainer, scheme.onErrorContainer)
    }
}
