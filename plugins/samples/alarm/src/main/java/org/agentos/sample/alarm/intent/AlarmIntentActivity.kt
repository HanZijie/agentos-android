package org.agentos.sample.alarm.intent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import org.agentos.sample.alarm.AlarmGraph
import org.agentos.sample.alarm.R
import org.agentos.sample.alarm.ui.ClockStyle
import org.agentos.sample.alarm.ui.ContextTexts
import org.agentos.sample.alarm.ui.MainActivity

/**
 * 系统标准闹钟 Intent 的入口（[android.provider.AlarmClock]）：ACTION_SET_ALARM、ACTION_DISMISS_ALARM、ACTION_SNOOZE_ALARM。
 * （ACTION_SHOW_ALARMS 直接由 [MainActivity] 响应。）
 *
 * 清单里要求调用方持有 `com.android.alarm.permission.SET_ALARM`（官方对接收这些 Intent 的 Activity 的要求）。
 * 没有界面（Theme.NoDisplay）：同步解析、落库、给反馈就 finish()——NoDisplay 的 Activity 必须在 onResume 前结束。
 * 仓库操作是几毫秒的本地 SQLite 写 + 一次 AlarmManager 调用，直接在主线程做。
 * 反馈规则见 [IntentFeedback]：带 EXTRA_SKIP_UI 不开界面，用 Toast 说明结果；其他情况打开列表 / 新建页。
 */
class AlarmIntentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handle(intent)
        } catch (e: Exception) {
            // 任何意外都不能让调用方（语音助手等）看到崩溃；用户能看到原因
            Log.e(TAG, "failed to handle ${intent?.action}", e)
            val reason = getString(R.string.intent_reject_invalid_param)
            Toast.makeText(applicationContext, getString(R.string.intent_rejected, reason), Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun handle(intent: Intent?) {
        val extras = extrasOf(intent)
        val request = if (extras == null) {
            AlarmIntentRequest.Rejected(RejectReason.INVALID_PARAM)
        } else {
            AlarmIntentParser.parse(intent?.action, extras)
        }
        val graph = AlarmGraph.get(this)
        val outcome = AlarmIntentHandler(graph.repository, graph.ring).handle(request)
        Log.i(TAG, "${intent?.action} -> $outcome")
        val texts = ContextTexts(this)
        IntentFeedback.message(texts, ClockStyle.of(this), outcome)?.let {
            Toast.makeText(applicationContext, it, Toast.LENGTH_LONG).show()
        }
        when (IntentFeedback.uiAction(outcome)) {
            UiAction.NONE -> Unit
            UiAction.SHOW_LIST -> startActivity(mainIntent())
            UiAction.SHOW_NEW_EDITOR -> startActivity(mainIntent().putExtra(MainActivity.EXTRA_OPEN_NEW, true))
        }
    }

    private fun mainIntent() = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** extras 里可能带着别的 App 的自定义 Parcelable，解不开会抛异常——返回 null，按非法请求拒绝，不崩。 */
    @Suppress("DEPRECATION")
    private fun extrasOf(intent: Intent?): Map<String, Any?>? = try {
        val bundle = intent?.extras
        bundle?.keySet()?.associateWith { bundle.get(it) } ?: emptyMap()
    } catch (e: RuntimeException) {
        Log.w(TAG, "unreadable extras: ${e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "AlarmIntent"
    }
}
