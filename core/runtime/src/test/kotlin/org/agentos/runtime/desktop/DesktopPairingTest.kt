package org.agentos.runtime.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W9：电脑端接入的开关、一次性配对码和令牌。 */
class DesktopPairingTest {
    private var now = 1_000_000L
    private val store = MemoryPairingStore()
    private fun pairing(s: PairingStore = store) = DesktopPairing(s, wallClock = { now }, monotonicClock = { now })

    private fun DesktopPairing.pair(): PairingResult.Accepted {
        val code = newCode()
        return assertIs<PairingResult.Accepted>(pairWithCode(code.code, "laptop"))
    }

    @Test
    fun `off by default, and no code can be issued while off`() {
        val p = pairing()
        assertFalse(p.enabled)
        assertFailsWith<IllegalStateException> { p.newCode() }
        assertEquals(PairingResult.Rejected(PairingFailure.DISABLED), p.pairWithCode("000000", null))
        assertEquals(PairingResult.Rejected(PairingFailure.DISABLED), p.verifyToken("x".repeat(43)))
    }

    @Test
    fun `a code is six digits, works once and returns a token once`() {
        val p = pairing().apply { setEnabled(true) }
        val code = p.newCode()
        assertTrue(Regex("^\\d{6}$").matches(code.code))
        assertFalse(code.toString().contains(code.code), "toString does not leak the code")
        val ok = assertIs<PairingResult.Accepted>(p.pairWithCode(code.code, "laptop"))
        val token = assertNotNull(ok.token)
        assertEquals(43, token.length)
        assertEquals("laptop", ok.pairing.label)
        assertNull(p.activeCode(), "used up")
        assertEquals(PairingResult.Rejected(PairingFailure.INVALID_CODE), p.pairWithCode(code.code, "again"))
        // 令牌重连：不再发令牌
        val again = assertIs<PairingResult.Accepted>(p.verifyToken(token))
        assertEquals(ok.pairing.id, again.pairing.id)
        assertNull(again.token)
        assertEquals(PairingResult.Rejected(PairingFailure.INVALID_TOKEN), p.verifyToken(token.reversed()))
        assertEquals(PairingResult.Rejected(PairingFailure.INVALID_TOKEN), p.verifyToken("short"))
    }

    @Test
    fun `a code expires`() {
        val p = pairing().apply { setEnabled(true) }
        val code = p.newCode(ttlMillis = 60_000)
        assertNotNull(p.codeStatus())
        now += 60_000
        assertNull(p.codeStatus())
        assertEquals(PairingResult.Rejected(PairingFailure.INVALID_CODE), p.pairWithCode(code.code, null))
        val code2 = p.newCode(ttlMillis = 60_000)
        now += 60_000
        assertEquals(PairingResult.Rejected(PairingFailure.CODE_EXPIRED), p.pairWithCode(code2.code, null))
    }

    @Test
    fun `five wrong attempts void the code`() {
        val p = pairing().apply { setEnabled(true) }
        val code = p.newCode()
        val wrong = ((code.code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        repeat(4) { assertEquals(PairingResult.Rejected(PairingFailure.INVALID_CODE), p.pairWithCode(wrong, null)) }
        assertEquals(1, p.codeStatus()!!.attemptsLeft)
        assertEquals(PairingResult.Rejected(PairingFailure.TOO_MANY_ATTEMPTS), p.pairWithCode(wrong, null))
        assertEquals(PairingResult.Rejected(PairingFailure.INVALID_CODE), p.pairWithCode(code.code, null), "the right code no longer works")
    }

    @Test
    fun `spaces and dashes in the typed code are ignored, a new code replaces the old one`() {
        val p = pairing().apply { setEnabled(true) }
        val old = p.newCode()
        val code = p.newCode()
        if (old.code != code.code) assertEquals(PairingResult.Rejected(PairingFailure.INVALID_CODE), p.pairWithCode(old.code, null))
        val typed = code.code.substring(0, 3) + " - " + code.code.substring(3)
        assertIs<PairingResult.Accepted>(p.pairWithCode(typed, null))
    }

    @Test
    fun `turning the switch off voids the code and every pairing`() {
        val p = pairing().apply { setEnabled(true) }
        val token = p.pair().token!!
        p.newCode()
        p.setEnabled(false)
        p.setEnabled(true)
        assertNull(p.activeCode())
        assertTrue(p.pairings().isEmpty())
        assertEquals(PairingResult.Rejected(PairingFailure.INVALID_TOKEN), p.verifyToken(token))
    }

    @Test
    fun `revoke removes one pairing`() {
        val p = pairing().apply { setEnabled(true) }
        val a = p.pair()
        val b = p.pair()
        assertTrue(p.revoke(a.pairing.id))
        assertFalse(p.revoke(a.pairing.id))
        assertEquals(PairingResult.Rejected(PairingFailure.INVALID_TOKEN), p.verifyToken(a.token!!))
        assertIs<PairingResult.Accepted>(p.verifyToken(b.token!!))
        assertEquals(1, p.revokeAll())
        assertTrue(p.pairings().isEmpty())
    }

    @Test
    fun `the oldest pairing is dropped beyond the limit`() {
        val p = DesktopPairing(store, PairingConfig(maxPairings = 2), wallClock = { now }, monotonicClock = { now })
        p.setEnabled(true)
        val first = p.pair()
        now += 1
        val second = p.pair()
        now += 1
        p.pair()
        assertEquals(2, p.pairings().size)
        assertEquals(PairingResult.Rejected(PairingFailure.INVALID_TOKEN), p.verifyToken(first.token!!))
        assertIs<PairingResult.Accepted>(p.verifyToken(second.token!!))
    }

    @Test
    fun `labels from the client are sanitized`() {
        assertEquals("desktop", DesktopPairing.sanitizeLabel(null))
        assertEquals("desktop", DesktopPairing.sanitizeLabel(" \u0000\n "))
        assertEquals("ab", DesktopPairing.sanitizeLabel("a\u001b[31mb".replace("[31m", "")))
        assertEquals(64, DesktopPairing.sanitizeLabel("x".repeat(200)).length)
    }

    @Test
    fun `the switch and pairings survive a restart, the code does not, and only token hashes are stored`() {
        val file = Files.createTempDirectory("pairing").resolve("desktop/pairing.json").toFile()
        val p1 = pairing(FilePairingStore(file)).apply { setEnabled(true) }
        val token = p1.pair().token!!
        p1.newCode()
        val text = file.readText()
        assertFalse(text.contains(token), "the token itself is not stored")
        assertTrue(text.contains(DesktopPairing.sha256(token)))

        val p2 = pairing(FilePairingStore(file))
        assertTrue(p2.enabled)
        assertNull(p2.activeCode())
        assertIs<PairingResult.Accepted>(p2.verifyToken(token))

        file.writeText("{corrupt")
        assertFalse(pairing(FilePairingStore(file)).enabled, "an unreadable file falls back to off")
    }
}
