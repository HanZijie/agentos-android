package org.agentos.runtime.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.errors.RpcErrorData
import org.agentos.runtime.events.RuntimeJson

/**
 * 电脑端连接的配对握手（acp-mapping.md 第 10 节）。连接建立后，客户端发的**第一行**必须是：
 *
 * ```json
 * {"jsonrpc":"2.0","id":0,"method":"_org.agentos/pair","params":{"version":1,"code":"482913","label":"acp-bridge@my-laptop"}}
 * {"jsonrpc":"2.0","id":0,"method":"_org.agentos/pair","params":{"version":1,"token":"<配对时拿到的令牌>"}}
 * ```
 *
 * 成功：`{"jsonrpc":"2.0","id":0,"result":{"version":1,"pairingId":"dp_…","token":"…","maxLineChars":65536}}`
 * （`token` 只在用配对码配对时出现），之后同一连接上是普通的 ACP。
 * 失败：JSON-RPC 错误 -32000（`auth_required`），`data.details.reason` 是 [PairingFailure.wire]，然后关闭连接。
 * 第一行不是配对请求（例如直接发 ACP 的 `initialize`）时同样回 `auth_required`（沿用那条请求的 id），不处理它。
 */
object DesktopHandshake {
    const val METHOD = "_org.agentos/pair"
    const val VERSION = 1

    /** 握手行的上限（字符）。握手之前的连接还没有认证，不给它 65,536 字符的缓冲。 */
    const val MAX_LINE_CHARS = 4_096

    /** 连上之后多久内必须发来握手行。 */
    const val TIMEOUT_MILLIS = 10_000L

    sealed interface Request {
        /** 原样回显的请求 id；没有或不合法时是 JSON null。 */
        val id: JsonElement

        data class Code(override val id: JsonElement, val code: String, val label: String?) : Request

        class Token(override val id: JsonElement, val token: String) : Request {
            override fun toString() = "Token(id=$id, ****)"
        }

        data class Invalid(override val id: JsonElement) : Request
    }

    private val lenient = Json { ignoreUnknownKeys = true }

    fun parse(line: String): Request {
        val obj = runCatching { lenient.parseToJsonElement(line).jsonObject }.getOrNull() ?: return Request.Invalid(JsonNull)
        val id = obj["id"].let { if (it is JsonPrimitive && it !is JsonNull) it else JsonNull }
        if ((obj["jsonrpc"] as? JsonPrimitive)?.content != "2.0" || (obj["method"] as? JsonPrimitive)?.content != METHOD || id == JsonNull) {
            return Request.Invalid(id)
        }
        val params = obj["params"] as? JsonObject ?: return Request.Invalid(id)
        val version = (params["version"] as? JsonPrimitive)?.intOrNull ?: VERSION
        if (version != VERSION) return Request.Invalid(id)
        val code = (params["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val token = (params["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val label = (params["label"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when {
            code != null && token == null -> Request.Code(id, code, label)
            token != null && code == null -> Request.Token(id, token)
            else -> Request.Invalid(id)
        }
    }

    fun success(id: JsonElement, accepted: PairingResult.Accepted, maxLineChars: Int): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", buildJsonObject {
                put("version", VERSION)
                put("pairingId", accepted.pairing.id)
                accepted.token?.let { put("token", it) }
                put("maxLineChars", maxLineChars)
            })
        },
    )

    fun failure(id: JsonElement, failure: PairingFailure): String =
        error(id, ErrorCode.AUTH_REQUIRED, "${failure.message}", buildJsonObject { put("reason", failure.wire) })

    /** 连接数已满（`busy`，可重试）。 */
    fun busy(): String = error(JsonNull, ErrorCode.BUSY, "too many desktop connections", null)

    private fun error(id: JsonElement, code: ErrorCode, message: String, details: JsonObject?): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject {
                put("code", code.rpcCode)
                put("message", "${code.wire}: $message")
                put("data", RuntimeJson.encodeToJsonElement(RpcErrorData.serializer(), RpcErrorData(code, code.retryable, details = details)))
            })
        },
    )
}
