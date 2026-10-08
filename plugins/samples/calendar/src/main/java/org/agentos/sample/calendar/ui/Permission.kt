package org.agentos.sample.calendar.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.agentos.sample.calendar.R
import org.agentos.sample.calendar.data.CalendarRepository

/**
 * 日历权限（READ_CALENDAR + WRITE_CALENDAR）的界面状态。
 * [permanentlyDenied]：问过并被拒绝、且系统不会再弹窗（“不再询问”），只能去应用设置里开。
 */
class CalendarPermissionState(
    val granted: Boolean,
    val permanentlyDenied: Boolean,
    private val ask: () -> Unit,
    private val openSettings: () -> Unit,
) {
    /** 没问过 / 还能再问：弹系统授权框；被永久拒绝：直接去应用设置。 */
    fun requestOrOpenSettings() = if (permanentlyDenied) openSettings() else ask()
}

private val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

private fun hasCalendarPermission(context: Context): Boolean = PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun rememberCalendarPermission(repo: CalendarRepository): CalendarPermissionState {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("calendar_settings", Context.MODE_PRIVATE) }
    var granted by remember { mutableStateOf(hasCalendarPermission(context)) }
    var asked by remember { mutableStateOf(prefs.getBoolean(KEY_ASKED, false)) }
    // 从系统设置回来（或在设置里改了权限）：重新读一次，并让仓库重读系统日历
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = hasCalendarPermission(context)
        if (now != granted) {
            granted = now
            repo.refresh()
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = PERMISSIONS.all { result[it] == true } || hasCalendarPermission(context)
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        asked = true
        repo.refresh()
    }
    val activity = context.findActivity()
    val permanentlyDenied = !granted && asked && activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)
    return CalendarPermissionState(
        granted, permanentlyDenied,
        ask = { launcher.launch(PERMISSIONS) },
        openSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}

private const val KEY_ASKED = "calendar_permission_asked"

/** 首页顶部的权限引导：说清楚授权后能做什么；被永久拒绝后按钮改成“去设置”。 */
@Composable
fun CalendarPermissionBanner(permission: CalendarPermissionState, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.EventAvailable, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.perm_title),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(if (permission.permanentlyDenied) R.string.perm_body_settings else R.string.perm_body),
                Modifier.padding(top = 4.dp, end = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                Button(onClick = permission::requestOrOpenSettings) {
                    Text(stringResource(if (permission.permanentlyDenied) R.string.perm_action_settings else R.string.perm_action_grant), maxLines = 1)
                }
            }
        }
    }
}
