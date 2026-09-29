package org.agentos.runtime.testing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.errors.ErrorInfo
import org.agentos.runtime.events.RuntimeJson
import org.agentos.runtime.ports.AgentSessionConfig
import org.agentos.runtime.ports.TurnInput

/**
 * FakeAgentCore 的剧本：一轮 prompt 由若干次“模型往返”（[rounds]）组成，每次往返产生一条 assistant 消息。
 * 某次往返里有 [FakeStep.ToolUse] 时，假 core 按 Pi 的顺序执行这些工具（beforeToolCall → executeTool → afterToolCall），
 * 然后进入下一次往返。剧本里的往返用完、最后一次仍有工具调用时，自动补一次只含文字 [FINAL_TEXT] 的往返。
 */
data class FakeTurnScript(val rounds: List<List<FakeStep>>) {
    constructor(vararg steps: FakeStep) : this(listOf(steps.toList()))

    companion object {
        const val FINAL_TEXT = "(fake) done"
    }
}

/** 剧本的一步。 */
sealed interface FakeStep {
    /** 流式文字：每 [chunkChars] 个字符一个 text_delta，每段之间等 [intervalMs]（0 表示只让出线程）。 */
    data class Text(val text: String, val chunkChars: Int = 8, val intervalMs: Long = 0) : FakeStep

    /** 流式 thinking。 */
    data class Thinking(val text: String, val chunkChars: Int = 16, val intervalMs: Long = 0) : FakeStep

    /** 在这条 assistant 消息里请求一次工具调用。[id] 为空时自动生成。 */
    data class ToolUse(val name: String, val arguments: JsonObject = JsonObject(emptyMap()), val id: String? = null) : FakeStep

    /** 等待（可被 abort 打断）。 */
    data class Delay(val millis: Long) : FakeStep

    /** 一直挂起，直到 abort（测试取消）。 */
    data object AwaitAbort : FakeStep

    /** 模型调用失败：本轮以 TurnOutcome.Failed 结束。 */
    data class Fail(val error: ErrorInfo) : FakeStep

    /** 模型以 length 结束：本轮以 FinishReason.MAX_TOKENS 结束。 */
    data object MaxTokens : FakeStep

    /** 模拟泵故障（F8）：Agent core 整个失效，进行中的轮次都以 CoreLost 返回。 */
    data class CrashCore(val message: String = "fake pump exited") : FakeStep
}

/** 选剧本时能看到的上下文。[turnIndex] 是这个会话在本实例里的第几轮（从 0 开始）。 */
data class FakeTurnContext(
    val sessionId: String,
    val turnIndex: Int,
    val input: TurnInput,
    val config: AgentSessionConfig,
)

/** 常用剧本。 */
object FakeScripts {
    /** 把输入原样分段回显。 */
    fun echo(chunkChars: Int = 8): (FakeTurnContext) -> FakeTurnScript = { ctx ->
        FakeTurnScript(FakeStep.Text("echo: ${ctx.input.text}", chunkChars))
    }

    /** 每一轮都用同一个剧本。 */
    fun always(script: FakeTurnScript): (FakeTurnContext) -> FakeTurnScript = { script }

    /** 按顺序使用给定的剧本，用完后回显。 */
    fun sequence(vararg scripts: FakeTurnScript): (FakeTurnContext) -> FakeTurnScript {
        val queue = ArrayDeque(scripts.toList())
        val fallback = echo()
        return { ctx -> synchronized(queue) { queue.removeFirstOrNull() } ?: fallback(ctx) }
    }

