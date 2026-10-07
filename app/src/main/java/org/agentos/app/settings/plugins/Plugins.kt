package org.agentos.app.settings.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 插件管理页（D5.3）：IExtensionHost 返回的 JSON 的解析、显示文案和启用规则。纯 Kotlin（PluginsLogicTest），Activity 只负责画。
 *
 * 一切来自插件的文字（名称、描述、工具标题、问题说明、服务器名）都是**不可信输入**：这里只做解析和长度限制（[clean]），界面只用
 * `setText(String)` 显示，不当 HTML、不当格式化字符串。字段说明见 IExtensionHost.aidl。
 */
object Plugins {
    private val json = Json { ignoreUnknownKeys = true }

    /** 插件级状态（PluginStatus）。不认识的值按“不可用”显示，不会当成可启用。 */
    enum class Status { READY, UNAVAILABLE, SIGNATURE_CHANGED, SIGNATURE_UNCONFIRMED }

    data class Problem(val code: String, val message: String)
    data class Unsupported(val kind: String, val location: String, val detail: String)
    data class Server(val name: String, val state: String, val error: String?, val toolCount: Int)
    data class Rejected(val name: String, val reason: String)

    data class Plugin(
        val id: String,
        val packageName: String,
        val name: String?,
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
    ) {
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

    private fun plugin(o: JsonObject): Plugin? {
        val id = str(o, "id")?.takeIf { it.isNotEmpty() } ?: return null
        val pkg = str(o, "packageName").orEmpty()
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
            problems = objs(o, "problems").map { Problem(str(it, "code").orEmpty(), clean(str(it, "message"))) },
            unsupported = objs(o, "unsupported").map { Unsupported(clean(str(it, "kind"), 40), clean(str(it, "location"), 120), clean(str(it, "detail"))) },
            servers = objs(o, "servers").map { Server(clean(str(it, "name"), 60), str(it, "state").orEmpty(), str(it, "error")?.let { e -> clean(e) }, int(it, "toolCount")) },
            rejectedServers = objs(o, "rejectedServers").map { Rejected(clean(str(it, "name"), 60), str(it, "reason").orEmpty()) },
            toolCount = int(o, "toolCount"),
            skillCount = int(o, "skillCount"),
            skillProblems = objs(o, "skillProblems").map { Problem(str(it, "code").orEmpty(), clean(str(it, "message"))) },
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
    fun errorText(message: String?): String = when (errorCode(message)) {
        "not_found" -> "找不到这个插件或工具（可能刚被卸载），请刷新"
        "not_ready" -> "插件服务还没准备好，稍后再试"
        "not_needed" -> "这个插件的签名不需要重新确认"
        "high_risk" -> "高风险工具不能设为“始终允许”"
        "bad_mode" -> "不支持的审批方式"
        else -> "操作失败，请稍后再试"
    }

    /** `agentos.ext.<code>: …` 的 code。 */
    fun errorCode(message: String?): String? = message?.substringAfter("agentos.ext.", "")?.substringBefore(':')?.trim()?.ifEmpty { null }

    // ---------------------------------------------------------------- 文案

    fun statusLine(p: Plugin): Pair<String, Boolean> = when (p.status) {
        Status.READY -> when {
            !p.enabled -> "已发现，未启用" to false
            p.servers.any { it.state == "unreachable" } && p.servers.none { it.state == "connected" } -> "已启用，但连不上插件服务" to true
            else -> "已启用" to false
        }
        Status.SIGNATURE_CHANGED -> "需要重新确认：这个 App 的签名变了，已停用" to true
        Status.SIGNATURE_UNCONFIRMED -> "需要重新确认：AgentOS 记不清这个 App 之前的签名，已停用" to true
        Status.UNAVAILABLE -> "不可用：${unavailableText(p.unavailableReason)}" to true
    }

    fun unavailableText(reason: String?): String = when (reason) {
        "assets_missing" -> "App 里没有找到插件包"
        "manifest_rejected" -> "插件清单不符合要求"
        "no_usable_server" -> "没有可用的插件服务"
        "name_conflict" -> "插件名与另一个插件重复"
        else -> "原因未知"
    }

    fun rejectedText(reason: String): String = when (reason) {
        "not_in_package" -> "服务不在这个 App 里，已忽略"
        "not_exported" -> "服务没有对 AgentOS 开放，已忽略"
        "missing_permission" -> "服务没有要求 AgentOS 的绑定权限，已忽略"
        else -> "服务不可用（$reason）"
    }

    fun serverStateText(s: Server): String = when (s.state) {
        "connected" -> "已连接"
        "idle" -> "空闲（用到时连接）"
        "unreachable" -> "连不上" + (s.error?.let { "：$it" } ?: "")
        "disabled" -> "已停用"
        else -> s.state
    }

    /** 列表里的一行摘要。被停用的插件工具列表未知时，写“启用后可查看工具”，不写“0 个工具”。 */
    fun toolSummary(p: Plugin): String = when {
        p.status != Status.READY -> ""
        !p.enabled && p.toolCount == 0 -> "启用后可查看工具"
        // 服务还没连过：工具列表要到第一次连接后才知道，不能写成“0 个工具”
        p.toolCount == 0 && p.servers.isNotEmpty() && p.servers.all { it.state == "idle" } -> "工具在第一次连接后显示，点详情查看"
        else -> "${p.toolCount} 个工具" + if (p.skillCount > 0) "，${p.skillCount} 个 Skill" else ""
    }

    fun riskText(r: Risk): String = when (r) {
        Risk.READ -> "只读"
        Risk.WRITE -> "写操作"
        Risk.HIGH -> "高风险（可能不可恢复）"
    }

    /** 审批方式的文字；高风险每次都要确认，不管策略写了什么。 */
    fun approvalText(t: Tool): String = when {
        t.risk == Risk.HIGH -> "每次确认（高风险，不能设为始终允许）"
        t.risk == Risk.READ -> "不需要确认（只读）"
        t.approval == Approval.ALWAYS -> "始终允许"
        else -> "每次确认"
    }

    /** 能不能在界面上把这个工具设成“始终允许”：只有写级、策略允许、并且现在是“每次确认”。 */
    fun canSetAlways(t: Tool): Boolean = t.risk == Risk.WRITE && t.mayAlwaysAllow && t.approval == Approval.ASK

    /** 能不能撤销“始终允许”（改回每次确认）：现在是“始终允许”。 */
    fun canRemoveAlways(t: Tool): Boolean = t.approval == Approval.ALWAYS

    /** 问题行：列表里最多显示几条，剩下的写“另有 N 项”。 */
    const val LIST_ISSUES = 3

    /** 一个插件的全部“问题/不支持/被忽略”文字，按重要性排。 */
    fun issues(p: Plugin): List<String> = buildList {
        // 签名问题已经写在状态行里，不重复
        p.problems.filterNot { p.needsReconfirm && it.code.startsWith("signature_") }.forEach { add("问题：" + it.message.ifEmpty { it.code }) }
        p.unsupported.forEach { add("不支持：" + listOf(it.kind, it.location).filter { s -> s.isNotEmpty() }.joinToString(" ") + (if (it.detail.isNotEmpty()) "（${it.detail}）" else "")) }
        p.rejectedServers.forEach { add("已忽略服务「${it.name}」：${rejectedText(it.reason)}") }
        p.skillProblems.forEach { add("Skill 问题：" + it.message.ifEmpty { it.code }) }
    }

    fun policyText(p: PolicyStatus): String? = when {
        !p.corrupt -> null
        p.failClosed -> "用户策略文件读不出来，也没有可用的副本：第三方插件一律按停用处理，不能修改设置。确认重置后全部恢复默认（现有第三方插件都会停用）。"
        p.using == "previous" -> "用户策略文件损坏，正在使用上一份有效的策略。"
        p.using == "backup" -> "用户策略文件损坏，正在使用备份。"
        else -> "用户策略文件有问题。"
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

    private const val DIGEST_SHOWN = 12

    /** 摘要的前几位，空格分组方便核对。 */
    fun digestHead(digest: String?): String = (digest?.takeIf { it.isNotEmpty() }?.take(DIGEST_SHOWN)?.chunked(4)?.joinToString(" ")) ?: "（无）"

    /**
     * 签名变了的对话框并排写“之前的签名”（trustedSigningDigest 的前 12 位）和“现在的签名”；trustedSigningDigest 为 null 写“从未确认过”。
     */
    fun enablePlan(p: Plugin): EnablePlan {
        val risk = "插件的工具名称、描述和返回的结果都来自第三方，AgentOS 不能保证它们可信。AgentOS 会在每个写操作之前向你确认，高风险操作每次都要确认。"
        val who = "「${p.displayName}」（${p.packageName}）"
        return when {
            p.status == Status.SIGNATURE_CHANGED -> EnablePlan(
                title = "重新确认并启用？",
                message = "$who 的签名与之前不同，可能是开发者换了签名，也可能是被别人换了安装包。\n\n" +
                    "包名：${p.packageName}\n" +
                    "现在的签名：${digestHead(p.signingDigest)}…\n" +
                    (p.trustedSigningDigest?.let { "之前的签名：${digestHead(it)}…\n" } ?: "之前的签名：从未确认过\n") +
                    "\n只有确认这个 App 来自你信任的来源时才继续。\n\n$risk",
                confirmLabel = "确认新签名并启用",
                confirmSignatureFirst = true,
            )
            p.status == Status.SIGNATURE_UNCONFIRMED -> EnablePlan(
                title = "重新确认并启用？",
                message = "AgentOS 记不清 $who 之前确认过的签名（记录丢失或损坏），需要你重新确认。\n\n" +
                    "包名：${p.packageName}\n现在的签名：${digestHead(p.signingDigest)}…\n\n只有确认这个 App 来自你信任的来源时才继续。\n\n$risk",
                confirmLabel = "确认签名并启用",
                confirmSignatureFirst = true,
            )
            p.builtin -> EnablePlan("启用插件？", "启用 $who？这是 AgentOS 自带的插件。", "启用", false)
            else -> EnablePlan(
                title = "启用第三方插件？",
                message = "$who 来自另一个 App，默认关闭。启用后，模型可以调用它提供的工具。\n\n" +
                    "签名：${digestHead(p.signingDigest)}…\n\n$risk",
                confirmLabel = "启用",
                confirmSignatureFirst = false,
            )
        }
    }
}
