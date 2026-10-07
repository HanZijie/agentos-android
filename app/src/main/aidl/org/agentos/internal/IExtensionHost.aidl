// App 内部接口（docs/extensions.md 第 9 节）：Extension Host（:ext 进程）对 :agent（运行时）和主进程（插件管理页，D）。
// 不导出；服务端校验 Binder.getCallingUid() 等于本 App。随 App 一起升级，不是对外契约。
// 增加方法只加在末尾，并把 getVersion() 加 1。
package org.agentos.internal;

import org.agentos.internal.IExtensionCallback;

/**
 * 约定（与 IAgentControl 相同）：
 * - 数据一律是 JSON 字符串（UTF-8，单个返回值控制在 256 KiB 以内）。
 * - 出错时抛 IllegalArgumentException / IllegalStateException，message 形如 "agentos.ext.<code>: <说明>"，
 *   code 见各方法；Binder 只能把这两类异常原样传回调用方，其他异常在服务端换成 "agentos.ext.internal: <类型>"。
 * - 都会读写文件或 bind 别的 App，不要在主线程调用。
 *
 * 插件注册表与用户策略都用 A 车道的纯逻辑（core:extensions 的 PluginScanLogic / ApprovalStore，core:runtime 的
 * ApprovalPolicy、RiskPolicy）：Extension Host 是唯一的写入方，策略随目录一起交给运行时（getCatalog 的 "policy"），
 * Broker 每次调用前按它决定可用与确认。策略按插件名记（plugin.json 的 name；注册表保证名字唯一，原来的主人优先，
 * 改名、卸载、签名变化时清掉旧策略）。第三方插件第一次被发现时写入插件级 enabled=false（默认关闭）。
 *
 * 插件（listPlugins 的一项；setPluginEnabled / confirmSignature / setPluginApproval 的返回值）。键固定、顺序固定、不省略（空值为 null），
 * D 的插件页依赖这个形状（ExtWire.pluginJson / PLUGIN_KEYS）：
 *   {"id"（PluginRecord.id = "<包名>/<assets 目录>"）, "source":"app", "packageName", "name"（plugin.json 的 name，读不出时为 null）,
 *    "displayName", "description"（或 null）, "versionName"（或 null）, "versionCode",
 *    "signingDigest"（当前安装包的签名摘要：SHA-256 小写十六进制；多个签名者时排序后拼接再 SHA-256）,
 *    "trustedSigningDigest"（注册表记住的、上一次确认过的签名摘要，同一种摘要；还没确认过（signature_unconfirmed）时为 null，
 *      从不是空串；signature_changed 时与 signingDigest 不同，插件页据此显示“之前的签名 / 现在的签名”）,
 *    "status":"ready"|"unavailable"|"signature_changed"|"signature_unconfirmed"（PluginStatus）,
 *    "unavailableReason":"assets_missing"|"manifest_rejected"|"no_usable_server"|"name_conflict"|null,
 *    "builtin": bool, "enabled": bool（插件级是否启用）, "approval":"ask"|"always"|null（插件级审批方式，null = 没设，按 ask）,
 *    "problems":[{"code","message"}]（中文，给插件页显示）, "unsupported":[{"kind","location","detail"}]（PluginManifest.unsupported）,
 *    "servers":[{"name", "service"（完整类名，Binder 服务器；否则 null）, "url"（https，远端服务器；否则 null）,
 *                "state":"idle"|"connected"|"unreachable"|"disabled", "error"（或 null）, "toolCount"}],
 *    "rejectedServers":[{"name","service","reason":"not_in_package"|"not_exported"|"missing_permission"}],
 *    "toolCount", "skillCount",
 *    "skillProblems":[{"code","message"}]（ExtensionSkillPort.problems：frontmatter 缺失 / 非法、SKILL.md 读不到等，A10）}
 *   签名变化（signature_changed）或记忆丢失（signature_unconfirmed）时已停用，要用户先 confirmSignature 再启用。
 *
 * 已知工具（listTools 的一项；setToolEnabled / setToolApproval / setToolApprovalBySource 的返回值）：与 core:extensions 的
 * ExtensionToolHost.knownTools 的 KnownTool **字段一一对应**，键固定、不省略（插件页 D5.3 依赖这个形状）：
 *   {"name"（交给模型的名字，ToolNaming：mcp__<插件名>__<服务器>__<工具>，见 extensions.md 5.3；禁用、断开、:ext 重建后都不变）,
 *    "pluginId"（PluginRecord.id）, "source":{"plugin"（插件名）, "server", "tool"（服务器报告的原始工具名）},
 *    "title"（字符串或 null，截到 128 字符）, "description"（截到 1,024 字符，可为空串）, "inputSchema":{…},
 *    "risk":"read"|"write"|"high"（RiskPolicy.effectiveRisk：默认 write，destructiveHint=true 升 high；只有自带插件的 readOnlyHint 降为 read）,
 *    "enabled": bool（插件、服务器、工具三层策略都启用；为 true 的才在目录里）, "approval":"ask"|"always"（ApprovalPolicy.resolve）,
 *    "mayAlwaysAllow": bool（RiskPolicy.mayAlwaysAllow：高风险为 false，设 always 会报 high_risk）}
 *
 * 目录里的工具（getCatalog 的 tools，只给运行时 :agent 用）：上面的字段去掉 enabled=false 的工具，另加 "provider"（插件 ID，与 pluginId 相同），
 * title 没有时省略。
 * 描述、title、schema 都来自第三方，是不可信输入。
 *
 * 审批方式参数 mode：ask | always | ""（清除这一层，沿用上一层）。
 */
