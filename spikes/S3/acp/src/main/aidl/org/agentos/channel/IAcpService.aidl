// binder-channel-v1 草案（S3 第二部分）。
package org.agentos.channel;

import org.agentos.channel.IChannel;

/** AgentOS App 导出，运行在 :agent 进程。 */
interface IAcpService {
    /** 传入客户端的接收端，返回 Agent 的接收端。通道绑定调用方 UID。 */
    IChannel open(IChannel client);
}
