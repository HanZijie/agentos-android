// 手机上 AgentOS 的 :agent 经 adb forward（tools/acp-bridge → localabstract:agentos-acp）的设备专属检查（W9）。
// 设了 AGENTOS_ACP_DEVICE=<adb serial> 时运行；设备上要装 debug 包（开关和配对码经 DesktopGatewayDebugReceiver 操作）。
//
// C4 之后 :agent 的 Agent 循环是真实 Pi，模型是电脑上的 FakeModelServer（经 adb reverse 映射到手机的 127.0.0.1:18787），
// 通用的一致性用例在 conformance.test.mjs 的 device 目标里跑。这里只放手机上才有意义的：连接的身份（adbd、DESKTOP）、
// 一次性很长的模型输出经网关完整送达、手机发出的每一行的格式与大小。
import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { deviceControl, deviceSerial, directive, PROTOCOL_VERSION, startAgent, startDeviceModel, textOf } from "./agent.mjs";

const skip = deviceSerial ? false : "set AGENTOS_ACP_DEVICE=<adb serial> to run against the phone";

describe(`AgentOS on the phone over adb forward (${deviceSerial ?? "no device"})`, { skip, timeout: 300_000 }, () => {
  let model;
  let agent;
  let init;

  before(async () => {
    model = await startDeviceModel(deviceSerial);
    agent = await startAgent({ target: "device" });
    init = await agent.connection.initialize({ protocolVersion: PROTOCOL_VERSION, clientCapabilities: {} });
  });

  after(async () => {
    const result = await agent?.stop();
    if (result && result.code !== 0) console.error(agent.stderr.join(""));
    try {
      deviceControl(deviceSerial, "disable");
    } finally {
      await model?.stop();
    }
  });

  test("the connection is a desktop caller forwarded by adbd (uid 2000)", () => {
    assert.equal(init.agentInfo.name, "agentos");
    const status = deviceControl(deviceSerial, "status");
    assert.equal(status.enabled, true);
    assert.equal(status.listening, true);
    assert.equal(status.socket, "agentos-acp");
    assert.equal(status.connections.length, 1);
    assert.equal(status.connections[0].peerUid, 2000);
    assert.match(status.connections[0].label, /^acp-bridge@/);
    assert.ok(!JSON.stringify(status).match(/"code":"\d{6}"/), "status never contains the pairing code");
  });

  test("a 70,000-character model output reaches the desktop complete, no line over 65,536 characters", async () => {
    const { sessionId } = await agent.connection.newSession({ cwd: "/sdcard", mcpServers: [] });
    // 假模型端点一次发出 70,000 字符（一条增量）：宿主层切到 ≤ 8,192 字符写日志，网关按行发出
    const r = await agent.connection.prompt({ sessionId, prompt: directive({ chunks: 1, chunkChars: 70_000, text: "y" }) });
    assert.equal(r.stopReason, "end_turn");
    assert.equal(textOf(agent.updatesFor(sessionId)), "y".repeat(70_000));
    const status = deviceControl(deviceSerial, "status");
    assert.equal(status.connections[0].transport.droppedTooLarge, 0);
  });

  test("every line from the phone is exactly one JSON-RPC 2.0 message, no extra fields, at most 65,536 characters", () => {
    assert.ok(agent.lines.length > 5);
    const extra = new Set();
    for (const line of agent.lines) {
      assert.ok(line.length <= 65_536, `line of ${line.length}`);
      const msg = JSON.parse(line);
      assert.equal(msg.jsonrpc, "2.0");
      for (const k of Object.keys(msg)) if (!["jsonrpc", "id", "method", "params", "result", "error"].includes(k)) extra.add(k);
    }
    assert.deepEqual([...extra], [], "JsonRpcCodec: no SDK class discriminator");
  });
});
