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
        scope = MainScope().also { it.launch { reload() } }
    }

    override fun onPause() {
        scope?.cancel()
        scope = null
        super.onPause()
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
    }

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

        // ---- security level (principle 6)
        column.addView(Ui.sectionTitle(this, "安全等级"))
        column.addView(Ui.card(this).apply {
            addView(Ui.line(context, "等级", "best_effort（尽力而为）"))
            addView(Ui.paragraph(context, SECURITY_TEXT))
        })

        // ---- runtime and supervisor
        column.addView(Ui.sectionTitle(this, "运行与监督"))
        column.addView(Ui.card(this).apply {
            if (s.runtime != null) StatusText.runtime(s.runtime).forEach { addView(Ui.line(context, it.label, it.value, it.warn)) }
            StatusText.supervisor(s.supervisor, StatusText.supervisorMissing(s.diagnostics))
                .forEach { addView(Ui.line(context, it.label, it.value, it.warn)) }
            addView(Ui.buttons(context, "刷新" to { scope?.launch { reload() } }))
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
                    setOnCheckedChangeListener { _, on -> desktop { it.setDesktopAccessEnabled(on) } }
                })
            })
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
                if (d.pairings.size > 1) actions += ("全部撤销" as CharSequence) to { desktop { it.revokeDesktopPairing("") } }
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
                val minutes = ((code.expiresAtMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("配对码")
                    .setMessage("${code.code}\n\n在电脑端第一次连接时输入。约 $minutes 分钟内有效，配对成功或输错 5 次后作废。")
                    .setPositiveButton("好", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, Desktop.errorText(e.message), Toast.LENGTH_LONG).show()
            }
            reload()
        }
    }

    companion object {
        const val SECURITY_TEXT =
            "这台手机已经 root，其他获得 root 权限的应用可以读取 AgentOS 的全部数据，包括加密前后的 key、对话和日志。\n\n" +
                "AgentOS 能做的是：key 用 Android Keystore 加密保存，只在发出模型请求的那一刻注入，不写进日志、诊断和通知；" +
                "Agent 的代码不以 root 运行，模型和插件接触不到 root。但它防不住拥有 root 权限的应用。" +
                "请只给信任的应用授予 root 权限。"
    }
}
