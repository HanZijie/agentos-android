package org.agentos.extensions.registry

import org.agentos.extensions.ManifestReader
import org.agentos.extensions.ManifestResult
import org.agentos.extensions.McpServerDecl
import org.agentos.extensions.PluginFiles
import org.agentos.extensions.PluginOrigin
import org.agentos.extensions.UnsupportedKind
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.broker.PolicyScope

/**
 * 插件注册表的扫描逻辑（docs/extensions.md 4.1、E1、E6）：**纯函数，不依赖 Android**。
 * Android 适配层（AppPluginScanner）只把 PackageManager 读到的东西填进 [InstalledAppView]，调用 [scan]，
 * 保存 [ScanResult.persisted] 和 [ScanResult.policy]，并执行 [ScanResult.events]（尤其是 [RegistryEvent.Revoke]）。
 *
 * ## 规则
 * **一个 assets 目录 = 一个插件**（id `<包名>/<目录>`）。每个插件：
 * 1. 清单用 [ManifestReader]（[PluginOrigin.INSTALLED_APP]）读；没有 assets 目录或 plugin.json → 不可用（ASSETS_MISSING）；
 *    清单被拒绝 → 不可用（MANIFEST_REJECTED，带全部错误）。
 * 2. `extensions."org.agentos".mcpServers` 里的每个 Binder 服务器必须：属于本包（在 [InstalledAppView.services] 里）、已导出、
 *    要求 [BIND_PERMISSION]。**任一项不满足只拒绝这个服务器**（[PluginRecord.rejectedServers]，带原因），其余服务器照常可用。
 *    取舍：如果插件**声明了服务器、却一个都不能用，而且也没有 Skills 和 Hooks**，整个插件标为不可用（NO_USABLE_SERVER）——
 *    它对用户没有任何用处，留在“可用”里只会让人困惑。只有 Skills / Hooks、不声明服务器的插件是正常的，保持可用。
 * 3. 签名：第一次看到时记为被信任的签名。之后签名摘要变化 → 状态 [PluginStatus.SIGNATURE_CHANGED]（没有任何服务器可用），
 *    **只在变化的那一次**发出 [RegistryEvent.SignatureChanged] 和 [RegistryEvent.Revoke]（关闭已有连接、作废授权），
 *    并把这个插件的用户策略清空、plugin 级 enabled 设为 false。用户确认之后（[confirmSignature]）恢复为可用，
 *    但**仍然是停用的**，要用户再启用一次。签名变回被信任的签名则自动恢复可用（仍停用）。
 * 4. 升级（versionCode 变了、签名没变）：重新读清单，**保留用户策略**，发出 [RegistryEvent.Updated]。
 *    如果插件名变了：旧名字的策略清掉，发出 [RegistryEvent.Renamed] 和 Revoke，新名字按第三方默认处理。
 * 5. 卸载（上次有、这次没有）：从注册表移除，策略清掉，发出 [RegistryEvent.Removed] 和 Revoke。
 * 6. **第三方插件默认关闭**：没有明确写过 plugin 级 enabled 的第三方插件，策略里写 enabled=false。
 *    AgentOS 自带插件（[builtinPackages]）例外：默认启用，不做签名重新确认。
 * 7. 插件名必须唯一：同名时自带插件优先，其次是原来就占着这个名字的，再其次是 id 较小的；其余标为不可用（NAME_CONFLICT）。
 *    `user.` 开头的名字保留给用户配置的第三方 MCP（4.3），也标为 NAME_CONFLICT。
 *
 * 8. **记忆丢失**（`previous == null`）：无法判断签名有没有变过，所有第三方插件一律当作没确认过——
 *    状态 [PluginStatus.SIGNATURE_UNCONFIRMED]（没有可用的服务器），策略清空并把 plugin 级 enabled 设为 false，
 *    发出一条 [RegistryEvent.MemoryLost] 和每个第三方插件的 [RegistryEvent.Revoke]（[RevokeReason.MEMORY_LOST]）。
 *    新的记忆里它们的被信任签名是 [PersistedPlugin.UNCONFIRMED]，用户确认（[confirmSignature]）之后恢复可用，仍然停用。
 *    自带插件不受影响；清单被拒绝、assets 缺失的插件仍然是不可用（原因更根本）。
 *
 * 不可用的插件**留在注册表里**（带原因），插件页要显示，不静默丢弃。
 */
