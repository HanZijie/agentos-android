package org.agentos.app.agent

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.agentos.runtime.net.FetchRequest
import org.agentos.runtime.net.HostFetch
import org.agentos.runtime.net.HostFetchException
import org.agentos.runtime.net.NetErrorKind
import org.agentos.runtime.net.PLACEHOLDER_API_KEY
import org.agentos.runtime.pi.ModelCatalog
import org.agentos.runtime.ports.Credential
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
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 电脑上的 [SecretCipher]：软件 AES-256-GCM，格式与 AndroidKeystoreCipher 相同。 */
class SoftwareCipher : SecretCipher {
    private var key: SecretKey? = null
    var destroyed = 0
        private set

    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val k = key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(SealedBlob.TAG_BITS, iv))
        c.updateAAD(aad)
        return SealedBlob.encode(iv, c.doFinal(plain))
    }

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
        val (iv, ct) = SealedBlob.decode(sealed)
        val k = key ?: throw GeneralSecurityException("master key missing")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(SealedBlob.TAG_BITS, iv))
        c.updateAAD(aad)
        return c.doFinal(ct)
    }

    override fun destroy() {
        key = null
        destroyed++
    }

    override fun status(): Map<String, Any?> = mapOf("present" to (key != null))
}

/** 与 core/pi-runtime/build.mjs 生成的 model-catalog.json 同形状的小目录。 */
val TEST_CATALOG = """
{
  "schemaVersion": 1,
  "generatedFrom": { "@earendil-works/pi-ai": "0.86.1" },
  "apis": ["anthropic-messages", "openai-completions"],
  "customTemplates": {
    "anthropic-messages": { "api": "anthropic-messages", "provider": "custom", "reasoning": false, "input": ["text", "image"],
      "cost": { "input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0 }, "contextWindow": 128000, "maxTokens": 8192 },
    "openai-completions": { "api": "openai-completions", "provider": "custom", "reasoning": false, "input": ["text", "image"],
      "cost": { "input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0 }, "contextWindow": 128000, "maxTokens": 8192 }
  },
  "providers": [
    { "id": "minimax", "name": "MiniMax", "apis": ["anthropic-messages"], "baseUrls": ["https://api.minimax.io/anthropic"],
      "auth": { "type": "api-key", "label": "MiniMax API key" },
      "models": [
        { "id": "MiniMax-M2.7", "name": "MiniMax-M2.7", "api": "anthropic-messages", "provider": "minimax",
          "baseUrl": "https://api.minimax.io/anthropic", "reasoning": true, "input": ["text"], "contextWindow": 204800, "maxTokens": 131072 }
      ] },
    { "id": "minimax-cn", "name": "MiniMax CN", "apis": ["anthropic-messages"], "baseUrls": ["https://api.minimaxi.com/anthropic"],
      "auth": { "type": "api-key", "label": "MiniMax CN API key" },
      "models": [
        { "id": "MiniMax-M2.7", "name": "MiniMax-M2.7", "api": "anthropic-messages", "provider": "minimax-cn",
          "baseUrl": "https://api.minimaxi.com/anthropic", "reasoning": true, "input": ["text"], "contextWindow": 204800, "maxTokens": 131072 },
        { "id": "MiniMax-M2.5", "name": "MiniMax-M2.5", "api": "anthropic-messages", "provider": "minimax-cn",
          "baseUrl": "https://api.minimaxi.com/anthropic", "reasoning": true, "input": ["text"], "contextWindow": 204800, "maxTokens": 131072 }
      ] },
    { "id": "fireworks", "name": "Fireworks", "apis": ["anthropic-messages", "openai-completions"],
      "baseUrls": ["https://api.fireworks.ai/inference", "https://api.fireworks.ai/inference/v1"],
      "auth": { "type": "api-key", "label": "Fireworks API key" },
      "models": [
        { "id": "fw-o", "name": "FW O", "api": "openai-completions", "provider": "fireworks",
          "baseUrl": "https://api.fireworks.ai/inference/v1", "reasoning": false, "input": ["text"], "contextWindow": 131072, "maxTokens": 16384 }
      ] }
  ]
}
""".trimIndent()

