package org.agentos.app.settings

import org.agentos.app.R
import org.agentos.app.i18n.Strings

/**
 * Wording about the battery optimisation exemption, shared by the settings page (runtime status, desktop
 * access) and the first-run guide. Pure Kotlin (SettingsLogicTest); [Battery] checks and asks. The words live
 * in `strings_p2.xml` (`battery_*`), read through [Strings].
 *
 * Why it matters (architecture F2, F11 item 4): the runtime has to enter the foreground whenever it works in
 * the background. The system lets a background-started app do that only with the exemption (or when root
 * starts it); otherwise the start is refused (getRuntimeStatus `foregroundDenied`), the process is frozen
 * like any cached app, and a task stalls — seen on an emulator as a turn stuck in retry backoff for 300 s.
 */
object BatteryText {
    /** Button everywhere; the system dialog then asks "let the app always run in the background?". */
    fun action(strings: Strings): String = strings.get(R.string.battery_action)

    /** Runtime status line when getRuntimeStatus says foregroundDenied. */
    fun deniedLabel(strings: Strings): String = strings.get(R.string.battery_denied_label)

    fun denied(exempt: Boolean, strings: Strings): String =
        strings.get(if (exempt) R.string.battery_denied_exempt else R.string.battery_denied_restricted)

    /** Desktop access card while the switch is on and the exemption is missing. */
    fun desktopWarning(strings: Strings): String = strings.get(R.string.battery_desktop_warning)

    fun desktopDialogTitle(strings: Strings): String = strings.get(R.string.battery_desktop_dialog_title)
    fun desktopDialogMessage(strings: Strings): String = strings.get(R.string.battery_desktop_dialog_message)

    /** First-run guide: asked once more on "开始使用" when the step was skipped, so the guide always asks. */
    fun guideDialogTitle(strings: Strings): String = strings.get(R.string.battery_guide_dialog_title)
    fun guideDialogMessage(strings: Strings): String = strings.get(R.string.battery_guide_dialog_message)

    /** First-run guide step text. */
    fun guideStep(strings: Strings): String = strings.get(R.string.battery_guide_step)
}
