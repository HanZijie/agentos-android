package org.agentos.spike.s3.api;

/** 只给 spike 用：读取 :agent 的内部状态、调整通道参数、杀掉自己。 */
interface ISpikeProbe {
    /** JSON：连接、通道、在跑的 prompt、线程数、内存、最近的关闭原因。 */
    String stats();

    /** 之后新开的 ACP 通道使用这组参数（ChannelConfig JSON）；空串恢复默认。两端要一致。 */
    void setChannelConfig(String json);

    /** 清空计数与关闭记录。 */
    void resetStats();

    /** 对 :agent 自己发 SIGKILL，模拟被系统杀掉。 */
    oneway void killProcess();
}
