package org.agentos.extensions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.agentos.runtime.i18n.MessageRef
import java.net.URI

/**
 * 读插件清单（docs/extensions.md 3、4）：`plugin.json`、可选的 `mcp.json`，加上 `skills/` 目录清单。
 *
 * **校验按 Agent Plugins 1.0 的 schema**（`core/protocol/agent-plugins-1.0/` 里固定的副本，运行时不联网；
 * 这里的规则是对它的手写实现，ManifestReaderSchemaTest 逐项和那份 schema 文件核对）：
 * - `plugin.json`：`$schema`、`name` 必填；`name` 最长 64，只含小写字母、数字、`.`、`-`，首尾是字母或数字，没有 `--` 和 `..`；
 *   顶层只允许 schema 列出的字段；`extensions` 的每个分区必须是对象；
 * - `mcp.json`：`$schema`、`mcpServers` 必填；每个服务器是 stdio、streamable-http、sse 三种之一，字段与类型按 schema。
 *
 * **分区内容**（schema 不规定，AgentOS 自己读）：
 * - `extensions."org.agentos".mcpServers.<名>.service`：Binder 服务，只定义 `service` 字段（其他字段忽略，向前兼容）；
 *   导入的插件包里出现 `mcpServers` 就拒绝（[PluginOrigin.IMPORTED]）；
 * - `extensions."com.openai"`：读 `interface`（显示名、简介、图标）和 `hooks`（原样保留给 W22）；忽略其余（包括 `apps`）。
 *
 * **不可用的部分不拒绝整个插件**：mcp.json 里的 stdio、sse 服务器、非 `https://` 的地址、跑出插件根的图标路径，
 * 记在 [PluginManifest.unsupported]（位置 + 原因）里，插件页据此显示。
 *
 * **整个插件校验失败**（[ManifestResult.Rejected]）：JSON 不合法、不符合 schema、`org.agentos` 部分不合规、
 * 两处声明了同名服务器、导入的插件包里有 Binder 服务器。一次收集全部能发现的问题。
 *
 * **错误信息不是文字**：每一项是 [ExtMessages] 的 key + 参数（[MessageRef]），位置（`plugin.json › name`）是文件和字段路径；
 * 用哪种语言显示由界面定（核心层是纯 Kotlin / JVM，用不了 Android 资源）。参数里可能有第三方文字（服务器名、图标路径…），界面渲染前负责清理。
 *
 * 不做的事（归调用方）：Service 是否属于这个 App、是否导出、是否要求 BIND_MCP_SERVICE（Android 侧，W14 的 AppPluginScanner）；
 * 文件系统层面的检查（符号链接、`..`、大小、数量，PackageValidator，W18）；`SKILL.md` 的内容；Hook 的解析（W22）。
 */
object ManifestReader {
    const val PLUGIN_SCHEMA_ID = "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json"
    const val MCP_SCHEMA_ID = "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json"
    const val MAX_NAME_LENGTH = 64

    /** schema 里 `name` 的 pattern（去掉首尾锚点，用 [Regex.matchEntire] 整串匹配；ECMA 的 `$` 只匹配串尾）。 */
    internal val NAME_REGEX = Regex("(?!.*(?:--|\\.\\.))[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")

    /** schema 里 stdio 的 `cwd` 的 pattern。 */
    internal val CWD_REGEX = Regex("^(?:\\./|\\$\\{PLUGIN_ROOT\\}(?:/|\\z)|\\$\\{PLUGIN_DATA\\}(?:/|\\z)).*", RegexOption.DOT_MATCHES_ALL)

    internal val PLUGIN_KEYS = setOf("\$schema", "name", "version", "description", "author", "homepage", "repository", "license", "keywords", "extensions")
    private val AUTHOR_KEYS = setOf("name", "email", "url")
    private val STDIO_KEYS = setOf("type", "command", "args", "env", "cwd")
    private val REMOTE_KEYS = setOf("type", "url", "headers")
    private val RESERVED_ENV = setOf("PLUGIN_ROOT", "PLUGIN_DATA")

    private const val PLUGIN_FILE = "plugin.json"
    private const val MCP_FILE = "mcp.json"

