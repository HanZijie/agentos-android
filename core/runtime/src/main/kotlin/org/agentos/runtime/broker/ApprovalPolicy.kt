package org.agentos.runtime.broker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource

/** 审批方式（docs/extensions.md 5.4）。 */
enum class ApprovalMode(val wire: String) {
    /** 每次调用都确认（默认）。 */
    ASK("ask"),

    /** 始终允许：不再确认。**高风险工具不适用**（[RiskPolicy.mayAlwaysAllow]）。 */
    ALWAYS("always"),
    ;

    companion object {
        /** 文件里的值；不认识的值按最严的 [ASK]（向前兼容：以后新增的方式，老版本当“每次确认”处理）。 */
        fun fromWire(value: String?): ApprovalMode = entries.firstOrNull { it.wire == value } ?: ASK
    }
}

/** 一个策略作用的范围：整个插件、插件里的一个服务器、服务器里的一个工具（名字是 MCP 服务器报告的原始工具名）。 */
sealed interface PolicyScope {
    data class Plugin(val plugin: String) : PolicyScope

    data class Server(val plugin: String, val server: String) : PolicyScope

    data class Tool(val plugin: String, val server: String, val tool: String) : PolicyScope

    companion object {
        fun of(source: ToolSource): Tool = Tool(source.plugin, source.server, source.tool)
    }
}

/**
 * 某一层写下的设置。null 表示“没设置，沿用上一层”。
 *
 * @property enabled false = 禁用；true = 启用；null = 沿用
 * @property approval null = 沿用
 */
data class PolicyEntry(val enabled: Boolean? = null, val approval: ApprovalMode? = null) {
    val isEmpty: Boolean get() = enabled == null && approval == null
}

data class ServerPolicy(val entry: PolicyEntry = PolicyEntry(), val tools: Map<String, PolicyEntry> = emptyMap())

data class PluginPolicy(val entry: PolicyEntry = PolicyEntry(), val servers: Map<String, ServerPolicy> = emptyMap())

/** [ApprovalPolicy.resolve] 的结果：这个工具现在能不能用、审批方式是什么。 */
data class ToolPolicy(val enabled: Boolean, val approval: ApprovalMode)

/**
 * 用户的工具策略（docs/extensions.md 5.4 的第三层）：按插件、服务器、工具分别启用或禁用，审批方式设为
 * “每次确认”或“始终允许”。不可变；改动返回新对象。纯数据，不依赖 Android；持久化格式见 [toJson]。
 *
 * **解析规则**（[resolve]）：
 * - 启用：插件、服务器、工具三层里**任何一层明确禁用**（`enabled = false`）就是禁用；没写的层视为启用。
 *   所以插件级禁用一整个插件，工具级再写 `enabled = true` 也不会把它救回来；
 * - 审批方式：从具体到一般取第一个写了的——工具、服务器、插件，都没写就是 [ApprovalMode.ASK]；
 * - 没有来源信息的工具（`ToolSource == null`，例如宿主层自己注册的）不受这份策略约束：启用、每次按风险等级确认。
 *
 * “始终允许”对高风险工具无效：[ApprovalMode.ALWAYS] 能写进文件，但 [RiskPolicy.consentRequirement] 对高风险工具照样要确认；
 * 设置界面用 [RiskPolicy.mayAlwaysAllow] 不提供这个选项，[withApproval] 在已知是高风险时直接拒绝。
 *
 * 第三方插件默认关闭（docs/extensions.md 6）：这份策略对没写的插件按“启用”解析，所以导入或发现第三方插件时，
 * Extension Host 要为它显式写一个 `enabled = false` 的插件级设置，用户启用时改成 true。
 */
data class ApprovalPolicy(val plugins: Map<String, PluginPolicy> = emptyMap()) {

    /** 这个工具现在能不能用、审批方式是什么。 */
    fun resolve(source: ToolSource?): ToolPolicy {
        if (source == null) return ToolPolicy(enabled = true, approval = ApprovalMode.ASK)
        val plugin = plugins[source.plugin]
        val server = plugin?.servers?.get(source.server)
        val tool = server?.tools?.get(source.tool)
        val levels = listOfNotNull(tool, server?.entry, plugin?.entry)
        return ToolPolicy(
            enabled = levels.none { it.enabled == false },
            approval = levels.firstNotNullOfOrNull { it.approval } ?: ApprovalMode.ASK,
        )
    }

