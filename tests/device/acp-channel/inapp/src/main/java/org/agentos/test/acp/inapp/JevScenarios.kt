package org.agentos.test.acp.inapp

import android.content.Context
import android.os.SystemClock
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.agentos.channel.ChannelConfig
import org.agentos.test.acp.AcpConn
import org.agentos.test.acp.TestIds
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * D5.1：自动选会话（session/new 的 `_meta."org.agentos".autoSelect`）经 Jev。Jev 是电脑上的假服务（fake_jev.py，
 * 经 adb reverse 到设备的 127.0.0.1:18788，F9 允许回环明文）。key 由 run.py 随机生成，经 stdin 写进
 * `files/test/jev_key`（KeyDropProvider，命令行里没有 key），读完立即删除。
 *
 * 一个场景按顺序走完：没有候选 → 新建；没配 key → jev_unconfigured；Jev 选已有 / 选 new_session；
 * 401 / 503 / 乱码 / 空答案 / 非法 ID / 超时 / key 不对 → 回退新建且 fallbackReason 对。
 * 选择结果由随后的 `session_info_update`（`_meta."org.agentos".selection`）告知；SDK 把 prompt 之前到达的通知并进下一轮的
 * 事件流，所以每次选择后发一句话再读。清掉 Store 才能保证“没有候选”，所以场景开头杀掉 :agent、删库。
 */
