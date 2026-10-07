package org.agentos.runtime.router

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.Ids
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.events.PendingEvent
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.RuntimeLog
import org.agentos.runtime.ports.info
import org.agentos.runtime.store.SessionRecord
import org.agentos.runtime.store.SessionState
import org.agentos.runtime.store.Store
import org.agentos.runtime.store.StoreTx

/**
 * 自动选会话（core/contracts/session-selection.md）：用户不指定会话时，在**调用方自己的**会话里选一个，或新建一个。
 *
 * - 候选只来自 Store（按 ownerKey 隔离），活跃窗口 30 分钟、最多 254 个，不足 20 个时用较早的会话补足（标为 stale）；
 * - 交给 Jev 的 brief 只含首轮问题、首轮回答、最近回答、最近两轮问答，按字符预算截断，`new_session` 永远在；
 * - Jev 只能从请求里的 choiceId 里选；选中已有会话时在事务里再校验归属和状态；
 * - **任何失败都回退为新建会话**（未配置、网络、超时、非法响应、非法选择、会话已不可用），不阻塞用户提交。
 */
class SessionRouter(
    private val store: Store,
    private val jev: JevProvider?,
    private val config: RouterConfig = RouterConfig(),
    private val log: RuntimeLog = RuntimeLog.NONE,
) {
    data class Result(
        val session: SessionRecord,
        val created: Boolean,
        /** jev / no_candidates / fallback_new_session */
        val method: String,
        val fallbackReason: String? = null,
    )

    suspend fun route(caller: CallerIdentity, query: String, cwd: String?): Result {
        val now = store.read { it.now }
        val candidates = store.read { tx -> candidates(tx.sessions.listByOwner(caller.ownerKey), now) }
        if (candidates.isEmpty()) return createNew(caller, cwd, "no_candidates", null)
        if (jev == null) return createNew(caller, cwd, METHOD_FALLBACK, "jev_unconfigured")

        val request = buildRequest(query, candidates, now)
        val choice = try {
            withTimeoutOrNull(config.timeoutMillis) { jev.choose(request) } ?: return createNew(caller, cwd, METHOD_FALLBACK, "jev_timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: JevException) {
            return createNew(caller, cwd, METHOD_FALLBACK, e.reason)
        } catch (e: Exception) {
            return createNew(caller, cwd, METHOD_FALLBACK, "jev_error")
        }
        if (choice == JevProvider.NEW_SESSION) return createNew(caller, cwd, METHOD_JEV, null)
        if (candidates.none { it.id == choice }) return createNew(caller, cwd, METHOD_FALLBACK, "jev_invalid_choice")

        // 提交前再校验：仍属于调用方、没有终态、不在等恢复决定
        val selected = store.write { tx ->
            val s = tx.sessions.get(choice)
            if (s == null || s.ownerKey != caller.ownerKey || s.state.terminal || s.state == SessionState.SYSTEM || s.pauseReason != null) {
                return@write null
            }
            tx.events.append(
                PendingEvent(choice, null, EventTypes.SESSION_SELECTED, buildJsonObject { put("created", false); put("reason", METHOD_JEV) }),
            )
            s
        } ?: return createNew(caller, cwd, METHOD_FALLBACK, "selected_session_unavailable")
        log.info(TAG, "auto-select chose an existing session")
        return Result(selected, created = false, method = METHOD_JEV)
    }

    private suspend fun createNew(caller: CallerIdentity, cwd: String?, method: String, reason: String?): Result {
        val session = store.write { tx ->
            val s = createSession(tx, caller, cwd, via = "auto_select")
            tx.events.append(
                PendingEvent(
                    s.id, null, EventTypes.SESSION_SELECTED,
                    buildJsonObject {
                        put("created", true)
                        put("reason", reason ?: method)
                    },
                ),
            )
            s
        }
        if (reason != null) log.info(TAG, "auto-select fell back to a new session: $reason")
        return Result(session, created = true, method = method, fallbackReason = reason)
    }

    /** 活跃池 + 冷候选补足（session-selection.md 第 1 节）。 */
    internal fun candidates(sessions: List<SessionRecord>, now: Long): List<Candidate> {
        val eligible = sessions.filter { !it.state.terminal && it.state != SessionState.SYSTEM && it.pauseReason == null && !it.selection.firstQuery.isNullOrBlank() }
            .sortedWith(compareByDescending<SessionRecord> { it.lastActivityAt }.thenBy { it.id })
        val cutoff = now - config.activeWindowMillis
        val active = eligible.filter { it.lastActivityAt >= cutoff }.take(config.maxActiveSessions)
        val staleWanted = (config.minRecentSessions - active.size).coerceAtLeast(0)
        val stale = eligible.filter { it.lastActivityAt < cutoff }.take(minOf(staleWanted, config.maxActiveSessions - active.size))
        return active.map { Candidate(it, stale = false) } + stale.map { Candidate(it, stale = true) }
    }

    internal data class Candidate(val session: SessionRecord, val stale: Boolean) {
        val id: String get() = session.id
    }

    /** 按字符预算组装请求：先给每个候选完整 brief，超预算时整体缩短 brief，`new_session` 永远保留。 */
    internal fun buildRequest(query: String, candidates: List<Candidate>, @Suppress("UNUSED_PARAMETER") now: Long): JevRequest {
        val q = truncate(query.trim(), config.maxQueryChars)
        val newChoice = JevChoice(JevProvider.NEW_SESSION, "Start a new Session")
        var perBrief = config.maxBriefChars
        while (true) {
            val choices = candidates.map { c -> JevChoice(c.id, brief(c, perBrief)) } + newChoice
            val size = q.length + choices.sumOf { it.id.length + it.brief.length + 8 }
            if (size <= config.maxInputChars || perBrief == 0) return JevRequest(q, choices)
            perBrief = if (perBrief <= 32) 0 else perBrief / 2
        }
    }

    internal fun brief(c: Candidate, maxChars: Int): String {
        if (maxChars <= 0) return ""
        val sel = c.session.selection
        val field = maxChars / 3
        val parts = buildList {
            sel.firstQuery?.let { add("First query: " + truncate(it, field)) }
            sel.firstAnswer?.let { add("First answer: " + truncate(it, field)) }
            sel.latestAnswer?.takeIf { it != sel.firstAnswer }?.let { add("Latest answer: " + truncate(it, field)) }
            sel.recentTurns.forEach { (q, a) -> add("Recent: Q: ${truncate(q, field / 2)} A: ${truncate(a, field / 2)}") }
        }
        val text = (if (c.stale) "[stale recent Session]\n" else "") + parts.joinToString("\n")
        return truncate(text, maxChars)
    }

    private fun truncate(s: String, max: Int): String = if (s.length <= max) s else s.take((max - 1).coerceAtLeast(0)) + "…"

    companion object {
        const val TAG = "SessionRouter"
        const val METHOD_JEV = "jev"
        const val METHOD_FALLBACK = "fallback_new_session"

        /** 新建会话并写 session.created（session/new 与自动选会话共用）。 */
        fun createSession(tx: StoreTx, caller: CallerIdentity, cwd: String?, via: String): SessionRecord {
            val s = tx.sessions.create(Ids.session(tx.now), caller, cwd, tx.now)
            tx.events.append(
                PendingEvent(
                    s.id, null, EventTypes.SESSION_CREATED,
                    buildJsonObject {
                        put("ownerKey", caller.ownerKey)
                        put("callerKind", caller.kind.name.lowercase())
                        put("callerUid", caller.uid)
                        put("via", via)
                    },
                ),
            )
            return s
        }
    }
}

/** session-selection.md 第 1、2 节的参数。 */
data class RouterConfig(
    val activeWindowMillis: Long = 30 * 60_000L,
    /** 活跃池上限；加上 new_session 不超过 Jev 的 255 个 Choice。 */
    val maxActiveSessions: Int = 254,
    /** 活跃会话不足时用较早的会话补足到这个数。 */
    val minRecentSessions: Int = 20,
    /** 本地的输入预算（字符，约 16,384 token）。 */
    val maxInputChars: Int = 48_000,
    val maxQueryChars: Int = 8_000,
    /** 每个候选 brief 的上限（首轮问题、首轮回答、最近回答各约 1,600 字符）。 */
    val maxBriefChars: Int = 4_800,
    /** 等 Jev 的时间；超时回退。比 JevConfig.timeoutMillis（3 秒）多留 0.5 秒给建立连接之外的开销。 */
    val timeoutMillis: Long = 3_500,
) {
    init {
        require(maxActiveSessions in 1..254) { "maxActiveSessions must be 1..254 (255 choices including new_session)" }
        require(minRecentSessions >= 0)
    }
}
