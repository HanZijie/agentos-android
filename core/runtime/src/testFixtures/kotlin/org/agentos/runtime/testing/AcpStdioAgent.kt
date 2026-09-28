@file:JvmName("AcpStdioAgent")

package org.agentos.runtime.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.agentos.runtime.RuntimeConfig
import org.agentos.runtime.acp.BoundedLineReader
import org.agentos.runtime.acp.LineTransport
import org.agentos.runtime.desktop.DesktopGatewayConfig
import org.agentos.runtime.desktop.DesktopGatewayCore
import org.agentos.runtime.desktop.DesktopPairing
import org.agentos.runtime.desktop.FilePairingStore
import org.agentos.runtime.desktop.MemoryPairingStore
import org.agentos.runtime.ports.CallerIdentity
import org.agentos.runtime.ports.CallerKind
import org.agentos.runtime.ports.ConsentDecision
import org.agentos.runtime.ports.OutboundGate
import org.agentos.runtime.ports.ToolInvocationResult
import org.agentos.runtime.ports.ToolResult
import org.agentos.runtime.ports.ToolRisk
import org.agentos.runtime.router.JevProvider
import org.agentos.runtime.scheduler.SchedulerConfig
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * 电脑上的 ACP Agent 进程，给 tests/acp-conformance（官方 TypeScript 客户端）和 tools/acp-bridge 的测试用。
 *
 * 宿主层是完整的 [org.agentos.runtime.RuntimeEngine]（真实 SQLite、调度、Broker、ACP Agent 端）；
 * Agent 循环 `--core=fake`（默认）是 FakeAgentCore，prompt 里的 JSON 指令决定剧本（[FakeScripts.directives]）。
 * B lane 的 PiAdapter 进 main 后加 `--core=pi`（真实 Pi + 假模型端点）。
 *
 * 两种模式：
 * - **stdio**（默认）：stdin / stdout 按行收发 JSON-RPC，传输是 [LineTransport]（与手机上的电脑端网关相同的传输，
 *   编码没有多余字段）。stdout 上只有 JSON-RPC，日志一律走 stderr。
 * - **网关**（`--listen=<端口>`，0 表示任选）：与手机上的电脑端网关相同的 [DesktopGatewayCore]（开关、配对码、令牌、握手），
 *   监听本机回环地址的 TCP 端口代替抽象 socket，对端 UID 报 2000（adb forward 的 adbd）。开关默认关闭。
 *   stdin / stdout 是控制通道（每行一条 JSON），与设备上 debug 包的测试入口（DesktopGatewayDebugReceiver）的操作相同：
 *   `{"op":"enable"}`、`{"op":"disable"}`、`{"op":"pair","ttlMs":…}`、`{"op":"status"}`、`{"op":"revoke_all"}`。
 *   stdin 关闭时退出。
 *
 * 其他参数：
 * - 工具：`add`（read，返回 a + b）、`send_note`（write）。`--consent=allow`（默认，手机上的确认自动通过）或 `deny`。
 * - `--jev=first`：自动选会话时总是选第一个候选；默认不配置 Jev（回退新建）。
 * - `--db=<目录>`：数据库目录，默认临时目录。`--pairing-state=<文件>`：网关模式的配对状态文件，默认只在内存里。
 * - `--handshake-timeout-ms=<毫秒>`：网关模式的握手超时，默认 10,000。
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
    val consent = arg("consent") ?: "allow"
    require(consent == "allow" || consent == "deny") { "--consent must be allow or deny" }

    // stdout 只给 JSON-RPC / 控制通道；其他任何输出都不能混进去
    val out = System.out
    System.setOut(PrintStream(System.err, true))

    runBlocking {
        val rt = TestRuntime(
            FakeScripts.directives(),
            databaseFile = File(dbDir, "agent.db"),
            config = RuntimeConfig(scheduler = SchedulerConfig(), jev = jev),
        )
        if (consent == "deny") rt.host.consent.answer = { ConsentDecision.Deny(ConsentDecision.DenyReason.USER) }
        rt.host.tools.registerSimple("add") { a ->
            val x = (a["a"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            val y = (a["b"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            val sum = x + y
            ToolResult.text(if (sum % 1.0 == 0.0) sum.toLong().toString() else sum.toString())
        }
        rt.host.tools.register("send_note", ToolRisk.WRITE) { ToolInvocationResult.Completed(ToolResult.text("sent")) }

        val listen = arg("listen")
        if (listen != null) {
            runGateway(rt, listen.toInt(), arg("pairing-state"), arg("handshake-timeout-ms")?.toLong(), out)
        } else {
            val transport = LineTransport(
                reader = BoundedLineReader(System.`in`),
                output = out,
                parentScope = this,
                codec = TestLineCodec,
                name = "acp-stdio",
                closeStreams = { out.flush() },
            )
            // 与 :agent 一样：先接上连接（立即返回），再执行启动恢复
            val connection = rt.engine.serveAcp(transport, CallerIdentity(0, CallerKind.DESKTOP, "desktop"), OutboundGate.NONE)
            rt.start()
            System.err.println("agentos-acp-stdio: ready (core=$core, db=${dbDir.absolutePath})")
            connection.awaitClosed()
        }
        rt.stop()
    }
    exitProcess(0)
}

private suspend fun CoroutineScope.runGateway(rt: TestRuntime, port: Int, stateFile: String?, handshakeTimeout: Long?, out: PrintStream) {
    val listeners = TcpDesktopListenerFactory(port)
    val pairing = DesktopPairing(stateFile?.let { FilePairingStore(File(it)) } ?: MemoryPairingStore())
    val gateway = DesktopGatewayCore(
        runtime = rt.engine,
        pairing = pairing,
        codec = TestLineCodec,
        listenerFactory = listeners,
        parentScope = this,
        config = DesktopGatewayConfig(handshakeTimeoutMillis = handshakeTimeout ?: DesktopGatewayConfig().handshakeTimeoutMillis),
    )
    gateway.restore()
    rt.start()
    fun reply(obj: JsonObject) = synchronized(out) {
        out.write((Json.encodeToString(JsonObject.serializer(), obj) + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }
    reply(buildJsonObject { put("event", "ready") })
    System.err.println("agentos-acp-gateway: ready (control on stdin/stdout)")
    val control = System.`in`.bufferedReader(Charsets.UTF_8)
    while (true) {
        val line = withContext(Dispatchers.IO) { control.readLine() } ?: break
        if (line.isBlank()) continue
        val cmd = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
        val op = (cmd?.get("op") as? JsonPrimitive)?.content
        val response = runCatching {
            when (op) {
                "enable" -> {
                    gateway.setEnabled(true)
                    status(gateway, listeners.port, op)
                }
                "disable" -> {
                    gateway.setEnabled(false)
                    status(gateway, listeners.port, op)
                }
                "pair" -> {
                    val ttl = (cmd["ttlMs"] as? JsonPrimitive)?.longOrNull
                    val code = if (ttl != null) gateway.newPairingCode(ttl) else gateway.newPairingCode()
                    buildJsonObject {
                        put("op", op)
                        put("ok", true)
                        put("code", code.code)
                        put("expiresAtMs", code.expiresAtMillis)
                        put("ttlMs", code.ttlMillis)
                    }
                }
                "revoke_all" -> {
                    val n = gateway.revokeAll()
                    buildJsonObject {
                        put("op", op)
                        put("ok", true)
                        put("revoked", n)
                    }
                }
                "status" -> status(gateway, listeners.port, op)
                else -> buildJsonObject {
                    put("op", op)
                    put("ok", false)
                    put("error", "unknown op")
                }
            }
        }.getOrElse { e ->
            buildJsonObject {
                put("op", op)
                put("ok", false)
                put("error", e.message ?: e.javaClass.simpleName)
            }
        }
        reply(response)
    }
    gateway.shutdown()
}

private fun status(gateway: DesktopGatewayCore, port: Int, op: String) = gateway.stats().let { s ->
    buildJsonObject {
        put("op", op)
        put("ok", true)
        put("enabled", s.enabled)
        put("listening", s.listening)
        put("port", port)
        put("pairings", s.pairings)
        put("connections", s.connections.size)
        put("served", s.served)
        put("closed", s.closed)
        put("rejectedPeer", s.rejectedPeer)
        put("handshakeTimeouts", s.handshakeTimeouts)
        putJsonObject("handshakeFailures") { s.handshakeFailures.forEach { (k, v) -> put(k, v) } }
        s.code?.let { c ->
            putJsonObject("code") {
                put("expiresAtMs", c.expiresAtMillis)
                put("attemptsLeft", c.attemptsLeft)
            }
        }
    }
}
