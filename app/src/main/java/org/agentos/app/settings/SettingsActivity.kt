package org.agentos.app.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.agentos.app.R
import org.agentos.app.i18n.AndroidStrings
import org.agentos.app.i18n.Strings
import org.agentos.app.onboarding.OnboardingActivity
import org.agentos.app.ui.Ui

/**
 * Settings (W8): model & key (BYOK, IAgentControl v2), security level, runtime and supervisor
 * status (v1), desktop access (v3, W9), first-run guide, about. Reads everything from `:agent`
 * over IAgentControl each time the page is shown.
 */
class SettingsActivity : Activity() {
    private lateinit var column: LinearLayout
    private val control by lazy { AgentControlClient(this) }
    private var scope: CoroutineScope? = null
    private val strings: Strings by lazy { AndroidStrings(this) }

    private data class Snapshot(
        val version: Int,
        val source: Byok.Source?,
        val runtime: String?,
        val supervisor: String?,
        val diagnostics: String?,
        val desktop: Desktop.Access?,
        val jev: Jev.Source?,
        val error: String?,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (root, col) = Ui.page(this, getString(R.string.ui_settings))
        column = col
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        foregroundRetried = false
        scope = MainScope().also { it.launch { reload() } }
    }

    override fun onPause() {
        scope?.cancel()
        scope = null
        super.onPause()
    }

    /**
     * Pulling down the notification shade does not pause the page, but the shade can change what it shows
     * (the desktop-access notification's "关闭" turns the switch off): read again when the window is back.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) reloadLater()
    }

    private suspend fun reload() {
        val snap = try {
            control.use { c ->
                val v = c.version
                Snapshot(
                    version = v,
                    source = if (v >= AgentControlClient.BYOK_VERSION) Byok.parseSource(c.modelSource) else null,
                    runtime = c.runtimeStatus,
                    supervisor = c.supervisorStatus,
                    diagnostics = c.diagnostics,
                    desktop = if (v >= AgentControlClient.DESKTOP_VERSION) Desktop.parseAccess(c.desktopAccess) else null,
                    jev = if (v >= AgentControlClient.JEV_VERSION) Jev.parse(c.jevSource) else null,
                    error = null,
                )
            }
        } catch (e: Exception) {
            Snapshot(0, null, null, null, null, null, null, "连不上 AgentOS 运行时（${e.javaClass.simpleName}）")
        }
        render(snap)
        // Desktop access is on but the runtime's foreground start was refused (started in the background without
        // the battery exemption): ask again now, while this page makes the App's UID foreground, which the
        // system allows. Turning the switch "on" again changes nothing (same pairings, same code) but makes
        // the runtime request the foreground once more (RuntimeLifecycle.onHoldChanged). Once per visit.
        if (!foregroundRetried && snap.desktop?.enabled == true && StatusText.foregroundDenied(snap.runtime)) {
            foregroundRetried = true
            runCatching { control.use { it.setDesktopAccessEnabled(true) } }
            reload()
        }
    }

    /** The foreground was asked for again in this visit (see [reload]). */
    private var foregroundRetried = false

