package org.agentos.sample.sms.agentos

import android.content.Context
import java.time.ZonedDateTime
import java.util.Locale

/** release：只用真网关和固定的提示词，没有任何开关。 */
object GatewayProvider {
    fun create(context: Context): AgentOsGateway = createRealGateway(context)

    @Suppress("UNUSED_PARAMETER")
    fun promptFor(context: Context, source: ScheduleSource, now: ZonedDateTime, locale: Locale): String = SmsSchedulePrompt.build(source, now, locale)
}
