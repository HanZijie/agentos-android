package org.agentos.extensions.registry

import org.agentos.extensions.ManifestError
import org.agentos.extensions.McpServerDecl
import org.agentos.extensions.PluginManifest

/**
 * 插件身份（docs/extensions.md 4.1）：已安装 App 内嵌的插件记录为“包名 + 签名证书摘要 + versionCode”。
 *
 * @property signerDigest 签名证书摘要，Android 侧算好（建议 SHA-256 十六进制小写；这里只做字符串比较）。
 *   多签名者时由适配层按固定规则合成一个字符串（例如排序后拼接再哈希）。
 */
data class PluginIdentity(val packageName: String, val signerDigest: String, val versionCode: Long)

/**
 * 一个 App 的 Manifest 里的 Service（Android 适配层从 PackageManager 读出来）。
 *
 * @property className Service 的**完整类名**（适配层已按 Manifest 的包名规则解析好，不是 `.agent.X` 这种相对写法）。
 *   `plugin.json` 里 `org.agentos.mcpServers.<名>.service` 与它做精确比较。
 * @property exported 是否导出
 * @property permission Service 要求的权限（`android:permission`）；没有为 null
 * @property isPluginAnchor 是否带 `org.agentos.intent.action.PLUGIN` 的 intent-filter（AgentOS 发现插件的锚点）
 * @property assetsDir `<meta-data android:name="org.agentos.plugin.assets">` 的值（插件包在 assets 里的目录名）；没写为 null
 */
data class PluginServiceInfo(
    val className: String,
    val exported: Boolean,
    val permission: String?,
    val isPluginAnchor: Boolean = false,
    val assetsDir: String? = null,
)

/**
 * 适配层读到的某个 assets 目录里的插件包内容。`pluginJson` 为 null 表示这个目录下没有 `plugin.json`（或读不出来）。
 * [skillFiles] 是 `skills/` 下的文件相对路径（相对插件根）。
 */
data class PluginAssets(
    val pluginJson: String?,
    val mcpJson: String? = null,
    val skillFiles: List<String> = emptyList(),
)

/**
 * **Android 与 JVM 测试之间的接缝**：一个已安装 App 的、和插件有关的全部信息。适配层（C 的 AppPluginScanner）用
 * `queryIntentServices(PLUGIN)` 找到带 [PluginServiceInfo.isPluginAnchor] 的 App，再按 `getPackageInfo(GET_SERVICES | 签名)`
 * 填这个对象；`createPackageContext(包名, 0).assets` 读 [assets]。**这一步不 bind。** 逻辑都在 [PluginScanLogic]，不依赖 Android。
 *
 * @property services 这个包的 Service（至少包含全部 PLUGIN 锚点；`plugin.json` 里引用的 Service 如果不在这里，就是“不属于本包”）
 * @property assets assets 目录名 → 内容；至少包含各锚点 `assetsDir` 指向的目录
 */
data class InstalledAppView(
    val identity: PluginIdentity,
    val services: List<PluginServiceInfo>,
    val assets: Map<String, PluginAssets> = emptyMap(),
)

/** 一个插件的状态（给插件页）。 */
enum class PluginStatus {
    /** 可用：清单合格，至少有一样能用的东西（或没声明服务器）。是否启用由用户策略（ApprovalPolicy 的 plugin 级 enabled）决定。 */
    READY,

    /** 不可用：原因在 [PluginRecord.problems]。保留在注册表里，插件页要显示。 */
    UNAVAILABLE,

    /** 签名变了：已停用，需要用户重新确认（[PluginScanLogic.confirmSignature]）；确认之前没有任何服务器可用。 */
    SIGNATURE_CHANGED,

    /**
     * 没有这个 App 的签名记录（持久化的记忆丢失，[PluginScanLogic.scan] 的 `previous == null`）：无法判断签名有没有变过，
     * 一律当作没确认过——已停用，需要用户重新确认；确认之前没有任何服务器可用。自带插件不受影响。
     */
    SIGNATURE_UNCONFIRMED,
}

/** 为什么是 [PluginStatus.UNAVAILABLE]。 */
enum class UnavailableReason {
    /** 锚点 Service 没有 `org.agentos.plugin.assets`，或那个目录里没有 plugin.json。 */
    ASSETS_MISSING,

    /** 清单没通过校验（[PluginRecord.manifestErrors]）。 */
    MANIFEST_REJECTED,

    /** 声明了服务器，但没有一个能用，也没有 Skills、Hooks：整个插件没有可用的东西。 */
    NO_USABLE_SERVER,

    /** 插件名已被另一个插件占用，或是保留的名字。 */
    NAME_CONFLICT,
}

/** 为什么一个 Binder 服务器被拒绝（docs/extensions.md 4.1 的三项检查）。 */
enum class ServerRejection {
    /** `service` 不是这个包的 Service。 */
    NOT_IN_PACKAGE,

    /** Service 没有导出。 */
    NOT_EXPORTED,

    /** Service 没有要求 `org.agentos.permission.BIND_MCP_SERVICE`（要求了别的权限也算）。 */
    MISSING_PERMISSION,
}

data class RejectedServer(val name: String, val service: String, val reason: ServerRejection)

/** 给插件页显示的一条问题（中文，已带位置）。 */
data class PluginProblem(val code: String, val message: String)

