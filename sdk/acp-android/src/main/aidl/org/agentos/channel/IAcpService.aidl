// binder-channel-v1：见 core/protocol/binder-channel-v1.md。
package org.agentos.channel;

import org.agentos.channel.IChannel;

/**
 * AgentOS App 导出，运行在 :agent 进程（intent action：org.agentos.intent.action.ACP）。
 * 方法顺序决定事务号，冻结后只能在末尾追加。
 */
interface IAcpService {
    /**
     * 传入客户端的接收端，返回 Agent 的接收端。通道绑定调用方 UID（Binder.getCallingUid()）。
     * 拒绝时抛 SecurityException，message 以 "agentos.acp." 开头的原因码起头
     * （例如 agentos.acp.not_open：这个版本还不接受第三方 App）。
     */
    IChannel open(IChannel client);
}
