#!/usr/bin/env node
// AgentOS ACP bridge：电脑上的 stdio ↔ 手机上 AgentOS 的电脑端网关（抽象 socket agentos-acp）。
//
// 以子进程方式启动 Agent 的 ACP 客户端（Zed、各种 CLI）把它当作 Agent 命令即可：
//   acp-bridge pair 482913      # 第一次：用手机上显示的一次性配对码配对，令牌存到本机
//   acp-bridge                  # 之后：自动 adb forward、用令牌握手，然后 stdin/stdout 就是 ACP
//
// 协议见 core/protocol/acp-mapping.md 第 10 节。只用 Node 自带模块，没有依赖。
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";

const VERSION = "0.1.0";
const SOCKET_NAME = "agentos-acp";
const PAIR_METHOD = "_org.agentos/pair";
const MAX_RESPONSE_LINE = 65_536;

const EXIT = { OK: 0, FAILURE: 1, USAGE: 2, AUTH: 3, DEVICE: 4, NOT_LISTENING: 5, CLOSED_BY_PHONE: 6 };

const USAGE = `acp-bridge ${VERSION} — stdio ↔ AgentOS on an Android phone (adb forward → localabstract:${SOCKET_NAME})

Usage:
  acp-bridge [options]              bridge stdin/stdout to the phone's ACP agent
  acp-bridge pair <code> [options]  pair with the one-time code shown on the phone, store the token, exit
  acp-bridge unpair [options]       forget the stored token for this device

Options:
  -s, --serial <serial>    adb device (default: $ANDROID_SERIAL, or the only connected device)
      --port <n>           local TCP port for adb forward (default: 0, adb picks a free one)
      --code <code>        one-time pairing code for this connection (or $AGENTOS_PAIRING_CODE)
      --connect <host:port>  connect to this TCP address instead of using adb (tests, remote forwards)
      --state <file>       token store (default: $AGENTOS_BRIDGE_STATE or ~/.config/agentos/acp-bridge.json)
      --adb <path>         adb executable (default: $ADB, $ANDROID_HOME/platform-tools/adb, or adb on PATH)
      --label <name>       name shown on the phone for this pairing (default: acp-bridge@<hostname>)
      --timeout <ms>       handshake timeout (default: 10000)
  -q, --quiet              only print errors
  -h, --help, --version

stdout carries nothing but the agent's ACP messages; all diagnostics go to stderr.
Exit codes: 0 ok, 2 usage, 3 not paired / pairing refused, 4 adb or device problem,
5 the phone is not listening (desktop access off, or AgentOS not running), 6 connection closed by the phone.`;

class BridgeError extends Error {
  constructor(exitCode, message) {
    super(message);
    this.exitCode = exitCode;
  }
}

// ------------------------------------------------------------------ 参数

function parseArgs(argv) {
  const opts = { positional: [] };
  const takes = new Set(["serial", "port", "code", "connect", "state", "adb", "label", "timeout"]);
  const alias = { s: "serial", q: "quiet", h: "help" };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--") {
      opts.positional.push(...argv.slice(i + 1));
      break;
    }
    let name;
    let value;
    if (a.startsWith("--")) {
      [name, value] = a.slice(2).split(/=(.*)/s, 2);
    } else if (a.startsWith("-") && a.length === 2) {
      name = alias[a[1]];
      if (!name) throw new BridgeError(EXIT.USAGE, `unknown option ${a}`);
    } else {
      opts.positional.push(a);
      continue;
    }
    if (takes.has(name)) {
      if (value === undefined) {
        value = argv[++i];
        if (value === undefined) throw new BridgeError(EXIT.USAGE, `--${name} needs a value`);
      }
      opts[name] = value;
    } else if (["quiet", "help", "version"].includes(name)) {
      opts[name] = true;
    } else {
      throw new BridgeError(EXIT.USAGE, `unknown option --${name}`);
    }
  }
  return opts;
}

// ------------------------------------------------------------------ 令牌存储

function defaultStatePath() {
  if (process.env.AGENTOS_BRIDGE_STATE) return process.env.AGENTOS_BRIDGE_STATE;
  if (process.platform === "win32" && process.env.APPDATA) return path.join(process.env.APPDATA, "agentos", "acp-bridge.json");
  const base = process.env.XDG_CONFIG_HOME || path.join(os.homedir(), ".config");
  return path.join(base, "agentos", "acp-bridge.json");
}

function loadState(file) {
  try {
    const s = JSON.parse(fs.readFileSync(file, "utf8"));
    if (s && typeof s === "object" && s.devices && typeof s.devices === "object") return s;
  } catch {
    // 没有或读不出来：当作没有配对过
  }
  return { version: 1, devices: {} };
}

function saveState(file, state) {
  fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
  const tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(state, null, 2) + "\n", { mode: 0o600 });
  fs.renameSync(tmp, file);
  try {
    fs.chmodSync(file, 0o600);
  } catch {
    // Windows
  }
}

// ------------------------------------------------------------------ adb

