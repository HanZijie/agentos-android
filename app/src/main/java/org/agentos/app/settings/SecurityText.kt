package org.agentos.app.settings

/**
 * The security level text of the settings page and the first-run guide. The level is fixed at
 * best_effort (architecture decision record) and told as it is. "This phone is rooted" is only said
 * when it is known: the root supervisor reported in this boot ([StatusText.supervisorThisBoot]), which
 * only happens when the AgentOS module runs as root. Otherwise (no module, disabled, not rooted, or
 * not reported yet) the text covers both cases instead of claiming root.
 */
object SecurityText {
    const val LEVEL = "best_effort（尽力而为）"

    private const val WHAT_AGENTOS_DOES =
        "AgentOS 能做的是：key 用 Android Keystore 加密保存，只在发出模型请求的那一刻注入，不写进日志、诊断和通知；" +
            "Agent 的代码不以 root 运行，模型和插件接触不到 root。"

    /** Settings page, under "等级". */
    fun settings(rootedKnown: Boolean): String = if (rootedKnown) {
        "这台手机已经 root（本次开机收到了 AgentOS 模块监督进程的状态），其他获得 root 权限的应用可以读取 AgentOS 的全部数据，" +
            "包括加密前后的 key、对话和日志。\n\n" +
            WHAT_AGENTOS_DOES + "但它防不住拥有 root 权限的应用。请只给信任的应用授予 root 权限。"
    } else {
        "本次开机还没有收到 AgentOS 模块监督进程的状态，无法确认这台手机是否已经 root。\n\n" +
            "如果已经 root：其他获得 root 权限的应用可以读取 AgentOS 的全部数据，包括加密前后的 key、对话和日志，" +
            "请只给信任的应用授予 root 权限。如果没有 root：AgentOS 的数据由 Android 的应用隔离保护，其他应用读不到。\n\n" +
            WHAT_AGENTOS_DOES
    }

    /**
     * The security part of the first-run welcome card. [rooted]: true = the supervisor reported this boot,
     * false = the runtime has run 30 s without a report (getDiagnostics supervisorMissing), null = not known yet.
     */
    fun welcome(rooted: Boolean?): String = when (rooted) {
        true ->
            "安全等级：$LEVEL。这台手机已经 root，其他获得 root 权限的应用可以读取 AgentOS 的数据（包括 key），" +
                "AgentOS 只能尽力保护，详见设置页。\n\n" +
                "你现在打开了 App，AgentOS 模块的监督进程从此开始守护运行时：有任务时被系统杀掉会自动拉起。"
        false ->
            "安全等级：$LEVEL，详见设置页。\n\n" +
                "本次开机没有检测到 AgentOS 模块：对话照常可用，但运行时被系统杀掉后不会自动拉起。" +
                "在 root 管理器里刷入 AgentOS 模块并重启后，这里会显示已检测到。"
        null -> "安全等级：$LEVEL，详见设置页。"
    }
}
