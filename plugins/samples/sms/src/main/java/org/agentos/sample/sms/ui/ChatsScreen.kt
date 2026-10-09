package org.agentos.sample.sms.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.agentos.sample.sms.R
import org.agentos.sample.sms.agentos.SmsLine
import org.agentos.sample.sms.SmsGraph
import org.agentos.sample.sms.data.MessageQuery
import org.agentos.sample.sms.data.SmsAccess
import org.agentos.sample.sms.data.SmsBox
import org.agentos.sample.sms.data.SmsRecord
import org.agentos.sample.sms.data.ThreadScan
import org.agentos.sample.sms.data.ThreadSummary
import org.agentos.sample.sms.rules.CodeMasker
import org.agentos.sample.sms.ui.components.Avatar
import org.agentos.sample.sms.ui.components.ChatsIllustration
import org.agentos.sample.sms.ui.components.EmptyState
import org.agentos.sample.sms.ui.components.LockIllustration
import org.agentos.sample.sms.ui.theme.LocalSmsExtras

/** 会话浏览（只读）。没有读取权限时显示空状态并引导去授权；验证码按设置遮蔽，规则和 Agent 看到的完全一样。 */
@Composable
fun ChatsScreen(
    graph: SmsGraph,
    access: SmsAccess,
    maskCodes: Boolean,
    padding: PaddingValues,
    openThread: String?,
    onOpenThread: (String) -> Unit,
    onCloseThread: () -> Unit,
    onGoStatus: () -> Unit,
) {
    if (!access.canRead) {
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()), contentAlignment = Alignment.Center) {
            EmptyState(
                illustration = { LockIllustration() },
                title = stringResource(R.string.chats_no_permission_title),
                body = stringResource(R.string.chats_no_permission_body),
                actionLabel = stringResource(R.string.chats_go_status),
                onAction = onGoStatus,
            )
        }
        return
    }
    // 系统短信库变化（新短信到达、Agent 发出的短信被系统写入）时刷新
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reload++
            }
        }
        context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    if (openThread != null) {
        ConversationScreen(graph, openThread, maskCodes, reload, padding, onCloseThread)
    } else {
        ThreadList(graph, maskCodes, reload, padding, onOpenThread)
    }
}