object PluginScanLogic {
    const val BIND_PERMISSION = "org.agentos.permission.BIND_MCP_SERVICE"
    const val RESERVED_NAME_PREFIX = "user."

    /**
     * @param views 当前已安装的、带 PLUGIN 锚点 Service 的 App
     * @param previous 上一次保存的记忆。**读不出来（文件丢了、版本缺失、结构坏）时传 null**，不要传空记忆：
     *   空记忆表示“没装过任何插件”，会把当前的签名当作可信；null 表示“记忆丢失”，此时所有第三方插件按
     *   [PluginStatus.SIGNATURE_UNCONFIRMED] 处理（见类说明第 8 条）。调用方不需要自己想该怎么办。
     * @param policy 当前用户策略
     * @param builtinPackages AgentOS 自带插件所在的包（通常只有 AgentOS App 自己）
     */
    fun scan(
        views: List<InstalledAppView>,
        previous: PersistedRegistry?,
        policy: ApprovalPolicy,
        builtinPackages: Set<String> = emptySet(),
    ): ScanResult {
        val memoryLost = previous == null
        val prevById = previous?.plugins.orEmpty().associateBy { it.id }
        var drafts = views.sortedBy { it.identity.packageName }.flatMap { view -> draftsOf(view, prevById, builtinPackages, memoryLost) }
        drafts = resolveNames(drafts, prevById)

        var newPolicy = policy
        val events = ArrayList<RegistryEvent>()
        val persisted = ArrayList<PersistedPlugin>()
        val records = ArrayList<PluginRecord>()
        if (memoryLost) events += RegistryEvent.MemoryLost

        for (d in drafts.sortedBy { it.record.id }) {
            val rec = d.record
            val prior = prevById[rec.id]
            val name = rec.name
            val eligible = d.ownsName // 清单合格、名字归它
            val signer = rec.identity.signerDigest

            if (memoryLost) {
                // 没有任何记录可比：第三方插件一律未确认，策略清空并停用；不发 Added（它们不是新装的）
                if (!rec.builtin) {
                    events += RegistryEvent.Revoke(rec.id, name, RevokeReason.MEMORY_LOST)
                    if (name != null) newPolicy = newPolicy.cleared(PolicyScope.Plugin(name)).withEnabled(PolicyScope.Plugin(name), false)
                }
            } else if (prior == null) {
                events += RegistryEvent.Added(rec.id, name)
            } else {
                if (!rec.builtin && rec.trustedSigner != PersistedPlugin.UNCONFIRMED && signer != rec.trustedSigner && signer != prior.observedSigner) {
                    val who = name ?: prior.name
                    events += RegistryEvent.SignatureChanged(rec.id, who, prior.observedSigner, signer)
                    events += RegistryEvent.Revoke(rec.id, who, RevokeReason.SIGNATURE_CHANGED)
                    if (who != null) newPolicy = newPolicy.cleared(PolicyScope.Plugin(who)).withEnabled(PolicyScope.Plugin(who), false)
                } else if (rec.identity.versionCode != prior.versionCode && signer == prior.observedSigner) {
                    events += RegistryEvent.Updated(rec.id, prior.versionCode, rec.identity.versionCode)
                }
                if (name != null && prior.name != null && name != prior.name && eligible) {
                    events += RegistryEvent.Renamed(rec.id, prior.name, name)
                    events += RegistryEvent.Revoke(rec.id, prior.name, RevokeReason.RENAMED)
                    newPolicy = newPolicy.cleared(PolicyScope.Plugin(prior.name))
                }
            }
            if (eligible && name != null && !rec.builtin && newPolicy.entryOf(PolicyScope.Plugin(name)).enabled == null) {
                newPolicy = newPolicy.withEnabled(PolicyScope.Plugin(name), false)
            }
            records += rec
            persisted += PersistedPlugin(rec.id, rec.identity.packageName, name ?: prior?.name, rec.trustedSigner, signer, rec.identity.versionCode)
        }

        val currentIds = drafts.map { it.record.id }.toSet()
        val namesInUse = drafts.filter { it.ownsName }.mapNotNull { it.record.name }.toSet()
        for (gone in previous?.plugins.orEmpty().filter { it.id !in currentIds }.sortedBy { it.id }) {
            events += RegistryEvent.Removed(gone.id, gone.name)
            events += RegistryEvent.Revoke(gone.id, gone.name, RevokeReason.UNINSTALLED)
            if (gone.name != null && gone.name !in namesInUse) newPolicy = newPolicy.cleared(PolicyScope.Plugin(gone.name))
        }

        return ScanResult(PluginRegistry(records), PersistedRegistry(persisted), newPolicy, events)
    }

