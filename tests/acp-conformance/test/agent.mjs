// 启动被测的 AgentOS 运行时，用官方 TypeScript ACP 客户端连上。三种目标（AGENTOS_ACP_TARGETS，逗号分隔）：
//
// - stdio：电脑上的运行时（org.agentos.runtime.testing.AcpStdioAgent）经 stdin/stdout；
// - gateway：同一个运行时以网关模式运行（与手机上相同的开关、配对码、令牌、握手和按行传输，监听本机 TCP 端口代替抽象 socket），
//   客户端经 tools/acp-bridge（--connect）连进去；
// - device：手机上 AgentOS 的 :agent（AGENTOS_ACP_DEVICE=<adb serial>），经 tools/acp-bridge（adb forward → localabstract:agentos-acp）。
//   开关和配对码经 debug 包的测试入口（DesktopGatewayDebugReceiver）操作。
//
// 类路径来自 Gradle 任务 :core:runtime:acpConformanceClasspath（core/runtime/build/acp-conformance/classpath.txt）；
// 文件不存在、或 core/runtime 的源码和构建文件比它新（合 main、切分支之后）时自动调用它（也就重新编译了），
// AGENTOS_ACP_REBUILD=1 强制调用。也可以用环境变量 AGENTOS_ACP_CLASSPATH 直接给出类路径。
// Java 用 JAVA_HOME 下的（JDK 21），没有就用 PATH 里的 java。
//
// 设备模式开始前（prepareDevice）清掉上次中断留下的开关、配对、adb reverse / forward，加上电池优化豁免；结束时恢复。
// 中断（SIGINT / SIGTERM / SIGHUP）时写明哪一例、跑了多久、设备状态（经 test/run.mjs 打印），然后结束子进程、恢复设备。
import { execFileSync, spawn } from "node:child_process";
import { appendFileSync, existsSync, mkdtempSync, readdirSync, readFileSync, statSync, unlinkSync, utimesSync, writeFileSync } from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { PassThrough, Readable, Writable } from "node:stream";
import { afterEach, beforeEach } from "node:test";
import { fileURLToPath } from "node:url";
import * as acp from "@agentclientprotocol/sdk";

const here = path.dirname(fileURLToPath(import.meta.url));
export const repoRoot = path.resolve(here, "../../..");
const classpathFile = path.join(repoRoot, "core/runtime/build/acp-conformance/classpath.txt");
export const bridgeScript = path.join(repoRoot, "tools/acp-bridge/acp-bridge.mjs");

export const deviceSerial = process.env.AGENTOS_ACP_DEVICE || null;

// ------------------------------------------------------------------ 子进程与中断

const children = new Set();
const cleanups = [];

/** 登记一个子进程：中断时一并结束；它先退出时往它的 stdin 写（EPIPE）不能把测试进程带走。 */
function track(child, what) {
  children.add(child);
  child.once("exit", () => children.delete(child));
  child.stdin?.on("error", (e) => console.error(`[acp-conformance] ${what}: stdin ${e.code ?? e.message}`));
  return child;
}

/** 登记一个同步的清理动作（恢复设备状态等），中断或进程退出时执行；返回取消登记的函数。 */
export function onCleanup(fn) {
  cleanups.push(fn);
  return () => {
    const i = cleanups.indexOf(fn);
    if (i >= 0) cleanups.splice(i, 1);
  };
}

function cleanupNow(reason) {
  for (const c of children) {
    try {
      c.kill("SIGTERM"); // acp-bridge 收到 SIGTERM 会移除自己的 adb forward
    } catch {
      // 已经退出
    }
  }
  children.clear();
  while (cleanups.length) {
    const fn = cleanups.pop();
    try {
      fn();
    } catch (e) {
      console.error(`[acp-conformance] cleanup after ${reason} failed: ${e.message}`);
    }
  }
}

// 正在跑的用例（根上的钩子对每个文件里所有 describe 下的用例都生效）
let currentTest = null;
let lastFinished = null;
beforeEach((t) => {
  currentTest = { name: t.fullName ?? t.name, start: Date.now(), warned: 0 };
});
afterEach((t) => {
  lastFinished = t.fullName ?? t.name;
  currentTest = null;
});