class KeystoreSecretsTest {
    private val key = "sk-test-0123456789abcdefWXYZ"
    private val cn = "https://api.minimaxi.com/anthropic"

    @Test
    fun sealIsBoundToEndpoints() {
        val s = KeystoreSecrets(SoftwareCipher())
        val sealed = s.seal(key, listOf(cn))
        assertEquals(key, s.unseal(sealed))
        assertFalse(sealed.blob.contains(key))
        // 同一份密文挪给另一个端点：GCM 认证失败
        expectSecurity { s.unseal(SealedKey(listOf("https://evil.example.com"), sealed.blob)) }
        // 改一个字节
        val bytes = java.util.Base64.getDecoder().decode(sealed.blob)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        expectSecurity { s.unseal(SealedKey(listOf(cn), java.util.Base64.getEncoder().encodeToString(bytes))) }
        expectSecurity { s.unseal(SealedKey(listOf(cn), "not base64 !!")) }
    }

    @Test
    fun matchesByParsedEndpoint() = runBlocking {
        val s = KeystoreSecrets(SoftwareCipher())
        s.activate(key, listOf(cn))
        assertTrue(s.resolves("$cn/v1/messages"))
        assertEquals(key, s.credentialFor("$cn/v1/messages")?.reveal())
        assertEquals(key, s.credentialFor("HTTPS://API.MINIMAXI.COM:443/anthropic/v1/messages")?.reveal())
        for (bad in listOf(
            "https://api.minimaxi.com.evil.com/anthropic/v1/messages",
            "http://api.minimaxi.com/anthropic/v1/messages",
            "https://api.minimaxi.com/anthropicx/v1/messages",
            "https://api.minimaxi.com:8443/anthropic/v1/messages",
            "https://api.minimaxi.com/other",
            "https://user@api.minimaxi.com/anthropic/v1/messages",
            "not a url",
        )) {
            assertNull(bad, s.credentialFor(bad))
            assertFalse(bad, s.resolves(bad))
        }
    }

    @Test
    fun replacedKeyStaysUsableUntilIdle() = runBlocking {
        var idle = false
        val s = KeystoreSecrets(SoftwareCipher()) { idle }
        val a = "https://a.example.com/v1"
        val b = "https://b.example.com/v1"
        s.activate("key-aaaaaaaa", listOf(a))
        s.activate("key-bbbbbbbb", listOf(b))
        // 进行中的任务还在用 a 的 key
        assertEquals("key-aaaaaaaa", s.credentialFor("$a/chat/completions")?.reveal())
        assertEquals("key-bbbbbbbb", s.credentialFor("$b/chat/completions")?.reveal())
        assertTrue(s.holdsRetiredKey)
        // 同一端点换 key：新 key 优先
        s.activate("key-cccccccc", listOf(b))
        assertEquals("key-cccccccc", s.credentialFor("$b/chat/completions")?.reveal())
        s.retire()
        assertNull(s.credentialFor("$a/chat/completions"))
        assertFalse(s.holdsRetiredKey)
        // 空闲时换下来的 key 立即丢弃
        idle = true
        s.activate("key-dddddddd", listOf(a))
        assertNull(s.credentialFor("$b/chat/completions"))
        s.activate(null, emptyList())
        assertNull(s.credentialFor("$a/chat/completions"))
        assertFalse(s.isSet)
    }