    /**
     * 用户确认了 [pluginId] 的新签名：把它记为被信任的签名。返回新的记忆；下一次扫描时状态恢复为 [PluginStatus.READY]。
     * 策略不动：插件仍然是停用的（签名变化时已经写了 enabled=false），要用户再启用。
     */
    fun confirmSignature(previous: PersistedRegistry, pluginId: String): PersistedRegistry =
        PersistedRegistry(previous.plugins.map { if (it.id == pluginId) it.copy(trustedSigner = it.observedSigner) else it })

    // ------------------------------------------------------------------ 内部

    private class Draft(val record: PluginRecord, val ownsName: Boolean)

    private fun draftsOf(view: InstalledAppView, prev: Map<String, PersistedPlugin>, builtinPackages: Set<String>, memoryLost: Boolean): List<Draft> {
        val anchors = view.services.filter { it.isPluginAnchor }
        if (anchors.isEmpty()) return emptyList()
        val dirs = anchors.mapNotNull { it.assetsDir?.takeIf(String::isNotEmpty) }.distinct().sorted()
        val packageName = view.identity.packageName
        val builtin = packageName in builtinPackages
        if (dirs.isEmpty()) {
            val id = "$packageName/"
            return listOf(
                Draft(
                    base(id, null, view, prev[id], builtin, memoryLost).copy(
                        status = PluginStatus.UNAVAILABLE,
                        unavailableReason = UnavailableReason.ASSETS_MISSING,
                        problems = listOf(PluginProblem("assets_missing", "插件的 Service 没有声明 org.agentos.plugin.assets，找不到插件包")),
                    ),
                    ownsName = false,
                ),
            )
        }
        return dirs.map { dir -> draftOf(view, dir, prev["$packageName/$dir"], builtin, memoryLost) }
    }

    private fun base(id: String, name: String?, view: InstalledAppView, prior: PersistedPlugin?, builtin: Boolean, memoryLost: Boolean): PluginRecord {
        val signer = view.identity.signerDigest
        return PluginRecord(
            id = id,
            name = name,
            identity = view.identity,
            trustedSigner = when {
                builtin -> signer
                memoryLost -> PersistedPlugin.UNCONFIRMED
                else -> prior?.trustedSigner ?: signer
            },
            builtin = builtin,
            status = PluginStatus.READY,
        )
    }

