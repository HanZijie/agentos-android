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
        val (root, col) = Ui.page(this, strings.get(R.string.plugin_detail_title))
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
                    val p = Plugins.withAppLabels(Plugins.parsePlugins(it.listPlugins()), ::appLabelOf).firstOrNull { p -> p.id == pluginId }
                    p to if (p != null && p.status == Plugins.Status.READY && p.enabled) Plugins.parseTools(it.listTools(p.id)) else emptyList()
                }
                if (plugin == null) {
                    column.removeAllViews()
                    column.addView(Ui.paragraph(this@PluginDetailActivity, strings.get(R.string.plugin_detail_gone)))
                } else render(plugin, tools)
            } catch (e: Exception) {
                column.removeAllViews()
                column.addView(Ui.paragraph(this@PluginDetailActivity, Plugins.errorText(e.message, strings), R.color.ui_error))
            }
        }
    }

    private fun render(p: Plugins.Plugin, tools: List<Plugins.Tool>) {
        column.removeAllViews()
        column.addView(Ui.card(this).apply {
            addView(header(p) { on -> toggle(p, on) { reload() } })
            identity(this, p)
            if (p.id != p.packageName && p.id.isNotEmpty()) addView(Ui.paragraph(context, strings.get(R.string.plugin_detail_id, Plugins.frame(p.id, 160))))
        })

        if (locked) column.addView(Ui.paragraph(this, strings.get(R.string.plugin_detail_locked), R.color.ui_error))
        val issues = Plugins.issues(p, strings)
        if (issues.isNotEmpty()) {
            column.addView(Ui.sectionTitle(this, strings.get(R.string.plugin_detail_issues_section)))
            column.addView(Ui.card(this).apply { issues.forEach { addView(Ui.paragraph(context, it, R.color.ui_warn)) } })
        }

        column.addView(Ui.sectionTitle(this, strings.get(R.string.plugin_detail_servers_section)))
        column.addView(Ui.card(this).apply {
            if (p.servers.isEmpty()) addView(Ui.paragraph(context, strings.get(R.string.plugin_detail_no_servers)))
            p.servers.forEach { addView(Ui.line(context, it.name.ifEmpty { strings.get(R.string.plugin_detail_unnamed_server) }, Plugins.serverStateText(it, strings), warn = it.state == "unreachable")) }
        })

        column.addView(Ui.sectionTitle(this, strings.get(R.string.plugin_detail_tools_section)))
        if (!p.enabled || p.status != Plugins.Status.READY) {
            column.addView(Ui.card(this).apply { addView(Ui.paragraph(context, strings.get(R.string.plugin_detail_tools_after_enable))) })
        } else if (tools.isEmpty()) {
            column.addView(Ui.card(this).apply { addView(Ui.paragraph(context, strings.get(R.string.plugin_detail_tools_none))) })
        } else {
            tools.forEach { column.addView(toolCard(it), Ui.matchWrap().apply { bottomMargin = dp(10) }) }
        }
        column.addView(Ui.buttons(this, strings.get(R.string.plugin_detail_refresh) to { reload() }))
    }

    private fun toolCard(t: Plugins.Tool): LinearLayout = Ui.card(this).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(Ui.text(context, 16f, R.color.ui_text, bold = true).apply { text = t.display }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(context).apply {
                contentDescription = strings.get(R.string.plugin_detail_tool_switch_cd, t.display)
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
        addView(Ui.line(context, strings.get(R.string.plugin_detail_risk_label), Plugins.riskText(t.risk, strings), warn = t.risk == Plugins.Risk.HIGH))
        addView(Ui.line(context, strings.get(R.string.plugin_detail_approval_label), Plugins.approvalText(t, strings)))
        val actions = mutableListOf<Pair<CharSequence, () -> Unit>>()
        if (locked) return@apply
        if (Plugins.canSetAlways(t)) actions += (strings.get(R.string.plugin_detail_set_always) as CharSequence) to { confirmAlways(t) }
        if (Plugins.canRemoveAlways(t)) actions += (strings.get(R.string.plugin_detail_set_ask) as CharSequence) to { call({ reload() }) { it.setToolApproval(t.name, "ask") } }
        if (actions.isNotEmpty()) addView(Ui.buttons(context, *actions.toTypedArray()))
    }

    private fun confirmAlways(t: Plugins.Tool) {
        show(
            AlertDialog.Builder(this)
                .setTitle(strings.get(R.string.plugin_detail_always_title))
                .setMessage(Plugins.alwaysAllowMessage(t, strings))
                .setPositiveButton(strings.get(R.string.plugin_detail_always_confirm)) { _, _ -> call({ reload() }) { it.setToolApproval(t.name, "always") } }
                .setNegativeButton(strings.get(R.string.plugins_cancel), null),
        )
    }

    companion object {
        const val EXTRA_ID = "plugin_id"
    }
}
