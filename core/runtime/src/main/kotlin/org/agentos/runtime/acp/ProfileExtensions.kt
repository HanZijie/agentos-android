package org.agentos.runtime.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.agentos.runtime.ports.SessionMcpResult
import org.agentos.runtime.ports.ToolRef
import org.agentos.runtime.ports.ToolScope
import org.agentos.runtime.router.SessionRouter

/**
 * AgentOS ACP Profile 的扩展约定（core/protocol/acp-extensions.schema.json，acp-mapping.md 第 5 节）。
 *
 * 所有扩展数据都放在 ACP 的 `_meta` 里，键名 [META_KEY]；不在标准对象的根上加字段，不改变标准方法的含义（Profile）。
 * 扩展要在 `initialize` 时协商：服务端在响应的 `_meta` 里声明支持的扩展，客户端在请求的 `_meta` 里声明要用的；
 * 没协商就使用扩展的请求会被明确拒绝，而不是静默按标准语义处理。
 */
object ProfileExtensions {
    /** `_meta` 里 AgentOS 数据的键。 */
    const val META_KEY = "org.agentos"

    /** Profile 版本（与 ACP 协议版本独立）。 */
    const val PROFILE_VERSION = 1

    /** 自动选会话扩展（M1）。 */
    const val SESSION_AUTO_SELECT = "sessionAutoSelect"

    /**
     * 会话的工具范围（docs/third-party-acp.md 4.5）：`session/new` 的 `_meta."org.agentos".toolScope`。
     * **只在 `initialize` 里声明，让客户端探测；不要求客户端协商**——它只会让会话能用的工具更少（第三方 App 没有它就没有任何工具），
     * 不会改变任何标准方法的含义，所以没有“没协商就静默按标准语义处理”的风险。
     */
    const val SESSION_TOOL_SCOPE = "toolScope"

    /** 本版本支持的扩展及其版本。持久化提交、增量恢复在 W10 加入。 */
    val SUPPORTED: Map<String, Int> = mapOf(SESSION_AUTO_SELECT to 1, SESSION_TOOL_SCOPE to 1, SESSION_SETUP to 1)

    /**
     * 会话建立收尾标记（`session/new | load | resume | fork`）：**要协商**。协商了的客户端，每次会话建立的最后（响应之前）都会收到一条
     * `session_info_update`，`_meta."org.agentos".setup = { "replayed": N }`——前面一共重放了 N 条 `session/update`（不含这条和其他 `session_info_update`）。
     * 客户端等到收尾标记和 N 条都到齐，就知道历史、服务器状态收全了：通知和响应在客户端那边是并发处理的，光靠“响应回来了”不能保证通知都到了。
     * 没协商的客户端不会收到这条（行为和以前一样）。
     */
    const val SESSION_SETUP = "sessionSetup"

    /** `initialize` 响应的 `_meta`。 */
    fun initializeMeta(runtimeVersion: String): JsonObject = buildJsonObject {
        put(
            META_KEY,
            buildJsonObject {
                put("profile", PROFILE_VERSION)
                put("runtime", runtimeVersion)
                // architecture 原则 6：安全等级如实告知
                put("securityLevel", "best_effort")
                put("extensions", buildJsonObject { SUPPORTED.forEach { (name, v) -> put(name, buildJsonObject { put("version", v) }) } })
            },
        )
    }

