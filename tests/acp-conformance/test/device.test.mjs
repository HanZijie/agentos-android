// 手机上 AgentOS 的 :agent 经 adb forward（tools/acp-bridge → localabstract:agentos-acp），官方 TypeScript 客户端（W9）。
// 设了 AGENTOS_ACP_DEVICE=<adb serial> 时运行；设备上要装 debug 包（开关和配对码经 DesktopGatewayDebugReceiver 操作）。
//
// C3 之后 :agent 是真的 RuntimeEngine，Agent 循环在 Pi 接上之前是 C 的 ScriptedAgentCore（不调模型、没有工具，
// 按 prompt 里的 JSON 脚本流式输出）；更早的包是占位实现 PlaceholderAgentRuntime。两者都不认 FakeAgentCore 的指令和
// 测试工具，所以这里放握手类用例：配对 → initialize → session/new → prompt 流式 → cancel，线上格式和单行上限。
// 完整的 13 个用例在电脑上跑（conformance.test.mjs 的 stdio / gateway 目标，同一份网关代码）。
import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { deviceControl, deviceSerial, ensureDeviceTestModel, PROTOCOL_VERSION, startAgent, textOf, until } from "./agent.mjs";

const PLACEHOLDER = "agentos-placeholder-agent";
const skip = deviceSerial ? false : "set AGENTOS_ACP_DEVICE=<adb serial> to run against the phone";

describe(`AgentOS on the phone over adb forward (${deviceSerial ?? "no device"})`, { skip, timeout: 180_000 }, () => {
  let agent;
  let init;
  let placeholder;

  before(async () => {
    await ensureDeviceTestModel(deviceSerial);
    agent = await startAgent({ target: "device" });
    init = await agent.connection.initialize({ protocolVersion: PROTOCOL_VERSION, clientCapabilities: {} });
    placeholder = init.agentInfo?.name === PLACEHOLDER;
  });

  after(async () => {
    const result = await agent?.stop();
    if (result && result.code !== 0) console.error(agent.stderr.join(""));
    deviceControl(deviceSerial, "disable");
  });

  test("initialize over the desktop gateway speaks ACP v1 and declares the AgentOS profile", () => {
    assert.equal(init.protocolVersion, 1);
    assert.equal(typeof init.agentInfo.name, "string");
    assert.ok(init.agentCapabilities);
    if (!placeholder) {
      assert.equal(init.agentInfo.name, "agentos");
      assert.equal(init._meta["org.agentos"].profile, 1);
      assert.equal(init.agentCapabilities.loadSession, false);
    }
  });

  test("the connection is a desktop caller forwarded by adbd (uid 2000)", () => {
    const status = deviceControl(deviceSerial, "status");
    assert.equal(status.enabled, true);
    assert.equal(status.listening, true);
    assert.equal(status.socket, "agentos-acp");
    assert.equal(status.connections.length, 1);
    assert.equal(status.connections[0].peerUid, 2000);
    assert.match(status.connections[0].label, /^acp-bridge@/);
    assert.ok(!JSON.stringify(status).match(/"code":"\d{6}"/), "status never contains the pairing code");
  });

  test("session/new then a prompt streams agent_message_chunk and ends with end_turn", async () => {
    const { sessionId } = await agent.connection.newSession({ cwd: "/sdcard", mcpServers: [] });
    assert.ok(sessionId);
    const r = await agent.connection.prompt({ sessionId, prompt: [{ type: "text", text: "hello from the desktop" }] });
    assert.equal(r.stopReason, "end_turn");
    if (!placeholder) {
      assert.match(sessionId, /^ses_[0-9A-Z]{26}$/);
      assert.match(r._meta["org.agentos"].taskId, /^tsk_/);
    }
    const chunks = agent.updatesFor(sessionId).filter((u) => u.sessionUpdate === "agent_message_chunk");
    assert.ok(chunks.length >= 1);
    assert.ok(textOf(agent.updatesFor(sessionId)).length > 0);
  });

  test("session/cancel mid-turn ends with cancelled and the session keeps working", async () => {
    const { sessionId } = await agent.connection.newSession({ cwd: "/sdcard", mcpServers: [] });
    // 占位实现：100,000 条、每条间隔 20 ms；正式运行时：普通文字
    const turn = agent.connection.prompt({ sessionId, prompt: [{ type: "text", text: JSON.stringify({ chunks: 100_000, intervalMs: 20 }) }] });
    await until(() => agent.updatesFor(sessionId).length > 0, 20_000, "first update");
    const t0 = Date.now();
    await agent.connection.cancel({ sessionId });
    const r = await turn;
    assert.equal(r.stopReason, "cancelled");
    assert.ok(Date.now() - t0 < 5_000, "cancel took too long");
    const next = await agent.connection.prompt({ sessionId, prompt: [{ type: "text", text: "again" }] });
    assert.equal(next.stopReason, "end_turn");
  });

  test("a 70,000-character output never produces a line over 65,536 characters", async () => {
    const { sessionId } = await agent.connection.newSession({ cwd: "/sdcard", mcpServers: [] });
    const r = await agent.connection.prompt({ sessionId, prompt: [{ type: "text", text: JSON.stringify({ chunks: 2, intervalMs: 0, bigChunkChars: 70_000 }) }] });
    assert.equal(r.stopReason, "end_turn");
    const status = deviceControl(deviceSerial, "status");
    const text = textOf(agent.updatesFor(sessionId));
    if (placeholder) {
      // 占位实现不切分：这条 70,000 字符的通知超过单行上限，网关不发，计数
      assert.ok(!text.includes("y".repeat(1_000)), "the oversized chunk was dropped");
      assert.ok(status.connections[0].transport.droppedTooLarge >= 1);
    } else {
      // 真运行时：UpdateMapper 按 8,192 字符切分，完整送达
      assert.ok(text.endsWith("y".repeat(70_000)), "the whole output arrives, split into several updates");
      assert.equal(status.connections[0].transport.droppedTooLarge, 0);
    }
  });

  test("every line from the phone is exactly one JSON-RPC 2.0 message, no extra fields, at most 65,536 characters", () => {
    assert.ok(agent.lines.length > 10);
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
