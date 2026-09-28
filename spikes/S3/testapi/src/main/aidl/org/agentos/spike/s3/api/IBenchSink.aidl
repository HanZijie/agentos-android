package org.agentos.spike.s3.api;

/** 原始 oneway 压测的接收端：不走 BinderChannel，不做流控，用来测 Binder 本身的边界。 */
oneway interface IBenchSink {
    void msg(String s);
    void bytes(in byte[] b);
}
