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
import org.agentos.app.i18n.AndroidStrings
import org.agentos.app.i18n.Strings
import org.agentos.app.ui.Ui

/**
 * 自动选择会话（Jev）的 key 和 endpoint（IAgentControl v4）。和 [ModelSourceActivity] 一样：key 用密码框直接进 setJevSource，
 * 保存后清空；FLAG_SECURE 挡住截屏和最近任务缩略图；界面上只显示首尾各 4 位。endpoint 留空 = 默认。
 */
class JevSourceActivity : Activity() {
    private val control by lazy { AgentControlClient(this) }
    private val strings: Strings by lazy { AndroidStrings(this) }
    private var scope: CoroutineScope? = null
    private var current: Jev.Source? = null

    private lateinit var endpoint: EditText
    private lateinit var key: EditText
    private lateinit var keyHint: TextView
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val (root, column) = Ui.page(this, getString(R.string.jev_title))
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
        column.addView(Ui.paragraph(this, Jev.explain(strings)))
        column.addView(Ui.card(this).apply {
            addView(label(getString(R.string.jev_label_key)))
            key = field(getString(R.string.model_hint_key), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            addView(key)
            keyHint = Ui.paragraph(context, "")
            addView(keyHint)
            addView(label(getString(R.string.jev_label_endpoint)))
            endpoint = field(getString(R.string.jev_hint_endpoint), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            addView(endpoint)
            addView(Ui.paragraph(context, getString(R.string.jev_endpoint_note, org.agentos.app.agent.JevSources.DEFAULT_ENDPOINT)))
        }, Ui.matchWrap().apply { topMargin = dp(12) })
        message = Ui.text(this, 14f, R.color.ui_error).apply { setPadding(dp(4), dp(8), dp(4), 0) }
        column.addView(message)
        column.addView(Ui.buttons(this, getString(R.string.model_save) to { save() }))
        column.addView(Ui.paragraph(this, getString(R.string.jev_save_note)))
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
            keyHint.text = if (src.keySet) getString(R.string.model_key_keep, src.keyMasked.orEmpty()) else ""
            message.text = src.problems.joinToString("\n") { Jev.problemText(it, strings) }
        } catch (e: Exception) {
            message.text = getString(R.string.jev_load_failed, Jev.errorText(e.message, strings))
        }
    }

    private fun save() {
        val keyText = key.text.toString().trim()
        val ep = endpoint.text.toString().trim()
        Jev.validate(ep, keyText.isNotEmpty(), current, strings)?.let { bad ->
            message.text = bad.message
            return
        }
        message.text = ""
        scope?.launch {
            try {
                val after = Jev.parse(control.use { it.setJevSource(ep.ifEmpty { null }, keyText.ifEmpty { null }) })
                key.text.clear()
                current = after
                Toast.makeText(this@JevSourceActivity, if (after.usable) R.string.jev_saved else R.string.model_saved_unusable, Toast.LENGTH_SHORT).show()
                if (after.usable) finish() else message.text = after.problems.joinToString("\n") { Jev.problemText(it, strings) }
            } catch (e: Exception) {
                message.text = Jev.errorText(e.message, strings)
            }
        }
    }
}
