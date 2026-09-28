// App 内部接口（architecture 5.4）：主进程 → :agent。不导出，只接受本 App 的 UID；随 App 一起升级，不是对外契约。
// 增加方法只加在末尾，并把 VERSION 加 1。
package org.agentos.internal;

interface IAgentControl {
    /** 本接口的版本；v1 = 前四个方法，v2 = 加上 BYOK 的四个方法。 */
    int getVersion();

    /**
     * 运行状态 JSON：pid、phase（STARTING | RECOVERING | READY）、tasks、foreground、
     * state（starting | recovering | idle | busy）、serviceRunning、foregroundDenied、uptimeMs。
     */
    String getRuntimeStatus();

    /**
     * 诊断 JSON：runtime（运行状态 + recoveryMs、userStopped、wakeLockHeld、engineStarted、runState…）、
     * startCommands（最近的启动命令）、lastExit（上一个 :agent 进程的 ApplicationExitInfo）、heartbeat（路径和字段）、
     * store（数据库路径与存储类型 ce、本次是否新建、大小、系统流事件计数、本进程经 ACP 新建 / 载入的会话数）、
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
    // 修改立即生效（热加载）：宿主层从下一轮起用新的模型和 key，进行中的一轮不受影响
    // （换下来的 key 留在 :agent 内存里，运行时空闲后丢弃）。

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
     *     baseUrl 必须是绝对 https URL（http 只允许 localhost / 127.x / ::1），不带用户信息、query、fragment；
     *     OpenAI Chat Completions 填到 /v1 这一级，Anthropic Messages 填 API 根；key 只绑定到这个 baseUrl。
     *   thinkingLevel 可省略，默认 "off"。
     * apiKey：新 key（去掉首尾空白；不能含空白和控制字符；最长 4096 字符）。
     *   null 或 "" 表示沿用已保存的 key：只在新来源的端点与 key 绑定的端点完全相同时允许（同一厂商换模型、
     *   改 thinkingLevel），否则 key_required。换端点必须重新输入 key，key 不会被带到别的端点。
     * 错误 code：invalid_source、unknown_provider、unknown_model、unsupported_api、invalid_endpoint、
     *   invalid_thinking_level、invalid_key、key_required、catalog_unavailable；storage_failed。
     */
    String setModelSource(String sourceJson, String apiKey);

    /** 清除模型来源和 key，并删除 Keystore 主密钥。之后任务以 model_not_configured 失败。 */
    void clearModelSource();
}
