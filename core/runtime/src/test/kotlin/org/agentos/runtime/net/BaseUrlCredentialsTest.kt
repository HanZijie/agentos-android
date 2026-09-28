package org.agentos.runtime.net

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BaseUrlCredentialsTest {
    private val minimax = ApiKey("sk-minimax-test")
    private val deepseek = ApiKey("sk-deepseek-test")
    private val creds = BaseUrlCredentials(
        listOf(
            BaseUrlCredentials.Entry("https://api.minimax.io/anthropic", minimax),
            BaseUrlCredentials.Entry("https://api.deepseek.com", deepseek),
        ),
    )

    private fun key(url: String): ApiKey? = creds.apiKeyFor(URI(url))

    @Test
    fun `key is used for the base URL and paths below it`() {
        assertEquals(minimax, key("https://api.minimax.io/anthropic/v1/messages"))
        assertEquals(minimax, key("https://api.minimax.io/anthropic"))
        assertEquals(minimax, key("https://API.MINIMAX.IO:443/anthropic/v1/messages?beta=true"))
        assertEquals(deepseek, key("https://api.deepseek.com/chat/completions"))
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
    }

    @Test
    fun `longest base path wins`() {
        val general = ApiKey("general")
        val specific = ApiKey("specific")
        val c = BaseUrlCredentials(
            listOf(
                BaseUrlCredentials.Entry("https://gateway.example.com/", general),
                BaseUrlCredentials.Entry("https://gateway.example.com/team-a/v1/", specific),
            ),
        )
        assertEquals(specific, c.apiKeyFor(URI("https://gateway.example.com/team-a/v1/chat/completions")))
        assertEquals(general, c.apiKeyFor(URI("https://gateway.example.com/team-b/v1/chat/completions")))
    }

    @Test
    fun `key supplier is read per request, so a changed key applies to the next request`() {
        var current = ApiKey("old")
        val c = BaseUrlCredentials(listOf(BaseUrlCredentials.Entry("https://api.example.com/v1") { current }))
        assertEquals(ApiKey("old"), c.apiKeyFor(URI("https://api.example.com/v1/chat/completions")))
        current = ApiKey("new")
        assertEquals(ApiKey("new"), c.apiKeyFor(URI("https://api.example.com/v1/chat/completions")))
    }

    @Test
    fun `api key never shows in strings`() {
        val k = ApiKey("sk-very-secret-value")
        assertFalse("secret" in k.toString())
        assertFalse("secret" in "$k")
        assertFalse("secret" in listOf(k).toString())
        assertTrue(k.toString().contains("****"))
    }

    @Test
    fun `invalid keys and base URLs are rejected`() {
        assertFailsWith<IllegalArgumentException> { ApiKey(" ") }
        assertFailsWith<IllegalArgumentException> { ApiKey("abc\r\nx-injected: 1") }
        assertFailsWith<IllegalArgumentException> { BaseUrlCredentials.Entry("api.minimax.io/anthropic", minimax) }
        assertFailsWith<IllegalArgumentException> { BaseUrlCredentials.Entry("ftp://api.minimax.io/", minimax) }
    }
}
