// App 内部接口（architecture 5.4）：主进程 → :agent。不导出，只接受本 App 的 UID；随 App 一起升级，不是对外契约。
// 增加方法只加在末尾，并把 VERSION 加 1。
package org.agentos.internal;

interface IAgentControl {
    /** 本接口的版本；v1 = 下面四个方法。 */
    int getVersion();

    /**
     * 运行状态 JSON：pid、phase（STARTING | RECOVERING | READY）、tasks、foreground、
     * state（starting | recovering | idle | busy）、serviceRunning、foregroundDenied、uptimeMs。
     */
    String getRuntimeStatus();

    /**
     * 诊断 JSON：runtime（运行状态 + recoveryMs、userStopped、wakeLockHeld…）、startCommands（最近的启动命令）、
     * lastExit（上一个 :agent 进程的 ApplicationExitInfo）、heartbeat（路径和字段）、acp（连接统计）。
     * 不含 key、prompt 和会话内容。
     */
    String getDiagnostics();

    /**
     * 监督状态 JSON：DE 存储的 supervisor/status（由 SupervisorStatusReceiver 写入，W7）。
     * 文件是 JSON 时原样返回，是 key=value 行时转成 JSON 对象；还没有时返回 "{}"。
     */
    String getSupervisorStatus();
}