interface IExtensionHost {
    /** 本接口的版本：2（v2 加了 refreshTools、readSkill、setToolApprovalBySource、getPolicyStatus、resetPolicy，getCatalog 带 skills）。 */
    int getVersion();

    // ---------------------------------------------------------------- 插件管理（设置页，D）

    /** 全部插件（含不可用的），JSON 数组，每项见上。 */
    String listPlugins();

    /**
     * 启用或停用一个插件（插件级 enabled），返回更新后的插件 JSON。停用：立即从目录移除、关闭连接、取消进行中的调用。
     * 启用后在后台连接一次拉取工具（结果见 listTools），之后第一次用到工具时才再连接。
     * 错误 code：not_found、not_ready（status 不是 ready：不可用，或签名变化 / 未确认，要先 confirmSignature）。
     */
    String setPluginEnabled(String pluginId, boolean enabled);

    /**
     * 确认插件的当前签名（status 为 signature_changed / signature_unconfirmed 时），返回更新后的插件 JSON：恢复可用，但仍然停用。
     * 错误 code：not_found、not_needed（签名没有待确认的变化）。
     */
    String confirmSignature(String pluginId);

    /** 插件级审批方式（这个插件下没有单独设置的工具都按它），返回更新后的插件 JSON。错误 code：not_found、bad_mode。 */
    String setPluginApproval(String pluginId, String mode);

    /**
     * 一个插件的全部已知工具（含被策略禁用的），JSON 数组，每项是上面的“已知工具”，按 name 排序。错误 code：not_found、unavailable（插件不是 ready）。
     * 规则（ExtensionToolHost.knownTools，docs/extensions.md“已知工具”）：
     * - 被策略禁用的工具（工具、服务器或插件级）、连接断开（空闲回收、插件进程被杀）后都保留，名字不变；
     * - 插件被移除、签名变化或未确认、升级或服务声明变了、撤销授权时丢弃；
     * - :ext 重建后缓存是空的（不落盘）。已启用的插件启动时连一次，取到后被工具级禁用的工具也在里面；
     *   **被禁用、且在这个 :ext 进程里从没连上过的插件，在启用一次之前没有工具列表**（返回 []，插件页显示“启用后可查看工具”，
     *   不显示“0 个工具”；不为显示去连接被禁用的插件）；
     * - 插件已启用但还没有已知工具（刚启用、:ext 刚重建）时，先等一次刷新（最多 10 秒），然后照常返回。
     * 只读，不改目录版本；插件页每次打开时重新调用即可。
     */
    String listTools(String pluginId);

    /** 启用或禁用一个工具（toolName 为 listTools 里的 name），返回更新后的已知工具 JSON。错误 code：not_found、unavailable。 */
    String setToolEnabled(String toolName, boolean enabled);

    /**
     * 设置一个工具的审批方式，返回更新后的已知工具 JSON。
     * 错误 code：not_found、bad_mode、high_risk（risk=high 的工具不能设为 always）。
     */
    String setToolApproval(String toolName, String mode);

    /** 重新扫描已安装的 App（包变化时 :ext 会自动扫描），返回 listPlugins 的结果。 */
    String rescan();

    // ---------------------------------------------------------------- 运行时（:agent）

