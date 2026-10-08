package org.agentos.app.agent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.agentos.extensions.registry.AtomicTextFile
import java.io.IOException
import java.util.UUID

/** [CallerRegistry] 的参数（docs/third-party-acp.md 第 3 节：拒绝后 10 分钟内直接返回 denied）。 */
data class CallerConfig(
    /** 用户拒绝（或授权超时）后，这个 App 的 open 直接返回 denied 的时间。 */
    val denyCooldownMillis: Long = 10 * 60_000L,
    /**
     * 授权提示最多等多久。SDK 等 90 秒（[org.agentos.acp.AgentOs.AUTHORIZATION_TIMEOUT_MILLIS]），这里多留 10 秒：
     * 超时没人决定按拒绝记（进入冷却）。
     */
    val pendingTtlMillis: Long = 100_000L,
    /** 同时待决的授权提示上限；再多的 App 直接 not_open（防止装一批 App 刷屏）。 */
    val maxPending: Int = 8,
    /** 记着的 App 总数上限（已允许 / 已拒绝）；满了只清最久没用的已拒绝项，仍满则不再接受新的。 */
    val maxEntries: Int = 64,
    /** 一个 prompt 完成时间的保留窗口（算 promptsLastHour）。 */
    val usageWindowMillis: Long = 60 * 60_000L,
)

enum class CallerState(val wire: String) { PENDING("pending"), ALLOWED("allowed"), DENIED("denied") }

/**
 * 一个第三方 App 的记录（包名 + 签名摘要）。[requestId]、[requestedAt] 只在 PENDING 时有值；[deniedUntil] 只在 DENIED 时有值。
 * 内存里的 promptTimes 不进文件（重启后“最近一小时”从零算）；promptsTotal 进文件。
 */
data class CallerEntry(
    val packageName: String,
    val signingDigest: String,
    val label: String,
    val state: CallerState,
    val firstSeenAt: Long,
    val decidedAt: Long? = null,
    val lastUsedAt: Long? = null,
    val deniedUntil: Long? = null,
    val requestId: String? = null,
    val requestedAt: Long? = null,
    val promptsTotal: Long = 0,
    /** 只在 PENDING 时有意义（不进文件）：这个包名以前被记录过，但签名和那时不同——授权卡片要写明。 */
    val signatureChanged: Boolean = false,
)

/** 准入的结果（[CallerRegistry.admit]）。 */
sealed interface Admission {
    data class Allowed(val label: String) : Admission

    /** 已经记下请求、等用户决定；调用方稍后重试。 */
    data class Pending(val requestId: String) : Admission

    data class Denied(val untilMillis: Long) : Admission

    /** 不接受（待决的请求太多、记录满了、注册表不可用）：当作 not_open。 */
    data class Refused(val reason: String) : Admission
}

/** 注册表文件的健康状况（插件页风格：读不出来时不悄悄回到“全部允许”）。 */
sealed interface CallerHealth {
    data object Ok : CallerHealth

    /**
     * 文件读不出来。[using] = BACKUP：用了备份；EMPTY：没有可用的备份，**当作一份空的**——每个 App 都要重新授权（fail closed：
     * 丢记录只会多问，不会放行）。坏文件原样留在 callers.json.corrupt 里供诊断。
     */
    data class Corrupt(val reason: String, val using: Using) : CallerHealth

    enum class Using { BACKUP, EMPTY }
}

/** 一条授权提示是怎么结案的（[CallerListener.onResolved]）。 */
enum class CallerResolution {
    /** 用户答复了（允许或拒绝）。 */
    ANSWERED,

    /** 没人决定，按拒绝记（进入冷却）。 */
    TIMED_OUT,

    /** 没有答复就作废了：被新的请求取代（签名又变了）、记录被删除。界面撤回卡片即可。 */
    CANCELLED,
}

