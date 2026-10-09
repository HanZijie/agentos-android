package org.agentos.sample.sms.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.agentos.sample.sms.rules.RateLimit

/**
 * 安全开关（S1、S3）。默认值都是最保守的：
 * - [maskCodes] = true：验证码遮蔽；界面上的开关叫“允许 Agent 读取验证码”，开 = [maskCodes] 为 false；
 * - [allowShortNumbers] = false：拒绝发给短号 / 服务号码；
 * - [rateLimit]：10 分钟内最多发几条。
 */
data class SmsSettingsValues(
    val maskCodes: Boolean = true,
    val allowShortNumbers: Boolean = false,
    val rateLimit: Int = RateLimit.DEFAULT_LIMIT,
)

interface SmsSettings {
    val values: StateFlow<SmsSettingsValues>

    fun update(transform: (SmsSettingsValues) -> SmsSettingsValues)

    val current: SmsSettingsValues get() = values.value
}

class InMemorySmsSettings(initial: SmsSettingsValues = SmsSettingsValues()) : SmsSettings {
    private val state = MutableStateFlow(initial)
    override val values: StateFlow<SmsSettingsValues> = state.asStateFlow()

    override fun update(transform: (SmsSettingsValues) -> SmsSettingsValues) {
        state.value = transform(state.value).normalized()
    }
}

class PrefsSmsSettings(context: Context) : SmsSettings {
    private val prefs = context.applicationContext.getSharedPreferences("sms_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    override val values: StateFlow<SmsSettingsValues> = state.asStateFlow()

    @Synchronized
    override fun update(transform: (SmsSettingsValues) -> SmsSettingsValues) {
        val next = transform(state.value).normalized()
        prefs.edit {
            putBoolean(KEY_MASK, next.maskCodes)
            putBoolean(KEY_SHORT, next.allowShortNumbers)
            putInt(KEY_RATE, next.rateLimit)
        }
        state.value = next
    }

    private fun read() = SmsSettingsValues(
        maskCodes = prefs.getBoolean(KEY_MASK, true),
        allowShortNumbers = prefs.getBoolean(KEY_SHORT, false),
        rateLimit = RateLimit.clampLimit(prefs.getInt(KEY_RATE, RateLimit.DEFAULT_LIMIT)),
    )

    private companion object {
        const val KEY_MASK = "mask_codes"
        const val KEY_SHORT = "allow_short_numbers"
        const val KEY_RATE = "rate_limit"
    }
}

internal fun SmsSettingsValues.normalized() = copy(rateLimit = RateLimit.clampLimit(rateLimit))
