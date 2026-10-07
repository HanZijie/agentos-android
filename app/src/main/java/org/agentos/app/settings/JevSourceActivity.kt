package org.agentos.app.settings

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.agentos.app.R
import org.agentos.app.ui.Ui

/**
 * 自动选择会话（Jev）的 key 和 endpoint（IAgentControl v4）。和 [ModelSourceActivity] 一样：key 用密码框直接进 setJevSource，
 * 保存后清空；FLAG_SECURE 挡住截屏和最近任务缩略图；界面上只显示首尾各 4 位。endpoint 留空 = 默认。
 */
class JevSourceActivity : Activity() {
    private val control by lazy { AgentControlClient(this) }
    private var scope: CoroutineScope? = null
    private var current: Jev.Source? = null

    private lateinit var endpoint: EditText
    private lateinit var key: EditText
    private lateinit var keyHint: TextView
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val (root, column) = Ui.page(this, "自动选择会话（Jev）")
        build(column)
        setContentView(root)
        scope = MainScope().also { it.launch { load() } }
    }

    override fun onDestroy() {
        scope?.cancel()
        key.text.clear()
        super.onDestroy()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun build(column: LinearLayout) {
        column.addView(Ui.paragraph(this, Jev.EXPLAIN))
        column.addView(Ui.card(this).apply {
            addView(label("Jev key"))
            key = field("粘贴 key", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            addView(key)
            keyHint = Ui.paragraph(context, "")
            addView(keyHint)
            addView(label("服务地址（可选）"))
            endpoint = field("留空使用默认地址", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            addView(endpoint)
            addView(Ui.paragraph(context,
                "默认 ${org.agentos.app.agent.JevSources.DEFAULT_ENDPOINT}。必须是 https://；本机地址（127.0.0.1、localhost、::1）可以用 http://。换了地址需要重新填写 key，key 不会被带到别的地址。"))
        }, Ui.matchWrap().apply { topMargin = dp(12) })
        message = Ui.text(this, 14f, R.color.ui_error).apply { setPadding(dp(4), dp(8), dp(4), 0) }
        column.addView(message)
        column.addView(Ui.buttons(this, "保存" to { save() }))
        column.addView(Ui.paragraph(this, "key 用 Android Keystore 加密保存。保存后从下一次自动选择起生效。"))
    }

    private fun label(text: String) = Ui.text(this, 12f, R.color.ui_text_secondary).apply {
        this.text = text
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        // see ModelSourceActivity.field: single-line first, then the input type, then the transformation
        setSingleLine(true)
        inputType = type
        if (type and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        setTextColor(getColor(R.color.ui_text))
        setHintTextColor(getColor(R.color.ui_text_secondary))
    }

    private suspend fun load() {
        try {
            val src = control.use { Jev.parse(it.getJevSource()) }
            current = src
            if (src.configured && src.customEndpoint) endpoint.setText(src.endpoint)
            keyHint.text = if (src.keySet) "留空则沿用已保存的 key（${src.keyMasked}）" else ""
            message.text = src.problems.joinToString("\n") { Jev.problemText(it) }
        } catch (e: Exception) {
            message.text = "读取失败：${Jev.errorText(e.message)}"
        }
    }

    private fun save() {
        val keyText = key.text.toString().trim()
        val ep = endpoint.text.toString().trim()
        Jev.validate(ep, keyText.isNotEmpty(), current)?.let { bad ->
            message.text = bad.message
            return
        }
        message.text = ""
        scope?.launch {
            try {
                val after = Jev.parse(control.use { it.setJevSource(ep.ifEmpty { null }, keyText.ifEmpty { null }) })
                key.text.clear()
                current = after
                Toast.makeText(this@JevSourceActivity, if (after.usable) "已保存，下一次自动选择起生效" else "已保存，但暂时不可用", Toast.LENGTH_SHORT).show()
                if (after.usable) finish() else message.text = after.problems.joinToString("\n") { Jev.problemText(it) }
            } catch (e: Exception) {
                message.text = Jev.errorText(e.message)
            }
        }
    }
}
