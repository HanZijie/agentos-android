package org.agentos.runtime.ports

import androidx.sqlite.SQLiteDriver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import org.agentos.runtime.broker.ApprovalPolicy
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.errors.ErrorInfo

/*
 * HostPort：宿主层（core/runtime，纯 JVM）对 Android 的全部依赖。
 *
 * - 实现：C lane 的 app/.../agent/HostPortImpl.kt（W6 起；W16 工具与确认，W20 Skill，W22 Hook 逐步补齐）。
 * - 测试：testFixtures 里的 FakeHostPort（内存工具目录、脚本化确认、临时目录里的真实 SQLite、手动时钟）。
 * - 每个子端口都有 M1 可以直接用的空实现（NONE / DENY_ALL），W6 可以先只实现 storage、secrets、models、clock、log。
 *
 * 规则：
 * - core/runtime 只通过这里接触 Android；这里的类型都不依赖 Android。
 * - 所有挂起方法都可以被取消；取消表示宿主层不再需要结果（例如 Pi 这一轮被 abort）。
 * - 凭据只出现在 SecretPort 的返回值里，由网络出口使用；不得进入日志、事件、异常消息和 JS。
 */
interface HostPort {
    /** 工具目录与调用（Extension Host 经 IExtensionHost，W14–W16）。 */
    val tools: ToolPort

    /** Skill 目录与读取（W20）。 */
    val skills: SkillPort

    /** Hook 事件派发与决定合并（W22）。 */
    val hooks: HookPort

    /** 工具调用的用户确认（ConsentCoordinator，W16）。 */
    val consent: ConsentPort

    /**
     * 用户的工具策略（按插件、服务器、工具启用或禁用，审批方式；W16，界面是 D 的插件管理页）。
     * 有默认实现（全部启用、每次确认），已有的 HostPort 实现不用改；Extension Host 接上之后覆盖它。
     */
    val approvals: ApprovalPolicyPort get() = ApprovalPolicyPort.DEFAULT

    /** Store 用的 SQLite。 */
    val storage: StoragePort

    /** 用户当前选择的模型（BYOK 配置里不含 key 的部分，F9）。 */
    val models: ModelConfigPort

    /** 模型 key（Keystore 加密保存，F9）。 */
    val secrets: SecretPort

    val clock: Clock

    /** safe mode 等由 Android 侧决定的运行环境（W11）。 */
    val environment: EnvironmentPort

    val log: RuntimeLog
}

// ---------------------------------------------------------------- 工具

interface ToolPort {
    /** 当前启用的工具目录；变化时 version 增加。Broker 只放行目录里的工具名（W2）。 */
    val catalog: StateFlow<ToolCatalog>

    /**
     * 调用一个工具。必须区分三种结局，恢复流程依赖它（F8“结果未知不重放”）：
     * - [ToolInvocationResult.Completed]：工具返回了结果（结果本身可以是 isError）；
     * - [ToolInvocationResult.NotDispatched]：请求确定没有到达工具提供方（未连接、bind 失败、已从目录移除），没有副作用；
     * - [ToolInvocationResult.Unknown]：请求已经发出，但没有拿到结果（提供方进程死亡、调用超时），副作用未知。
     *
     * 协程被取消时，实现尽力把取消转给提供方（MCP 的取消通知），然后以 CancellationException 结束。
     */
    suspend fun invoke(invocation: ToolInvocation): ToolInvocationResult

    /**
     * **任务开始前的准备**：Scheduler 在为这个任务构造 Agent 的工具目录和系统提示之前调用一次，最多等 [timeoutMillis]
     * （`SchedulerConfig.toolPrepareTimeoutMillis`，默认 2 秒）。Extension Host 借此保证模型看到的目录不是空的：
     * 等“还没有缓存”的 MCP 服务器取完工具列表（刚启用、刚开机）。默认什么都不做。
     *
     * 实现必须在 [timeoutMillis] 内返回（Scheduler 也会用同样的时限强制取消）；抛异常只会被记录，任务照常开始。
     */
    suspend fun prepare(timeoutMillis: Long) {}

