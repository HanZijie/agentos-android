package org.agentos.extensions

import kotlinx.serialization.json.JsonElement
import org.agentos.runtime.i18n.MessageRef

/** 插件包的来源，决定校验规则（docs/extensions.md 4.1、4.2）。 */
enum class PluginOrigin {
    /** 已安装 App 内嵌的插件（`assets/agent-plugin/`）：可以声明 `extensions."org.agentos".mcpServers` 的 Binder 服务。 */
    INSTALLED_APP,

    /** 用户导入的 zip 插件包：出现 `extensions."org.agentos".mcpServers` 就拒绝（Binder 端点只属于已安装的 App）。 */
    IMPORTED,
}

/**
 * 读清单需要的内容。
 *
 * @property pluginJson 根目录 `plugin.json` 的全文
 * @property mcpJson `mcp.json` 的全文；没有这个文件时为 null
 * @property skillFiles 插件包里 `skills/` 下的文件相对路径（相对插件根，例如 `skills/notes/SKILL.md`、`skills/notes/scripts/a.sh`），
 *   用来列出 Skill 目录；不读文件内容（Skill 正文与 frontmatter 归 W20）
 */
class PluginFiles(
    val pluginJson: String,
    val mcpJson: String? = null,
    val skillFiles: List<String> = emptyList(),
)

/** 读出的清单。只有校验通过（[ManifestResult.Accepted]）才会有。 */
data class PluginManifest(
    /** 插件名（已按 Agent Plugins 1.0 的规则校验：≤64、小写字母数字 `.` `-`，首尾是字母或数字，无 `--` 和 `..`）。 */
    val name: String,
    val version: String?,
    val description: String?,
    val authorName: String?,
    /** `extensions."com.openai".interface`，给插件页展示；没有时为 null。 */
    val display: PluginDisplay?,
    /** 可用的 MCP 服务器（Binder 与远端 Streamable HTTP），按声明顺序：先 plugin.json 里的，再 mcp.json 里的。 */
    val servers: List<McpServerDecl>,
    /** `skills/<目录>/SKILL.md` 的目录清单，按目录名排序。 */
    val skills: List<SkillRef>,
    /** `extensions."com.openai".hooks` 的原始 JSON（路径、路径数组、内联对象或数组）；W22 解析。没有时为 null。 */
    val hooks: JsonElement?,
    /** 这个插件里**不可用**的部分和原因，给插件页显示。插件仍然可以导入和启用，只是这些部分用不了。 */
    val unsupported: List<UnsupportedPart>,
) {
    /** 插件页上的显示名：interface.displayName，没有就用插件名。 */
    val displayName: String get() = display?.displayName ?: name
}

/** `extensions."com.openai".interface` 里 AgentOS 用到的部分。路径是相对插件根的 `./…` 路径，已检查没有跑出插件根。 */
data class PluginDisplay(
    val displayName: String?,
    val shortDescription: String?,
    val longDescription: String?,
    /** 图标：`composerIcon`，没有就用 `logo`。 */
    val iconPath: String?,
)

data class SkillRef(
    /** `skills/` 下的目录名。 */
    val directory: String,
    /** 相对插件根的 SKILL.md 路径。 */
    val path: String,
)

sealed interface McpServerDecl {
    /** 服务器名：同一个插件里唯一（plugin.json 与 mcp.json 两处合起来）。 */
    val name: String

    /** `extensions."org.agentos".mcpServers.<名>.service`：本地 App 的 Binder MCP 服务（Service 完整类名）。只有已安装 App 内嵌的插件有。 */
    data class Binder(override val name: String, val service: String) : McpServerDecl

    /**
     * `mcp.json` 里的 Streamable HTTP 服务：地址是 `https://`。
     * [headerNames] 只有请求头的名字：值（token、key）不写进插件包，用户启用时在 AgentOS 里填写。
     */
    data class StreamableHttp(override val name: String, val url: String, val headerNames: List<String>) : McpServerDecl
}

/** 清单里一处不可用的部分。 */
data class UnsupportedPart(
    val kind: UnsupportedKind,
    /** 位置，给人看：例如 `mcp.json › mcpServers.docs`。 */
    val location: String,
    /** 原因，给人看：文案 key + 参数（[ExtMessages]），界面按自己的语言渲染。 */
    val reason: MessageRef,
)

enum class UnsupportedKind {
    /** stdio 服务器：手机上不为插件提供解释器（extensions.md 12）。 */
    STDIO_SERVER,

    /** 旧 HTTP+SSE 传输的服务器。 */
    SSE_SERVER,

    /** 地址不是 `https://`（或带用户名密码、没有主机名）。 */
    INSECURE_URL,

    /** 图标路径不在插件根目录内。 */
    UNSAFE_PATH,
}

/**
 * 校验失败的一项。[code] 给程序判断；[location]（文件 + 字段路径，如 `plugin.json › name`，不是自然语言）与 [message]（文案 key + 参数，[ExtMessages]）给人看。
 */
data class ManifestError(val code: ManifestErrorCode, val location: String, val message: MessageRef)

enum class ManifestErrorCode {
    /** 不是合法的 JSON，或根不是对象。 */
    NOT_JSON,

    /** 不符合 Agent Plugins 1.0 的 schema（缺必填项、类型不对、多出不允许的字段、name 不合规…）。 */
    SCHEMA,

    /** `extensions."org.agentos".mcpServers` 的内容不合规（AgentOS 自己定义的部分）。 */
    ORG_AGENTOS,

    /** 同一个插件里两处声明了同名的服务器：整个插件校验失败。 */
    DUPLICATE_SERVER,

    /** 导入的插件包里出现 `extensions."org.agentos".mcpServers`（只允许已安装 App 内嵌的插件）。 */
    BINDER_NOT_ALLOWED,
}

sealed interface ManifestResult {
    data class Accepted(val manifest: PluginManifest) : ManifestResult

    /** 整个插件校验失败；[errors] 至少一项，已经收集了能发现的全部问题。 */
    data class Rejected(val errors: List<ManifestError>) : ManifestResult
}
