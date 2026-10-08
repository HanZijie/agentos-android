package org.agentos.sample.notes.agentos

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 内置的假网关脚本（debug 包开关 `fake_gateway` 和单元测试都用它）。结果 JSON 的字段与日历 / 闹钟示例 App 的
 * `event_create` / `alarm_create` 真实返回一致（只取我们用到的那几个字段，外加几个无关字段，证明解析不依赖它们）。
 */
object FakeScripts {
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    val names: List<String> = listOf(
        "success", "first_run", "reject", "no_time", "no_model", "auth_timeout", "denied", "disconnect",
        "busy", "rate_limited", "too_large", "failed", "not_installed",
        "no_confirm", "hold_auth", "hold_running", "hold_approval", "slow", "hold_forever",
    )

    fun byName(name: String, now: ZonedDateTime = ZonedDateTime.now()): FakeScript? = when (name) {
        // 日程 + 闹钟都成功
        "success" -> script(prompt = work(now, event = Outcome.CREATED, alarm = Outcome.CREATED))
        // 第一次使用：先等授权，再成功
        "first_run" -> script(connect = listOf(FakeStep.AuthWait, FakeStep.Delay(3_000)), prompt = work(now, Outcome.CREATED, Outcome.CREATED))
        // 用户在 AgentOS 里为这两个工具设了“始终允许”：没有 PENDING_APPROVAL，直接 RUNNING → COMPLETED（闹钟连 RUNNING 都没有，只有结果）
        "no_confirm" -> script(
            prompt = listOf(
                FakeStep.Delay(400),
                text("我找到了两个需要安排的时间：周五下午 3 点的会议，和每周一早上 7 点的跑步。"),
                call("e1", "event_create", ToolStatus.RUNNING, null),
                FakeStep.Delay(500),
                call("e1", "event_create", ToolStatus.COMPLETED, eventResult(now)),
                FakeStep.Delay(300),
                call("a1", "alarm_create", ToolStatus.COMPLETED, alarmResult(now)),
                FakeStep.Delay(300),
                text("处理好了。"),
                done(),
            ),
        )
        // 日程创建了，闹钟被用户拒绝
        "reject" -> script(prompt = work(now, event = Outcome.CREATED, alarm = Outcome.DENIED))
        // 文字里没有明确时间：什么也不建，给出解释
        "no_time" -> script(
            prompt = listOf(
                FakeStep.Delay(500),
                text("我读了这条备忘，里面只有“周末前整理一下资料”这样的说法，没有明确的日期或钟点，所以没有创建日程或闹钟。"),
                done(),
            ),
        )
        "no_model" -> script(prompt = listOf(FakeStep.Delay(400), FakeStep.Fail(AgentOsError.NO_MODEL)))
        "auth_timeout" -> script(connect = listOf(FakeStep.AuthWait, FakeStep.Delay(4_000), FakeStep.Fail(AgentOsError.AUTHORIZATION_PENDING_TIMEOUT)))
        "denied" -> script(connect = listOf(FakeStep.Delay(400), FakeStep.AuthWait, FakeStep.Delay(1_500), FakeStep.Fail(AgentOsError.DENIED)))
        // 日程已经建好，连接断了
        "disconnect" -> script(
            prompt = work(now, event = Outcome.CREATED, alarm = null) + listOf(FakeStep.Delay(600), FakeStep.Fail(AgentOsError.DISCONNECTED)),
        )
        "busy" -> script(prompt = listOf(FakeStep.Delay(300), FakeStep.Fail(AgentOsError.BUSY)))
        "rate_limited" -> script(prompt = listOf(FakeStep.Delay(300), FakeStep.Fail(AgentOsError.RATE_LIMITED)))
        "too_large" -> script(prompt = listOf(FakeStep.Delay(300), FakeStep.Fail(AgentOsError.TOO_LARGE)))
        "failed" -> script(prompt = listOf(FakeStep.Delay(300), FakeStep.Fail(AgentOsError.FAILED, "the agent crashed")))
        "not_installed" -> FakeScript(installed = false)
        // 停在某个状态上，截图用
        "hold_auth" -> script(connect = listOf(FakeStep.Delay(300), FakeStep.AuthWait, FakeStep.Hang))
        "hold_running" -> script(
            prompt = listOf(
                FakeStep.Delay(300),
                text("我找到了两个需要安排的时间：周五下午 3 点的会议，和每周一早上 7 点的跑步。"),
                call("e1", "event_create", ToolStatus.RUNNING, null),
                FakeStep.Hang,
            ),
        )
        "hold_approval" -> script(
            prompt = listOf(
                FakeStep.Delay(300),
                text("我找到了两个需要安排的时间：周五下午 3 点的会议，和每周一早上 7 点的跑步。"),
                call("e1", "event_create", ToolStatus.COMPLETED, eventResult(now)),
                call("a1", "alarm_create", ToolStatus.PENDING_APPROVAL, null),
                FakeStep.Hang,
            ),
        )
        // 很慢的一轮（100 秒才结束）：验证 ask_agent 的 goAsync 撑得住 150 秒；hold_forever 永远不结束：验证 150 秒超时
        "slow" -> script(prompt = listOf(FakeStep.Delay(100_000)) + work(now, Outcome.CREATED, Outcome.CREATED))
        "hold_forever" -> script(prompt = listOf(FakeStep.Delay(300), text("还在想……"), FakeStep.Hang))
        else -> null
    }

