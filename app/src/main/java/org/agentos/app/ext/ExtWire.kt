package org.agentos.app.ext

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.agentos.extensions.host.KnownTool
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.broker.RiskPolicy
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.errors.ErrorInfo
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.CatalogTool
import org.agentos.runtime.ports.ContentPart
import org.agentos.runtime.ports.SkillCatalog
import org.agentos.runtime.ports.SkillSummary
import org.agentos.runtime.ports.ToolCatalog
import org.agentos.runtime.ports.ToolInvocation
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource

/**
 * IExtensionHost / IExtensionCallback 上的 JSON（:ext 编码、:agent 解码，同一个 APK 里的两个进程共用这一份）。
 * 字段说明见 IExtensionHost.aidl。错误和内容块直接用 core:runtime 的 [ErrorInfo]、[ContentPart] 的序列化形状，
 * 所以 :agent 拿到的 [ToolInvocationResult] 与 :ext 里 ExtensionToolHost 返回的完全相同。
 */
object ExtWire {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    // ------------------------------------------------------------------ 目录

    /** 目录里的一个工具（listTools / getCatalog 的一项）。[policy] 用来算 enabled / approval。 */
    fun toolJson(t: CatalogTool, policy: ApprovalPolicy): JsonObject = buildJsonObject {
        put("name", t.name)
        put("pluginId", t.provider)
        put("provider", t.provider)
        t.source?.let { s ->
            put("source", buildJsonObject { put("plugin", s.plugin); put("server", s.server); put("tool", s.tool) })
        }
        t.title?.let { put("title", it) }
        put("description", t.description)
        put("inputSchema", t.inputSchema)
        put("risk", t.risk.name.lowercase())
        val resolved = policy.resolve(t.source)
        put("enabled", resolved.enabled)
        put("approval", resolved.approval.wire)
        put("mayAlwaysAllow", RiskPolicy.mayAlwaysAllow(t.risk))
    }

    /**
     * 插件页的工具（listTools、setToolEnabled、setToolApproval、setToolApprovalBySource 的返回值）：与 A9 的 [KnownTool] **字段一一对应**，
     * 键固定为 [KNOWN_TOOL_KEYS]（title 没有时为 null，不省略），D 的插件页依赖这个形状。
     */
    fun knownToolJson(k: KnownTool): JsonObject = buildJsonObject {
        put("name", k.name)
        put("pluginId", k.pluginId)
        put("source", buildJsonObject { put("plugin", k.source.plugin); put("server", k.source.server); put("tool", k.source.tool) })
        put("title", k.title?.let { JsonPrimitive(it) } ?: JsonNull)
        put("description", k.description)
        put("inputSchema", k.inputSchema)
        put("risk", k.risk.name.lowercase())
        put("enabled", k.enabled)
        put("approval", k.approval.wire)
        put("mayAlwaysAllow", k.mayAlwaysAllow)
    }

    /** [knownToolJson] 的全部键，按输出顺序。 */
    val KNOWN_TOOL_KEYS = listOf("name", "pluginId", "source", "title", "description", "inputSchema", "risk", "enabled", "approval", "mayAlwaysAllow")

    /** getCatalog 的返回值。[failClosed]：策略文件坏了又没有可用的副本，第三方插件一律当作禁用（toJson 不带这个状态）。 */
    fun catalogJson(version: Long, catalog: ToolCatalog, policy: ApprovalPolicy, skills: SkillCatalog = SkillCatalog.EMPTY): String = buildJsonObject {
        put("version", version)
        put("tools", buildJsonArray { catalog.tools.forEach { add(toolJson(it, policy)) } })
        put("skills", buildJsonArray {
            skills.skills.forEach { s ->
                add(buildJsonObject { put("id", s.id); put("name", s.name); put("description", s.description); put("provider", s.provider) })
            }
        })
        put("policy", policy.toJsonObject())
        put("policyFailClosed", !policy.unlistedPluginsEnabled)
    }.toString()

    /** :agent 侧解析 getCatalog 的结果。 */
    class DecodedCatalog(val version: Long, val tools: List<CatalogTool>, val policy: ApprovalPolicy, val skills: List<SkillSummary>)