    @Test
    fun keyNeverInToStringAndRedacted() {
        val s = KeystoreSecrets(SoftwareCipher())
        s.activate(key, listOf(cn))
        assertEquals("sk-t…WXYZ", s.masked())
        val sealed = s.seal(key, listOf(cn))
        for (text in listOf(s.toString(), sealed.toString(), runBlocking { s.credentialFor("$cn/v1/messages") }.toString())) {
            assertFalse(text, text.contains(key))
        }
        assertEquals("auth failed for **** at x", s.redact("auth failed for $key at x"))
        s.activate("short", listOf(cn))
        assertEquals("****", s.masked())
        assertEquals("a short line", s.redact("a short line"))
    }

    /** 清除 = 立即作废：当前的和换下来的 key 一起丢掉，不等空闲；之后谁都拿不到。 */
    @Test
    fun revokeDropsEverythingImmediately() = runBlocking {
        val s = KeystoreSecrets(SoftwareCipher()) { false } // 一直有任务在跑
        val a = "https://a.example.com/v1"
        val b = "https://b.example.com/v1"
        s.activate("key-aaaaaaaa", listOf(a))
        s.activate("key-bbbbbbbb", listOf(b))
        assertEquals("key-aaaaaaaa", s.credentialFor("$a/chat/completions")?.reveal()) // 更换：进行中的这一轮还能用旧 key
        s.revoke()
        assertNull(s.credentialFor("$a/chat/completions"))
        assertNull(s.credentialFor("$b/chat/completions"))
        assertFalse(s.isSet)
        assertFalse(s.holdsRetiredKey)
        assertNull(s.masked())
        assertEquals(1L, s.stats()["revocations"])
        assertEquals(2L, s.stats()["denied"])
        // 没有 key 时再撤销不计数
        s.revoke()
        assertEquals(1L, s.stats()["revocations"])
    }

    /** F9 第二层：清除时在 revocations 上发出当前的和换下来的 Credential（就是交出去的那两个对象）；更换不发。 */
    @Test
    fun revokeSignalsWithdrawnCredentialsByIdentity() = runBlocking {
        val s = KeystoreSecrets(SoftwareCipher()) { false }
        val a = "https://a.example.com/v1"
        val b = "https://b.example.com/v1"
        val got = CopyOnWriteArrayList<Credential>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { s.revocations.collect { got += it } }
        assertEquals(1L, s.stats()["subscribers"])
        s.activate("key-aaaaaaaa", listOf(a))
        val ca = s.credentialFor("$a/chat/completions")!!
        s.activate("key-bbbbbbbb", listOf(b)) // 更换：热加载，不发信号
        val cb = s.credentialFor("$b/chat/completions")!!
        yield()
        assertTrue("replacing a key sends no revocation", got.isEmpty())

        s.revoke()
        withTimeout(5_000) { while (got.size < 2) yield() }
        assertEquals(2, got.size)
        assertTrue(got.any { it === ca })
        assertTrue(got.any { it === cb })
        assertTrue("an equal-content Credential is a different key", got.none { it === Credential("key-aaaaaaaa") })
        assertEquals(2L, s.stats()["signals"])
        assertEquals(0L, s.stats()["signalsDropped"])

        // 没有 key 时再清除：不发
        s.revoke()
        yield()
        assertEquals(2, got.size)
        job.cancel()
    }

    /** 没有订阅者（运行时还没建 HostFetch）时清除也不出错，信号不积压。 */
    @Test
    fun revokeWithoutSubscribers() {
        val s = KeystoreSecrets(SoftwareCipher())
        s.activate("key-aaaaaaaa", listOf("https://a.example.com/v1"))
        s.revoke()
        assertEquals(0L, s.stats()["subscribers"])
        assertEquals(1L, s.stats()["signals"])
        assertEquals(0L, s.stats()["signalsDropped"])
    }

