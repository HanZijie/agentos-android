package org.agentos.runtime.consent

import org.agentos.runtime.i18n.MessageRef
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource

/*
 * 确认协调器（architecture F5，docs/extensions.md 5.4）与 Android 之间的**接缝**与显示模型。
 * 协调器（[ConsentCoordinator]）是纯 JVM：排队、超时、取消、选项校验、“始终允许”的写回、文案；界面（对话框、通知、进程间通信）由 D 实现 [ConsentSurface]。
 */

/** 用户在确认界面上可以做的选择。能选哪些由请求决定（[ConsentView.options]）。 */
enum class ConsentChoice {
    /** 允许这一次。 */
    ALLOW_ONCE,

    /** 本会话内不再询问这个工具（只有写级工具、且调用方提供了“本会话内不再询问”）。 */
    ALLOW_FOR_SESSION,

    /** 始终允许这个工具（写进用户策略；只有写级、且有来源插件的工具）。 */
    ALWAYS_ALLOW,

    /** 拒绝。 */
    DENY,
}

/** 一个可选项，连同界面上显示的文案（key，见 [ConsentMessages]；用哪种语言由界面定）。[destructive] 为 true 的是拒绝类，界面用次要样式。 */
data class ConsentOption(val choice: ConsentChoice, val label: MessageRef, val destructive: Boolean = false)

/** 界面显示的醒目程度：只读 / 写 / 高风险。 */
enum class ConsentSeverity { NORMAL, ELEVATED, CRITICAL }

/**
 * 发起者。[kind] 与 [uid] 来自 `CallerIdentity`（宿主层根据 UID 解析，不来自客户端）。
 * [packageName]：第三方 App（`CallerKind.APP`）发起时的**包名**（宿主层按 UID 解析，已清理、单行、最多 128 字符），界面把它解析成 App 的名字和图标
 * （解析不到就显示包名）；其他调用方、以及不知道包名时为 null。**不是 App 自己起的显示名**（那个只出现在 [ConsentView.initiatorLine] 里）：显示名谁都能随便写。
 */
data class ConsentCaller(val kind: CallerKind, val uid: Int, val packageName: String?)

/**
 * 一条待确认请求的**显示模型**：纯数据，界面按它画。所有来自第三方的文字（工具名、参数、插件名、服务器名）都已经清理过
 * （去控制字符和不可见格式字符——包括双向文字控制符，折叠空白，截断，引号类字符换成 ASCII 单引号），并且只放在它们自己的字段里；
 * 界面自己的文案（[title]、[initiatorLine]、[sourceLine]、[riskLabel]、[riskDescription]、[ConsentOption.label]）**核心层不写成文字**，只给
 * [MessageRef]（文案 key + 参数，key 见 [ConsentMessages]），由 app 层按界面语言的模板填出来。第三方文字只出现在 [MessageRef.args] 里，
 * 模板用引号或括号把它们框起来、只占一行、有长度上限；所有语言的定界符都已从参数里去掉（[org.agentos.runtime.i18n.FrameChars]），第三方伪造不了结尾。
 *
 * @property title 标题：[ConsentMessages.TITLE]（工具显示名）。中文 `要允许「…」吗？`，英文 `Allow “…”?`
 * @property initiatorLine 发起者一行：第三方 App 知道包名时 [ConsentMessages.INITIATOR_APP]（名字、包名；包名一定在、名字先被截断），
 *   只有一个名字时 [ConsentMessages.INITIATOR_NAMED]；界面可用 [caller] 换成 App 名和图标；电脑端、AgentOS 自己、运行时是固定 key
 * @property sourceLine 来源一行：[ConsentMessages.SOURCE]（插件名、服务器名）；不属于任何插件的工具为 null
 * @property toolDisplayName 工具显示名（title 优先，否则原始名），已清理，单行
 * @property toolName 模型调用的工具名（已清理）
 * @property argumentsPreview 参数摘要（已清理、已截断到显示上限）
 * @property argumentsTruncated 参数摘要是否被截断（界面要标明“已截断”）
 * @property risk 风险等级；[severity]、[riskLabel]、[riskDescription] 是它的显示形式（高风险写明“可能不可恢复”）
 * @property options 可选项，顺序就是界面顺序；总有 [ConsentChoice.ALLOW_ONCE] 和 [ConsentChoice.DENY]
 * @property deadlineMillis 超时的墙钟时间（毫秒）；界面用它倒计时。**从请求创建时起算，排队时间也计入**
 * @property timeoutMillis 总时限（毫秒）
 * @property queuePosition 在待确认队列里的位置，0 = 队首（界面先显示这一条）；其他的按先进先出排在后面
 * @property queueSize 现在待确认的总数
 */