    private fun draftOf(view: InstalledAppView, dir: String, prior: PersistedPlugin?, builtin: Boolean, memoryLost: Boolean): Draft {
        val id = "${view.identity.packageName}/$dir"
        val assets = view.assets[dir]
        val skeleton = base(id, null, view, prior, builtin, memoryLost)
        if (assets?.pluginJson == null) {
            return Draft(
                skeleton.copy(
                    status = PluginStatus.UNAVAILABLE,
                    unavailableReason = UnavailableReason.ASSETS_MISSING,
                    problems = listOf(PluginProblem("assets_missing", "assets/$dir 里没有 plugin.json")),
                ),
                ownsName = false,
            )
        }
        val result = ManifestReader.read(PluginFiles(assets.pluginJson, assets.mcpJson, assets.skillFiles), PluginOrigin.INSTALLED_APP)
        if (result is ManifestResult.Rejected) {
            return Draft(
                skeleton.copy(
                    status = PluginStatus.UNAVAILABLE,
                    unavailableReason = UnavailableReason.MANIFEST_REJECTED,
                    manifestErrors = result.errors,
                    problems = result.errors.map { PluginProblem(it.code.name.lowercase(), "${it.location}：${it.message}") },
                ),
                ownsName = false,
            )
        }
        val manifest = (result as ManifestResult.Accepted).manifest
        val usable = ArrayList<McpServerDecl>()
        val rejected = ArrayList<RejectedServer>()
        for (server in manifest.servers) {
            if (server !is McpServerDecl.Binder) {
                usable += server
                continue
            }
            val svc = view.services.firstOrNull { it.className == server.service }
            val why = when {
                svc == null -> ServerRejection.NOT_IN_PACKAGE
                !svc.exported -> ServerRejection.NOT_EXPORTED
                svc.permission != BIND_PERMISSION -> ServerRejection.MISSING_PERMISSION
                else -> null
            }
            if (why == null) usable += server else rejected += RejectedServer(server.name, server.service, why)
        }
        val problems = ArrayList<PluginProblem>()
        for (r in rejected) problems += PluginProblem("server_rejected", "服务器 ${r.name}（${r.service}）被拒绝：${rejectionText(r.reason)}")
        for (u in manifest.unsupported) problems += PluginProblem("unsupported_${u.kind.name.lowercase()}", "${u.location}：${u.reason}")

        val declaredServers = manifest.servers.size + manifest.unsupported.count {
            it.kind == UnsupportedKind.STDIO_SERVER || it.kind == UnsupportedKind.SSE_SERVER || it.kind == UnsupportedKind.INSECURE_URL
        }
        val nothingUsable = declaredServers > 0 && usable.isEmpty() && manifest.skills.isEmpty() && manifest.hooks == null

        val unconfirmed = !builtin && skeleton.trustedSigner == PersistedPlugin.UNCONFIRMED
        val signerChanged = !builtin && !unconfirmed && view.identity.signerDigest != skeleton.trustedSigner
        val status = when {
            nothingUsable -> PluginStatus.UNAVAILABLE
            unconfirmed -> PluginStatus.SIGNATURE_UNCONFIRMED
            signerChanged -> PluginStatus.SIGNATURE_CHANGED
            else -> PluginStatus.READY
        }
        if (signerChanged) problems += PluginProblem("signature_changed", "这个 App 的签名变了：已停用，需要重新确认后才能启用")
        if (unconfirmed) problems += PluginProblem("signature_unconfirmed", "没有这个 App 的签名记录（记忆丢失）：已停用，需要重新确认后才能启用")
        if (nothingUsable) problems += PluginProblem("no_usable_server", "声明的服务器都不能用，插件没有可用的内容")

        return Draft(
            skeleton.copy(
                name = manifest.name,
                status = status,
                unavailableReason = if (nothingUsable) UnavailableReason.NO_USABLE_SERVER else null,
                manifest = manifest,
                servers = usable,
                rejectedServers = rejected,
                problems = problems,
                skillFiles = assets.skillFiles,
            ),
            ownsName = true,
        )
    }

    /** 名字唯一：同名时自带插件优先，其次原来占着名字的，再其次 id 较小的；`user.` 前缀保留。 */
    private fun resolveNames(drafts: List<Draft>, prev: Map<String, PersistedPlugin>): List<Draft> {
        val losers = HashSet<String>()
        val named = drafts.filter { it.ownsName && it.record.name != null }
        for (d in named) {
            val n = d.record.name!!
            if (n.startsWith(RESERVED_NAME_PREFIX) && !d.record.builtin) losers += d.record.id
        }
        for ((name, group) in named.filter { it.record.id !in losers }.groupBy { it.record.name!! }) {
            if (group.size < 2) continue
            val winner = group.sortedWith(
                compareByDescending<Draft> { it.record.builtin }
                    .thenByDescending { prev[it.record.id]?.name == name }
                    .thenBy { it.record.id },
            ).first()
            for (d in group) if (d !== winner) losers += d.record.id
        }
        return drafts.map { d ->
            if (d.record.id !in losers) d else Draft(
                d.record.copy(
                    status = PluginStatus.UNAVAILABLE,
                    unavailableReason = UnavailableReason.NAME_CONFLICT,
                    servers = emptyList(),
                    problems = d.record.problems + PluginProblem("name_conflict", "插件名 \"${d.record.name}\" 已被占用或是保留的名字"),
                ),
                ownsName = false,
            )
        }
    }

    private fun rejectionText(r: ServerRejection) = when (r) {
        ServerRejection.NOT_IN_PACKAGE -> "这个 Service 不属于这个 App"
        ServerRejection.NOT_EXPORTED -> "这个 Service 没有导出"
        ServerRejection.MISSING_PERMISSION -> "这个 Service 没有要求 $BIND_PERMISSION"
    }
}
