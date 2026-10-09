package org.agentos.runtime.consent

/**
 * 确认链上核心层产出的文案 key（[org.agentos.runtime.i18n.MessageRef.key]）。名字和 app 里 `values/strings.xml` 的资源名相同；
 * 参数顺序就是模板里 `%1$s`、`%2$s` 的顺序。app 层的映射表是 `org.agentos.app.i18n.CoreMessages`，它的测试保证这里每个 key 都有对应的中英文模板。
 */
object ConsentMessages {
    /** 标题。参数：工具显示名（已清理）。 */
    const val TITLE = "consent_title"

    /** 发起者，第三方 App、知道包名。参数：名字（已清理，括号已去掉）、包名。 */
    const val INITIATOR_APP = "consent_initiator_app"

    /** 发起者，只有一个可显示的名字（第三方 App 没有包名时是 App 自己起的名字；有包名但名字不可用时是包名）。参数：名字或包名。 */
    const val INITIATOR_NAMED = "consent_initiator_named"

    /** 第三方 App，没有名字也没有包名。无参数。 */
    const val INITIATOR_UNKNOWN_APP = "consent_initiator_unknown_app"

    const val INITIATOR_DESKTOP = "consent_initiator_desktop"
    const val INITIATOR_SELF = "consent_initiator_self"
    const val INITIATOR_SYSTEM = "consent_initiator_system"

    /** 来源一行。参数：插件名、服务器名（已清理）。 */
    const val SOURCE = "consent_source"

    /** 风险标签（无参数）：只读 / 会修改数据 / 高风险。 */
    const val RISK_READ = "consent_risk_read"
    const val RISK_WRITE = "consent_risk_write"
    const val RISK_HIGH = "consent_risk_high"

    /** 风险说明（无参数）；高风险那条写明“可能不可恢复”。 */
    const val RISK_DESC_READ = "consent_risk_desc_read"
    const val RISK_DESC_WRITE = "consent_risk_desc_write"
    const val RISK_DESC_HIGH = "consent_risk_desc_high"

    /** 选项标签（无参数），对应 [ConsentChoice]。 */
    const val OPTION_ALLOW_ONCE = "consent_option_allow_once"
    const val OPTION_ALLOW_FOR_SESSION = "consent_option_allow_for_session"
    const val OPTION_ALWAYS_ALLOW = "consent_option_always_allow"
    const val OPTION_DENY = "consent_option_deny"

    /** “始终允许”没能保存时告诉用户的一句话（结案提示，[ConsentResolution.notice]；无参数），后缀是原因。 */
    const val NOTICE_UNSAVED_NO_SOURCE = "consent_notice_unsaved_no_source"
    const val NOTICE_UNSAVED_ERROR = "consent_notice_unsaved_error"
    const val NOTICE_UNSAVED_TIMEOUT = "consent_notice_unsaved_timeout"
    const val NOTICE_UNSAVED_POLICY = "consent_notice_unsaved_policy"

    /** 本对象产出的全部 key：app 层的完整性测试遍历它。 */
    val ALL: List<String> = listOf(
        TITLE, INITIATOR_APP, INITIATOR_NAMED, INITIATOR_UNKNOWN_APP, INITIATOR_DESKTOP, INITIATOR_SELF, INITIATOR_SYSTEM, SOURCE,
        RISK_READ, RISK_WRITE, RISK_HIGH, RISK_DESC_READ, RISK_DESC_WRITE, RISK_DESC_HIGH,
        OPTION_ALLOW_ONCE, OPTION_ALLOW_FOR_SESSION, OPTION_ALWAYS_ALLOW, OPTION_DENY,
        NOTICE_UNSAVED_NO_SOURCE, NOTICE_UNSAVED_ERROR, NOTICE_UNSAVED_TIMEOUT, NOTICE_UNSAVED_POLICY,
    )
}
