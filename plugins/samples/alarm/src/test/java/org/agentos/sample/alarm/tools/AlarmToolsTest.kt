package org.agentos.sample.alarm.tools

import java.time.OffsetDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.sample.alarm.data.Alarm
import org.agentos.sample.alarm.data.TestEnv
import org.agentos.sample.alarm.data.at
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 假的“响铃状态”：dismiss / snooze 只记录调用。 */
class FakeRing : RingControl {
    val state = MutableStateFlow<Alarm?>(null)
    override val ringing: StateFlow<Alarm?> = state
    var dismissed = 0
    var snoozed = 0

    override fun dismiss(): Alarm? = state.value?.also { dismissed++; state.value = null }

    override fun snooze(): Alarm? = state.value?.also { snoozed++; state.value = null }
}

class AlarmToolsTest {
    private val env = TestEnv()
    private val ring = FakeRing()
    private val tools = AlarmTools(env.repository, ring)

    private fun call(name: String, args: JsonObject = buildJsonObject {}): ToolOutput =
        runBlocking { tools.find(name)!!.handler(args) }

    private fun ok(name: String, args: JsonObject = buildJsonObject {}): JsonObject {
        val out = call(name, args)
        assertFalse("$name failed: ${out.text}", out.isError)
        val parsed = Json.parseToJsonElement(out.text).jsonObject
        assertEquals("structuredContent must equal the text JSON", parsed, out.structured)
        return parsed
    }

    private fun fail(name: String, args: JsonObject = buildJsonObject {}): String {
        val out = call(name, args)
        assertTrue("$name should fail but returned: ${out.text}", out.isError)
        assertTrue("error must be one short sentence: ${out.text}", out.text.isNotBlank() && !out.text.contains('\n'))
        return out.text
    }

