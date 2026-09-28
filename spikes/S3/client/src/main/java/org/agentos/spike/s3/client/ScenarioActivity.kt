package org.agentos.spike.s3.client

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * 场景执行器：
 * adb shell am start -n org.agentos.spike.s3.client/.ScenarioActivity \
 *     --es scenario stream --es args '{"chunks":5000}' --es run <id>
 * 结果以 `S3RESULT <id> <i>/<n> <json 分片>` 写到 logcat。
 */
class ScenarioActivity : Activity() {
    private val ui = MainScope()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var text: TextView
    private var running: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { textSize = 14f; setPadding(32, 64, 32, 32) }
        setContentView(text)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val scenario = intent?.getStringExtra("scenario") ?: run {
            text.text = "idle (pid ${Process.myPid()})"
            return
        }
        val runId = intent.getStringExtra("run") ?: UUID.randomUUID().toString().take(8)
        val args = runCatching { JSONObject(intent.getStringExtra("args") ?: "{}") }.getOrElse { JSONObject() }
        if (running?.isActive == true) {
            Results.emit(runId, JSONObject().put("scenario", scenario).put("ok", false).put("error", "busy"))
            return
        }
        text.text = "$scenario …"
        running = ui.launch {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val result = try {
                withContext(Dispatchers.Default) {
                    Scenarios(this@ScenarioActivity, work, runId) { s -> ui.launch { text.text = "$scenario: $s" } }
                        .run(scenario, args)
                }
            } catch (e: Throwable) {
                JSONObject().put("ok", false).put("error", JSONObject().put("class", e.javaClass.name).put("message", e.message ?: ""))
                    .put("stack", e.stackTraceToString().take(2000))
            }
            result.put("scenario", scenario).put("run", runId).put("args", args)
                .put("wallMs", (SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
                .put("device", JSONObject().put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
                    .put("release", Build.VERSION.RELEASE).put("fingerprint", Build.FINGERPRINT))
                .put("debuggable", (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            Results.emit(runId, result)
            text.text = "$scenario done: ok=${result.opt("ok")}"
        }
    }

    override fun onDestroy() {
        ui.cancel()
        work.cancel()
        super.onDestroy()
    }
}