data class ConsentView(
    val requestId: String,
    val sessionId: String,
    val taskId: String,
    val title: MessageRef,
    val initiatorLine: MessageRef,
    val caller: ConsentCaller,
    val sourceLine: MessageRef?,
    val source: ToolSource?,
    val toolDisplayName: String,
    val toolName: String,
    val argumentsPreview: String,
    val argumentsTruncated: Boolean,
    val risk: ToolRisk,
    val severity: ConsentSeverity,
    val riskLabel: MessageRef,
    val riskDescription: MessageRef,
    val options: List<ConsentOption>,
    val createdAtMillis: Long,
    val deadlineMillis: Long,
    val timeoutMillis: Long,
    val queuePosition: Int,
    val queueSize: Int,
)

/** 一条请求为什么结束。 */
enum class ConsentEnd {
    /** 用户答复了（[ConsentResolution.choice]）。 */
    ANSWERED,

    /** 超时没有答复，按拒绝处理。 */
    TIMED_OUT,

    /** 调用方协程被取消（任务被取消）：界面要撤回对话框和通知。 */
    CANCELLED,

    /** 协调器关闭（进程退出）。 */
    CLOSED,
}

/**
 * 请求结案。[choice] 只在 [ConsentEnd.ANSWERED] 时有值；[notice] 是要告诉用户的一句话（例如“始终允许”没能保存、这次按“允许一次”处理），
 * 没有为 null。
 */
data class ConsentResolution(val end: ConsentEnd, val choice: ConsentChoice? = null, val notice: MessageRef? = null)

/**
 * 确认界面（**Android 接缝，由 D 实现**）。协调器只负责“现在有哪些待确认”和“谁该被通知”；前台时 App 把待确认渲染成对话框，
 * 后台时发带“允许 / 拒绝”的通知，用户点通知或打开 App 后看到的是同一份待确认（[ConsentCoordinator.pending]）。
 * 通知的发送和撤回由这两个回调驱动：[requested] 发，[resolved]（答复、超时、取消、关闭都会来）撤，实现不需要自己跟踪状态。
 *
 * 回调由协调器在**单独的协程里按发生顺序依次**调用，**不会阻塞**发起确认的任务；实现里抛出的异常被记录后忽略。
 * 回调里可以调用 [ConsentCoordinator.respond]（调试用的自动应答就是这样做的）。
 */
interface ConsentSurface {
    /** 一条新请求进入待确认队列。 */
    fun requested(view: ConsentView)

    /** 这条请求结案了：撤回对话框和通知，必要时显示 [ConsentResolution.notice]。 */
    fun resolved(requestId: String, resolution: ConsentResolution)

    companion object {
        /** 什么都不做（测试，或界面还没接上）。 */
        val NONE: ConsentSurface = object : ConsentSurface {
            override fun requested(view: ConsentView) = Unit

            override fun resolved(requestId: String, resolution: ConsentResolution) = Unit
        }
    }
}

/**
 * “始终允许”的写回（**跨进程到 :ext 的 ApprovalStore，由 C / D 实现**；core:runtime 只定义接口）。
 * 把 [source] 这个工具设为“始终允许”（`ApprovalPolicy.withApproval(PolicyScope.of(source), ALWAYS, risk)`，工具级）。
 * 不抛异常：失败（策略文件读不出来、fail closed、IPC 失败）返回 [ApprovalWriteResult.Failed]。
 */
interface ApprovalWriter {
    /** 能不能写。为 false 时确认界面不提供“始终允许”（提供一个必定失败的选项没有意义）。 */
    val available: Boolean get() = true

    suspend fun setAlways(source: ToolSource, risk: ToolRisk): ApprovalWriteResult

    companion object {
        /** 不能写（没有接上用户策略）。 */
        val UNAVAILABLE: ApprovalWriter = object : ApprovalWriter {
            override val available: Boolean get() = false

            override suspend fun setAlways(source: ToolSource, risk: ToolRisk) = ApprovalWriteResult.Failed("not available")
        }
    }
}

sealed interface ApprovalWriteResult {
    data object Saved : ApprovalWriteResult

    /** [reason] 给日志看（不含参数和密钥）；给用户的说明由协调器生成。 */
    data class Failed(val reason: String) : ApprovalWriteResult
}

/** [ConsentCoordinator] 的参数。 */
data class ConsentConfig(
    /** 同时待确认的上限；超过的请求直接 `Deny(UNAVAILABLE)`。 */
    val maxPending: Int = 16,
    /** 参数摘要在界面上显示的字符上限。 */
    val maxArgumentChars: Int = 600,
    /** 工具显示名的字符上限。 */
    val maxDisplayNameChars: Int = 80,
    /** 插件名、服务器名在来源一行里的字符上限。 */
    val maxSourceChars: Int = 48,
    /** 写回“始终允许”的时限。 */
    val writeTimeoutMillis: Long = 5_000,
) {
    init {
        require(maxPending >= 1 && maxArgumentChars >= 16 && maxDisplayNameChars >= 8 && maxSourceChars >= 8 && writeTimeoutMillis > 0)
    }
}
