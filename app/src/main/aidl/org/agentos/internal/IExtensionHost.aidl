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
 * 用户策略（启用 / 禁用、审批方式）就是 core/runtime 的 ApprovalPolicy（org.agentos.runtime.broker，A 车道），
 * 由 Extension Host 保存，随目录一起交给运行时（getCatalog 的 "policy"），Broker 每次调用前按它决定可用与确认。
 * 策略里的插件键是插件 ID（App 内嵌插件 = 包名），不是 plugin.json 的 name：另一个 App 声明同名插件不会继承这份策略。
 * 第三方插件第一次被发现时写入插件级 enabled=false（默认关闭）；签名变化时同样改回 false。
 *
 * 插件（listPlugins 的一项）：
 *   {"id"（插件 ID，App 内嵌插件 = 包名）, "source":"app", "packageName", "name"（plugin.json 的 name）, "displayName",
 *    "description", "versionName", "versionCode", "signingDigest"（证书 SHA-256，小写十六进制，多签名时逗号分隔）,
 *    "enabled": bool（插件级是否启用）, "approval":"ask"|"always"|null（插件级审批方式，null = 没设，按 ask）,
 *    "state", "problems":[string], "unsupported":[{"kind","location","detail"}]（ManifestReader 的 unsupported）,
 *    "servers":[{"name", "service"（完整类名）, "state", "error"（可无）, "toolCount"}],
 *    "toolCount", "updatedAtMs"}
 *   state：ok（可用）| disabled（用户没启用）| invalid（插件包或 Service 校验不通过，见 problems）|
 *          signature_changed（签名变了，已停用，需要用户重新启用）| unavailable（启用了但连不上，见 servers[].error）
 *
 * 工具（listTools 的一项；getCatalog 的 tools 也是这个形状）：
 *   {"name"（交给模型的名字，ToolNaming：mcp__<插件名>__<服务器>__<工具>，见 extensions.md 5.3）,
 *    "source":{"plugin"（插件 ID）, "server", "tool"（服务器报告的原始工具名）}, "provider"（插件 ID）,
 *    "title"（可无）, "description", "inputSchema":{…},
 *    "annotations":{"readOnlyHint","destructiveHint","idempotentHint","openWorldHint"}（只列出服务器给了的）,
 *    "risk":"read"|"write"|"high"（RiskPolicy.effectiveRisk：默认 write，destructiveHint=true 升 high，readOnlyHint 不降级）,
 *    "enabled": bool, "approval":"ask"|"always"（ApprovalPolicy.resolve 的结果）, "mayAlwaysAllow": bool（RiskPolicy.mayAlwaysAllow）}
 *   描述、schema、注解都来自第三方，是不可信输入。
 *
 * 审批方式参数 mode：ask | always | ""（清除这一层，沿用上一层）。
 */
interface IExtensionHost {
    /** 本接口的版本：1。 */
    int getVersion();

    // ---------------------------------------------------------------- 插件管理（设置页，D）

    /** 全部插件（含不可用的），JSON 数组，每项见上。 */
    String listPlugins();

    /**
     * 启用或停用一个插件（插件级 enabled），返回更新后的插件 JSON。停用：立即从目录移除、关闭连接、取消进行中的调用。
     * 启用后在后台连接一次拉取工具（结果见 listTools），之后第一次用到工具时才再连接。
     * 错误 code：not_found、invalid（插件包或 Service 校验不通过，不能启用）。
     */
    String setPluginEnabled(String pluginId, boolean enabled);

    /** 插件级审批方式（这个插件下没有单独设置的工具都按它），返回更新后的插件 JSON。错误 code：not_found、bad_mode。 */
    String setPluginApproval(String pluginId, String mode);

    /** 一个插件的全部工具（含被禁用的），JSON 数组。插件已启用但还没连接过时会连接一次去拉取。错误 code：not_found、unavailable。 */
    String listTools(String pluginId);

    /** 启用或禁用一个工具（toolName 为目录里的名字），返回更新后的工具 JSON。错误 code：not_found。 */
    String setToolEnabled(String toolName, boolean enabled);

    /**
     * 设置一个工具的审批方式，返回更新后的工具 JSON。
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
     * 当前目录：{"version": long, "tools":[工具…], "policy":{ApprovalPolicy.toJson()}}。
     * tools 只含插件级启用的插件里的工具（工具级禁用的也在里面，由 Broker 按 policy 过滤）。
     * 只在 onCatalogChanged 之后来取（目录可能较大，不放在单向回调里）。
     */
    String getCatalog();

    /**
     * 发起一次工具调用。立即返回：true = 已受理，结果稍后经 callback.onToolResult(callId, …) 交回（恰好一次）；
     * false = 没有受理（工具不在目录里或已禁用、callId 重复），这时不会有回调，调用确定没有发出。
     * requestJson：{"name"（目录里的名字）, "arguments":{…}, "timeoutMs": long（0 = 不限）,
     *   "sessionId", "taskId", "toolCallId"（只用于诊断和审计）}
     * 受理之后如果 :ext 进程死亡，运行时按“结果未知”处理（已受理的调用可能已经发给插件）。
     */
    boolean callTool(String callId, String requestJson, IExtensionCallback callback);

    /** 取消一个进行中的调用：转成 MCP 的 notifications/cancelled；之后仍会有一次 onToolResult。 */
    void cancelTool(String callId);

    /** 诊断 JSON：各插件、各服务器的连接状态与计数（不含参数和结果内容）。 */
    String getDiagnostics();
}
