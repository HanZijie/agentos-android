package org.agentos.extensions.session

import org.agentos.extensions.ToolNaming
import java.security.MessageDigest

/**
 * 会话级工具的命名：`ses__<服务器名>__<工具名>`，兼容各家模型 API 对工具名的限制（`[A-Za-z0-9_-]{1,64}`）。
 *
 * 规则与 [ToolNaming] 相同，前缀换成 [PREFIX]：
 * 1. 每一段只保留 `[A-Za-z0-9_-]`，其余字符换成 `_`（[ToolNaming.sanitize]）；
 * 2. 总长不超过 [MAX_LENGTH]：超过时截断并加 `_` 加 6 位十六进制哈希（取自服务器名和工具名的**原文**，不是处理后的名字）；
 * 3. 一个会话里不同的 (服务器, 工具) 得到同一个名字时（例如 `a.b` 和 `a_b`），**全部**加哈希后缀，
 *    后缀从 6 位开始，仍重名就加长（8、12、16、24 位）；结果只取决于传入的**集合**，与顺序无关。
 *
 * 前缀 `ses__` 保证名字**永远不会**以插件工具的前缀 `mcp__` 开头，也不会等于内置的 `read_skill`。
 */
internal object SessionToolNaming {
    const val PREFIX = "ses__"
    const val MAX_LENGTH = ToolNaming.MAX_LENGTH
    private val HASH_LENGTHS = intArrayOf(6, 8, 12, 16, 24)

    /** (服务器名, MCP 原始工具名) */
    fun assign(ids: Collection<Pair<String, String>>): Map<Pair<String, String>, String> {
        val unique = ids.toSet()
        require(unique.size == ids.size) { "duplicate tool ids" }
        val base = unique.associateWith { fit(it, HASH_LENGTHS[0], force = false) }
        val groups = base.entries.groupBy({ it.value }, { it.key })
        val result = LinkedHashMap<Pair<String, String>, String>()
        val colliding = ArrayList<Pair<String, String>>()
        for (id in unique.sortedWith(compareBy({ it.first }, { it.second }))) {
            if (groups.getValue(base.getValue(id)).size == 1) result[id] = base.getValue(id) else colliding += id
        }
        if (colliding.isEmpty()) return result
        val taken = HashSet(result.values)
        var pending: List<Pair<String, String>> = colliding
        for (length in HASH_LENGTHS) {
            val candidates = pending.associateWith { fit(it, length, force = true) }
            val count = candidates.values.groupingBy { it }.eachCount()
            val settled = pending.filter { count.getValue(candidates.getValue(it)) == 1 && candidates.getValue(it) !in taken }
            for (id in settled) {
                result[id] = candidates.getValue(id)
                taken += candidates.getValue(id)
            }
            pending = pending - settled.toSet()
            if (pending.isEmpty()) break
        }
        check(pending.isEmpty()) { "cannot make session tool names unique: ${pending.size} left" } // 96 位哈希碰撞，不会发生
        return result
    }

    private fun plain(id: Pair<String, String>) = "$PREFIX${ToolNaming.sanitize(id.first)}__${ToolNaming.sanitize(id.second)}"

    private fun fit(id: Pair<String, String>, hashLength: Int, force: Boolean): String {
        val name = plain(id)
        if (!force && name.length <= MAX_LENGTH) return name
        val hash = sha256Hex("${id.first}\u0000${id.second}").take(hashLength)
        return name.take((MAX_LENGTH - 1 - hash.length).coerceAtLeast(0)) + "_" + hash
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
