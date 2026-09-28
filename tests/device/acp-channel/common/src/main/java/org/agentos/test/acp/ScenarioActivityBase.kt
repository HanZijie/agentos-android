package org.agentos.test.acp

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
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
import org.json.JSONObject
import java.util.UUID

/**
 * 场景执行器的基类。主机端脚本用
 * `am start -n <pkg>/<activity> --es scenario <name> --es args '<json>' --es run <id>` 驱动，
 * 结果以 `ACPTEST <id> <i>/<n> <json 分片>` 写到 logcat。
 */
abstract class ScenarioActivityBase : Activity() {
    private val ui = MainScope()
    /** 场景跑在进程级的作用域里：Activity 被重建（配置变化等）时不取消正在跑的场景。 */
    protected val work: CoroutineScope get() = processScope
    private lateinit var text: TextView

    /** 执行一个场景；不认识的场景返回 null。 */
    protected abstract suspend fun runScenario(name: String, args: JSONObject, runId: String, status: (String) -> Unit): JSONObject?

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { textSize = 14f; setPadding(32, 160, 32, 32) }
        setContentView(text)
        // 重建（配置变化）时不重复执行同一个 intent
        if (savedInstanceState == null) handle(intent)
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
        running = processScope.launch {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val result = try {
                runScenario(scenario, args, runId) { s -> ui.launch { text.text = "$scenario: $s" } }
                    ?: JSONObject().put("ok", false).put("error", "unknown scenario: $scenario")
            } catch (e: Throwable) {
                JSONObject().put("ok", false).put("error", errJson(e)).put("stack", e.stackTraceToString().take(2000))
            }
            result.put("scenario", scenario).put("run", runId).put("args", redacted(args))
                .put("wallMs", (SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
                .put("runner", JSONObject().put("package", packageName).put("pid", Process.myPid()).put("uid", Process.myUid())
                    .put("debuggable", (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0))
                .put("device", JSONObject().put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
                    .put("release", Build.VERSION.RELEASE).put("fingerprint", Build.FINGERPRINT))
            Results.emit(runId, result)
            ui.launch { text.text = "$scenario done: ok=${result.opt("ok")}" }
        }
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    private companion object {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile var running: Job? = null

        /** 场景参数里的密钥（BYOK 用例的测试 key）不回显：结果会写进 logcat，而用例要检查 logcat 里没有 key。 */
        val SECRET_ARGS = setOf("apiKey")

        fun redacted(args: JSONObject): JSONObject =
            JSONObject(args.toString()).apply { SECRET_ARGS.forEach { if (has(it)) put(it, "<redacted>") } }
    }
}
