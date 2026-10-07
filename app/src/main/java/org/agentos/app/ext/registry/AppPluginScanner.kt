package org.agentos.app.ext.registry

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.util.Log
import org.agentos.extensions.registry.InstalledAppView
import org.agentos.extensions.registry.PluginAssets
import org.agentos.extensions.registry.PluginIdentity
import org.agentos.extensions.registry.PluginServiceInfo
import org.agentos.plugin.AgentPluginContract
import java.io.IOException
import java.security.MessageDigest

/**
 * W14 的 Android 薄适配层（docs/extensions.md 4.1、E1）：PackageManager → [InstalledAppView]。**这一步不 bind。**
 * 规则全在 A 的 PluginScanLogic 里，这里只读数据：
 *
 * 1. `queryIntentServices(org.agentos.intent.action.PLUGIN)` 找锚点（需要 Manifest 里的 `<queries>`）。
 *    系统不会把别的 App **没有导出**的 Service 交给隐式 Intent 解析，所以锚点没导出的 App 在这里就看不到（本来也用不了）。
 * 2. `getPackageInfo(GET_SERVICES | GET_META_DATA | GET_SIGNING_CERTIFICATES)`：全部 Service（完整类名、是否导出、要求的权限、
 *    `org.agentos.plugin.assets`）和签名。
 * 3. 签名摘要：当前签名证书（`apkContentsSigners`，轮换后是新证书）的 SHA-256 小写十六进制；多个签名者时各自摘要排序后
 *    用逗号拼接再 SHA-256。签名轮换（v3）同样算“签名变了”。
 * 4. `createPackageContext(包名, 0).assets` 读各锚点 assets 目录下的 `plugin.json`、`mcp.json` 和 `skills/` 下的文件列表
 *    （只列路径，不读内容）。单个文件上限 [MAX_JSON_BYTES]，文件列表最多 [MAX_SKILL_FILES] 项。
 *
 * 读某个 App 失败（扫描期间被卸载、assets 读不出来）只影响这个 App：跳过或把读不出的文件当作不存在，由 PluginScanLogic 判定。
 */
class AppPluginScanner(private val context: Context) {

    /** 当前已安装的、带插件锚点的 App。 */
    fun scanViews(): List<InstalledAppView> {
        val pm = context.packageManager
        val anchors = pm.queryIntentServices(
            Intent(AgentPluginContract.ACTION_PLUGIN),
            PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        ).mapNotNull { it.serviceInfo }
        return anchors.groupBy { it.packageName }.mapNotNull { (pkg, svcs) ->
            try {
                viewOf(pm, pkg, svcs.map { it.name }.toSet())
            } catch (e: PackageManager.NameNotFoundException) {
                null // 扫描期间被卸载
            } catch (e: Exception) {
                Log.w(TAG, "cannot read $pkg: ${e.javaClass.simpleName}")
                null
            }
        }.sortedBy { it.identity.packageName }
    }

    private fun viewOf(pm: PackageManager, pkg: String, anchorClasses: Set<String>): InstalledAppView {
        val flags = PackageManager.GET_SERVICES.toLong() or PackageManager.GET_META_DATA.toLong() or
            PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        val info = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags))
        val services = info.services.orEmpty().map { s ->
            PluginServiceInfo(
                className = s.name,
                exported = s.exported,
                permission = s.permission,
                isPluginAnchor = s.name in anchorClasses,
                assetsDir = s.metaData?.getString(AgentPluginContract.META_PLUGIN_ASSETS),
            )
        }
        val dirs = services.filter { it.isPluginAnchor }.mapNotNull { it.assetsDir }.toSortedSet()
        val assets = if (dirs.isEmpty()) emptyMap() else {
            val am = context.createPackageContext(pkg, 0).assets
            dirs.associateWith { readAssets(am, it) }
        }
        return InstalledAppView(PluginIdentity(pkg, signerDigest(info), info.longVersionCode), services, assets)
    }

    private fun readAssets(am: AssetManager, dir: String): PluginAssets {
        val root = dir.trim('/')
        if (root.isEmpty() || root.split('/').any { it == ".." || it == "." }) return PluginAssets(null)
        return PluginAssets(
            pluginJson = readText(am, "$root/plugin.json"),
            mcpJson = readText(am, "$root/mcp.json"),
            skillFiles = listFiles(am, root, "skills"),
        )
    }

    private fun readText(am: AssetManager, path: String): String? = try {
        am.open(path, AssetManager.ACCESS_STREAMING).use { input ->
            val bytes = input.readNBytes(MAX_JSON_BYTES + 1)
            if (bytes.size > MAX_JSON_BYTES) {
                Log.w(TAG, "$path is larger than $MAX_JSON_BYTES bytes; treated as missing")
                null
            } else {
                String(bytes, Charsets.UTF_8)
            }
        }
    } catch (e: IOException) {
        null
    }

    /** `<root>/<sub>` 下的文件路径（相对 root，例如 `skills/notes/SKILL.md`）。AssetManager 分不清空目录和文件：没有子项的当文件。 */
    private fun listFiles(am: AssetManager, root: String, sub: String): List<String> {
        val out = ArrayList<String>()
        fun walk(rel: String, depth: Int) {
            if (out.size >= MAX_SKILL_FILES || depth > MAX_DEPTH) return
            val children = try {
                am.list("$root/$rel").orEmpty()
            } catch (e: IOException) {
                emptyArray()
            }
            if (children.isEmpty()) {
                if (rel != sub) out += rel
                return
            }
            for (c in children.sorted()) walk("$rel/$c", depth + 1)
        }
        walk(sub, 0)
        return out
    }

    companion object {
        private const val TAG = "AppPluginScanner"
        const val MAX_JSON_BYTES = 256 * 1024
        const val MAX_SKILL_FILES = 1_024
        private const val MAX_DEPTH = 6

        /** 签名摘要（见类说明第 3 条）。 */
        fun signerDigest(info: PackageInfo): String {
            val signers = info.signingInfo?.apkContentsSigners.orEmpty()
            val digests = signers.map { sha256Hex(it.toByteArray()) }.sorted()
            return when (digests.size) {
                0 -> ""
                1 -> digests[0]
                else -> sha256Hex(digests.joinToString(",").toByteArray(Charsets.UTF_8))
            }
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
