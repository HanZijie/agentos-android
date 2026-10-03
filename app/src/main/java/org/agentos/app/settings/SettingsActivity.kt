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

    private data class Snapshot(
        val version: Int,
        val source: Byok.Source?,
        val runtime: String?,
        val supervisor: String?,
        val diagnostics: String?,
        val desktop: Desktop.Access?,
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
                    error = null,
                )
            }
        } catch (e: Exception) {
            Snapshot(0, null, null, null, null, null, "连不上 AgentOS 运行时（${e.javaClass.simpleName}）")
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

        // ---- security level (principle 6): "rooted" only when the root supervisor reported this boot
        val bootCount = android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
            .takeIf { it >= 0 }
        column.addView(Ui.sectionTitle(this, "安全等级"))
        column.addView(Ui.card(this).apply {
            addView(Ui.line(context, "等级", SecurityText.LEVEL))
            addView(Ui.paragraph(context, SecurityText.settings(StatusText.supervisorThisBoot(s.supervisor, bootCount))))
        })

        // ---- runtime and supervisor
        val exempt = Battery.isExempt(this)
        column.addView(Ui.sectionTitle(this, "运行与监督"))
        column.addView(Ui.card(this).apply {
            if (s.runtime != null) StatusText.runtime(s.runtime, exempt).forEach { addView(Ui.line(context, it.label, it.value, it.warn)) }
            StatusText.supervisor(s.supervisor, StatusText.supervisorMissing(s.diagnostics), bootCount)
                .forEach { addView(Ui.line(context, it.label, it.value, it.warn)) }
            val actions = mutableListOf<Pair<CharSequence, () -> Unit>>("刷新" to { reloadLater() })
            if (!exempt && StatusText.foregroundDenied(s.runtime)) {
                actions.add(0, (BatteryText.ACTION as CharSequence) to { Battery.request(this@SettingsActivity) })
            }
            addView(Ui.buttons(context, *actions.toTypedArray()))
        })

        // ---- desktop access (F11, W9)
        column.addView(Ui.sectionTitle(this, "电脑端接入"))
        column.addView(Ui.card(this).apply {
            val d = s.desktop
            if (d == null) {
                addView(Ui.line(context, "电脑端接入", if (s.version in 1 until AgentControlClient.DESKTOP_VERSION) "运行时版本过旧，不支持" else "读取中…"))
                return@apply
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Ui.line(context, "开发者功能：允许电脑经 adb 连接", Desktop.summary(d), warn = d.listenError != null),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Switch(context).apply {
                    isChecked = d.enabled
                    contentDescription = "电脑端接入"
                    setOnCheckedChangeListener { sw, on ->
                        if (on) {
                            desktop { it.setDesktopAccessEnabled(true) }
                            // F11 item 4: without the exemption the runtime may not get back to the foreground
                            if (!Battery.isExempt(this@SettingsActivity)) {
                                confirm(BatteryText.DESKTOP_DIALOG_TITLE, BatteryText.DESKTOP_DIALOG_MESSAGE, "去允许", cancelLabel = "暂不") {
                                    Battery.request(this@SettingsActivity)
                                }
                            }
                        } else if (d.pairings.isEmpty() && d.connections == 0) {
                            desktop { it.setDesktopAccessEnabled(false) }
                        } else {
                            // turning it off disconnects every computer and voids all pairings (IAgentControl v3)
                            confirm(
                                "关闭电脑端接入？",
                                "会断开 ${d.connections} 个连接，并作废 ${d.pairings.size} 台电脑的配对；以后再打开需要重新配对。",
                                "关闭",
                                onCancel = { sw.setOnCheckedChangeListener(null); sw.isChecked = true; reloadLater() },
                            ) { desktop { it.setDesktopAccessEnabled(false) } }
                        }
                    }
                })
            })
            addView(Ui.paragraph(context, Desktop.FOREGROUND_NOTE))
            if (Desktop.needsBatteryExemption(d.enabled, exempt)) {
                addView(Ui.line(context, BatteryText.DENIED_LABEL, BatteryText.DESKTOP_WARNING, warn = true))
                addView(Ui.buttons(context, BatteryText.ACTION to { Battery.request(this@SettingsActivity) }))
            }
            if (d.enabled) {
                addView(Ui.paragraph(context, Desktop.HOW_TO))
                d.pairings.forEach { p ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(Ui.line(context, "已配对", p.label), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        addView(Ui.textButton(context, "撤销", R.color.ui_error) { desktop { it.revokeDesktopPairing(p.id) } })
                    })
                }
                val actions = mutableListOf<Pair<CharSequence, () -> Unit>>("生成配对码" to { newPairingCode() })
                if (d.pairings.size > 1) {
                    actions += ("全部撤销" as CharSequence) to {
                        confirm("撤销全部 ${d.pairings.size} 台电脑的配对？", "它们的连接会立即断开，需要重新配对才能再连。", "全部撤销") {
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
        cancelLabel: String = "取消",
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
                Toast.makeText(this@SettingsActivity, Desktop.errorText(e.message), Toast.LENGTH_LONG).show()
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
                    .setTitle("配对码 ${code.code}")
                    .setMessage(
                        "电脑用 USB 连上手机，在 AgentOS 仓库目录里运行：\n\n$command\n\n" +
                            "约 $minutes 分钟内有效，配对成功或输错 5 次后作废。配对之后，把 acp-bridge 设为 ACP 客户端的 Agent 命令即可。",
                    )
                    .setPositiveButton("好", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, Desktop.errorText(e.message), Toast.LENGTH_LONG).show()
            }
            reload()
        }
    }
}