    /**
     * 由 prompt 文字里的 JSON 指令决定剧本（给 ACP 一致性测试和设备测试用，客户端不需要知道 Kotlin 类型）：
     *
     * ```json
     * {"fake": {"chunks": 20, "chunkChars": 16, "intervalMs": 20, "text": "x", "thinking": "…",
     *           "tools": [{"name": "add", "arguments": {"a": 1}}], "awaitAbort": true,
     *           "fail": "model_rate_limited", "failMessage": "…", "failDetails": {"reason": "key_revoked"},
     *           "maxTokens": true, "crash": true, "toolLoop": 13}}
     * ```
     *
     * 不是这种 JSON 时回显。字段都可省略；`chunks` 条文字先发，然后 thinking 在前（若有），
     * 有 `tools` 时本轮先调用工具、再以 [FakeTurnScript.FINAL_TEXT] 结束；`awaitAbort` 在发完文字后挂起到 abort。
     */
    fun directives(): (FakeTurnContext) -> FakeTurnScript {
        val fallback = echo()
        return { ctx -> parseDirective(ctx.input.text) ?: fallback(ctx) }
    }

    internal fun parseDirective(text: String): FakeTurnScript? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return null
        val root = runCatching { RuntimeJson.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        val d = root["fake"] as? JsonObject ?: return null
        fun int(key: String) = (d[key] as? JsonPrimitive)?.intOrNull
        fun long(key: String) = (d[key] as? JsonPrimitive)?.longOrNull
        fun str(key: String) = (d[key] as? JsonPrimitive)?.contentOrNull
        fun bool(key: String) = (d[key] as? JsonPrimitive)?.booleanOrNull == true

        val steps = mutableListOf<FakeStep>()
        str("thinking")?.let { steps += FakeStep.Thinking(it) }
        val chunks = int("chunks") ?: 0
        val chunkChars = int("chunkChars") ?: 8
        val interval = long("intervalMs") ?: 0
        val fill = str("text") ?: "x"
        if (chunks > 0) {
            val body = buildString { repeat(chunks) { i -> append(fill.repeat(chunkChars).take(chunkChars).ifEmpty { "$i" }) } }
            steps += FakeStep.Text(body, chunkChars, interval)
        }
        (d["tools"] as? JsonArray)?.forEach { el ->
            val t = el as? JsonObject ?: return@forEach
            val name = (t["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            steps += FakeStep.ToolUse(name, t["arguments"] as? JsonObject ?: JsonObject(emptyMap()))
        }
        if (bool("awaitAbort")) steps += FakeStep.AwaitAbort
        str("fail")?.let { code ->
            val c = ErrorCode.fromWire(code) ?: ErrorCode.MODEL_UNAVAILABLE
            // failMessage / failDetails：模拟 Agent core 带说明和 details 的失败（例如 B5 的 key_revoked）
            steps += FakeStep.Fail(
                org.agentos.runtime.errors.ErrorInfo(c, str("failMessage") ?: "fake failure: $code", details = d["failDetails"] as? JsonObject),
            )
        }
        if (bool("maxTokens")) steps += FakeStep.MaxTokens
        if (bool("crash")) steps += FakeStep.CrashCore()
        // toolLoop: N 次连续带工具调用的往返（测工具轮次上限）；工具取 tools 的第一个，没有就用 add(1, 1)
        int("toolLoop")?.takeIf { it > 0 }?.let { n ->
            val tool = steps.filterIsInstance<FakeStep.ToolUse>().firstOrNull()
                ?: FakeStep.ToolUse("add", kotlinx.serialization.json.buildJsonObject { put("a", JsonPrimitive(1)); put("b", JsonPrimitive(1)) })
            val first = steps.filterNot { it is FakeStep.ToolUse } + tool.copy(id = null)
            return FakeTurnScript(listOf(first) + List(n - 1) { listOf(tool.copy(id = null)) })
        }
        if (steps.isEmpty()) steps += FakeStep.Text("ok")
        return FakeTurnScript(listOf(steps))
    }
}

internal fun fakeUsage(): JsonObject = buildJsonObject {
    put("input", JsonPrimitive(0))
    put("output", JsonPrimitive(0))
    put("cacheRead", JsonPrimitive(0))
    put("cacheWrite", JsonPrimitive(0))
    put("totalTokens", JsonPrimitive(0))
    put(
        "cost",
        buildJsonObject {
            listOf("input", "output", "cacheRead", "cacheWrite", "total").forEach { put(it, JsonPrimitive(0)) }
        },
    )
}
