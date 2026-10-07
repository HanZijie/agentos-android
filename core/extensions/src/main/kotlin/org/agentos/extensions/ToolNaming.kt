package org.agentos.extensions

import java.security.MessageDigest

/**
 * 一个 MCP 工具的来源：插件名、服务器名、MCP 服务器报告的原始工具名（都是**未处理**的原文）。
 * [ToolNaming] 据此生成交给模型的工具名。
 */
data class ToolId(val plugin: String, val server: String, val tool: String) {
    init {
        require(plugin.isNotEmpty() && server.isNotEmpty() && tool.isNotEmpty()) { "plugin, server and tool must not be empty" }
    }

    /** 用 NUL 分隔的原文，哈希只看它；字段里不会出现 NUL（MCP 名字是普通文本）。 */
    internal val identity: String get() = "$plugin\u0000$server\u0000$tool"
}

/**
 * 工具命名（docs/extensions.md 5.3）：`mcp__<插件名>__<服务器名>__<工具名>`，兼容各家模型 API 对工具名的限制。
 *
 * 规则：
 * 1. 每一段只保留 `[A-Za-z0-9_-]`：插件名里的 `.` 换成 `_`，其余不合法的字符也换成 `_`（不删除，保留单词边界）。
 * 2. 总长不超过 [MAX_LENGTH]（64）。超过时截断并加 `_` 加 6 位十六进制哈希（哈希取自原文三元组，不是处理后的名字，
 *    所以不同的原文几乎不会得到同一个后缀），最终恰好 64 个字符。
 * 3. 单个工具的名字由 [nameOf] 给出，只取决于这个工具本身；**一个目录里**可能有不同的工具得到同一个名字
 *    （例如 `a.b` 和 `a_b` 两个插件），目录级的确定性消解由 [assign] 负责。
 *
 * 目录级消解的规则（[assign]）：
 * - 结果只取决于传入的**集合**，与顺序无关，同一个集合永远得到同一份映射；
 * - 名字唯一的工具保持 [nameOf] 的结果；
 * - 名字相同的一组工具**全部**加哈希后缀（不偏袒任何一个，也就不依赖安装先后）：`<基础名截断>_<哈希>`；
 *   后缀从 6 位开始，如果仍和目录里别的名字重名就加长（8、12、16、24 位），直到整个目录唯一；
 * - 目录里出现两个完全相同的 [ToolId] 是调用方的错误，抛 [IllegalArgumentException]。
 *
 * 注意：目录里新增一个会撞名的工具，会让原来不带后缀的那个工具改名；审批策略（ApprovalPolicy）按 [ToolId] 记录，
 * 不受影响，只有按工具名写的 Hook matcher 会受影响（matcher 按处理后的最终名字匹配）。
 */
object ToolNaming {
    const val PREFIX = "mcp__"
    const val MAX_LENGTH = 64
    private val HASH_LENGTHS = intArrayOf(6, 8, 12, 16, 24)

    /** 一段名字：不合法字符换成 `_`；空串（理论上不会有）得到 `_`。 */
    fun sanitize(segment: String): String {
        if (segment.isEmpty()) return "_"
        val sb = StringBuilder(segment.length)
        for (c in segment) sb.append(if (c.isAllowed()) c else '_')
        return sb.toString()
    }

    /** 不考虑目录的名字：`mcp__<插件>__<服务器>__<工具>`，超长时截断加哈希。 */
    fun nameOf(id: ToolId): String = fit(plain(id), id, HASH_LENGTHS[0], forceSuffix = false)

    /**
     * 给一个目录里的全部工具命名。返回的映射覆盖每个传入的 [ToolId]；值两两不同，都是合法的模型工具名
     * （`[A-Za-z0-9_-]{1,64}`）。
     */
    fun assign(ids: Collection<ToolId>): Map<ToolId, String> {
        val unique = ids.toSet()
        require(unique.size == ids.size) { "duplicate tool ids" }
        val base = unique.associateWith { nameOf(it) }
        val groups = base.entries.groupBy({ it.value }, { it.key })
        val result = LinkedHashMap<ToolId, String>()
        val colliding = ArrayList<ToolId>()
        for (id in unique.sortedWith(ID_ORDER)) {
            if (groups.getValue(base.getValue(id)).size == 1) result[id] = base.getValue(id) else colliding += id
        }
        if (colliding.isEmpty()) return result
        // 唯一的名字先占住；撞名的逐步加长哈希，直到谁也不和谁（或唯一的名字）重名
        val taken = HashSet(result.values)
        var pending: List<ToolId> = colliding
        for (length in HASH_LENGTHS) {
            val candidates = pending.associateWith { fit(plain(it), it, length, forceSuffix = true) }
            val count = candidates.values.groupingBy { it }.eachCount()
            val settled = pending.filter { count.getValue(candidates.getValue(it)) == 1 && candidates.getValue(it) !in taken }
            for (id in settled) {
                result[id] = candidates.getValue(id)
                taken += candidates.getValue(id)
            }
            pending = pending - settled.toSet()
            if (pending.isEmpty()) break
        }
        // 24 位哈希（96 位）之后仍撞名只可能是哈希碰撞，不会发生；保险起见明确失败，不静默产生重名
        check(pending.isEmpty()) { "cannot make tool names unique: ${pending.size} left" }
        return result.entries.sortedBy { it.value }.associate { it.key to it.value }
    }

    private fun plain(id: ToolId) = "$PREFIX${sanitize(id.plugin)}__${sanitize(id.server)}__${sanitize(id.tool)}"

    /** 超长（或 [forceSuffix]）时：截断基础名，加 `_` 加 [hashLength] 位哈希，总长不超过 [MAX_LENGTH]。 */
    private fun fit(name: String, id: ToolId, hashLength: Int, forceSuffix: Boolean): String {
        if (!forceSuffix && name.length <= MAX_LENGTH) return name
        val hash = sha256Hex(id.identity).take(hashLength)
        val keep = (MAX_LENGTH - 1 - hash.length).coerceAtLeast(0)
        return name.take(keep) + "_" + hash
    }

    private fun Char.isAllowed() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '-'

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private val ID_ORDER = compareBy<ToolId>({ it.plugin }, { it.server }, { it.tool })
}
