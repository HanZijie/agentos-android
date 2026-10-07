// App 内部接口（docs/extensions.md 第 9 节）：Extension Host（:ext）回调运行时（:agent）或设置页。不导出。
// 单向调用，不阻塞 :ext；增加方法只加在末尾，并把 IExtensionHost.getVersion() 加 1。
package org.agentos.internal;

oneway interface IExtensionCallback {
    /** 目录或用户策略变了（version 单调递增）。收到后调用 IExtensionHost.getCatalog() 取完整目录和策略。 */
    void onCatalogChanged(long version);

    /**
     * 一次 callTool 的结果，每个受理的 callId 恰好一次。就是 ExtensionToolHost.invoke 的 ToolInvocationResult（A9）：
     *   {"outcome":"completed", "result":{"content":[ContentPart…], "isError":bool, "details":{…}（MCP structuredContent，可无）}}
     *     —— 工具返回了结果（可以是 isError，例如参数不对、对象不存在；插件回的 JSON-RPC 错误也在这里，文字 "[mcp error …]"）；
     *   {"outcome":"not_dispatched", "error":ErrorInfo} —— 确定没有发给插件（不在目录 / 被禁用、连不上或没有权限、请求没写出去），没有副作用；
     *   {"outcome":"unknown", "error":ErrorInfo} —— 已经发出但没拿到结果（插件进程死亡、超时、被撤销、取消），
     *     副作用未知，按 architecture F8 不重放。
     * ContentPart、ErrorInfo 是 core:runtime 的序列化形状（{"type":"text","text"} / {"type":"image","data","mimeType"}；
     * {"code"（ErrorCode.wire：tool_not_in_catalog、tool_unavailable、tool_result_unknown、tool_timeout…）,"message","retryable","details"}）。
     * 取消（cancelTool、调用方死亡）的结局是 unknown + tool_result_unknown，details.cancelled = true。
     */
    void onToolResult(String callId, String outcomeJson);

    /**
     * 某个 MCP 服务器的连接状态变了（只进诊断页）：{"pluginId","server","state":"idle|connected|unreachable|disabled","error"（可为 null）}。
     * 就是 ExtensionToolHost.serverStates；disabled 的 error 是原因（user_policy / plugin_not_ready）。
     */
    void onConnectionState(String stateJson);
}