    /**
     * 端到端（F9 第二层）：真实的 HostFetch 构造时订阅 KeystoreSecrets；清除时正在读的流式响应以 KEY_REVOKED 中止，
     * 之后的请求拿不到 key（第一层）。
     */
    @Test
    fun revokeCutsOffInFlightHostFetch() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val base = "http://127.0.0.1:${server.port}/anthropic"
            val s = KeystoreSecrets(SoftwareCipher()) { false }
            s.activate(key, listOf(base))
            val fetch = HostFetch(s)
            assertEquals(1L, s.stats()["subscribers"])
            val line = "data: {\"n\":0000}\n\n"
            server.enqueue(
                MockResponse().setHeader("content-type", "text/event-stream")
                    .setBody(line.repeat(200)).throttleBody(line.length.toLong(), 50, TimeUnit.MILLISECONDS),
            )
            fun post() = FetchRequest("$base/v1/messages", "POST", listOf("x-api-key" to PLACEHOLDER_API_KEY), "{}")
            val response = fetch.open(post())
            assertTrue(response.read()!!.isNotEmpty())
            assertEquals(key, server.takeRequest().getHeader("x-api-key"))

            val t0 = System.nanoTime()
            s.revoke()
            val e = try {
                withTimeout(5_000) { while (response.read() != null) Unit }
                null
            } catch (x: HostFetchException) {
                x
            }
            val ms = (System.nanoTime() - t0) / 1e6
            assertNotNull("the in-flight body must be cut off", e)
            assertEquals(NetErrorKind.KEY_REVOKED, e!!.kind)
            assertFalse(e.retryable)
            assertTrue(response.keyRevoked)
            assertTrue("cut off after ${ms}ms", ms < 1_000)
            assertFalse(key in e.message.orEmpty())
            assertEquals(0, fetch.callsWithKey)

            // 第一层：之后的请求拿不到 key，发不出去
            try {
                fetch.open(post())
                fail("expected no key after revoke")
            } catch (x: HostFetchException) {
                assertFalse(key in x.message.orEmpty())
            }
            assertEquals(1, server.requestCount)
            fetch.close()
        } finally {
            server.shutdown()
        }
    }

    private fun expectSecurity(block: () -> Unit) {
        try {
            block()
            fail("expected GeneralSecurityException")
        } catch (e: GeneralSecurityException) {
            // expected
        }
    }
}

class ModelSourcesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = "sk-cp-SECRET0123456789-abcdefghijkLMNO"
    private val cipher = SoftwareCipher()
    private var clock = 1_000L

    private fun sources(
        dir: File = File(tmp.root, "byok"),
        c: SecretCipher = cipher,
        catalog: () -> ModelCatalog = { ModelCatalog.parse(TEST_CATALOG) },
        idle: () -> Boolean = { true },
    ): Pair<ModelSources, KeystoreSecrets> {
        val secrets = KeystoreSecrets(c, idle)
        return ModelSources(dir, secrets, catalog, clock = { clock }) to secrets
    }

    private fun preset(provider: String, model: String, thinking: String? = null) =
        """{"kind":"preset","provider":"$provider","model":"$model"${thinking?.let { ",\"thinkingLevel\":\"$it\"" } ?: ""}}"""

    private fun JsonObject.bool(k: String) = this[k]!!.jsonPrimitive.boolean
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
    private fun JsonObject.keyObj() = this["key"]!!.jsonObject
    private fun JsonObject.problems() = (this["problems"] as JsonArray).map { it.jsonPrimitive.content }

    @Test
    fun presetRoundTripPersistsAndReloads() {
        val (m, secrets) = sources()
        assertFalse(m.get().bool("configured"))
        assertNull(m.activeModel.value)

        val out = m.set(preset("minimax-cn", "MiniMax-M2.7", "high"), "  $key\n")
        assertTrue(out.bool("configured"))
        assertTrue(out.bool("usable"))
        assertEquals("sk-c…LMNO", out.keyObj().str("masked"))
        assertEquals("MiniMax CN API key", out.keyObj().str("label"))
        assertEquals(listOf("https://api.minimaxi.com/anthropic"), out.keyObj()["endpoints"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("MiniMax CN", out.str("providerName"))
        assertEquals("https://api.minimaxi.com/anthropic", out.str("baseUrl"))
        assertFalse(out.toString().contains(key))

        val active = m.activeModel.value!!
        assertEquals("MiniMax-M2.7", active.id)
        assertEquals("minimax-cn", active.provider)
        assertEquals("high", active.thinkingLevel)
        assertTrue(secrets.resolves("https://api.minimaxi.com/anthropic/v1/messages"))

        val file = File(tmp.root, "byok/${ModelSources.FILE_NAME}")
        assertTrue(file.isFile)
        assertFalse("key in plaintext on disk", file.readText().contains(key))
        assertFalse(File(tmp.root, "byok/${ModelSources.FILE_NAME}.tmp").exists())

        // 新进程：同一个主密钥，从文件恢复
        val (m2, secrets2) = sources()
        m2.ensureLoaded()
        assertEquals("sk-c…LMNO", m2.get().keyObj().str("masked"))
        assertTrue(m2.get().bool("usable"))
        assertEquals("high", m2.activeModel.value!!.thinkingLevel)
        assertEquals(key, runBlocking { secrets2.credentialFor("https://api.minimaxi.com/anthropic/v1/messages") }?.reveal())
        assertTrue(m2.status()["credentialResolves"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun keptKeyOnlyForTheSameEndpoints() {
        val (m, _) = sources()
        m.set(preset("minimax-cn", "MiniMax-M2.7"), key)
        // 同一厂商换模型：沿用 key
        val out = m.set(preset("minimax-cn", "MiniMax-M2.5", "low"), null)
        assertEquals("MiniMax-M2.5", m.activeModel.value!!.id)
        assertEquals("sk-c…LMNO", out.keyObj().str("masked"))
        m.set(preset("minimax-cn", "MiniMax-M2.7"), "")
        // 换厂商、换成自定义端点：必须重新输入 key，key 不会被带到别的端点
        assertByok(ModelSources.KEY_REQUIRED) { m.set(preset("minimax", "MiniMax-M2.7"), null) }
        assertByok(ModelSources.KEY_REQUIRED) {
            m.set("""{"kind":"custom","api":"anthropic-messages","baseUrl":"https://gw.example.com/anthropic","model":"m"}""", null)
        }
        // 失败的请求不改变当前设置
        assertEquals("minimax-cn", m.activeModel.value!!.provider)
    }

    @Test
    fun presetKeyCoversAllProviderBaseUrls() = runBlocking {
        val (m, secrets) = sources()
        val out = m.set(preset("fireworks", "fw-o"), key)
        assertEquals(2, out.keyObj()["endpoints"]!!.jsonArray.size)
        assertNotNull(secrets.credentialFor("https://api.fireworks.ai/inference/v1/chat/completions"))
        assertNotNull(secrets.credentialFor("https://api.fireworks.ai/inference/v1/messages"))
        assertNull(secrets.credentialFor("https://api.minimaxi.com/anthropic/v1/messages"))
    }

    @Test
    fun customEndpoint() = runBlocking {
        val (m, secrets) = sources()
        val out = m.set(
            """{"kind":"custom","api":"openai-completions","baseUrl":"https://gw.example.com/acct-123/v1/","model":"qwen3",
               "name":"Qwen 3","contextWindow":32768,"maxTokens":4096,"reasoning":true,"input":["text"]}""",
            key,
        )
        assertEquals("custom", out.str("kind"))
        assertEquals("https://gw.example.com/acct-123/v1", out.str("baseUrl"))
        val model = m.activeModel.value!!
        assertEquals("openai-completions", model.api)
        assertEquals("qwen3", model.id)
        assertEquals("32768", model.model["contextWindow"].toString())
        assertNotNull(secrets.credentialFor("https://gw.example.com/acct-123/v1/chat/completions"))
        assertNull(secrets.credentialFor("https://gw.example.com/other/v1/chat/completions"))
        // 诊断只给 scheme + host，路径里可能有账号信息
        val status = m.status().toString()
        assertTrue(status, status.contains("\"endpoint\":\"https://gw.example.com\""))
        assertFalse(status.contains("acct-123"))
        assertFalse(status.contains(key) || status.contains("LMNO"))
        // 回环地址允许 http（测试、adb reverse）
        m.set("""{"kind":"custom","api":"anthropic-messages","baseUrl":"http://127.0.0.1:8787","model":"fake"}""", "x")
        assertEquals("****", m.get().keyObj().str("masked"))
        Unit
    }

    @Test
    fun rejectsBadEndpointsAndSources() {
        val (m, _) = sources()
        fun custom(base: String, api: String = "anthropic-messages") = """{"kind":"custom","api":"$api","baseUrl":"$base","model":"m"}"""
        for (bad in listOf(
            "http://gw.example.com/v1", "ftp://gw.example.com", "gw.example.com/v1", "https://", "https://u:p@gw.example.com/v1",
            "https://gw.example.com/v1?key=1", "https://gw.example.com/v1#x", "https://gw.example.com/a/../b",
            // F9：http 只允许 127.0.0.1、localhost、::1
            "http://127.0.0.2:8080/v1", "http://127.example.com/v1", "http://10.0.2.2:8080/v1",
        )) {
            assertByok(ModelSources.INVALID_ENDPOINT, bad) { m.set(custom(bad), key) }
        }
        assertByok(ModelSources.UNSUPPORTED_API) { m.set(custom("https://gw.example.com", "openai-responses"), key) }
        // 回环地址可以用 http（本机模型服务、adb reverse）
        for (ok in listOf("http://127.0.0.1:18787", "http://localhost:11434/v1", "http://[::1]:8080/v1")) {
            assertTrue(ok, m.set(custom(ok), key)["configured"]!!.jsonPrimitive.boolean)
        }
        m.clear()
        assertByok(ModelSources.INVALID_SOURCE) { m.set("not json", key) }
        assertByok(ModelSources.INVALID_SOURCE) { m.set("[]", key) }
        assertByok(ModelSources.INVALID_SOURCE) { m.set("""{"kind":"magic","model":"m"}""", key) }
        assertByok(ModelSources.INVALID_SOURCE) { m.set("""{"kind":"preset","provider":"minimax"}""", key) }
        assertByok(ModelSources.INVALID_SOURCE) {
            m.set("""{"kind":"custom","api":"anthropic-messages","baseUrl":"https://g.example.com","model":"m","maxTokens":-1}""", key)
        }
        assertByok(ModelSources.INVALID_SOURCE) {
            m.set("""{"kind":"custom","api":"anthropic-messages","baseUrl":"https://g.example.com","model":"m","input":["audio"]}""", key)
        }
        assertByok(ModelSources.INVALID_THINKING_LEVEL) { m.set(preset("minimax", "MiniMax-M2.7", "max"), key) }
        assertByok(ModelSources.UNKNOWN_PROVIDER) { m.set(preset("nope", "MiniMax-M2.7"), key) }
        assertByok(ModelSources.UNKNOWN_MODEL) { m.set(preset("minimax", "nope"), key) }
        assertByok(ModelSources.INVALID_KEY) { m.set(preset("minimax", "MiniMax-M2.7"), "Bearer $key") }
        assertByok(ModelSources.INVALID_KEY) { m.set(preset("minimax", "MiniMax-M2.7"), "sk-a\u0000b") }
        assertByok(ModelSources.INVALID_KEY) { m.set(preset("minimax", "MiniMax-M2.7"), "k".repeat(5000)) }
        assertByok(ModelSources.KEY_REQUIRED) { m.set(preset("minimax", "MiniMax-M2.7"), "   ") }
        assertFalse(m.get().bool("configured"))
        assertFalse(File(tmp.root, "byok/${ModelSources.FILE_NAME}").exists())
    }

    /** 界面 bug 把 key 填进了别的字段：错误消息里也不能出现它。 */
    @Test
    fun errorMessagesNeverEchoInputs() {
        val (m, _) = sources()
        val attempts: List<() -> Unit> = listOf(
            { m.set(preset(key, "MiniMax-M2.7"), key) },
            { m.set(preset("minimax", key), key) },
            { m.set(preset("minimax", "MiniMax-M2.7", key), key) },
            { m.set("""{"kind":"custom","api":"anthropic-messages","baseUrl":"https://g.example.com/v1?key=$key","model":"m"}""", key) },
            { m.set("""{"kind":"custom","api":"anthropic-messages","baseUrl":"http://$key.example.com","model":"m"}""", key) },
            { m.set("""{"kind":"custom","api":"$key","baseUrl":"https://g.example.com","model":"m"}""", key) },
            { m.set("""{"kind":"$key","model":"m"}""", key) },
            { m.set("{\"kind\":\"preset\",\"provider\":\"$key", key) },
            { m.set(preset("minimax", "MiniMax-M2.7"), "$key\n$key") },
            { m.set(preset("minimax", "MiniMax-M2.7"), "$key $key") },
        )
        for ((i, attempt) in attempts.withIndex()) {
            try {
                attempt()
                fail("attempt $i should fail")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message, e.message!!.startsWith("agentos.byok."))
                assertFalse("attempt $i leaked the key: ${e.message}", e.message!!.contains("SECRET"))
            }
        }
    }

    @Test
    fun clearRemovesEverything() = runBlocking {
        val (m, secrets) = sources()
        m.set(preset("minimax-cn", "MiniMax-M2.7"), key)
        m.clear()
        assertNull(m.activeModel.value)
        assertFalse(m.get().bool("configured"))
        assertFalse(File(tmp.root, "byok/${ModelSources.FILE_NAME}").exists())
        assertNull(secrets.credentialFor("https://api.minimaxi.com/anthropic/v1/messages"))
        assertEquals(1, cipher.destroyed)
        // 新进程也读不到
        val (m2, _) = sources()
        assertFalse(m2.get().bool("configured"))
        // 清除之后可以重新设置（新建主密钥）
        assertTrue(m.set(preset("minimax", "MiniMax-M2.7"), key).bool("usable"))
    }

    /**
     * 有任务在跑时：更换是热加载（这一轮之后对旧端点的请求还能拿到旧 key），清除是立即作废（旧 key、新 key 都拿不到）。
     */
    @Test
    fun replaceIsHotReloadButClearRevokesImmediately() = runBlocking {
        val (m, secrets) = sources(idle = { false })
        val cn = "https://api.minimaxi.com/anthropic/v1/messages"
        val intl = "https://api.minimax.io/anthropic/v1/messages"
        m.set(preset("minimax-cn", "MiniMax-M2.7"), key)
        m.set(preset("minimax", "MiniMax-M2.7"), "sk-other-key-000000000")
        assertEquals(key, secrets.credentialFor(cn)?.reveal())
        assertEquals("sk-other-key-000000000", secrets.credentialFor(intl)?.reveal())
        val servedBefore = secrets.stats()["served"] as Long
        m.clear()
        assertNull(secrets.credentialFor(cn))
        assertNull(secrets.credentialFor(intl))
        assertNull(m.activeModel.value)
        assertEquals("no request gets a key after clear", servedBefore, secrets.stats()["served"])
        assertFalse(m.status()["credentialResolves"]!!.jsonPrimitive.boolean)
        assertFalse(m.status()["retiredKeyHeld"]!!.jsonPrimitive.boolean)
        assertEquals("1", m.status()["requests"]!!.jsonObject["revocations"].toString())
    }

    @Test
    fun lostMasterKeyAsksForTheKeyAgain() {
        val (m, _) = sources()
        m.set(preset("minimax-cn", "MiniMax-M2.7"), key)
        // 数据被恢复到另一台设备：文件还在，Keystore 主密钥不在
        val (m2, secrets2) = sources(c = SoftwareCipher())
        val out = m2.get()
        assertTrue(out.bool("configured"))
        assertFalse(out.bool("usable"))
        assertFalse(out.keyObj().bool("set"))
        assertTrue(out.problems().contains(ModelSources.PROBLEM_KEY_UNREADABLE))
        assertNull(m2.activeModel.value)
        assertFalse(secrets2.isSet)
        assertByok(ModelSources.KEY_REQUIRED) { m2.set(preset("minimax-cn", "MiniMax-M2.5"), null) }
        val again = m2.set(preset("minimax-cn", "MiniMax-M2.5"), key)
        assertTrue(again.bool("usable"))
        assertTrue(again.problems().isEmpty())
    }

    @Test
    fun damagedOrNewerFileIsReportedNotFatal() {
        val dir = File(tmp.root, "byok").apply { mkdirs() }
        File(dir, ModelSources.FILE_NAME).writeText("{ broken")
        val (m, _) = sources()
        assertTrue(m.get().problems().contains(ModelSources.PROBLEM_CONFIG_UNREADABLE))
        assertTrue(m.set(preset("minimax", "MiniMax-M2.7"), key).bool("usable"))

        File(dir, ModelSources.FILE_NAME).writeText("""{"v":99}""")
        val (m2, _) = sources()
        assertTrue(m2.get().problems().contains(ModelSources.PROBLEM_NEWER_FORMAT))
    }

    @Test
    fun presetModelDroppedFromCatalogKeepsWorking() {
        val (m, _) = sources()
        m.set(preset("minimax-cn", "MiniMax-M2.5"), key)
        val smaller = TEST_CATALOG.replace("\"id\": \"MiniMax-M2.5\"", "\"id\": \"MiniMax-M2.5-gone\"")
        val (m2, _) = sources(catalog = { ModelCatalog.parse(smaller) })
        val out = m2.get()
        assertTrue(out.bool("usable"))
        assertTrue(out.problems().contains(ModelSources.PROBLEM_MODEL_NOT_IN_CATALOG))
        assertEquals("MiniMax-M2.5", m2.activeModel.value!!.id)
    }

    @Test
    fun presetsAreTwoLevel() {
        val (m, _) = sources()
        val all = m.presets(null)
        val providers = all["providers"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("minimax", "minimax-cn", "fireworks"), providers.map { it.str("id") })
        assertNull(providers[0]["models"])
        assertEquals("2", providers[1]["modelCount"].toString())
        assertEquals(listOf("anthropic-messages", "openai-completions"), all["customApis"]!!.jsonArray.map { it.jsonPrimitive.content })
        val one = m.presets("minimax-cn")["provider"]!!.jsonObject
        assertEquals(listOf("MiniMax-M2.7", "MiniMax-M2.5"), one["models"]!!.jsonArray.map { it.jsonObject.str("id") })
        assertByok(ModelSources.UNKNOWN_PROVIDER) { m.presets("nope") }
    }

    @Test
    fun missingCatalog() {
        val (m, _) = sources(catalog = { throw java.io.FileNotFoundException("model-catalog.json") })
        assertByok(ModelSources.CATALOG_UNAVAILABLE) { m.presets(null) }
        assertByok(ModelSources.CATALOG_UNAVAILABLE) { m.set(preset("minimax", "MiniMax-M2.7"), key) }
        assertFalse(m.get().bool("configured"))
        assertFalse(m.status()["catalog"]!!.jsonObject.bool("loaded"))
    }

    private fun assertByok(code: String, label: String = code, block: () -> Unit) {
        try {
            block()
            fail("$label: expected agentos.byok.$code")
        } catch (e: ByokException) {
            assertEquals(label, code, e.code)
            assertTrue(e.message!!.startsWith("agentos.byok.$code: "))
        }
    }
}
