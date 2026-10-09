package org.agentos.app.settings.callers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.agentos.app.R
import org.agentos.app.i18n.Strings
import org.agentos.app.settings.plugins.Digest
import org.agentos.app.ui.consent.ConsentLabels

/**
 * “已授权的应用”页的数据和文案（纯 Kotlin，AcpCallersLogicTest）：`IAgentControl.listAcpCallers()` 的 JSON（C，docs/third-party-acp.md 4.3）。
 * App 名、包名来自第三方，是不可信输入：App 名清理成安全单行（所有语言的引号去掉），**包名总是同时显示**，界面只用 `setText(String)`。
 * 文案来自 [Strings]（`values/strings_p3.xml` 的 `callers_*`），所以产生文字的函数都带一个 [Strings]。
 */
object AcpCallers {
    private val json = Json { ignoreUnknownKeys = true }

    enum class State { ALLOWED, DENIED, PENDING }

    /**
     * @property label 注册表记下的 App 名（可能为 null）。
     * @property lastUsedAt 最近一次 prompt 完成的时间（毫秒）；null = 从未用过。
     * @property promptCount 累计 prompt 次数（`usage.promptsTotal`）；[promptsLastHour] 最近一小时（配额用的窗口）。
     * @property deniedUntil 拒绝冷却到什么时候（毫秒）；只在 DENIED 时有意义，null = 没有。
     */
    data class Caller(
        val packageName: String,
        val label: String?,
        val signingDigest: String,
        val state: State,
        val lastUsedAt: Long?,
        val promptCount: Int,
        val promptsLastHour: Int,
        val deniedUntil: Long?,
        /** pending 时授权提示的 ID（`answerAuthorization` 用）；设置页用 `setAcpCaller`，不需要它。 */
        val requestId: String? = null,
    ) {
        val displayName: String get() = ConsentLabels.cleanLabel(label)?.takeIf { it != packageName } ?: safePackage(packageName)
    }

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull
    private fun long(o: JsonObject, k: String): Long? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
    private fun int(o: JsonObject, k: String): Int = (long(o, k) ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    /** 读不懂的项（没有包名、不认识的状态）不显示：不把不明状态当成“已允许”。 */
    fun parse(text: String?): List<Caller> = try {
        (json.parseToJsonElement(text ?: "[]") as JsonArray).mapNotNull { (it as? JsonObject)?.let(::caller) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun caller(o: JsonObject): Caller? {
        val pkg = str(o, "packageName")?.takeIf { it.isNotEmpty() } ?: return null
        val state = when (str(o, "state")) {
            "allowed" -> State.ALLOWED
            "denied" -> State.DENIED
            "pending" -> State.PENDING
            else -> return null
        }
        val usage = o["usage"] as? JsonObject
        return Caller(
            packageName = pkg,
            label = str(o, "label"),
            signingDigest = str(o, "signingDigest").orEmpty(),
            state = state,
            lastUsedAt = long(o, "lastUsedAt")?.takeIf { it > 0 },
            promptCount = usage?.let { int(it, "promptsTotal") } ?: 0,
            promptsLastHour = usage?.let { int(it, "promptsLastHour") } ?: 0,
            deniedUntil = long(o, "deniedUntil")?.takeIf { it > 0 },
            requestId = str(o, "requestId"),
        )
    }

    // ---------------------------------------------------------------- 文案

    /** 包名里只留 Android 包名允许的字符 `[A-Za-z0-9_.]`（不依赖语言，用作名字的退路）。不截断：截断的包名会让人认错 App。 */
    fun safePackage(pkg: String): String = pkg.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '.' }

    /** 包名那一行；有字符被隐藏（或为空）时写明，别的字符一律不显示。 */
    fun packageLine(pkg: String, strings: Strings): String {
        val ok = safePackage(pkg)
        val shown = if (ok == pkg && ok.isNotEmpty()) ok else strings.get(R.string.callers_package_hidden, ok.ifEmpty { strings.get(R.string.callers_package_empty) })
        return strings.get(R.string.callers_package_line, shown)
    }

    /** 签名那一行：前 12 位，四位一组；没有摘要写明，不留空。 */
    fun digestLine(digest: String?, strings: Strings): String =
        strings.get(R.string.callers_digest_line, Digest.head(digest) ?: strings.get(R.string.callers_digest_unreadable))

    fun stateText(c: Caller, now: Long, strings: Strings): String = when (c.state) {
        State.ALLOWED -> strings.get(R.string.callers_state_allowed)
        State.PENDING -> strings.get(R.string.callers_state_pending)
        State.DENIED ->
            if (c.deniedUntil != null && c.deniedUntil > now) {
                val minutes = ((c.deniedUntil - now + 59_999) / 60_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                strings.plural(R.plurals.callers_state_denied_cooldown, minutes, minutes)
            } else {
                strings.get(R.string.callers_state_denied)
            }
    }

    fun lastUsedText(c: Caller, now: Long, strings: Strings): String {
        val at = c.lastUsedAt ?: return strings.get(R.string.callers_last_used_never)
        val ago = (now - at).coerceAtLeast(0)
        val text = when {
            ago < 60_000 -> strings.get(R.string.callers_ago_just_now)
            ago < 3_600_000 -> (ago / 60_000).toInt().let { strings.plural(R.plurals.callers_ago_minutes, it, it) }
            ago < 86_400_000 -> (ago / 3_600_000).toInt().let { strings.plural(R.plurals.callers_ago_hours, it, it) }
            else -> (ago / 86_400_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().let { strings.plural(R.plurals.callers_ago_days, it, it) }
        }
        return strings.get(R.string.callers_last_used, text)
    }

    fun usageText(c: Caller, strings: Strings): String =
        if (c.promptCount > 0) strings.plural(R.plurals.callers_usage_total_hour, c.promptCount, c.promptCount, c.promptsLastHour)
        else strings.plural(R.plurals.callers_usage_total, c.promptCount, c.promptCount)

    /**
     * 能做什么：已允许的可以撤销（改成拒绝：通道立即关闭，10 分钟内它再请求直接被拒）；已拒绝的可以改成允许、或移除记录（下次再请求
     * 会重新询问）；等你决定的可以允许或拒绝（平时在授权提示里答，这里是提示丢了时的后路）。
     */
    enum class Action { REVOKE, ALLOW, DENY, REMOVE }

    fun actions(c: Caller): List<Action> = when (c.state) {
        State.ALLOWED -> listOf(Action.REVOKE)
        State.DENIED -> listOf(Action.ALLOW, Action.REMOVE)
        State.PENDING -> listOf(Action.ALLOW, Action.DENY)
    }

    /** [c] 为已拒绝时写“改为允许”，等你决定时写“允许”。 */
    fun actionLabel(a: Action, c: Caller? = null, strings: Strings): String = strings.get(
        when (a) {
            Action.REVOKE -> R.string.callers_action_revoke
            Action.ALLOW -> if (c?.state == State.DENIED) R.string.callers_action_allow_denied else R.string.callers_action_allow
            Action.DENY -> R.string.callers_action_deny
            Action.REMOVE -> R.string.callers_action_remove
        },
    )

    /** `setAcpCaller` 的 state 参数。 */
    fun wireState(a: Action): String = when (a) {
        Action.REVOKE, Action.DENY -> "denied"
        Action.REMOVE -> "removed"
        Action.ALLOW -> "allowed"
    }

    data class Confirm(val title: String, val message: String, val confirmLabel: String)

    /** 点操作后的确认对话框：写明名字、包名、签名摘要前 12 位，和后果。 */
    fun confirm(c: Caller, a: Action, strings: Strings): Confirm {
        val who = strings.get(R.string.callers_who, c.displayName, packageLine(c.packageName, strings), digestLine(c.signingDigest, strings))
        return when (a) {
            Action.REVOKE -> Confirm(strings.get(R.string.callers_revoke_title), strings.get(R.string.callers_revoke_message, who), strings.get(R.string.callers_revoke_confirm))
            Action.DENY -> Confirm(strings.get(R.string.callers_deny_title), strings.get(R.string.callers_deny_message, who), strings.get(R.string.callers_deny_confirm))
            Action.ALLOW -> Confirm(
                strings.get(R.string.callers_allow_title), strings.get(R.string.callers_allow_message, who, strings.get(R.string.callers_allow_explanation)), strings.get(R.string.callers_allow_confirm),
            )
            Action.REMOVE -> Confirm(strings.get(R.string.callers_remove_title), strings.get(R.string.callers_remove_message, who), strings.get(R.string.callers_remove_confirm))
        }
    }

    /** 固定文案，不回显原始异常文字。 */
    fun errorText(strings: Strings): String = strings.get(R.string.callers_error)
}
