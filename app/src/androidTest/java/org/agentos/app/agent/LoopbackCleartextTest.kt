package org.agentos.app.agent

import android.security.NetworkSecurityPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.agentos.runtime.errors.ErrorCode
import org.agentos.runtime.net.HostFetch
import org.agentos.runtime.pi.testing.FakeModelServer
import org.agentos.runtime.ports.AgentSessionConfig
import org.agentos.runtime.ports.ModelSpec
import org.agentos.runtime.ports.TurnInput
import org.agentos.runtime.ports.TurnOutcome
import org.agentos.runtime.testing.FakeHostPort
import org.agentos.runtime.testing.RecordingTurnHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Architecture F9: custom model endpoints may use cleartext http only on loopback (127.0.0.1,
 * localhost, ::1), for model servers running on the device. Two layers enforce it: HostFetch
 * refuses other http URLs before sending, and the app's network security config
 * (res/xml/network_security_config.xml) lets the platform allow cleartext to those hosts only.
 */
class LoopbackCleartextTest {

    private val catalog by lazy { PiAgentCores.loadCatalog(Device.context) }

    @Test
    fun platformAllowsCleartextOnlyToTheLoopbackHosts() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertFalse(policy.isCleartextTrafficPermitted, "cleartext stays off by default")
        for (host in HostFetch.LOOPBACK_HOSTS) {
            assertTrue(policy.isCleartextTrafficPermitted(host), host)
        }
        for (host in listOf("10.0.2.2", "192.168.1.20", "127.0.0.2", "example.com", "localhost.example.com", "api.minimaxi.com")) {
            assertFalse(policy.isCleartextTrafficPermitted(host), host)
        }
    }

    private suspend fun oneTurn(model: ModelSpec, credentials: Map<String, String>): Pair<TurnOutcome, RecordingTurnHost> {
        val core = PiAgentCores.create(Device.context, FakeHostPort(credentials = credentials)).create()
        try {
            return withTimeout(30_000) {
                core.start()
                val session = core.openSession("loopback", AgentSessionConfig(model, systemPrompt = "x", tools = emptyList()))
                val turnHost = RecordingTurnHost()
                session.runTurn(TurnInput("hello"), turnHost) to turnHost
            }
        } finally {
            core.close()
        }
    }

    @Test
    fun piTurnsOverCleartextToEachLoopbackHost() = runBlocking<Unit> {
        for (host in listOf("127.0.0.1", "localhost", "::1")) {
            FakeModelServer(host = host).use { fake ->
                val model = catalog.customModel("openai-completions", "fake-chat", fake.openaiBaseUrl).toModelSpec()
                val (outcome, turnHost) = oneTurn(model, mapOf(fake.openaiBaseUrl to fake.key))
                assertIs<TurnOutcome.Finished>(outcome, "$host (${fake.openaiBaseUrl}): $outcome")
                assertTrue(turnHost.streamedText().startsWith("echo:hello"), "$host: ${turnHost.streamedText()}")
                assertEquals(listOf("real"), fake.requests.map { it.presentedKeyKind }, host)
            }
        }
    }

    @Test
    fun cleartextToOtherHostsIsRefusedBeforeSending() = runBlocking<Unit> {
        for (url in listOf("http://10.0.2.2:9/v1", "http://192.168.1.20:11434/v1")) {
            // First layer: the catalog does not build such a model (settings validation).
            assertFailsWith<IllegalArgumentException>(url) { catalog.customModel("openai-completions", "lan-model", url) }
            // Second layer: a model object carrying such a baseUrl anyway (stale or hand-edited
            // config) is refused by HostFetch before anything is sent, even with a key configured.
            val https = catalog.customModel("openai-completions", "lan-model", "https://gateway.example.com/v1").json
            val model = ModelSpec(JsonObject(https + ("baseUrl" to JsonPrimitive(url))))
            val (outcome, _) = oneTurn(model, mapOf(url to "k-not-used"))
            assertIs<TurnOutcome.Failed>(outcome, url)
            assertEquals(ErrorCode.MODEL_NOT_CONFIGURED, outcome.error.code, "$url: ${outcome.error.message}")
            assertTrue(outcome.error.message.startsWith("endpoint not allowed"), outcome.error.message)
        }
    }
}
