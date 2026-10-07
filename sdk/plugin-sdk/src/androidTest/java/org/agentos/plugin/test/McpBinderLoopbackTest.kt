package org.agentos.plugin.test

import android.content.ComponentName
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.plugin.McpBinderClient
import org.agentos.plugin.McpClosedException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MCP over Binder 的跨进程回环（C7a 验收）：测试进程里的 [McpBinderClient] 经 binder-channel-v1 连到 :mcp 进程里的
 * [LoopbackMcpService]。
 */
@RunWith(AndroidJUnit4::class)
class McpBinderLoopbackTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val component = ComponentName(ctx, LoopbackMcpService::class.java)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun initializeListCallCancelListChanged() = runBlocking<Unit> {
        val client = McpBinderClient.bind(ctx, component, scope)
        val info = client.initialize("loopback-test", "1")
        assertEquals("loopback", info.name)
        assertEquals("0.1", info.version)

        val names = client.listTools().map { it.name }
        assertTrue(names.toString(), names.containsAll(listOf("echo", "slow", "stats", "add_tool")))

        val echo = client.callTool("echo", buildJsonObject { put("text", "你好 MCP") })
        assertFalse(echo.isError)
        assertEquals("你好 MCP", echo.structuredContent!!["text"]!!.jsonPrimitive.content)
        val serverPid = echo.structuredContent!!["pid"]!!.jsonPrimitive.int
        assertNotEquals("the service runs in another process", Process.myPid(), serverPid)
        assertTrue(client.callTool("echo").isError)

        // 跨进程取消：调用方协程取消 → notifications/cancelled → 服务端 handler 被取消
        val before = client.callTool("stats").structuredContent!!["cancelled"]!!.jsonPrimitive.int
        val call = async { client.callTool("slow", buildJsonObject { put("ms", 60_000) }) }
        delay(500)
        call.cancel()
        withTimeout(5_000) {
            while (client.callTool("stats").structuredContent!!["cancelled"]!!.jsonPrimitive.int == before) delay(50)
        }

        // list_changed
        val changed = async { client.toolsChanged.first() }
        delay(100)
        assertEquals("added", client.callTool("add_tool").text)
        withTimeout(5_000) { changed.await() }
        assertTrue(client.listTools().any { it.name == "extra" })
        assertEquals("extra", client.callTool("extra").text)

        // 单条上限附近：30,000 字符的参数往返（结果里文本出现两次：content 的 JSON 文本 + structuredContent，约 60,100 字符）
        val big = "x".repeat(30_000)
        assertEquals(30_000, client.callTool("echo", buildJsonObject { put("text", big) }).structuredContent!!["text"]!!.jsonPrimitive.content.length)

        client.close()
        withTimeout(5_000) { client.closed.await() }
    }

    @Test
    fun serviceProcessDeath_failsInFlightCallAsDispatched() = runBlocking<Unit> {
        val client = McpBinderClient.bind(ctx, component, scope)
        client.initialize()
        val pid = client.callTool("stats").structuredContent!!["pid"]!!.jsonPrimitive.int
        val call = async { runCatching { client.callTool("slow", buildJsonObject { put("ms", 60_000) }) } }
        delay(500)
        Process.killProcess(pid)
        val e = withTimeout(10_000) { call.await() }.exceptionOrNull()
        assertTrue("got $e", e is McpClosedException && e.dispatched)
        assertTrue(withTimeout(5_000) { client.closed.await() }.contains("peer_died"))

        // 重新 bind：系统重建服务进程
        val again = McpBinderClient.bind(ctx, component, scope)
        again.initialize()
        assertNotEquals(pid, again.callTool("stats").structuredContent!!["pid"]!!.jsonPrimitive.int)
        again.close()
    }
}
