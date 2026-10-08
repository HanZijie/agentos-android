package org.agentos.sample.sms.ui

import org.agentos.sample.sms.R

/**
 * outbox 里的失败原因是英文短语（`radio_off`、`no_service` …，给模型看，不本地化，R2）；界面上把已知的几个换成资源里的文字（R1），
 * 不认识的原样显示。纯映射，JVM 可测。
 */
object FailureReason {
    fun res(reason: String): Int? = when (reason) {
        "radio_off", "radio_not_available" -> R.string.reason_radio_off
        "no_service" -> R.string.reason_no_service
        "generic_failure", "modem_error", "network_error", "system_error", "internal_error", "network_reject" -> R.string.reason_generic
        "limit_exceeded" -> R.string.reason_limit
        "short_code_not_allowed", "short_code_never_allowed" -> R.string.reason_short_code
        "delivery_failed" -> R.string.reason_delivery_failed
        "permission_denied" -> R.string.reason_permission
        else -> null
    }
}
