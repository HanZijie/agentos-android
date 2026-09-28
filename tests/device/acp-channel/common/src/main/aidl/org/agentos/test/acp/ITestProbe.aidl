package org.agentos.test.acp;

/** 测试 Agent App（org.agentos.test.acp.agent）的探针：读 :agent 的内部状态、调通道参数、自杀。 */
interface ITestProbe {
    /** JSON，字段见 AcpTarget.stats()。 */
    String stats();

    /** 之后新开的 ACP 通道使用这组参数（ChannelConfig JSON）；空串恢复默认。两端要一致。 */
    void setChannelConfig(String json);

    /** 清空计数与关闭记录。 */
    void resetStats();

    /** 对 :agent 自己发 SIGKILL，模拟被系统杀掉。 */
    oneway void killProcess();
}
