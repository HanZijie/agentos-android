// 启动被测的 AgentOS 运行时，用官方 TypeScript ACP 客户端连上。三种目标（AGENTOS_ACP_TARGETS，逗号分隔）：
//
// - stdio：电脑上的运行时（org.agentos.runtime.testing.AcpStdioAgent）经 stdin/stdout；
// - gateway：同一个运行时以网关模式运行（与手机上相同的开关、配对码、令牌、握手和按行传输，监听本机 TCP 端口代替抽象 socket），
//   客户端经 tools/acp-bridge（--connect）连进去；
// - device：手机上 AgentOS 的 :agent（AGENTOS_ACP_DEVICE=<adb serial>），经 tools/acp-bridge（adb forward → localabstract:agentos-acp）。
//   开关和配对码经 debug 包的测试入口（DesktopGatewayDebugReceiver）操作。
//
// 类路径来自 Gradle 任务 :core:runtime:acpConformanceClasspath（core/runtime/build/acp-conformance/classpath.txt）；
// 文件不存在时自动调用它。也可以用环境变量 AGENTOS_ACP_CLASSPATH 直接给出类路径。
// Java 用 JAVA_HOME 下的（JDK 21），没有就用 PATH 里的 java。
import { execFileSync, spawn } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync } from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { PassThrough, Readable, Writable } from "node:stream";
import { fileURLToPath } from "node:url";
import * as acp from "@agentclientprotocol/sdk";

const here = path.dirname(fileURLToPath(import.meta.url));
export const repoRoot = path.resolve(here, "../../..");
const classpathFile = path.join(repoRoot, "core/runtime/build/acp-conformance/classpath.txt");
export const bridgeScript = path.join(repoRoot, "tools/acp-bridge/acp-bridge.mjs");

export const deviceSerial = process.env.AGENTOS_ACP_DEVICE || null;

/**
 * 这次要跑的目标（AGENTOS_ACP_TARGETS，逗号分隔）。默认：没设 AGENTOS_ACP_DEVICE 时 stdio 和 gateway（都不需要设备）；
 * 设了时只跑 device（手机上的 :agent，真实 Pi + 电脑上的 FakeModelServer）。
 */
export function targets() {
  const fallback = deviceSerial ? "device" : "stdio,gateway";
  return (process.env.AGENTOS_ACP_TARGETS ?? fallback).split(",").map((s) => s.trim()).filter(Boolean);
}

/**
 * 被测目标具备的测试能力。电脑上的 AcpStdioAgent 注册了测试工具 add（read）/ send_note（write，确认自动通过），
 * 并能用 --jev=first 模拟 Jev；手机上 M1 都没有，依赖它们的用例在设备模式下跳过（原因见 SKIP）。
 */
export function capabilities(target) {
  const desktop = target !== "device";
  return { tools: desktop, consent: desktop, jev: desktop };
}

/** 设备模式下跳过的原因（报告里逐条列出）。 */
export const SKIP = {
  tools: "手机上 M1 没有工具：插件工具归 W14（Extension Host），MCP 工具归 W15；电脑上用 AcpStdioAgent 注册的 add / send_note",
  consent: "手机上 M1 没有风险策略与确认界面（W16）；电脑上的确认由 AcpStdioAgent 自动通过",
  jev: "--jev=first 只在电脑上的 AcpStdioAgent 里有；手机上的 :agent 没有配置 Jev（RuntimeConfig.jev 为空，接线还没有分配工作包）",
};

/** 某个用例在这个目标上要不要跳过：返回 false（照常跑）或跳过原因。 */
export function skipUnless(target, ...needs) {
  const caps = capabilities(target);
  const missing = needs.filter((n) => !caps[n]);
  return missing.length ? missing.map((n) => SKIP[n]).join("；") : false;
}

/**
 * 电脑上的运行时用哪个 Agent 循环（AGENTOS_ACP_CORE）：fake（默认，FakeAgentCore）或 pi（真实 Pi：PiAdapter + 电脑上的
 * QuickJS + 假模型端点 FakeModelServer，需要先在 core/pi-runtime 里 `npm ci && node build.mjs`）。
 * pi 时 AGENTOS_ACP_PI_API 选模型协议族：anthropic（默认）或 openai。
 */
export const agentCore = process.env.AGENTOS_ACP_CORE ?? "fake";

function coreArgs() {
  if (agentCore === "fake") return [];
  if (agentCore !== "pi") throw new Error(`AGENTOS_ACP_CORE must be fake or pi, not ${agentCore}`);
  const args = ["--core=pi", `--pi-assets=${path.join(repoRoot, "app/src/main/assets")}`];
  if (process.env.AGENTOS_ACP_PI_API) args.push(`--pi-api=${process.env.AGENTOS_ACP_PI_API}`);
  return args;
}

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

