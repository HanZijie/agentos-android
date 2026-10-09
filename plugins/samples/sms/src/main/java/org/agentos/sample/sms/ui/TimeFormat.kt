package org.agentos.sample.sms.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * 列表里的时间文字（R4、R8）：今天显示时刻，其他显示日期；格式交给 `java.time` 的本地化风格（跟随语言和地区，包括 12 / 24 小时习惯），
 * 不手拼单位字，也不判断语言。纯函数，接收 locale，JVM 测试中英各一个用例。
 */
object MessageTime {
    fun format(millis: Long, nowMillis: Long, zone: ZoneId, locale: Locale): String {
        val moment = Instant.ofEpochMilli(millis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val formatter = if (moment.toLocalDate() == today) {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        } else {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
        }
        return formatter.withLocale(locale).format(moment)
    }
}
