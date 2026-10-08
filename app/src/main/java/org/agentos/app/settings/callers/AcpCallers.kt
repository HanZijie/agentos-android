package org.agentos.app.settings.callers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.agentos.app.ui.consent.AuthorizationLabels
import org.agentos.app.ui.consent.ConsentLabels

/**
 * “已授权的应用”页的数据和文案（纯 Kotlin，AcpCallersLogicTest）：`IAgentControl.listAcpCallers()` 的 JSON（C，docs/third-party-acp.md 4.3）。
 * App 名、包名来自第三方，是不可信输入：App 名清理成安全单行，**包名总是同时显示**，界面只用 `setText(String)`。
 */
object AcpCallers {
    private val json = Json { ignoreUnknownKeys = true }

    enum class State { ALLOWED, DENIED }

    /**
     * @property label 注册表记下的 App 名（可能为 null）。
     * @property lastUsedAt 最近一次 prompt 完成的时间（毫秒）；null = 从未用过。
     * @property promptCount 累计 prompt 次数；[promptsLastHour] 最近一小时（配额用的窗口）。
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
    ) {
        val displayName: String get() = ConsentLabels.cleanLabel(label)?.takeIf { it != packageName } ?: AuthorizationLabels.safePackage(packageName)
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
            else -> return null
        }
        return Caller(
            packageName = pkg,
            label = str(o, "label"),
            signingDigest = str(o, "signingDigest").orEmpty(),
            state = state,
            lastUsedAt = long(o, "lastUsedAt")?.takeIf { it > 0 },
            promptCount = int(o, "promptCount"),
            promptsLastHour = int(o, "promptsLastHour"),
            deniedUntil = long(o, "deniedUntil")?.takeIf { it > 0 },
        )
    }

    // ---------------------------------------------------------------- 文案

    fun stateText(c: Caller, now: Long): String = when (c.state) {
        State.ALLOWED -> "已允许"
        State.DENIED -> if (c.deniedUntil != null && c.deniedUntil > now) "已拒绝（${(c.deniedUntil - now + 59_999) / 60_000} 分钟内它再请求会直接被拒绝）" else "已拒绝"
    }

    fun lastUsedText(c: Caller, now: Long): String {
        val at = c.lastUsedAt ?: return "最近使用：从未使用"
        val ago = (now - at).coerceAtLeast(0)
        val text = when {
            ago < 60_000 -> "刚刚"
            ago < 3_600_000 -> "${ago / 60_000} 分钟前"
            ago < 86_400_000 -> "${ago / 3_600_000} 小时前"
            else -> "${ago / 86_400_000} 天前"
        }
        return "最近使用：$text"
    }

    fun usageText(c: Caller): String = "用量：共 ${c.promptCount} 次" + if (c.promptCount > 0) "，最近一小时 ${c.promptsLastHour} 次" else ""

    /** 能做什么：已允许的只能撤销；已拒绝的可以改成允许、或移除记录（下次再请求会重新询问）。 */
    enum class Action { REVOKE, ALLOW, REMOVE }

    fun actions(c: Caller): List<Action> = when (c.state) {
        State.ALLOWED -> listOf(Action.REVOKE)
        State.DENIED -> listOf(Action.ALLOW, Action.REMOVE)
    }

    fun actionLabel(a: Action): String = when (a) {
        Action.REVOKE -> "撤销授权"
        Action.ALLOW -> "改为允许"
        Action.REMOVE -> "移除记录"
    }

    /** `setAcpCaller` 的 state 参数。 */
    fun wireState(a: Action): String = when (a) {
        Action.REVOKE, Action.REMOVE -> "removed"
        Action.ALLOW -> "allowed"
    }

    data class Confirm(val title: String, val message: String, val confirmLabel: String)

    /** 点操作后的确认对话框：写明名字、包名、签名摘要前 12 位，和后果。 */
    fun confirm(c: Caller, a: Action): Confirm {
        val who = "「${c.displayName}」\n${AuthorizationLabels.safePackage(c.packageName).let { "包名：$it" }}\n${AuthorizationLabels.digestLine(c.signingDigest)}"
        return when (a) {
            Action.REVOKE -> Confirm(
                "撤销授权？", "$who\n\n它现有的连接会立即断开，进行中的任务会取消。下次它再请求使用 AgentOS 时会重新询问你。", "撤销",
            )
            Action.ALLOW -> Confirm(
                "改为允许？", "$who\n\n${AuthorizationLabels.EXPLANATION}\n\n只有确认这个 App 来自你信任的来源时才允许。", "允许",
            )
            Action.REMOVE -> Confirm("移除记录？", "$who\n\n移除后，它下次请求使用 AgentOS 时会重新询问你。", "移除")
        }
    }

    fun errorText(message: String?): String = "操作失败，请稍后再试"
}