function adbPath(opts) {
  if (opts.adb) return opts.adb;
  if (process.env.ADB) return process.env.ADB;
  for (const root of [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT]) {
    if (!root) continue;
    const p = path.join(root, "platform-tools", process.platform === "win32" ? "adb.exe" : "adb");
    if (fs.existsSync(p)) return p;
  }
  return "adb";
}

function runAdb(adb, args) {
  const r = spawnSync(adb, args, { encoding: "utf8" });
  if (r.error) throw new BridgeError(EXIT.DEVICE, `cannot run ${adb}: ${r.error.message}`);
  return r;
}

function resolveSerial(adb, opts) {
  if (opts.serial) return opts.serial;
  if (process.env.ANDROID_SERIAL) return process.env.ANDROID_SERIAL;
  const r = runAdb(adb, ["devices"]);
  const devices = r.stdout
    .split("\n")
    .slice(1)
    .map((l) => l.trim().split(/\s+/))
    .filter((f) => f.length >= 2 && f[1] === "device")
    .map((f) => f[0]);
  if (devices.length === 1) return devices[0];
  if (devices.length === 0) throw new BridgeError(EXIT.DEVICE, "no device: connect the phone with USB debugging on (adb devices shows nothing)");
  throw new BridgeError(EXIT.DEVICE, `several devices (${devices.join(", ")}): pass --serial or set ANDROID_SERIAL`);
}

function forward(adb, serial, port) {
  const r = runAdb(adb, ["-s", serial, "forward", `tcp:${port}`, `localabstract:${SOCKET_NAME}`]);
  if (r.status !== 0) throw new BridgeError(EXIT.DEVICE, `adb forward failed: ${(r.stderr || r.stdout).trim()}`);
  const actual = port === 0 ? Number.parseInt(r.stdout.trim(), 10) : port;
  if (!Number.isInteger(actual) || actual <= 0) throw new BridgeError(EXIT.DEVICE, `adb forward did not report a port: ${r.stdout.trim()}`);
  return actual;
}

// ------------------------------------------------------------------ 握手

function connect(host, port) {
  return new Promise((resolve, reject) => {
    const socket = net.connect({ host, port });
    socket.once("connect", () => {
      socket.removeAllListeners("error");
      resolve(socket);
    });
    socket.once("error", (e) =>
      reject(
        e.code === "ECONNREFUSED"
          ? new BridgeError(EXIT.NOT_LISTENING, `nothing is listening at ${host}:${port}`)
          : new BridgeError(EXIT.DEVICE, `cannot connect to ${host}:${port}: ${e.message}`),
      ),
    );
  });
}

/** 发出握手行，读第一行响应；返回 { response, leftover }。 */
function handshake(socket, params, timeoutMs) {
  return new Promise((resolve, reject) => {
    let buf = Buffer.alloc(0);
    const done = (err, value) => {
      clearTimeout(timer);
      socket.removeListener("data", onData);
      socket.removeListener("end", onEnd);
      socket.removeListener("close", onEnd);
      socket.removeListener("error", onError);
      socket.pause();
      if (err) reject(err);
      else resolve(value);
    };
    const onData = (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      const nl = buf.indexOf(0x0a);
      if (nl < 0) {
        if (buf.length > MAX_RESPONSE_LINE * 4) done(new BridgeError(EXIT.FAILURE, "the handshake response is too long"));
        return;
      }
      let response;
      try {
        response = JSON.parse(buf.subarray(0, nl).toString("utf8"));
      } catch {
        return done(new BridgeError(EXIT.FAILURE, "the phone answered the handshake with something that is not JSON"));
      }
      done(null, { response, leftover: buf.subarray(nl + 1) });
    };
    const onEnd = () =>
      done(
        new BridgeError(
          EXIT.NOT_LISTENING,
          "the phone closed the connection before answering: desktop access is off, AgentOS (:agent) is not running, or another connection is in the way",
        ),
      );
    const onError = (e) => done(new BridgeError(EXIT.DEVICE, `connection error during the handshake: ${e.message}`));
    const timer = setTimeout(() => done(new BridgeError(EXIT.FAILURE, `no handshake response within ${timeoutMs} ms`)), timeoutMs);
    socket.on("data", onData);
    socket.once("end", onEnd);
    socket.once("close", onEnd);
    socket.once("error", onError);
    socket.write(JSON.stringify({ jsonrpc: "2.0", id: 0, method: PAIR_METHOD, params }) + "\n");
  });
}

const HINTS = {
  disabled: "turn on desktop access on the phone (Settings → Desktop access)",
  pairing_required: "pair first: acp-bridge pair <code shown on the phone>",
  invalid_code: "check the code, or generate a new one on the phone",
  code_expired: "generate a new code on the phone",
  too_many_attempts: "generate a new code on the phone",
  invalid_token: "this computer is no longer paired; run: acp-bridge pair <new code shown on the phone>",
};

// ------------------------------------------------------------------ 主流程

