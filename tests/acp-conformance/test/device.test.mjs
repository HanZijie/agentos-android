// 手机上 AgentOS 的 :agent 经 adb forward（tools/acp-bridge → localabstract:agentos-acp），官方 TypeScript 客户端（W9）。
// 设了 AGENTOS_ACP_DEVICE=<adb serial> 时运行；设备上要装 debug 包（开关和配对码经 DesktopGatewayDebugReceiver 操作）。
//
// 真 AgentRuntime 进 main（C3）之前，:agent 里是 C 的占位实现（PlaceholderAgentRuntime，假 Agent），所以这里只放
// 握手类用例：配对 → initialize → session/new → prompt 流式 → cancel，以及线上格式和出站单行上限。用例对占位实现和
// 正式运行时都成立；只有占位实现才能触发的（超长出站消息）按 agentInfo.name 判断。完整的 13 个用例在电脑上跑
// （conformance.test.mjs 的 stdio / gateway 目标）。
import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { deviceControl, deviceSerial, PROTOCOL_VERSION, startAgent, textOf, until } from "./agent.mjs";

const PLACEHOLDER = "agentos-placeholder-agent";
const skip = deviceSerial ? false : "set AGENTOS_ACP_DEVICE=<adb serial> to run against the phone";

describe(`AgentOS on the phone over adb forward (${deviceSerial ?? "no device"})`, { skip }, () => {
  let agent;
  let init;
  let placeholder;

  before(async () => {
    agent = await startAgent({ target: "device" });
    init = await agent.connection.initialize({ protocolVersion: PROTOCOL_VERSION, clientCapabilities: {} });
    placeholder = init.agentInfo?.name === PLACEHOLDER;
  });

  after(async () => {
    const result = await agent?.stop();
    if (result && result.code !== 0) console.error(agent.stderr.join(""));
    deviceControl(deviceSerial, "disable");
  });

  test("initialize over the desktop gateway speaks ACP v1", () => {
    assert.equal(init.protocolVersion, 1);
    assert.equal(typeof init.agentInfo.name, "string");
    assert.ok(init.agentCapabilities);
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

  test("an outbound message over 65,536 characters is not sent; the turn still ends", async (t) => {
    if (!placeholder) return t.skip("only the placeholder agent can emit an oversized chunk on demand");
    const { sessionId } = await agent.connection.newSession({ cwd: "/sdcard", mcpServers: [] });
    const r = await agent.connection.prompt({ sessionId, prompt: [{ type: "text", text: JSON.stringify({ chunks: 2, intervalMs: 0, bigChunkChars: 70_000 }) }] });
    assert.equal(r.stopReason, "end_turn");
    assert.equal(agent.updatesFor(sessionId).filter((u) => u.sessionUpdate === "agent_message_chunk").length, 2, "the oversized chunk was dropped");
    const status = deviceControl(deviceSerial, "status");
    assert.ok(status.connections[0].transport.droppedTooLarge >= 1);
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