    enum class Outcome { CREATED, DENIED, FAILED }

    private fun script(connect: List<FakeStep> = listOf(FakeStep.Delay(400)), prompt: List<FakeStep> = emptyList()) =
        FakeScript(connect = connect, prompt = prompt)

    /** 读文字 → 日程（确认 → 创建 → 结果）→ 闹钟（同样）→ 总结。[event] / [alarm] 为 null 表示这一项不出现。 */
    private fun work(now: ZonedDateTime, event: Outcome?, alarm: Outcome?): List<FakeStep> = buildList {
        add(FakeStep.Delay(500))
        add(text("我找到了两个需要安排的时间：周五下午 3 点的会议，和每周一早上 7 点的跑步。"))
        if (event != null) addAll(toolFlow("e1", "event_create", event, eventResult(now)))
        if (alarm != null) addAll(toolFlow("a1", "alarm_create", alarm, alarmResult(now)))
        add(FakeStep.Delay(400))
        add(text("处理好了。"))
        add(done())
    }

    private fun toolFlow(id: String, tool: String, outcome: Outcome, result: String): List<FakeStep> = buildList {
        add(call(id, tool, ToolStatus.PENDING_APPROVAL, null))
        add(FakeStep.Delay(1_400))
        when (outcome) {
            Outcome.CREATED -> {
                add(call(id, tool, ToolStatus.RUNNING, null))
                add(FakeStep.Delay(500))
                add(call(id, tool, ToolStatus.COMPLETED, result))
            }
            Outcome.DENIED -> add(call(id, tool, ToolStatus.DENIED, "denied by user"))
            Outcome.FAILED -> add(call(id, tool, ToolStatus.FAILED, "{\"error\":\"calendar is read-only\"}"))
        }
        add(FakeStep.Delay(400))
    }

    private fun text(chunk: String) = FakeStep.Emit(GatewayEvent.Text(chunk))
    private fun done() = FakeStep.Emit(GatewayEvent.Done("end_turn"))
    /** 和真实事件一致：带模型传的参数，和 toolScope 里对应的那一项（按原始工具名）。 */
    private fun call(id: String, tool: String, status: ToolStatus, result: String?) =
        FakeStep.Emit(GatewayEvent.ToolCall(id, tool, status, result, argsFor(tool), NotesToolScope.firstOrNull { it.tool == tool }))

    private fun argsFor(tool: String): String? = when (tool) {
        "event_create" -> """{"title":"和王总开会","start":"明天 15:00","location":"3 号会议室","reminder_minutes":[15]}"""
        "alarm_create" -> """{"time":"07:00","label":"跑步","days":["mon"]}"""
        else -> null
    }

    fun eventResult(now: ZonedDateTime): String {
        val start = now.plusDays(1).withHour(15).withMinute(0).withSecond(0).withNano(0)
        return """{"id":"e1","series_id":"e1","calendar_id":"1","calendar_name":"我的日历","title":"和王总开会","start":"${ISO.format(start)}","end":"${ISO.format(start.plusHours(1))}","all_day":false,"location":"3 号会议室","reminder_minutes":[15],"recurrence":"none","timezone":"${now.zone.id}"}"""
    }

    fun alarmResult(now: ZonedDateTime): String {
        val next = now.plusDays(1).withHour(7).withMinute(0).withSecond(0).withNano(0)
        return """{"id":"a1","time":"07:00","label":"跑步","days":["mon"],"repeat":"weekly","enabled":true,"vibrate":true,"snooze_minutes":10,"next_fire_at":"${ISO.format(next)}","ringing":false}"""
    }
}
