package org.agentos.extensions.skills

import org.agentos.extensions.registry.PluginRecord
import java.io.IOException

/**
 * 读插件包里的文件（**Android 与 JVM 之间的接缝**）。Android 上：已安装 App 内嵌的插件经 `createPackageContext(包名, 0).assets`，
 * 导入的插件包读 `:ext` 的私有目录。[ExtensionSkillPort] 只会请求 [PluginRecord.skillFiles] 里列出的、已经通过路径检查的相对路径，
 * 实现按这个相对路径（相对插件根）原样读取，不需要再做路径解析；也不应该跟随符号链接跑出插件根（导入时 PackageValidator 已经拒绝符号链接，
 * assets 里没有符号链接）。
 */
interface SkillFileSource {
    /**
     * 读 [path]（相对插件根，例如 `skills/notes/SKILL.md`）。最多返回 [maxBytes] 字节；文件更长时 [SkillFile.truncated] 为 true。
     * 文件不存在返回 null；读不了抛 [IOException]。
     */
    suspend fun read(plugin: PluginRecord, path: String, maxBytes: Int): SkillFile?
}

class SkillFile(val bytes: ByteArray, val truncated: Boolean = false)
