package org.agentos.sample.alarm.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import org.agentos.sample.alarm.AlarmGraph
import org.agentos.sample.alarm.R
import org.agentos.sample.alarm.ui.theme.AlarmTheme

/** 主界面：列表 ↔ 编辑页。编辑目标用 rememberSaveable 存，转屏 / 进程重建后回到同一个闹钟。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = AlarmGraph.get(this)
        setContent {
            AlarmTheme {
                val alarms by graph.repository.alarms.collectAsState()
                val ringing by graph.ring.ringing.collectAsState()
                var editing by rememberSaveable { mutableStateOf<String?>(null) } // null = 列表；"" = 新建；其它 = 闹钟 id

                // 编辑的闹钟被别处（MCP）删掉了：回到列表
                LaunchedEffect(editing, alarms) {
                    val id = editing
                    if (!id.isNullOrEmpty() && alarms.none { it.id == id }) editing = null
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
                    if (target == null) {
                        AlarmListScreen(
                            repository = graph.repository,
                            alarms = alarms,
                            ringing = ringing,
                            banners = { PermissionBanners() },
                            onNew = { editing = "" },
                            onEdit = { editing = it.id },
                            onDismissRinging = { graph.ring.dismiss() },
                        )
                    } else {
                        AlarmEditorScreen(
                            repository = graph.repository,
                            initial = alarms.firstOrNull { it.id == target },
                            onClose = { editing = null },
                        )
                    }
                }
            }
        }
    }
}

/** 通知权限（运行时申请）与全屏通知权限的提示条。授权后自动消失。 */
@Composable
private fun PermissionBanners() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val notificationManager = remember { context.getSystemService(NotificationManager::class.java) }

    // 回到前台（比如从系统设置回来）时重新检查
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    // 第一次进来直接弹系统权限框；被拒绝后用提示条引导去设置
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!asked && !notificationsGranted(context)) {
            asked = true
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val notificationsOk = remember(refresh) { notificationsGranted(context) }
    val fullScreenOk = remember(refresh) { notificationManager.canUseFullScreenIntent() }

    Column {
        if (!notificationsOk) {
            PermissionBanner(
                title = stringResource(R.string.perm_notifications_title),
                body = stringResource(R.string.perm_notifications_body),
                onGrant = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
            )
        }
        if (notificationsOk && !fullScreenOk) {
            PermissionBanner(
                title = stringResource(R.string.perm_fullscreen_title),
                body = stringResource(R.string.perm_fullscreen_body),
                onGrant = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri()),
                    )
                },
            )
        }
    }
}

private fun notificationsGranted(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

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
