package org.agentos.extensions.registry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 注册表的持久化记忆（JSON，带版本号）：每个插件一条，扫描据此判断“签名变了、升级了、卸载了”。
 * 清单、服务器、状态每次扫描都从已安装的 App 重新读，不存。
 *
 * ```json
 * {"version":1,"plugins":[{"id":"org.x.notes/agent-plugin","package":"org.x.notes","name":"notes",
 *   "trustedSigner":"ab12…","observedSigner":"ab12…","versionCode":3}]}
 * ```
 *
 * 向前兼容：未知字段忽略；版本缺失或不是正整数、结构不对抛 [PersistedRegistryFormatException]。
 * **读不出来时不要当作“空”继续**：空记忆等于“第一次看到每个插件”，会把当前的签名当作可信的，签名变化就查不出来了。
 * 调用方要么保留上一份可用的，要么提示用户（第三方插件重新确认）。
 */
data class PersistedRegistry(val plugins: List<PersistedPlugin> = emptyList()) {

    fun toJson(): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("version", VERSION)
        put("plugins", buildJsonArray {
            for (p in plugins.sortedBy { it.id }) {
                add(buildJsonObject {
                    put("id", p.id)
                    put("package", p.packageName)
                    p.name?.let { put("name", it) }
                    put("trustedSigner", p.trustedSigner)
                    put("observedSigner", p.observedSigner)
                    put("versionCode", p.versionCode)
                })
            }
        })
    })

    companion object {
        const val VERSION = 1
        val EMPTY = PersistedRegistry()

        fun fromJson(text: String): PersistedRegistry {
            val root = try {
                Json.parseToJsonElement(text)
            } catch (e: Exception) {
                throw PersistedRegistryFormatException("not valid JSON")
            } as? JsonObject ?: throw PersistedRegistryFormatException("root is not an object")
            val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            if (version == null || version < 1) throw PersistedRegistryFormatException("missing or invalid version")
            val list = when (val p = root["plugins"]) {
                null, JsonNull -> JsonArray(emptyList())
                is JsonArray -> p
                else -> throw PersistedRegistryFormatException("plugins is not an array")
            }
            return PersistedRegistry(list.map { e ->
                val o = e as? JsonObject ?: throw PersistedRegistryFormatException("a plugin entry is not an object")
                fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                PersistedPlugin(
                    id = str("id") ?: throw PersistedRegistryFormatException("plugin without id"),
                    packageName = str("package") ?: throw PersistedRegistryFormatException("plugin without package"),
                    name = str("name"),
                    trustedSigner = str("trustedSigner") ?: throw PersistedRegistryFormatException("plugin without trustedSigner"),
                    observedSigner = str("observedSigner") ?: throw PersistedRegistryFormatException("plugin without observedSigner"),
                    versionCode = (o["versionCode"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                        ?: throw PersistedRegistryFormatException("plugin without versionCode"),
                )
            })
        }
    }
}

class PersistedRegistryFormatException(message: String) : IllegalArgumentException("invalid plugin registry: $message")