function whereWeAre() {
  if (currentTest) return `while running "${currentTest.name}" (for ${Math.round((Date.now() - currentTest.start) / 1000)}s)`;
  return `outside a test (in a before/after hook${lastFinished ? `; last finished "${lastFinished}"` : ", before the first test"})`;
}

/** 写给 test/run.mjs：node --test 被中断后不再转出测试文件进程的 stderr，由它在 runner 退出后打印这份报告。 */
function report(line) {
  console.error(line);
  if (process.env.AGENTOS_ACP_REPORT) {
    try {
      appendFileSync(process.env.AGENTOS_ACP_REPORT, line + "\n");
    } catch {
      // 报告只是辅助
    }
  }
}

const testFile = path.basename(process.argv[1] ?? "test");
let interrupted = null;
for (const sig of ["SIGINT", "SIGTERM", "SIGHUP"]) {
  // 用 on 不用 once：Ctrl-C 或按进程组发信号时，这个进程先直接收到一次，runner 随后再转发一次（SIGTERM）；
  // once 的监听移除后第二个信号走默认动作，进程在清理中途被杀，设备状态恢复不了
  process.on(sig, () => {
    if (interrupted) return;
    interrupted = sig;
    // node:test 的“Interrupted while running: <文件>”只说明这个文件被中断，这里写明哪一例、跑了多久、设备当时的状态。
    // 第一行马上写（run.mjs 据此知道要等清理）；设备诊断要跑几条 adb，放在后面
    report(`[acp-conformance] INTERRUPTED ${testFile} by ${sig} ${whereWeAre()}`);
    if (deviceSerial) report(`[acp-conformance]   device ${deviceSerial}: ${deviceDiagnostics(deviceSerial)}`);
    report(`[acp-conformance]   stopping ${children.size} child process(es), running ${cleanups.length} cleanup step(s)`);
    cleanupNow(sig);
    report(`[acp-conformance] CLEANED UP ${testFile}`);
    process.exit(sig === "SIGINT" ? 130 : 143);
  });
}
process.once("exit", () => cleanupNow("exit"));

// 一例跑得异常久（多半是在等设备）时先把状态打出来：中断后就看不到了，外层工具超时用 SIGKILL 时更是什么都留不下
const SLOW_TEST_MS = 90_000;
setInterval(() => {
  if (!currentTest) return;
  const secs = Math.round((Date.now() - currentTest.start) / 1000);
  if (secs * 1000 < SLOW_TEST_MS * (currentTest.warned + 1)) return;
  currentTest.warned++;
  console.error(
    `[acp-conformance] still running "${currentTest.name}" after ${secs}s` +
      (deviceSerial ? `; device ${deviceSerial}: ${deviceDiagnostics(deviceSerial)}` : ""),
  );
}, 10_000).unref();

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
  // scope：session/new 的 toolScope（docs/third-party-acp.md 4.5）。手机上的 :agent 在设备脚本里单独验证（第三方 App 通道，整合人驱动）
  return { tools: desktop, consent: desktop, jev: desktop, scope: desktop };
}

