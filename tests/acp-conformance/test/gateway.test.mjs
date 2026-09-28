// 电脑端网关的配对与安全（W9，acp-mapping.md 第 10 节）：原始 TCP 连接检查握手，tools/acp-bridge 检查配对码和令牌。
//
// 目标：
// - local：电脑上的运行时以网关模式运行（AcpStdioAgent --listen，与手机上同一份 DesktopGatewayCore），每次都跑；
// - device：设了 AGENTOS_ACP_DEVICE=<adb serial> 时，手机上的 :agent（debug 包），经 adb forward → localabstract:agentos-acp。
import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  deviceControl,
  deviceForward,
  deviceSerial,
  PROTOCOL_VERSION,
  rawConnect,
  runBridge,
  sleep,
  startBridgeAgent,
  startLocalGateway,
  tempStateFile,
  until,
} from "./agent.mjs";

const AUTH_REQUIRED = -32000;
const initialize = { jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: 1, clientCapabilities: {} } };
const newSession = { jsonrpc: "2.0", id: 2, method: "session/new", params: { cwd: "/sdcard", mcpServers: [] } };

function localTarget() {
  let gw;
  return {
    name: "local",
    handshakeTimeoutMs: 500,
    async setup() {
      gw = await startLocalGateway({ args: ["--handshake-timeout-ms=500"] });
      const s = await gw.status();
      assert.equal(s.enabled, false, "desktop access is off by default");
      assert.equal(s.listening, false, "nothing listens while it is off");
      await gw.control({ op: "enable" });
    },
    control: (op, extra = {}) => gw.control({ op, ...extra }),
    port: () => gw.port,
    bridgeArgs: () => ["--connect", `127.0.0.1:${gw.port}`],
    async teardown() {
      await gw?.stop();
    },
  };
}

function deviceTarget(serial) {
  let fwd;
  return {
    name: `device ${serial}`,
    handshakeTimeoutMs: 10_000,
    async setup() {
      deviceControl(serial, "disable");
      deviceControl(serial, "enable");
      fwd = deviceForward(serial);
    },
    control: async (op, extra = {}) => deviceControl(serial, op, extra),
    port: () => fwd.port,
    bridgeArgs: () => ["--serial", serial],
    async teardown() {
      try {
        deviceControl(serial, "disable");
      } finally {
        fwd?.remove();
      }
    },
  };
}

const gatewayTargets = [localTarget(), ...(deviceSerial ? [deviceTarget(deviceSerial)] : [])];

function otherCode(code) {
  return String((Number(code) + 1) % 1_000_000).padStart(6, "0");
}