    /**
     * 订阅目录变化：立即回调一次 onCatalogChanged，之后每次变化（启用 / 停用、策略改动、list_changed、安装 / 卸载）再回调。
     * 同一个回调重复订阅无效；回调方进程死亡时自动退订。
     */
    void subscribe(IExtensionCallback callback);

    void unsubscribe(IExtensionCallback callback);

    /**
     * 当前目录：{"version": long, "tools":[工具…], "skills":[{"id","name","description","provider"}…], "policy":{ApprovalPolicy.toJson()},
     *   "policyFailClosed": bool}。skills 是 ExtensionSkillPort 的目录（A10：插件 ready 且插件级启用；name / description 是第三方文本）。
     * tools 只含现在可用的工具（插件 ready、插件 / 服务器 / 工具都启用，连上过至少一次）；Broker 仍按 policy 再查一次，
     * :ext 执行前也再查一次。policyFailClosed = 策略文件读不出来又没有可用的副本：第三方插件一律当作禁用
     * （运行时的镜像要设 unlistedPluginsEnabled=false，toJson 不带这个状态）。
     * version 在工具目录、Skill 目录或策略任一变化时加一（:ext 重建后从头开始）。只在 onCatalogChanged 之后来取（目录可能较大，不放在单向回调里）。
     */
    String getCatalog();

    /**
     * 发起一次工具调用。立即返回：true = 已受理，结果稍后经 callback.onToolResult(callId, …) 交回（恰好一次）；
     * false = 没有受理（工具不在目录里或已禁用、callId 重复），这时不会有回调，调用确定没有发出。
     * 请求格式不对抛 agentos.ext.bad_request（同样确定没有发出）。
     * requestJson：{"name"（目录里的名字）, "arguments":{…}, "timeoutMs": long（0 = 不限）,
     *   "sessionId", "taskId", "toolCallId"（只用于诊断和审计）}
     * 受理之后如果 :ext 进程死亡，运行时按“结果未知”处理（已受理的调用可能已经发给插件）。
     */
    boolean callTool(String callId, String requestJson, IExtensionCallback callback);

    /** 取消一个进行中的调用：转成 MCP 的 notifications/cancelled；之后仍会有一次 onToolResult。 */
    void cancelTool(String callId);

    /** 诊断 JSON：各插件、各服务器的连接状态与计数（不含参数和结果内容）。 */
    String getDiagnostics();

    // ---------------------------------------------------------------- v2

    /**
     * 等“还没有工具缓存”的可用服务器刷新完，最多 timeoutMs（ExtensionToolHost.refreshNow）；force = 全部可用服务器都重新取。
     * 返回 {"refreshed":[{"pluginId","server"}], "failed":[…], "timedOut": bool}。没刷新完的继续在后台刷新。
     */
    String refreshTools(long timeoutMs, boolean force);

    /**
     * 读 Skill（ExtensionSkillPort.read，A10）：path 相对 Skill 目录，空串或 null = SKILL.md。返回 {"text", "truncated": bool}（单次最多 64 KiB）。
     * 错误 code：not_found（没有这个 Skill / 文件、插件已停用）、bad_path（路径不合法、不是文本文件）。内容是第三方文本，不可信。
     */
    String readSkill(String skillId, String path);

    /**
     * 按来源设置一个工具的审批方式（确认框里的“始终允许”写回用，A11 的 ApprovalWriter）：plugin / server / tool 是 ToolSource
     * （插件名、服务器名、原始工具名），不受 ToolNaming 改名影响。返回更新后的已知工具 JSON。
     * 错误 code：not_found（工具不在 :ext 已知的工具里）、bad_mode、high_risk（按 :ext 算的风险等级）、unavailable（策略文件 fail closed）。
     */
    String setToolApprovalBySource(String plugin, String server, String tool, String mode);

    /**
     * 用户策略文件的状态（插件页显示，ApprovalStore.health）：
     * {"health":"ok"|"corrupt", "using":"previous"|"backup"|"fail_closed"|null, "reason", "failClosed": bool, "lastBackupError"}。
     * corrupt + fail_closed：文件读不出来又没有可用的副本，第三方插件一律当作禁用，插件页的写入报 unavailable，要用户确认 resetPolicy。
     */
    String getPolicyStatus();

    /** 用户确认重置策略（ApprovalStore.resetToDefault）：全部默认，现有第三方插件写成停用。返回 getPolicyStatus 的结果。 */
    String resetPolicy();
}
