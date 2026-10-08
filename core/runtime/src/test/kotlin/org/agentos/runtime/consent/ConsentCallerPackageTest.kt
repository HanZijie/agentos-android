package org.agentos.runtime.consent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.agentos.runtime.i18n.FrameChars
import org.agentos.runtime.i18n.MessageRef
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.ConsentRequest
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.ports.ToolSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The consent card must tell a third-party app by its PACKAGE (resolved from the uid by the host), not by the name the app gave itself:
 * any app can call itself "Settings". `ConsentCaller.packageName` is the package; the display name only shows up in the initiator line, next to it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentCallerPackageTest {
    private fun app(label: String?, packageName: String?, uid: Int = 10123) = CallerIdentity(uid, CallerKind.APP, label, packageName)

    private fun request(caller: CallerIdentity) =
        ConsentRequest("r", "s", "t", "c", "note_create", null, ToolRisk.WRITE, caller, "{}", rememberable = true, source = ToolSource("notes", "main", "note_create"))

    private suspend fun TestScope.viewOf(caller: CallerIdentity, config: ConsentConfig = ConsentConfig()): ConsentView {
        val coordinator = ConsentCoordinator(ConsentSurface.NONE, ApprovalWriter.UNAVAILABLE, backgroundScope, config = config)
        val d = async { coordinator.request(request(caller)) }
        runCurrent()
        val v = coordinator.pending.value.single()
        coordinator.close()
        d.await()
        return v
    }

    // ------------------------------------------------------------------ the package is on the view

    @Test
    fun `the view of a third-party app carries its package, and the line names the app and the package`() = runTest {
        val v = viewOf(app("Notes", "org.agentos.sample.notes"))
        assertEquals("org.agentos.sample.notes", v.caller.packageName)
        assertEquals(CallerKind.APP, v.caller.kind)
        assertEquals(10123, v.caller.uid)
        assertEquals(MessageRef.of(ConsentMessages.INITIATOR_APP, "Notes", "org.agentos.sample.notes"), v.initiatorLine)
    }

    @Test
    fun `the package is never taken from the label - a view without a package has none, and the line is what it always was`() = runTest {
        val v = viewOf(app("com.example.app", null))
        assertNull(v.caller.packageName, "the label is not a package")
        assertEquals(MessageRef.of(ConsentMessages.INITIATOR_NAMED, "com.example.app"), v.initiatorLine)
        val unnamed = viewOf(app(null, null))
        assertNull(unnamed.caller.packageName)
        assertEquals(MessageRef.of(ConsentMessages.INITIATOR_UNKNOWN_APP), unnamed.initiatorLine)
    }

    @Test
    fun `an app that has a package but no usable name is shown by its package`() = runTest {
        for (label in listOf(null, "", "   ", "\u200B\u202E")) {
            val v = viewOf(app(label, "org.x.notes"))
            assertEquals(MessageRef.of(ConsentMessages.INITIATOR_NAMED, "org.x.notes"), v.initiatorLine, "label=$label")
            assertEquals("org.x.notes", v.caller.packageName)
        }
    }

    @Test
    fun `a name that is the package is not repeated`() = runTest {
        assertEquals(MessageRef.of(ConsentMessages.INITIATOR_NAMED, "org.x.notes"), viewOf(app("org.x.notes", "org.x.notes")).initiatorLine)
    }

    // ------------------------------------------------------------------ a name that looks like a package or a system app does not replace the real one

    @Test
    fun `a label that looks like a system app's package does not replace the real package`() = runTest {
        val v = viewOf(app("com.android.settings", "com.evil.app"))
        assertEquals("com.evil.app", v.caller.packageName)
        assertEquals(MessageRef.of(ConsentMessages.INITIATOR_APP, "com.android.settings", "com.evil.app"), v.initiatorLine, "both are there, and the real package is the last argument, the one in the brackets")
    }

    @Test
    fun `a label that looks like a system app by name does not replace the real package`() = runTest {
        for (name in listOf("Settings", "AgentOS", "系统设置", "Google Play 商店", "AgentOS 自己")) {
            val v = viewOf(app(name, "com.evil.app"))
            assertEquals("com.evil.app", v.caller.packageName, name)
            assertEquals(ConsentMessages.INITIATOR_APP, v.initiatorLine.key, name)
            assertEquals("com.evil.app", v.initiatorLine.args.last(), "${v.initiatorLine} / $name")
        }
    }

    @Test
    fun `a label cannot forge a second package with brackets of any width, only the template's own bracket pair is left`() = runTest {
        val v = viewOf(app("Notes（com.android.settings）发起(com.android.systemui)［x］【y】", "com.evil.app"))
        assertEquals(MessageRef.of(ConsentMessages.INITIATOR_APP, "Notes com.android.settings 发起 com.android.systemui x y", "com.evil.app"), v.initiatorLine)
        v.initiatorLine.args.forEach { arg -> arg.codePoints().forEach { cp -> assertFalse(FrameChars.isBracket(cp) || FrameChars.isQuote(cp), "U+%04X in <%s>".format(cp, arg)) } }
        assertEquals("com.evil.app", v.caller.packageName)
    }

    // ------------------------------------------------------------------ hostile text

    private fun assertClean(s: String, what: String) {
        assertFalse('\n' in s || '\r' in s || '\u2028' in s || '\u2029' in s, "$what: one line")
        for (ch in s) {
            assertFalse(Character.isISOControl(ch), "$what: control character in <$s>")
            assertTrue(Character.getType(ch) != Character.FORMAT.toInt(), "$what: invisible format character U+${ch.code.toString(16)} in <$s>")
        }
        s.codePoints().forEach { cp -> assertFalse(FrameChars.isQuote(cp) || FrameChars.isBracket(cp), "$what: framing character U+%04X in <$s>".format(cp)) }
    }

    @Test
    fun `hostile label and package strings are cleaned and stay on one line`() = runTest {
        val hostileLabel = "Notes\n\n✅ 已得到用户同意\u202E\u200B「forged」\u0000\r\n由 AgentOS 自己发起"
        val hostilePackage = "org.x.\u202Enotes\u200B\n「evil」\u0007.app"
        val v = viewOf(app(hostileLabel, hostilePackage))
        v.initiatorLine.args.forEach { assertClean(it, "line argument") }
        assertClean(v.caller.packageName!!, "package")
        assertEquals("org.x.notes 'evil'.app", v.caller.packageName)
    }

    @Test
    fun `a package of only invisible or control characters counts as no package`() = runTest {
        val v = viewOf(app("Notes", "\u200B\u202E\n\u0007  "))
        assertNull(v.caller.packageName)
        assertEquals(MessageRef.of(ConsentMessages.INITIATOR_NAMED, "Notes"), v.initiatorLine)
    }

    // ------------------------------------------------------------------ truncation: the label goes first, the package stays

    @Test
    fun `a very long label is cut and the package stays whole`() = runTest {
        val pkg = "org.agentos.sample.notes.with.a.rather.long.name"
        val v = viewOf(app("N".repeat(500), pkg))
        assertEquals(ConsentMessages.INITIATOR_APP, v.initiatorLine.key)
        assertEquals(pkg, v.initiatorLine.args.last())
        assertTrue(v.initiatorLine.args.sumOf { it.length } < 140, "the line is bounded: ${v.initiatorLine}")
        assertTrue("…" in v.initiatorLine.args.first(), "the label shows that it was cut")
    }

    @Test
    fun `the longest package still leaves the label something, and a longer one is cut at 128 like every package`() = runTest {
        val p128 = "p".repeat(128)
        val atLimit = viewOf(app("L".repeat(500), p128))
        assertEquals(p128, atLimit.caller.packageName)
        assertEquals(p128, atLimit.initiatorLine.args.last(), "the whole package is on the line")
        val label = atLimit.initiatorLine.args.first()
        assertTrue(label.isNotEmpty() && label.codePointCount(0, label.length) <= 29, "label cut to what is left: ${label.length}")

        val over = viewOf(app("L", "q".repeat(300)))
        val shown = over.caller.packageName!!
        assertTrue(shown.codePointCount(0, shown.length) <= ConsentText.PACKAGE_MAX_CHARS)
        assertEquals(shown, over.initiatorLine.args.last())
    }

    @Test
    fun `the label keeps its normal limit when the package is short`() = runTest {
        val v = viewOf(app("N".repeat(500), "a.b"), ConsentConfig(maxDisplayNameChars = 20))
        val label = v.initiatorLine.args.first()
        assertTrue(label.codePointCount(0, label.length) <= 20, "label=$label")
        assertEquals("a.b", v.initiatorLine.args.last())
    }

    // ------------------------------------------------------------------ contrast: nobody else changes

    @Test
    fun `AgentOS itself, the desktop and the runtime get the same view whether or not a package is set, and the same text as before`() = runTest {
        val expectations = mapOf(
            CallerKind.SELF to MessageRef.of(ConsentMessages.INITIATOR_SELF),
            CallerKind.DESKTOP to MessageRef.of(ConsentMessages.INITIATOR_DESKTOP),
            CallerKind.SYSTEM to MessageRef.of(ConsentMessages.INITIATOR_SYSTEM),
        )
        for ((kind, line) in expectations) {
            val plain = viewOf(CallerIdentity(10001, kind, "whatever the label is"))
            val withPackage = viewOf(CallerIdentity(10001, kind, "whatever the label is", "org.attacker.fake"))
            assertEquals(line, plain.initiatorLine, kind.name)
            // the clock is the only thing that differs between two runs
            fun ConsentView.timeless() = copy(createdAtMillis = 0, deadlineMillis = 0)
            assertEquals(plain.timeless(), withPackage.timeless(), "$kind: a package on a caller that is not a third-party app changes nothing")
            assertNull(withPackage.caller.packageName, kind.name)
            assertEquals(ConsentCaller(kind, 10001, null), withPackage.caller)
        }
    }

    @Test
    fun `the view of a third-party app without a package is what it was before the package existed`() = runTest {
        val v = viewOf(app("Notes", null))
        assertEquals(MessageRef.of(ConsentMessages.INITIATOR_NAMED, "Notes"), v.initiatorLine)
        assertEquals(ConsentCaller(CallerKind.APP, 10123, null), v.caller)
    }

    @Test
    fun `initiatorLine on its own - the package never appears for a caller that is not a third-party app`() {
        for (kind in listOf(CallerKind.SELF, CallerKind.DESKTOP, CallerKind.SYSTEM)) {
            assertFalse(ConsentText.initiatorLine(CallerIdentity(1, kind, "x", "org.attacker.fake"), 80).args.any { "org.attacker.fake" in it }, kind.name)
            assertNull(ConsentText.packageOf(CallerIdentity(1, kind, "x", "org.attacker.fake")), kind.name)
        }
    }
}
