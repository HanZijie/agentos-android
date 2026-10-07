package org.agentos.internal;

/**
 * 主进程在前台时向 :agent 登记的确认回调（D5.2）。oneway：:agent 的协调器不等主进程，主进程卡住不会拖慢任务。
 * 内容都是 JSON 文本（格式见 app/.../agent/consent/ConsentWire.kt），里面的文字已由协调器清理，仍然只当纯文本显示。
 */
oneway interface IConsentListener {
    /** 登记时和之后每次变化：现在全部待确认的请求（队首在前），JSON 数组。主进程按 requestId 去重。 */
    void onSnapshot(String pendingJson);

    /** 一条新请求进入待确认队列（单个 ConsentView 的 JSON）。 */
    void onRequested(String viewJson);

    /** 一条请求结案：答复、超时、取消、关闭都会来。resolutionJson：{"end","choice","notice"}。 */
    void onResolved(String requestId, String resolutionJson);
}
