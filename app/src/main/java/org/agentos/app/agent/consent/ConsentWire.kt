package org.agentos.app.agent.consent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.agentos.runtime.consent.ConsentChoice
import org.agentos.runtime.consent.ConsentEnd
import org.agentos.runtime.consent.ConsentResolution
import org.agentos.runtime.consent.ConsentSeverity
import org.agentos.runtime.consent.ConsentView

/**
 * `:agent` ↔ 主进程之间的确认数据（[org.agentos.internal.IConsentListener]）：纯 Kotlin，可在电脑上单测（ConsentWireTest）。
 *
 * [ConsentView] 里所有第三方文字已由协调器清理；这里只搬运，不再解释。主进程收到的 JSON 当不可信输入读：缺字段、类型不对、
 * 选项名不认识都按“这条请求不显示”处理（[parseCard] 返回 null），不会崩。
 */
object ConsentWire {
    private val json = Json { ignoreUnknownKeys = true }

    /** 主进程显示用的一条待确认请求。和 [ConsentView] 一一对应，只保留界面要的字段。 */
    data class Card(
        val requestId: String,
        val title: String,
        val initiatorLine: String,
        val callerKind: String,
        val callerPackage: String?,
        val sourceLine: String?,
        val toolDisplayName: String,
        val argumentsPreview: String,
        val argumentsTruncated: Boolean,
        val risk: String,
        val severity: ConsentSeverity,
        val riskLabel: String,
        val riskDescription: String,
        val options: List<Option>,
        val deadlineMillis: Long,
        val timeoutMillis: Long,
        val queuePosition: Int,
        val queueSize: Int,
    ) {
        /** 能用在通知按钮上的选项：只有“允许一次”和“拒绝”（“始终允许”“本会话内不再询问”要在对话框里看清楚再选）。 */
        fun allowsOnce(): Boolean = options.any { it.choice == ConsentChoice.ALLOW_ONCE }
    }

    data class Option(val choice: ConsentChoice, val label: String, val destructive: Boolean)

    data class Resolution(val end: ConsentEnd, val choice: ConsentChoice?, val notice: String?)

    // ---------------------------------------------------------------- :agent 侧：编码

    fun encodeView(v: ConsentView): JsonObject = buildJsonObject {
        put("requestId", v.requestId)
        put("title", v.title)
        put("initiatorLine", v.initiatorLine)
        put("callerKind", v.caller.kind.name)
        put("callerPackage", v.caller.packageName)
        put("sourceLine", v.sourceLine)
        put("toolDisplayName", v.toolDisplayName)
        put("argumentsPreview", v.argumentsPreview)
        put("argumentsTruncated", v.argumentsTruncated)
        put("risk", v.risk.name)
        put("severity", v.severity.name)
        put("riskLabel", v.riskLabel)
        put("riskDescription", v.riskDescription)
        put(
            "options",
            JsonArray(
                v.options.map {
                    buildJsonObject {
                        put("choice", it.choice.name)
                        put("label", it.label)
                        put("destructive", it.destructive)
                    }
                },
            ),
        )
        put("deadlineMillis", v.deadlineMillis)
        put("timeoutMillis", v.timeoutMillis)
        put("queuePosition", v.queuePosition)
        put("queueSize", v.queueSize)
    }

    fun encodeViews(views: List<ConsentView>): String = JsonArray(views.map { encodeView(it) }).toString()

    fun encodeViewString(v: ConsentView): String = encodeView(v).toString()

    fun encodeResolution(r: ConsentResolution): String = buildJsonObject {
        put("end", r.end.name)
        put("choice", r.choice?.name)
        put("notice", r.notice)
    }.toString()

    // ---------------------------------------------------------------- 主进程侧：解码

    fun parseCard(text: String?): Card? = try {
        text?.let { cardOf(json.parseToJsonElement(it) as JsonObject) }
    } catch (e: Exception) {
        null
    }

    fun parseCards(text: String?): List<Card> = try {
        (json.parseToJsonElement(text ?: "[]") as JsonArray).mapNotNull { (it as? JsonObject)?.let(::cardOf) }
    } catch (e: Exception) {
        emptyList()
    }

    fun parseResolution(text: String?): Resolution? = try {
        val o = json.parseToJsonElement(text ?: "") as JsonObject
        Resolution(
            end = ConsentEnd.valueOf(str(o, "end") ?: return null),
            choice = str(o, "choice")?.let { name -> ConsentChoice.entries.firstOrNull { it.name == name } },
            notice = str(o, "notice"),
        )
    } catch (e: Exception) {
        null
    }

    fun parseChoice(name: String?): ConsentChoice? = ConsentChoice.entries.firstOrNull { it.name == name }

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull

    private fun cardOf(o: JsonObject): Card? {
        val options = ((o["options"] as? JsonArray) ?: return null).mapNotNull { e ->
            val eo = e as? JsonObject ?: return@mapNotNull null
            val choice = parseChoice(str(eo, "choice")) ?: return@mapNotNull null
            Option(choice, str(eo, "label") ?: return@mapNotNull null, (eo["destructive"] as? JsonPrimitive)?.boolean == true)
        }
        // 总有“允许一次”和“拒绝”（协调器保证）；缺了说明数据不对，不显示
        if (options.none { it.choice == ConsentChoice.DENY } || options.none { it.choice == ConsentChoice.ALLOW_ONCE }) return null
        return Card(
            requestId = str(o, "requestId")?.takeIf { it.isNotEmpty() } ?: return null,
            title = str(o, "title") ?: return null,
            initiatorLine = str(o, "initiatorLine") ?: return null,
            callerKind = str(o, "callerKind") ?: return null,
            callerPackage = str(o, "callerPackage"),
            sourceLine = str(o, "sourceLine"),
            toolDisplayName = str(o, "toolDisplayName") ?: return null,
            argumentsPreview = str(o, "argumentsPreview").orEmpty(),
            argumentsTruncated = (o["argumentsTruncated"] as? JsonPrimitive)?.boolean == true,
            risk = str(o, "risk") ?: return null,
            severity = ConsentSeverity.entries.firstOrNull { it.name == str(o, "severity") } ?: ConsentSeverity.CRITICAL, // 不认识就按最醒目的显示
            riskLabel = str(o, "riskLabel").orEmpty(),
            riskDescription = str(o, "riskDescription").orEmpty(),
            options = options,
            deadlineMillis = (o["deadlineMillis"] as? JsonPrimitive)?.long ?: return null,
            timeoutMillis = (o["timeoutMillis"] as? JsonPrimitive)?.long ?: 60_000L,
            queuePosition = (o["queuePosition"] as? JsonPrimitive)?.int ?: 0,
            queueSize = (o["queueSize"] as? JsonPrimitive)?.int ?: 1,
        )
    }
}
