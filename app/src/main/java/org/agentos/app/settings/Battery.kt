package org.agentos.app.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import org.agentos.app.R

/**
 * Battery optimisation exemption (architecture F2 step 3, F11 item 4). The runtime needs it to enter the
 * foreground when it is started in the background by anyone but the root supervisor; without it the
 * start is refused (`foregroundDenied`) and the idle `:agent` is frozen, so desktop access stops answering.
 * Asked through the system's standard dialog (principle 2), from the first-run guide and the settings page.
 */
object Battery {
    fun isExempt(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    /** Opens the system dialog "let the app always run in the background?". */
    fun request(activity: Activity) {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")),
            )
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.settings_open_failed, Toast.LENGTH_LONG).show()
        }
    }
}
