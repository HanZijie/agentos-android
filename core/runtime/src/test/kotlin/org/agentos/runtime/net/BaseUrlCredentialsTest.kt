package org.agentos.runtime.net

import kotlinx.coroutines.runBlocking
import org.agentos.runtime.ports.Credential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BaseUrlCredentialsTest {
    private val minimax = Credential("sk-minimax-test")
    private val deepseek = Credential("sk-deepseek-test")
    private val creds = BaseUrlCredentials(
        listOf(
            BaseUrlCredentials.Entry("https://api.minimax.io/anthropic", minimax),
            BaseUrlCredentials.Entry("https://api.deepseek.com", deepseek),
        ),
    )

    private fun key(url: String): Credential? = runBlocking { creds.credentialFor(url) }

    @Test
    fun `key is used for the base URL and paths below it`() {
        assertSame(minimax, key("https://api.minimax.io/anthropic/v1/messages"))
        assertSame(minimax, key("https://api.minimax.io/anthropic"))
        assertSame(minimax, key("https://API.MINIMAX.IO:443/anthropic/v1/messages?beta=true"))
        assertSame(deepseek, key("https://api.deepseek.com/chat/completions"))
    }

    @Test
    fun `key never goes to a look-alike host, another scheme or port, or a sibling path`() {
        assertNull(key("https://api.minimax.io.evil.com/anthropic/v1/messages"))
        assertNull(key("https://evil.com/api.minimax.io/anthropic/v1/messages"))
        assertNull(key("http://api.minimax.io/anthropic/v1/messages"))
        assertNull(key("https://api.minimax.io:8443/anthropic/v1/messages"))
        assertNull(key("https://api.minimax.io/anthropicx/v1/messages"))
        assertNull(key("https://api.minimax.io/v1/messages"))
        assertNull(key("https://user:pass@api.minimax.io/anthropic/v1/messages"))
        assertNull(key("https://api.minimax.io/anthropic/../openai/v1/chat"))
        assertNull(key("not a url"))
    }

    @Test
    fun `longest base path wins`() {
        val general = Credential("general")
        val specific = Credential("specific")
        val c = BaseUrlCredentials(
            listOf(
                BaseUrlCredentials.Entry("https://gateway.example.com/", general),
                BaseUrlCredentials.Entry("https://gateway.example.com/team-a/v1/", specific),
            ),
        )
        runBlocking {
            assertSame(specific, c.credentialFor("https://gateway.example.com/team-a/v1/chat/completions"))
            assertSame(general, c.credentialFor("https://gateway.example.com/team-b/v1/chat/completions"))
        }
    }

    @Test
    fun `key supplier is read per request, so a changed key applies to the next request`() = runBlocking<Unit> {
        var current = Credential("old")
        val c = BaseUrlCredentials(listOf(BaseUrlCredentials.Entry("https://api.example.com/v1") { current }))
        assertEquals("old", c.credentialFor("https://api.example.com/v1/chat/completions")!!.reveal())
        current = Credential("new")
        assertEquals("new", c.credentialFor("https://api.example.com/v1/chat/completions")!!.reveal())
    }

    @Test
    fun `credential never shows in strings`() {
        val k = Credential("sk-very-secret-value")
        assertFalse("secret" in k.toString())
        assertFalse("secret" in "$k")
        assertFalse("secret" in listOf(k).toString())
        assertTrue(k.toString().contains("****"))
    }

    @Test
    fun `invalid base URLs are rejected`() {
        assertFailsWith<IllegalArgumentException> { BaseUrlCredentials.Entry("api.minimax.io/anthropic", minimax) }
        assertFailsWith<IllegalArgumentException> { BaseUrlCredentials.Entry("ftp://api.minimax.io/", minimax) }
    }
}
