// App 内部接口（docs/extensions.md 第 9 节）：Extension Host（:ext）回调运行时（:agent）或设置页。不导出。
// 单向调用，不阻塞 :ext；增加方法只加在末尾，并把 IExtensionHost.getVersion() 加 1。
package org.agentos.internal;

oneway interface IExtensionCallback {
    /** 目录或用户策略变了（version 单调递增）。收到后调用 IExtensionHost.getCatalog() 取完整目录和策略。 */
    void onCatalogChanged(long version);

    /**
     * 一次 callTool 的结果，每个受理的 callId 恰好一次。outcomeJson：
     *   {"outcome":"completed", "result":{"content":[{"type":"text","text":…}…], "structuredContent":{…}（可无）, "isError":bool}}
     *     —— 工具返回了结果（可以是 isError，例如参数不对、对象不存在、插件回了 JSON-RPC 错误）；
     *   {"outcome":"not_dispatched", "error":{"code","message"}} —— 确定没有发给插件（bind 失败、没有权限、连接建立前就取消、
     *     插件已停用或卸载、请求超长），没有副作用；
     *   {"outcome":"unknown", "error":{"code","message"}} —— 已经发出但没拿到结果（插件进程死亡、超时、发出后取消），
     *     副作用未知，按 architecture F8 不重放。
     * error.code：not_in_catalog、bind_failed、permission_denied、plugin_disabled、too_large、cancelled、timeout、
     *   peer_died、connection_closed、internal。
     */
    void onToolResult(String callId, String outcomeJson);

    /** 某个 MCP 服务器的连接状态变了（只进诊断页）：{"pluginId","server","state":"idle|connecting|ready|closed|failed","error"}。 */
    void onConnectionState(String stateJson);
}
