package org.agentos.runtime.ports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One tool a session may use: the `name` of the plugin (`plugin.json`) and the tool's own name as the plugin's MCP server reports it.
 * Never the model-facing `mcp__...` name: callers must not need to know the suffix rules (docs/third-party-acp.md 4.5).
 */
data class ToolRef(val plugin: String, val tool: String)

/**
 * What a session may use (docs/third-party-acp.md 4.5): **narrowing only**. The tools a session can really use are the scope intersected with
 * the current catalog (user policy, enabled plugins), so an entry that does not exist is simply never matched, and nothing tells the caller.
 *
 * - [ALL]: no restriction. What AgentOS itself (`SELF`) and the desktop get when they did not ask for a scope; today's behaviour, unchanged.
 * - [only] / [NONE]: exactly these tools. A scope can be given to any caller, and then it applies to that caller too.
 * - A third-party app ([CallerKind.APP]) that did not give a scope has **no tools at all** ([forCaller]).
 *
 * The match is on (plugin, tool); the server name is not part of it. Tools that do not come from a plugin (no [ToolSource]) are never in a
 * restricted scope.
 */
class ToolScope private constructor(private val refs: Set<ToolRef>?) {
    /** The scope names tools (possibly none). False only for [ALL]. */
    val restricted: Boolean get() = refs != null

    /** The tools, sorted; empty for [ALL] and [NONE] (tell them apart with [restricted]). */
    val entries: List<ToolRef> get() = refs?.let { normalize(it) } ?: emptyList()

    fun allows(source: ToolSource?): Boolean = refs == null || (source != null && ToolRef(source.plugin, source.tool) in refs)

    /**
     * AgentOS's own built-in tool (`read_skill`) has no plugin, so a restricted scope can never name it: a session that was given a scope
     * gets exactly the tools in it and nothing else, and its system prompt has no skills list either.
     */
    val allowsBuiltinTools: Boolean get() = refs == null

    /**
     * The scope to apply for a caller of this [kind]: a third-party app never gets [ALL] (it would be a wiring mistake: a session of an app
     * is created with a scope, or without one = no tools), so fail closed.
     */
    fun forCaller(kind: CallerKind): ToolScope = if (kind == CallerKind.APP && refs == null) NONE else this

    override fun equals(other: Any?) = other is ToolScope && other.refs == refs

    override fun hashCode() = refs.hashCode()

    override fun toString() = if (refs == null) "ToolScope.ALL" else "ToolScope(${entries.joinToString { "${it.plugin}/${it.tool}" }})"

    companion object {
        /** docs/third-party-acp.md 4.5: at most this many entries, each string at most [MAX_FIELD_CHARS] characters. */
        const val MAX_ENTRIES = 32
        const val MAX_FIELD_CHARS = 128

        val ALL = ToolScope(null)

        /** A scope that names no tool: a session that can only chat. */
        val NONE = ToolScope(emptySet())

        fun only(refs: Collection<ToolRef>): ToolScope = ToolScope(refs.toSet())

        /** Distinct entries in a fixed order: two scopes that name the same tools are equal and persist identically. */
        fun normalize(refs: Collection<ToolRef>): List<ToolRef> = refs.distinct().sortedWith(compareBy({ it.plugin }, { it.tool }))

        /** The persisted / logged form: `[{"plugin":"alarm","tool":"alarm_create"}, ...]` in [normalize] order. */
        fun toJson(refs: Collection<ToolRef>): JsonArray = buildJsonArray {
            normalize(refs).forEach { add(buildJsonObject { put("plugin", it.plugin); put("tool", it.tool) }) }
        }

        /**
         * Reads the persisted form. `null` / blank = no scope was given. Anything that cannot be read (damaged column) is a scope with no tools,
         * never "no restriction": a broken value must not widen what a session may do.
         */
        fun fromJson(text: String?): List<ToolRef>? {
            if (text.isNullOrBlank()) return null
            return try {
                val array = Json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
                array.map { el ->
                    val o = el as? JsonObject ?: return emptyList()
                    val plugin = (o["plugin"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return emptyList()
                    val tool = (o["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return emptyList()
                    ToolRef(plugin, tool)
                }.let { normalize(it) }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