    fun read(files: PluginFiles, origin: PluginOrigin): ManifestResult {
        val errors = ArrayList<ManifestError>()
        val unsupported = ArrayList<UnsupportedPart>()

        val plugin = parseObject(files.pluginJson, PLUGIN_FILE, errors)
        var name: String? = null
        var version: String? = null
        var description: String? = null
        var authorName: String? = null
        var display: PluginDisplay? = null
        var hooks: JsonElement? = null
        val binder = ArrayList<McpServerDecl.Binder>()

        if (plugin != null) {
            name = checkPlugin(plugin, errors)
            version = plugin.string("version")
            description = plugin.string("description")
            authorName = (plugin["author"] as? JsonObject)?.string("name")
            val extensions = plugin["extensions"] as? JsonObject
            if (extensions != null) {
                readOrgAgentos(extensions["org.agentos"], origin, binder, errors)
                val openai = extensions["com.openai"] as? JsonObject
                if (openai != null) {
                    display = readInterface(openai["interface"], unsupported)
                    hooks = openai["hooks"]
                }
            }
        }

        val remote = ArrayList<McpServerDecl>()
        val remoteNames = ArrayList<String>()
        if (files.mcpJson != null) {
            val mcp = parseObject(files.mcpJson, MCP_FILE, errors)
            if (mcp != null) readMcpJson(mcp, remote, remoteNames, unsupported, errors)
        }

        val seen = HashSet<String>()
        for (server in binder.map { it.name } + remoteNames) {
            if (!seen.add(server)) {
                errors += ManifestError(
                    ManifestErrorCode.DUPLICATE_SERVER,
                    "$PLUGIN_FILE › extensions.org.agentos.mcpServers / $MCP_FILE › mcpServers.$server",
                    msg(ExtMessages.DUPLICATE_SERVER, server),
                )
            }
        }

        if (errors.isNotEmpty() || name == null) return ManifestResult.Rejected(errors)
        return ManifestResult.Accepted(
            PluginManifest(
                name = name,
                version = version,
                description = description,
                authorName = authorName,
                display = display,
                servers = binder + remote,
                skills = skillRefs(files.skillFiles),
                hooks = hooks,
                unsupported = unsupported,
            ),
        )
    }

    // ------------------------------------------------------------------ plugin.json

    private fun checkPlugin(plugin: JsonObject, errors: MutableList<ManifestError>): String? {
        val where = PLUGIN_FILE
        for (key in plugin.keys - PLUGIN_KEYS) errors += schema("$where › $key", msg(ExtMessages.PLUGIN_FIELD_NOT_ALLOWED))
        when (val s = plugin["\$schema"]) {
            null -> errors += schema("$where › \$schema", msg(ExtMessages.MISSING_REQUIRED, "\$schema"))
            else -> if (s.stringOrNull() != PLUGIN_SCHEMA_ID) errors += schema("$where › \$schema", msg(ExtMessages.MUST_BE_VALUE, PLUGIN_SCHEMA_ID))
        }
        var name: String? = null
        when (val n = plugin["name"]) {
            null -> errors += schema("$where › name", msg(ExtMessages.MISSING_REQUIRED, "name"))
            else -> {
                val text = n.stringOrNull()
                when {
                    text == null -> errors += schema("$where › name", msg(ExtMessages.MUST_BE_STRING))
                    text.isEmpty() || text.length > MAX_NAME_LENGTH -> errors += schema("$where › name", msg(ExtMessages.NAME_LENGTH, MAX_NAME_LENGTH.toString()))
                    NAME_REGEX.matchEntire(text) == null -> errors += schema("$where › name", msg(ExtMessages.NAME_PATTERN))
                    else -> name = text
                }
            }
        }
        for (key in listOf("version", "description", "homepage", "repository", "license")) {
            val v = plugin[key] ?: continue
            if (v.stringOrNull() == null) errors += schema("$where › $key", msg(ExtMessages.MUST_BE_STRING))
        }
        plugin["author"]?.let { author ->
            if (author !is JsonObject) {
                errors += schema("$where › author", msg(ExtMessages.MUST_BE_OBJECT))
            } else {
                for (key in author.keys - AUTHOR_KEYS) errors += schema("$where › author.$key", msg(ExtMessages.FIELD_NOT_ALLOWED))
                for (key in AUTHOR_KEYS) author[key]?.let { if (it.stringOrNull() == null) errors += schema("$where › author.$key", msg(ExtMessages.MUST_BE_STRING)) }
            }
        }
        plugin["keywords"]?.let { keywords ->
            if (keywords !is JsonArray || keywords.any { it.stringOrNull() == null }) errors += schema("$where › keywords", msg(ExtMessages.MUST_BE_STRING_ARRAY))
        }
        plugin["extensions"]?.let { extensions ->
            if (extensions !is JsonObject) {
                errors += schema("$where › extensions", msg(ExtMessages.MUST_BE_OBJECT))
            } else {
                for ((key, value) in extensions) if (value !is JsonObject) errors += schema("$where › extensions.$key", msg(ExtMessages.SECTION_MUST_BE_OBJECT))
            }
        }
        return name
    }