    fun decodeCatalog(text: String): DecodedCatalog {
        val root = json.parseToJsonElement(text) as JsonObject
        val failClosed = root.bool("policyFailClosed") ?: false
        val policy = ApprovalPolicy.fromJsonObject(root["policy"] as JsonObject).copy(unlistedPluginsEnabled = !failClosed)
        val tools = (root["tools"] as? JsonArray).orEmpty().mapNotNull { decodeTool(it as? JsonObject ?: return@mapNotNull null) }
        // 目录里不能有重名（ToolCatalog 的要求）；:ext 已经保证，这里只是不让一条坏数据拖垮整个目录
        val skills = (root["skills"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            SkillSummary(o.str("id") ?: return@mapNotNull null, o.str("name") ?: "", o.str("description") ?: "", o.str("provider") ?: "")
        }.distinctBy { it.id }
        return DecodedCatalog(root.long("version") ?: 0, tools.distinctBy { it.name }, policy, skills)
    }

    private fun decodeTool(o: JsonObject): CatalogTool? {
        val name = o.str("name") ?: return null
        val src = o["source"] as? JsonObject
        val source = src?.let { s -> ToolSource(s.str("plugin") ?: return null, s.str("server") ?: return null, s.str("tool") ?: return null) }
        return CatalogTool(
            name = name,
            description = o.str("description") ?: "",
            inputSchema = o["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
            // 认不出的风险等级按高（最严）
            risk = ToolRisk.entries.firstOrNull { it.name.equals(o.str("risk"), ignoreCase = true) } ?: ToolRisk.HIGH,
            provider = o.str("provider") ?: o.str("pluginId") ?: "",
            title = o.str("title"),
            source = source,
        )
    }

    // ------------------------------------------------------------------ 调用

    fun requestJson(inv: ToolInvocation): String = buildJsonObject {
        put("name", inv.name)
        put("arguments", inv.arguments)
        put("timeoutMs", inv.timeoutMillis)
        put("sessionId", inv.sessionId)
        put("taskId", inv.taskId)
        put("toolCallId", inv.toolCallId)
        put("callerUid", inv.caller.uid)
        put("callerKind", inv.caller.kind.name)
        inv.caller.label?.let { put("callerLabel", it) }
    }.toString()

    /** :ext 侧解析 callTool 的请求；格式不对抛 [IllegalArgumentException]（agentos.ext.bad_request）。 */
    fun decodeRequest(text: String?): ToolInvocation {
        val o = try {
            json.parseToJsonElement(text ?: "") as JsonObject
        } catch (e: Exception) {
            throw ExtError.badRequest("request is not a JSON object")
        }
        val name = o.str("name") ?: throw ExtError.badRequest("name is missing")
        val args = when (val a = o["arguments"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> a
            else -> throw ExtError.badRequest("arguments must be an object")
        }
        val timeout = o.long("timeoutMs") ?: 0
        if (timeout < 0) throw ExtError.badRequest("timeoutMs must be >= 0")
        return ToolInvocation(
            sessionId = o.str("sessionId") ?: "",
            taskId = o.str("taskId") ?: "",
            toolCallId = o.str("toolCallId") ?: "",
            name = name,
            arguments = args,
            caller = CallerIdentity(
                uid = (o["callerUid"] as? JsonPrimitive)?.intOrNull ?: -1,
                kind = CallerKind.entries.firstOrNull { it.name == o.str("callerKind") } ?: CallerKind.SYSTEM,
                label = o.str("callerLabel"),
            ),
            timeoutMillis = timeout,
        )
    }

    fun outcomeJson(r: ToolInvocationResult): String = when (r) {
        is ToolInvocationResult.Completed -> buildJsonObject {
            put("outcome", "completed")
            put("result", buildJsonObject {
                put("content", buildJsonArray { r.result.content.forEach { add(json.encodeToJsonElement(ContentPart.serializer(), it)) } })
                put("isError", r.result.isError)
                r.result.details?.let { put("details", it) }
            })
        }
        is ToolInvocationResult.NotDispatched -> errorOutcome("not_dispatched", r.error)
        is ToolInvocationResult.Unknown -> errorOutcome("unknown", r.error)
    }.toString()

    private fun errorOutcome(kind: String, e: ErrorInfo) = buildJsonObject {
        put("outcome", kind)
        put("error", json.encodeToJsonElement(ErrorInfo.serializer(), e))
    }

    /**
     * :agent 侧解析 onToolResult。解析不了时按“结果未知”（已受理的调用可能已经发出，不能当作没发出）。
     */
    fun decodeOutcome(text: String?): ToolInvocationResult = try {
        val o = json.parseToJsonElement(text ?: "") as JsonObject
        when (o.str("outcome")) {
            "completed" -> {
                val r = o["result"] as JsonObject
                val content = (r["content"] as JsonArray).map { json.decodeFromJsonElement(ContentPart.serializer(), it) }
                ToolInvocationResult.Completed(ToolResult(content, isError = r.bool("isError") ?: false, details = r["details"]?.takeIf { it !is JsonNull }))
            }
            "not_dispatched" -> ToolInvocationResult.NotDispatched(json.decodeFromJsonElement(ErrorInfo.serializer(), o["error"]!!))
            "unknown" -> ToolInvocationResult.Unknown(json.decodeFromJsonElement(ErrorInfo.serializer(), o["error"]!!))
            else -> malformed("unknown outcome")
        }
    } catch (e: Exception) {
        malformed(e.javaClass.simpleName)
    }

    private fun malformed(why: String) = ToolInvocationResult.Unknown(
        ErrorCode.TOOL_RESULT_UNKNOWN.info("The extension host returned a result that could not be read ($why); the result is unknown."),
    )

    // ------------------------------------------------------------------ 小工具

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}