    companion object {
        /** 没有任何工具（M1）。 */
        val NONE: ToolPort = object : ToolPort {
            override val catalog: StateFlow<ToolCatalog> = MutableStateFlow(ToolCatalog.EMPTY).asStateFlow()

            override suspend fun invoke(invocation: ToolInvocation) = ToolInvocationResult.NotDispatched(
                ErrorCode.TOOL_NOT_IN_CATALOG.info("no tools are available"),
            )
        }
    }
}

data class ToolCatalog(val version: Long, val tools: List<CatalogTool>) {
    private val byName: Map<String, CatalogTool> = tools.associateBy { it.name }

    init {
        require(byName.size == tools.size) { "duplicate tool names in catalog" }
    }

    operator fun get(name: String): CatalogTool? = byName[name]

    companion object {
        val EMPTY = ToolCatalog(0, emptyList())
    }
}

/**
 * 目录里的一个工具。
 *
 * @property name 交给模型的工具名（core/extensions 的 ToolNaming 生成，W14）。
 * @property risk 风险等级，**已经算好**：Extension Host 用 `RiskPolicy.effectiveRisk` 把默认等级（MCP 工具 WRITE）和服务端注解
 *   （只能调高）合成后放进来；用户策略（启用、审批方式）不在这里，由 Broker 按 [source] 查 `HostPort.approvals`。
 * @property provider 提供方：插件 ID，或 "agentos"（自带插件、内置 read_skill）。只用于展示和审计。
 * @property source 这个工具来自哪个插件的哪个服务器（MCP 的原始工具名）：用户策略（ApprovalPolicy）据此匹配。
 *   宿主层自己注册的、不属于任何插件的工具为 null，不受用户策略约束。
 */
data class CatalogTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val risk: ToolRisk = ToolRisk.WRITE,
    val provider: String,
    val title: String? = null,
    val source: ToolSource? = null,
)

/**
 * 一个 MCP 工具的来源：插件名、服务器名、MCP 服务器报告的**原始**工具名（不是 ToolNaming 处理后的名字——
 * 处理后的名字会因为目录里别的工具而变，策略要在这种变化下保持不变）。
 */
data class ToolSource(val plugin: String, val server: String, val tool: String)


/** docs/architecture.md F5 的三级。 */
enum class ToolRisk { READ, WRITE, HIGH }

data class ToolInvocation(
    val sessionId: String,
    val taskId: String,
    val toolCallId: String,
    val name: String,
    val arguments: JsonObject,
    val caller: CallerIdentity,
    /** 这次调用的超时；超时后按 Unknown 处理（已发出）或 NotDispatched（未发出）。 */
    val timeoutMillis: Long,
)

sealed interface ToolInvocationResult {
    data class Completed(val result: ToolResult) : ToolInvocationResult

    data class NotDispatched(val error: ErrorInfo) : ToolInvocationResult

    data class Unknown(val error: ErrorInfo) : ToolInvocationResult
}

// ---------------------------------------------------------------- Skill

interface SkillPort {
    val catalog: StateFlow<SkillCatalog>

    /**
     * 读取 Skill 的 SKILL.md（[path] 为 null）或同一 Skill 目录里的附属文件（[path] 相对 Skill 目录）。内容是**不可信输入**。
     *
     * @param skillId [SkillSummary.id]
     * @throws NoSuchElementException 没有这个 Skill（包括插件被禁用、不再可用），或这个 Skill 里没有这个文件
     * @throws IllegalArgumentException [path] 不合法（路径穿越、绝对路径、控制字符…）或文件不是文本
     */
    suspend fun read(skillId: String, path: String? = null): SkillContent

