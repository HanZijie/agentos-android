package org.agentos.app.settings.callers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpCallersLogicTest {
    private val now = 1_000_000_000L
    private val digest = "7920a1b2c3d4" + "e".repeat(52)

    private fun json(vararg items: String) = "[" + items.joinToString(",") + "]"

    private fun item(pkg: String = "org.example.notes", label: String? = "备忘录", state: String = "allowed", extra: String = "") =
        """{"packageName":"$pkg","label":${label?.let { "\"$it\"" } ?: "null"},"signingDigest":"$digest","state":"$state",
        "lastUsedAt":${now - 5 * 60_000},"usage":{"promptsTotal":12,"promptsLastHour":3,"activeChannels":0,"activeTasks":0},"deniedUntil":null$extra}"""

    @Test
    fun parsesAllowedAndDeniedAndSkipsUnknown() {
        val list = AcpCallers.parse(
            json(item(), item("org.example.calendar", "日历", "denied"), item("x.y", "z", "mystery"), """{"label":"no package","state":"allowed"}""", "5"),
        )
        assertEquals(listOf("org.example.notes", "org.example.calendar"), list.map { it.packageName })
        assertEquals(AcpCallers.State.ALLOWED, list[0].state)
        assertEquals(AcpCallers.State.DENIED, list[1].state)
        assertEquals(12, list[0].promptCount)
        assertEquals(3, list[0].promptsLastHour)
    }

    @Test
    fun malformedInputYieldsNothing() {
        assertTrue(AcpCallers.parse("not json").isEmpty())
        assertTrue(AcpCallers.parse("{}").isEmpty())
        assertTrue(AcpCallers.parse(null).isEmpty())
    }

    @Test
    fun displayNameIsCleanedAndFallsBackToPackage() {
        assertEquals("备忘录", AcpCallers.parse(json(item()))[0].displayName)
        assertEquals("org.example.notes", AcpCallers.parse(json(item(label = null)))[0].displayName)
        val evil = AcpCallers.parse(json(item(label = "\\u202Eevil\\n")))[0]
        assertEquals("evil", evil.displayName)
        assertEquals("org.example.notes", AcpCallers.parse(json(item(label = "org.example.notes")))[0].displayName)
    }

    @Test
    fun lastUsedAndUsageText() {
        val c = AcpCallers.parse(json(item()))[0]
        assertEquals("最近使用：5 分钟前", AcpCallers.lastUsedText(c, now))
        assertEquals("最近使用：刚刚", AcpCallers.lastUsedText(c.copy(lastUsedAt = now - 10_000), now))
        assertEquals("最近使用：3 小时前", AcpCallers.lastUsedText(c.copy(lastUsedAt = now - 3 * 3_600_000L), now))
        assertEquals("最近使用：2 天前", AcpCallers.lastUsedText(c.copy(lastUsedAt = now - 2 * 86_400_000L), now))
        assertEquals("最近使用：从未使用", AcpCallers.lastUsedText(c.copy(lastUsedAt = null), now))
        assertEquals("用量：共 12 次，最近一小时 3 次", AcpCallers.usageText(c))
        assertEquals("用量：共 0 次", AcpCallers.usageText(c.copy(promptCount = 0, promptsLastHour = 0)))
        // a clock that went backwards does not print a negative time
        assertEquals("最近使用：刚刚", AcpCallers.lastUsedText(c.copy(lastUsedAt = now + 99_000), now))
    }

    @Test
    fun stateTextMentionsTheCooldown() {
        val d = AcpCallers.parse(json(item(state = "denied")))[0]
        assertEquals("已拒绝", AcpCallers.stateText(d, now))
        assertEquals("已拒绝（10 分钟内它再请求会直接被拒绝）", AcpCallers.stateText(d.copy(deniedUntil = now + 600_000), now))
        assertEquals("已拒绝", AcpCallers.stateText(d.copy(deniedUntil = now - 1), now))
        assertEquals("已允许", AcpCallers.stateText(AcpCallers.parse(json(item()))[0], now))
    }

    @Test
    fun actionsPerState() {
        val allowed = AcpCallers.parse(json(item()))[0]
        val denied = AcpCallers.parse(json(item(state = "denied")))[0]
        assertEquals(listOf(AcpCallers.Action.REVOKE), AcpCallers.actions(allowed))
        assertEquals(listOf(AcpCallers.Action.ALLOW, AcpCallers.Action.REMOVE), AcpCallers.actions(denied))
        val pending = AcpCallers.parse(json(item(state = "pending", extra = ""","requestId":"r1"""")))[0]
        assertEquals(AcpCallers.State.PENDING, pending.state)
        assertEquals("r1", pending.requestId)
        assertEquals(listOf(AcpCallers.Action.ALLOW, AcpCallers.Action.DENY), AcpCallers.actions(pending))
        assertEquals("denied", AcpCallers.wireState(AcpCallers.Action.DENY))
        assertEquals("允许", AcpCallers.actionLabel(AcpCallers.Action.ALLOW, pending))
        assertEquals("改为允许", AcpCallers.actionLabel(AcpCallers.Action.ALLOW, denied))
        assertEquals("denied", AcpCallers.wireState(AcpCallers.Action.REVOKE))
        assertEquals("allowed", AcpCallers.wireState(AcpCallers.Action.ALLOW))
        assertEquals("removed", AcpCallers.wireState(AcpCallers.Action.REMOVE))
        // only a deliberate action on a denied app ever produces "allowed"
        assertFalse(AcpCallers.actions(allowed).any { AcpCallers.wireState(it) == "allowed" })
    }

    @Test
    fun confirmDialogsNamePackageAndDigestAndConsequence() {
        val c = AcpCallers.parse(json(item(state = "denied")))[0]
        val allow = AcpCallers.confirm(c, AcpCallers.Action.ALLOW)
        assertEquals("允许", allow.confirmLabel)
        assertTrue(allow.message.contains("org.example.notes"))
        assertTrue(allow.message.contains("7920 a1b2 c3d4"))
        assertTrue(allow.message.contains("每次都会再问你"))
        val revoke = AcpCallers.confirm(AcpCallers.parse(json(item()))[0], AcpCallers.Action.REVOKE)
        assertTrue(revoke.message.contains("立即断开"))
        assertTrue(revoke.message.contains("10 分钟内"))
        assertTrue(revoke.message.contains("重新询问"))
        assertEquals("操作失败，请稍后再试", AcpCallers.errorText("agentos.x: secret /data/x"))
        assertNull(AcpCallers.parse(json(item())).firstOrNull { it.packageName == "none" })
    }
}