    /** 设置（[enabled] 为 null 表示清除，沿用上一层）。 */
    fun withEnabled(scope: PolicyScope, enabled: Boolean?): ApprovalPolicy = update(scope) { it.copy(enabled = enabled) }

    /**
     * 设置审批方式（[approval] 为 null 表示清除）。
     *
     * @param risk 工具级设置时传这个工具的风险等级：高风险不能设为 [ApprovalMode.ALWAYS]，抛 [IllegalArgumentException]。
     *   插件、服务器级设置覆盖很多工具，不检查（其中的高风险工具运行时仍然每次确认）。
     */
    fun withApproval(scope: PolicyScope, approval: ApprovalMode?, risk: ToolRisk? = null): ApprovalPolicy {
        require(!(approval == ApprovalMode.ALWAYS && risk != null && !RiskPolicy.mayAlwaysAllow(risk))) {
            "a $risk tool cannot be set to always allow"
        }
        return update(scope) { it.copy(approval = approval) }
    }

    /** 清掉某个范围的全部设置（含它下面的）。 */
    fun cleared(scope: PolicyScope): ApprovalPolicy = when (scope) {
        is PolicyScope.Plugin -> copy(plugins = plugins - scope.plugin)
        is PolicyScope.Server -> modifyPlugin(scope.plugin) { it.copy(servers = it.servers - scope.server) }
        is PolicyScope.Tool -> modifyPlugin(scope.plugin) { p ->
            p.copy(servers = p.servers.mapValues { (name, s) -> if (name == scope.server) s.copy(tools = s.tools - scope.tool) else s })
        }
    }.pruned()

    /** 某个范围自己写下的设置（不含继承）；没写过为空。 */
    fun entryOf(scope: PolicyScope): PolicyEntry = when (scope) {
        is PolicyScope.Plugin -> plugins[scope.plugin]?.entry
        is PolicyScope.Server -> plugins[scope.plugin]?.servers?.get(scope.server)?.entry
        is PolicyScope.Tool -> plugins[scope.plugin]?.servers?.get(scope.server)?.tools?.get(scope.tool)
    } ?: PolicyEntry()

    private fun update(scope: PolicyScope, change: (PolicyEntry) -> PolicyEntry): ApprovalPolicy = when (scope) {
        is PolicyScope.Plugin -> modifyPlugin(scope.plugin) { it.copy(entry = change(it.entry)) }
        is PolicyScope.Server -> modifyPlugin(scope.plugin) { p -> p.modifyServer(scope.server) { it.copy(entry = change(it.entry)) } }
        is PolicyScope.Tool -> modifyPlugin(scope.plugin) { p ->
            p.modifyServer(scope.server) { s -> s.copy(tools = s.tools + (scope.tool to change(s.tools[scope.tool] ?: PolicyEntry()))) }
        }
    }.pruned()

    private fun modifyPlugin(name: String, change: (PluginPolicy) -> PluginPolicy) =
        copy(plugins = plugins + (name to change(plugins[name] ?: PluginPolicy())))

    private fun PluginPolicy.modifyServer(name: String, change: (ServerPolicy) -> ServerPolicy) =
        copy(servers = servers + (name to change(servers[name] ?: ServerPolicy())))

    /** 去掉空设置：同一份策略只有一种表示（[equals] 和 [toJson] 都因此稳定）。 */
    private fun pruned(): ApprovalPolicy = ApprovalPolicy(
        plugins.mapValues { (_, p) ->
            p.copy(
                servers = p.servers.mapValues { (_, s) -> s.copy(tools = s.tools.filterValues { !it.isEmpty }) }
                    .filterValues { !it.entry.isEmpty || it.tools.isNotEmpty() },
            )
        }.filterValues { !it.entry.isEmpty || it.servers.isNotEmpty() },
    )

    // ------------------------------------------------------------------ 持久化

