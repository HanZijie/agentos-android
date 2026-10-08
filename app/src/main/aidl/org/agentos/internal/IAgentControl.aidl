// App 内部接口（architecture 5.4）：主进程 → :agent。不导出，只接受本 App 的 UID；随 App 一起升级，不是对外契约。
// 增加方法只加在末尾，并把 VERSION 加 1。
package org.agentos.internal;

interface IAgentControl {
    /** 本接口的版本；v1 = 前四个方法，v2 = 加上 BYOK 的四个方法，v3 = 加上电脑端接入的四个方法，v4 = Jev，v5 = 第三方 App 接入 ACP 的三个方法。 */
    int getVersion();

    /**
     * 运行状态 JSON：pid、phase（STARTING | RECOVERING | READY）、tasks、foreground、
     * state（starting | recovering | idle | busy）、serviceRunning、foregroundDenied、uptimeMs。
     */
    String getRuntimeStatus();

    /**
     * 诊断 JSON：runtime（运行状态 + recoveryMs、userStopped、wakeLockHeld、engineStarted、runState…）、
     * startCommands（最近的启动命令）、lastExit（上一个 :agent 进程的 ApplicationExitInfo）、heartbeat（路径和字段）、
     * store（数据库路径与存储类型 ce、本次是否新建、大小、系统流事件计数、最近一次恢复的计数 lastRecovered）、
     * byok（是否配置、可用、厂商 / 模型 / 协议族、端点只到 scheme + host、keySet、credentialResolves、problems、
     * 目录与 Keystore 主密钥的状态）、supervisorMissing（S2 契约 e：运行 30 秒后还没收到本次开机的监督状态）、
     * acp（连接统计）、supervisor（同 getSupervisorStatus）。
     * 不含 key（连首尾 4 位也不含）、prompt 和会话内容。
     */
    String getDiagnostics();

    /**
     * 监督状态 JSON：DE 存储的 files/supervisor/status（key=value 行，监督契约 v0.2，由主进程的
     * SupervisorStatusReceiver 写入）经 SupervisorStatus.toJson() 转换，键名与文件相同：protocol、state、reason、seq、
     * since、deaths、boot_count、module_version、module_version_code、runtime_pid、received_at。还没有时返回 "{}"。
     */
    String getSupervisorStatus();

    // ---------------------------------------------------------------- v2：BYOK 模型来源（F9，W6）
    //
    // 设置页（W8）调用。出错时抛（消息里不回显任何传入的值）：
    //   IllegalArgumentException("agentos.byok.<code>: <说明>")：请求不对，code 见各方法；
    //   IllegalStateException("agentos.byok.storage_failed: <说明>")：保存失败（磁盘、Android Keystore）。
    // key 只在 setModelSource 的参数里出现；任何返回值里只有首尾各 4 位，诊断、日志、心跳里没有。
    // 更换（setModelSource）= 热加载：下一次模型请求就用新的模型和 key，进行中的这一轮不被打断
    //   （换下来的 key 只服务它原来的端点，留在 :agent 内存里，运行时空闲后丢弃）。
    // 清除（clearModelSource）= 立即作废（architecture F9）：key 当场丢掉，进行中的这一轮之后的模型请求也拿不到 key，
    //   以 model_not_configured 结束（错误消息不含 key）。

    /**
     * 厂商预设（assets/model-catalog.json，B 的 ModelCatalog；MiniMax 国际 minimax、国内 minimax-cn 排在最前）。
     * providerId 为 null 或 ""：
     *   {"schemaVersion":1, "piAi":"0.86.1", "customApis":["anthropic-messages","openai-completions"],
     *    "thinkingLevels":["off","minimal","low","medium","high"],
     *    "providers":[{"id","name","apis":[…],"baseUrls":[…],"keyLabel","modelCount"}]}
     * 否则：{"provider":{…同上…, "models":[{"id","name","api","baseUrl","reasoning","input":["text",…],
     *   "contextWindow","maxTokens"}]}}（分两级是为了控制 Binder 返回值大小）。
     * 错误 code：catalog_unavailable、unknown_provider。
     */
    String getModelPresets(String providerId);

    /**
     * 当前模型来源。
     * 没配置：{"configured":false, "usable":false, "problems":[…]}。
     * 已配置：{"configured":true, "usable":bool, "kind":"preset"|"custom", "provider"（preset）, "providerName"（preset）,
     *   "model", "modelName", "api", "baseUrl", "thinkingLevel", "reasoning", "input", "contextWindow", "maxTokens",
     *   "key":{"set":bool, "masked":"abcd…wxyz"（首尾各 4 位，中间是 U+2026；长度不超过 8 时为 "****"）,
     *          "label"（preset，例如 "MiniMax CN API key"）, "endpoints":[key 绑定的 baseUrl]},
     *   "updatedAt":epoch 毫秒, "problems":[…]}。
     * usable=false 时任务以 model_not_configured 失败。problems：
     *   config_unreadable、config_newer_format（保存的文件读不了，重新设置即可）；
     *   key_unreadable（Keystore 主密钥丢失，例如数据被恢复到另一台设备：需要重新输入 key）；
     *   key_endpoint_mismatch（App 更新后厂商端点变了：需要重新输入 key）；
     *   model_not_in_catalog（预设模型已不在目录里：沿用保存的参数，仍可用）；catalog_unavailable。
     */
    String getModelSource();

