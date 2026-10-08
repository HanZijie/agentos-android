@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package org.agentos.acp

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement

/** [SessionCollector.finish] 的结果：建立会话那一段里收到的东西。 */
internal class Collected(val history: List<AgentOsEvent>, val servers: List<McpServerStatus>, val activeTask: String?)

/**
 * 一个会话的“带外”通知：不属于任何一轮 prompt 的 `session/update`，只在建立会话的那一段（[begin] 到 [finish]）里收集。
 * - 重放历史（`session/load`）：用户消息、Agent 的文字和思考、工具调用 → history；
 * - `session_info_update` 的 AgentOS 字段：挂着的 MCP 服务器的状态、进行中的任务、**收尾标记**。
 *
 * 通知和响应在客户端是并发处理的，响应回来不等于通知都到了。AgentOS 在最后发一条收尾标记（`setup.replayed = N`，前面一共 N 条 `session/update`），
 * [finish] 等标记和 N 条都到齐才返回；旧版本的 AgentOS 没有标记，不等。
 *
 * AgentOS 的工具确认由 AgentOS 自己的界面问用户（F5）；作为 ACP 客户端，SDK 不替用户批准任何东西，
 * 所以 ACP 的权限请求一律回答“取消”。
 */
internal class SessionCollector(private val scope: List<ToolRef>?) : ClientSessionOperations {
    val setupLock = Mutex()

    private val history = ArrayList<AgentOsEvent>()
    private var mapper = AgentOsMapping.PromptMapper(scope, includeThoughts = true, includeUserMessages = true)
    private var servers: List<McpServerStatus> = emptyList()
    private var activeTask: String? = null
    private var replay = false
    private var active = false
    private var received = 0
    private var expected: Int? = null
    private var ready = CompletableDeferred<Unit>()

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse = RequestPermissionResponse(RequestPermissionOutcome.Cancelled)

    /** 开始一段建立会话：清掉上一段留下的东西。 */
    fun begin(replay: Boolean, awaitMarker: Boolean) = synchronized(this) {
        history.clear()
        mapper = AgentOsMapping.PromptMapper(scope, includeThoughts = true, includeUserMessages = true)
        servers = emptyList()
        activeTask = null
        received = 0
        expected = null
        this.replay = replay
        ready = CompletableDeferred<Unit>().also { if (!awaitMarker) it.complete(Unit) }
        active = true
    }

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
        synchronized(this) {
            if (!active) return
            if (notification is SessionUpdate.SessionInfoUpdate) {
                AgentOsMapping.sessionInfo(notification._meta)?.let { info ->
                    info.mcpServers?.let { servers = it }
                    info.activeTaskId?.let { activeTask = it }
                }
                AgentOsMapping.setupReplayed(notification._meta)?.let { expected = it }
            } else {
                received++
                if (replay) mapper.map(notification)?.let { history += it }
            }
            if (expected?.let { received >= it } == true) ready.complete(Unit)
        }
    }

    /** 建立失败：不再收集。 */
    fun abort() = synchronized(this) {
        active = false
        ready.complete(Unit)
    }

    /** 响应到了：等收尾标记和它说的那么多条都到齐（最多 [timeoutMillis]；等不到就用手上有的），然后停止收集。 */
    suspend fun finish(timeoutMillis: Long): Collected {
        val deferred = synchronized(this) { ready }
        withTimeoutOrNull(timeoutMillis) { deferred.await() }
        return synchronized(this) {
            active = false
            Collected(history.toList(), servers, activeTask)
        }
    }
}