    companion object {
        val NONE: SkillPort = object : SkillPort {
            override val catalog: StateFlow<SkillCatalog> = MutableStateFlow(SkillCatalog.EMPTY).asStateFlow()

            override suspend fun read(skillId: String, path: String?): SkillContent =
                throw NoSuchElementException("no skills are available")
        }
    }
}

data class SkillCatalog(val version: Long, val skills: List<SkillSummary>) {
    companion object {
        val EMPTY = SkillCatalog(0, emptyList())
    }
}

/**
 * 目录里的一个 Skill。[name]、[description] 来自插件（**第三方文本，不可信**）。
 *
 * @property id 唯一的标识，`read_skill` 用它：名字不冲突时就是 [name]，冲突时是 `<插件名>:<Skill 名>`
 * @property name Skill 自己的名字（SKILL.md 的 frontmatter）
 * @property provider 来源插件的名字
 */
data class SkillSummary(val id: String, val name: String, val description: String, val provider: String)

/** [truncated] 为 true 表示内容超过单次读取上限，已经截断。 */
data class SkillContent(val text: String, val truncated: Boolean = false)

// ---------------------------------------------------------------- Hook

interface HookPort {
    /** 派发一个 Hook 事件，取回合并后的决定（HookEngine + DecisionMerger，W22）。没有匹配的 Hook 时返回 [HookOutcome.NONE]。 */
    suspend fun dispatch(request: HookRequest): HookOutcome

    companion object {
        val NONE: HookPort = object : HookPort {
            override suspend fun dispatch(request: HookRequest) = HookOutcome.NONE
        }
    }
}

/**
 * @property hookEvent Hook 事件名，首批见 core/protocol/hooks-v1.md（W22），例如 PreToolUse、PostToolUse、UserPromptSubmit、Stop。
 * @property input 按 hooks-v1 的输入字段组织的 JSON。
 */
data class HookRequest(
    val hookEvent: String,
    val sessionId: String,
    val taskId: String?,
    val input: JsonObject,
)

/**
 * 合并后的决定。Hook 可以拒绝、要求确认、改写输入、补充上下文，但**不能替用户同意**：
 * [HookDecision.ALLOW] 不跳过风险策略要求的确认（architecture F5）。
 */
data class HookOutcome(
    val matched: Int,
    val decision: HookDecision,
    val reason: String? = null,
    val updatedInput: JsonObject? = null,
    val additionalContext: String? = null,
    val errors: List<ErrorInfo> = emptyList(),
) {
    companion object {
        val NONE = HookOutcome(matched = 0, decision = HookDecision.NO_OPINION)
    }
}

enum class HookDecision { NO_OPINION, ALLOW, ASK, DENY }

// ---------------------------------------------------------------- 确认

interface ConsentPort {
    /**
     * 请用户确认一次工具调用：App 在前台弹确认界面，否则发带“允许 / 拒绝”的通知；[ConsentRequest.timeoutMillis] 内无响应视为拒绝。
     * 调用方 App 的回答（session/request_permission）只能追加拒绝，不经过这里。
     */
    suspend fun request(request: ConsentRequest): ConsentDecision

    companion object {
        /** 还没有确认界面时（M1）：一律拒绝。 */
        val DENY_ALL: ConsentPort = object : ConsentPort {
            override suspend fun request(request: ConsentRequest) = ConsentDecision.Deny(ConsentDecision.DenyReason.UNAVAILABLE)
        }
    }
}

/**
 * @property argumentsPreview 给用户看的参数摘要：宿主层已脱敏并截断（不超过 2,000 字符）。
 * @property argumentsTruncated [argumentsPreview] 是不是被截断过（完整参数更长）。
 * @property rememberable 是否提供“本会话内不再询问”（高风险工具为 false）。
 * @property source 工具来自哪个插件的哪个服务器（确认框显示“来自插件 X”用）；不属于任何插件的工具为 null。
 */
