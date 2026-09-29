package org.agentos.app.onboarding

import org.agentos.app.settings.BatteryText
import org.agentos.app.settings.SecurityText

/**
 * First-run guide (F2 step 3, W8): what each step shows and whether it is done, from facts the
 * Activity collects. Pure Kotlin (OnboardingTest). Every step can be skipped; only the model is
 * needed for a first conversation.
 */
object Onboarding {
    const val PREFS = "agentos_ui"
    const val KEY_DONE = "onboarding_done_v1"

    enum class Id { WELCOME, MODEL, NOTIFICATIONS, ASSISTANT, BATTERY, PLUGINS }

    /** null = not known yet (e.g. :agent still starting). */
    data class Facts(
        val modelConfigured: Boolean?,
        val modelUsable: Boolean?,
        val notificationsGranted: Boolean,
        val assistantHeld: Boolean,
        val batteryExempt: Boolean,
        val pluginCount: Int?,
        /** true: the root supervisor reported this boot; false: 30 s of runtime without a report; null: not known yet. */
        val rooted: Boolean? = null,
    )

    data class Step(val id: Id, val title: String, val detail: String, val done: Boolean?, val action: String?)

    fun steps(f: Facts): List<Step> = listOf(
        Step(
            Id.WELCOME, "欢迎使用 AgentOS",
            "AgentOS 是常驻在这台手机上的 Agent 服务：本 App 和其他 App 都能通过 ACP 调用它，模型用你自己的 key。\n\n" +
                SecurityText.welcome(f.rooted),
            done = true, action = null,
        ),
        Step(
            Id.MODEL, "选择模型并填写 key",
            when {
                f.modelConfigured == null -> "正在读取当前设置…"
                f.modelConfigured && f.modelUsable == true -> "已配置，可以开始对话。"
                f.modelConfigured -> "已保存，但暂时不可用，打开看看原因。"
                else -> "MiniMax（国际 / 国内）等厂商预设只需要填 key；也可以填自建的兼容端点。不配置就没法对话。"
            },
            done = f.modelConfigured?.let { it && f.modelUsable == true },
            action = if (f.modelConfigured == true) "修改" else "去设置",
        ),
        Step(
            Id.NOTIFICATIONS, "允许通知",
            "Agent 在后台执行任务时会显示一条常驻通知；以后需要你确认的操作也会通过通知询问。",
            done = f.notificationsGranted, action = if (f.notificationsGranted) null else "允许",
        ),
        Step(
            Id.ASSISTANT, "设为默认助理（可选）",
            if (f.assistantHeld) "AgentOS 已是默认助理。"
            else "设为默认助理后，长按电源键即可唤起。这个入口由后续版本（W13）提供；现在可以先打开系统设置看看。",
            done = f.assistantHeld, action = if (f.assistantHeld) null else "打开系统设置",
        ),
        Step(
            Id.BATTERY, "允许忽略电池优化",
            BatteryText.GUIDE_STEP,
            done = f.batteryExempt, action = if (f.batteryExempt) null else "允许",
        ),
        Step(
            Id.PLUGINS, "已发现的插件",
            when (f.pluginCount) {
                null, 0 -> "还没有发现插件。安装了内嵌 AgentOS 插件的 App 之后，会在这里和设置页的插件管理里出现（M3a）。"
                else -> "发现了 ${f.pluginCount} 个插件，可以在设置页里启用。"
            },
            done = null, action = null,
        ),
    )

    /** Steps still worth the user's attention (not done, and not informational). */
    fun pending(f: Facts): List<Id> = steps(f).filter { it.done == false }.map { it.id }

    /**
     * The guide always asks for the battery optimisation exemption (architecture F2 step 3, F11 item 4): if the
     * step was skipped, "开始使用" asks once more before leaving; answering "later" there ends the guide.
     */
    fun askBatteryOnFinish(f: Facts, alreadyAsked: Boolean): Boolean = !f.batteryExempt && !alreadyAsked
}
