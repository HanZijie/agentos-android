package org.agentos.app.agent.consent

import org.agentos.runtime.consent.ConsentResolution

/**
 * 第三方 App 授权提示的界面接缝（docs/third-party-acp.md 4.2；D）。`:agent` 里的 CallerRegistry（C）在“没记录 / 签名变了”时调
 * [authorizationRequested]，结案（用户答复、超时、撤销、App 取消）时调 [authorizationResolved]；[ConsentBridge] 实现它：
 * 主进程前台时推给对话框，否则发通知，和工具确认排同一个队。
 *
 * 回答不走这里：主进程经 `IAgentControl.answerAuthorization(requestId, allow)`（C），通知上的“拒绝”经 [ConsentBridge.authorizationAnswer]。
 * 超时由 CallerRegistry 计时（界面只显示 [ConsentWire] 里的 deadline）。回调都不阻塞、不抛。
 */
interface AuthorizationSurface {
    fun authorizationRequested(request: ConsentWire.AuthRequest)

    /** [ConsentResolution.end] 同工具确认：ANSWERED / TIMED_OUT / CANCELLED / CLOSED；[ConsentResolution.notice] 可选，主进程用 Toast 显示。 */
    fun authorizationResolved(requestId: String, resolution: ConsentResolution)
}