    /** 客户端在 `initialize` 请求的 `_meta."org.agentos".extensions` 里声明要用的扩展（字符串数组）；只保留服务端支持的。 */
    fun clientExtensions(meta: JsonElement?): Set<String> {
        val list = ((meta as? JsonObject)?.get(META_KEY) as? JsonObject)?.get("extensions") as? JsonArray ?: return emptySet()
        return list.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it in SUPPORTED }.toSet()
    }

    /**
     * `session/new` 请求里的自动选会话参数：`_meta."org.agentos".autoSelect.query`。
     * 没有这个对象时返回 null（标准的新建会话）；有对象但 query 不是非空字符串时返回 ""（由调用方报 invalid_params）。
     */
    fun autoSelectQuery(meta: JsonElement?): String? {
        val auto = ((meta as? JsonObject)?.get(META_KEY) as? JsonObject)?.get("autoSelect") as? JsonObject ?: return null
        return (auto["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
    }

    /** [toolScope] 解析的结果：没有这个字段（`Absent`），或合法的 `Scope`（可以为空列表 = 没有任何工具），或形状不对（`Invalid`，带原因）。 */
    sealed interface ToolScopeParse {
        data object Absent : ToolScopeParse

        data class Scope(val refs: List<ToolRef>) : ToolScopeParse

        data class Invalid(val reason: String) : ToolScopeParse
    }

    /**
     * `session/new` 请求里的工具范围：`_meta."org.agentos".toolScope` = `[{"plugin": "...", "tool": "..."}, ...]`。
     *
     * - 没有 `_meta`、没有 `org.agentos`、没有 `toolScope` 键：`Absent`；
     * - 不是数组、元素不是对象、元素缺 `plugin` / `tool` 或不是字符串、字符串为空或超过 [ToolScope.MAX_FIELD_CHARS] 个字符、
     *   超过 [ToolScope.MAX_ENTRIES] 项：`Invalid`（调用方报 `invalid_params`）；
     * - 元素里多出来的键忽略（以后可以加字段）；重复的项合并；空数组是合法的（= 没有任何工具）。
     *
     * 这里只看形状，不看工具是否存在：写了不存在的项不报错（调用方不能借此探测用户装了什么）。
     */
    fun toolScope(meta: JsonElement?): ToolScopeParse {
        val ext = (meta as? JsonObject)?.get(META_KEY)
        if (ext == null || ext is kotlinx.serialization.json.JsonNull) return ToolScopeParse.Absent
        val obj = ext as? JsonObject ?: return ToolScopeParse.Absent
        val raw = obj["toolScope"] ?: return ToolScopeParse.Absent
        val list = raw as? JsonArray ?: return ToolScopeParse.Invalid("toolScope must be an array")
        if (list.size > ToolScope.MAX_ENTRIES) return ToolScopeParse.Invalid("toolScope has more than ${ToolScope.MAX_ENTRIES} entries")
        val refs = ArrayList<ToolRef>(list.size)
        for ((i, el) in list.withIndex()) {
            val o = el as? JsonObject ?: return ToolScopeParse.Invalid("toolScope[$i] must be an object with \"plugin\" and \"tool\"")
            val plugin = scopeString(o["plugin"]) ?: return ToolScopeParse.Invalid("toolScope[$i].plugin must be a non-empty string of at most ${ToolScope.MAX_FIELD_CHARS} characters")
            val tool = scopeString(o["tool"]) ?: return ToolScopeParse.Invalid("toolScope[$i].tool must be a non-empty string of at most ${ToolScope.MAX_FIELD_CHARS} characters")
            refs += ToolRef(plugin, tool)
        }
        return ToolScopeParse.Scope(ToolScope.normalize(refs))
    }

    private fun scopeString(el: JsonElement?): String? =
        (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() && it.length <= ToolScope.MAX_FIELD_CHARS }

    /** 自动选会话之后，经 `session_info_update` 的 `_meta` 告知客户端选择结果。 */
    fun selectionMeta(result: SessionRouter.Result): JsonObject = buildJsonObject {
        put(
            META_KEY,
            buildJsonObject {
                put(
                    "selection",
                    buildJsonObject {
                        put("sessionId", result.session.id)
                        put("created", result.created)
                        put("method", result.method)
                        result.fallbackReason?.let { put("fallbackReason", it) }
                    },
                )
            },
        )
    }

    /**
     * 会话建立（`session/new | load | resume | fork`）之后，经 `session_info_update` 的 `_meta."org.agentos"` 告诉客户端的几件事，都是可选的：
     * - `selection`：自动选会话的结果（只有 `session/new` 带 `autoSelect` 时）；
     * - `mcpServers`：这次挂的会话级 MCP 服务器各自的结局 `[{name, connected, toolCount, reason?}]`。`reason` 是短代码，不含 URL 和头；
     *   连不上的服务器不会让会话建立失败，客户端据此提示用户；
     * - `activeTask`：`session/load | resume` 时这个会话里还有一轮没结束 `{taskId, state}`；客户端等它结束再发新的，或者先取消。
     * 三样都没有时返回 null（不发这条通知）。
     */
    fun sessionMeta(
        selection: SessionRouter.Result?,
        mcp: List<SessionMcpResult>?,
        activeTask: Pair<String, String>?,
        /** 协商了 [SESSION_SETUP] 时：前面重放了多少条 `session/update`；没协商为 null。 */
        replayed: Int? = null,
    ): JsonObject? {
        if (selection == null && mcp.isNullOrEmpty() && activeTask == null && replayed == null) return null
        return buildJsonObject {
            put(
                META_KEY,
                buildJsonObject {
                    replayed?.let { put("setup", buildJsonObject { put("replayed", it) }) }
                    selection?.let { r ->
                        put(
                            "selection",
                            buildJsonObject {
                                put("sessionId", r.session.id)
                                put("created", r.created)
                                put("method", r.method)
                                r.fallbackReason?.let { put("fallbackReason", it) }
                            },
                        )
                    }
                    if (!mcp.isNullOrEmpty()) {
                        put(
                            "mcpServers",
                            JsonArray(
                                mcp.map { m ->
                                    buildJsonObject {
                                        put("name", m.server)
                                        put("connected", m.connected)
                                        put("toolCount", m.toolCount)
                                        m.reason?.let { put("reason", it) }
                                    }
                                },
                            ),
                        )
                    }
                    activeTask?.let { (id, state) -> put("activeTask", buildJsonObject { put("taskId", id); put("state", state) }) }
                },
            )
        }
    }

    /** `session/prompt` 响应的 `_meta`：本轮对应的任务 ID（诊断与 W10 的恢复扩展用）。 */
    fun promptResponseMeta(taskId: String): JsonObject = buildJsonObject {
        put(META_KEY, buildJsonObject { put("taskId", taskId) })
    }
}