    /** `extensions."org.agentos"`：首版只定义 mcpServers（Binder 服务）。 */
    private fun readOrgAgentos(
        section: JsonElement?,
        origin: PluginOrigin,
        out: MutableList<McpServerDecl.Binder>,
        errors: MutableList<ManifestError>,
    ) {
        val org = section as? JsonObject ?: return
        val servers = org["mcpServers"] ?: return
        val where = "$PLUGIN_FILE › extensions.org.agentos.mcpServers"
        if (origin == PluginOrigin.IMPORTED) {
            errors += ManifestError(ManifestErrorCode.BINDER_NOT_ALLOWED, where, msg(ExtMessages.BINDER_NOT_ALLOWED))
            return
        }
        if (servers !is JsonObject) {
            errors += ManifestError(ManifestErrorCode.ORG_AGENTOS, where, msg(ExtMessages.ORG_SERVERS_SHAPE))
            return
        }
        for ((serverName, decl) in servers) {
            val at = "$where.$serverName"
            val service = (decl as? JsonObject)?.get("service")?.stringOrNull()
            when {
                serverName.isEmpty() -> errors += ManifestError(ManifestErrorCode.ORG_AGENTOS, where, msg(ExtMessages.SERVER_NAME_EMPTY))
                decl !is JsonObject -> errors += ManifestError(ManifestErrorCode.ORG_AGENTOS, at, msg(ExtMessages.ORG_SERVER_SHAPE))
                service.isNullOrBlank() || service.any { it.isWhitespace() } ->
                    errors += ManifestError(ManifestErrorCode.ORG_AGENTOS, "$at.service", msg(ExtMessages.SERVICE_CLASS_NAME))
                else -> out += McpServerDecl.Binder(serverName, service)
            }
        }
    }

    /** `extensions."com.openai".interface`：显示名、简介、图标。这个分区的内容 schema 不规定，类型不对的字段忽略。 */
    private fun readInterface(element: JsonElement?, unsupported: MutableList<UnsupportedPart>): PluginDisplay? {
        val ui = element as? JsonObject ?: return null
        val rawIcon = ui.string("composerIcon") ?: ui.string("logo")
        var icon: String? = null
        if (rawIcon != null) {
            if (safeRelativePath(rawIcon)) {
                icon = rawIcon
            } else {
                unsupported += UnsupportedPart(UnsupportedKind.UNSAFE_PATH, "$PLUGIN_FILE › extensions.com.openai.interface", msg(ExtMessages.ICON_PATH_UNSAFE, rawIcon))
            }
        }
        return PluginDisplay(ui.string("displayName"), ui.string("shortDescription"), ui.string("longDescription"), icon)
    }

    // ------------------------------------------------------------------ mcp.json

    private fun readMcpJson(
        mcp: JsonObject,
        servers: MutableList<McpServerDecl>,
        names: MutableList<String>,
        unsupported: MutableList<UnsupportedPart>,
        errors: MutableList<ManifestError>,
    ) {
        val where = MCP_FILE
        for (key in mcp.keys - setOf("\$schema", "mcpServers")) errors += schema("$where › $key", msg(ExtMessages.FIELD_NOT_ALLOWED))
        when (val s = mcp["\$schema"]) {
            null -> errors += schema("$where › \$schema", msg(ExtMessages.MISSING_REQUIRED, "\$schema"))
            else -> if (s.stringOrNull() != MCP_SCHEMA_ID) errors += schema("$where › \$schema", msg(ExtMessages.MUST_BE_VALUE, MCP_SCHEMA_ID))
        }
        val declared = mcp["mcpServers"]
        if (declared == null) {
            errors += schema("$where › mcpServers", msg(ExtMessages.MISSING_REQUIRED, "mcpServers"))
            return
        }
        if (declared !is JsonObject) {
            errors += schema("$where › mcpServers", msg(ExtMessages.MUST_BE_OBJECT))
            return
        }
        for ((serverName, decl) in declared) {
            val at = "$where › mcpServers.$serverName"
            names += serverName
            if (decl !is JsonObject) {
                errors += schema(at, msg(ExtMessages.MUST_BE_OBJECT))
                continue
            }
            when (val type = decl["type"]?.stringOrNull()) {
                "stdio" -> if (checkStdio(decl, at, errors)) {
                    unsupported += UnsupportedPart(UnsupportedKind.STDIO_SERVER, at, msg(ExtMessages.STDIO_UNSUPPORTED))
                }
                "sse" -> if (checkRemote(decl, at, errors)) {
                    unsupported += UnsupportedPart(UnsupportedKind.SSE_SERVER, at, msg(ExtMessages.SSE_UNSUPPORTED))
                }
                "streamable-http" -> if (checkRemote(decl, at, errors)) {
                    val url = decl["url"]!!.stringOrNull()!!
                    val problem = httpsProblem(url)
                    if (problem != null) {
                        unsupported += UnsupportedPart(UnsupportedKind.INSECURE_URL, at, problem)
                    } else {
                        val headerNames = (decl["headers"] as? JsonObject)?.keys?.toList().orEmpty()
                        servers += McpServerDecl.StreamableHttp(serverName, url, headerNames)
                    }
                }
                else -> errors += schema("$at.type", if (type == null) msg(ExtMessages.SERVER_TYPE_MISSING) else msg(ExtMessages.SERVER_TYPE_UNKNOWN, type))
            }
        }
    }

