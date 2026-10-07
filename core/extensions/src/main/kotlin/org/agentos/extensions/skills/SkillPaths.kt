package org.agentos.extensions.skills

/**
 * Skill 文件的路径规则：模型给的 `path` 是第三方可影响的输入（模型读了 SKILL.md 之后会照着它读别的文件），
 * 只能读**同一个 Skill 目录里**的文件。
 *
 * 两道检查：
 * 1. 字面检查（[resolve]）：相对路径，不能是绝对路径（`/…`、`C:\…`），不能有 `..`、`.`、空段（`a//b`、结尾的 `/`），
 *    不能有反斜杠、NUL 和其他控制字符、Unicode 格式字符（如从右到左的覆盖符），长度和层数有上限。不解码百分号编码（`%2e%2e` 就是字面上的文件名）。
 * 2. 成员检查（[ExtensionSkillPort] 做）：结果必须正好是插件包文件清单（[org.agentos.extensions.registry.PluginRecord.skillFiles]）里的一个文件。
 *    清单只有普通文件（导入时已经拒绝符号链接和特殊文件，assets 里没有符号链接），所以符号链接指向插件根之外的内容、清单里没有的文件都读不到。
 */
object SkillPaths {
    const val SKILL_FILE = "SKILL.md"
    const val MAX_PATH_CHARS = 256
    const val MAX_SEGMENTS = 8

    /** 返回相对插件根的完整路径（`skills/<目录>/<path>`）；[path] 为 null 或空表示 SKILL.md。不合法抛 [IllegalArgumentException]。 */
    fun resolve(skillDirectory: String, path: String?): String {
        require(skillDirectory.isNotEmpty() && '/' !in skillDirectory && skillDirectory != "." && skillDirectory != "..") { "invalid skill directory" }
        val rel = if (path.isNullOrEmpty()) SKILL_FILE else path
        require(rel.length <= MAX_PATH_CHARS) { "path is too long" }
        for (c in rel) {
            require(c != '\u0000') { "path contains a NUL character" }
            require(!Character.isISOControl(c)) { "path contains a control character" }
            require(Character.getType(c) != Character.FORMAT.toInt() && c != '\u2028' && c != '\u2029') { "path contains an invisible formatting character" }
            require(c != '\\') { "path must use / as the separator" }
        }
        require(!rel.startsWith("/")) { "path must be relative to the skill directory" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(rel)) { "path must be relative to the skill directory" }
        val segments = rel.split('/')
        require(segments.size <= MAX_SEGMENTS) { "path is too deep" }
        for (s in segments) {
            require(s.isNotEmpty()) { "path has an empty segment" }
            require(s != "." && s != "..") { "path must not contain . or .. segments" }
        }
        return "skills/$skillDirectory/$rel"
    }
}
