package org.agentos.app.settings.plugins

import android.app.AlertDialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Switch
import kotlinx.coroutines.launch
import org.agentos.app.R
import org.agentos.app.ui.Ui

/**
 * 插件详情（D5.3）：身份和状态、服务器（连接状态/连不上的原因）、全部问题，以及每个工具的风险等级、审批方式和开关。
 *
 * 审批：只读不确认；写操作默认“每次确认”，策略允许时可以改成“始终允许”（要先确认），也可以改回；高风险没有“始终允许”。
 * 工具的名称、描述都来自第三方，原样当纯文本显示（限长、去控制字符）。
 */
class PluginDetailActivity : PluginPage() {
    private lateinit var column: LinearLayout
    private var pluginId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pluginId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val (root, col) = Ui.page(this, "插件详情")
        column = col
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        scope?.launch {
            try {
                val (plugin, tools) = host.use {
                    locked = runCatching { Plugins.parsePolicy(it.policyStatus) }.getOrNull()?.let { s -> s.corrupt && s.failClosed } == true
                    val p = Plugins.parsePlugins(it.listPlugins()).firstOrNull { p -> p.id == pluginId }
                    p to if (p != null && p.status == Plugins.Status.READY && p.enabled) Plugins.parseTools(it.listTools(p.id)) else emptyList()
                }
                if (plugin == null) {
                    column.removeAllViews()
                    column.addView(Ui.paragraph(this@PluginDetailActivity, "这个插件已经不存在（可能刚被卸载）。"))
                } else render(plugin, tools)
            } catch (e: Exception) {
                column.removeAllViews()
                column.addView(Ui.paragraph(this@PluginDetailActivity, Plugins.errorText(e.message), R.color.ui_error))
            }
        }
    }

    private fun render(p: Plugins.Plugin, tools: List<Plugins.Tool>) {
        column.removeAllViews()
        column.addView(Ui.card(this).apply {
            addView(header(p) { on -> toggle(p, on) { reload() } })
            identity(this, p)
            if (p.id != p.packageName && p.id.isNotEmpty()) addView(Ui.paragraph(context, "插件 ID：${p.id}"))
        })

        if (locked) column.addView(Ui.paragraph(this, "用户策略文件有问题，暂时不能修改设置。到“插件管理”页重置策略后再试。", R.color.ui_error))
        val issues = Plugins.issues(p)
        if (issues.isNotEmpty()) {
            column.addView(Ui.sectionTitle(this, "问题与不支持的项"))
            column.addView(Ui.card(this).apply { issues.forEach { addView(Ui.paragraph(context, it, R.color.ui_warn)) } })
        }

        column.addView(Ui.sectionTitle(this, "服务"))
        column.addView(Ui.card(this).apply {
            if (p.servers.isEmpty()) addView(Ui.paragraph(context, "没有可用的服务。"))
            p.servers.forEach { addView(Ui.line(context, it.name.ifEmpty { "（未命名）" }, Plugins.serverStateText(it), warn = it.state == "unreachable")) }
        })

        column.addView(Ui.sectionTitle(this, "工具"))
        if (!p.enabled || p.status != Plugins.Status.READY) {
            column.addView(Ui.card(this).apply { addView(Ui.paragraph(context, "启用后可查看工具。")) })
        } else if (tools.isEmpty()) {
            column.addView(Ui.card(this).apply { addView(Ui.paragraph(context, "现在没有可用的工具。")) })
        } else {
            tools.forEach { column.addView(toolCard(it), Ui.matchWrap().apply { bottomMargin = dp(10) }) }
        }
        column.addView(Ui.buttons(this, "刷新" to { reload() }))
    }

    private fun toolCard(t: Plugins.Tool): LinearLayout = Ui.card(this).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(Ui.text(context, 16f, R.color.ui_text, bold = true).apply { text = t.display }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(context).apply {
                contentDescription = "启用工具 ${t.display}"
                isChecked = t.enabled
                isEnabled = !locked
                setOnClickListener {
                    val wanted = isChecked
                    isChecked = !wanted
                    call({ reload() }) { it.setToolEnabled(t.name, wanted) }
                }
            })
        })
        if (t.server.isNotEmpty() || t.tool != t.display) addView(Ui.paragraph(context, "${t.server.ifEmpty { "-" }} / ${t.tool}"))
        if (t.description.isNotEmpty()) addView(Ui.paragraph(context, t.description))
        addView(Ui.line(context, "风险", Plugins.riskText(t.risk), warn = t.risk == Plugins.Risk.HIGH))
        addView(Ui.line(context, "审批", Plugins.approvalText(t)))
        val actions = mutableListOf<Pair<CharSequence, () -> Unit>>()
        if (locked) return@apply
        if (Plugins.canSetAlways(t)) actions += ("设为始终允许" as CharSequence) to { confirmAlways(t) }
        if (Plugins.canRemoveAlways(t)) actions += ("改回每次确认" as CharSequence) to { call({ reload() }) { it.setToolApproval(t.name, "ask") } }
        if (actions.isNotEmpty()) addView(Ui.buttons(context, *actions.toTypedArray()))
    }

    private fun confirmAlways(t: Plugins.Tool) {
        show(
            AlertDialog.Builder(this)
                .setTitle("设为始终允许？")
                .setMessage("以后模型调用「${t.display}」时不再逐次向你确认，这个工具会直接修改数据。工具的行为由第三方 App 决定，AgentOS 不能保证它可信。随时可以改回“每次确认”。")
                .setPositiveButton("始终允许") { _, _ -> call({ reload() }) { it.setToolApproval(t.name, "always") } }
                .setNegativeButton("取消", null),
        )
    }

    companion object {
        const val EXTRA_ID = "plugin_id"
    }
}
