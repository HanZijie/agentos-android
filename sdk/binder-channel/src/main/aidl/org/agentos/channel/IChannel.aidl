// binder-channel-v1：见 core/protocol/binder-channel-v1.md（草案，S3 定参；真机复核后冻结）。
package org.agentos.channel;

/**
 * 通道的接收端。通信双方各实现一个，在 open 时交换。
 * 全部是 oneway：发送方不等待对方处理，同一个 IChannel 上的调用按发送顺序、逐个到达。
 * 方法顺序决定事务号（send = 1，close = 2，ack = 3），冻结后只能在末尾追加。
 */
oneway interface IChannel {
    /** 一条完整的 JSON-RPC 消息，语义等同 stdio 下的一行（不含换行符）。 */
    void send(String message);

    /** 关闭通道；在它之前发出的 send 都会先到达。 */
    void close(String reason);

    /**
     * 流控回执：调用方已经处理完对方发来的前 consumed 条消息（累计值，单调递增）。
     * 发送方据此释放在途窗口。
     */
    void ack(long consumed);
}
