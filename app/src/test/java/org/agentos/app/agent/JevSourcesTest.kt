package org.agentos.app.agent

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.agentos.runtime.ports.Credential
import org.agentos.runtime.ports.RuntimeLog
import org.agentos.runtime.router.JevChoice
import org.agentos.runtime.router.JevException
import org.agentos.runtime.router.JevRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Jev 的 endpoint 和 key（D5.1）：第二个凭据位、持久化、清除立即作废、HTTP 提供者。 */
class JevSourcesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val modelUrl = "https://api.minimaxi.com/anthropic/v1/messages"
    private val jevUrl = JevSources.DEFAULT_ENDPOINT
    private val modelKey = "sk-model-0123456789abcdef"
    private val jevKey = "jev-0123456789abcdefwxyz"

    private class Rig(dir: File, val cipher: SoftwareCipher = SoftwareCipher()) {
        val secrets = KeystoreSecrets(SoftwareCipher())
        val jev = JevSources(File(dir, "jev"), cipher, secrets, RuntimeLog.NONE)
    }

    private fun rig(cipher: SoftwareCipher = SoftwareCipher()) = Rig(tmp.root, cipher)

    @Test
    fun jevKeyIsOnlyHandedToTheJevEndpoint() = runBlocking {
        val r = rig()
        r.secrets.activate(modelKey, listOf("https://api.minimaxi.com/anthropic"))
        assertNull("not configured yet", r.secrets.credentialFor(jevUrl))
        r.jev.set(null, jevKey)
        assertEquals(jevKey, r.secrets.credentialFor(jevUrl)!!.reveal())
        assertEquals("the model key still goes to the model endpoint", modelKey, r.secrets.credentialFor(modelUrl)!!.reveal())
        assertNull("the Jev key is never offered to another URL", r.secrets.credentialFor("https://evil.example/v1/systemone"))
        assertNull("the model key is never offered to the Jev endpoint", run {
            val only = KeystoreSecrets(SoftwareCipher()).also { it.activate(modelKey, listOf("https://api.minimaxi.com/anthropic")) }
            only.credentialFor(jevUrl)
        })
        assertEquals("jev-…wxyz", r.jev.get()["keyMasked"]!!.jsonPrimitive.content)
    }

    @Test
    fun savedKeyIsEncryptedOnDiskAndRestoredAfterRestart() = runBlocking {
        val cipher = SoftwareCipher()
        val r = rig(cipher)
        r.jev.set("", jevKey)
        val text = File(tmp.root, "jev/${JevSources.FILE_NAME}").readText()
        assertFalse("no key in the file", text.contains(jevKey) || text.contains("0123456789abcdef"))
        // a new process: same files, same master key
        val again = Rig(tmp.root, cipher)
        assertTrue(again.jev.usable())
        assertEquals(jevKey, again.secrets.credentialFor(jevUrl)!!.reveal())
        assertEquals(jevUrl, again.jev.endpoint())
    }

    @Test
    fun keyIsBoundToItsEndpoint() = runBlocking {
        val r = rig()
        r.jev.set("http://127.0.0.1:18788/v1/systemone", jevKey)
        assertEquals(jevKey, r.secrets.credentialFor("http://127.0.0.1:18788/v1/systemone")!!.reveal())
        assertNull(r.secrets.credentialFor(jevUrl))
        // changing the endpoint without a new key is refused; the same endpoint keeps the key
        try {
            r.jev.set(jevUrl, null)
            fail()
        } catch (e: JevSourceException) {
            assertEquals("agentos.jev.key_required: an API key is required for this endpoint", e.message)
        }
        r.jev.set("http://127.0.0.1:18788/v1/systemone", null)
        assertTrue(r.jev.usable())
    }

    @Test
    fun endpointRulesAndErrorsNeverEchoInput() {
        val r = rig()
        for (bad in listOf("http://jev.example.com/v1", "https://u:p@jev.example.com/", "ftp://x/y", "https://x/y?k=sk-secret", "not a url")) {
            try {
                r.jev.set(bad, jevKey)
                fail(bad)
            } catch (e: JevSourceException) {
                assertEquals("invalid_endpoint", e.code)
                assertFalse(e.message!!.contains("sk-secret") || e.message!!.contains(jevKey) || e.message!!.contains("jev.example"))
            }
        }
        try {
            r.jev.set(null, "has space")
            fail()
        } catch (e: JevSourceException) {
            assertEquals("invalid_key", e.code)
        }
        assertFalse(r.jev.usable())
    }

    @Test
    fun clearingRevokesAtOnceAndAbortsInflightRequests() = runBlocking {
        val cipher = SoftwareCipher()
        val r = rig(cipher)
        r.jev.set(null, jevKey)
        val credential = r.secrets.credentialFor(jevUrl)!!
        val revoked = mutableListOf<Credential>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { r.secrets.revocations.collect { revoked += it } }
        r.jev.clear()
        yield()
        assertNull(r.secrets.credentialFor(jevUrl))
        assertEquals("the revocation signal carries the very Credential that was handed out", 1, revoked.size)
        assertTrue(revoked[0] === credential)
        assertFalse(File(tmp.root, "jev/${JevSources.FILE_NAME}").exists())
        assertEquals(1, cipher.destroyed)
        assertFalse(r.jev.get()["configured"]!!.jsonPrimitive.boolean)
        job.cancel()
        // clearing the Jev key does not touch the model key
        val r2 = rig()
        r2.secrets.activate(modelKey, listOf("https://api.minimaxi.com/anthropic"))
        r2.jev.set(null, jevKey)
        r2.jev.clear()
        assertEquals(modelKey, r2.secrets.credentialFor(modelUrl)!!.reveal())
    }

    @Test
    fun redactCoversTheJevKey() {
        val r = rig()
        r.jev.set(null, jevKey)
        assertEquals("x **** y", r.secrets.redact("x $jevKey y"))
    }

    @Test
    fun lostMasterKeyIsReportedNotThrown() {
        val cipher = SoftwareCipher()
        rig(cipher).jev.set(null, jevKey)
        val lost = Rig(tmp.root, SoftwareCipher()) // another device: no master key
        val st = lost.jev.get()
        assertFalse(st["usable"]!!.jsonPrimitive.boolean)
        assertEquals("key_unreadable", st["problems"]!!.jsonArray[0].jsonPrimitive.content)
    }

    // ---- ConfiguredJevProvider + HttpJevProvider on a mock server

    private val request = JevRequest("hotels in Kyoto", listOf(JevChoice("ses_1", "First query: Kyoto"), JevChoice("new_session", "Start a new Session")))

    @Test
    fun providerUsesTheSavedKeyAndEndpointAndReportsUnconfigured() = runBlocking {
        val r = rig()
        val provider = ConfiguredJevProvider(r.jev, r.secrets)
        try {
            provider.choose(request)
            fail()
        } catch (e: JevException) {
            assertEquals("jev_unconfigured", e.reason)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"answers":{"session":{"choice":"ses_1"}}}"""))
            server.start()
            r.jev.set(server.url("/v1/systemone").toString(), jevKey)
            assertEquals("ses_1", provider.choose(request))
            val got = server.takeRequest()
            assertEquals("Bearer $jevKey", got.getHeader("Authorization"))
            assertFalse("the key is not in the body", got.body.readUtf8().contains(jevKey))
        }
    }

    @Test
    fun providerMapsHttpFailures() = runBlocking {
        val r = rig()
        MockWebServer().use { server ->
            server.start()
            r.jev.set(server.url("/v1/systemone").toString(), jevKey)
            val provider = ConfiguredJevProvider(r.jev, r.secrets, timeoutMillis = 400)
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Invalid API key."}"""))
            assertEquals("jev_http_error", reason(provider))
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals("jev_http_retryable", reason(provider))
            server.enqueue(MockResponse().setBody("<html>not json</html>"))
            assertEquals("jev_invalid_response", reason(provider))
            server.enqueue(MockResponse().setBody("""{"answers":{"session":{}}}"""))
            assertEquals("jev_invalid_response", reason(provider))
            server.enqueue(MockResponse().setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS).setBody("{}"))
            assertEquals("jev_timeout", reason(provider))
        }
        assertNotNull(r)
    }

    private suspend fun reason(p: ConfiguredJevProvider): String =
        try {
            p.choose(request)
            "no error"
        } catch (e: JevException) {
            e.reason
        }
}