for (const t of gatewayTargets) {
  describe(`desktop gateway pairing (${t.name})`, () => {
    before(() => t.setup());
    after(() => t.teardown());

    test("while desktop access is off nothing answers and not a byte is sent", async () => {
      await t.control("disable");
      const raw = await rawConnect(t.port());
      // 电脑上：端口没有监听（连接被拒）；经 adb forward：adbd 接受了 TCP 连接，但设备上没人监听，立即关闭
      if (raw.connected) {
        assert.equal(await raw.next(), null);
        assert.equal(raw.bytes, 0);
      }
      await t.control("enable");
      const status = await t.control("status");
      assert.equal(status.enabled, true);
      assert.equal(status.listening, true);
    });

    test("an ACP request before pairing gets auth_required and the connection closes without processing anything", async () => {
      await t.control("pair");
      const raw = await rawConnect(t.port());
      raw.send(initialize, newSession);
      const reply = await raw.next();
      assert.equal(reply.id, 1, "answers the initialize id so an unpaired client fails fast");
      assert.equal(reply.error.code, AUTH_REQUIRED);
      assert.equal(reply.error.data.agentosCode, "auth_required");
      assert.equal(reply.error.data.details.reason, "pairing_required");
      assert.equal(await raw.next(), null, "closed; session/new is never answered");
      assert.equal((await t.control("status")).code.attemptsLeft, 5, "a non-pairing message does not burn an attempt");
    });

    test("a wrong code is refused, costs an attempt, and the ACP line right behind it is never answered", async () => {
      const { code } = await t.control("pair");
      const raw = await rawConnect(t.port());
      raw.send({ jsonrpc: "2.0", id: 0, method: "_org.agentos/pair", params: { version: 1, code: otherCode(code) } }, initialize);
      const reply = await raw.next();
      assert.equal(reply.id, 0);
      assert.equal(reply.error.data.details.reason, "invalid_code");
      assert.equal(await raw.next(), null);
      assert.equal((await t.control("status")).code.attemptsLeft, 4);
    });

    test("five wrong codes void the code", async () => {
      const { code } = await t.control("pair");
      const reasons = [];
      for (let i = 0; i < 5; i++) {
        const raw = await rawConnect(t.port());
        reasons.push((await raw.pair({ code: otherCode(code) })).error.data.details.reason);
        raw.close();
      }
      assert.deepEqual(reasons, ["invalid_code", "invalid_code", "invalid_code", "invalid_code", "too_many_attempts"]);
      const raw = await rawConnect(t.port());
      assert.equal((await raw.pair({ code })).error.data.details.reason, "invalid_code", "the right code no longer works");
    });

    test("a code expires", async () => {
      const { code } = await t.control("pair", { ttlMs: 300 });
      await sleep(800);
      const raw = await rawConnect(t.port());
      assert.equal((await raw.pair({ code })).error.data.details.reason, "code_expired");
    });

    test("acp-bridge pairs with the code once; the same code is refused afterwards", async () => {
      const { code } = await t.control("pair");
      const state = tempStateFile();
      const first = await runBridge(["pair", code, ...t.bridgeArgs(), "--state", state]);
      assert.equal(first.code, 0, first.stderr);
      const stored = Object.values(JSON.parse(readFileSync(state, "utf8")).devices);
      assert.equal(stored.length, 1);
      assert.match(stored[0].token, /^[A-Za-z0-9_-]{43}$/);
      assert.ok(!first.stderr.includes(stored[0].token), "the token is never printed");
      const second = await runBridge(["pair", code, ...t.bridgeArgs(), "--state", tempStateFile()]);
      assert.equal(second.code, 3, second.stderr);
      assert.match(second.stderr, /invalid_code/);
    });

    test("the stored token reconnects; turning desktop access off closes the session and voids the token", async () => {
      const { code } = await t.control("pair");
      const state = tempStateFile();
      assert.equal((await runBridge(["pair", code, ...t.bridgeArgs(), "--state", state])).code, 0);
      // 第二次连接不要配对码
      const agent = startBridgeAgent([...t.bridgeArgs(), "--state", state, "--quiet"]);
      const init = await agent.connection.initialize({ protocolVersion: PROTOCOL_VERSION, clientCapabilities: {} });
      assert.equal(init.protocolVersion, 1);
      const { sessionId } = await agent.connection.newSession({ cwd: "/sdcard", mcpServers: [] });
      assert.ok(sessionId);
      const status = await t.control("status");
      assert.ok(status.connections.length >= 1 || status.connections >= 1);

      await t.control("disable");
      const exit = await agent.exited;
      assert.equal(exit.code, 6, "the bridge reports that the phone closed the connection");
      await t.control("enable");
      const again = await runBridge([...t.bridgeArgs(), "--state", state]);
      assert.equal(again.code, 3, again.stderr);
      assert.match(again.stderr, /invalid_token/);
      assert.deepEqual(JSON.parse(readFileSync(state, "utf8")).devices, {}, "the dead token is forgotten");
    });

    test("an ACP line over 65,536 characters closes the connection", async () => {
      const { code } = await t.control("pair");
      const raw = await rawConnect(t.port());
      assert.ok((await raw.pair({ code })).result.token);
      raw.send(initialize);
      assert.equal((await raw.next()).id, 1);
      raw.send({ jsonrpc: "2.0", method: "session/cancel", params: { sessionId: "x".repeat(65_536) } });
      assert.equal(await raw.next(), null);
    });

    test("a silent connection is closed after the handshake timeout", async () => {
      const raw = await rawConnect(t.port());
      const t0 = Date.now();
      assert.equal(await raw.next(t.handshakeTimeoutMs + 10_000), null);
      const elapsed = Date.now() - t0;
      assert.ok(elapsed >= t.handshakeTimeoutMs - 200 && elapsed < t.handshakeTimeoutMs + 5_000, `closed after ${elapsed} ms`);
      await until(async () => (await t.control("status")).handshakeTimeouts >= 1, 5_000, "timeout counted");
    });
  });
}