@Composable
private fun ThreadList(graph: SmsGraph, maskCodes: Boolean, reload: Int, padding: PaddingValues, onOpen: (String) -> Unit) {
    val scan by produceState<ThreadScan?>(initialValue = null, reload) {
        value = withContext(Dispatchers.IO) { runCatching { graph.gateway.threads() }.getOrNull() }
    }
    val loaded = scan
    when {
        loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        loaded.threads.isEmpty() -> Box(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                illustration = { ChatsIllustration() },
                title = stringResource(R.string.chats_empty_title),
                body = stringResource(R.string.chats_empty_body),
            )
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 8.dp,
            ),
        ) {
            item {
                Text(
                    stringResource(R.string.chats_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (maskCodes) {
                item {
                    Text(
                        stringResource(R.string.chats_masked_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
                    )
                }
            }
            items(loaded.threads, key = { it.threadId.toString() + it.address }) { thread ->
                ThreadRow(thread, maskCodes) { onOpen(thread.address) }
                HorizontalDivider(Modifier.padding(start = 80.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ThreadRow(thread: ThreadSummary, maskCodes: Boolean, onClick: () -> Unit) {
    val locale = Locale.getDefault()
    val snippet = if (maskCodes) CodeMasker.mask(thread.snippet).text else thread.snippet
    val unread = thread.unreadCount > 0
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AddressAvatar(thread.address)
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    thread.address,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    MessageTime.format(thread.lastDateMillis, System.currentTimeMillis(), ZoneId.systemDefault(), locale),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.messages_count, thread.messageCount, thread.messageCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
                if (unread) {
                    Spacer(Modifier.size(8.dp))
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                        Text(
                            stringResource(R.string.chats_unread, thread.unreadCount),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressAvatar(address: String) {
    val numeric = address.none { it.isLetter() }
    if (numeric) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Call, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp)) }
    } else {
        Avatar(address.take(1).uppercase())
    }
}

/** 一个号码的消息，旧的在上、新的在下。只读：回复交给系统短信界面（撰写不需要任何短信权限）。 */
@Composable
private fun ConversationScreen(
    graph: SmsGraph,
    address: String,
    maskCodes: Boolean,
    reload: Int,
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    val messages by produceState<List<SmsRecord>?>(initialValue = null, address, reload) {
        value = withContext(Dispatchers.IO) {
            runCatching { graph.gateway.messages(MessageQuery(address, null, null, 0, 200)).reversed() }.getOrNull()
        }
    }
    val list = messages
    val processedIds by graph.processed.ids.collectAsState()
    // 要交给 AgentOS 的：这个会话里收到 / 发出的短信，带“是否已处理”；按钮据此数“未处理”
    val lines = remember(list, maskCodes, processedIds) { list?.let { SmsLine.from(it, maskCodes, processedIds) }.orEmpty() }
    val unprocessed = lines.count { !it.processed }
    val listState = rememberLazyListState()
    LaunchedEffect(list?.size) {
        if (!list.isNullOrEmpty()) listState.scrollToItem(list.size - 1)
    }
    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            AddressAvatar(address)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(address, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (maskCodes) {
                    Text(
                        stringResource(R.string.chats_masked_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f)) {
            if (list == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.id }) { Bubble(it, maskCodes, it.id in processedIds) }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).padding(bottom = padding.calculateBottomPadding())) {
                AskAgentButton(
                    unprocessed = unprocessed,
                    enabled = lines.isNotEmpty(),
                    onClick = {
                        val records = list ?: return@AskAgentButton
                        val schedule = graph.agentSchedule
                        schedule.open(schedule.sourceFor(address, records, maskCodes))
                    },
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.chats_readonly_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(12.dp))
                    FilledTonalButton(onClick = { graph.gateway.openComposer(address, null) }) {
                        Text(stringResource(R.string.chats_open_composer), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(record: SmsRecord, maskCodes: Boolean, processed: Boolean) {
    val extras = LocalSmsExtras.current
    val incoming = record.box == SmsBox.INBOX
    val body = if (maskCodes) CodeMasker.mask(record.body).text else record.body
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp, topEnd = 20.dp,
                bottomStart = if (incoming) 4.dp else 20.dp, bottomEnd = if (incoming) 20.dp else 4.dp,
            ),
            color = if (incoming) extras.bubbleIn else extras.bubbleOut,
            contentColor = if (incoming) extras.onBubbleIn else extras.onBubbleOut,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(3.dp))
                val faint = (if (incoming) extras.onBubbleIn else extras.onBubbleOut).copy(alpha = 0.65f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        MessageTime.format(record.dateMillis, System.currentTimeMillis(), ZoneId.systemDefault(), Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = faint,
                    )
                    if (processed) {
                        Spacer(Modifier.size(8.dp))
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = faint, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.size(2.dp))
                        Text(stringResource(R.string.ask_processed_tag), style = MaterialTheme.typography.labelSmall, color = faint, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * 会话页底部的主按钮：把这个会话里**还没处理过**的短信交给 AgentOS，建日程、待办和闹钟（点开是预览面板，可改提示词）。
 * 右边的小标签数出还有几条没处理；全处理过了写“已全部处理”（仍可点：面板里可以选择把处理过的再处理一遍）。
 */
@Composable
private fun AskAgentButton(unprocessed: Int, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.ask_button), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(10.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f), contentColor = MaterialTheme.colorScheme.onPrimary) {
            Text(
                if (unprocessed > 0) pluralStringResource(R.plurals.ask_button_unprocessed, unprocessed, unprocessed) else stringResource(R.string.ask_button_all_done),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}
