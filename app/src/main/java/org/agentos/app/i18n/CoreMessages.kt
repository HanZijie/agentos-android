package org.agentos.app.i18n

import org.agentos.app.R
import org.agentos.runtime.consent.ConsentMessages

/**
 * 核心层文案 key → app 资源。核心层是纯 Kotlin / JVM，只给 [org.agentos.runtime.i18n.MessageRef]（key + 参数）；这张表是唯一的接缝：
 * 加一个核心层 key，就在这里登记（资源 id 和占位符个数），并在 `values/` 和 `values-en/` 各加一条（`CoreMessagesTest` 逐项检查，
 * 占位符个数、两种语言都在）。不用 `getIdentifier`：编译期检查资源存在，也不怕资源收缩。
 */
object CoreMessages {
    class Entry(val id: Int, val arity: Int)

    private val table: Map<String, Entry> = mapOf(
        ConsentMessages.TITLE to Entry(R.string.consent_title, 1),
        ConsentMessages.INITIATOR_APP to Entry(R.string.consent_initiator_app, 2),
        ConsentMessages.INITIATOR_NAMED to Entry(R.string.consent_initiator_named, 1),
        ConsentMessages.INITIATOR_UNKNOWN_APP to Entry(R.string.consent_initiator_unknown_app, 0),
        ConsentMessages.INITIATOR_DESKTOP to Entry(R.string.consent_initiator_desktop, 0),
        ConsentMessages.INITIATOR_SELF to Entry(R.string.consent_initiator_self, 0),
        ConsentMessages.INITIATOR_SYSTEM to Entry(R.string.consent_initiator_system, 0),
        ConsentMessages.SOURCE to Entry(R.string.consent_source, 2),
        ConsentMessages.RISK_READ to Entry(R.string.consent_risk_read, 0),
        ConsentMessages.RISK_WRITE to Entry(R.string.consent_risk_write, 0),
        ConsentMessages.RISK_HIGH to Entry(R.string.consent_risk_high, 0),
        ConsentMessages.RISK_DESC_READ to Entry(R.string.consent_risk_desc_read, 0),
        ConsentMessages.RISK_DESC_WRITE to Entry(R.string.consent_risk_desc_write, 0),
        ConsentMessages.RISK_DESC_HIGH to Entry(R.string.consent_risk_desc_high, 0),
        ConsentMessages.OPTION_ALLOW_ONCE to Entry(R.string.consent_option_allow_once, 0),
        ConsentMessages.OPTION_ALLOW_FOR_SESSION to Entry(R.string.consent_option_allow_for_session, 0),
        ConsentMessages.OPTION_ALWAYS_ALLOW to Entry(R.string.consent_option_always_allow, 0),
        ConsentMessages.OPTION_DENY to Entry(R.string.consent_option_deny, 0),
        ConsentMessages.NOTICE_UNSAVED_NO_SOURCE to Entry(R.string.consent_notice_unsaved_no_source, 0),
        ConsentMessages.NOTICE_UNSAVED_ERROR to Entry(R.string.consent_notice_unsaved_error, 0),
        ConsentMessages.NOTICE_UNSAVED_TIMEOUT to Entry(R.string.consent_notice_unsaved_timeout, 0),
        ConsentMessages.NOTICE_UNSAVED_POLICY to Entry(R.string.consent_notice_unsaved_policy, 0),
    )

    fun entry(key: String): Entry? = table[key]

    /** 登记了的全部 key（测试用）。 */
    val keys: Set<String> get() = table.keys
}