function spawnJavaAgent(args) {
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", "java") : "java";
  return spawn(java, ["-Dkotlin-logging-to-jul=true", "-cp", agentClasspath(), "org.agentos.runtime.testing.AcpStdioAgent", ...coreArgs(), ...args], {
    stdio: ["pipe", "pipe", "pipe"],
  });
}

export function tempStateFile() {
  return path.join(mkdtempSync(path.join(os.tmpdir(), "acp-bridge-")), "state.json");
}

/** 启动 tools/acp-bridge（子进程），参数原样传入。 */
export function spawnBridge(args) {
  return spawn(process.execPath, [bridgeScript, ...args], { stdio: ["pipe", "pipe", "pipe"] });
}

/** 跑一次 acp-bridge 直到退出（pair、unpair 等），返回 { code, stderr }。 */
export function runBridge(args, timeoutMs = 20_000) {
  return new Promise((resolve, reject) => {
    const child = spawnBridge(args);
    const stderr = [];
    child.stderr.on("data", (d) => stderr.push(d.toString("utf8")));
    child.stdin.end();
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      reject(new Error(`acp-bridge ${args.join(" ")} did not exit`));
    }, timeoutMs);
    child.on("exit", (code) => {
      clearTimeout(timer);
      resolve({ code, stderr: stderr.join("") });
    });
  });
}

/**
 * 把 ACP 客户端接到一个以 stdio 说 ACP 的子进程（AcpStdioAgent 或 acp-bridge）上。
 * 记录 stdout 的每一行原文（检查线上格式），同时原样交给 SDK。
 */
function attachClient(child, { onStop } = {}) {
  const stderr = [];
  child.stderr.on("data", (d) => stderr.push(d.toString("utf8")));
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
  const permissionRequests = [];
  const client = {
    async requestPermission(params) {
      permissionRequests.push(params);
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
    permissionRequests,
    lines,
    stderr,
    exited,
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
      if (onStop) await onStop();
      return result;
    },
  };
}

/**
 * 启动一个 Agent 并建立 ACP 连接。
 * @param {{ args?: string[], target?: string }} [options] args 传给 AcpStdioAgent，例如 ["--jev=first"]
 */
export async function startAgent({ args = [], target = "stdio" } = {}) {
  if (target === "stdio") return attachClient(spawnJavaAgent(args));
  if (target === "gateway") {
    const gw = await startLocalGateway({ args });
    await gw.control({ op: "enable" });
    const { code } = await gw.control({ op: "pair" });
    const bridge = spawnBridge(["--connect", `127.0.0.1:${gw.port}`, "--code", code, "--state", tempStateFile(), "--quiet"]);
    const agent = attachClient(bridge, { onStop: () => gw.stop() });
    agent.gateway = gw;
    return agent;
  }
  if (target === "device") {
    if (!deviceSerial) throw new Error("set AGENTOS_ACP_DEVICE=<adb serial> for the device target");
    deviceControl(deviceSerial, "enable");
    const { code } = deviceControl(deviceSerial, "pair");
    const bridge = spawnBridge(["--serial", deviceSerial, "--code", code, "--state", tempStateFile(), "--quiet"]);
    return attachClient(bridge);
  }
  throw new Error(`unknown target ${target}`);
}

/** 用给定参数启动 acp-bridge，并把 ACP 客户端接上去（配对已经完成、用保存的令牌时用它）。 */
export function startBridgeAgent(args) {
  return attachClient(spawnBridge(args));
}

// ------------------------------------------------------------------ 本机网关（AcpStdioAgent --listen）

/**
 * 以网关模式启动电脑上的运行时。stdin/stdout 是控制通道（每行一条 JSON）。
 * 返回 { control(cmd), port, status(), stop(), stderr }；port 在第一次 enable 之后才有。
 */
