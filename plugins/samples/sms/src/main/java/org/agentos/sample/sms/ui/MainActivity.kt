package org.agentos.sample.sms.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.agentos.sample.sms.R
import org.agentos.sample.sms.SmsGraph
import org.agentos.sample.sms.data.SmsMode
import org.agentos.sample.sms.ui.theme.SmsTheme

/**
 * 主界面：四个页签——状态（权限与引导）、会话（只读浏览）、Agent 发送记录、设置。不做完整客户端。
 * `adb shell am start -n org.agentos.sample.sms/.ui.MainActivity --ei tab 2`（0 状态 1 会话 2 Agent 发送 3 设置）可直接打开某个页签。
 */
class MainActivity : ComponentActivity() {
    private var requestedTab by mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedTab = intent.tabExtra()
        val graph = SmsGraph.get(this)
        setContent {
            SmsTheme { SmsRoot(graph, requestedTab) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTab = intent.tabExtra()
    }

    private fun Intent.tabExtra(): Int? = getIntExtra(EXTRA_TAB, -1).takeIf { it in 0..3 }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}

private enum class Tab { STATUS, CHATS, AGENT, SETTINGS }

@Composable
private fun SmsRoot(graph: SmsGraph, requestedTab: Int?) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    // 回到前台（比如从系统设置回来）时重新检查权限
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose {}
    }
    val access = remember(refresh) { graph.gateway.access() }
    val settings by graph.settings.values.collectAsState()
    val outbox by graph.outbox.recent.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(requestedTab ?: if (access.mode == SmsMode.FULL) Tab.CHATS.ordinal else Tab.STATUS.ordinal) }
    var openThread by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            openThread = null
        }
    }
    BackHandler(enabled = openThread != null) { openThread = null }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val grant = {
        val missing = buildList {
            if (!access.canRead) add(Manifest.permission.READ_SMS)
            if (!access.canSend) add(Manifest.permission.SEND_SMS)
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
    val openAppInfo = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }
    val openLanguage = {
        context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }

    DisclosureDialog()

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavItem(Tab.STATUS, tab, R.string.tab_status, Icons.Rounded.VerifiedUser) { tab = it; openThread = null }
                NavItem(Tab.CHATS, tab, R.string.tab_chats, Icons.Rounded.Forum) { tab = it; openThread = null }
                NavItem(Tab.AGENT, tab, R.string.tab_agent, Icons.AutoMirrored.Rounded.Send) { tab = it; openThread = null }
                NavItem(Tab.SETTINGS, tab, R.string.tab_settings, Icons.Rounded.Settings) { tab = it; openThread = null }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
            modifier = Modifier,
            label = "tab",
        ) { current ->
            when (Tab.entries[current]) {
                Tab.STATUS -> StatusScreen(access, padding, onGrant = grant, onOpenAppInfo = openAppInfo)
                Tab.CHATS -> ChatsScreen(
                    graph = graph,
                    access = access,
                    maskCodes = settings.maskCodes,
                    padding = padding,
                    openThread = openThread,
                    onOpenThread = { openThread = it },
                    onCloseThread = { openThread = null },
                    onGoStatus = { tab = Tab.STATUS.ordinal },
                )
                Tab.AGENT -> AgentScreen(outbox, settings.rateLimit, padding)
                Tab.SETTINGS -> SettingsScreen(
                    settings = settings,
                    padding = padding,
                    onUpdate = { graph.settings.update(it) },
                    onOpenLanguage = openLanguage,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    tab: Tab,
    selected: Int,
    label: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onSelect: (Int) -> Unit,
) {
    NavigationBarItem(
        selected = selected == tab.ordinal,
        onClick = { onSelect(tab.ordinal) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/** 第一次打开：如实披露短信内容的去向（S6）。确认后不再弹，状态页和设置页里一直有同样的说明。 */
@Composable
private fun DisclosureDialog() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("sms_ui", android.content.Context.MODE_PRIVATE) }
    var acknowledged by remember { mutableStateOf(prefs.getBoolean("disclosure_ack", false)) }
    if (acknowledged) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.disclosure_title)) },
        text = { Text(stringResource(R.string.disclosure_body)) },
        confirmButton = {
            TextButton(onClick = {
                prefs.edit { putBoolean("disclosure_ack", true) }
                acknowledged = true
            }) { Text(stringResource(R.string.disclosure_ack)) }
        },
    )
}
