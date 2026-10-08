package org.agentos.sample.calendar.data

/**
 * 日历与日程 id 的来源前缀（对外仍是字符串）：
 * - `local:<uuid>`：本 App 自己的 SQLite（“本机日历”）。升级前写进库里的 id 是不带前缀的裸 UUID，库里不改，进出仓库时加 / 去前缀。
 * - `sys:<数字>`：系统日历库（CalendarContract）里的 `_id`。
 *
 * 重复日程的某一次出现是 `<series id>@<key>`；前缀里没有 `@`，拆分不受影响。收到不带前缀的 id（升级前的通知、旧脚本）一律按本机处理。
 */
object CalendarIds {
    const val LOCAL = "local:"
    const val SYSTEM = "sys:"

    fun isSystem(id: String): Boolean = id.startsWith(SYSTEM)

    /** 补全前缀：已有 `local:` / `sys:` 的原样返回，其余（升级前的裸 id）当作本机。 */
    fun normalize(id: String): String = if (id.startsWith(LOCAL) || id.startsWith(SYSTEM)) id else LOCAL + id

    fun local(raw: String): String = LOCAL + raw
    fun system(raw: Long): String = SYSTEM + raw

    fun rawLocal(id: String): String = id.removePrefix(LOCAL)

    /** `sys:` id 里的数字；不是系统 id 或不是数字返回 null。 */
    fun rawSystem(id: String): Long? = if (isSystem(id)) id.removePrefix(SYSTEM).toLongOrNull() else null

    /** 把 id（series id 或 `series@key`）拆开。 */
    fun split(id: String): Pair<String, String?> {
        val at = id.indexOf('@')
        return if (at < 0) id to null else id.substring(0, at) to id.substring(at + 1)
    }
}
