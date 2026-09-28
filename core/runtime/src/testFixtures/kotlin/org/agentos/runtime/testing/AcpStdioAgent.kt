@file:JvmName("AcpStdioAgent")

package org.agentos.runtime.testing

import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.agentos.runtime.RuntimeConfig
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.OutboundGate
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.router.JevProvider
import org.agentos.runtime.scheduler.SchedulerConfig
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * 电脑上的 ACP Agent 进程，给 tests/acp-conformance（官方 TypeScript 客户端）用：
 *
 * - stdin / stdout 按行收发 JSON-RPC，传输是 ACP SDK 自带的 `StdioTransport`；stdout 上只有 JSON-RPC，日志一律走 stderr。
 * - 宿主层是完整的 [org.agentos.runtime.RuntimeEngine]（真实 SQLite、调度、Broker、ACP Agent 端）。
 * - Agent 循环：`--core=fake`（默认）用 FakeAgentCore，prompt 里的 JSON 指令决定剧本（[FakeScripts.directives]）。
 *   B lane 的 PiAdapter 进 main 后加 `--core=pi`（真实 Pi + 假模型端点）。
 * - 工具：`add`（read，返回 a + b）、`send_note`（write，确认自动通过，返回 "sent"）。
 * - `--jev=first`：自动选会话时总是选第一个候选（验证扩展的“选中已有会话”分支）；默认不配置 Jev（回退新建）。
 * - `--db=<目录>`：数据库目录，默认临时目录。
 *
 * ACP SDK 0.30.1 在 JVM 上依赖 kotlin-logging：必须设置 `-Dkotlin-logging-to-jul=true`，否则缺 slf4j 会在第一次记日志时崩溃。
 * 这里没设置时自动补上。
 */
fun main(args: Array<String>) {
    if (System.getProperty("kotlin-logging-to-jul") == null) System.setProperty("kotlin-logging-to-jul", "true")
    fun arg(name: String) = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter("=")
    val core = arg("core") ?: "fake"
    require(core == "fake") { "--core=$core is not available yet (the Pi adapter lands with B2)" }
    val dbDir = arg("db")?.let(::File) ?: Files.createTempDirectory("agentos-acp").toFile()
    val jev = if (arg("jev") == "first") JevProvider { req -> req.choices.first().id } else null

    val out = System.out
    runBlocking {
        val rt = TestRuntime(
            FakeScripts.directives(),
            databaseFile = File(dbDir, "agent.db"),
            config = RuntimeConfig(scheduler = SchedulerConfig(), jev = jev),
        )
        rt.host.tools.registerSimple("add") { args ->
            val a = (args["a"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            val b = (args["b"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            val sum = a + b
            ToolResult.text(if (sum % 1.0 == 0.0) sum.toLong().toString() else sum.toString())
        }
        rt.host.tools.register("send_note", ToolRisk.WRITE) { ToolInvocationResult.Completed(ToolResult.text("sent")) }

        val stdin = System.`in`.bufferedReader(Charsets.UTF_8)
        val input = flow {
            while (true) {
                val line = withContext(Dispatchers.IO) { stdin.readLine() } ?: break
                if (line.isNotBlank()) emit(line)
            }
        }
        val transport = StdioTransport(this, Dispatchers.IO, input, { line ->
            synchronized(out) {
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }, "acp-stdio")
        // 与 W6 一样：先接上连接（立即返回），再执行启动恢复
        val connection = rt.engine.serveAcp(transport, CallerIdentity(0, CallerKind.DESKTOP, "desktop"), OutboundGate.NONE)
        rt.start()
        System.err.println("agentos-acp-stdio: ready (core=$core, db=${dbDir.absolutePath})")
        connection.awaitClosed()
        rt.stop()
    }
    exitProcess(0)
}
