package org.agentos.sample.alarm.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import org.agentos.sample.alarm.AlarmGraph
import org.agentos.sample.alarm.R
import org.agentos.sample.alarm.reliability.SystemSettings
import org.agentos.sample.alarm.ui.theme.AlarmTheme

/**
 * 主界面：列表 ↔ 编辑页 ↔ “保证准时响铃”检查页。路由用 rememberSaveable 存，转屏 / 进程重建后回到同一页：
 * null = 列表；"" = 新建；[ROUTE_RELIABILITY] = 检查页；其它 = 闹钟 id。
 *
 * 系统的 ACTION_SHOW_ALARMS（"显示闹钟"）直接落在这里；[AlarmIntentActivity][org.agentos.sample.alarm.intent.AlarmIntentActivity]
 * 处理完设闹钟请求后，也会带 [EXTRA_OPEN_NEW] 把用户送到新建页。singleTask：已经开着时走 [onNewIntent]，回到对应页面。
 */
class MainActivity : ComponentActivity() {
    /** 外部 Intent 要求切到哪一页；nonce 保证连续两次相同的请求也能被重新处理。 */
    private data class RouteRequest(val route: String?, val nonce: Long)

    private var routeRequest by mutableStateOf<RouteRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = AlarmGraph.get(this)
        // 重建（转屏等）时 Intent 还是最初那个，路由由 rememberSaveable 恢复，不要再套一次
        if (savedInstanceState == null) requestRoute(intent)
        setContent {
            AlarmTheme {
                val alarms by graph.repository.alarms.collectAsState()
                val ringing by graph.ring.ringing.collectAsState()
                var editing by rememberSaveable { mutableStateOf<String?>(null) }

                val request = routeRequest
                LaunchedEffect(request) { if (request != null) editing = request.route }

                // 编辑的闹钟被别处（MCP）删掉了：回到列表
                LaunchedEffect(editing, alarms) {
                    val id = editing
                    if (!id.isNullOrEmpty() && id != ROUTE_RELIABILITY && alarms.none { it.id == id }) editing = null
                }
                BackHandler(enabled = editing != null) { editing = null }

                AnimatedContent(
                    targetState = editing,
                    transitionSpec = {
                        if (targetState != null) {
                            (slideInVertically(tween(320)) { it / 6 } + fadeIn(tween(280))) togetherWith fadeOut(tween(160))
                        } else {
                            fadeIn(tween(240)) togetherWith (slideOutVertically(tween(280)) { it / 6 } + fadeOut(tween(220)))
                        }
                    },
                    label = "main-content",
                ) { target ->
                    when (target) {
                        null -> AlarmListScreen(
                            repository = graph.repository,
                            alarms = alarms,
                            ringing = ringing,
                            banners = { PermissionBanners() },
                            onNew = { editing = "" },
                            onEdit = { editing = it.id },
                            onDismissRinging = { graph.ring.dismiss() },
                            onOpenReliability = { editing = ROUTE_RELIABILITY },
                            onOpenLanguage = { SystemSettings.open(this@MainActivity, SystemSettings.appLanguage(this@MainActivity)) },
                        )
                        ROUTE_RELIABILITY -> ReliabilityScreen(onBack = { editing = null })
                        else -> AlarmEditorScreen(
                            repository = graph.repository,
                            initial = alarms.firstOrNull { it.id == target },
                            onClose = { editing = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestRoute(intent)
    }

    private fun requestRoute(intent: Intent?) {
        val route = when {
            intent?.getBooleanExtra(EXTRA_OPEN_NEW, false) == true -> ""
            intent?.action == AlarmClock.ACTION_SHOW_ALARMS -> null
            else -> return
        }
        routeRequest = RouteRequest(route, System.nanoTime())
    }

    companion object {
        /** 带上它启动 MainActivity = 直接打开新建闹钟页（ACTION_SET_ALARM 没带时间时用）。 */
        const val EXTRA_OPEN_NEW = "org.agentos.sample.alarm.extra.OPEN_NEW"
        private const val ROUTE_RELIABILITY = "@reliability"
    }
}

/** 通知权限（运行时申请）与全屏通知权限的提示条。授权后自动消失。检查页（ReliabilityScreen）是同一份状态的完整版，读取和跳转共用 [SystemSettings]。 */
@Composable
private fun PermissionBanners() {
    val context = LocalContext.current
    val refresh = rememberResumeTick()

    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // 第一次进来直接弹系统权限框；被拒绝后用提示条引导去设置
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!asked && !SystemSettings.notificationsGranted(context)) {
            asked = true
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val notificationsOk = remember(refresh) { SystemSettings.notificationsGranted(context) }
    val fullScreenOk = remember(refresh) { SystemSettings.fullScreenAllowed(context) }

    Column {
        if (!notificationsOk) {
            PermissionBanner(
                title = stringResource(R.string.perm_notifications_title),
                body = stringResource(R.string.perm_notifications_body),
                onGrant = { SystemSettings.open(context, SystemSettings.notifications(context)) },
            )
        }
        if (notificationsOk && !fullScreenOk) {
            PermissionBanner(
                title = stringResource(R.string.perm_fullscreen_title),
                body = stringResource(R.string.perm_fullscreen_body),
                onGrant = { SystemSettings.open(context, SystemSettings.fullScreen(context)) },
            )
        }
    }
}

@Composable
private fun PermissionBanner(title: String, body: String, onGrant: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.NotificationsActive, contentDescription = null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(10.dp))
            Button(onClick = onGrant) { Text(stringResource(R.string.perm_grant)) }
        }
    }
}