    /**
     * 设置模型来源，返回设置后的 getModelSource()。
     * sourceJson：
     *   厂商预设：{"kind":"preset", "provider":"minimax-cn", "model":"MiniMax-M2.7", "thinkingLevel":"off"}，
     *     key 绑定到该厂商的全部 baseUrl；
     *   自定义兼容端点：{"kind":"custom", "api":"anthropic-messages"|"openai-completions", "baseUrl":"https://…",
     *     "model":"模型 id", "name"?, "contextWindow"?, "maxTokens"?, "reasoning"?, "input"?:["text","image"], "thinkingLevel"?}，
     *     baseUrl 必须是绝对 https URL（http 只允许 127.0.0.1 / localhost / ::1，architecture F9），不带用户信息、query、fragment；
     *     OpenAI Chat Completions 填到 /v1 这一级，Anthropic Messages 填 API 根；key 只绑定到这个 baseUrl。
     *   thinkingLevel 可省略，默认 "off"。
     * apiKey：新 key（去掉首尾空白；不能含空白和控制字符；最长 4096 字符）。
     *   null 或 "" 表示沿用已保存的 key：只在新来源的端点与 key 绑定的端点完全相同时允许（同一厂商换模型、
     *   改 thinkingLevel），否则 key_required。换端点必须重新输入 key，key 不会被带到别的端点。
     * 错误 code：invalid_source、unknown_provider、unknown_model、unsupported_api、invalid_endpoint、
     *   invalid_thinking_level、invalid_key、key_required、catalog_unavailable；storage_failed。
     */
    String setModelSource(String sourceJson, String apiKey);

    /**
     * 清除模型来源和 key：删除保存的文件和 Keystore 主密钥，key 立即作废（不等进行中的任务结束）。进行中的这一轮
     * 下一次模型请求就拿不到 key，以 model_not_configured 结束；之后的任务同样以 model_not_configured 失败。
     * 已经在传输中的那一次响应目前会继续到结束（中止它需要网络出口配合，见 C3.1 报告）。
     */
    void clearModelSource();

    // ---------------------------------------------------------------- v3：电脑端接入（F11，W9）
    //
    // 设置页（W8）的“电脑端接入”调用。电脑经 adb forward 连到 :agent 的抽象 socket agentos-acp，第一次用一次性配对码配对，
    // 之后用配对时拿到的令牌（core/protocol/acp-mapping.md 第 10 节；电脑上的 tools/acp-bridge 自动完成）。
    // 这些方法读写文件、开关 socket：服务端在 Binder 线程上执行，调用方不要在主线程等。
    // 配对码只出现在 newDesktopPairingCode 的返回值里；令牌任何返回值里都没有。

    /**
     * 电脑端接入的状态：
     * {"enabled":bool（开关，默认 false）, "listening":bool, "socket":"agentos-acp",
     *  "listenError":null|"…"（打不开监听，例如抽象 socket 名被别的 App 占用；设置页应提示）,
     *  "code":null|{"expiresAtMs","attemptsLeft"}（当前有效的配对码，不含配对码本身）,
     *  "pairings":[{"id":"dp_…","label"（电脑端自报的名字，只作区分）,"pairedAtMs","lastSeenMs"}],
     *  "connections":[{"id","pairingId","label","peerUid","openedAtMs","transport":{…统计}}]}
     */
    String getDesktopAccess();

    /**
     * 打开或关闭电脑端接入，返回 getDesktopAccess()。关闭时停止监听、断开所有电脑端连接，并清掉配对码和全部配对
     * （电脑要重新配对）。
     */
    String setDesktopAccessEnabled(boolean enabled);

    /**
     * 生成一次性配对码（替换旧的），给设置页显示：{"code":"482913","expiresAtMs":epoch 毫秒,"ttlMs":300000}。
     * 6 位数字，5 分钟有效，配对成功即作废，输错 5 次作废。
     * 开关关闭时抛 IllegalStateException("agentos.desktop.disabled: …")。
     */
    String newDesktopPairingCode();

    /** 撤销一个已配对的电脑并断开它的连接；pairingId 为 null 或 "" 时撤销全部。返回 getDesktopAccess()。 */
    String revokeDesktopPairing(String pairingId);