/** 设备模式下跳过的原因（报告里逐条列出）。 */
export const SKIP = {
  tools: "手机上 M1 没有工具：插件工具归 W14（Extension Host），MCP 工具归 W15；电脑上用 AcpStdioAgent 注册的 add / send_note",
  consent: "手机上 M1 没有风险策略与确认界面（W16）；电脑上的确认由 AcpStdioAgent 自动通过",
  jev: "--jev=first 只在电脑上的 AcpStdioAgent 里有；手机上的 :agent 没有配置 Jev（RuntimeConfig.jev 为空，接线还没有分配工作包）",
  scope: "toolScope 在手机上的 :agent 里由第三方 App 通道的设备用例验证（需要 C 的 CallerRegistry 与真实第三方身份）；这里的客户端是电脑端 / AgentOS 自己",
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

/** 类路径依赖的输入：core/runtime 的源码与构建文件。 */
const CLASSPATH_INPUTS = [
  "core/runtime/src/main",
  "core/runtime/src/testFixtures",
  "core/runtime/build.gradle.kts",
  "gradle/libs.versions.toml",
  "build.gradle.kts",
  "settings.gradle.kts",
];

function newestMtime(p) {
  if (!existsSync(p)) return 0;
  const st = statSync(p);
  if (!st.isDirectory()) return st.mtimeMs;
  let newest = st.mtimeMs;
  for (const name of readdirSync(p)) newest = Math.max(newest, newestMtime(path.join(p, name)));
  return newest;
}

function classpathStale() {
  if (!existsSync(classpathFile)) return "missing";
  const built = statSync(classpathFile).mtimeMs;
  const changed = CLASSPATH_INPUTS.find((p) => newestMtime(path.join(repoRoot, p)) > built);
  return changed ? `${changed} changed` : null;
}

export function agentClasspath() {
  if (process.env.AGENTOS_ACP_CLASSPATH) return process.env.AGENTOS_ACP_CLASSPATH;
  if (cachedClasspath) return cachedClasspath;
  const why = process.env.AGENTOS_ACP_REBUILD === "1" ? "AGENTOS_ACP_REBUILD=1" : classpathStale();
  if (why) {
    console.error(`[acp-conformance] rebuilding the desktop agent classpath (${why})`);
    const gradlew = path.join(repoRoot, process.platform === "win32" ? "gradlew.bat" : "gradlew");
    execFileSync(gradlew, ["-q", ":core:runtime:acpConformanceClasspath"], { cwd: repoRoot, stdio: "inherit" });
    // 任务是 up-to-date 时不重写文件：更新时间戳，免得下一个测试文件再构建一次
    const now = new Date();
    utimesSync(classpathFile, now, now);
  }
  cachedClasspath = readFileSync(classpathFile, "utf8").trim();
  return cachedClasspath;
}

function spawnJavaAgent(args) {
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", "java") : "java";
  const child = spawn(java, ["-Dkotlin-logging-to-jul=true", "-cp", agentClasspath(), "org.agentos.runtime.testing.AcpStdioAgent", ...coreArgs(), ...args], {
    stdio: ["pipe", "pipe", "pipe"],
  });
  return track(child, "AcpStdioAgent");
}

export function tempStateFile() {
  return path.join(mkdtempSync(path.join(os.tmpdir(), "acp-bridge-")), "state.json");
}

/** 启动 tools/acp-bridge（子进程），参数原样传入。 */
export function spawnBridge(args) {
  return track(spawn(process.execPath, [bridgeScript, ...args], { stdio: ["pipe", "pipe", "pipe"] }), "acp-bridge");
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

const APP_PACKAGE = "org.agentos.app";

function adbQuiet(serial, ...args) {
  try {
    return execFileSync(adbPath(), ["-s", serial, ...args], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], timeout: 10_000 });
  } catch {
    return "";
  }
}

/**
 * 设备当时的状态，一行：AgentOS 各进程（pid、是否被冻结、进程状态）、:agent 是否前台服务、电池优化豁免、
 * 电脑端接入开关（经 debug 入口，:agent 被冻结时可能拿不到）、这台设备上的 adb reverse / forward。每条 adb 命令最多 10 秒。
 */