async function main(argv) {
  const opts = parseArgs(argv);
  if (opts.help) {
    process.stdout.write(USAGE + "\n");
    return EXIT.OK;
  }
  if (opts.version) {
    process.stdout.write(VERSION + "\n");
    return EXIT.OK;
  }
  const say = (msg) => {
    if (!opts.quiet) process.stderr.write(`acp-bridge: ${msg}\n`);
  };
  const [command = "bridge", ...rest] = opts.positional;
  if (!["bridge", "pair", "unpair"].includes(command)) throw new BridgeError(EXIT.USAGE, `unknown command ${command}\n\n${USAGE}`);
  if (command === "pair") {
    if (rest.length !== 1 && !opts.code) throw new BridgeError(EXIT.USAGE, "usage: acp-bridge pair <code>");
    opts.code = rest[0] ?? opts.code;
  }
  const timeoutMs = opts.timeout ? Number.parseInt(opts.timeout, 10) : 10_000;

  // 目标：adb 设备或直接的 TCP 地址
  let target;
  if (opts.connect) {
    const m = /^(.+):(\d+)$/.exec(opts.connect);
    if (!m) throw new BridgeError(EXIT.USAGE, "--connect expects host:port");
    target = { key: `tcp:${m[1]}:${m[2]}`, host: m[1], port: Number(m[2]) };
  } else {
    const adb = adbPath(opts);
    const serial = resolveSerial(adb, opts);
    target = { key: `adb:${serial}`, adb, serial };
  }

  const statePath = opts.state || defaultStatePath();
  const state = loadState(statePath);
  if (command === "unpair") {
    const had = Boolean(state.devices[target.key]);
    delete state.devices[target.key];
    saveState(statePath, state);
    say(had ? `forgot the token for ${target.key}` : `no token stored for ${target.key}`);
    return EXIT.OK;
  }

  const code = opts.code || process.env.AGENTOS_PAIRING_CODE;
  const stored = state.devices[target.key];
  if (!code && !stored?.token) {
    throw new BridgeError(
      EXIT.AUTH,
      `not paired with ${target.key}. Turn on desktop access on the phone, then run: acp-bridge pair <code shown on the phone>`,
    );
  }

  // adb forward（退出时移除）
  if (!target.host) {
    const port = forward(target.adb, target.serial, opts.port ? Number.parseInt(opts.port, 10) : 0);
    target.host = "127.0.0.1";
    target.port = port;
    const remove = () => spawnSync(target.adb, ["-s", target.serial, "forward", "--remove", `tcp:${port}`]);
    process.once("exit", remove);
    say(`adb -s ${target.serial} forward tcp:${port} localabstract:${SOCKET_NAME}`);
  }

  const socket = await connect(target.host, target.port);
  const params = { version: 1, label: opts.label || `acp-bridge@${os.hostname()}` };
  if (code) params.code = code;
  else params.token = stored.token;
  const { response, leftover } = await handshake(socket, params, timeoutMs);

  if (response.error) {
    const reason = response.error.data?.details?.reason ?? "unknown";
    if (reason === "invalid_token" && !code) {
      delete state.devices[target.key];
      saveState(statePath, state);
    }
    socket.destroy();
    const hint = HINTS[reason] ? `\n  → ${HINTS[reason]}` : "";
    throw new BridgeError(EXIT.AUTH, `the phone refused the connection (${reason}): ${response.error.message}${hint}`);
  }
  const result = response.result ?? {};
  if (result.token) {
    state.devices[target.key] = { token: result.token, pairingId: result.pairingId, label: params.label, pairedAt: new Date().toISOString() };
    saveState(statePath, state);
    say(`paired with ${target.key} (${result.pairingId}); token stored in ${statePath}`);
  }

  if (command === "pair") {
    socket.end();
    return EXIT.OK;
  }

  // 桥接：之后只转发字节
  say(`connected to ${target.key}`);
  return await new Promise((resolve) => {
    let stdinEnded = false;
    const finish = (codeOut) => process.stdout.write("", () => resolve(codeOut));
    if (leftover.length > 0) process.stdout.write(leftover);
    socket.pipe(process.stdout, { end: false });
    process.stdin.pipe(socket);
    process.stdin.once("end", () => {
      stdinEnded = true;
    });
    process.stdout.on("error", () => {
      // 客户端先退出了
      socket.destroy();
      resolve(EXIT.OK);
    });
    socket.once("close", () => {
      if (!stdinEnded) say("the phone closed the connection");
      finish(stdinEnded ? EXIT.OK : EXIT.CLOSED_BY_PHONE);
    });
    socket.on("error", (e) => say(`connection error: ${e.message}`));
    socket.resume();
  });
}

for (const sig of ["SIGINT", "SIGTERM", "SIGHUP"]) process.once(sig, () => process.exit(EXIT.FAILURE));

main(process.argv.slice(2)).then(
  (code) => process.exit(code),
  (e) => {
    process.stderr.write(`acp-bridge: ${e.message}\n`);
    process.exit(e instanceof BridgeError ? e.exitCode : EXIT.FAILURE);
  },
);
