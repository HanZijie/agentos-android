package org.agentos.internal;

import org.agentos.internal.IConsentListener;

/**
 * 工具确认的界面接口（D5.2，architecture F5）。:agent 里的 ConsentCoordinator 是事实来源：待确认的请求、各自的 60 秒超时都在那里；
 * 主进程的界面只是显示它和回传用户的选择，主进程被杀不影响任务（pending 照常超时后拒绝）。
 * 不导出，服务端校验调用方 UID 等于本 App。
 *
 * 前台时主进程 bind 并 registerListener：新请求推给它（弹对话框），:agent 不再发通知；没有监听者（后台、主进程被杀）时
 * :agent 自己发带“允许 / 拒绝”的通知。
 */
interface IConsentService {
    /** 登记前台监听者：先收到一次 onSnapshot（当前全部待确认），之后收到 onRequested / onResolved。同时撤回已发出的通知。 */
    void registerListener(IConsentListener listener);

    /** 撤销登记。没有监听者了且还有待确认的，:agent 为它们发通知。 */
    void unregisterListener(IConsentListener listener);

    /**
     * 回答一条待确认的请求。choice 是 ConsentChoice 的名字（ALLOW_ONCE、ALLOW_FOR_SESSION、ALWAYS_ALLOW、DENY）。
     * 返回回答是否被采纳（已超时、已回答、不存在返回 false；选项不在这条请求允许的范围内按拒绝处理，返回 true）。
     */
    boolean respond(String requestId, String choice);
}
