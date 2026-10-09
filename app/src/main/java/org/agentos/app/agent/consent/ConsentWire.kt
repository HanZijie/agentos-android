package org.agentos.app.agent.consent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import org.agentos.runtime.consent.ConsentMessages
import org.agentos.runtime.consent.ConsentResolution
import org.agentos.runtime.consent.ConsentSeverity
import org.agentos.runtime.consent.ConsentView
import org.agentos.runtime.i18n.MessageRef

/**
 * `:agent` ↔ 主进程之间的确认数据（[org.agentos.internal.IConsentListener]）：纯 Kotlin，可在电脑上单测（ConsentWireTest）。
 *
 * [ConsentView] 里所有第三方文字已由协调器清理；这里只搬运，不再解释。主进程收到的 JSON 当不可信输入读：缺字段、类型不对、
 * 选项名不认识都按“这条请求不显示”处理（[parseCard] 返回 null），不会崩。
 *
 * 核心层给的文案是 [MessageRef]（key + 参数），原样过 IPC（`{"key":"…","args":["…"]}`），主进程按自己的界面语言渲染（`Strings.get(ref)`）；
 * 这里不生成任何自然语言。
 */
object ConsentWire {
    private val json = Json { ignoreUnknownKeys = true }

    /** 前台队列里的一项：工具确认（[Card]）或第三方 App 的授权提示（[AuthRequest]），按到达先后排。 */
    sealed interface Pending {
        val requestId: String
        /** 到达时间：deadline 减去超时（排队时间计入 deadline，所以这就是入队时刻）。 */
        val arrivalMillis: Long
        val deadlineMillis: Long
    }

    /**
     * 第三方 App 第一次（或换了签名后）要使用 AgentOS 时的授权提示（docs/third-party-acp.md 4.2）。由 `:agent` 里的 CallerRegistry
     * 经 [AuthorizationSurface] 交来；所有字段都是不可信输入（App 名、包名由 App 自己决定），界面按纯文本显示。
     *
     * @property appLabel 注册表解析到的 App 名；null 时主进程自己解析。
     * @property signingDigest 签名摘要（SHA-256 小写十六进制），界面只显示前 12 位。
     * @property signatureChanged 这个包名以前被允许/拒绝过、但签名与那时不同：卡片上要写明。
     */
    data class AuthRequest(
        override val requestId: String,
        val packageName: String,
        val appLabel: String?,
        val signingDigest: String,
        val signatureChanged: Boolean,
        override val deadlineMillis: Long,
        val timeoutMillis: Long,
    ) : Pending {
        override val arrivalMillis: Long get() = deadlineMillis - timeoutMillis
    }

    /** 主进程显示用的一条待确认请求。和 [ConsentView] 一一对应，只保留界面要的字段。 */
    data class Card(
        override val requestId: String,
        val title: MessageRef,
        val initiatorLine: MessageRef,
        val callerKind: String,
        val callerPackage: String?,
        val sourceLine: MessageRef?,
        val toolDisplayName: String,
        val argumentsPreview: String,
        val argumentsTruncated: Boolean,
        val risk: String,
        val severity: ConsentSeverity,
        val riskLabel: MessageRef,
        val riskDescription: MessageRef,
        val options: List<Option>,
        override val deadlineMillis: Long,
        val timeoutMillis: Long,
        val queuePosition: Int,
        val queueSize: Int,
    ) : Pending {
        override val arrivalMillis: Long get() = deadlineMillis - timeoutMillis

        /** 能用在通知按钮上的选项：只有“允许一次”和“拒绝”（“始终允许”“本会话内不再询问”要在对话框里看清楚再选）。 */
        fun allowsOnce(): Boolean = options.any { it.choice == ConsentChoice.ALLOW_ONCE }
    }

    data class Option(val choice: ConsentChoice, val label: MessageRef, val destructive: Boolean)

    data class Resolution(val end: ConsentEnd, val choice: ConsentChoice?, val notice: MessageRef?)

    // ---------------------------------------------------------------- :agent 侧：编码

