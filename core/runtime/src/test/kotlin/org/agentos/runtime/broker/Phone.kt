package org.agentos.runtime.broker

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.RuntimeConfig
import org.agentos.runtime.events.EventEnvelope
import org.agentos.runtime.events.EventTypes
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.HookDecision
import org.agentos.runtime.ports.HookOutcome
import org.agentos.runtime.ports.ToolRef
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import org.agentos.runtime.store.TaskState
import org.agentos.runtime.testing.FakeScripts
import org.agentos.runtime.testing.FakeStep
import org.agentos.runtime.testing.FakeTurnScript
import org.agentos.runtime.testing.TestRuntime
import kotlin.test.assertEquals

/**
 * A phone with the sample apps' tools, shared by the caller-policy tests: two write tools, one read tool, one destructive one.
 * [policy] selects the [CallerPolicy] of the runtime (default: the shipped default, open).
 */
internal class Phone(script: FakeTurnScript? = null, policy: CallerPolicy = OpenCallerPolicy, hookAsk: Boolean = false) {
    val alarm = ToolSource("alarm", "main", "alarm_create")
    val event = ToolSource("calendar", "main", "event_create")
    val list = ToolSource("notes", "main", "note_list")
    val delete = ToolSource("notes", "main", "note_delete")

    val memoScope = listOf(ToolRef("alarm", "alarm_create"), ToolRef("calendar", "event_create"))
    val allTools = listOf("alarm_create", "event_create", "note_delete", "note_list")

    val config: RuntimeConfig = TestRuntime.config(policy)
    val rt = TestRuntime(if (script != null) FakeScripts.always(script) else FakeScripts.echo(), config = config).also { rt ->
        rt.host.tools.registerSimple("alarm_create", ToolRisk.WRITE, alarm) { ToolResult.text("alarm set") }
        rt.host.tools.registerSimple("event_create", ToolRisk.WRITE, event) { ToolResult.text("event created") }
        rt.host.tools.registerSimple("note_list", ToolRisk.READ, list) { ToolResult.text("[]") }
        rt.host.tools.registerSimple("note_delete", ToolRisk.HIGH, delete) { ToolResult.text("deleted") }
        if (hookAsk) rt.host.hooks.answer = { HookOutcome(matched = 1, decision = HookDecision.ASK) }
    }

    fun <T> run(block: suspend (TestRuntime) -> T) {
        runBlocking {
            rt.start()
            try {
                withTimeout(15_000) { block(rt) }
            } finally {
                rt.stop()
                rt.host.deleteDatabase()
            }
        }
    }

    companion object {
        fun script(vararg names: String) = FakeTurnScript(
            listOf(names.mapIndexed { i, n -> FakeStep.ToolUse(n, buildJsonObject { put("n", i) }, id = "call_$i") }, listOf(FakeStep.Text("done"))),
        )
    }
}

/** One turn as [caller]: creates a session with [scope] (null = none given), sends [text], waits for the task, returns the session id and its events. */
internal suspend fun TestRuntime.turn(caller: CallerIdentity, scope: List<ToolRef>?, text: String = "go"): Pair<String, List<EventEnvelope>> {
    val s = engine.createSession(caller, null, scope)
    val t = engine.submit(caller, s.id, TestRuntime.text(text))
    assertEquals(TaskState.COMPLETED, engine.awaitTask(t.id).state)
    return s.id to engine.readEvents(s.id)
}

/** The tool names the model was given in the last session configuration. */
internal fun TestRuntime.offered(): List<String> = core!!.configs.last().tools.map { it.name }

internal fun List<EventEnvelope>.settledErrors() = filter { it.eventType == EventTypes.TOOL_SETTLED }.map { it.error?.code }

internal fun List<EventEnvelope>.consentReasons() =
    filter { it.eventType == EventTypes.CONSENT_RESOLVED }.map { it.payload["reason"]!!.jsonPrimitive.content }

internal fun List<EventEnvelope>.resultTexts() = filter { it.eventType == EventTypes.TOOL_EXECUTION_END }
    .map { it.payload["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content }
