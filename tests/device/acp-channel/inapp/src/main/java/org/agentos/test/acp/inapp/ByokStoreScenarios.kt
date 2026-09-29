package org.agentos.test.acp.inapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.agentos.channel.ChannelConfig
import org.agentos.test.acp.AcpConn
import org.agentos.test.acp.PromptRun
import org.agentos.test.acp.Results
import org.agentos.test.acp.TestIds
import org.agentos.test.acp.expectedChars
import org.agentos.test.acp.runPrompt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 杀掉 :agent 并等它消失（同 UID 的 Process.killProcess = SIGKILL）。 */
internal suspend fun killAgentAndWait(ctx: Context): Boolean {
    val pid = AppTarget.agentPid(ctx) ?: return true
    Process.killProcess(pid)
    val deadline = SystemClock.elapsedRealtime() + 10_000
    while (AppTarget.agentPid(ctx) == pid && SystemClock.elapsedRealtime() < deadline) delay(50)
    return AppTarget.agentPid(ctx) != pid
}

/**
 * C3 的设备用例：BYOK（IAgentControl v2、KeystoreSecrets）、Store（AndroidStore + RuntimeEngine）、
 * 用户主动停止后的恢复（HostPort.environment.previousExitStoppedByUser）。
 *
 * BYOK 用例的测试 key 由主机端 run.py 生成（随机，不是任何真实 key），经 stdin 写进 App 私有目录的文件传入
 * （KeyDropProvider，见 [testKey]）。
 * 本执行器检查返回值、诊断、心跳、监督状态和 App 私有目录里的所有文件都没有 key；run.py 再检查整个 logcat（-b all）。
 */