export async function startLocalGateway({ args = [] } = {}) {
  const child = spawnJavaAgent(["--listen=0", ...args]);
  const stderr = [];
  child.stderr.on("data", (d) => stderr.push(d.toString("utf8")));
  const waiting = [];
  let ready;
  const readyP = new Promise((r) => (ready = r));
  let buf = "";
  child.stdout.on("data", (d) => {
    buf += d.toString("utf8");
    for (let i = buf.indexOf("\n"); i >= 0; i = buf.indexOf("\n")) {
      const msg = JSON.parse(buf.slice(0, i));
      buf = buf.slice(i + 1);
      if (msg.event === "ready") ready();
      else waiting.shift()?.(msg);
    }
  });
  const exited = new Promise((resolve) => child.on("exit", (code) => resolve(code)));
  await Promise.race([readyP, exited.then((c) => Promise.reject(new Error(`gateway exited (${c}): ${stderr.join("")}`)))]);

  let chain = Promise.resolve();
  const gw = {
    port: 0,
    stderr,
    /** 发一条控制命令，等它的回复。 */
    control(cmd) {
      const p = chain.then(
        () =>
          new Promise((resolve) => {
            waiting.push(resolve);
            child.stdin.write(JSON.stringify(cmd) + "\n");
          }),
      );
      chain = p.catch(() => {});
      return p.then((reply) => {
        if (reply.port) gw.port = reply.port;
        if (!reply.ok) throw new Error(`gateway ${cmd.op} failed: ${reply.error}`);
        return reply;
      });
    },
    status() {
      return gw.control({ op: "status" });
    },
    async stop() {
      child.stdin.end();
      const timer = setTimeout(() => child.kill("SIGKILL"), 10_000);
      const code = await exited;
      clearTimeout(timer);
      return code;
    },
  };
  return gw;
}

// ------------------------------------------------------------------ 设备（adb）

export function adbPath() {
  if (process.env.ADB) return process.env.ADB;
  for (const root of [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT]) {
    if (root && existsSync(path.join(root, "platform-tools", "adb"))) return path.join(root, "platform-tools", "adb");
  }
  return "adb";
}

/** debug 包的测试入口：adb shell am broadcast … DesktopGatewayDebugReceiver --es op <op>。返回 result data（JSON）。 */
export function deviceControl(serial, op, longExtras = {}) {
  const args = ["-s", serial, "shell", "am", "broadcast", "-f", "32", "-n", "org.agentos.app/.agent.DesktopGatewayDebugReceiver", "--es", "op", op];
  for (const [k, v] of Object.entries(longExtras)) args.push("--el", k, String(v));
  const out = execFileSync(adbPath(), args, { encoding: "utf8" });
  const m = /data="(.*)"\s*$/m.exec(out);
  if (!m) throw new Error(`no result data from the debug receiver (is the debug APK installed?):\n${out}`);
  const reply = JSON.parse(m[1]);
  if (!reply.ok) throw new Error(`device ${op} failed: ${reply.error}`);
  return reply;
}

/** 设备上的测试模型来源（C 的 tests/device/acp-channel inapp 里 FakeModel：自定义 Anthropic Messages 端点）。 */
export const DEVICE_MODEL = { port: 18787, baseUrl: "http://127.0.0.1:18787", model: "fake-model", key: "agtest-fake-model-key" };

function deviceModelReady(status) {
  return status.modelUsable && status.modelBaseUrl === DEVICE_MODEL.baseUrl && status.modelId === DEVICE_MODEL.model;
}

/**
 * 让手机上的模型来源指向测试端点。C4 之后 :agent 的 Agent 循环是真实 Pi，模型请求真的发出去；
 * 借 C 的设备测试执行器（tests/device/acp-channel/inapp 的 AgentScenarioActivity，debug 包里有）设置：它在跑任何
 * 非 BYOK 场景之前调用 ensureTestModel（baseUrl、模型、测试 key 都在它的代码里，不经 adb 命令行）。已经是它时什么都不做。
 */
export async function ensureDeviceTestModel(serial) {
  if (deviceModelReady(deviceControl(serial, "status"))) return false;
  execFileSync(adbPath(), [
    "-s", serial, "shell", "am", "start", "-W", "-n", "org.agentos.app/org.agentos.test.acp.inapp.AgentScenarioActivity",
    "--es", "scenario", "handshake", "--es", "run", `a6-model-${Date.now()}`, "--es", "args", "'{}'",
  ], { encoding: "utf8" });
  await until(() => deviceModelReady(deviceControl(serial, "status")), 30_000, "the device test model to be configured");
  return true;
}

/**
 * 设备模式的前置条件：模拟 F2 首次引导里用户已允许“忽略电池优化”。这样 :agent 从后台（debug 广播打开开关、有任务时）
 * 也能进入前台服务（C6：电脑端接入打开期间；W6：有任务期间），不会被 cached-apps freezer 冻结。没有这一步时系统拒绝
 * 后台启动前台服务（logcat：Background started FGS: Disallowed），空闲的 :agent 约 10 秒后被冻结，用例会卡住。
 * 真机上还可以由 root 监督进程 promote（S2 契约 a）；模拟器上没有监督进程。幂等。
 */
export function prepareDevice(serial) {
  execFileSync(adbPath(), ["-s", serial, "shell", "dumpsys", "deviceidle", "whitelist", "+org.agentos.app"], { encoding: "utf8" });
}

