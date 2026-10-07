package org.agentos.app.ext.skills

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.agentos.extensions.registry.PluginRecord
import org.agentos.extensions.skills.SkillFile
import org.agentos.extensions.skills.SkillFileSource
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

/**
 * A10 的 [SkillFileSource] 在 Android 上的实现（已安装 App 内嵌的插件）：`createPackageContext(包名, 0).assets`，
 * 路径 = `<assets 目录>/<path>`。插件根目录取自 [PluginRecord.id]（`<包名>/<assets 目录>`，PluginScanLogic 的约定）。
 * [path] 已经由 ExtensionSkillPort 检查过、且在 [PluginRecord.skillFiles] 里（AppPluginScanner 列出的），这里不再解析。
 * 导入的插件包（W18）以后在 `:ext` 私有目录里读。
 */
class AssetSkillFileSource(private val context: Context) : SkillFileSource {

    override suspend fun read(plugin: PluginRecord, path: String, maxBytes: Int): SkillFile? = withContext(Dispatchers.IO) {
        val pkg = plugin.identity.packageName
        val root = plugin.id.removePrefix("$pkg/").takeIf { it != plugin.id && it.isNotEmpty() }
            ?: throw IOException("plugin ${plugin.id} is not an installed-app plugin")
        val assets = try {
            context.createPackageContext(pkg, 0).assets
        } catch (e: Exception) {
            throw IOException("cannot open the package of ${plugin.id}: ${e.javaClass.simpleName}")
        }
        val stream = try {
            assets.open("$root/$path")
        } catch (e: FileNotFoundException) {
            return@withContext null
        }
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var truncated = false
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val room = maxBytes - out.size()
                if (n > room) {
                    out.write(buf, 0, room.coerceAtLeast(0))
                    truncated = true
                    break
                }
                out.write(buf, 0, n)
            }
            SkillFile(out.toByteArray(), truncated)
        }
    }
}
