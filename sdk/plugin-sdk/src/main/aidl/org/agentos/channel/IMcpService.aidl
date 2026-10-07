// MCP over Binder：docs/extensions.md 5.1；通道是 binder-channel-v1（core/protocol/binder-channel-v1.md），与 ACP 共用。
package org.agentos.channel;

import org.agentos.channel.IChannel;

/**
 * 提供插件的 App 导出（McpBinderService），要求 org.agentos.permission.BIND_MCP_SERVICE（AgentOS 定义，signature）。
 * 每条 MCP JSON-RPC 消息是一次 IChannel.send。方法顺序决定事务号，冻结后只能在末尾追加。
 */
interface IMcpService {
    /**
     * 传入客户端（AgentOS 的 Extension Host）的接收端，返回 MCP 服务的接收端。
     * 通道绑定调用方 UID（Binder.getCallingUid()），之后每条入站消息都按它校验。
     */
    IChannel open(IChannel client);
}