/**
 * 设备模式的模型端点：电脑上起 B 的 FakeModelServer（FakeModelServerMain，按 {"fake":…} 指令回应），
 * `adb reverse tcp:18787 tcp:<端口>` 映射到手机，再确保手机上的模型来源指向它。返回 { port, stop() }。
 */
export async function startDeviceModel(serial) {
  prepareDevice(serial);
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", "java") : "java";
  const child = spawn(java, ["-cp", agentClasspath(), "org.agentos.runtime.testing.FakeModelServerMain", `--key=${DEVICE_MODEL.key}`], {
    stdio: ["pipe", "pipe", "pipe"],
  });
  const stderr = [];
  child.stderr.on("data", (d) => stderr.push(d.toString("utf8")));
  const port = await new Promise((resolve, reject) => {
    let buf = "";
    child.stdout.on("data", (d) => {
      buf += d.toString("utf8");
      const nl = buf.indexOf("\n");
      if (nl >= 0) resolve(JSON.parse(buf.slice(0, nl)).port);
    });
    child.once("exit", (code) => reject(new Error(`FakeModelServerMain exited (${code}): ${stderr.join("")}`)));
  });
  execFileSync(adbPath(), ["-s", serial, "reverse", `tcp:${DEVICE_MODEL.port}`, `tcp:${port}`]);
  await ensureDeviceTestModel(serial);
  return {
    port,
    stderr,
    async stop() {
      try {
        execFileSync(adbPath(), ["-s", serial, "reverse", "--remove", `tcp:${DEVICE_MODEL.port}`]);
      } catch {
        // 已经没有了
      }
      child.stdin.end();
      await new Promise((resolve) => {
        const timer = setTimeout(() => {
          child.kill("SIGKILL");
          resolve();
        }, 5_000);
        child.once("exit", () => {
          clearTimeout(timer);
          resolve();
        });
      });
    },
  };
}

/** adb forward tcp:0 localabstract:agentos-acp，返回本机端口和移除函数。 */
export function deviceForward(serial) {
  const port = Number.parseInt(execFileSync(adbPath(), ["-s", serial, "forward", "tcp:0", "localabstract:agentos-acp"], { encoding: "utf8" }).trim(), 10);
  return { port, remove: () => execFileSync(adbPath(), ["-s", serial, "forward", "--remove", `tcp:${port}`]) };
}

// ------------------------------------------------------------------ 原始连接（检查握手，不经过桥接）

/**
 * 直接连到网关的 TCP 端口，按行收发。next() 在连接关闭时返回 null。
 * 连不上（没有监听）时 connected 为 false。
 */
export async function rawConnect(port, host = "127.0.0.1") {
  const socket = net.connect({ host, port });
  const connected = await new Promise((resolve) => {
    socket.once("connect", () => resolve(true));
    socket.once("error", () => resolve(false));
  });
  const lines = [];
  const waiters = [];
  let buf = "";
  let closed = !connected;
  let bytes = 0;
  const flush = () => {
    while (waiters.length && (lines.length || closed)) waiters.shift()(lines.length ? lines.shift() : null);
  };
  socket.on("data", (d) => {
    bytes += d.length;
    buf += d.toString("utf8");
    for (let i = buf.indexOf("\n"); i >= 0; i = buf.indexOf("\n")) {
      lines.push(JSON.parse(buf.slice(0, i)));
      buf = buf.slice(i + 1);
    }
    flush();
  });
  socket.on("error", () => {});
  socket.on("close", () => {
    closed = true;
    flush();
  });
  return {
    connected,
    get bytes() {
      return bytes;
    },
    send(...out) {
      socket.write(out.map((l) => (typeof l === "string" ? l : JSON.stringify(l)) + "\n").join(""));
    },
    next(timeoutMs = 15_000) {
      return new Promise((resolve, reject) => {
        const waiter = (v) => {
          clearTimeout(timer);
          resolve(v);
        };
        // 超时的等待者要从队列里拿掉，否则之后到达的行会交给它、丢掉
        const timer = setTimeout(() => {
          const i = waiters.indexOf(waiter);
          if (i >= 0) waiters.splice(i, 1);
          reject(new Error("no line and no close within timeout"));
        }, timeoutMs);
        waiters.push(waiter);
        flush();
      });
    },
    pair(params) {
      this.send({ jsonrpc: "2.0", id: 0, method: "_org.agentos/pair", params: { version: 1, ...params } });
      return this.next();
    },
    close() {
      socket.destroy();
    },
  };
}

// ------------------------------------------------------------------ 其他

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
  while (!(await condition())) {
    if (Date.now() - start > timeoutMs) throw new Error(`timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 10));
  }
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export const PROTOCOL_VERSION = acp.PROTOCOL_VERSION;