    private fun create(time: String = "07:30", vararg extra: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        ok("alarm_create", buildJsonObject { put("time", time); extra.forEach { (k, v) -> put(k, v) } })

    // ---- 契约：名字、必填参数、注解 ----

    @Test
    fun contractNamesAndRequiredParameters() {
        val expected = mapOf(
            "alarm_list" to emptyList(),
            "alarm_get" to listOf("id"),
            "alarm_create" to listOf("time"),
            "alarm_update" to listOf("id"),
            "alarm_set_enabled" to listOf("id", "enabled"),
            "alarm_delete" to listOf("id"),
            "alarm_next" to emptyList(),
            "alarm_dismiss" to emptyList(),
        )
        for ((name, required) in expected) {
            val tool = tools.find(name)
            assertNotNull("missing tool $name", tool)
            val schema = tool!!.inputSchema
            assertEquals("object", schema["type"]!!.jsonPrimitive.content)
            assertEquals(required, schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertTrue("$name description must be English prose", tool.description.length > 30 && tool.description.all { it.code < 128 })
            assertTrue(Regex("^[a-z]+(_[a-z]+)+$").matches(name))
        }
    }

    @Test
    fun annotationsAreAccurate() {
        fun a(name: String) = tools.find(name)!!.annotations
        for (readOnly in listOf("alarm_list", "alarm_get", "alarm_next")) assertEquals(true, a(readOnly).readOnlyHint)
        assertEquals(true, a("alarm_delete").destructiveHint)
        assertEquals(true, a("alarm_update").idempotentHint)
        assertEquals(true, a("alarm_set_enabled").idempotentHint)
        for (writer in listOf("alarm_create", "alarm_update", "alarm_set_enabled", "alarm_delete", "alarm_dismiss")) {
            assertNull("$writer must not claim readOnly", a(writer).readOnlyHint)
        }
        assertNull(a("alarm_create").destructiveHint)
        assertNull(a("alarm_set_enabled").destructiveHint)
    }

    // ---- alarm_create ----

    @Test
    fun createReturnsAlarmWithIsoNextFireAtAndSchedules() {
        val a = create("07:30", "label" to JsonPrimitive("Wake up"), "days" to buildJsonArray { add(JsonPrimitive("mon")); add(JsonPrimitive("fri")) })
        assertEquals("07:30", a["time"]!!.jsonPrimitive.content)
        assertEquals("Wake up", a["label"]!!.jsonPrimitive.content)
        assertEquals(listOf("mon", "fri"), a["days"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(a["enabled"]!!.jsonPrimitive.boolean)
        // 2026-10-07（周三）08:00 之后，下一个周五是 10-09
        val nextFireAt = a["next_fire_at"]!!.jsonPrimitive.content
        assertEquals("2026-10-09T07:30:00+08:00", nextFireAt)
        assertEquals(at(7, 30, day = 9).toInstant(), OffsetDateTime.parse(nextFireAt).toInstant())
        assertEquals(env.millis(at(7, 30, day = 9)), env.scheduler.scheduled[a["id"]!!.jsonPrimitive.content])
        assertEquals(1, env.repository.alarms.value.size)
    }

    @Test
    fun createDefaultsAreSensible() {
        val a = create("06:05")
        assertEquals("", a["label"]!!.jsonPrimitive.content)
        assertEquals(0, a["days"]!!.jsonArray.size)
        assertEquals("once", a["repeat"]!!.jsonPrimitive.content)
        assertTrue(a["vibrate"]!!.jsonPrimitive.boolean)
        assertEquals(10, a["snooze_minutes"]!!.jsonPrimitive.int)
        assertEquals("2026-10-08T06:05:00+08:00", a["next_fire_at"]!!.jsonPrimitive.content)
    }

    @Test
    fun createDisabledHasNullNextFireAndNoSystemAlarm() {
        val a = create("06:05", "enabled" to JsonPrimitive(false))
        assertEquals(JsonNull, a["next_fire_at"])
        assertTrue(env.scheduler.scheduled.isEmpty())
    }

    @Test
    fun createAcceptsSingleDigitHourAndUppercaseDays() {
        val a = create("7:05", "days" to buildJsonArray { add(JsonPrimitive("SAT")); add(JsonPrimitive("Sun")) })
        assertEquals("07:05", a["time"]!!.jsonPrimitive.content)
        assertEquals(listOf("sat", "sun"), a["days"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("weekends", a["repeat"]!!.jsonPrimitive.content)
    }

    @Test
    fun createMissingTimeFails() {
        assertTrue(fail("alarm_create").contains("time"))
        assertTrue(env.repository.alarms.value.isEmpty())
    }

    @Test
    fun createRejectsInvalidValues() {
        for (bad in listOf("25:00", "12:60", "7", "7:5", "noon", "", "07:30:00", "-1:00")) {
            fail("alarm_create", buildJsonObject { put("time", bad) })
        }
        fail("alarm_create", buildJsonObject { put("time", JsonPrimitive(730)) })
        fail("alarm_create", buildJsonObject { put("time", "07:30"); put("days", "mon") })
        assertTrue(fail("alarm_create", buildJsonObject { put("time", "07:30"); put("days", buildJsonArray { add(JsonPrimitive("funday")) }) }).contains("funday"))
        fail("alarm_create", buildJsonObject { put("time", "07:30"); put("days", buildJsonArray { add(JsonPrimitive(1)) }) })
        fail("alarm_create", buildJsonObject { put("time", "07:30"); put("enabled", "yes") })
        fail("alarm_create", buildJsonObject { put("time", "07:30"); put("snooze_minutes", 0) })
        fail("alarm_create", buildJsonObject { put("time", "07:30"); put("snooze_minutes", 99) })
        fail("alarm_create", buildJsonObject { put("time", "07:30"); put("snooze_minutes", 2.5) })
        fail("alarm_create", buildJsonObject { put("time", "07:30"); put("label", "x".repeat(200)) })
        assertTrue(env.repository.alarms.value.isEmpty())
    }

    // ---- alarm_get / alarm_list ----

    @Test
    fun getReturnsTheAlarmAndFailsForUnknownOrMissingId() {
        val id = create("09:00")["id"]!!.jsonPrimitive.content
        assertEquals("09:00", ok("alarm_get", buildJsonObject { put("id", id) })["time"]!!.jsonPrimitive.content)
        assertTrue(fail("alarm_get", buildJsonObject { put("id", "does-not-exist") }).contains("does-not-exist"))
        assertTrue(fail("alarm_get").contains("id"))
        fail("alarm_get", buildJsonObject { put("id", "") })
    }

    @Test
    fun listIsOrderedByNextFireAndFiltersEnabled() {
        val morning = create("06:00", "label" to JsonPrimitive("morning"))["id"]!!.jsonPrimitive.content
        val night = create("22:00")["id"]!!.jsonPrimitive.content
        val off = create("09:00", "enabled" to JsonPrimitive(false))["id"]!!.jsonPrimitive.content

        val all = ok("alarm_list")
        assertEquals(3, all["count"]!!.jsonPrimitive.int)
        assertEquals(listOf(night, morning, off), all["alarms"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })

        val onlyOn = ok("alarm_list", buildJsonObject { put("enabled_only", true) })
        assertEquals(listOf(night, morning), onlyOn["alarms"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        fail("alarm_list", buildJsonObject { put("enabled_only", "maybe") })
    }

    @Test
    fun listOnEmptyReturnsEmptyArray() {
        val r = ok("alarm_list")
        assertEquals(0, r["count"]!!.jsonPrimitive.int)
        assertEquals(JsonArray(emptyList()), r["alarms"])
    }

    // ---- alarm_update ----

    @Test
    fun updateChangesOnlyGivenFieldsAndReschedules() {
        val a = create("07:00", "label" to JsonPrimitive("Gym"))
        val id = a["id"]!!.jsonPrimitive.content
        val u = ok("alarm_update", buildJsonObject { put("id", id); put("time", "18:45"); put("vibrate", false) })
        assertEquals("18:45", u["time"]!!.jsonPrimitive.content)
        assertEquals("Gym", u["label"]!!.jsonPrimitive.content)
        assertFalse(u["vibrate"]!!.jsonPrimitive.boolean)
        assertEquals(env.millis(at(18, 45)), env.scheduler.scheduled[id])
        assertEquals("2026-10-07T18:45:00+08:00", u["next_fire_at"]!!.jsonPrimitive.content)
    }

    @Test
    fun updateDaysEmptyListMakesItOneShotAndLabelEmptyClears() {
        val id = create("07:00", "label" to JsonPrimitive("x"), "days" to buildJsonArray { add(JsonPrimitive("mon")) })["id"]!!.jsonPrimitive.content
        val u = ok("alarm_update", buildJsonObject { put("id", id); put("days", buildJsonArray {}); put("label", "") })
        assertEquals(0, u["days"]!!.jsonArray.size)
        assertEquals("", u["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun updateFailures() {
        val id = create("07:00")["id"]!!.jsonPrimitive.content
        assertTrue(fail("alarm_update", buildJsonObject { put("id", id) }).contains("Nothing to update"))
        fail("alarm_update", buildJsonObject { put("id", "nope"); put("time", "08:00") })
        fail("alarm_update", buildJsonObject { put("time", "08:00") })
        fail("alarm_update", buildJsonObject { put("id", id); put("time", "24:00") })
        fail("alarm_update", buildJsonObject { put("id", id); put("snooze_minutes", 100) })
        assertEquals(7, env.repository.get(id)!!.hour)
    }

    // ---- alarm_set_enabled ----

    @Test
    fun setEnabledSwitchesAndSchedulesOrCancels() {
        val id = create("07:00")["id"]!!.jsonPrimitive.content
        val off = ok("alarm_set_enabled", buildJsonObject { put("id", id); put("enabled", false) })
        assertFalse(off["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, off["next_fire_at"])
        assertFalse(env.scheduler.scheduled.containsKey(id))

        val on = ok("alarm_set_enabled", buildJsonObject { put("id", id); put("enabled", true) })
        assertTrue(on["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("2026-10-08T07:00:00+08:00", on["next_fire_at"]!!.jsonPrimitive.content)
        assertTrue(env.scheduler.scheduled.containsKey(id))
    }

    @Test
    fun setEnabledIsIdempotent() {
        val id = create("07:00")["id"]!!.jsonPrimitive.content
        val first = ok("alarm_set_enabled", buildJsonObject { put("id", id); put("enabled", true) })
        val second = ok("alarm_set_enabled", buildJsonObject { put("id", id); put("enabled", true) })
        assertEquals(first["next_fire_at"], second["next_fire_at"])
        assertEquals(1, env.repository.alarms.value.size)
    }

    @Test
    fun setEnabledFailures() {
        val id = create("07:00")["id"]!!.jsonPrimitive.content
        assertTrue(fail("alarm_set_enabled", buildJsonObject { put("id", id) }).contains("enabled"))
        fail("alarm_set_enabled", buildJsonObject { put("enabled", true) })
        fail("alarm_set_enabled", buildJsonObject { put("id", "nope"); put("enabled", true) })
        fail("alarm_set_enabled", buildJsonObject { put("id", id); put("enabled", "true") })
    }

    // ---- alarm_delete ----

    @Test
    fun deleteRemovesAndCancelsSystemAlarm() {
        val id = create("07:00")["id"]!!.jsonPrimitive.content
        val r = ok("alarm_delete", buildJsonObject { put("id", id) })
        assertTrue(r["deleted"]!!.jsonPrimitive.boolean)
        assertEquals(id, r["alarm"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(env.repository.alarms.value.isEmpty())
        assertFalse(env.scheduler.scheduled.containsKey(id))
        assertTrue(id in env.scheduler.cancelled)
        // 再删一次：不存在
        assertTrue(fail("alarm_delete", buildJsonObject { put("id", id) }).contains(id))
    }

    @Test
    fun deleteFailures() {
        fail("alarm_delete")
        fail("alarm_delete", buildJsonObject { put("id", "nope") })
    }

    // ---- alarm_next ----

    @Test
    fun nextIsJsonNullWhenNothingIsOn() {
        val out = call("alarm_next")
        assertFalse(out.isError)
        assertEquals("null", out.text)
        assertNull(out.structured)
        create("07:00", "enabled" to JsonPrimitive(false))
        assertEquals("null", call("alarm_next").text)
    }

    @Test
    fun nextReturnsEarliestWithMinutesUntil() {
        create("06:00")
        val tonight = create("22:00", "label" to JsonPrimitive("tonight"))
        val r = ok("alarm_next")
        assertEquals(tonight["id"], r["alarm"]!!.jsonObject["id"])
        assertEquals("2026-10-07T22:00:00+08:00", r["next_fire_at"]!!.jsonPrimitive.content)
        assertEquals(14 * 60, r["fires_in_minutes"]!!.jsonPrimitive.int)
    }

    // ---- alarm_dismiss ----

    @Test
    fun dismissFailsWhenNothingIsRinging() {
        assertTrue(fail("alarm_dismiss").contains("No alarm is ringing"))
        assertTrue(fail("alarm_dismiss", buildJsonObject { put("id", "1") }).contains("No alarm is ringing"))
        assertEquals(0, ring.dismissed)
    }

    @Test
    fun dismissStopsTheRingingAlarm() {
        val id = create("08:00")["id"]!!.jsonPrimitive.content
        env.time.current = at(8, 0)
        ring.state.value = env.repository.onFired(id)
        val r = ok("alarm_dismiss")
        assertTrue(r["dismissed"]!!.jsonPrimitive.boolean)
        assertEquals(1, ring.dismissed)
        assertNull(ring.ringing.value)
    }

    @Test
    fun dismissWithMatchingIdWorksAndWrongIdDoesNot() {
        val id = create("08:00")["id"]!!.jsonPrimitive.content
        env.time.current = at(8, 0)
        ring.state.value = env.repository.onFired(id)
        assertTrue(fail("alarm_dismiss", buildJsonObject { put("id", "other") }).contains("other"))
        assertEquals(0, ring.dismissed)
        ok("alarm_dismiss", buildJsonObject { put("id", id) })
        assertEquals(1, ring.dismissed)
    }

    @Test
    fun snoozeToolRequiresRingingAlarm() {
        assertTrue(fail("alarm_snooze").contains("No alarm is ringing"))
        val id = create("08:00")["id"]!!.jsonPrimitive.content
        env.time.current = at(8, 0)
        ring.state.value = env.repository.onFired(id)
        assertTrue(ok("alarm_snooze")["snoozed"]!!.jsonPrimitive.boolean)
        assertEquals(1, ring.snoozed)
    }

    // ---- 其它 ----

    @Test
    fun handlerNeverThrowsEvenForGarbageArguments() {
        for (tool in tools.tools) {
            val out = runBlocking { tool.handler(buildJsonObject { put("id", JsonPrimitive(null as String?)); put("time", buildJsonArray {}) }) }
            // 要么正常（无必填参数的工具），要么 isError；绝不抛异常
            assertTrue(tool.name, out.isError || tool.inputSchema["required"]!!.jsonArray.isEmpty())
        }
    }
}
