package org.agentos.sample.sms.tools

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.sample.sms.data.Drafts
import org.agentos.sample.sms.data.Outbox
import org.agentos.sample.sms.data.OutboxEntry
import org.agentos.sample.sms.data.SmsAccess
import org.agentos.sample.sms.data.SmsSettingsValues

/**
 * debug 的 `cmd=dump` 用的状态快照：**只读**，包含权限状态、模式、设置和 outbox。
 * **不读系统短信库**（不含任何收到的短信内容）。outbox 里是本 App 发出的短信，字段与 `sms_send_status` 一致，再加 `to` / `text`。
 *
 * 广播的 result data 约 1 MB 上限，所以按字符预算分页：到预算就停，`next_offset` 指向下一条，没有更多时为 null；
 * 也可以用 `limit` 主动限制每页条数。纯函数，JVM 可测。
 */
object SmsDump {
    const val PAGE_BUDGET_CHARS = 200_000
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 500

    fun build(
        access: SmsAccess,
        settings: SmsSettingsValues,
        outbox: Outbox,
        drafts: Drafts,
        nowMillis: Long,
        zone: ZoneId,
        offset: Int = 0,
        limit: Int? = null,
    ): JsonObject {
        val total = outbox.count()
        val start = offset.coerceIn(0, total)
        val maxItems = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val page = ArrayList<JsonObject>()
        var chars = 0
        var next = start
        for (entry in outbox.page(start, maxItems)) {
            val item = entryJson(entry, zone)
            val cost = item.toString().length
            if (page.isNotEmpty() && chars + cost > PAGE_BUDGET_CHARS) break
            page += item
            chars += cost
            next++
        }
        return buildJsonObject {
            put("mode", access.mode.wire)
            put(
                "permissions",
                buildJsonObject {
                    put("read_sms", access.canRead)
                    put("send_sms", access.canSend)
                },
            )
            put(
                "settings",
                buildJsonObject {
                    put("mask_codes", settings.maskCodes)
                    put("allow_short_numbers", settings.allowShortNumbers)
                    put("rate_limit", settings.rateLimit)
                },
            )
            put("outbox", buildJsonArray { page.forEach { add(it) } })
            put(
                "drafts",
                buildJsonArray {
                    drafts.recent.value.forEach {
                        add(
                            buildJsonObject {
                                put("id", it.id)
                                put("to", it.to)
                                put("text", it.text?.let { t -> JsonPrimitive(t) } ?: JsonNull)
                                put("created_at", iso(it.createdAt, zone))
                            },
                        )
                    }
                },
            )
            put("total", total)
            put("offset", start)
            put("count", page.size)
            put("next_offset", if (next < total) JsonPrimitive(next) else JsonNull)
            put("now", iso(nowMillis, zone))
            put("time_zone", zone.id)
        }
    }

    fun entryJson(entry: OutboxEntry, zone: ZoneId): JsonObject = buildJsonObject {
        put("id", entry.id)
        put("to", entry.to)
        put("text", entry.text)
        put("parts", entry.parts)
        put("state", entry.state.wire)
        put("sent_parts", entry.sentParts)
        put("delivered_parts", entry.deliveredParts)
        put("error", entry.error?.let { JsonPrimitive(it) } ?: JsonNull)
        put("created_at", iso(entry.createdAt, zone))
        put("updated_at", iso(entry.updatedAt, zone))
    }

    private fun iso(millis: Long, zone: ZoneId): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).truncatedTo(ChronoUnit.SECONDS).format(SmsTools.ISO)
}
