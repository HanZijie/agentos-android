@file:JvmName("FakeModelServerMain")

package org.agentos.runtime.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.agentos.runtime.pi.testing.FakeModelServer
import kotlin.system.exitProcess

/**
 * 单独运行 B 的 [FakeModelServer]（测试用）：tests/acp-conformance 的设备模式在电脑上起它，经
 * `adb reverse tcp:18787 tcp:<端口>` 映射到手机，手机上 :agent 里的真实 Pi 向它请求模型。它按一致性用例的
 * `{"fake":…}` 指令回应（文字、thinking、工具调用、等待中止、max_tokens、各类失败），所以同一组用例在手机上也能跑。
 *
 * - `--key=<测试 key>`：只接受这个 key（与设备上的测试模型来源一致，C 的 ensureTestModel 用 `agtest-fake-model-key`）。
 *   这是测试值，不是任何真实 key；它只出现在电脑上的命令行里，不经 adb shell。
 * - stdout 第一行：`{"event":"ready","port":<端口>,"baseUrl":"http://127.0.0.1:<端口>"}`。
 *   Anthropic Messages 在根路径 `/v1/messages`（也在 `/anthropic/v1/messages`）。
 * - stdin 每行一条控制命令（JSON），stdout 每条回一行 `{"ok":true|false,"op":…}`：
 *   - `{"op":"failNext","status":429,"times":2,"retryAfter":"1"}`：接下来 times 个请求不看内容都回 status
 *     （[FakeModelServer.failNext]；retryAfter 可省略）。用来检查手机上宿主层的重试（B6）；
 *   - `{"op":"requests"}`：到目前为止的请求，`"requests":[{"id":1,"plan":"fail429","status":429},…]`（不含请求内容和 key）。
 * - stdin 关闭时退出。每个请求在 stderr 打一行（序号、协议族、剧本、状态码），不含请求内容和 key。
 */
fun main(args: Array<String>) {
    fun arg(name: String) = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter("=")
    val key = arg("key") ?: run {
        System.err.println("usage: FakeModelServerMain --key=<test key>")
        exitProcess(2)
    }
    val server = FakeModelServer(key)
    println("""{"event":"ready","port":${server.baseUrl.substringAfterLast(':')},"baseUrl":"${server.baseUrl}"}""")
    System.out.flush()
    val reporter = Thread({
        var seen = 0
        while (true) {
            val all = server.requests
            while (seen < all.size) {
                val r = all[seen]
                val settled = r.status != 0 || r.closedEarly || r.finished || System.currentTimeMillis() - r.startedAt > 2_000
                if (!settled) break
                seen++
                System.err.println("fake-model: #${r.id} ${r.api} plan=${r.plan} status=${r.status}")
            }
            Thread.sleep(200)
        }
    }, "fake-model-report").apply { isDaemon = true }
    reporter.start()
    System.`in`.bufferedReader().forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        println(control(server, line))
        System.out.flush()
    }
    server.close()
    exitProcess(0)
}

private fun control(server: FakeModelServer, line: String): JsonObject {
    val cmd = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
    val op = cmd?.get("op")?.jsonPrimitive?.content
    return when (op) {
        "failNext" -> {
            val status = cmd["status"]?.jsonPrimitive?.intOrNull ?: return error(op, "status is required")
            val times = cmd["times"]?.jsonPrimitive?.intOrNull ?: 1
            server.failNext(status, times, cmd["retryAfter"]?.jsonPrimitive?.contentOrNull)
            buildJsonObject {
                put("ok", true)
                put("op", op)
            }
        }
        "requests" -> buildJsonObject {
            put("ok", true)
            put("op", op)
            put(
                "requests",
                buildJsonArray {
                    for (r in server.requests) {
                        add(
                            buildJsonObject {
                                put("id", r.id)
                                put("plan", r.plan)
                                put("status", r.status)
                            },
                        )
                    }
                },
            )
        }
        else -> error(op, "unknown op")
    }
}

private fun error(op: String?, message: String) = buildJsonObject {
    put("ok", false)
    put("op", op)
    put("error", message)
}