data class ConsentRequest(
    val requestId: String,
    val sessionId: String,
    val taskId: String,
    val toolCallId: String,
    val toolName: String,
    val toolTitle: String?,
    val risk: ToolRisk,
    val caller: CallerIdentity,
    val argumentsPreview: String,
    val rememberable: Boolean,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val source: ToolSource? = null,
    val argumentsTruncated: Boolean = false,
) {
    companion object {
        /** architecture F5：60 秒无响应视为拒绝。 */
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
    }
}

sealed interface ConsentDecision {
    data class Allow(val rememberForSession: Boolean = false) : ConsentDecision

    data class Deny(val reason: DenyReason) : ConsentDecision

    enum class DenyReason { USER, TIMEOUT, UNAVAILABLE }
}

// ---------------------------------------------------------------- 用户的工具策略

/**
 * 用户的工具策略的来源。Extension Host 持久化 [ApprovalPolicy]（`toJson` 的格式），改动后发布新值；
 * Broker 每次调用前读 [policy] 的当前值，所以用户在插件页里禁用一个工具或改审批方式，对正在进行的任务从下一次调用起生效。
 */
interface ApprovalPolicyPort {
    val policy: StateFlow<ApprovalPolicy>

    companion object {
        /** 没有用户策略：全部启用、每次确认。 */
        val DEFAULT: ApprovalPolicyPort = object : ApprovalPolicyPort {
            override val policy: StateFlow<ApprovalPolicy> = MutableStateFlow(ApprovalPolicy.DEFAULT).asStateFlow()
        }
    }
}

// ---------------------------------------------------------------- 存储

/**
 * Store 的 SQLite。core/runtime 自己管理 schema、迁移和全部 SQL（store/，W2），Android 与电脑上的测试共用同一套。
 *
 * - Android：`BundledSQLiteDriver`（与电脑测试同一份 SQLite）或 `AndroidSQLiteDriver`（系统 SQLite），见 libs.versions.toml。
 * - 电脑测试：`BundledSQLiteDriver`。
 */
interface StoragePort {
    val driver: SQLiteDriver

    /** 数据库文件的绝对路径，放在 :agent 的私有目录（CE 存储）。父目录由实现保证存在。 */
    val databasePath: String
}

// ---------------------------------------------------------------- 模型与密钥

interface ModelConfigPort {
    /** 用户当前选择的模型；没配置时为 null，任务以 model_not_configured 失败。变化后从下一轮起生效，不影响进行中的一轮。 */
    val activeModel: StateFlow<ModelSpec?>
}

interface SecretPort {
    /**
     * 按请求 URL 找 key：与 key 绑定的 endpoint（厂商的 baseUrl，或自定义端点）匹配——scheme、host、port 完全相同，
     * 路径在段边界上匹配（`https://api.example.com/v1` 匹配 `…/v1/messages`，不匹配 `…/v10` 或别的 host）；
     * 多个 endpoint 都匹配时取路径最长的。**不是字符串前缀匹配**：`https://api.example.com.evil.net/…` 拿不到
     * `https://api.example.com` 的 key。参考实现是 `net/BaseUrlCredentials`（生产的 KeystoreSecrets 委托给它）。
     * 只由网络出口（net/HostFetch）调用；找不到时返回 null，网络出口不发请求（model_not_configured）。
     */
    suspend fun credentialFor(url: String): Credential?

    /**
     * 撤销信号（architecture F9：清除 = 立即作废）。用户**清除**模型来源时，被丢掉的每个 [Credential]（当前的和
     * 换下来还留在内存里的）各发一次；网络出口（net/HostFetch，B）据此中止正在用这个 key 传输的 HTTP 响应，
     * 那次请求以 `model_not_configured`（`details.reason = key_revoked`）结束。之后 [credentialFor] 对它原来的端点一律返回 null。
     *
     * - 按**对象身份**比较：发出的就是 [credentialFor] 当初返回的那个对象（[Credential] 不重写 equals / hashCode），
     *   订阅方用 `===` 判断某个请求用的 key 是否被撤销，不比较 key 的内容；
     * - **更换** key（setModelSource，热加载）**不发**：换下来的 key 继续服务进行中的那一轮，运行时空闲后才丢弃；
     * - 默认实现是空流：不支持撤销的实现（测试的假实现、电脑上的实现）不用改。生产实现（KeystoreSecrets，C）在清除时发送。
     *   是热流，没有重放：只对订阅之后的撤销生效，网络出口在发请求之前订阅。
     */
    val revocations: Flow<Credential> get() = emptyFlow()
}