    /**
     * 持久化格式（JSON，带版本号）。键按字典序，同一份策略总是同一个字符串；空设置不写：
     *
     * ```json
     * {"version":1,"plugins":{"notes":{"enabled":true,"approval":"ask",
     *   "servers":{"notes":{"approval":"always","tools":{"delete_note":{"enabled":false}}}}}}}
     * ```
     */
    fun toJson(): String = Json.encodeToString(JsonObject.serializer(), toJsonObject())

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("version", VERSION)
        putJsonObject("plugins") {
            for ((pluginName, plugin) in plugins.toSortedMap()) {
                put(pluginName, buildJsonObject {
                    putEntry(plugin.entry)
                    if (plugin.servers.isNotEmpty()) {
                        putJsonObject("servers") {
                            for ((serverName, server) in plugin.servers.toSortedMap()) {
                                put(serverName, buildJsonObject {
                                    putEntry(server.entry)
                                    if (server.tools.isNotEmpty()) {
                                        putJsonObject("tools") {
                                            for ((toolName, tool) in server.tools.toSortedMap()) put(toolName, buildJsonObject { putEntry(tool) })
                                        }
                                    }
                                })
                            }
                        }
                    }
                })
            }
        }
    }

    companion object {
        /** 当前写出的格式版本。 */
        const val VERSION = 1

        /** 没有任何设置：全部启用、全部每次确认。 */
        val DEFAULT = ApprovalPolicy()

        /**
         * 读持久化的策略。**向前兼容**：
         * - 认识的版本号是 1 或更大的整数；更大的版本按 1 的字段读，所有不认识的字段忽略；
         * - `approval` 不认识的值按 [ApprovalMode.ASK]（最严）；
         * - `enabled` 不是布尔值（也不是 null）按 false（禁用，最严）；
         * - 版本号缺失或不是正整数、不是 JSON 对象、`plugins` 不是对象：抛 [ApprovalPolicyFormatException]，
         *   由调用方决定（保留上一份可用的，或把第三方工具都当作禁用）。不要悄悄回到默认值：那会把用户禁用的插件重新启用。
         *
         * 注意：用旧版本读到新版本写的文件再写回，不认识的字段会丢。
         */
        fun fromJson(text: String): ApprovalPolicy {
            val root = try {
                Json.parseToJsonElement(text)
            } catch (e: Exception) {
                throw ApprovalPolicyFormatException("not valid JSON")
            }
            return fromJsonObject(root as? JsonObject ?: throw ApprovalPolicyFormatException("root is not an object"))
        }

        fun fromJsonObject(root: JsonObject): ApprovalPolicy {
            val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            if (version == null || version < 1) throw ApprovalPolicyFormatException("missing or invalid version")
            val plugins = when (val p = root["plugins"]) {
                null, JsonNull -> JsonObject(emptyMap())
                is JsonObject -> p
                else -> throw ApprovalPolicyFormatException("plugins is not an object")
            }
            return ApprovalPolicy(
                plugins.mapNotNull { (pluginName, p) ->
                    val plugin = p as? JsonObject ?: return@mapNotNull pluginName to PluginPolicy(PolicyEntry(enabled = false))
                    pluginName to PluginPolicy(
                        entry = readEntry(plugin),
                        servers = objectOf(plugin["servers"]).mapValues { (_, s) ->
                            val server = s as? JsonObject
                            if (server == null) ServerPolicy(PolicyEntry(enabled = false)) else ServerPolicy(
                                entry = readEntry(server),
                                tools = objectOf(server["tools"]).mapValues { (_, t) -> (t as? JsonObject)?.let(::readEntry) ?: PolicyEntry(enabled = false) },
                            )
                        },
                    )
                }.toMap(),
            ).pruned()
        }

        private fun objectOf(element: JsonElement?): JsonObject = element as? JsonObject ?: JsonObject(emptyMap())

        private fun readEntry(obj: JsonObject): PolicyEntry {
            val enabled = when (val e = obj["enabled"]) {
                null, JsonNull -> null
                is JsonPrimitive -> if (e.isString) false else e.booleanOrNull ?: false
                else -> false
            }
            val approval = when (val a = obj["approval"]) {
                null, JsonNull -> null
                is JsonPrimitive -> ApprovalMode.fromWire(a.takeIf { it.isString }?.content)
                else -> ApprovalMode.ASK
            }
            return PolicyEntry(enabled, approval)
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.putEntry(entry: PolicyEntry) {
            entry.enabled?.let { put("enabled", it) }
            entry.approval?.let { put("approval", it.wire) }
        }
    }
}

class ApprovalPolicyFormatException(message: String) : IllegalArgumentException("invalid approval policy: $message")