export function deviceDiagnostics(serial) {
  const state = adbQuiet(serial, "get-state").trim();
  if (state !== "device") return `adb state ${state || "unknown (disconnected?)"}`;
  const parts = [];
  const procs = [];
  let proc = null;
  for (const line of adbQuiet(serial, "shell", "dumpsys", "activity", "processes", APP_PACKAGE).split("\n")) {
    const app = /\*APP\* UID \d+ ProcessRecord\{\S+ (\d+):([^/\s]+)/.exec(line);
    if (app) {
      proc = { pid: app[1], name: app[2] };
      procs.push(proc);
      continue;
    }
    if (!proc) continue;
    const frozen = /isFrozen=(\w+)/.exec(line);
    if (frozen) proc.frozen = frozen[1];
    const ps = /\bcurProcState=(\d+)/.exec(line);
    if (ps) proc.procState = ps[1];
    const why = new RegExp(`\\b${proc.pid}:${proc.name.replace(/[.:]/g, "\\$&")}/\\S+ \\(([^)]+)\\)`).exec(line);
    if (why && !proc.why) proc.why = why[1];
  }
  const uniq = [...new Map(procs.map((p) => [p.pid, p])).values()];
  parts.push(
    uniq.length
      ? uniq.map((p) => `${p.name} pid ${p.pid} frozen=${p.frozen ?? "?"} procState=${p.procState ?? "?"}${p.why ? ` (${p.why})` : ""}`).join(", ")
      : "no AgentOS process",
  );
  parts.push(`foreground service=${/isForeground=true/.test(adbQuiet(serial, "shell", "dumpsys", "activity", "services", APP_PACKAGE))}`);
  parts.push(`battery optimization exempt=${adbQuiet(serial, "shell", "cmd", "deviceidle", "whitelist").includes(APP_PACKAGE)}`);
  const status = /data="(.*)"\s*$/m.exec(
    adbQuiet(serial, "shell", "am", "broadcast", "-f", "32", "-n", `${APP_PACKAGE}/.agent.DesktopGatewayDebugReceiver`, "--es", "op", "status"),
  );
  try {
    const s = JSON.parse(status?.[1] ?? "");
    parts.push(
      s.ok
        ? `desktop access enabled=${s.enabled} listening=${s.listening} pairings=${s.pairings?.length ?? "?"} connections=${s.connections?.length ?? "?"}`
        : `desktop access status failed: ${s.error}`,
    );
  } catch {
    parts.push("desktop access status unavailable");
  }
  // reverse --list 已经限定在这台设备，第一列是传输名（host-16、UsbFfs…）；forward --list 列出所有设备，第一列是序列号
  const reverses = adbQuiet(serial, "reverse", "--list")
    .split("\n")
    .map((l) => l.trim().split(/\s+/).slice(1).join(" "))
    .filter(Boolean);
  const forwards = adbQuiet(serial, "forward", "--list")
    .split("\n")
    .filter((l) => l.startsWith(`${serial} `))
    .map((l) => l.slice(serial.length + 1).trim());
  parts.push(`adb reverse [${reverses.join("; ")}]`);
  parts.push(`adb forward [${forwards.join("; ")}]`);
  return parts.join("; ");
}

/** 移除这台设备上指向手机网关（localabstract:agentos-acp）的 adb forward。 */
function removeGatewayForwards(serial) {
  for (const line of adbQuiet(serial, "forward", "--list").split("\n")) {
    const [s, local, remote] = line.trim().split(/\s+/);
    if (s === serial && remote === "localabstract:agentos-acp") adbQuiet(serial, "forward", "--remove", local);
  }
}

/**
 * 设备模式的准备，返回恢复函数（测试结束时调用；中断时由清理流程调用）。
 *
 * 1. 清掉上次中断可能留下的状态：电脑端接入开着、留着配对、adb reverse 18787、指向 agentos-acp 的 adb forward。
 * 2. 模拟 F2 首次引导里用户已允许“忽略电池优化”（整合人定 M1 靠它）：`cmd deviceidle whitelist +org.agentos.app`。
 *    这样 :agent 从后台（debug 广播打开开关、有任务时）也能进入前台服务（C6 / W6），不会被 cached-apps freezer 冻结。
 *    没有这一步时系统拒绝后台启动前台服务（logcat：Background started FGS: Disallowed … uidState: RCVR），用例会卡住。
 *    恢复时只移除测试加的（设备上本来就有这项豁免时保留）；是不是测试加的记在电脑的临时目录里，强行结束后下次运行照样认得。
 */
export function prepareDevice(serial) {
  for (const op of ["revoke_all", "disable"]) {
    try {
      deviceControl(serial, op);
    } catch (e) {
      console.error(`[acp-conformance] setup: ${op} failed: ${e.message}`);
    }
  }
  adbQuiet(serial, "reverse", "--remove", `tcp:${DEVICE_MODEL.port}`);
  removeGatewayForwards(serial);
  // 豁免是不是测试加的，记在电脑上（按设备）：上次运行加了豁免、没来得及移除就被强行结束时，这次仍然认作测试加的，结束时移除
  const marker = path.join(os.tmpdir(), `agentos-acp-battery-exemption-${serial.replace(/[^\w.-]/g, "_")}`);
  const exempt = adbQuiet(serial, "shell", "cmd", "deviceidle", "whitelist").includes(APP_PACKAGE);
  // AGENTOS_ACP_BATTERY_EXEMPTION=0：不加豁免，模拟用户没有允许“忽略电池优化”（复现 cached-apps freezer 的问题用）
  const withoutExemption = process.env.AGENTOS_ACP_BATTERY_EXEMPTION === "0";
  if (withoutExemption && exempt) console.error(`[acp-conformance] AGENTOS_ACP_BATTERY_EXEMPTION=0, but ${serial} already exempts ${APP_PACKAGE}`);
  const ours = !withoutExemption && (!exempt || existsSync(marker));
  if (ours) writeFileSync(marker, `added by tests/acp-conformance for ${serial}\n`);
  if (ours && !exempt) execFileSync(adbPath(), ["-s", serial, "shell", "cmd", "deviceidle", "whitelist", `+${APP_PACKAGE}`], { encoding: "utf8" });
  let done = false;
  const restore = () => {
    if (done) return;
    done = true;
    adbQuiet(serial, "reverse", "--remove", `tcp:${DEVICE_MODEL.port}`);
    // 用例自己建的转发（deviceForward）平时在 teardown 里移除，中断时 teardown 不会执行
    removeGatewayForwards(serial);
    try {
      deviceControl(serial, "disable");
    } catch {
      // 设备已断开
    }
    if (ours) {
      const removed = adbQuiet(serial, "shell", "cmd", "deviceidle", "whitelist", `-${APP_PACKAGE}`).includes("Removed");
      const list = removed ? "" : adbQuiet(serial, "shell", "cmd", "deviceidle", "whitelist");
      // 设备断开时 adb 什么都不返回，标记留着，下次再移除
      if (removed || (list && !list.includes(APP_PACKAGE))) {
        try {
          unlinkSync(marker);
        } catch {
          // 已经没有了
        }
      }
    }
  };
  const unregister = onCleanup(restore);
  return () => {
    unregister();
    restore();
  };
}

/**
 * 设备模式的模型端点：电脑上起 B 的 FakeModelServer（FakeModelServerMain，按 {"fake":…} 指令回应），
 * `adb reverse tcp:18787 tcp:<端口>` 映射到手机，再确保手机上的模型来源指向它。返回 { port, control(cmd), stop() }。
 */
export async function startDeviceModel(serial) {
  const restoreDevice = prepareDevice(serial);
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", "java") : "java";
  const child = track(
    spawn(java, ["-cp", agentClasspath(), "org.agentos.runtime.testing.FakeModelServerMain", `--key=${DEVICE_MODEL.key}`], {
      stdio: ["pipe", "pipe", "pipe"],
    }),
    "FakeModelServerMain",
  );
  const stderr = [];
  child.stderr.on("data", (d) => stderr.push(d.toString("utf8")));
  const waiting = [];
  const port = await new Promise((resolve, reject) => {
    let buf = "";
    child.stdout.on("data", (d) => {
      buf += d.toString("utf8");
      for (let i = buf.indexOf("\n"); i >= 0; i = buf.indexOf("\n")) {
        const msg = JSON.parse(buf.slice(0, i));
        buf = buf.slice(i + 1);
        if (msg.event === "ready") resolve(msg.port);
        else waiting.shift()?.(msg);
      }
    });
    child.once("exit", (code) => reject(new Error(`FakeModelServerMain exited (${code}): ${stderr.join("")}`)));
  });
  execFileSync(adbPath(), ["-s", serial, "reverse", `tcp:${DEVICE_MODEL.port}`, `tcp:${port}`]);
  await ensureDeviceTestModel(serial);
  let chain = Promise.resolve();
  return {
    port,
    stderr,
    /** 控制命令（FakeModelServerMain 的 stdin）：{op:"failNext",status,times,retryAfter?}、{op:"requests"}。 */
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
        if (!reply.ok) throw new Error(`fake model ${cmd.op} failed: ${reply.error}`);
        return reply;
      });
    },
    async stop() {
      restoreDevice();
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
