package org.agentos.app.settings

import org.agentos.app.R
import org.agentos.app.i18n.Strings

/**
 * The security level text of the settings page and the first-run guide. The level is fixed at
 * best_effort (architecture decision record) and told as it is. "This phone is rooted" is only said
 * when it is known: the root supervisor reported in this boot ([StatusText.supervisorThisBoot]), which
 * only happens when the AgentOS module runs as root. Otherwise (no module, disabled, not rooted, or
 * not reported yet) the text covers both cases instead of claiming root.
 *
 * The words live in `strings_p2.xml` (`security_*`); the rooted / not-known split and which sentences each
 * variant carries are decided here, so a translation cannot drop the warning from one variant (SettingsLogicTest
 * checks both languages).
 */
object SecurityText {
    fun level(strings: Strings): String = strings.get(R.string.security_level)

    private fun whatAgentosDoes(strings: Strings): String = strings.get(R.string.security_what_agentos_does)

    /** Settings page, under "等级". */
    fun settings(rootedKnown: Boolean, strings: Strings): String =
        if (rootedKnown) strings.get(R.string.security_settings_rooted, whatAgentosDoes(strings))
        else strings.get(R.string.security_settings_unknown, whatAgentosDoes(strings))

    /**
     * The security part of the first-run welcome card. [rooted]: true = the supervisor reported this boot,
     * false = the runtime has run 30 s without a report (getDiagnostics supervisorMissing), null = not known yet.
     */
    fun welcome(rooted: Boolean?, strings: Strings): String = when (rooted) {
        true -> strings.get(R.string.security_welcome_rooted, level(strings))
        false -> strings.get(R.string.security_welcome_no_module, level(strings))
        null -> strings.get(R.string.security_welcome_unknown, level(strings))
    }
}
