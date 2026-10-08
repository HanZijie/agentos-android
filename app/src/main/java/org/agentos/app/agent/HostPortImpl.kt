package org.agentos.app.agent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.ports.ApprovalPolicyPort
import org.agentos.runtime.ports.Clock
import org.agentos.runtime.ports.ConsentDecision
import org.agentos.runtime.ports.ConsentPort
import org.agentos.runtime.ports.ConsentRequest
import org.agentos.runtime.ports.EnvironmentPort
import org.agentos.runtime.ports.HookOutcome
import org.agentos.runtime.ports.HookPort
import org.agentos.runtime.ports.HookRequest
import org.agentos.runtime.ports.HostPort
import org.agentos.runtime.ports.RuntimeLog
import org.agentos.runtime.ports.SafeModeState
import org.agentos.runtime.ports.SessionToolPort
import org.agentos.runtime.ports.SkillCatalog
import org.agentos.runtime.ports.SkillContent
import org.agentos.runtime.ports.SkillPort
import org.agentos.runtime.ports.ToolCatalog
import org.agentos.runtime.ports.ToolInvocation
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolPort

/**
 * `HostPort` 的 Android 实现（W6）：宿主层（core:runtime）对 Android 的全部依赖。
 *
 * | 端口 | W6 | 以后 |
 * |---|---|---|
 * | storage | [AndroidStore]：BundledSQLiteDriver，CE 私有目录 | — |
 * | models | [ModelSources]：BYOK 模型来源，热加载 | — |
 * | secrets | [KeystoreSecrets]：Keystore 加密的 key，按 baseUrl 匹配 | — |
 * | clock | 系统时钟 | — |
 * | environment | [AndroidEnvironment]：previousExitStoppedByUser 来自 ApplicationExitInfo；safe mode 关 | W11：safe mode 来自监督状态 |
 * | log | [AndroidRuntimeLog]：android.util.Log，写之前去掉 key | — |
 * | tools | [ExtensionClient]：`:ext` 的 IExtensionHost 的薄代理（目录、三种结局；C7b） | — |
 * | approvals | [ExtensionClient.approvals]：`:ext` 的用户策略的镜像，收到第一份之前 fail closed（C7b） | — |
 * | consent | [ConsentCoordinator]（见 AgentProcess）：前台对话框、后台通知（D5.2）；[NotOpen.CONSENT] 只是构造默认值 | — |
 * | skills | [ExtensionClient.skills]：`:ext` 里 ExtensionSkillPort 的代理（C7b） | — |
 * | hooks | [NotOpen.HOOKS]：没有 Hook，NO_OPINION | W22 |
 * | sessionTools | `core:extensions` 的 SessionToolHost：调用方在 `mcpServers` 里带的 Streamable HTTP 服务器，只在内存里，随会话存在 | — |
 */
class HostPortImpl(
    override val storage: AndroidStore,
    override val models: ModelSources,
    override val secrets: KeystoreSecrets,
    override val environment: AndroidEnvironment,
    override val log: RuntimeLog,
    override val tools: ToolPort = NotOpen.TOOLS,
    override val approvals: ApprovalPolicyPort = ApprovalPolicyPort.DEFAULT,
    override val skills: SkillPort = NotOpen.SKILLS,
    /** 工具调用的用户确认：默认一律拒绝；AgentProcess 传入 ConsentCoordinator（D5.2，release 和 debug 都是）。 */
    override val consent: ConsentPort = NotOpen.CONSENT,
    /** 调用方在 ACP `mcpServers` 里带来的、只对它自己的会话可见的 MCP 服务器（Streamable HTTP）；默认不支持。AgentProcess 传入 SessionToolHost。 */
    override val sessionTools: SessionToolPort = SessionToolPort.NONE,
) : HostPort {
    override val hooks: HookPort = NotOpen.HOOKS
    override val clock: Clock = Clock.SYSTEM
}

/**
 * 运行环境。[previousExitStoppedByUser] 由 [AgentProcess] 在调用 `runtime.start()` 之前按上一个 `:agent` 的
 * ApplicationExitInfo 设好（RecoveryPolicy，S2 契约 a 第 6 条）；宿主层只在 start 时读一次。
 */
class AndroidEnvironment : EnvironmentPort {
    override val safeMode: StateFlow<SafeModeState> = MutableStateFlow(SafeModeState.OFF).asStateFlow()

    @Volatile override var previousExitStoppedByUser: Boolean = false
}

/** M1 还没开放的能力：行为明确（空目录、调用返回“未开放”、确认一律拒绝），不会假装成功。 */
object NotOpen {
    val TOOLS: ToolPort = object : ToolPort {
        override val catalog: StateFlow<ToolCatalog> = MutableStateFlow(ToolCatalog.EMPTY).asStateFlow()

        // 目录为空，Broker 本来就不会放行；这里再兜一层：请求没有发出，没有副作用
        override suspend fun invoke(invocation: ToolInvocation) = ToolInvocationResult.NotDispatched(
            ErrorCode.TOOL_NOT_IN_CATALOG.info("tools are not open yet on this build (Extension Host, W14–W16)"),
        )

        override fun toString() = "NotOpen.TOOLS"
    }

    val SKILLS: SkillPort = object : SkillPort {
        override val catalog: StateFlow<SkillCatalog> = MutableStateFlow(SkillCatalog.EMPTY).asStateFlow()

        override suspend fun read(skillId: String, path: String?): SkillContent =
            throw NoSuchElementException("skills are not open yet on this build (W20)")

        override fun toString() = "NotOpen.SKILLS"
    }

    val HOOKS: HookPort = object : HookPort {
        override suspend fun dispatch(request: HookRequest) = HookOutcome.NONE

        override fun toString() = "NotOpen.HOOKS"
    }

    val CONSENT: ConsentPort = object : ConsentPort {
        override suspend fun request(request: ConsentRequest) = ConsentDecision.Deny(ConsentDecision.DenyReason.UNAVAILABLE)

        override fun toString() = "NotOpen.CONSENT"
    }
}

/**
 * 宿主层日志 → android.util.Log（tag `AgentOS.<tag>`）。宿主层保证消息里没有 key；这里再用 [redact] 兜一层，
 * 异常只记类型和（去掉 key 后的）消息与栈，不记 cause 的原始对象。
 */
class AndroidRuntimeLog(private val redact: (String) -> String) : RuntimeLog {
    override fun log(level: RuntimeLog.Level, tag: String, message: String, error: Throwable?) {
        val priority = when (level) {
            RuntimeLog.Level.DEBUG -> Log.DEBUG
            RuntimeLog.Level.INFO -> Log.INFO
            RuntimeLog.Level.WARN -> Log.WARN
            RuntimeLog.Level.ERROR -> Log.ERROR
        }
        val text = if (error == null) message else message + "\n" + Log.getStackTraceString(error)
        Log.println(priority, "AgentOS.$tag", redact(text))
    }
}
