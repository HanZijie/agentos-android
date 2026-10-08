package org.agentos.runtime.ports

/*
 * 会话级工具（ACP `session/new | load | resume` 的 `mcpServers`）：调用方自带的 MCP 服务器，只对它自己的这个会话可见。
 *
 * 和插件工具（[ToolPort]，用户在 AgentOS 里装的、对所有会话可见）分开，原因是信任关系不同：
 * - 来源是**调用方**，不是用户。用户没有在插件页里审阅过它，所以不进用户策略（[ApprovalPolicyPort]），
 *   也永远没有“始终允许”（写入 ApprovalStore 的键是插件/服务器/工具，调用方可以随便取名撞上别人的键）。
 * - 只在 `:agent` 进程的内存里，随会话存在：`headers` 里常常带 token，所以**不进 Store、事件、日志、确认框**。
 *   进程重启后要调用方在 `session/load | resume` 里重新带上来。
 * - 工具的风险等级最低是写级（每次确认）；服务端注解只能调高。
 */

/**
 * 调用方带来的一个 MCP 服务器（ACP `McpServer.Http`）。只支持 Streamable HTTP；stdio 和 SSE 在 ACP 层就被拒绝。
 *
 * [url] 和 [headers] 是调用方的内容，可能含凭据：[toString] 只写名字。
 */
class SessionMcpServer(
    val name: String,
    val url: String,
    val headers: List<Pair<String, String>> = emptyList(),
) {
    override fun toString() = "SessionMcpServer(name=$name)"
}

/** [SessionToolPort.attach] 里一个服务器的结局。[reason] 是短代码（`connect_failed`、`timeout`…），不含 URL 和头。 */
data class SessionMcpResult(
    val server: String,
    val connected: Boolean,
    val toolCount: Int = 0,
    val reason: String? = null,
)

/**
 * 整批服务器被拒绝（形状或限制不对：太多、名字重复或不合法、URL 不是 https、指向本机或内网地址…）。
 * [reason] 是短代码；[message] 说明哪条规则，**不回显**调用方传入的任何值（URL、头、名字原文）。会话不创建。
 */
class SessionMcpRejected(val reason: String, message: String) : IllegalArgumentException(message)

interface SessionToolPort {
    /**
     * 把 [servers] 挂到会话上，**替换**这个会话原来挂的那批（`session/load | resume` 重新带上来时就是替换）。
     * 先校验（失败抛 [SessionMcpRejected]，什么都不连），再并发连接、取工具列表，总共不超过实现的时限。
     * 连不上的服务器**不让会话创建失败**：它的结局是 [SessionMcpResult.connected] = false，工具目录里没有它的工具，
     * 之后每个任务开始前 [prepare] 会再试。
     */
    suspend fun attach(sessionId: String, owner: CallerIdentity, servers: List<SessionMcpServer>): List<SessionMcpResult>

    /** 会话删除、关闭（`session/close | delete`）时释放：断开连接，丢掉内存里的 URL 和头。重复调用无害。 */
    fun detach(sessionId: String)

    /**
     * 这个会话现在能用的会话级工具，名字是交给模型的最终名字（不以 `mcp__` 开头，永远不会和插件工具或 `read_skill` 重名），
     * [CatalogTool.source] 为 null，[CatalogTool.risk] 至少是 [ToolRisk.WRITE]。没有挂过服务器的会话返回空列表。
     */
    fun tools(sessionId: String): List<CatalogTool>

    /** 任务开始前：重连掉线的服务器、刷新工具列表，最多等 [timeoutMillis]；失败只记录，不影响任务开始。 */
    suspend fun prepare(sessionId: String, timeoutMillis: Long) {}

    /**
     * 调用一个会话级工具（[ToolInvocation.name] 是 [tools] 里的名字，[ToolInvocation.sessionId] 决定在哪个会话里找）。
     * 结局的三种分类与 [ToolPort.invoke] 相同。找不到工具（会话没挂、已 detach）返回 [ToolInvocationResult.NotDispatched]。
     */
    suspend fun invoke(invocation: ToolInvocation): ToolInvocationResult

    companion object {
        /** 不支持会话级工具：不挂、没有工具。 */
        val NONE: SessionToolPort = object : SessionToolPort {
            override suspend fun attach(sessionId: String, owner: CallerIdentity, servers: List<SessionMcpServer>): List<SessionMcpResult> {
                if (servers.isNotEmpty()) throw SessionMcpRejected("unsupported", "session MCP servers are not available on this build")
                return emptyList()
            }

            override fun detach(sessionId: String) = Unit

            override fun tools(sessionId: String): List<CatalogTool> = emptyList()

            override suspend fun invoke(invocation: ToolInvocation) = ToolInvocationResult.NotDispatched(
                org.agentos.runtime.errors.ErrorCode.TOOL_NOT_IN_CATALOG.info("session tools are not available"),
            )

            override fun toString() = "SessionToolPort.NONE"
        }
    }
}