class ByokStoreScenarios(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val runId: String,
    private val status: (String) -> Unit,
) {
    private val control = ControlClient(ctx)
    private val de = ctx.createDeviceProtectedStorageContext()

    suspend fun run(name: String, args: JSONObject): JSONObject? = when (name) {
        "byok-roundtrip" -> roundtrip(testKey(args))
        "byok-restart" -> restart(testKey(args))
        "byok-clear" -> clear(runCatching { testKey(args) }.getOrNull())
        "byok-clear-inflight" -> clearInflight(testKey(args))
        "store-restart" -> storeRestart(args.optBoolean("fresh", true))
        "user-stop-arm" -> userStopArm()
        "user-stop-check" -> userStopCheck(args)
        else -> null
    }

    // ------------------------------------------------------------------ BYOK

    /**
     * run.py 经 stdin 写进 files/<apiKeyFile> 的测试 key（不经 adb shell 命令行：API 37 的 adbd 会把命令行写进 logcat）。
     * 读完立即删除，后面的文件扫描不会把它算成泄漏。
     */
    private fun testKey(args: JSONObject): String {
        val f = File(ctx.filesDir, args.optString("apiKeyFile", "test/byok_key"))
        try {
            val key = f.readText().trim()
            check(key.length >= 16) { "test key file is empty or too short" }
            return key
        } finally {
            f.delete()
        }
    }

    private fun preset(provider: String, model: String, thinking: String? = null) = JSONObject()
        .put("kind", "preset").put("provider", provider).put("model", model)
        .apply { thinking?.let { put("thinkingLevel", it) } }.toString()

    private fun custom(api: String, baseUrl: String, model: String) = JSONObject()
        .put("kind", "custom").put("api", api).put("baseUrl", baseUrl).put("model", model).toString()

    private fun masked(key: String) = if (key.length <= 8) "****" else key.take(4) + "\u2026" + key.takeLast(4)

    /** 期望抛 `agentos.byok.<code>`；记录实际的异常，并检查消息里没有 key。 */
    private fun expectByok(key: String, code: String, block: () -> Unit): JSONObject {
        val out = JSONObject().put("expected", code)
        try {
            block()
            out.put("ok", false).put("error", "no exception")
        } catch (e: Throwable) {
            val msg = e.message.orEmpty()
            val leaked = msg.contains(key) || msg.contains(key.substring(4, key.length - 4))
            out.put("ok", msg.startsWith("agentos.byok.$code:") && !leaked && e is IllegalArgumentException)
                .put("class", e.javaClass.simpleName).put("message", if (leaked) "<contains key>" else msg).put("leaked", leaked)
        }
        return out
    }

    private fun providerModels(p: ControlClient.Proxy, id: String): List<String> {
        val models = p.presets(id).getJSONObject("provider").getJSONArray("models")
        return (0 until models.length()).map { models.getJSONObject(it).getString("id") }
    }

    /** 设置 → 读取（掩码）→ 同厂商换模型沿用 key → 各种拒绝 → 自定义端点 → 诊断里没有 key；全程不重启 :agent（热加载）。 */
    private suspend fun roundtrip(key: String): JSONObject = control.use { p ->
        val checks = JSONObject()
        val errors = JSONObject()
        val pid0 = AppTarget.agentPid(ctx)
        checks.put("version2", p.version() >= 2)
        p.clearModelSource()
        checks.put("emptyAfterClear", !p.modelSource().getBoolean("configured"))

        val all = p.presets(null)
        val providers = all.getJSONArray("providers")
        val ids = (0 until providers.length()).map { providers.getJSONObject(it).getString("id") }
        checks.put("minimaxFirst", ids.take(2) == listOf("minimax", "minimax-cn"))
        val cnModels = providerModels(p, "minimax-cn")
        val m1 = cnModels.first()
        val m2 = cnModels.getOrElse(1) { m1 }

        val set1 = p.setModelSource(preset("minimax-cn", m1), key)
        checks.put("setReturnsMasked", set1.getJSONObject("key").optString("masked") == masked(key) && set1.getBoolean("usable"))
        val get1 = p.modelSource()
        checks.put("getReturnsMasked", get1.getJSONObject("key").optString("masked") == masked(key) &&
            get1.optString("provider") == "minimax-cn" && get1.optString("model") == m1)
        val d1 = p.diagnostics().getJSONObject("byok")
        checks.put("hotReloadSet", d1.optString("model") == m1 && d1.optBoolean("usable") && d1.optBoolean("credentialResolves"))

        val set2 = p.setModelSource(preset("minimax-cn", m2, "low"), null)
        checks.put("keepKeySameProvider", set2.getJSONObject("key").optString("masked") == masked(key) &&
            set2.optString("model") == m2 && set2.optString("thinkingLevel") == "low")
        checks.put("hotReloadModelChange", p.diagnostics().getJSONObject("byok").optString("model") == m2)

        val intl = providerModels(p, "minimax").first()
        errors.put("otherProviderNeedsKey", expectByok(key, "key_required") { p.setModelSource(preset("minimax", intl), null) })
        errors.put("customNeedsKey", expectByok(key, "key_required") {
            p.setModelSource(custom("anthropic-messages", "https://gw.example.com/anthropic", "m"), null)
        })
        errors.put("cleartextRejected", expectByok(key, "invalid_endpoint") {
            p.setModelSource(custom("anthropic-messages", "http://gw.example.com/anthropic", "m"), key)
        })
        errors.put("keyWithNewline", expectByok(key, "invalid_key") { p.setModelSource(preset("minimax-cn", m1), "$key\n$key") })
        errors.put("bearerPrefix", expectByok(key, "invalid_key") { p.setModelSource(preset("minimax-cn", m1), "Bearer $key") })
        errors.put("unknownProvider", expectByok(key, "unknown_provider") { p.setModelSource(preset("no-such-vendor", m1), key) })
        errors.put("keyInWrongField", expectByok(key, "unknown_model") { p.setModelSource(preset("minimax-cn", key), key) })
        errors.put("badJson", expectByok(key, "invalid_source") { p.setModelSource("{\"kind\":", key) })
        checks.put("unchangedAfterErrors", p.modelSource().optString("model") == m2)

        val cust = p.setModelSource(custom("openai-completions", "https://gw.example.com/acct-7/v1", "qwen3"), key)
        checks.put("customEndpoint", cust.optString("kind") == "custom" && cust.optString("baseUrl") == "https://gw.example.com/acct-7/v1" &&
            cust.getJSONObject("key").optString("masked") == masked(key))
        val dc = p.diagnostics().getJSONObject("byok")
        checks.put("diagCustomOriginOnly", dc.optString("endpoint") == "https://gw.example.com" && !dc.toString().contains("acct-7"))

        // 回到预设，留给 byok-restart
        p.setModelSource(preset("minimax-cn", m1), key)
        val diag = p.diagnostics()
        val byok = diag.getJSONObject("byok")
        checks.put("diagByok", byok.optBoolean("configured") && byok.optBoolean("usable") && byok.optBoolean("keySet") &&
            byok.optBoolean("credentialResolves"))
        val sources = listOf(p.modelSource().toString(), diag.toString(), p.supervisorStatus().toString(), p.runtimeStatus().toString())
        val leaks = scanText(key, sources + listOf(heartbeatText()))
        checks.put("noKeyInReturnsDiagHeartbeat", leaks == 0)
        // 诊断连掩码都不给
        checks.put("diagHasNoMask", !diag.toString().contains(masked(key)))
        checks.put("samePid", pid0 == null || AppTarget.agentPid(ctx) == pid0)

        val errorsOk = errors.keys().asSequence().all { errors.getJSONObject(it).optBoolean("ok") }
        val ok = checks.keys().asSequence().all { checks.optBoolean(it) } && errorsOk
        JSONObject().put("ok", ok)
            .put("summary", "checks=${checks.keys().asSequence().count { checks.optBoolean(it) }}/${checks.length()} errors=${errors.keys().asSequence().count { errors.getJSONObject(it).optBoolean("ok") }}/${errors.length()}")
            .put("checks", checks).put("errors", errors)
            .put("masked", get1.getJSONObject("key").optString("masked"))
            .put("keyLength", key.length)
            .put("presetProviders", ids.size)
            .put("byok", byok)
    }

    /** 设置 key → 杀 :agent → 冷启动后 key 仍可用（Keystore 解密、端点匹配）；私有目录里没有明文 key。 */
    private suspend fun restart(key: String): JSONObject {
        val m1 = control.use { p ->
            val m = providerModels(p, "minimax-cn").first()
            p.setModelSource(preset("minimax-cn", m), key)
            m
        }
        val pidBefore = AppTarget.agentPid(ctx)
        check(killAgentAndWait(ctx)) { "could not kill :agent" }
        delay(300)
        val (src, diag) = control.use { p -> p.modelSource() to p.diagnostics() }
        val pidAfter = AppTarget.agentPid(ctx)
        val byok = diag.getJSONObject("byok")
        val keystore = byok.optJSONObject("keystore") ?: JSONObject()
        val file = File(ctx.filesDir, "byok/model-source.json")
        val fileText = runCatching { file.readText() }.getOrDefault("")
        val scan = scanFiles(key)
        val checks = JSONObject()
            .put("newProcess", pidBefore != null && pidAfter != null && pidBefore != pidAfter)
            .put("usableAfterRestart", src.optBoolean("usable") && src.optString("model") == m1)
            .put("maskedAfterRestart", src.getJSONObject("key").optString("masked") == masked(key))
            .put("credentialResolves", byok.optBoolean("credentialResolves"))
            .put("noProblems", (src.optJSONArray("problems")?.length() ?: 0) == 0)
            .put("keystoreKeyPresent", keystore.optBoolean("present"))
            .put("fileHasCiphertextOnly", file.isFile && fileText.contains("\"sealed\"") && !fileText.contains(key))
            .put("fileInCe", file.absolutePath.startsWith(ctx.dataDir.absolutePath + "/") && !file.absolutePath.contains("user_de"))
            .put("noPlaintextInAppFiles", scan.getInt("hits") == 0)
            .put("noKeyInDiagnostics", scanText(key, listOf(diag.toString(), heartbeatText())) == 0)
        val ok = checks.keys().asSequence().all { checks.optBoolean(it) }
        return JSONObject().put("ok", ok)
            .put("summary", "pid $pidBefore→$pidAfter usable=${src.optBoolean("usable")} keystore=${keystore.optString("securityLevel")} files=${scan.getInt("files")} hits=${scan.getInt("hits")}")
            .put("checks", checks)
            .put("keystore", keystore)
            .put("fileScan", scan)
            .put("byok", byok)
            .put("recoveryMs", diag.getJSONObject("runtime").optLong("recoveryMs"))
    }

    /** 清除 → 重启后仍是未配置，Keystore 主密钥已删除，文件已删除，私有目录里没有 key。 */
    private suspend fun clear(key: String?): JSONObject {
        val before = control.use { p ->
            p.clearModelSource()
            p.modelSource() to p.diagnostics().getJSONObject("byok")
        }
        check(killAgentAndWait(ctx)) { "could not kill :agent" }
        delay(300)
        val (src, byok) = control.use { p -> p.modelSource() to p.diagnostics().getJSONObject("byok") }
        val scan = key?.let { scanFiles(it) }
        val checks = JSONObject()
            .put("clearedNow", !before.first.getBoolean("configured") && !before.second.optBoolean("keySet"))
            .put("clearedAfterRestart", !src.getBoolean("configured") && !src.getBoolean("usable") && !byok.optBoolean("keySet"))
            .put("noCredential", !byok.optBoolean("credentialResolves"))
            .put("keystoreKeyDeleted", byok.optJSONObject("keystore")?.optBoolean("present") == false)
            .put("fileDeleted", !File(ctx.filesDir, "byok/model-source.json").exists())
            .put("noPlaintextInAppFiles", scan == null || scan.getInt("hits") == 0)
        val ok = checks.keys().asSequence().all { checks.optBoolean(it) }
        return JSONObject().put("ok", ok)
            .put("summary", "configured=${src.getBoolean("configured")} keystore=${byok.optJSONObject("keystore")} hits=${scan?.getInt("hits")}")
            .put("checks", checks).put("byok", byok).put("fileScan", scan ?: JSONObject.NULL)
    }

    /**
     * 清除 = 立即作废（C3.1，C4 起走真正的 Pi）：模型来源是电脑上的假端点，key 是 BYOK 测试 key。模型第一轮流式输出后
     * 要调用工具（fs_read，未开放），Pi 随后要发第二个请求。第一轮流式进行中清除模型来源：
     * - 已经在传输中的第一轮照常结束（中止它是第二层，要网络出口配合，见 C3.1 报告）；
     * - 第二个请求拿不到 key，HostFetch 不发（NO_CREDENTIAL），本轮以 model_not_configured 结束、错误消息里没有 key；
     * - 假端点只见到一个带 key 的请求；KeystoreSecrets 清除后 served 不再增加、denied 增加；同一会话下一轮同样失败。
     */
    private suspend fun clearInflight(key: String): JSONObject {
        control.use { p -> p.setModelSource(FakeModel.source(), key) }
        val n = java.util.UUID.randomUUID().toString().take(8)
        val c = AcpConn(ctx, TestIds.APP_ACP, ChannelConfig.DEFAULT, scope, "clear-inflight")
        try {
            c.connect()
            val session = c.newSession()
            val run = PromptRun()
            var tClearNs = 0L
            var charsAtClear = 0L
            val atClear = kotlinx.coroutines.CompletableDeferred<JSONObject>()
            runPrompt(session, """{"chunks":60,"intervalMs":20,"tool":"fs_read","n":"$n"}""", run, status) { r ->
                if (tClearNs == 0L && r.chunkChars >= 200) {
                    tClearNs = SystemClock.elapsedRealtimeNanos()
                    charsAtClear = r.chunkChars
                    scope.launch {
                        runCatching { control.use { p -> p.clearModelSource(); p.diagnostics().getJSONObject("byok") } }
                            .onSuccess { atClear.complete(it) }.onFailure { atClear.completeExceptionally(it) }
                    }
                }
            }
            val byokAtClear = kotlinx.coroutines.withTimeout(10_000) { atClear.await() }
            val clearToEndMs = if (tClearNs > 0) (run.endNs - tClearNs) / 1e6 else -1.0
            val follow = PromptRun()
            runPrompt(session, """{"chunks":3,"intervalMs":0}""", follow)
            val byokAfter = control.use { it.diagnostics().getJSONObject("byok") }
            c.closeAndWait()
            val requests = fakeRequests().filter { it.optString("userText").contains(n) }

            val middle = key.substring(4, key.length - 4)
            fun clean(m: String?) = m != null && !m.contains(key) && !m.contains(middle)
            val msg = run.error?.message
            val followMsg = follow.error?.message
            val reqAt = byokAtClear.optJSONObject("requests") ?: JSONObject()
            val reqAfter = byokAfter.optJSONObject("requests") ?: JSONObject()
            val checks = JSONObject()
                .put("streamedBeforeClear", charsAtClear >= 200)
                .put("turnEndedWithError", run.stopReason == null && msg != null && msg.contains("model_not_configured"))
                .put("endedPromptly", clearToEndMs in 0.0..5_000.0)
                .put("errorHasNoKey", clean(msg) && clean(followMsg))
                .put("onlyOneKeyedRequest", requests.size == 1 && requests[0].optString("key") == "byok" && requests[0].optInt("round") == 0)
                .put("noKeyServedAfterClear", reqAfter.optLong("served") == reqAt.optLong("served") &&
                    reqAfter.optLong("denied") > reqAt.optLong("denied"))
                .put("revoked", reqAfter.optLong("revocations") >= 1 && !byokAfter.optBoolean("keySet") &&
                    !byokAfter.optBoolean("credentialResolves") && byokAfter.optJSONObject("keystore")?.optBoolean("present") == false)
                .put("nextTurnFails", follow.stopReason == null && followMsg != null && followMsg.contains("model_not_configured"))
            val ok = checks.keys().asSequence().all { checks.optBoolean(it) }
            return JSONObject().put("ok", ok)
                .put("summary", "clearToEndMs=${"%.0f".format(clearToEndMs)} charsAfterClear=${run.chunkChars - charsAtClear} " +
                    "inFlightCompleted=${requests.firstOrNull()?.optBoolean("completed")} requests=${requests.size} " +
                    "served ${reqAt.optLong("served")}→${reqAfter.optLong("served")} denied ${reqAt.optLong("denied")}→${reqAfter.optLong("denied")} " +
                    "checks=${checks.keys().asSequence().count { checks.optBoolean(it) }}/${checks.length()}")
                .put("checks", checks)
                .put("error", if (clean(msg)) msg else "<contains key>")
                .put("followUpError", if (clean(followMsg)) followMsg else "<contains key>")
                .put("clearToEndMs", clearToEndMs).put("charsAtClear", charsAtClear).put("charsAfterClear", run.chunkChars - charsAtClear)
                // 第二层（中止传输中的响应）还没做：记录传输中的那一次是否照常结束
                .put("inFlightResponseCompleted", requests.firstOrNull()?.optBoolean("completed") ?: JSONObject.NULL)
                .put("requestsAtClear", reqAt).put("requestsAfter", reqAfter).put("fakeRequests", JSONArray(requests))
        } finally {
            c.dispose()
        }
    }

    /** 电脑上假模型端点记录的请求（只有 key 的类别，没有 key）。 */
    private suspend fun fakeRequests(): List<JSONObject> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val conn = java.net.URL(FakeModel.BASE_URL + "/_log").openConnection() as java.net.HttpURLConnection
        try {
            val arr = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("requests")
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun heartbeatText(): String = runCatching { File(de.filesDir, "supervisor/heartbeat").readText() }.getOrDefault("")

    /** 在若干段文字里找 key 的全文或中段（中段不含掩码用的首尾 4 位）。 */
    private fun scanText(key: String, texts: List<String>): Int {
        val middle = key.substring(4, key.length - 4)
        return texts.count { it.contains(key) || it.contains(middle) }
    }

    /** 扫描 App 的 CE 和 DE 私有目录下所有普通文件（含数据库、WAL、SharedPreferences）找明文 key。 */
    private fun scanFiles(key: String): JSONObject {
        val needles = listOf(key.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_16LE))
        var files = 0
        var bytes = 0L
        val hits = JSONArray()
        for (root in listOf(ctx.dataDir, de.dataDir)) {
            root.walkTopDown().onEnter { it.canRead() }.filter { it.isFile && it.length() <= 16L * 1024 * 1024 }.forEach { f ->
                val data = runCatching { f.readBytes() }.getOrNull() ?: return@forEach
                files++
                bytes += data.size
                if (needles.any { indexOf(data, it) >= 0 }) hits.put(f.absolutePath)
            }
        }
        return JSONObject().put("roots", JSONArray(listOf(ctx.dataDir.absolutePath, de.dataDir.absolutePath)))
            .put("files", files).put("bytes", bytes).put("hits", hits.length()).put("hitPaths", hits)
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || data.size < needle.size) return -1
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // ------------------------------------------------------------------ Store

    /**
     * 直接读 :agent 的数据库文件（:agent 已被杀，没有并发写）。只读打开：读写打开时系统 SQLite 会把 journal_mode 改成它的
     * 默认值（TRUNCATE），改动被测对象。WAL 看文件头第 18、19 字节（都为 2）。表名和列名来自 core/runtime store/Schema.kt v1。
     */
    private fun readDb(sessionIds: List<String>): JSONObject {
        val path = ctx.getDatabasePath("agentos-runtime.db")
        val out = JSONObject().put("path", path.absolutePath).put("exists", path.isFile)
        if (!path.isFile) return out
        val header = path.inputStream().use { s -> ByteArray(20).also { s.read(it) } }
        out.put("walHeader", header[18].toInt() == 2 && header[19].toInt() == 2)
        val db = SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            fun long(sql: String, vararg a: String): Long = db.rawQuery(sql, a).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
            fun str(sql: String, vararg a: String): String? = db.rawQuery(sql, a).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            out.put("userVersion", long("PRAGMA user_version"))
            out.put("journalMode", str("PRAGMA journal_mode"))
            out.put("runtimeStarted", long("SELECT count(*) FROM events WHERE session_id = '_system' AND event_type = 'runtime.started'"))
            out.put("systemEvents", long("SELECT count(*) FROM events WHERE session_id = '_system'"))
            out.put("systemLastSequence", long("SELECT coalesce(max(sequence), 0) FROM events WHERE session_id = '_system'"))
            val sessions = JSONObject()
            for (id in sessionIds) {
                val tasks = JSONArray()
                val cursor = db.rawQuery("SELECT state FROM tasks WHERE session_id = ? ORDER BY position", arrayOf(id))
                try {
                    while (cursor.moveToNext()) tasks.put(cursor.getString(0))
                } finally {
                    cursor.close()
                }
                sessions.put(id, JSONObject()
                    .put("exists", long("SELECT count(*) FROM sessions WHERE id = ?", id) == 1L)
                    .put("owner", str("SELECT owner_key FROM sessions WHERE id = ?", id) ?: JSONObject.NULL)
                    .put("tasks", tasks)
                    .put("events", long("SELECT count(*) FROM events WHERE session_id = ?", id))
                    .put("lastSequence", long("SELECT coalesce(max(sequence), 0) FROM events WHERE session_id = ?", id))
                    .put("created", long("SELECT count(*) FROM events WHERE session_id = ? AND event_type = 'session.created'", id))
                    .put("completed", long("SELECT count(*) FROM events WHERE session_id = ? AND event_type = 'task.completed'", id))
                    .put("piMessages", long("SELECT count(*) FROM pi_messages WHERE session_id = ?", id)))
            }
            out.put("sessions", sessions)
        } finally {
            db.close()
        }
        return out
    }

    /** 一次冷启动：连接、新建会话、各跑一轮短 prompt，读诊断，关闭。 */
    private suspend fun coldRound(label: String, sessions: Int): JSONObject {
        val c = AcpConn(ctx, TestIds.APP_ACP, ChannelConfig.DEFAULT, scope, label)
        try {
            c.connect()
            val ids = JSONArray()
            val prompts = JSONArray()
            repeat(sessions) { i ->
                val s = c.newSession()
                ids.put(s.sessionId.value)
                // 只在第一个会话里跑一轮：另一个会话留作“建了但没有任务”
                if (i == 0) {
                    val run = PromptRun()
                    runPrompt(s, """{"chunks":5,"intervalMs":0}""", run)
                    prompts.put(run.json())
                }
            }
            val diag = control.use { it.diagnostics() }
            c.closeAndWait()
            return JSONObject().put("sessions", ids).put("prompts", prompts).put("pid", AppTarget.agentPid(ctx) ?: -1)
                .put("store", diag.getJSONObject("store")).put("heartbeatPath", diag.getJSONObject("heartbeat").optString("path"))
        } finally {
            c.dispose()
        }
    }

    /**
     * 建库 → 追加写 → SIGKILL → 重启后恢复，全部经真正的宿主层（ACP → RuntimeEngine → AndroidStore）：
     * 1. 杀掉 :agent；fresh 时删掉数据库（CE 的 databases/agentos-runtime.db*），下一次启动新建；
     * 2. ACP 冷启动 :agent，建两个会话、在第一个里跑一轮；诊断：库在 CE、本次新建、runtime.started 1 次；
     * 3. SIGKILL :agent，直接读数据库文件：两个会话、一个已完成的任务、它的事件和 Pi messages 都在；
     * 4. 再冷启动一次：新会话照常，runtime.started / runtime.recovered 各多 1 次，系统流 sequence 接着增长；
     * 5. 再 SIGKILL 读一次文件：三个会话都在。
     */
    private suspend fun storeRestart(fresh: Boolean): JSONObject {
        check(killAgentAndWait(ctx)) { "could not kill :agent" }
        val deleted = JSONArray()
        if (fresh) {
            val db = ctx.getDatabasePath("agentos-runtime.db")
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                val f = File(db.path + suffix)
                if (f.exists() && f.delete()) deleted.put(f.name)
            }
        }
        val r1 = coldRound("store-1", 2)
        status("round 1 done; SIGKILL")
        check(killAgentAndWait(ctx)) { "could not kill :agent" }
        val ids1 = (0 until r1.getJSONArray("sessions").length()).map { r1.getJSONArray("sessions").getString(it) }
        val db1 = readDb(ids1)
        val r2 = coldRound("store-2", 1)
        check(killAgentAndWait(ctx)) { "could not kill :agent" }
        val ids2 = ids1 + r2.getJSONArray("sessions").getString(0)
        val db2 = readDb(ids2)

        val s1 = r1.getJSONObject("store")
        val s2 = r2.getJSONObject("store")
        val sys1 = s1.optJSONObject("system") ?: JSONObject()
        val sys2 = s2.optJSONObject("system") ?: JSONObject()
        val first = db1.optJSONObject("sessions")?.optJSONObject(ids1[0]) ?: JSONObject()
        val second = db1.optJSONObject("sessions")?.optJSONObject(ids1[1]) ?: JSONObject()
        fun promptOk(r: JSONObject) = r.getJSONArray("prompts").getJSONObject(0).let { it.optString("stopReason") == "END_TURN" && it.optLong("chars") == expectedChars(5, 0) }
        val checks = JSONObject()
            .put("sessionIdsFromStore", ids2.size == 3 && ids2.all { it.startsWith("ses_") } && ids2.distinct().size == 3)
            .put("promptsThroughRuntime", promptOk(r1) && promptOk(r2))
            .put("dbInCe", s1.optString("storage") == "ce" && s1.optString("path").startsWith(ctx.dataDir.absolutePath + "/") &&
                !s1.optString("path").contains("user_de"))
            .put("heartbeatInDe", r1.optString("heartbeatPath").startsWith(de.dataDir.absolutePath + "/"))
            .put("created", !fresh || (!s1.optBoolean("existedAtStart", true) && sys1.optInt("runtimeStarted") == 1))
            .put("persistedAfterSigkill", db1.optLong("userVersion") == 1L && db1.optBoolean("walHeader") &&
                first.optBoolean("exists") && second.optBoolean("exists") &&
                first.optJSONArray("tasks")?.toString() == "[\"completed\"]" && first.optLong("completed") == 1L &&
                first.optLong("piMessages") == 1L && second.optLong("created") == 1L &&
                db1.optLong("runtimeStarted") == sys1.optInt("runtimeStarted").toLong())
            .put("newProcess", r1.optInt("pid") > 0 && r2.optInt("pid") > 0 && r1.optInt("pid") != r2.optInt("pid"))
            .put("appendedOnRestart", s2.optBoolean("existedAtStart") && sys2.optInt("runtimeStarted") == sys1.optInt("runtimeStarted") + 1 &&
                sys2.optInt("runtimeRecovered") == sys1.optInt("runtimeRecovered") + 1 &&
                sys2.optLong("lastSequence") > sys1.optLong("lastSequence") && sys2.optInt("events").toLong() == sys2.optLong("lastSequence"))
            .put("allSessionsAfterSecondKill", ids2.all { db2.optJSONObject("sessions")?.optJSONObject(it)?.optBoolean("exists") == true } &&
                db2.optLong("runtimeStarted") == db1.optLong("runtimeStarted") + 1 &&
                db2.optLong("systemEvents") == db2.optLong("systemLastSequence"))
        val ok = checks.keys().asSequence().all { checks.optBoolean(it) }
        return JSONObject().put("ok", ok)
            .put("summary", "fresh=$fresh deleted=${deleted.length()} started ${sys1.optInt("runtimeStarted")}→${sys2.optInt("runtimeStarted")} " +
                "sysSeq ${sys1.optLong("lastSequence")}→${sys2.optLong("lastSequence")} size=${s2.optLong("sizeBytes")} " +
                "checks=${checks.keys().asSequence().count { checks.optBoolean(it) }}/${checks.length()}")
            .put("checks", checks).put("deleted", deleted)
            .put("round1", r1).put("round2", r2).put("db1", db1).put("db2", db2)
    }

    // ------------------------------------------------------------------ 用户主动停止（previousExitStoppedByUser）

    /**
     * 第一步：两个会话在跑长任务（每个调用方最多同时 2 个），第三个会话的任务排队。状态到位后发 phase=armed，
     * 由 run.py 执行 `am force-stop org.agentos.app`（本执行器也随之被杀）。
     */
    private suspend fun userStopArm(): JSONObject {
        val c = AcpConn(ctx, TestIds.APP_ACP, ChannelConfig.DEFAULT, scope, "user-stop")
        c.connect()
        val ids = JSONArray()
        repeat(3) { i ->
            val s = c.newSession()
            ids.put(s.sessionId.value)
            scope.launch { runPrompt(s, """{"chunks":1000000,"intervalMs":50}""", PromptRun()) }
            if (i < 2) delay(300)
        }
        var diag = JSONObject()
        val deadline = SystemClock.elapsedRealtime() + 15_000
        control.use { p ->
            while (SystemClock.elapsedRealtime() < deadline) {
                diag = p.diagnostics()
                val rs = diag.getJSONObject("runtime").getJSONObject("runState")
                if (rs.optInt("activeTasks") == 2 && rs.optInt("queuedTasks") >= 1) break
                Thread.sleep(100)
            }
        }
        val rs = diag.getJSONObject("runtime").getJSONObject("runState")
        val armed = rs.optInt("activeTasks") == 2 && rs.optInt("queuedTasks") >= 1
        Results.emit(
            runId, JSONObject().put("scenario", "user-stop").put("phase", "armed").put("armed", armed)
                .put("sessions", ids).put("runState", rs).put("heartbeat", diag.getJSONObject("heartbeat").opt("fields"))
                .put("agentPid", AppTarget.agentPid(ctx) ?: -1),
        )
        // 等 run.py force-stop（不回来）
        delay(60_000)
        return JSONObject().put("ok", false).put("error", "the app was not force-stopped")
    }

    /**
     * 第二步（force-stop 之后的新进程）：冷启动 :agent，上一个 :agent 的退出原因是 USER_REQUESTED，
     * 宿主层拿到 previousExitStoppedByUser=true：排队的任务被取消（by user_stop），运行中的两个标记为结果未知，
     * runState 没有任务，服务不进前台，心跳 tasks=0。
     */
    private suspend fun userStopCheck(args: JSONObject): JSONObject {
        var diag = JSONObject()
        control.use { p ->
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline) {
                diag = p.diagnostics()
                if (diag.getJSONObject("runtime").optString("phase") == "READY") break
                Thread.sleep(100)
            }
            // 宽限期过后再看一次：确实没有进前台
            Thread.sleep(2_500)
            diag = p.diagnostics()
        }
        val rt = diag.getJSONObject("runtime")
        val rs = rt.getJSONObject("runState")
        val last = diag.optJSONObject("lastExit") ?: JSONObject()
        val rec = diag.getJSONObject("store").optJSONObject("system")?.optJSONObject("lastRecovered") ?: JSONObject()
        val hb = diag.getJSONObject("heartbeat").optJSONObject("fields") ?: JSONObject()
        val armedSessions = args.optJSONArray("sessions") ?: JSONArray()
        check(killAgentAndWait(ctx)) { "could not kill :agent" }
        val ids = (0 until armedSessions.length()).map { armedSessions.getString(it) }
        val db = readDb(ids)
        val states = ids.map { db.optJSONObject("sessions")?.optJSONObject(it)?.optJSONArray("tasks")?.optString(0) }
        val checks = JSONObject()
            .put("exitWasUserStop", last.optString("reasonName") in setOf("USER_REQUESTED", "USER_STOPPED"))
            .put("runtimeSawUserStop", rt.optBoolean("userStopped") && rec.optBoolean("userStopped"))
            .put("queuedCancelled", rec.optInt("cancelled") >= 1 && states.count { it == "cancelled" } == 1)
            .put("runningMarkedUnknown", rec.optInt("interrupted") >= 2 && states.count { it == "unknown" } == 2)
            .put("noTasks", rs.optInt("activeTasks") == 0 && rs.optInt("queuedTasks") == 0 && rt.optInt("tasks") == 0)
            .put("recoveryPendingKept", rs.optInt("recoveryPending") >= 2)
            .put("notForeground", !rt.optBoolean("foreground") && !rt.optBoolean("serviceRunning"))
            .put("heartbeatIdle", hb.optString("tasks") == "0" && hb.optString("fg") == "0" && hb.optString("state") == "idle")
        val ok = armedSessions.length() == 3 && checks.keys().asSequence().all { checks.optBoolean(it) }
        return JSONObject().put("ok", ok)
            .put("summary", "exit=${last.optString("reasonName")} recovered=$rec states=$states tasks=${rt.optInt("tasks")} fg=${rt.optBoolean("foreground")}")
            .put("checks", checks).put("lastExit", last).put("lastRecovered", rec).put("runState", rs)
            .put("heartbeat", hb).put("taskStates", JSONArray(states))
    }
}
