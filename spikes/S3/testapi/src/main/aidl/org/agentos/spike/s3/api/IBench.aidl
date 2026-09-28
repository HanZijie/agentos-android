package org.agentos.spike.s3.api;

import org.agentos.spike.s3.api.IBenchSink;

/** :agent 里的压测服务。 */
interface IBench {
    /** 新建一个接收端（替换上一个）；每条消息的处理耗时人为加上 handlerDelayMicros 微秒。 */
    IBenchSink newSink(int handlerDelayMicros);

    /** 最近一个接收端的统计 JSON：count、units。 */
    String sinkStats();

    /** 由 :agent 向对方的接收端发送（:agent → 客户端方向），参数与返回都是 JSON。 */
    String blast(IBenchSink target, String configJson);
}
