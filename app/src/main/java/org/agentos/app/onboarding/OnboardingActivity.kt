package org.agentos.app.onboarding

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.agentos.app.R
import org.agentos.app.i18n.AndroidStrings
import org.agentos.app.i18n.Strings
import org.agentos.app.settings.AgentControlClient
import org.agentos.app.settings.Battery
import org.agentos.app.settings.BatteryText
import org.agentos.app.settings.Byok
import org.agentos.app.settings.ModelSourceActivity
import org.agentos.app.settings.StatusText
import org.agentos.app.ui.Ui

/**
 * First-run guide (F2 step 3): model & key, notifications, default assistant, battery optimisation
 * exemption, discovered plugins (placeholder until M3a). Every step can be skipped. Reading the model
 * source binds `:agent`, so after the first open the root supervisor has a runtime to adopt
 * (supervision contract v0.2: a never-opened App is not started at boot).
 */
class OnboardingActivity : Activity() {
    private lateinit var column: LinearLayout
    private var scope: CoroutineScope? = null
    private var model: Byok.Source? = null
    private var rooted: Boolean? = null
    private val strings: Strings by lazy { AndroidStrings(this) }
    /** The battery exemption was asked on "Get started" in this visit (asked at most once). */
    private var askedBattery = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (root, col) = Ui.page(this, getString(R.string.onboarding_title))
        column = col
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
        scope = MainScope().also { s ->
            s.launch {
                runCatching {
                    AgentControlClient(this@OnboardingActivity).use { c ->
                        val bootCount = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
                        model = Byok.parseSource(c.modelSource)
                        rooted = when {
                            StatusText.supervisorThisBoot(c.supervisorStatus, bootCount) -> true
                            StatusText.supervisorMissing(c.diagnostics) -> false
                            else -> null
                        }
                    }
                }
                render()
            }
        }
    }

    override fun onPause() {
        scope?.cancel()
        scope = null
        super.onPause()
    }

    private fun facts() = Onboarding.Facts(
        modelConfigured = model?.configured,
        modelUsable = model?.usable,
        notificationsGranted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        assistantHeld = getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true,
        batteryExempt = Battery.isExempt(this),
        pluginCount = 0, // M3a: Extension Host discovery
        rooted = rooted,
    )

    private fun render() {
        column.removeAllViews()
        val steps = Onboarding.steps(facts(), strings)
        steps.forEachIndexed { i, step ->
            column.addView(Ui.card(this).apply {
                val mark = when (step.done) {
                    true -> "✓ "
                    false -> "${i} · "
                    null -> ""
                }
                addView(Ui.text(context, 17f, R.color.ui_text, bold = true).apply { text = mark + step.title })
                addView(Ui.paragraph(context, step.detail))
                step.action?.let { label -> addView(Ui.buttons(context, label to { act(step.id) })) }
            }, Ui.matchWrap().apply { topMargin = Ui.dp(this@OnboardingActivity, 10) })
        }
        column.addView(Ui.buttons(this, getString(R.string.onboarding_start) to { finishGuide() }), Ui.matchWrap().apply { topMargin = Ui.dp(this@OnboardingActivity, 16) })
    }

    private fun act(id: Onboarding.Id) {
        when (id) {
            Onboarding.Id.MODEL -> startActivity(Intent(this, ModelSourceActivity::class.java))
            Onboarding.Id.NOTIFICATIONS -> requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            Onboarding.Id.ASSISTANT -> open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            Onboarding.Id.BATTERY -> Battery.request(this)
            else -> Unit
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.settings_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun finishGuide() {
        if (Onboarding.askBatteryOnFinish(facts(), askedBattery)) {
            askedBattery = true
            android.app.AlertDialog.Builder(this)
                .setTitle(BatteryText.guideDialogTitle(strings))
                .setMessage(BatteryText.guideDialogMessage(strings))
                .setPositiveButton(R.string.battery_dialog_allow) { _, _ -> Battery.request(this) } // back here afterwards; "Get started" then leaves
                .setNegativeButton(R.string.battery_dialog_later) { _, _ -> finishGuide() }
                .show()
            return
        }
        markDone(this)
        finish()
    }

    companion object {
        private const val REQ_NOTIFICATIONS = 1

        fun isDone(ctx: Context): Boolean =
            ctx.getSharedPreferences(Onboarding.PREFS, Context.MODE_PRIVATE).getBoolean(Onboarding.KEY_DONE, false)

        fun markDone(ctx: Context) {
            ctx.getSharedPreferences(Onboarding.PREFS, Context.MODE_PRIVATE).edit().putBoolean(Onboarding.KEY_DONE, true).apply()
        }
    }
}
