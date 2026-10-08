package org.agentos.runtime.broker

import org.agentos.runtime.ports.ToolRisk

/**
 * MCP 服务端随工具报告的注解（MCP 规范 `ToolAnnotations` 里和风险有关的两项）。
 * **服务端自报，不可信**：只能把风险调高，不能调低（见 [RiskPolicy.effectiveRisk]）。
 *
 * @property readOnlyHint 服务端声称工具不修改环境。**永远不会降低风险等级。**
 * @property destructiveHint 服务端声称工具可能做破坏性更新。只有明确的 `true` 才升为 [ToolRisk.HIGH]；缺省（null）不算
 *   （MCP 规范里缺省的含义是“按 true 处理”，AgentOS 不采用，否则所有没写注解的工具都成了高风险）。
 */
data class ToolAnnotations(
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
)

/** 一次工具调用要不要请用户确认，以及不用确认时的依据（写进 `consent.resolved` 的 `reason`）。 */
enum class ConsentRequirement(val skipsPrompt: Boolean, val reason: String?) {
    /** 要确认。 */
    ASK(false, null),

    /** 读操作，直接执行（不产生确认事件）。 */
    NOT_NEEDED_READ(true, null),

    /** 用户策略设为“始终允许”（且不是高风险），`reason = "policy"`。 */
    ALWAYS_BY_POLICY(true, "policy"),

    /** 用户在这个会话里选过“本会话内不再询问”，`reason = "remembered"`。 */
    REMEMBERED_IN_SESSION(true, "remembered"),
}

/**
 * 风险策略（docs/extensions.md 5.4，architecture F5）：纯函数，没有状态，不依赖 Android。
 *
 * 三层叠加，**最终以 AgentOS 的风险策略和确认为准**：
 * 1. 默认等级：MCP 工具默认 [ToolRisk.WRITE]，每次调用都要确认；
 * 2. 服务端注解只能调高：`destructiveHint = true` → [ToolRisk.HIGH]；`readOnlyHint = true` 不降低（注解不可信）；
 * 3. 用户策略（[ApprovalPolicy]）：按插件、服务器、工具启用或禁用；审批方式“每次确认”（默认）或“始终允许”，
 *    **高风险工具不能设为始终允许**（[mayAlwaysAllow]），即使策略文件里写了也无效（[consentRequirement] 仍然要确认）。
 *
 * 调用方：Extension Host 生成工具目录时用 [effectiveRisk] 算出每个工具的等级，放进 `CatalogTool.risk`；
 * CapabilityBroker 在每次调用前用 [consentRequirement] 决定要不要弹确认。
 */
object RiskPolicy {
    /** 没有任何声明的工具（所有第三方 MCP 工具）的默认等级。 */
    val DEFAULT_RISK: ToolRisk = ToolRisk.WRITE

    /**
     * 一个工具的风险等级。
     *
     * @param declared AgentOS 自己信任的来源声明的等级（例如自带插件里的读取类工具是 [ToolRisk.READ]、shell 是 [ToolRisk.HIGH]）；
     *   第三方 MCP 工具没有，传 null，按 [DEFAULT_RISK]。
     * @param annotations MCP 服务端的注解；只能调高：`destructiveHint == true` → [ToolRisk.HIGH]。
     */
    fun effectiveRisk(declared: ToolRisk? = null, annotations: ToolAnnotations? = null): ToolRisk {
        val base = declared ?: DEFAULT_RISK
        return if (annotations?.destructiveHint == true) maxOf(base, ToolRisk.HIGH) else base
    }

    /**
     * 能不能把这个等级的工具设为“始终允许”：除高风险外都可以。界面据此决定是否提供这个选项；
     * 对插件、服务器级别的“始终允许”，高风险工具照样每次确认（[consentRequirement]）。
     */
    fun mayAlwaysAllow(risk: ToolRisk): Boolean = risk != ToolRisk.HIGH

    /** 确认框里能不能给“本会话内不再询问”：只有写级工具（高风险每次都要确认）。 */
    fun maySessionRemember(risk: ToolRisk): Boolean = risk == ToolRisk.WRITE

    /**
     * 这次调用要不要请用户确认。规则按顺序：
     * 0. [alwaysAsk]（调用方是第三方 App，docs/third-party-acp.md 4.4）：一定确认——读操作也确认，不看用户策略的“始终允许”，不看本会话的“不再询问”；
     * 1. Hook 的决定是 ask：一定确认（Hook 的 allow 不能跳过确认，ask 可以追加确认，连读操作也确认）；
     * 2. 读操作：不确认；
     * 3. 高风险：每次确认，不受“始终允许”和“本会话不再询问”影响；
     * 4. 写操作：策略是“始终允许”→ 不确认（依据 policy）；否则本会话内选过“不再询问”→ 不确认（依据 remembered）；否则确认。
     *
     * @param approval 用户策略解析出的审批方式（[ApprovalPolicy.resolve]）
     * @param rememberedInSession 用户在本会话里对这个工具选过“本会话内不再询问”
     * @param hookAsk PreToolUse Hook 合并后的决定是 ask
     * @param alwaysAsk 调用方是第三方 App：每次都确认。默认 false，其余调用方的结果与加这个参数之前一字不差。
     */
    fun consentRequirement(
        risk: ToolRisk,
        approval: ApprovalMode,
        rememberedInSession: Boolean = false,
        hookAsk: Boolean = false,
        alwaysAsk: Boolean = false,
    ): ConsentRequirement = when {
        alwaysAsk -> ConsentRequirement.ASK
        hookAsk -> ConsentRequirement.ASK
        risk == ToolRisk.READ -> ConsentRequirement.NOT_NEEDED_READ
        risk == ToolRisk.HIGH -> ConsentRequirement.ASK
        approval == ApprovalMode.ALWAYS -> ConsentRequirement.ALWAYS_BY_POLICY
        rememberedInSession -> ConsentRequirement.REMEMBERED_IN_SESSION
        else -> ConsentRequirement.ASK
    }

    /** [consentRequirement] 是 [ConsentRequirement.ASK]。 */
    fun needsConsent(
        risk: ToolRisk,
        approval: ApprovalMode,
        rememberedInSession: Boolean = false,
        hookAsk: Boolean = false,
        alwaysAsk: Boolean = false,
    ): Boolean = !consentRequirement(risk, approval, rememberedInSession, hookAsk, alwaysAsk).skipsPrompt
}