/** 注册表事件的接收方（:agent）：授权提示的出现 / 撤回，以及撤销时要关通道、取消任务。回调在持锁之外调用，不要阻塞。 */
interface CallerListener {
    /** 一个新的授权提示（PENDING）出现：界面弹卡片 / 发通知。 */
    fun onPending(entry: CallerEntry)

    /** 一个授权提示结案（[how]）：撤回卡片 / 通知。[state] 是之后的状态（用户答复的结果，或超时 / 作废后的 DENIED）。 */
    fun onResolved(requestId: String, state: CallerState, how: CallerResolution)

    /** 这个 App（包名 + 签名摘要）的授权被撤销（改为拒绝 / 删除 / 签名变了）：关掉它现有的通道、取消它进行中的任务。 */
    fun onRevoked(packageName: String, signingDigest: String)
}

/**
 * 第三方 App 的授权记录（docs/third-party-acp.md 4.1、4.3）：`files/acp/callers.json`，原子写；只存 ALLOWED 和 DENIED
 * （PENDING 在内存里，进程重启后调用方重试 open 会重新提出）。键 = 包名 + 签名摘要：包名相同、摘要变了的旧记录作废，
 * 当作新 App 重新询问（并撤销旧记录的通道）。
 *
 * - **准入不阻塞**：[admit] 只查表、改状态、立刻返回；用户的回答经 [decide] / [set] 到达。
 * - **fail closed**：文件读不出来时先用备份，没有备份就当空表（每个 App 重新授权），[health] 给设置页显示。
 *   写失败（磁盘满…）时状态不变，调用方得到 IOException 的信息。
 * - 线程安全；监听回调在锁外调用。时钟可注入（测试）。
 */
