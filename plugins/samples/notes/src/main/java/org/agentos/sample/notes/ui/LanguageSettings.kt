package org.agentos.sample.notes.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** “语言”入口：跳到系统设置里本 App 的“应用语言”（minSdk 35，用平台的 Intent，不引入 AppCompat）。 */
object LanguageSettings {
    fun intent(packageName: String): Intent =
        Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", packageName, null))

    /** 打开系统的应用语言设置；设备上没有这个页面（个别定制系统）返回 false，由调用方提示。 */
    fun open(context: Context): Boolean =
        try {
            context.startActivity(intent(context.packageName))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
}
