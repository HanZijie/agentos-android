package org.agentos.sample.sms.agentos

/** 面板上按钮的显示规则（纯函数，单测）：界面只负责把结果画出来。 */
object PanelRules {
    data class ViewLinks(val calendar: Boolean, val alarm: Boolean, val todo: Boolean) {
        val any: Boolean get() = calendar || alarm || todo
    }

    /** “在日历 / 待办 / 闹钟中查看”：对应的 App 已安装，并且确实创建了对应的东西才显示。 */
    fun viewLinks(summary: ScheduleSummary, calendarInstalled: Boolean, alarmInstalled: Boolean, todoInstalled: Boolean): ViewLinks =
        ViewLinks(
            calendar = calendarInstalled && summary.eventCount > 0,
            alarm = alarmInstalled && summary.alarmCount > 0,
            todo = todoInstalled && summary.todoCount > 0,
        )

    enum class Primary { OPEN_AGENTOS, LEARN_MORE, RETRY, NONE }

    /** 出错面板的按钮：[primary] 是实心的主操作，[retryToo] 表示主操作之外还要有一个“再试一次”。 */
    data class ErrorActions(val primary: Primary, val retryToo: Boolean) {
        /** 有下一步可做时，底下的文字按钮叫“取消”，否则叫“完成”。 */
        val hasNextStep: Boolean get() = primary != Primary.NONE || retryToo
    }

    fun errorActions(error: AgentOsError, interrupted: Boolean, hasSourceText: Boolean, agentOsInstalled: Boolean): ErrorActions {
        val retryable = !interrupted && hasSourceText && when (error) {
            // 授权待决超时 AgentOS 按拒绝记，进入 10 分钟冷却：马上重试只会得到 DENIED，所以和 DENIED 一样去 AgentOS 里直接允许
            AgentOsError.NOT_INSTALLED, AgentOsError.DENIED, AgentOsError.AUTHORIZATION_PENDING_TIMEOUT, AgentOsError.TOO_LARGE -> false
            else -> true
        }
        val openAgentOs = agentOsInstalled && !interrupted &&
            (error == AgentOsError.NO_MODEL || error == AgentOsError.DENIED || error == AgentOsError.AUTHORIZATION_PENDING_TIMEOUT)
        val learnMore = !interrupted && error == AgentOsError.NOT_INSTALLED
        val primary = when {
            openAgentOs -> Primary.OPEN_AGENTOS
            learnMore -> Primary.LEARN_MORE
            retryable -> Primary.RETRY
            else -> Primary.NONE
        }
        return ErrorActions(primary, retryToo = retryable && primary != Primary.RETRY)
    }
}