    // ---------------------------------------------------------------- v4：自动选择会话（Jev，D5.1）
    //
    // 设置页的“自动选择会话（Jev）”。key 只存 Android Keystore（自己的主密钥），按 endpoint 绑定；任何返回值里只有首尾各 4 位。
    // 错误：IllegalArgumentException("agentos.jev.<code>: …")（invalid_endpoint、invalid_key、key_required），
    //       IllegalStateException("agentos.jev.storage_failed: …")。说明里不回显传入的值。

    /**
     * Jev 的状态：{"configured":bool, "endpoint", "defaultEndpoint", "customEndpoint":bool, "keySet":bool,
     * "keyMasked":null|"abcd…wxyz", "usable":bool, "problems":["key_unreadable"|"file_unreadable"], "updatedAt"}。
     * 没有配置时 session/new 带 autoSelect 且有候选会话，一律新建，fallbackReason=jev_unconfigured。
     */
    String getJevSource();

    /**
     * 保存 Jev 的 endpoint 和 key，返回 getJevSource()。endpoint 为 null 或空 = 默认（https://api.typesafe.ai/v1/systemone）；
     * 规则同模型端点：https，只有 127.0.0.1 / localhost / ::1 可以用 http。apiKey 为 null 或空 = 沿用已保存的 key（endpoint 不变时才允许）。
     * 立即生效（热加载）。
     */
    String setJevSource(String endpoint, String apiKey);

    /** 清除 Jev 的 endpoint 和 key：立即作废（在途的 Jev 请求被中止，路由回退新建会话），并删除 Keystore 主密钥。 */
    void clearJevSource();

    // ---------------------------------------------------------------- v5：第三方 App 接入 ACP（docs/third-party-acp.md 4.1、4.3）
    //
    // 后装的 App 经 IAcpService.open 接入，要先被用户允许。C 的 CallerRegistry（:agent，files/acp/callers.json）记着每个 App 的状态；
    // 这三个方法给设置页（“已授权的应用”）和授权提示（D）用。错误：IllegalArgumentException("agentos.acp.<code>: …")
    // （not_found：没有这个包的记录；bad_state：state 不是 allowed / denied / removed），IllegalStateException("agentos.acp.registry_unavailable: …")
    // （callers.json 损坏且没有可用副本，fail closed）。说明里不回显调用方传入的值。

    /**
     * 所有记着的第三方 App，JSON 数组，按 lastUsedAt 降序（没用过的在后，再按 label）。每项的键固定、顺序固定、不省略（空值为 null）：
     *   {"packageName"（包名）, "label"（App 名；查不到时是包名）, "signingDigest"（签名摘要：SHA-256 小写十六进制，64 位；多个签名者时
     *      排序后拼接再 SHA-256，和插件的 signingDigest 同一种）,
     *    "state":"pending"|"allowed"|"denied"（pending = 已提出请求、等用户决定；denied 在 deniedUntil 之前是冷却中，之后 open 又会弹提示）,
     *    "requestId"（pending 时授权提示的 ID，answerAuthorization 用；否则 null）,
     *    "firstSeenAt", "decidedAt"（用户决定的时间，pending 时为 null）, "lastUsedAt"（最近一次 prompt 完成；没用过为 null）,
     *    "deniedUntil"（冷却结束时间，只有 denied 且还在冷却中才有，否则 null）, "requestedAt"（pending 时请求的时间，否则 null），
     *    "usage":{"promptsTotal", "promptsLastHour", "activeChannels", "activeTasks"}}
     * 时间都是毫秒时间戳（System.currentTimeMillis）。签名变了的 App：旧记录作废，当作新请求，列表里只有现在的签名。
     */
    String listAcpCallers();

    /**
     * 设置一个第三方 App 的状态，返回更新后的这一项（形状同 listAcpCallers 的一项；removed 返回 null）。state：
     *   "allowed"：允许（可用于 pending / denied 的改动；清除冷却）；
     *   "denied"：拒绝（进入 10 分钟冷却；撤销授权也用它）；
     *   "removed"：删除记录（下次 open 重新询问）。
     * 变成 denied / removed 时，这个 App 现有的通道立即关闭、进行中的任务取消。packageName 的记录有多个签名时（旧签名作废后不会有）按现在的签名。
     */
    String setAcpCaller(String packageName, String state);

    /**
     * 回答一条授权提示（requestId 来自 listAcpCallers 的 pending 项，或 D 的卡片）。allow=true 等同 setAcpCaller(包, "allowed")，
     * allow=false 等同 "denied"。返回回答是否被采纳（已被别处决定、已超时、不存在返回 false）。
     */
    boolean answerAuthorization(String requestId, boolean allow);
}