/**
 * 注册表里的一个插件。**由扫描结果推导出来，不持久化**（持久化的只有 [PersistedPlugin]）。
 *
 * @property id 稳定的键：`<包名>/<assets 目录>`
 * @property name 插件名（`plugin.json` 的 name）；清单读不出来时为 null
 * @property identity 当前观察到的身份（包名、当前签名、versionCode）
 * @property trustedSigner 被信任的签名。与 `identity.signerDigest` 不同就是 [PluginStatus.SIGNATURE_CHANGED]
 * @property builtin AgentOS 自带插件：默认启用，签名不做重新确认
 * @property manifest 读出的清单（[PluginStatus.UNAVAILABLE] 里清单被拒绝或没有时为 null）
 * @property servers 能用的 MCP 服务器：通过检查的 Binder 服务器，加上 mcp.json 里的 https 服务器。
 *   **只有 [PluginStatus.READY] 时才可以使用**，用 [activeServers]
 * @property rejectedServers 被拒绝的 Binder 服务器与原因
 * @property manifestErrors 清单校验失败的全部错误
 * @property skillFiles 插件包里 `skills/` 下的全部文件（相对插件根的路径）。Skill 只能读这里列出的文件（ExtensionSkillPort）
 * @property problems 给插件页显示的全部问题（不可用原因、被拒绝的服务器、清单里“不支持的部分”）
 */
data class PluginRecord(
    val id: String,
    val name: String?,
    val identity: PluginIdentity,
    val trustedSigner: String,
    val builtin: Boolean,
    val status: PluginStatus,
    val unavailableReason: UnavailableReason? = null,
    val manifest: PluginManifest? = null,
    val servers: List<McpServerDecl> = emptyList(),
    val rejectedServers: List<RejectedServer> = emptyList(),
    val manifestErrors: List<ManifestError> = emptyList(),
    val problems: List<PluginProblem> = emptyList(),
    val skillFiles: List<String> = emptyList(),
) {
    val activeServers: List<McpServerDecl> get() = if (status == PluginStatus.READY) servers else emptyList()
}

/** 全部插件，按 id 排序。 */
data class PluginRegistry(val plugins: List<PluginRecord> = emptyList()) {
    operator fun get(id: String): PluginRecord? = plugins.firstOrNull { it.id == id }

    fun byName(name: String): PluginRecord? = plugins.firstOrNull { it.name == name && it.status != PluginStatus.UNAVAILABLE }
}

/**
 * 扫描产生的变化。给 Extension Host 处理：[Revoke] 必须执行（关闭这个插件已有的 MCP 连接、作废授权）。
 */
sealed interface RegistryEvent {
    val pluginId: String

    /** 第一次看到这个插件。 */
    data class Added(override val pluginId: String, val name: String?) : RegistryEvent

    /** versionCode 变了、签名没变：重新读了清单，用户策略保留。 */
    data class Updated(override val pluginId: String, val fromVersionCode: Long, val toVersionCode: Long) : RegistryEvent

    /** App 卸载：插件已从注册表移除，用户策略已清掉。 */
    data class Removed(override val pluginId: String, val name: String?) : RegistryEvent

    /** 签名变了：状态变成 [PluginStatus.SIGNATURE_CHANGED]，策略已清空并停用。 */
    data class SignatureChanged(override val pluginId: String, val name: String?, val oldSigner: String, val newSigner: String) : RegistryEvent

    /** 升级后插件名变了：旧名字的策略已清掉，新名字按“第三方默认关闭”处理。 */
    data class Renamed(override val pluginId: String, val oldName: String, val newName: String?) : RegistryEvent

    /**
     * 持久化的记忆丢失（`scan(previous = null)`）：全部第三方插件变成 [PluginStatus.SIGNATURE_UNCONFIRMED]，策略清空并停用。
     * 每个第三方插件另有一条 [Revoke]（reason = [RevokeReason.MEMORY_LOST]）。插件页要显示原因。[pluginId] 为空串（不针对某个插件）。
     */
    data object MemoryLost : RegistryEvent {
        override val pluginId: String = ""
    }

    /** **明确的作废事件**：关闭 [pluginId] 已有的全部 MCP 连接，撤销它的全部授权（[name] 是策略里用的插件名）。 */
    data class Revoke(override val pluginId: String, val name: String?, val reason: RevokeReason) : RegistryEvent
}

enum class RevokeReason { SIGNATURE_CHANGED, UNINSTALLED, RENAMED, MEMORY_LOST }

/** 持久化的最少信息：扫描要和“上一次”比较的东西（其余每次从已安装的 App 重新读）。 */
data class PersistedPlugin(
    val id: String,
    val packageName: String,
    val name: String?,
    /** 被信任的签名：第一次看到时记下，用户确认新签名后更新；[UNCONFIRMED]（空串）表示记忆丢失后还没有确认。 */
    val trustedSigner: String,
    /** 上一次扫描观察到的签名：用来只在**变化的那一次**发出 [RegistryEvent.SignatureChanged]。 */
    val observedSigner: String,
    val versionCode: Long,
) {
    companion object {
        /** [trustedSigner] 的特殊值：记忆丢失后重建的记录，签名还没有被用户确认过。 */
        const val UNCONFIRMED = ""
    }
}

/** 扫描结果。 */
data class ScanResult(
    val registry: PluginRegistry,
    /** 要保存的记忆（[PersistedRegistry.toJson]）。 */
    val persisted: PersistedRegistry,
    /** 更新后的用户策略（第三方默认关闭、签名变化后清空并停用、卸载后清掉）；调用方交给 ApprovalStore 保存。 */
    val policy: org.agentos.runtime.broker.ApprovalPolicy,
    val events: List<RegistryEvent>,
)
