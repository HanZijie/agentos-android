package org.agentos.runtime.events

import kotlinx.serialization.json.Json

/**
 * 运行时内部统一的 JSON 配置：事件、错误、Pi 事件解码都用它。
 * 有默认值的字段照样输出（`v`、`retryable`、`isError` 等是契约的一部分），值为 null 的字段省略。
 */
val RuntimeJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    classDiscriminator = "type"
}