    private fun render(s: Snapshot) {
        column.removeAllViews()
        if (s.error != null) column.addView(Ui.card(this).apply { addView(Ui.line(context, "运行时", s.error, warn = true)) })

        // ---- model & key
        column.addView(Ui.sectionTitle(this, "模型与 key"))
        column.addView(Ui.card(this).apply {
            val src = s.source
            when {
                s.version in 1 until AgentControlClient.BYOK_VERSION ->
                    addView(Ui.line(context, "模型", "运行时版本过旧，不支持在这里设置", warn = true))
                src == null -> addView(Ui.line(context, "模型", "读取中…"))
                !src.configured -> addView(Ui.line(context, "模型", "还没有配置：对话前需要选择模型厂商并填写 key", warn = true))
                else -> {
                    val who = if (src.kind == "preset") src.providerName ?: src.provider.orEmpty() else "自定义兼容端点"
                    addView(Ui.line(context, "来源", who))
                    addView(Ui.line(context, "模型", listOfNotNull(src.modelName ?: src.model, src.api?.let { Byok.apiText(it) }).joinToString(" · ")))
                    if (src.kind == "custom") addView(Ui.line(context, "地址", src.baseUrl.orEmpty()))
                    if (src.thinkingLevel != null && src.thinkingLevel != "off") addView(Ui.line(context, "思考强度", Byok.thinkingText(src.thinkingLevel)))
                    addView(Ui.line(context, src.keyLabel ?: "key", if (src.keySet) src.keyMasked ?: "已设置" else "未设置", warn = !src.keySet))
                    addView(Ui.line(context, "状态", if (src.usable) "可用" else "不可用", warn = !src.usable))
                    src.problems.forEach { addView(Ui.line(context, "注意", Byok.problemText(it), warn = true)) }
                }
            }
            if (s.version >= AgentControlClient.BYOK_VERSION) {
                val actions = mutableListOf<Pair<CharSequence, () -> Unit>>(
                    (if (src?.configured == true) "更换" else "去设置") to {
                        startActivity(Intent(context, ModelSourceActivity::class.java))
                    },
                )
                if (src?.configured == true) actions += ("清除" as CharSequence) to { confirmClear() }
                addView(Ui.buttons(context, *actions.toTypedArray()))
            }
        })

        // ---- auto-select session (Jev, IAgentControl v4)
        column.addView(Ui.sectionTitle(this, "自动选择会话（Jev）"))
        column.addView(Ui.card(this).apply {
            val j = s.jev
            if (j == null) {
                addView(Ui.line(context, "Jev", if (s.version in 1 until AgentControlClient.JEV_VERSION) "运行时版本过旧，不支持" else "读取中…"))
                return@apply
            }
            addView(Ui.line(context, "状态", Jev.statusText(j), warn = j.problems.isNotEmpty()))
            if (j.configured) {
                addView(Ui.line(context, "服务地址", if (j.customEndpoint) j.endpoint else "默认（${j.endpoint}）"))
                addView(Ui.line(context, "Jev key", if (j.keySet) j.keyMasked ?: "已设置" else "未设置", warn = !j.keySet))
            }
            j.problems.forEach { addView(Ui.line(context, "注意", Jev.problemText(it), warn = true)) }
            val actions = mutableListOf<Pair<CharSequence, () -> Unit>>(
                (if (j.configured) "更换" else "去设置") to { startActivity(Intent(context, JevSourceActivity::class.java)) },
            )
            if (j.configured) actions += ("清除" as CharSequence) to { confirmClearJev() }
            addView(Ui.buttons(context, *actions.toTypedArray()))
        })

        // ---- plugins (D5.3): the list itself lives in PluginsActivity
        column.addView(Ui.sectionTitle(this, "插件"))
        column.addView(Ui.card(this).apply {
            addView(Ui.paragraph(context, "其他 App 提供给 AgentOS 的工具（插件管理）；以及哪些第三方 App 被允许使用 AgentOS（已授权的应用）。第三方插件默认关闭，启用前会说明风险。"))
            addView(Ui.buttons(
                context,
                "已授权的应用" to { startActivity(Intent(context, org.agentos.app.settings.callers.AuthorizedAppsActivity::class.java)) },
                "插件管理" to { startActivity(Intent(context, org.agentos.app.settings.plugins.PluginsActivity::class.java)) },
            ))
        })

        // ---- security level (principle 6): "rooted" only when the root supervisor reported this boot
        val bootCount = android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
            .takeIf { it >= 0 }
        column.addView(Ui.sectionTitle(this, "安全等级"))
        column.addView(Ui.card(this).apply {
            addView(Ui.line(context, "等级", SecurityText.level(strings)))
            addView(Ui.paragraph(context, SecurityText.settings(StatusText.supervisorThisBoot(s.supervisor, bootCount), strings)))
        })

        // ---- runtime and supervisor
        val exempt = Battery.isExempt(this)
        column.addView(Ui.sectionTitle(this, "运行与监督"))
        column.addView(Ui.card(this).apply {
            if (s.runtime != null) StatusText.runtime(s.runtime, strings, exempt).forEach { addView(Ui.line(context, it.label, it.value, it.warn)) }
            StatusText.supervisor(s.supervisor, StatusText.supervisorMissing(s.diagnostics), strings, bootCount)
                .forEach { addView(Ui.line(context, it.label, it.value, it.warn)) }
            val actions = mutableListOf<Pair<CharSequence, () -> Unit>>("刷新" to { reloadLater() })
            if (!exempt && StatusText.foregroundDenied(s.runtime)) {
                actions.add(0, (BatteryText.action(strings) as CharSequence) to { Battery.request(this@SettingsActivity) })
            }
            addView(Ui.buttons(context, *actions.toTypedArray()))
        })

        // ---- desktop access (F11, W9)
        column.addView(Ui.sectionTitle(this, getString(R.string.desktop_section)))
        column.addView(Ui.card(this).apply {
            val d = s.desktop
            if (d == null) {
                addView(Ui.line(context, getString(R.string.desktop_section), getString(if (s.version in 1 until AgentControlClient.DESKTOP_VERSION) R.string.desktop_runtime_too_old else R.string.settings_loading)))
                return@apply
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Ui.line(context, getString(R.string.desktop_switch_label), Desktop.summary(d, strings), warn = d.listenError != null),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Switch(context).apply {
                    isChecked = d.enabled
                    contentDescription = getString(R.string.desktop_section)
                    setOnCheckedChangeListener { sw, on ->
                        if (on) {
                            desktop { it.setDesktopAccessEnabled(true) }
                            // F11 item 4: without the exemption the runtime may not get back to the foreground
                            if (!Battery.isExempt(this@SettingsActivity)) {
                                confirm(BatteryText.desktopDialogTitle(strings), BatteryText.desktopDialogMessage(strings), getString(R.string.battery_dialog_allow), cancelLabel = getString(R.string.battery_dialog_not_now)) {
                                    Battery.request(this@SettingsActivity)
                                }
                            }
                        } else if (d.pairings.isEmpty() && d.connections == 0) {
                            desktop { it.setDesktopAccessEnabled(false) }
                        } else {
                            // turning it off disconnects every computer and voids all pairings (IAgentControl v3)
                            confirm(
                                getString(R.string.desktop_disable_title),
                                getString(R.string.desktop_disable_message, Desktop.connections(d.connections, strings), Desktop.computers(d.pairings.size, strings)),
                                getString(R.string.desktop_disable_confirm),
                                onCancel = { sw.setOnCheckedChangeListener(null); sw.isChecked = true; reloadLater() },
                            ) { desktop { it.setDesktopAccessEnabled(false) } }
                        }
                    }
                })
            })
            addView(Ui.paragraph(context, Desktop.foregroundNote(strings)))
            if (Desktop.needsBatteryExemption(d.enabled, exempt)) {
                addView(Ui.line(context, BatteryText.deniedLabel(strings), BatteryText.desktopWarning(strings), warn = true))
                addView(Ui.buttons(context, BatteryText.action(strings) to { Battery.request(this@SettingsActivity) }))
            }
            if (d.enabled) {
                addView(Ui.paragraph(context, Desktop.howTo(strings)))
                d.pairings.forEach { p ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(Ui.line(context, getString(R.string.desktop_paired_label), Desktop.pairingLabel(p, strings)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        addView(Ui.textButton(context, getString(R.string.desktop_revoke), R.color.ui_error) { desktop { it.revokeDesktopPairing(p.id) } })
                    })
                }
                val actions = mutableListOf<Pair<CharSequence, () -> Unit>>(getString(R.string.desktop_generate_code) to { newPairingCode() })
                if (d.pairings.size > 1) {
                    actions += (getString(R.string.desktop_revoke_all) as CharSequence) to {
                        confirm(
                            getString(R.string.desktop_revoke_all_title, Desktop.computers(d.pairings.size, strings)),
                            getString(R.string.desktop_revoke_all_message),
                            getString(R.string.desktop_revoke_all),
                        ) {
                            desktop { it.revokeDesktopPairing("") }
                        }
                    }
                }
                addView(Ui.buttons(context, *actions.toTypedArray()))
            }
        })

        // ---- first-run guide, about
        column.addView(Ui.sectionTitle(this, "其他"))
        column.addView(Ui.card(this).apply {
            addView(Ui.buttons(context, "重新查看首次引导" to {
                startActivity(Intent(context, OnboardingActivity::class.java))
            }))
            val pi = packageManager.getPackageInfo(packageName, 0)
            addView(Ui.line(context, "版本", "AgentOS ${pi.versionName}（${pi.longVersionCode}）；运行时接口 v${s.version}"))
        })
    }

    private fun confirmClearJev() {
        confirm(
            "清除 Jev key？",
            "清除后立即作废：删除保存的 key 和它的加密密钥，正在进行的自动选择也会中止。之后每次都新建会话，直到重新设置。",
            "清除",
        ) {
            scope?.launch {
                try {
                    control.use { it.clearJevSource() }
                    Toast.makeText(this@SettingsActivity, "已清除", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@SettingsActivity, Jev.errorText(e.message), Toast.LENGTH_LONG).show()
                }
                reload()
            }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清除模型和 key？")
            .setMessage(
                "清除后立即作废：删除保存的 key 和它的加密密钥。之后的每一次模型请求都会失败，包括正在进行的这一轮里的下一次请求，" +
                    "不会再用旧 key 跑完。\n\n如果只是想换厂商、模型或 key，用“更换”即可，那样从下一次请求起生效，不打断正在进行的这一轮。",
            )
            .setPositiveButton("清除") { _, _ ->
                scope?.launch {
                    try {
                        control.use { it.clearModelSource() }
                        Toast.makeText(this@SettingsActivity, "已清除", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity, Byok.errorText(e.message), Toast.LENGTH_LONG).show()
                    }
                    reload()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirm(
        title: String,
        message: String,
        action: String,
        cancelLabel: String = getString(R.string.settings_cancel),
        onCancel: () -> Unit = {},
        onConfirm: () -> Unit,
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> onConfirm() }
            .setNegativeButton(cancelLabel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun reloadLater() {
        scope?.launch { reload() }
    }

    private fun desktop(call: (org.agentos.internal.IAgentControl) -> Unit) {
        scope?.launch {
            try {
                control.use(block = call)
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, Desktop.errorText(e.message, strings), Toast.LENGTH_LONG).show()
            }
            reload()
        }
    }

    private fun newPairingCode() {
        scope?.launch {
            try {
                val code = Desktop.parseCode(control.use { it.newDesktopPairingCode() })
                // round up: a 5-minute code read a moment later should still say 5, not 4
                val minutes = ((code.expiresAtMs - System.currentTimeMillis() + 59_999) / 60_000).coerceAtLeast(1)
                val command = Desktop.pairCommand(code.code)
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(getString(R.string.desktop_code_title, code.code))
                    .setMessage(getString(R.string.desktop_code_message, command, Desktop.minutes(minutes.toInt(), strings)))
                    .setPositiveButton(R.string.desktop_code_ok, null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, Desktop.errorText(e.message, strings), Toast.LENGTH_LONG).show()
            }
            reload()
        }
    }
}