    private fun checkStdio(decl: JsonObject, at: String, errors: MutableList<ManifestError>): Boolean {
        val before = errors.size
        for (key in decl.keys - STDIO_KEYS) errors += schema("$at.$key", msg(ExtMessages.STDIO_FIELD_NOT_ALLOWED))
        val command = decl["command"]
        if (command == null) errors += schema("$at.command", msg(ExtMessages.MISSING_REQUIRED, "command"))
        else if (command.stringOrNull().isNullOrEmpty()) errors += schema("$at.command", msg(ExtMessages.MUST_BE_NONEMPTY_STRING))
        decl["args"]?.let { if (it !is JsonArray || it.any { a -> a.stringOrNull() == null }) errors += schema("$at.args", msg(ExtMessages.MUST_BE_STRING_ARRAY)) }
        decl["env"]?.let { env ->
            if (env !is JsonObject || env.values.any { it.stringOrNull() == null }) {
                errors += schema("$at.env", msg(ExtMessages.MUST_BE_STRING_MAP))
            } else {
                for (key in env.keys.filter { it in RESERVED_ENV }) errors += schema("$at.env.$key", msg(ExtMessages.ENV_RESERVED))
            }
        }
        decl["cwd"]?.let { cwd ->
            val text = cwd.stringOrNull()
            if (text == null || !CWD_REGEX.matches(text)) errors += schema("$at.cwd", msg(ExtMessages.CWD_PREFIX))
        }
        return errors.size == before
    }

    private fun checkRemote(decl: JsonObject, at: String, errors: MutableList<ManifestError>): Boolean {
        val before = errors.size
        for (key in decl.keys - REMOTE_KEYS) errors += schema("$at.$key", msg(ExtMessages.FIELD_NOT_ALLOWED))
        val url = decl["url"]
        if (url == null) errors += schema("$at.url", msg(ExtMessages.MISSING_REQUIRED, "url"))
        else if (url.stringOrNull().isNullOrEmpty()) errors += schema("$at.url", msg(ExtMessages.MUST_BE_NONEMPTY_STRING))
        decl["headers"]?.let { h ->
            if (h !is JsonObject || h.values.any { it.stringOrNull() == null }) errors += schema("$at.headers", msg(ExtMessages.MUST_BE_STRING_MAP))
        }
        return errors.size == before
    }

    // ------------------------------------------------------------------ 小工具

    /** 只收 `https://`、有主机名、不带用户名密码的地址；否则返回原因（文案 key，没有参数：地址本身是第三方文字，不进文案）。 */
    internal fun httpsProblem(url: String): MessageRef? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return msg(ExtMessages.URL_INVALID)
        if (!"https".equals(uri.scheme, ignoreCase = true)) return msg(ExtMessages.URL_NOT_HTTPS)
        if (uri.host.isNullOrEmpty()) return msg(ExtMessages.URL_NO_HOST)
        if (uri.userInfo != null) return msg(ExtMessages.URL_USERINFO)
        return null
    }

    /** 相对插件根的路径：以 `./` 开头，没有 `..`、反斜杠、NUL，不是空。 */
    internal fun safeRelativePath(path: String): Boolean {
        if (!path.startsWith("./") || path.length <= 2) return false
        if (path.any { it == '\\' || it == '\u0000' }) return false
        return path.removePrefix("./").split('/').none { it == ".." }
    }

    private fun skillRefs(files: List<String>): List<SkillRef> =
        files.mapNotNull { f ->
            val parts = f.split('/')
            if (parts.size == 3 && parts[0] == "skills" && parts[1].isNotEmpty() && parts[2] == "SKILL.md") SkillRef(parts[1], f) else null
        }.distinctBy { it.directory }.sortedBy { it.directory }

    private fun parseObject(text: String, file: String, errors: MutableList<ManifestError>): JsonObject? {
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            errors += ManifestError(ManifestErrorCode.NOT_JSON, file, msg(ExtMessages.NOT_JSON))
            return null
        }
        if (element !is JsonObject) {
            errors += ManifestError(ManifestErrorCode.NOT_JSON, file, msg(ExtMessages.ROOT_NOT_OBJECT))
            return null
        }
        return element
    }

    private fun schema(location: String, message: MessageRef) = ManifestError(ManifestErrorCode.SCHEMA, location, message)

    private fun msg(key: String, vararg args: String) = MessageRef(key, args.toList())

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.string(key: String): String? = this[key]?.stringOrNull()
}
