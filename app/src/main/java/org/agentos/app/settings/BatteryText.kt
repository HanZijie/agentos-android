package org.agentos.app.settings

/**
 * Wording about the battery optimisation exemption, shared by the settings page (runtime status, desktop
 * access) and the first-run guide. Pure Kotlin (SettingsLogicTest); [Battery] checks and asks.
 *
 * Why it matters (architecture F2, F11 item 4): the runtime has to enter the foreground whenever it works in
 * the background. The system lets a background-started app do that only with the exemption (or when root
 * starts it); otherwise the start is refused (getRuntimeStatus `foregroundDenied`), the process is frozen
 * like any cached app, and a task stalls — seen on an emulator as a turn stuck in retry backoff for 300 s.
 */
object BatteryText {
    /** Button everywhere; the system dialog then asks "let the app always run in the background?". */
    const val ACTION = "允许忽略电池优化"

    /** Runtime status line when getRuntimeStatus says foregroundDenied. */
    const val DENIED_LABEL = "后台运行"

    fun denied(exempt: Boolean): String = if (exempt) {
        "上一次进入前台被系统拒绝；现在已允许忽略电池优化，之后会恢复正常"
    } else {
        "被系统限制：进行中的任务可能被暂停，电脑端接入可能没有响应。请允许 AgentOS 忽略电池优化"
    }

    /** Desktop access card while the switch is on and the exemption is missing. */
    const val DESKTOP_WARNING = "还没有允许忽略电池优化：运行时在后台被重新拉起后可能进不了前台、被系统冻结，电脑端会连不上"

    const val DESKTOP_DIALOG_TITLE = "允许 AgentOS 忽略电池优化？"
    const val DESKTOP_DIALOG_MESSAGE =
        "电脑端接入打开期间，AgentOS 的运行时要一直在后台待命。没有这项豁免时，运行时被系统重新拉起后可能进不了前台、" +
            "被系统冻结，电脑端就连不上。\n\n在接下来的系统弹窗里选择“允许”即可，以后可以在系统设置里撤销。"

    /** First-run guide: asked once more on "开始使用" when the step was skipped, so the guide always asks. */
    const val GUIDE_DIALOG_TITLE = "还没有允许 AgentOS 忽略电池优化"
    const val GUIDE_DIALOG_MESSAGE =
        "不允许的话，AgentOS 在后台运行会被系统限制：进行中的任务可能被暂停，电脑端接入可能没有响应。\n\n" +
            "在接下来的系统弹窗里选择“允许”即可，以后可以在系统设置里撤销。"

    /** First-run guide step text. */
    const val GUIDE_STEP =
        "忽略电池优化后，AgentOS 在后台和灭屏时才能保持运行；不允许的话，任务可能被系统暂停，电脑端接入可能没有响应。"
}
