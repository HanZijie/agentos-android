package org.agentos.app.settings.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.agentos.app.R
import org.agentos.app.ext.ExtCoreMessages
import org.agentos.app.i18n.Strings
import org.agentos.app.ui.consent.ConsentLabels
import org.agentos.runtime.i18n.FrameChars
import org.agentos.runtime.i18n.MessageRef

/**
 * 插件管理页（D5.3）：IExtensionHost 返回的 JSON 的解析、显示文案和启用规则。纯 Kotlin（PluginsLogicTest），Activity 只负责画。
 *
 * 一切来自插件的文字（名称、描述、工具标题、问题说明、服务器名）都是**不可信输入**：这里只做解析和长度限制（[clean]），界面只用
 * `setText(String)` 显示，不当 HTML、不当格式化字符串。字段说明见 IExtensionHost.aidl。
 *
 * **文案来自 [Strings]**（`values/strings_p3.xml`，`plugins_*`、`plugin_detail_*`），所以每个产生文字的函数都带一个 [Strings]；
 * core/extensions 给的校验信息是 key + 参数（[MessageRef]），经 [ExtCoreMessages] 渲染。放进「」/“” 里的第三方文字先过 [frame]
 * （所有语言的引号换成 `'`、括号去掉），第三方伪造不了结尾引号。
 */
object Plugins {
    private val json = Json { ignoreUnknownKeys = true }

    /** 插件级状态（PluginStatus）。不认识的值按“不可用”显示，不会当成可启用。 */
    enum class Status { READY, UNAVAILABLE, SIGNATURE_CHANGED, SIGNATURE_UNCONFIRMED }

    /** [message] 是核心层的 key + 参数（已清理的参数）；[location] 是问题的位置（字段路径或 Skill 目录名），没有为空串。 */
    data class Problem(val code: String, val message: MessageRef?, val location: String)
    data class Unsupported(val kind: String, val location: String, val detail: MessageRef?)
    data class Server(val name: String, val state: String, val error: String?, val toolCount: Int)
    data class Rejected(val name: String, val reason: String)

    data class Plugin(
        val id: String,
        val packageName: String,
        val name: String?,
        /** `plugin.json` 里的显示名（可能是任何语言，作者写死的）；界面用 [title]。 */
        val displayName: String,
        val description: String,
        val versionName: String,
        val signingDigest: String,
        /** 上一次确认过的签名摘要；null = 从未确认过（signature_unconfirmed）。 */
        val trustedSigningDigest: String?,
        val status: Status,
        val unavailableReason: String?,
        val builtin: Boolean,
        val enabled: Boolean,
        val approval: String?,
        val problems: List<Problem>,
        val unsupported: List<Unsupported>,
        val servers: List<Server>,
        val rejectedServers: List<Rejected>,
        val toolCount: Int,
        val skillCount: Int,
        val skillProblems: List<Problem>,
        /** App 自己的标签（`PackageManager` 取，随系统语言，已清理）；取不到为 null（D12，见 [withAppLabels]）。 */
        val appLabel: String? = null,
    ) {
        /** 界面上的名字：优先 App 的标签，退回 `plugin.json` 的 [displayName]。包名总是同时显示在身份行里，所以标签再像别的 App 也冒充不了。 */
        val title: String get() = appLabel ?: displayName

        /** 能不能打开开关：只有 ready 的；签名待确认的要先在同一个对话框里确认。 */
        val canEnableDirectly: Boolean get() = status == Status.READY
        val needsReconfirm: Boolean get() = status == Status.SIGNATURE_CHANGED || status == Status.SIGNATURE_UNCONFIRMED

        /** 开关能不能动：ready 的、待重新确认的（打开时走确认对话框）、已启用的（可以关掉）。不可用的且没启用的不给打开。 */
        val switchEnabled: Boolean get() = status == Status.READY || needsReconfirm || enabled

        /** 开关显示成“开”：只有 ready 且已启用；签名待确认的即使记录上还是开也显示关（它不会被运行时使用）。 */
        val switchOn: Boolean get() = enabled && status == Status.READY
    }

