package org.agentos.sample.calendar.tools

import kotlinx.serialization.json.JsonObject

/**
 * 结果在 MCP 线上的真实成本（docs/sample-apps.md，C7a 的实际行为）：`McpToolResult.json(对象)` 把同一段紧凑 JSON
 * 在 `content`（作为被转义的字符串）和 `structuredContent` 里各放一份，单条消息上限 65,536 字符。
 * 成本 ≈ 2 × 长度 + 转义增量（引号、反斜杠各多一个字符；紧凑 JSON 里没有裸控制字符）+ 外层信封。
 * 算法与备忘录示例 App 的同名工具一致；区别是信封只算一次（数组里每一项只算 [payload]）。
 */
internal object WireSize {
    const val CHANNEL_LIMIT = 65_536

    /** 留出余量后的上限：列表类工具按它在数组元素边界上截断。 */
    const val LIMIT = 60_000

    /** JSON-RPC 外壳、`content` 数组、`type` / `text` / `structuredContent` 等键的固定开销（含余量）。 */
    private const val ENVELOPE = 200

    /** 一段 JSON 在线上占的字符数（不含信封）。 */
    fun payload(value: JsonObject): Int {
        val text = value.toString()
        var escapes = 0
        for (c in text) if (c == '"' || c == '\\' || c < ' ') escapes++
        return text.length * 2 + escapes
    }

    /** 整条结果的成本。 */
    fun cost(value: JsonObject): Int = payload(value) + ENVELOPE

    /**
     * 数组里最多能放几项：[basePayload] 是数组以外的部分，[itemPayloads] 是各项的 [payload]（逗号按每项 2 计）。
     * 至少放一项（单项远小于上限：标题 ≤ 200、地点 ≤ 200、备注 ≤ 4000 字符），这样分页总能前进。
     */
    fun fit(itemPayloads: Sequence<Int>, basePayload: Int, limit: Int = LIMIT): Int {
        var cost = basePayload + ENVELOPE
        var n = 0
        for (p in itemPayloads) {
            cost += p + 2
            if (cost > limit && n > 0) break
            n++
        }
        return n
    }
}