    fun encodeView(v: ConsentView): JsonObject = buildJsonObject {
        put("requestId", v.requestId)
        put("title", encodeRef(v.title))
        put("initiatorLine", encodeRef(v.initiatorLine))
        put("callerKind", v.caller.kind.name)
        put("callerPackage", v.caller.packageName)
        put("sourceLine", v.sourceLine?.let { encodeRef(it) } ?: JsonNull)
        put("toolDisplayName", v.toolDisplayName)
        put("argumentsPreview", v.argumentsPreview)
        put("argumentsTruncated", v.argumentsTruncated)
        put("risk", v.risk.name)
        put("severity", v.severity.name)
        put("riskLabel", encodeRef(v.riskLabel))
        put("riskDescription", encodeRef(v.riskDescription))
        put(
            "options",
            JsonArray(
                v.options.map {
                    buildJsonObject {
                        put("choice", it.choice.name)
                        put("label", encodeRef(it.label))
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

    /** 一句核心层文案：key 和参数。 */
    fun encodeRef(r: MessageRef): JsonObject = buildJsonObject {
        put("key", r.key)
        put("args", JsonArray(r.args.map { JsonPrimitive(it) }))
    }

    private fun ref(o: JsonObject, k: String): MessageRef? = refOf(o[k])

    /** 读不懂（不是对象、没有 key、参数里有非字符串）返回 null。 */
    fun refOf(e: JsonElement?): MessageRef? {
        val o = e as? JsonObject ?: return null
        val key = str(o, "key")?.takeIf { it.isNotEmpty() } ?: return null
        val args = (o["args"] as? JsonArray)?.map { a -> (a as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull ?: return null }.orEmpty()
        return MessageRef(key, args)
    }

    fun encodeViews(views: List<ConsentView>): String = JsonArray(views.map { encodeView(it) }).toString()

    /** 授权提示。`kind` 区分它和工具确认（工具确认没有这个键，按旧格式读）。 */
    fun encodeAuth(r: AuthRequest): JsonObject = buildJsonObject {
        put("kind", KIND_AUTH)
        put("requestId", r.requestId)
        put("packageName", r.packageName)
        put("appLabel", r.appLabel)
        put("signingDigest", r.signingDigest)
        put("signatureChanged", r.signatureChanged)
        put("deadlineMillis", r.deadlineMillis)
        put("timeoutMillis", r.timeoutMillis)
    }

    fun encodeAuthString(r: AuthRequest): String = encodeAuth(r).toString()

    /** 登记监听者时的快照：工具确认和授权提示按到达先后合在一起（稳定排序：同一时刻保持各自原来的顺序）。 */
    fun encodePending(views: List<ConsentView>, auths: List<AuthRequest>): String {
        val items = views.map { it.deadlineMillis - it.timeoutMillis to encodeView(it) } + auths.map { it.arrivalMillis to encodeAuth(it) }
        return JsonArray(items.sortedBy { it.first }.map { it.second }).toString()
    }

    fun encodeViewString(v: ConsentView): String = encodeView(v).toString()

    fun encodeResolution(r: ConsentResolution): String = buildJsonObject {
        put("end", r.end.name)
        put("choice", r.choice?.name)
        put("notice", r.notice?.let { encodeRef(it) } ?: JsonNull)
    }.toString()

    const val KIND_AUTH = "authorization"

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

    /** 快照/新请求里的一项：有 `kind":"authorization"` 的是授权提示，其余按工具确认读。读不懂的返回 null（不显示）。 */
    fun parsePending(text: String?): Pending? = try {
        text?.let { pendingOf(json.parseToJsonElement(it) as JsonObject) }
    } catch (e: Exception) {
        null
    }

    fun parsePendings(text: String?): List<Pending> = try {
        (json.parseToJsonElement(text ?: "[]") as JsonArray).mapNotNull { (it as? JsonObject)?.let(::pendingOf) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun pendingOf(o: JsonObject): Pending? = if (str(o, "kind") == KIND_AUTH) authOf(o) else cardOf(o)

    private fun authOf(o: JsonObject): AuthRequest? = AuthRequest(
        requestId = str(o, "requestId")?.takeIf { it.isNotEmpty() } ?: return null,
        packageName = str(o, "packageName")?.takeIf { it.isNotEmpty() } ?: return null,
        appLabel = str(o, "appLabel"),
        signingDigest = str(o, "signingDigest").orEmpty(),
        signatureChanged = (o["signatureChanged"] as? JsonPrimitive)?.boolean == true,
        deadlineMillis = (o["deadlineMillis"] as? JsonPrimitive)?.long ?: return null,
        timeoutMillis = (o["timeoutMillis"] as? JsonPrimitive)?.long ?: 60_000L,
    )

    fun parseResolution(text: String?): Resolution? = try {
        val o = json.parseToJsonElement(text ?: "") as JsonObject
        Resolution(
            end = ConsentEnd.valueOf(str(o, "end") ?: return null),
            choice = str(o, "choice")?.let { name -> ConsentChoice.entries.firstOrNull { it.name == name } },
            notice = ref(o, "notice"),
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
            Option(choice, ref(eo, "label") ?: return@mapNotNull null, (eo["destructive"] as? JsonPrimitive)?.boolean == true)
        }
        // 总有“允许一次”和“拒绝”（协调器保证）；缺了说明数据不对，不显示
        if (options.none { it.choice == ConsentChoice.DENY } || options.none { it.choice == ConsentChoice.ALLOW_ONCE }) return null
        return Card(
            requestId = str(o, "requestId")?.takeIf { it.isNotEmpty() } ?: return null,
            title = ref(o, "title") ?: return null,
            initiatorLine = ref(o, "initiatorLine") ?: return null,
            callerKind = str(o, "callerKind") ?: return null,
            callerPackage = str(o, "callerPackage"),
            sourceLine = ref(o, "sourceLine"),
            toolDisplayName = str(o, "toolDisplayName") ?: return null,
            argumentsPreview = str(o, "argumentsPreview").orEmpty(),
            argumentsTruncated = (o["argumentsTruncated"] as? JsonPrimitive)?.boolean == true,
            risk = str(o, "risk") ?: return null,
            severity = ConsentSeverity.entries.firstOrNull { it.name == str(o, "severity") } ?: ConsentSeverity.CRITICAL, // 不认识就按最醒目的显示
            // 读不懂风险文案时按最醒目的高风险显示（和 severity 一致）：不显示空白
            riskLabel = ref(o, "riskLabel") ?: MessageRef.of(ConsentMessages.RISK_HIGH),
            riskDescription = ref(o, "riskDescription") ?: MessageRef.of(ConsentMessages.RISK_DESC_HIGH),
            options = options,
            deadlineMillis = (o["deadlineMillis"] as? JsonPrimitive)?.long ?: return null,
            timeoutMillis = (o["timeoutMillis"] as? JsonPrimitive)?.long ?: 60_000L,
            queuePosition = (o["queuePosition"] as? JsonPrimitive)?.int ?: 0,
            queueSize = (o["queueSize"] as? JsonPrimitive)?.int ?: 1,
        )
    }
}
