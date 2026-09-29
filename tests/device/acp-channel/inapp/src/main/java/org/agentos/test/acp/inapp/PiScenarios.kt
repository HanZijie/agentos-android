package org.agentos.test.acp.inapp

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.agentos.channel.ChannelConfig
import org.agentos.test.acp.AcpConn
import org.agentos.test.acp.PromptRun
import org.agentos.test.acp.TestIds
import org.agentos.test.acp.runPrompt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Pi Agent core 的端到端用例（C4）：:agent 里是真正的 PiAdapter + QuickJsEngine，模型请求经 HostFetch 发到电脑上的
 * 假模型端点（fake_model.py，经 adb reverse），或者 MiniMax 国内平台（live-minimax）。假端点记录每个请求
 * （只记 key 的类别，不记 key），执行器经 `GET /_log` 读回来核对。
 */
class PiScenarios(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val status: (String) -> Unit,
) {
    private val control = ControlClient(ctx)

    suspend fun run(name: String, args: JSONObject): JSONObject? = when (name) {
        "pi-tool-round" -> toolRound()
        "pi-context" -> context()
        "recovery-context" -> recoveryContext()
        "live-minimax" -> live(args)
        "drop-keys" -> dropKeys()
        else -> null
    }

    /** 删除 KeyDropProvider 投递、还没被用例读走的 key 文件（run.py 在 app 用例全部结束后调用）。 */
    private fun dropKeys(): JSONObject {
        val dropped = KeyDropProvider.NAMES.count { File(ctx.filesDir, "test/$it").let { f -> f.isFile && f.delete() } }
        val left = KeyDropProvider.NAMES.count { File(ctx.filesDir, "test/$it").exists() }
        return JSONObject().put("ok", left == 0).put("summary", "dropped=$dropped left=$left")
    }

    private fun nonce() = UUID.randomUUID().toString().take(8)

    /** 假端点记录的请求。 */
    private suspend fun fakeLog(): List<JSONObject> = withContext(Dispatchers.IO) {
        val conn = URL(FakeModel.BASE_URL + "/_log").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val arr = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("requests")
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONObject.msgs(): List<JSONObject> = optJSONArray("messages")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()

    private fun conn(label: String) = AcpConn(ctx, TestIds.APP_ACP, ChannelConfig.DEFAULT, scope, label)

    private fun allTrue(checks: JSONObject) = checks.keys().asSequence().all { checks.optBoolean(it) }

    private fun count(checks: JSONObject) = "${checks.keys().asSequence().count { checks.optBoolean(it) }}/${checks.length()}"

    // ------------------------------------------------------------------ 工具轮次

    /**
     * 模型在第一轮末尾要调用工具 fs_read（M1 的工具目录为空，工具端口“未开放”）。按实际行为核对：
     * 第一轮的文字送达；Pi 把工具结果（错误）交回模型，发出第二个请求（带 tool_result）；第二轮的文字送达，本轮 end_turn。
     */
    private suspend fun toolRound(): JSONObject {
        val n = nonce()
        val prompt = """{"chunks":5,"intervalMs":0,"tool":"fs_read","n":"$n"}"""
        val c = conn("pi-tool")
        val run = PromptRun()
        try {
            c.connect()
            runPrompt(c.newSession(), prompt, run, status)
            c.closeAndWait()
        } finally {
            c.dispose()
        }
        val log = fakeLog().filter { it.optString("userText").contains(n) }
        val second = log.firstOrNull { it.optInt("round") == 1 }
        val toolResult = second?.msgs()?.lastOrNull { m -> m.optJSONArray("blocks")?.toString()?.contains("tool_result") == true }
        val text = run.text.toString()
        val checks = JSONObject()
            .put("endTurn", run.stopReason == "END_TURN")
            .put("firstRoundText", text.startsWith("chunk 0 chunk 1 chunk 2 chunk 3 chunk 4 "))
            .put("secondRoundText", text.contains("after-tool:"))
            .put("twoModelRequests", log.size == 2 && log[0].optInt("round") == 0 && second != null)
            .put("toolResultSentBack", toolResult != null)
            .put("keyInjectedByHost", log.isNotEmpty() && log.all { it.optString("key") == "test" })
        return JSONObject().put("ok", allTrue(checks))
            .put("summary", "requests=${log.size} toolCalls=${run.toolCalls} statuses=${run.toolStatuses} text=${text.takeLast(60).replace('\n', ' ')} checks=${count(checks)}")
            .put("checks", checks).put("prompt", run.json()).put("text", text.take(400))
            .put("fakeRequests", JSONArray(log))
    }

    // ------------------------------------------------------------------ 多轮上下文

    /** 同一会话两轮：第二个请求带着第一轮的 user 和 assistant 消息，assistant 的文字与客户端收到的一致。 */
    private suspend fun context(): JSONObject {
        val n = nonce()
        val t1 = "ctx-$n first"
        val t2 = "ctx-$n second"
        val c = conn("pi-context")
        val r1 = PromptRun()
        val r2 = PromptRun()
        try {
            c.connect()
            val s = c.newSession()
            runPrompt(s, t1, r1, status)
            runPrompt(s, t2, r2, status)
            c.closeAndWait()
        } finally {
            c.dispose()
        }
        val rec = fakeLog().lastOrNull { it.optString("userText") == t2 }
        val m = rec?.msgs().orEmpty()
        val checks = JSONObject()
            .put("bothEndTurn", r1.stopReason == "END_TURN" && r2.stopReason == "END_TURN")
            .put("threeMessages", m.size == 3)
            .put("firstUser", m.getOrNull(0)?.let { it.optString("role") == "user" && it.optString("text") == t1 } == true)
            .put("firstAssistantMatchesClient", m.getOrNull(1)?.let {
                it.optString("role") == "assistant" && r1.text.isNotEmpty() && it.optString("text") == r1.text.toString().take(300)
            } == true)
            .put("secondUser", m.getOrNull(2)?.optString("text") == t2)
        return JSONObject().put("ok", allTrue(checks))
            .put("summary", "messages=${m.size} checks=${count(checks)}")
            .put("checks", checks).put("request", rec ?: JSONObject.NULL)
            .put("turn1", r1.json()).put("turn2", r2.json())
    }

    // ------------------------------------------------------------------ 恢复后的上下文

    /**
     * 恢复后上下文一致（F8）：会话 A 先完成一轮；另两个会话占满本调用方的并发（每个调用方最多同时 2 个），A 的第二轮
     * 因此排队；此时 SIGKILL :agent。冷启动后恢复流程把排队的任务交还调度器，Pi 用 Store 里保存的 messages 重建会话 A，
     * 发给模型的请求里要带着崩溃前的那一轮（与客户端当时收到的文字一致）。另两个运行中的任务标为结果未知。
     * （M1 没有 session/load，客户端接不回会话 A，所以在模型端核对。）
     */
    private suspend fun recoveryContext(): JSONObject {
        val n = nonce()
        val t1 = "rec-$n first"
        val t2 = "rec-$n after restart"
        val c = conn("recovery-ctx")
        val r1 = PromptRun()
        var queued = false
        try {
            c.connect()
            val a = c.newSession()
            runPrompt(a, t1, r1, status)
            repeat(2) {
                val s = c.newSession()
                scope.launch { runPrompt(s, """{"chunks":1000000,"intervalMs":50,"n":"busy-$n"}""", PromptRun()) }
            }
            waitRunState { it.optInt("activeTasks") >= 2 }
            scope.launch { runPrompt(a, t2, PromptRun()) }
            queued = waitRunState { it.optInt("activeTasks") >= 2 && it.optInt("queuedTasks") >= 1 }
            status("queued=$queued; SIGKILL :agent")
            check(killAgentAndWait(ctx)) { "could not kill :agent" }
        } finally {
            c.dispose()
        }
        // 冷启动（bind 拉起），等排队的那一轮在模型端出现并完成
        var diag = JSONObject()
        var rec: JSONObject? = null
        val deadline = SystemClock.elapsedRealtime() + 40_000
        while (SystemClock.elapsedRealtime() < deadline) {
            diag = control.use { it.diagnostics() }
            rec = fakeLog().lastOrNull { it.optString("userText") == t2 && it.optBoolean("completed") }
            if (rec != null) break
            delay(500)
        }
        val m = rec?.msgs().orEmpty()
        val rs = diag.getJSONObject("runtime").getJSONObject("runState")
        val recovered = diag.optJSONObject("store")?.optJSONObject("system")?.optJSONObject("lastRecovered") ?: JSONObject()
        val checks = JSONObject()
            .put("queuedBeforeKill", queued)
            .put("firstTurnDone", r1.stopReason == "END_TURN" && r1.text.isNotEmpty())
            .put("resumedAfterRestart", rec != null && recovered.optInt("requeued") >= 1 && !recovered.optBoolean("userStopped"))
            .put("contextRestored", m.size == 3 &&
                m[0].optString("role") == "user" && m[0].optString("text") == t1 &&
                m[1].optString("role") == "assistant" && m[1].optString("text") == r1.text.toString().take(300) &&
                m[2].optString("text") == t2)
            .put("keyInjectedByHost", rec?.optString("key") == "test")
            .put("interruptedMarkedUnknown", recovered.optInt("interrupted") >= 2 && rs.optInt("recoveryPending") >= 2)
        return JSONObject().put("ok", allTrue(checks))
            .put("summary", "resumed=${rec != null} messages=${m.size} recovered=$recovered checks=${count(checks)}")
            .put("checks", checks).put("request", rec ?: JSONObject.NULL).put("turn1", r1.json()).put("runState", rs)
    }

    private suspend fun waitRunState(timeoutMs: Long = 15_000, pred: (JSONObject) -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val rs = control.use { it.diagnostics() }.getJSONObject("runtime").getJSONObject("runState")
            if (pred(rs)) return true
            delay(100)
        }
        return false
    }

    // ------------------------------------------------------------------ 真实对话

    /**
     * 用 MiniMax 国内平台跑一次真实对话。key 由 run.py 经 stdin 投递到 files/test/live_key（KeyDropProvider），
     * 读完立即删除；对话后清除模型来源，key 不留在设备上。没有投递 key 时跳过。
     */
    private suspend fun live(args: JSONObject): JSONObject {
        val f = File(ctx.filesDir, args.optString("keyFile", "test/live_key"))
        val key = try {
            f.takeIf { it.isFile }?.readText()?.trim()
        } finally {
            f.delete()
        }
        if (key.isNullOrEmpty()) return JSONObject().put("ok", true).put("skipped", true).put("summary", "skipped: no live key")
        val provider = args.optString("provider", "minimax-cn")
        val model = control.use { p ->
            val models = p.presets(provider).getJSONObject("provider").getJSONArray("models")
            val wanted = args.optString("model")
            val ids = (0 until models.length()).map { models.getJSONObject(it).getString("id") }
            val id = ids.firstOrNull { it == wanted } ?: ids.first()
            p.setModelSource(JSONObject().put("kind", "preset").put("provider", provider).put("model", id).toString(), key)
            id
        }
        val run = PromptRun()
        val c = conn("live")
        try {
            c.connect()
            val s = c.newSession()
            withTimeoutOrNull(150_000) {
                runPrompt(s, args.optString("prompt", "请只回复两个字：你好。不要解释。"), run, status)
            } ?: run.apply { error = IllegalStateException("live prompt timed out") }
            c.closeAndWait()
        } finally {
            c.dispose()
            // 不把真实 key 留在设备上
            runCatching { control.use { it.clearModelSource() } }
        }
        val after = control.use { it.modelSource() }
        val text = run.text.toString()
        val checks = JSONObject()
            .put("endTurn", run.stopReason == "END_TURN")
            .put("gotText", text.isNotBlank())
            .put("keyRemovedAfter", !after.optBoolean("configured"))
        return JSONObject().put("ok", allTrue(checks))
            .put("summary", "model=$provider/$model stop=${run.stopReason} chars=${text.length} firstChunkMs=${"%.0f".format(run.json().optDouble("firstChunkMs"))} " +
                "totalMs=${"%.0f".format(run.json().optDouble("totalMs"))} text=${text.take(40).replace('\n', ' ')}")
            .put("checks", checks).put("model", "$provider/$model").put("text", text.take(300)).put("prompt", run.json())
    }
}
