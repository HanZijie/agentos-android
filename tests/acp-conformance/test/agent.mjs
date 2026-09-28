// 启动电脑端的 AgentOS 运行时（org.agentos.runtime.testing.AcpStdioAgent），用官方 TypeScript ACP 客户端连上。
//
// 类路径来自 Gradle 任务 :core:runtime:acpConformanceClasspath（core/runtime/build/acp-conformance/classpath.txt）；
// 文件不存在时自动调用它。也可以用环境变量 AGENTOS_ACP_CLASSPATH 直接给出类路径。
// Java 用 JAVA_HOME 下的（JDK 21），没有就用 PATH 里的 java。
import { spawn, execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { PassThrough, Readable, Writable } from "node:stream";
import { fileURLToPath } from "node:url";
import * as acp from "@agentclientprotocol/sdk";

const here = path.dirname(fileURLToPath(import.meta.url));
export const repoRoot = path.resolve(here, "../../..");
const classpathFile = path.join(repoRoot, "core/runtime/build/acp-conformance/classpath.txt");

let cachedClasspath;

export function agentClasspath() {
  if (process.env.AGENTOS_ACP_CLASSPATH) return process.env.AGENTOS_ACP_CLASSPATH;
  if (cachedClasspath) return cachedClasspath;
  if (!existsSync(classpathFile) || process.env.AGENTOS_ACP_REBUILD === "1") {
    const gradlew = path.join(repoRoot, process.platform === "win32" ? "gradlew.bat" : "gradlew");
    execFileSync(gradlew, ["-q", ":core:runtime:acpConformanceClasspath"], { cwd: repoRoot, stdio: "inherit" });
  }
  cachedClasspath = readFileSync(classpathFile, "utf8").trim();
  return cachedClasspath;
}

/**
 * 启动一个 Agent 进程并建立 ACP 连接。
 * @param {{ args?: string[] }} [options] 传给 AcpStdioAgent 的参数，例如 ["--jev=first"]
 */
export async function startAgent({ args = [] } = {}) {
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", "java") : "java";
  const child = spawn(
    java,
    ["-Dkotlin-logging-to-jul=true", "-cp", agentClasspath(), "org.agentos.runtime.testing.AcpStdioAgent", ...args],
    { stdio: ["pipe", "pipe", "pipe"] },
  );
  const stderr = [];
  child.stderr.on("data", (d) => stderr.push(d.toString("utf8")));

  // stdout 的每一行原文（检查线上格式），同时原样交给 SDK
  const lines = [];
  let pending = "";
  const tee = new PassThrough();
  child.stdout.on("data", (d) => {
    pending += d.toString("utf8");
    for (let i = pending.indexOf("\n"); i >= 0; i = pending.indexOf("\n")) {
      lines.push(pending.slice(0, i));
      pending = pending.slice(i + 1);
    }
    tee.write(d);
  });
  child.stdout.on("end", () => tee.end());

  /** 收到的全部 session/update 通知（params），按到达顺序。 */
  const updates = [];
  const client = {
    async requestPermission() {
      return { outcome: { outcome: "cancelled" } };
    },
    async sessionUpdate(params) {
      updates.push(params);
    },
  };
  const stream = acp.ndJsonStream(Writable.toWeb(child.stdin), Readable.toWeb(tee));
  const connection = new acp.ClientSideConnection(() => client, stream);

  const exited = new Promise((resolve) => child.on("exit", (code, signal) => resolve({ code, signal })));

  return {
    child,
    connection,
    updates,
    lines,
    stderr,
    /** 某个会话收到的更新（update 对象）。 */
    updatesFor(sessionId) {
      return updates.filter((u) => u.sessionId === sessionId).map((u) => u.update);
    },
    /** 关闭 stdin，等进程退出（最多 10 秒，之后强制结束）。 */
    async stop() {
      child.stdin.end();
      const timer = setTimeout(() => child.kill("SIGKILL"), 10_000);
      const result = await exited;
      clearTimeout(timer);
      return result;
    },
  };
}

/** 一段文字的更新里 agent_message_chunk 拼起来的文字。 */
export function textOf(updates) {
  return updates
    .filter((u) => u.sessionUpdate === "agent_message_chunk" && u.content?.type === "text")
    .map((u) => u.content.text)
    .join("");
}

/** 让 FakeAgentCore 按剧本行事的 prompt（见 FakeScripts.directives）。 */
export function directive(fake) {
  return [{ type: "text", text: JSON.stringify({ fake }) }];
}

export async function until(condition, timeoutMs = 10_000, what = "condition") {
  const start = Date.now();
  while (!condition()) {
    if (Date.now() - start > timeoutMs) throw new Error(`timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 10));
  }
}

export const PROTOCOL_VERSION = acp.PROTOCOL_VERSION;