/**
 * 一个 key。toString 不泄露内容；只有网络出口在组装请求头时调用 [reveal]。
 * **不重写 equals / hashCode**：两个内容相同的 Credential 是两个不同的 key 实例，[SecretPort.revocations] 按对象身份比较。
 */
class Credential(private val secret: String) {
    init {
        require(secret.isNotEmpty()) { "empty credential" }
    }

    fun reveal(): String = secret

    /** 界面上只显示首尾各 4 位（F9）。 */
    fun masked(): String = if (secret.length <= 8) "****" else "${secret.take(4)}…${secret.takeLast(4)}"

    override fun toString(): String = "Credential(****)"
}

// ---------------------------------------------------------------- 时钟、环境、日志

interface Clock {
    /** 墙钟毫秒：事件 timestamp、deadline 的持久化。 */
    fun nowMillis(): Long

    /** 单调时钟纳秒：耗时统计。 */
    fun monotonicNanos(): Long

    companion object {
        val SYSTEM: Clock = object : Clock {
            override fun nowMillis() = System.currentTimeMillis()

            override fun monotonicNanos() = System.nanoTime()
        }
    }
}

interface EnvironmentPort {
    /** safe mode 下运行时不自动继续恢复出来的任务（F13、W11）。 */
    val safeMode: StateFlow<SafeModeState>

    /**
     * 上一个 `:agent` 进程是不是被用户主动停止的（S2 监督契约 a 第 6 条：`ApplicationExitInfo` 的
     * `REASON_USER_REQUESTED` / `REASON_USER_STOPPED`，由 W6 在进程启动时算好）。
     *
     * 为 true 时，启动恢复流程把恢复出来的排队任务取消（`task.cancelled { by: user_stop }`），不计入 runState，
     * 运行时不会因为它们进入前台；结果未知的任务照常暂停等用户决定（本来就不重放）。只在 `AgentRuntime.start()` 时读一次。
     */
    val previousExitStoppedByUser: Boolean get() = false

    companion object {
        val NORMAL: EnvironmentPort = object : EnvironmentPort {
            override val safeMode: StateFlow<SafeModeState> = MutableStateFlow(SafeModeState.OFF).asStateFlow()
        }
    }
}

data class SafeModeState(val active: Boolean, val reason: String? = null) {
    companion object {
        val OFF = SafeModeState(false)
    }
}

/** 运行时日志。消息里不得出现 key、完整 prompt、工具参数原文（W11 诊断页也按这条）。 */
interface RuntimeLog {
    fun log(level: Level, tag: String, message: String, error: Throwable? = null)

    enum class Level { DEBUG, INFO, WARN, ERROR }

    companion object {
        val NONE: RuntimeLog = object : RuntimeLog {
            override fun log(level: Level, tag: String, message: String, error: Throwable?) = Unit
        }
    }
}

fun RuntimeLog.debug(tag: String, message: String) = log(RuntimeLog.Level.DEBUG, tag, message)

fun RuntimeLog.info(tag: String, message: String) = log(RuntimeLog.Level.INFO, tag, message)

fun RuntimeLog.warn(tag: String, message: String, error: Throwable? = null) = log(RuntimeLog.Level.WARN, tag, message, error)

fun RuntimeLog.error(tag: String, message: String, error: Throwable? = null) = log(RuntimeLog.Level.ERROR, tag, message, error)