class CallerRegistry(
    private val primary: AtomicTextFile,
    private val backup: AtomicTextFile? = null,
    private val quarantine: AtomicTextFile? = null,
    config: CallerConfig = CallerConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()

    /** 当前参数。正式运行时就是构造时的值；只有 debug 构建的调试入口会改它（[overrideConfig]，让设备用例不必等 10 分钟）。 */
    @Volatile var config: CallerConfig = config
        private set
    private val entries = LinkedHashMap<String, CallerEntry>() // 包名 → 记录
    private val promptTimes = HashMap<String, ArrayDeque<Long>>() // 包名 → 最近完成的 prompt 时间
    private var listener: CallerListener? = null

    @Volatile var health: CallerHealth = CallerHealth.Ok
        private set

    /** 最近一次写备份失败的原因；没有失败为 null。 */
    @Volatile var lastBackupError: String? = null
        private set

    init {
        load()
    }

    fun setListener(l: CallerListener?) {
        synchronized(lock) { listener = l }
    }

    // ------------------------------------------------------------------ 准入

    /**
     * open 时调用：[packageName]、[signingDigest]、[label] 来自 Binder 调用方 UID（PackageManager），不是调用方自报的。
     */
    fun admit(packageName: String, signingDigest: String, label: String): Admission {
        val events = ArrayList<() -> Unit>()
        val result = synchronized(lock) {
            val now = clock()
            expirePending(now, events)
            val e = entries[packageName]
            when {
                // 没有记录，或签名变了：旧记录作废（并撤销它的通道），当作新 App
                e == null || e.signingDigest != signingDigest -> {
                    if (e != null) events += { listener?.onRevoked(e.packageName, e.signingDigest) }
                    if (e != null && e.state == CallerState.PENDING) events += resolvedEvent(e, CallerState.DENIED, CallerResolution.CANCELLED)
                    entries.remove(packageName)
                    propose(packageName, signingDigest, label, now, events, signatureChanged = e != null)
                }
                e.state == CallerState.PENDING -> {
                    if (e.label != label) entries[packageName] = e.copy(label = label)
                    Admission.Pending(e.requestId!!)
                }
                e.state == CallerState.ALLOWED -> {
                    if (e.label != label) {
                        entries[packageName] = e.copy(label = label)
                        persistQuietly()
                    }
                    Admission.Allowed(label)
                }
                // DENIED：冷却内直接拒绝；冷却过了重新询问
                else -> {
                    val until = e.deniedUntil ?: 0L
                    if (now < until) Admission.Denied(until) else propose(packageName, signingDigest, label, now, events, previous = e)
                }
            }
        }
        events.forEach { it() }
        return result
    }

    private fun propose(
        pkg: String, digest: String, label: String, now: Long, events: MutableList<() -> Unit>,
        previous: CallerEntry? = null, signatureChanged: Boolean = false,
    ): Admission {
        if (entries.values.count { it.state == CallerState.PENDING } >= config.maxPending) {
            return Admission.Refused("too many pending authorization requests")
        }
        if (previous == null && !makeRoom()) return Admission.Refused("too many remembered apps")
        val id = newRequestId()
        val entry = CallerEntry(
            packageName = pkg, signingDigest = digest, label = label, state = CallerState.PENDING,
            firstSeenAt = previous?.firstSeenAt ?: now, lastUsedAt = previous?.lastUsedAt, promptsTotal = previous?.promptsTotal ?: 0,
            requestId = id, requestedAt = now, signatureChanged = signatureChanged,
        )
        entries[pkg] = entry
        events += { listener?.onPending(entry) }
        return Admission.Pending(id)
    }

    /** 记录满了时清掉最久没动的 DENIED 项；仍然满返回 false。 */
    private fun makeRoom(): Boolean {
        if (entries.size < config.maxEntries) return true
        val victim = entries.values.filter { it.state == CallerState.DENIED }.minByOrNull { it.decidedAt ?: 0L } ?: return false
        entries.remove(victim.packageName)
        persistQuietly()
        return true
    }

    /** 过期的授权提示按拒绝记（算一次拒绝，进入冷却）。持锁调用。 */
    private fun expirePending(now: Long, events: MutableList<() -> Unit>) {
        for (e in entries.values.toList()) {
            val at = e.requestedAt ?: continue
            if (e.state != CallerState.PENDING || now - at < config.pendingTtlMillis) continue
            entries[e.packageName] = e.copy(
                state = CallerState.DENIED, decidedAt = now, deniedUntil = now + config.denyCooldownMillis, requestId = null, requestedAt = null,
            )
            persistQuietly()
            events += resolvedEvent(e, CallerState.DENIED, CallerResolution.TIMED_OUT)
        }
    }

    private fun resolvedEvent(e: CallerEntry, state: CallerState, how: CallerResolution): () -> Unit {
        val id = e.requestId ?: return {}
        return { listener?.onResolved(id, state, how) }
    }

    // ------------------------------------------------------------------ 用户的决定

    /** 授权提示的回答（IAgentControl.answerAuthorization）。返回是否采纳（已结案、已超时、不存在返回 false）。 */
    fun decide(requestId: String?, allow: Boolean): Boolean {
        if (requestId.isNullOrEmpty()) return false
        val events = ArrayList<() -> Unit>()
        val ok = synchronized(lock) {
            val now = clock()
            expirePending(now, events)
            val e = entries.values.firstOrNull { it.requestId == requestId && it.state == CallerState.PENDING } ?: return@synchronized false
            apply(e, if (allow) CallerState.ALLOWED else CallerState.DENIED, now, events)
            true
        }
        events.forEach { it() }
        return ok
    }

    /**
     * 设置页的操作（IAgentControl.setAcpCaller）。state：allowed / denied / removed。返回更新后的记录（removed 返回 null）。
     * @throws IllegalArgumentException agentos.acp.not_found / agentos.acp.bad_state
     * @throws IllegalStateException agentos.acp.registry_unavailable（写文件失败，状态不变）
     */
    fun set(packageName: String?, state: String?): CallerEntry? {
        val target = when (state) {
            "allowed" -> CallerState.ALLOWED
            "denied" -> CallerState.DENIED
            "removed" -> null
            else -> throw IllegalArgumentException("agentos.acp.bad_state: state must be allowed, denied or removed")
        }
        val events = ArrayList<() -> Unit>()
        val result = synchronized(lock) {
            val now = clock()
            expirePending(now, events)
            val e = entries[packageName ?: ""] ?: throw IllegalArgumentException("agentos.acp.not_found: no such app")
            if (target == null) {
                val before = entries.toMap()
                entries.remove(e.packageName)
                promptTimes.remove(e.packageName)
                try {
                    persist()
                } catch (x: IOException) {
                    entries.clear(); entries.putAll(before)
                    throw IllegalStateException("agentos.acp.registry_unavailable: could not write the caller registry")
                }
                events += { listener?.onRevoked(e.packageName, e.signingDigest) }
                events += resolvedEvent(e, CallerState.DENIED, CallerResolution.CANCELLED)
                null
            } else {
                apply(e, target, now, events)
            }
        }
        events.forEach { it() }
        return result
    }

    /** 把 [e] 改成 [target]（ALLOWED / DENIED）。持锁调用；写失败时恢复并抛 registry_unavailable。 */
    private fun apply(e: CallerEntry, target: CallerState, now: Long, events: MutableList<() -> Unit>): CallerEntry {
        val next = when (target) {
            CallerState.ALLOWED -> e.copy(state = CallerState.ALLOWED, decidedAt = now, deniedUntil = null, requestId = null, requestedAt = null)
            else -> e.copy(
                state = CallerState.DENIED, decidedAt = now, deniedUntil = now + config.denyCooldownMillis, requestId = null, requestedAt = null,
            )
        }
        entries[e.packageName] = next
        try {
            persist()
        } catch (x: IOException) {
            entries[e.packageName] = e
            throw IllegalStateException("agentos.acp.registry_unavailable: could not write the caller registry")
        }
        events += resolvedEvent(e, next.state, CallerResolution.ANSWERED)
        // 已允许 → 拒绝：撤销（待决 → 拒绝时它还没有通道，也无妨）
        if (next.state == CallerState.DENIED && e.state != CallerState.DENIED) events += { listener?.onRevoked(e.packageName, e.signingDigest) }
        return next
    }

    // ------------------------------------------------------------------ 调试入口专用（debug 构建的 AcpCallerDebugReceiver）

    /**
     * 直接记一条“已允许”（没有经过授权提示）。**只有 debug 的调试入口调用**：release 里没有任何入口能走到这里。
     * 已有的同包名记录被替换（旧签名的通道照常撤销）。
     */
    fun grant(packageName: String, signingDigest: String, label: String): CallerEntry {
        val events = ArrayList<() -> Unit>()
        val result = synchronized(lock) {
            val now = clock()
            val old = entries[packageName]
            val entry = CallerEntry(
                packageName, signingDigest, label, CallerState.ALLOWED,
                firstSeenAt = old?.firstSeenAt ?: now, decidedAt = now, lastUsedAt = old?.lastUsedAt, promptsTotal = old?.promptsTotal ?: 0,
            )
            entries[packageName] = entry
            try {
                persist()
            } catch (x: IOException) {
                if (old != null) entries[packageName] = old else entries.remove(packageName)
                throw IllegalStateException("agentos.acp.registry_unavailable: could not write the caller registry")
            }
            if (old != null) {
                events += resolvedEvent(old, CallerState.ALLOWED, CallerResolution.CANCELLED)
                if (old.signingDigest != signingDigest) events += { listener?.onRevoked(old.packageName, old.signingDigest) }
            }
            entry
        }
        events.forEach { it() }
        return result
    }

    /** 删除全部记录并撤销它们（调试入口的 clear）。返回删掉的个数。 */
    fun clear(): Int {
        val events = ArrayList<() -> Unit>()
        val n = synchronized(lock) {
            val all = entries.values.toList()
            val before = entries.toMap()
            entries.clear(); promptTimes.clear()
            try {
                persist()
            } catch (x: IOException) {
                entries.putAll(before)
                throw IllegalStateException("agentos.acp.registry_unavailable: could not write the caller registry")
            }
            for (e in all) {
                events += { listener?.onRevoked(e.packageName, e.signingDigest) }
                events += resolvedEvent(e, CallerState.DENIED, CallerResolution.CANCELLED)
            }
            all.size
        }
        events.forEach { it() }
        return n
    }

    /** 换参数（只给 debug 调试入口用）。 */
    fun overrideConfig(c: CallerConfig) {
        config = c
    }

    // ------------------------------------------------------------------ 用量

    /** 一个 prompt 完成（A 的 CallerQuota 每次完成回调一次）：计数、最近使用时间。 */
    fun recordPrompt(packageName: String, signingDigest: String) {
        synchronized(lock) {
            val e = entries[packageName]?.takeIf { it.signingDigest == signingDigest && it.state == CallerState.ALLOWED } ?: return
            val now = clock()
            entries[packageName] = e.copy(lastUsedAt = now, promptsTotal = e.promptsTotal + 1)
            promptTimes.getOrPut(packageName) { ArrayDeque() }.addLast(now)
            persistQuietly()
        }
    }

    fun promptsLastHour(packageName: String): Int = synchronized(lock) {
        val q = promptTimes[packageName] ?: return@synchronized 0
        val cutoff = clock() - config.usageWindowMillis
        while (q.isNotEmpty() && q.first() < cutoff) q.removeFirst()
        q.size
    }

    // ------------------------------------------------------------------ 查询

    /** 某个 App 现在的记录（过期的授权提示先结案）；没有为 null。 */
    fun entry(packageName: String): CallerEntry? = synchronized(lock) {
        val events = ArrayList<() -> Unit>()
        expirePending(clock(), events)
        val e = entries[packageName]
        events.forEach { it() }
        e
    }

    /** 全部记录，按最近使用降序（没用过的在后，再按 label）。 */
    fun entries(): List<CallerEntry> {
        val events = ArrayList<() -> Unit>()
        val list = synchronized(lock) {
            expirePending(clock(), events)
            entries.values.toList()
        }
        events.forEach { it() }
        return list.sortedWith(compareByDescending<CallerEntry> { it.lastUsedAt ?: Long.MIN_VALUE }.thenBy { it.label.lowercase() })
    }

    /** 待决的授权提示。 */
    fun pending(): List<CallerEntry> = entries().filter { it.state == CallerState.PENDING }

    // ------------------------------------------------------------------ JSON（IAgentControl.listAcpCallers）

    /**
     * 一项的 JSON：键固定、顺序固定、不省略（空值为 null）。[activeChannels]、[activeTasks] 来自 :agent 的连接表。
     */
    fun toJson(e: CallerEntry, activeChannels: Int = 0, activeTasks: Int = 0): JsonObject {
        fun num(v: Long?) = v?.let { JsonPrimitive(it) } ?: JsonNull
        fun str(v: String?) = v?.let { JsonPrimitive(it) } ?: JsonNull
        return buildJsonObject {
            put("packageName", e.packageName)
            put("label", e.label)
            put("signingDigest", e.signingDigest)
            put("state", e.state.wire)
            put("requestId", str(e.requestId))
            put("firstSeenAt", e.firstSeenAt)
            put("decidedAt", num(e.decidedAt))
            put("lastUsedAt", num(e.lastUsedAt))
            put("deniedUntil", num(e.deniedUntil?.takeIf { it > clock() }))
            put("requestedAt", num(e.requestedAt))
            put("usage", buildJsonObject {
                put("promptsTotal", e.promptsTotal)
                put("promptsLastHour", promptsLastHour(e.packageName))
                put("activeChannels", activeChannels)
                put("activeTasks", activeTasks)
            })
        }
    }

    // ------------------------------------------------------------------ 存取

    private fun load() {
        val attempt = read(primary)
        when (attempt) {
            is Read.Missing -> health = CallerHealth.Ok
            is Read.Good -> {
                entries.putAll(attempt.entries.associateBy { it.packageName })
                health = CallerHealth.Ok
            }
            is Read.Bad -> {
                // 坏文件留一份供诊断（尽力而为）
                runCatching { primary.readText()?.let { quarantine?.writeAtomically(it) } }
                val fromBackup = backup?.let { read(it) as? Read.Good }
                if (fromBackup != null) {
                    entries.putAll(fromBackup.entries.associateBy { it.packageName })
                    health = CallerHealth.Corrupt(attempt.reason, CallerHealth.Using.BACKUP)
                } else {
                    health = CallerHealth.Corrupt(attempt.reason, CallerHealth.Using.EMPTY)
                }
            }
        }
    }

    private sealed interface Read {
        data object Missing : Read
        data class Good(val entries: List<CallerEntry>) : Read
        data class Bad(val reason: String) : Read
    }

    private fun read(file: AtomicTextFile): Read {
        val text = try {
            file.readText() ?: return Read.Missing
        } catch (e: IOException) {
            return Read.Bad("unreadable: ${e.javaClass.simpleName}")
        }
        return try {
            Read.Good(parse(text))
        } catch (e: Exception) {
            Read.Bad("invalid: ${e.javaClass.simpleName}")
        }
    }

    private fun persist() {
        val text = serialize()
        primary.writeAtomically(text)
        health = if (health is CallerHealth.Corrupt) CallerHealth.Ok else health
        try {
            backup?.writeAtomically(text)
            lastBackupError = null
        } catch (e: IOException) {
            lastBackupError = e.javaClass.simpleName
        }
    }

    /** 写失败不阻断准入（用量、标签这类非决定性的更新）；决定性的更新用 [persist] 并向上报告。 */
    private fun persistQuietly() {
        try {
            persist()
        } catch (e: IOException) {
            lastBackupError = "primary: ${e.javaClass.simpleName}"
        }
    }

    private fun serialize(): String = buildJsonObject {
        put("version", FORMAT_VERSION)
        put("callers", buildJsonArray {
            for (e in entries.values) {
                if (e.state == CallerState.PENDING) continue
                add(buildJsonObject {
                    put("packageName", e.packageName)
                    put("signingDigest", e.signingDigest)
                    put("label", e.label)
                    put("state", e.state.wire)
                    put("firstSeenAt", e.firstSeenAt)
                    put("decidedAt", e.decidedAt?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("lastUsedAt", e.lastUsedAt?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("deniedUntil", e.deniedUntil?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("promptsTotal", e.promptsTotal)
                })
            }
        })
    }.toString()

    private fun parse(text: String): List<CallerEntry> {
        val root = Json.parseToJsonElement(text) as JsonObject
        val version = (root["version"] as? JsonPrimitive)?.longOrNull ?: throw IllegalArgumentException("no version")
        require(version >= 1) { "bad version" }
        val list = root["callers"] as? JsonArray ?: throw IllegalArgumentException("no callers")
        return list.map { el ->
            val o = el as JsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            fun l(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            // 不认识的状态按“已拒绝、冷却已过”处理（最严：下次 open 重新询问，绝不当作允许）
            val state = CallerState.entries.firstOrNull { it.wire == s("state") && it != CallerState.PENDING } ?: CallerState.DENIED
            CallerEntry(
                packageName = s("packageName") ?: throw IllegalArgumentException("caller without packageName"),
                signingDigest = s("signingDigest") ?: throw IllegalArgumentException("caller without signingDigest"),
                label = s("label") ?: s("packageName")!!,
                state = state,
                firstSeenAt = l("firstSeenAt") ?: 0L,
                decidedAt = l("decidedAt"),
                lastUsedAt = l("lastUsedAt"),
                deniedUntil = if (state == CallerState.DENIED) l("deniedUntil") ?: 0L else null,
                promptsTotal = l("promptsTotal") ?: 0L,
            )
        }
    }

    companion object {
        const val FORMAT_VERSION = 1
    }
}