class JevScenarios(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val status: (String) -> Unit,
) {
    private val control = ControlClient(ctx)

    suspend fun run(name: String, args: JSONObject): JSONObject? = when (name) {
        "jev-autoselect" -> autoSelect(args)
        else -> null
    }

    private class Selection(val sessionId: String, val created: Boolean?, val method: String?, val fallbackReason: String?, val ms: Long)

    private val ops = object : ClientSessionOperations {
        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: JsonElement?,
        ): RequestPermissionResponse = RequestPermissionResponse(RequestPermissionOutcome.Cancelled)

        // SDK 0.30.1：prompt 之前到达的会话通知不一定并进这一轮的事件流，也可能交给会话的 notify；两处都收
        override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
            if (notification is SessionUpdate.SessionInfoUpdate) strayInfo = notification._meta ?: _meta
        }
    }

    @Volatile private var strayInfo: JsonElement? = null

    private fun selectionOf(meta: JsonElement?): JsonObject? =
        ((meta as? JsonObject)?.get("org.agentos") as? JsonObject)?.get("selection") as? JsonObject

    private fun testKey(args: JSONObject): String {
        val f = File(ctx.filesDir, args.optString("keyFile", "test/jev_key"))
        try {
            val key = f.readText().trim()
            check(key.length >= 16) { "test key file is empty or too short" }
            return key
        } finally {
            f.delete()
        }
    }

    private suspend fun fakeLog(): List<JSONObject> = withContext(Dispatchers.IO) {
        val conn = URL("$BASE/_log").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val arr = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("requests")
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } finally {
            conn.disconnect()
        }
    }

    /** autoSelect 的 session/new，再发一句话读选择结果。返回选择和（可能被运行时挪到下一轮事件里的）通知。 */
    private suspend fun select(c: AcpConn, query: String): Selection {
        val meta = buildJsonObject { put("org.agentos", buildJsonObject { put("autoSelect", buildJsonObject { put("query", query) }) }) }
        strayInfo = null
        val t0 = SystemClock.elapsedRealtime()
        val session: ClientSession = c.client.newSession(SessionCreationParameters(cwd = "/", mcpServers = emptyList(), _meta = meta)) { _, _ -> ops }
        val ms = SystemClock.elapsedRealtime() - t0
        var selection: JsonObject? = null
        withTimeout(60_000) {
            session.prompt(listOf(ContentBlock.Text("ok"))).collect { ev ->
                val u = (ev as? Event.SessionUpdateEvent)?.update
                if (u is SessionUpdate.SessionInfoUpdate) selectionOf(u._meta)?.let { selection = it }
            }
        }
        val s = selection ?: selectionOf(strayInfo)
        fun str(k: String) = (s?.get(k) as? JsonPrimitive)?.contentOrNull
        return Selection(
            sessionId = session.sessionId.value,
            created = str("created")?.toBooleanStrictOrNull(),
            method = str("method"),
            fallbackReason = str("fallbackReason"),
            ms = ms,
        ).also { if (s != null) check(str("sessionId") == it.sessionId) { "selection.sessionId differs from the session/new result" } }
    }

    private fun Selection.json() = JSONObject().put("sessionId", sessionId).put("created", created ?: JSONObject.NULL)
        .put("method", method ?: JSONObject.NULL).put("fallbackReason", fallbackReason ?: JSONObject.NULL).put("newSessionMs", ms)

    private suspend fun autoSelect(args: JSONObject): JSONObject {
        val key = testKey(args)
        check(killAgentAndWait(ctx)) { "could not kill :agent" }
        val db = ctx.getDatabasePath("agentos-runtime.db")
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(db.path + suffix).delete()

        val checks = JSONObject()
        val steps = JSONArray()
        fun step(name: String, sel: Selection, vararg extra: Pair<String, Any?>) {
            steps.put(JSONObject().put("step", name).put("selection", sel.json()).apply { extra.forEach { put(it.first, it.second ?: JSONObject.NULL) } })
        }

        control.use { it.clearJevSource() }
        val conn = AcpConn(ctx, TestIds.APP_ACP, ChannelConfig.DEFAULT, scope, "jev")
        try {
            check(conn.binding.bind()) { "cannot bind AcpService" }
            conn.openOn(conn.binding.awaitConnected(15_000) ?: error("AcpService not connected"))
            val init = buildJsonObject {
                put("org.agentos", buildJsonObject { put("extensions", JsonArray(listOf(JsonPrimitive("sessionAutoSelect")))) })
            }
            conn.client.initialize(ClientInfo(implementation = Implementation("agentos-device-test-client", "0.1")), init)
            status("initialized")

            // 1. 没有候选：新建，不问 Jev
            val first = select(conn, "plan a trip to Kyoto")
            step("no-candidates", first)
            checks.put("noCandidatesCreates", first.created == true && first.method == "no_candidates" && first.fallbackReason == null)

            // 2. 有候选但没配 Jev key：新建，jev_unconfigured
            val unconfigured = select(conn, "hotels in Kyoto")
            step("unconfigured", unconfigured)
            checks.put("unconfigured", unconfigured.created == true && unconfigured.method == "fallback_new_session" && unconfigured.fallbackReason == "jev_unconfigured")
            checks.put("sessionsAreDistinct", unconfigured.sessionId != first.sessionId)

            // 配置假 Jev（回环明文，F9）
            val src = control.use { it.setJevSource(ENDPOINT, key) }
            checks.put("configured", src.optBoolean("usable") && src.optBoolean("customEndpoint") && src.optString("keyMasked").contains("\u2026"))
            fakeReset()

            // 3. Jev 选中已有会话：返回已有 sessionId，method=jev
            val picked = select(conn, "[jev:first] what about hotels?")
            val log1 = fakeLog()
            val chosen1 = log1.lastOrNull()?.optString("chosen")
            step("jev-picks-existing", picked, "fakeChosen" to chosen1)
            // 选中的是已经跑过 prompt 的会话：SDK 对这个 sessionId 已有登记，select 通知不一定进这一轮的事件流
            // （协议层的 selection 由 core:runtime 的 AcpAgentSideTest 核对 method=jev）。这里以 session/new 返回的 sessionId
            // 与假 Jev 的记录为准；收到了通知就必须是 method=jev、created=false。
            checks.put("jevPicksExisting", chosen1 != null && chosen1 != "new_session" && chosen1 == picked.sessionId &&
                picked.sessionId == unconfigured.sessionId && picked.fallbackReason == null &&
                (picked.method == null || (picked.method == "jev" && picked.created == false)))
            checks.put("jevSawChoicesAndNewSession", log1.lastOrNull()?.optJSONArray("choices")?.let { a ->
                (0 until a.length()).map { a.getString(it) }.let { ids -> "new_session" in ids && ids.size >= 2 }
            } == true)
            checks.put("keyInjectedByHost", log1.isNotEmpty() && log1.all { it.optString("key") == "test" })

            // 4. Jev 选 new_session：新建，method=jev
            val chosenNew = select(conn, "[jev:new] something else")
            step("jev-picks-new", chosenNew)
            checks.put("jevPicksNew", chosenNew.created == true && chosenNew.method == "jev" && chosenNew.fallbackReason == null)

            // 5. 各种失败都回退新建，原因对
            fun fallbackOk(s: Selection, reason: String) = s.created == true && s.method == "fallback_new_session" && s.fallbackReason == reason
            val http401 = select(conn, "[jev:401] q"); step("401", http401)
            checks.put("http401", fallbackOk(http401, "jev_http_error"))
            val http503 = select(conn, "[jev:503] q"); step("503", http503)
            checks.put("http503", fallbackOk(http503, "jev_http_retryable"))
            val garbage = select(conn, "[jev:garbage] q"); step("garbage", garbage)
            checks.put("garbage", fallbackOk(garbage, "jev_invalid_response"))
            val empty = select(conn, "[jev:empty] q"); step("empty", empty)
            checks.put("empty", fallbackOk(empty, "jev_invalid_response"))
            val invalid = select(conn, "[jev:invalid] q"); step("invalid-choice", invalid)
            checks.put("invalidChoice", fallbackOk(invalid, "jev_invalid_choice"))
            val hang = select(conn, "[jev:hang] q"); step("timeout", hang)
            checks.put("timeout", fallbackOk(hang, "jev_timeout") && hang.ms in 2_500..5_500)

            // 6. key 不对：假 Jev 返回 401 → jev_http_error；key 被清除 → jev_unconfigured
            control.use { it.setJevSource(ENDPOINT, key + "x") }
            val wrongKey = select(conn, "[jev:first] q"); step("wrong-key", wrongKey)
            checks.put("wrongKey", fallbackOk(wrongKey, "jev_http_error"))
            val log2 = fakeLog()
            checks.put("wrongKeySeenAsOther", log2.last().optString("key") == "other")
            control.use { it.clearJevSource() }
            val cleared = select(conn, "[jev:first] q"); step("cleared", cleared)
            checks.put("cleared", fallbackOk(cleared, "jev_unconfigured"))
            val st = control.use { it.jevSource() }
            checks.put("clearedState", !st.optBoolean("configured") && !st.optBoolean("keySet") && st.isNull("keyMasked"))
            checks.put("clearedNoRequest", fakeLog().size == log2.size)

            // 7. 端点规则：公网 http 被拒，错误不回显输入
            val bad = try {
                control.use { it.setJevSource("http://jev.example.com/v1", key) }
                null
            } catch (e: Throwable) {
                e.message
            }
            checks.put("plainHttpRefused", bad != null && bad.startsWith("agentos.jev.invalid_endpoint") && !bad.contains(key) && !bad.contains("jev.example.com"))
        } finally {
            conn.dispose()
            runCatching { control.use { it.clearJevSource() } }
        }
        val ok = checks.keys().asSequence().all { checks.optBoolean(it) }
        return JSONObject().put("ok", ok)
            .put("summary", "checks=${checks.keys().asSequence().count { checks.optBoolean(it) }}/${checks.length()} " +
                checks.keys().asSequence().filter { !checks.optBoolean(it) }.joinToString(",", "failed=[", "]"))
            .put("checks", checks).put("steps", steps)
    }

    private suspend fun fakeReset() = withContext(Dispatchers.IO) {
        val conn = URL("$BASE/_reset").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.use { it.write(ByteArray(0)) }
            conn.inputStream.readBytes()
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val BASE = "http://127.0.0.1:18788"
        const val ENDPOINT = "$BASE/v1/systemone"
    }
}
