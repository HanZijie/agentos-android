package org.agentos.app.onboarding

import org.agentos.app.R
import org.agentos.app.i18n.Strings
import org.agentos.app.settings.BatteryText
import org.agentos.app.settings.SecurityText

/**
 * First-run guide (F2 step 3, W8): what each step shows and whether it is done, from facts the
 * Activity collects. Pure Kotlin (OnboardingTest). Every step can be skipped; only the model is
 * needed for a first conversation. The words live in `strings_p2.xml` (`onboarding_*`), read through [Strings].
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

    fun steps(f: Facts, strings: Strings): List<Step> = listOf(
        Step(
            Id.WELCOME, strings.get(R.string.onboarding_welcome_title),
            strings.get(R.string.onboarding_welcome_detail, SecurityText.welcome(f.rooted, strings)),
            done = true, action = null,
        ),
        Step(
            Id.MODEL, strings.get(R.string.onboarding_model_title),
            strings.get(
                when {
                    f.modelConfigured == null -> R.string.onboarding_model_loading
                    f.modelConfigured && f.modelUsable == true -> R.string.onboarding_model_ready
                    f.modelConfigured -> R.string.onboarding_model_saved_unusable
                    else -> R.string.onboarding_model_none
                },
            ),
            done = f.modelConfigured?.let { it && f.modelUsable == true },
            action = strings.get(if (f.modelConfigured == true) R.string.onboarding_model_change else R.string.onboarding_model_set),
        ),
        Step(
            Id.NOTIFICATIONS, strings.get(R.string.onboarding_notifications_title),
            strings.get(R.string.onboarding_notifications_detail),
            done = f.notificationsGranted, action = if (f.notificationsGranted) null else strings.get(R.string.onboarding_allow),
        ),
        Step(
            Id.ASSISTANT, strings.get(R.string.onboarding_assistant_title),
            strings.get(if (f.assistantHeld) R.string.onboarding_assistant_held else R.string.onboarding_assistant_detail),
            done = f.assistantHeld, action = if (f.assistantHeld) null else strings.get(R.string.onboarding_assistant_open),
        ),
        Step(
            Id.BATTERY, strings.get(R.string.onboarding_battery_title),
            BatteryText.guideStep(strings),
            done = f.batteryExempt, action = if (f.batteryExempt) null else strings.get(R.string.onboarding_allow),
        ),
        Step(
            Id.PLUGINS, strings.get(R.string.onboarding_plugins_title),
            when (val n = f.pluginCount) {
                null, 0 -> strings.get(R.string.onboarding_plugins_none)
                else -> strings.plural(R.plurals.onboarding_plugins_found, n, n)
            },
            done = null, action = null,
        ),
    )

    /** Steps still worth the user's attention (not done, and not informational). */
    fun pending(f: Facts, strings: Strings): List<Id> = steps(f, strings).filter { it.done == false }.map { it.id }

    /**
     * The guide always asks for the battery optimisation exemption (architecture F2 step 3, F11 item 4): if the
     * step was skipped, "开始使用" asks once more before leaving; answering "later" there ends the guide.
     */
    fun askBatteryOnFinish(f: Facts, alreadyAsked: Boolean): Boolean = !f.batteryExempt && !alreadyAsked
}
