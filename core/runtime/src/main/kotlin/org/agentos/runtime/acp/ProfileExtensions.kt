package org.agentos.runtime.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
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

    /** 本版本支持的扩展及其版本。持久化提交、增量恢复在 W10 加入。 */
    val SUPPORTED: Map<String, Int> = mapOf(SESSION_AUTO_SELECT to 1)

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

    /** `session/prompt` 响应的 `_meta`：本轮对应的任务 ID（诊断与 W10 的恢复扩展用）。 */
    fun promptResponseMeta(taskId: String): JsonObject = buildJsonObject {
        put(META_KEY, buildJsonObject { put("taskId", taskId) })
    }
}
