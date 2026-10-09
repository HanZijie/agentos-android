package org.agentos.app.settings.callers

import org.agentos.app.R
import org.agentos.app.i18n.ResStrings
import org.agentos.app.i18n.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** “已授权的应用”页的纯逻辑；文案来自 [Strings]（中、英各一个实现，读真实的 `strings_p3.xml`）。 */
class AcpCallersLogicTest {
    private val zh = ResStrings.zh
    private val en = ResStrings.en
    private val both: List<Strings> = listOf(zh, en)
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
        // quotes of every language are gone from the name: the confirmation frames it with quotes
        assertEquals("a'b", AcpCallers.parse(json(item(label = "a'b")))[0].displayName)
        val quoted = AcpCallers.parse(json(item(label = "x\\u201D and \\u201Cy\\u300D\\u300Cz")))[0].displayName
        for (c in listOf('“', '”', '「', '」')) assertFalse(quoted.contains(c))
    }

    @Test
    fun lastUsedAndUsageText() {
        val c = AcpCallers.parse(json(item()))[0]
        for (s in both) {
            assertEquals(s.get(R.string.callers_last_used, s.plural(R.plurals.callers_ago_minutes, 5, 5)), AcpCallers.lastUsedText(c, now, s))
            assertEquals(s.get(R.string.callers_last_used, s.get(R.string.callers_ago_just_now)), AcpCallers.lastUsedText(c.copy(lastUsedAt = now - 10_000), now, s))
            assertEquals(s.get(R.string.callers_last_used, s.plural(R.plurals.callers_ago_hours, 3, 3)), AcpCallers.lastUsedText(c.copy(lastUsedAt = now - 3 * 3_600_000L), now, s))
            assertEquals(s.get(R.string.callers_last_used, s.plural(R.plurals.callers_ago_days, 2, 2)), AcpCallers.lastUsedText(c.copy(lastUsedAt = now - 2 * 86_400_000L), now, s))
            assertEquals(s.get(R.string.callers_last_used_never), AcpCallers.lastUsedText(c.copy(lastUsedAt = null), now, s))
            // a clock that went backwards does not print a negative time
            assertEquals(s.get(R.string.callers_last_used, s.get(R.string.callers_ago_just_now)), AcpCallers.lastUsedText(c.copy(lastUsedAt = now + 99_000), now, s))
        }
        assertEquals("最近使用：5 分钟前", AcpCallers.lastUsedText(c, now, zh))
        assertEquals("最近使用：2 天前", AcpCallers.lastUsedText(c.copy(lastUsedAt = now - 2 * 86_400_000L), now, zh))
        assertEquals("用量：共 12 次，最近一小时 3 次", AcpCallers.usageText(c, zh))
        assertEquals("用量：共 0 次", AcpCallers.usageText(c.copy(promptCount = 0, promptsLastHour = 0), zh))
        assertEquals("Last used: 5 minutes ago", AcpCallers.lastUsedText(c, now, en))
        assertEquals("Last used: 1 minute ago", AcpCallers.lastUsedText(c.copy(lastUsedAt = now - 60_000), now, en))
        assertEquals("Last used: 1 hour ago", AcpCallers.lastUsedText(c.copy(lastUsedAt = now - 3_600_000L), now, en))
        assertEquals("Last used: 3 hours ago", AcpCallers.lastUsedText(c.copy(lastUsedAt = now - 3 * 3_600_000L), now, en))
        assertEquals("Last used: 1 day ago", AcpCallers.lastUsedText(c.copy(lastUsedAt = now - 86_400_000L), now, en))
        assertEquals("Last used: 2 days ago", AcpCallers.lastUsedText(c.copy(lastUsedAt = now - 2 * 86_400_000L), now, en))
        assertEquals("Last used: just now", AcpCallers.lastUsedText(c.copy(lastUsedAt = now - 10_000), now, en))
        assertEquals("Last used: never", AcpCallers.lastUsedText(c.copy(lastUsedAt = null), now, en))
        assertEquals("Usage: 12 prompts in total, 3 in the last hour", AcpCallers.usageText(c, en))
        assertEquals("Usage: 1 prompt in total, 1 in the last hour", AcpCallers.usageText(c.copy(promptCount = 1, promptsLastHour = 1), en))
        assertEquals("Usage: 0 prompts in total", AcpCallers.usageText(c.copy(promptCount = 0, promptsLastHour = 0), en))
    }

    @Test
    fun stateTextMentionsTheCooldown() {
        val d = AcpCallers.parse(json(item(state = "denied")))[0]
        for (s in both) {
            assertEquals(s.get(R.string.callers_state_denied), AcpCallers.stateText(d, now, s))
            assertEquals(s.plural(R.plurals.callers_state_denied_cooldown, 10, 10), AcpCallers.stateText(d.copy(deniedUntil = now + 600_000), now, s))
            assertEquals(s.get(R.string.callers_state_denied), AcpCallers.stateText(d.copy(deniedUntil = now - 1), now, s))
            assertEquals(s.get(R.string.callers_state_allowed), AcpCallers.stateText(AcpCallers.parse(json(item()))[0], now, s))
        }
        assertEquals("已拒绝（10 分钟内它再请求会直接被拒绝）", AcpCallers.stateText(d.copy(deniedUntil = now + 600_000), now, zh))
        assertEquals("Denied (if it asks again within 10 minutes, it will be refused right away)", AcpCallers.stateText(d.copy(deniedUntil = now + 600_000), now, en))
        // a bit over zero rounds up to one minute: the singular form
        assertEquals("Denied (if it asks again within 1 minute, it will be refused right away)", AcpCallers.stateText(d.copy(deniedUntil = now + 1_000), now, en))
        assertEquals("Allowed", AcpCallers.stateText(AcpCallers.parse(json(item()))[0], now, en))
        assertEquals("Denied", AcpCallers.stateText(d, now, en))
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
        for (s in both) {
            assertEquals(s.get(R.string.callers_action_allow), AcpCallers.actionLabel(AcpCallers.Action.ALLOW, pending, s))
            assertEquals(s.get(R.string.callers_action_allow_denied), AcpCallers.actionLabel(AcpCallers.Action.ALLOW, denied, s))
        }
        assertEquals("Revoke access", AcpCallers.actionLabel(AcpCallers.Action.REVOKE, allowed, en))
        assertEquals("Allow", AcpCallers.actionLabel(AcpCallers.Action.ALLOW, pending, en))
        assertEquals("Change to Allow", AcpCallers.actionLabel(AcpCallers.Action.ALLOW, denied, en))
        assertEquals("Deny", AcpCallers.actionLabel(AcpCallers.Action.DENY, pending, en))
        assertEquals("Remove record", AcpCallers.actionLabel(AcpCallers.Action.REMOVE, denied, en))
        assertEquals("撤销授权", AcpCallers.actionLabel(AcpCallers.Action.REVOKE, allowed, zh))
        assertEquals("denied", AcpCallers.wireState(AcpCallers.Action.REVOKE))
        assertEquals("allowed", AcpCallers.wireState(AcpCallers.Action.ALLOW))
        assertEquals("removed", AcpCallers.wireState(AcpCallers.Action.REMOVE))
        // only a deliberate action on a denied app ever produces "allowed"
        assertFalse(AcpCallers.actions(allowed).any { AcpCallers.wireState(it) == "allowed" })
    }

    @Test
    fun confirmDialogsNamePackageAndDigestAndConsequence() {
        val c = AcpCallers.parse(json(item(state = "denied")))[0]
        val allowed = AcpCallers.parse(json(item()))[0]
        for (s in both) {
            val allow = AcpCallers.confirm(c, AcpCallers.Action.ALLOW, s)
            assertEquals(s.get(R.string.callers_allow_confirm), allow.confirmLabel)
            assertTrue(allow.message.contains("org.example.notes"))
            assertTrue(allow.message.contains("7920 a1b2 c3d4"))
            assertTrue("the explanation that tools still ask every time", allow.message.contains(s.get(R.string.callers_allow_explanation)))
            val revoke = AcpCallers.confirm(allowed, AcpCallers.Action.REVOKE, s)
            assertEquals(s.get(R.string.callers_revoke_confirm), revoke.confirmLabel)
            assertTrue(revoke.message.contains("org.example.notes"))
            assertEquals(s.get(R.string.callers_deny_confirm), AcpCallers.confirm(c, AcpCallers.Action.DENY, s).confirmLabel)
            assertEquals(s.get(R.string.callers_remove_confirm), AcpCallers.confirm(c, AcpCallers.Action.REMOVE, s).confirmLabel)
            assertEquals(s.get(R.string.callers_error), AcpCallers.errorText(s))
        }
        // the consequences are all still there in each language
        val allowZh = AcpCallers.confirm(c, AcpCallers.Action.ALLOW, zh)
        assertTrue(allowZh.message.contains("每次都会再问你"))
        val revokeZh = AcpCallers.confirm(allowed, AcpCallers.Action.REVOKE, zh)
        assertTrue(revokeZh.message.contains("立即断开"))
        assertTrue(revokeZh.message.contains("10 分钟内"))
        assertTrue(revokeZh.message.contains("重新询问"))
        val revokeEn = AcpCallers.confirm(allowed, AcpCallers.Action.REVOKE, en)
        assertEquals("Revoke access?", revokeEn.title)
        assertEquals("Revoke", revokeEn.confirmLabel)
        assertTrue(revokeEn.message.contains("closed immediately"))
        assertTrue(revokeEn.message.contains("tasks in progress will be cancelled"))
        assertTrue(revokeEn.message.contains("within 10 minutes it will be refused right away"))
        assertTrue(revokeEn.message.contains("you will be asked again"))
        val allowEn = AcpCallers.confirm(c, AcpCallers.Action.ALLOW, en)
        assertEquals("Change to Allow?", allowEn.title)
        assertTrue(allowEn.message.contains("any tool it uses still asks you every time"))
        assertTrue(allowEn.message.contains("Allow only if you are sure this app comes from a source you trust."))
        assertTrue(allowEn.message.contains("Signature: 7920 a1b2 c3d4…"))
        assertTrue(allowEn.message.contains("Package: org.example.notes"))
        assertEquals("Action failed. Please try again later.", AcpCallers.errorText(en))
        assertEquals("操作失败，请稍后再试", AcpCallers.errorText(zh))
        assertNull(AcpCallers.parse(json(item())).firstOrNull { it.packageName == "none" })
    }

    @Test
    fun aPackageNameWithOddCharactersIsHiddenAndSaidSo() {
        for (s in both) {
            assertEquals(s.get(R.string.callers_package_line, "org.example.notes"), AcpCallers.packageLine("org.example.notes", s))
            assertEquals(s.get(R.string.callers_package_line, s.get(R.string.callers_package_hidden, "org.evilx")), AcpCallers.packageLine("org.ev\u202Eil(x)", s))
            assertEquals(s.get(R.string.callers_package_line, s.get(R.string.callers_package_hidden, s.get(R.string.callers_package_empty))), AcpCallers.packageLine("", s))
            assertEquals(s.get(R.string.callers_digest_line, s.get(R.string.callers_digest_unreadable)), AcpCallers.digestLine("", s))
        }
        assertEquals("Package: org.evilx (the package name has invalid characters, hidden)", AcpCallers.packageLine("org.ev\u202Eil(x)", en))
        assertEquals("Signature: (unreadable)", AcpCallers.digestLine(null, en))
        assertEquals("org.evilx", AcpCallers.safePackage("org.ev\u202Eil(x)"))
    }
}