    data class Tool(
        val name: String,
        val pluginId: String,
        val server: String,
        val tool: String,
        val title: String?,
        val description: String,
        val risk: Risk,
        val enabled: Boolean,
        val approval: Approval,
        val mayAlwaysAllow: Boolean,
    ) {
        /** 显示名：title 优先，没有用服务器报告的原始工具名。 */
        val display: String get() = title?.takeIf { it.isNotBlank() } ?: tool
    }

    enum class Risk { READ, WRITE, HIGH }
    enum class Approval { ASK, ALWAYS }

    data class PolicyStatus(val corrupt: Boolean, val using: String?, val failClosed: Boolean, val reason: String?)

    // ---------------------------------------------------------------- 解析

    private const val MAX_TEXT = 300
    private const val MAX_DESCRIPTION = 400
    private const val MAX_TOOL_DESCRIPTION = 300
    private const val MAX_ARG = 120
    private const val MAX_LOCATION = 120

    /** 来自第三方的文字：去控制字符和不可见格式字符（含双向控制符）、折叠空白、截断。 */
    fun clean(s: String?, max: Int = MAX_TEXT): String {
        if (s == null) return ""
        val sb = StringBuilder()
        var space = false
        var count = 0
        var i = 0
        while (i < s.length && count < max) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            when {
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> space = sb.isNotEmpty()
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() || type == Character.PRIVATE_USE.toInt() ||
                    type == Character.SURROGATE.toInt() || type == Character.UNASSIGNED.toInt() -> Unit
                else -> {
                    if (space) {
                        sb.append(' ')
                        count++
                    }
                    space = false
                    sb.appendCodePoint(cp)
                    count++
                }
            }
        }
        return sb.toString().trim() + if (i < s.length) "…" else ""
    }

    /**
     * 第三方文字要放进界面模板的引号里（或和模板文字拼在同一行）时用：先 [clean]，再把**所有语言的引号**换成 ASCII `'`
     * （[FrameChars]，和核心层 `ConsentText` 同一份清单）。这样不管模板用「」、“ ” 还是别的引号，第三方都写不出一个能提前结束引号的字符。
     * [brackets]：文字会落在括号里（或整句放进括号）时为 true，括号类字符也当空白去掉，名字里造不出一对括号。
     */
    fun frame(s: String?, max: Int = MAX_TEXT, brackets: Boolean = false): String {
        val cleaned = clean(s, max)
        val sb = StringBuilder(cleaned.length)
        var space = false
        var i = 0
        while (i < cleaned.length) {
            val cp = cleaned.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == ' '.code -> space = sb.isNotEmpty()
                FrameChars.isQuote(cp) -> {
                    if (space) sb.append(' ')
                    space = false
                    sb.append(FrameChars.REPLACEMENT)
                }
                brackets && FrameChars.isBracket(cp) -> space = sb.isNotEmpty()
                else -> {
                    if (space) sb.append(' ')
                    space = false
                    sb.appendCodePoint(cp)
                }
            }
        }
        return sb.toString()
    }

    /**
     * 问题的位置：核心层写的 `文件 › 字段路径`（`plugin.json › extensions.org.agentos.mcpServers.notes`）。`›` 在 Unicode 里是引号类，整串过 [frame]
     * 会被换成 `'`，所以按 ` › ` 切开，每一段（里面可能有第三方的服务器名）各自过 [frame]，再用原来的分隔符接起来。
     */
    fun frameLocation(s: String?): String = clean(s, MAX_LOCATION).split(LOCATION_SEPARATOR).joinToString(LOCATION_SEPARATOR) { frame(it, MAX_LOCATION, brackets = true) }

    private const val LOCATION_SEPARATOR = " › "

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull
    private fun bool(o: JsonObject, k: String): Boolean = (o[k] as? JsonPrimitive)?.booleanOrNull == true
    private fun int(o: JsonObject, k: String): Int = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
    private fun objs(o: JsonObject, k: String): List<JsonObject> = (o[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    fun parsePlugins(text: String?): List<Plugin> = try {
        (json.parseToJsonElement(text ?: "[]") as JsonArray).mapNotNull { (it as? JsonObject)?.let(::plugin) }
    } catch (e: Exception) {
        emptyList()
    }

    fun parsePlugin(text: String?): Plugin? = try {
        (json.parseToJsonElement(text ?: "") as? JsonObject)?.let(::plugin)
    } catch (e: Exception) {
        null
    }

    /** 一句核心层文案：`{"key":…,"args":[…]}`；参数是第三方文字，清理后才用。读不懂返回 null。 */
    private fun message(e: JsonElement?): MessageRef? {
        val o = e as? JsonObject ?: return null
        val key = str(o, "key")?.takeIf { it.isNotEmpty() } ?: return null
        val args = (o["args"] as? JsonArray).orEmpty().map { a -> frame((a as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull, MAX_ARG, brackets = true) }
        return MessageRef(clean(key, 80), args)
    }

    private fun problem(o: JsonObject) = Problem(clean(str(o, "code"), 60), message(o["message"]), frameLocation(str(o, "location")))

    /** Android 包名只有 `[A-Za-z0-9_.]`：别的字符是第三方塞进来的，不显示。 */
    private fun safePackage(pkg: String): String = pkg.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '.' }

    private fun plugin(o: JsonObject): Plugin? {
        val id = str(o, "id")?.takeIf { it.isNotEmpty() } ?: return null
        val pkg = safePackage(str(o, "packageName").orEmpty())
        val name = str(o, "name")
        return Plugin(
            id = id,
            packageName = pkg,
            name = name,
            displayName = clean(str(o, "displayName"), 80).ifEmpty { clean(name, 80) }.ifEmpty { pkg.ifEmpty { id } },
            description = clean(str(o, "description"), MAX_DESCRIPTION),
            versionName = clean(str(o, "versionName"), 40),
            signingDigest = str(o, "signingDigest").orEmpty().lowercase().filter { it in '0'..'9' || it in 'a'..'f' },
            trustedSigningDigest = str(o, "trustedSigningDigest")?.lowercase()?.filter { it in '0'..'9' || it in 'a'..'f' }?.ifEmpty { null },
            status = when (str(o, "status")) {
                "ready" -> Status.READY
                "signature_changed" -> Status.SIGNATURE_CHANGED
                "signature_unconfirmed" -> Status.SIGNATURE_UNCONFIRMED
                else -> Status.UNAVAILABLE
            },
            unavailableReason = str(o, "unavailableReason"),
            builtin = bool(o, "builtin"),
            enabled = bool(o, "enabled"),
            approval = str(o, "approval"),
            problems = objs(o, "problems").map(::problem),
            unsupported = objs(o, "unsupported").map { Unsupported(frame(str(it, "kind"), 40, brackets = true), frameLocation(str(it, "location")), message(it["detail"])) },
            servers = objs(o, "servers").map { Server(clean(str(it, "name"), 60), str(it, "state").orEmpty(), str(it, "error")?.let { e -> clean(e) }, int(it, "toolCount")) },
            rejectedServers = objs(o, "rejectedServers").map { Rejected(clean(str(it, "name"), 60), str(it, "reason").orEmpty()) },
            toolCount = int(o, "toolCount"),
            skillCount = int(o, "skillCount"),
            skillProblems = objs(o, "skillProblems").map(::problem),
        )
    }

    fun parseTools(text: String?): List<Tool> = try {
        (json.parseToJsonElement(text ?: "[]") as JsonArray).mapNotNull { (it as? JsonObject)?.let(::tool) }
    } catch (e: Exception) {
        emptyList()
    }

    fun parseTool(text: String?): Tool? = try {
        (json.parseToJsonElement(text ?: "") as? JsonObject)?.let(::tool)
    } catch (e: Exception) {
        null
    }

    private fun tool(o: JsonObject): Tool? {
        val name = str(o, "name")?.takeIf { it.isNotEmpty() } ?: return null
        val src = o["source"] as? JsonObject
        return Tool(
            name = name,
            pluginId = str(o, "pluginId").orEmpty(),
            server = clean(src?.let { str(it, "server") }, 60),
            tool = clean(src?.let { str(it, "tool") } ?: name, 80),
            title = str(o, "title")?.let { clean(it, 128) }?.ifEmpty { null },
            description = clean(str(o, "description"), MAX_TOOL_DESCRIPTION),
            // 不认识的风险按最高处理：显示得最醒目、不提供“始终允许”
            risk = when (str(o, "risk")) {
                "read" -> Risk.READ
                "write" -> Risk.WRITE
                else -> Risk.HIGH
            },
            enabled = bool(o, "enabled"),
            approval = if (str(o, "approval") == "always") Approval.ALWAYS else Approval.ASK,
            // 缺字段按“不允许”：界面不提供“始终允许”
            mayAlwaysAllow = bool(o, "mayAlwaysAllow") && str(o, "risk") != "high",
        )
    }

    fun parsePolicy(text: String?): PolicyStatus? = try {
        (json.parseToJsonElement(text ?: "") as? JsonObject)?.let {
            PolicyStatus(
                corrupt = str(it, "health") != "ok",
                using = str(it, "using"),
                failClosed = bool(it, "failClosed"),
                reason = str(it, "reason")?.let { r -> clean(r) },
            )
        }
    } catch (e: Exception) {
        null
    }

    /** 列表/详情里给用户看的错误文字。 */
    fun errorText(message: String?, strings: Strings): String = strings.get(
        when (errorCode(message)) {
            "not_found" -> R.string.plugins_error_not_found
            "not_ready" -> R.string.plugins_error_not_ready
            "not_needed" -> R.string.plugins_error_not_needed
            "high_risk" -> R.string.plugins_error_high_risk
            "bad_mode" -> R.string.plugins_error_bad_mode
            else -> R.string.plugins_error_generic
        },
    )

    /** `agentos.ext.<code>: …` 的 code。 */
    fun errorCode(message: String?): String? = message?.substringAfter("agentos.ext.", "")?.substringBefore(':')?.trim()?.ifEmpty { null }

    // ---------------------------------------------------------------- App 标签（D12）

    /**
     * 插件的名字优先用 App 自己的标签（[labelOf]：`PackageManager` 取，随系统语言），取不到（没装、没有标签、标签就是包名、清理后为空）退回 `plugin.json` 的
     * `displayName`。标签是第三方文字，照旧清理（[ConsentLabels.cleanLabel]：控制字符、双向控制符、所有语言的引号、长度）。
     * 同一个 App 带了不止一个插件时，标签后面加 `displayName` 区分（`笔记 · 导出`）。包名总是同时显示（身份行），标签冒充不了别的 App。
     */
    fun withAppLabels(plugins: List<Plugin>, labelOf: (String) -> CharSequence?): List<Plugin> {
        val perPackage = plugins.groupingBy { it.packageName }.eachCount()
        return plugins.map { p ->
            val label = if (p.packageName.isEmpty()) null else ConsentLabels.cleanLabel(runCatching { labelOf(p.packageName) }.getOrNull())?.takeIf { it != p.packageName }
            val shown = when {
                label == null -> null
                (perPackage[p.packageName] ?: 1) > 1 && p.displayName.isNotEmpty() && p.displayName != label -> clean("$label · ${p.displayName}", 80)
                else -> label
            }
            p.copy(appLabel = shown)
        }
    }

    // ---------------------------------------------------------------- 文案

    /** 签名摘要前 12 位加省略号；读不出写“（无）”。 */
    fun digestShown(digest: String?, strings: Strings): String = Digest.head(digest) ?: strings.get(R.string.plugins_none)

    /** 一条核心层信息（key + 参数）：key 不认识时显示 key。 */
    fun messageText(m: MessageRef?, fallback: String, strings: Strings): String = m?.let { ExtCoreMessages.render(it, strings) } ?: fallback

    fun statusLine(p: Plugin, strings: Strings): Pair<String, Boolean> = when (p.status) {
        Status.READY -> when {
            !p.enabled -> strings.get(R.string.plugins_status_found_disabled) to false
            p.servers.any { it.state == "unreachable" } && p.servers.none { it.state == "connected" } -> strings.get(R.string.plugins_status_unreachable) to true
            else -> strings.get(R.string.plugins_status_enabled) to false
        }
        Status.SIGNATURE_CHANGED -> strings.get(R.string.plugins_status_sig_changed) to true
        Status.SIGNATURE_UNCONFIRMED -> strings.get(R.string.plugins_status_sig_unconfirmed) to true
        Status.UNAVAILABLE -> strings.get(R.string.plugins_status_unavailable, unavailableText(p.unavailableReason, strings)) to true
    }

    fun unavailableText(reason: String?, strings: Strings): String = strings.get(
        when (reason) {
            "assets_missing" -> R.string.plugins_unavailable_assets_missing
            "manifest_rejected" -> R.string.plugins_unavailable_manifest_rejected
            "no_usable_server" -> R.string.plugins_unavailable_no_usable_server
            "name_conflict" -> R.string.plugins_unavailable_name_conflict
            else -> R.string.plugins_unavailable_unknown
        },
    )

    fun rejectedText(reason: String, strings: Strings): String = when (reason) {
        "not_in_package" -> strings.get(R.string.plugins_rejected_not_in_package)
        "not_exported" -> strings.get(R.string.plugins_rejected_not_exported)
        "missing_permission" -> strings.get(R.string.plugins_rejected_missing_permission)
        else -> strings.get(R.string.plugins_rejected_unknown, frame(reason, 40))
    }

    fun serverStateText(s: Server, strings: Strings): String = when (s.state) {
        "connected" -> strings.get(R.string.plugins_server_connected)
        "idle" -> strings.get(R.string.plugins_server_idle)
        "unreachable" -> s.error?.let { strings.get(R.string.plugins_server_unreachable_reason, it) } ?: strings.get(R.string.plugins_server_unreachable)
        "disabled" -> strings.get(R.string.plugins_server_disabled)
        else -> s.state
    }

    /** 列表里的一行摘要。被停用的插件工具列表未知时，写“启用后可查看工具”，不写“0 个工具”。 */
    fun toolSummary(p: Plugin, strings: Strings): String = when {
        p.status != Status.READY -> ""
        !p.enabled && p.toolCount == 0 -> strings.get(R.string.plugins_summary_enable_to_view)
        // 服务还没连过：工具列表要到第一次连接后才知道，不能写成“0 个工具”
        p.toolCount == 0 && p.servers.isNotEmpty() && p.servers.all { it.state == "idle" } -> strings.get(R.string.plugins_summary_after_connect)
        else -> {
            val tools = strings.plural(R.plurals.plugins_tools_count, p.toolCount, p.toolCount)
            if (p.skillCount > 0) strings.get(R.string.plugins_summary_join, tools, strings.plural(R.plurals.plugins_skills_count, p.skillCount, p.skillCount)) else tools
        }
    }

    fun riskText(r: Risk, strings: Strings): String = strings.get(
        when (r) {
            Risk.READ -> R.string.plugins_risk_read
            Risk.WRITE -> R.string.plugins_risk_write
            Risk.HIGH -> R.string.plugins_risk_high
        },
    )

    /** 审批方式的文字；高风险每次都要确认，不管策略写了什么。 */
    fun approvalText(t: Tool, strings: Strings): String = strings.get(
        when {
            t.risk == Risk.HIGH -> R.string.plugins_approval_high
            t.risk == Risk.READ -> R.string.plugins_approval_read
            t.approval == Approval.ALWAYS -> R.string.plugins_approval_always
            else -> R.string.plugins_approval_ask
        },
    )

    /** 能不能在界面上把这个工具设成“始终允许”：只有写级、策略允许、并且现在是“每次确认”。 */
    fun canSetAlways(t: Tool): Boolean = t.risk == Risk.WRITE && t.mayAlwaysAllow && t.approval == Approval.ASK

    /** “设为始终允许”确认框的正文；工具名是第三方文字，放在引号里，先过 [frame]。 */
    fun alwaysAllowMessage(t: Tool, strings: Strings): String = strings.get(R.string.plugin_detail_always_message, frame(t.display, 128))

    /** 能不能撤销“始终允许”（改回每次确认）：现在是“始终允许”。 */
    fun canRemoveAlways(t: Tool): Boolean = t.approval == Approval.ALWAYS

    /** 问题行：列表里最多显示几条，剩下的写“另有 N 项”。 */
    const val LIST_ISSUES = 3

    /** 一条问题的正文：位置（字段路径或 Skill 目录）加核心层的信息；信息读不懂时退回问题的 code。 */
    private fun problemBody(p: Problem, strings: Strings): String {
        val text = messageText(p.message, p.code, strings)
        return if (p.location.isEmpty()) text else strings.get(R.string.plugins_located, p.location, text)
    }

    /** 一个插件的全部“问题/不支持/被忽略”文字，按重要性排。 */
    fun issues(p: Plugin, strings: Strings): List<String> = buildList {
        // 签名问题已经写在状态行里，不重复
        p.problems.filterNot { p.needsReconfirm && it.code.startsWith("signature_") }.forEach { add(strings.get(R.string.plugins_issue_problem, problemBody(it, strings))) }
        p.unsupported.forEach {
            val where = listOf(it.kind, it.location).filter { s -> s.isNotEmpty() }.joinToString(" ")
            val body = it.detail?.let { d -> strings.get(R.string.plugins_unsupported_body, where, ExtCoreMessages.render(d, strings)) } ?: where
            add(strings.get(R.string.plugins_issue_unsupported, body))
        }
        p.rejectedServers.forEach { add(strings.get(R.string.plugins_issue_rejected_server, frame(it.name, 60), rejectedText(it.reason, strings))) }
        p.skillProblems.forEach { add(strings.get(R.string.plugins_issue_skill, problemBody(it, strings))) }
    }

    fun policyText(p: PolicyStatus, strings: Strings): String? = when {
        !p.corrupt -> null
        p.failClosed -> strings.get(R.string.plugins_policy_fail_closed)
        p.using == "previous" -> strings.get(R.string.plugins_policy_previous)
        p.using == "backup" -> strings.get(R.string.plugins_policy_backup)
        else -> strings.get(R.string.plugins_policy_other)
    }

    // ---------------------------------------------------------------- 启用对话框

    /** 启用一个插件时对话框要写什么、点“启用”要做哪些调用。 */
    data class EnablePlan(
        val title: String,
        val message: String,
        val confirmLabel: String,
        /** 先 confirmSignature 再 setPluginEnabled(true)；false = 只 setPluginEnabled(true)。 */
        val confirmSignatureFirst: Boolean,
    )

    /**
     * 签名变了的对话框并排写“之前的签名”（trustedSigningDigest 的前 12 位）和“现在的签名”；trustedSigningDigest 为 null 写“从未确认过”。
     * 名字放在引号里，包名放在括号里（包名只剩 `[A-Za-z0-9_.]`）；名字先过 [frame]。
     */
    fun enablePlan(p: Plugin, strings: Strings): EnablePlan {
        val risk = strings.get(R.string.plugins_enable_risk)
        val who = strings.get(R.string.plugins_who, frame(p.title, 80), p.packageName)
        val current = digestShown(p.signingDigest, strings)
        return when {
            p.status == Status.SIGNATURE_CHANGED -> EnablePlan(
                title = strings.get(R.string.plugins_enable_changed_title),
                message = strings.get(
                    R.string.plugins_enable_changed_message, who, p.packageName, current,
                    p.trustedSigningDigest?.let { strings.get(R.string.plugins_enable_prev_digest, digestShown(it, strings)) } ?: strings.get(R.string.plugins_enable_prev_never),
                    risk,
                ),
                confirmLabel = strings.get(R.string.plugins_enable_changed_confirm),
                confirmSignatureFirst = true,
            )
            p.status == Status.SIGNATURE_UNCONFIRMED -> EnablePlan(
                title = strings.get(R.string.plugins_enable_changed_title),
                message = strings.get(R.string.plugins_enable_unconfirmed_message, who, p.packageName, current, risk),
                confirmLabel = strings.get(R.string.plugins_enable_unconfirmed_confirm),
                confirmSignatureFirst = true,
            )
            p.builtin -> EnablePlan(
                strings.get(R.string.plugins_enable_builtin_title), strings.get(R.string.plugins_enable_builtin_message, who), strings.get(R.string.plugins_enable_confirm), false,
            )
            else -> EnablePlan(
                title = strings.get(R.string.plugins_enable_third_title),
                message = strings.get(R.string.plugins_enable_third_message, who, current, risk),
                confirmLabel = strings.get(R.string.plugins_enable_confirm),
                confirmSignatureFirst = false,
            )
        }
    }
}
