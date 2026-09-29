// ACP 一致性测试（W4、W9）：官方 TypeScript 客户端（@agentclientprotocol/sdk 1.4.0，与 pi-acp-adapter 一致）
// 连接 AgentOS 运行时，prompt 里的 {"fake":…} 指令决定剧本。同一组用例跑三种目标（AGENTOS_ACP_TARGETS）：
// - stdio：电脑上的运行时经 stdin/stdout，Agent 循环是 FakeAgentCore 或真实 Pi（AGENTOS_ACP_CORE=pi）；
// - gateway：同一个运行时以网关模式运行（与手机上相同的电脑端网关）+ tools/acp-bridge；
// - device（AGENTOS_ACP_DEVICE=<serial>）：手机上的 :agent（真实 Pi）经 tools/acp-bridge，模型是电脑上的 FakeModelServer
//   经 adb reverse。手机上 M1 没有工具、确认和 Jev，依赖它们的用例跳过（原因见 agent.mjs 的 SKIP）。
//
// 覆盖：initialize → session/new → session/prompt 流式输出 → session/cancel，工具调用的更新、错误映射、
// 不支持的输入、自动选会话扩展，以及线上格式（每行一条 JSON-RPC、没有多余字段、单行不超过 65,536 字符）。
import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import { agentCore, deviceSerial, directive, PROTOCOL_VERSION, skipUnless, startAgent, startDeviceModel, targets, textOf, until } from "./agent.mjs";

const META = "org.agentos";
const TASK_FAILED = -32051;

for (const target of targets()) {
  const core = target === "device" ? "pi on the phone" : `${agentCore} agent core`;
  // 设备模式：模型端点（FakeModelServer + adb reverse）在这个目标的所有用例之前起、之后停
  let model;
  if (target === "device") {
    before(async () => {
      model = await startDeviceModel(deviceSerial);
    });
    after(async () => {
      await model?.stop();
    });
  }

  describe(`AgentOS runtime over ${target} (${core})`, { timeout: 300_000 }, () => {
    let agent;
    let init;

    before(async () => {
      agent = await startAgent({ target });
      init = await agent.connection.initialize({ protocolVersion: PROTOCOL_VERSION, clientCapabilities: {} });
    });

    after(async () => {
      const result = await agent?.stop();
      if (result && result.code !== 0) console.error(agent.stderr.join(""));
    });

    test("initialize declares ACP v1, the capabilities and the AgentOS profile", () => {
      assert.equal(init.protocolVersion, 1);
      assert.equal(init.agentCapabilities.loadSession, false);
      assert.equal(init.agentCapabilities.promptCapabilities.embeddedContext, true);
      assert.equal(init.agentCapabilities.promptCapabilities.image, false);
      assert.equal(init.agentCapabilities.mcpCapabilities.http, false);
      assert.equal(init.agentInfo.name, "agentos");
      const meta = init._meta[META];
      assert.equal(meta.profile, 1);
      assert.equal(meta.securityLevel, "best_effort");
      assert.deepEqual(meta.extensions.sessionAutoSelect, { version: 1 });
    });

    test("session/prompt streams agent_message_chunk and returns end_turn after the turn", async () => {
      const { sessionId } = await agent.connection.newSession({ cwd: "/sdcard", mcpServers: [] });
      assert.match(sessionId, /^ses_[0-9A-Z]{26}$/);
      const response = await agent.connection.prompt({
        sessionId,
        prompt: directive({ chunks: 30, chunkChars: 4, text: "abcd", intervalMs: 3 }),
      });
      assert.equal(response.stopReason, "end_turn");
      assert.match(response._meta[META].taskId, /^tsk_/);
      const updates = agent.updatesFor(sessionId);
      assert.equal(textOf(updates), "abcd".repeat(30));
      const chunks = updates.filter((u) => u.sessionUpdate === "agent_message_chunk").length;
      assert.ok(chunks >= 1 && chunks < 30, `30 deltas were coalesced into ${chunks} updates`);
    });

    test("a tool call shows up as tool_call, then tool_call_update in_progress and completed", { skip: skipUnless(target, "tools") }, async () => {
      const { sessionId } = await agent.connection.newSession({ cwd: "/sdcard", mcpServers: [] });
      const response = await agent.connection.prompt({
        sessionId,
        prompt: directive({ tools: [{ name: "add", arguments: { a: 2, b: 3 } }] }),
      });
      assert.equal(response.stopReason, "end_turn");
      const updates = agent.updatesFor(sessionId);
      const call = updates.find((u) => u.sessionUpdate === "tool_call");
      assert.ok(call, "tool_call");
      assert.equal(call.title, "add");
      assert.equal(call.status, "pending");
      assert.deepEqual(call.rawInput, { a: 2, b: 3 });
      const statuses = updates.filter((u) => u.sessionUpdate === "tool_call_update" && u.toolCallId === call.toolCallId).map((u) => u.status);
      assert.deepEqual(statuses, ["in_progress", "completed"]);
      const done = updates.filter((u) => u.sessionUpdate === "tool_call_update").at(-1);
      assert.equal(done.content[0].content.text, "5");
      assert.ok(textOf(updates).endsWith("(fake) done"));
    });

    test("a tool outside the catalog is reported as failed without ever running", async () => {
      const { sessionId } = await agent.connection.newSession({ cwd: "/sdcard", mcpServers: [] });
      const r = await agent.connection.prompt({ sessionId, prompt: directive({ tools: [{ name: "rm_rf", arguments: { path: "/" } }] }) });
      assert.equal(r.stopReason, "end_turn", "a rejected tool does not fail the turn");
      const updates = agent.updatesFor(sessionId).filter((u) => u.sessionUpdate === "tool_call_update");
      assert.deepEqual(updates.map((u) => u.status), ["failed"], "never dispatched, so never in_progress");
      // FakeAgentCore 把目录外的调用交给宿主层（Broker 拒绝：[agentos:tool_not_in_catalog]）；真实 Pi 对没有声明过的工具
      // 不调 beforeToolCall，自己返回 "Tool <name> not found"。两者都没有执行它
      assert.match(updates[0].content[0].content.text, /^\[agentos:tool_not_in_catalog\]|^Tool rm_rf not found$/);
    });

    test("a write tool is confirmed by AgentOS (not by the client) and runs", { skip: skipUnless(target, "tools", "consent") }, async () => {
      const { sessionId } = await agent.connection.newSession({ cwd: "/sdcard", mcpServers: [] });
      const response = await agent.connection.prompt({ sessionId, prompt: directive({ tools: [{ name: "send_note", arguments: { text: "hi" } }] }) });
      assert.equal(response.stopReason, "end_turn");
      const done = agent.updatesFor(sessionId).filter((u) => u.sessionUpdate === "tool_call_update").at(-1);
      assert.equal(done.status, "completed");
      assert.equal(done.content[0].content.text, "sent");
      assert.equal(agent.permissionRequests.length, 0, "the client is never asked (session/request_permission)");
    });

    test("session/cancel ends the turn with cancelled and the session keeps working", async () => {
      const { sessionId } = await agent.connection.newSession({ cwd: "/sdcard", mcpServers: [] });
      const turn = agent.connection.prompt({ sessionId, prompt: directive({ chunks: 2, chunkChars: 3, awaitAbort: true }) });
      await until(() => agent.updatesFor(sessionId).length > 0, 10_000, "first update");
      const t0 = Date.now();
      await agent.connection.cancel({ sessionId });
      const response = await turn;
      assert.equal(response.stopReason, "cancelled");
      assert.ok(Date.now() - t0 < 5_000, "cancel took too long");
      const next = await agent.connection.prompt({ sessionId, prompt: [{ type: "text", text: "again" }] });
      assert.equal(next.stopReason, "end_turn");
      // 回显的格式因 core 而异（FakeAgentCore："echo: again"；假模型端点："echo:again n=…"）
      assert.match(textOf(agent.updatesFor(sessionId)), /echo: ?again/);
    });

    test("a failed turn is a JSON-RPC error carrying the AgentOS error code", async () => {
      const { sessionId } = await agent.connection.newSession({ cwd: "/sdcard", mcpServers: [] });
      await assert.rejects(agent.connection.prompt({ sessionId, prompt: directive({ fail: "model_rate_limited" }) }), (e) => {
        assert.equal(e.code, TASK_FAILED);
        assert.equal(e.data.agentosCode, "model_rate_limited");
        assert.equal(e.data.retryable, true);
        assert.match(e.data.taskId, /^tsk_/);
        return true;
      });
      const again = await agent.connection.prompt({ sessionId, prompt: [{ type: "text", text: "still fine" }] });
      assert.equal(again.stopReason, "end_turn");
    });

    test("max tokens maps to the max_tokens stop reason", async () => {
      const { sessionId } = await agent.connection.newSession({ cwd: "/sdcard", mcpServers: [] });
      const r = await agent.connection.prompt({ sessionId, prompt: directive({ chunks: 1, maxTokens: true }) });
      assert.equal(r.stopReason, "max_tokens");
      const next = await agent.connection.prompt({ sessionId, prompt: [{ type: "text", text: "next" }] });
      assert.equal(next.stopReason, "end_turn");
    });

    test("the tool round limit maps to max_turn_requests", { skip: skipUnless(target, "tools") }, async () => {
      const { sessionId } = await agent.connection.newSession({ cwd: "/sdcard", mcpServers: [] });
      // 工具轮次上限 12（architecture 4.1）：第 13 次带工具的往返不执行，本轮以 max_turn_requests 结束
      const loop = await agent.connection.prompt({ sessionId, prompt: directive({ toolLoop: 13 }) });
      assert.equal(loop.stopReason, "max_turn_requests");
      const calls = agent.updatesFor(sessionId).filter((u) => u.sessionUpdate === "tool_call_update" && u.status === "completed");
      assert.equal(calls.length, 12);
      // 之后会话照常可用（每个 toolCall 都有 toolResult，messages 仍然合法）
      const next = await agent.connection.prompt({ sessionId, prompt: [{ type: "text", text: "next" }] });
      assert.equal(next.stopReason, "end_turn");
    });

    test("unsupported inputs are rejected explicitly", async () => {
      await assert.rejects(
        agent.connection.newSession({ cwd: "/sdcard", mcpServers: [{ name: "fs", command: "/bin/fs", args: [], env: [] }] }),
        (e) => e.code === -32602 && e.data.agentosCode === "unsupported",
      );
      const { sessionId } = await agent.connection.newSession({ cwd: "/sdcard", mcpServers: [] });
      await assert.rejects(
        agent.connection.prompt({ sessionId, prompt: [{ type: "image", data: "AAAA", mimeType: "image/png" }] }),
        (e) => e.code === -32602 && e.data.agentosCode === "unsupported",
      );
      await assert.rejects(agent.connection.loadSession({ sessionId, cwd: "/sdcard", mcpServers: [] }), (e) => e.code === -32601);
      // resource_link 和嵌入的文字资源可以用
      const ok = await agent.connection.prompt({
        sessionId,
        prompt: [
          { type: "resource_link", name: "notes", uri: "content://notes/1" },
          { type: "resource", resource: { uri: "content://notes/2", text: "milk, eggs" } },
          { type: "text", text: "summarise" },
        ],
      });
      assert.equal(ok.stopReason, "end_turn");
      const text = textOf(agent.updatesFor(sessionId));
      assert.ok(text.includes("content://notes/1") && text.includes("milk, eggs"), text);
    });

    test("long output is split so that every line stays under 65,536 characters", async () => {
      const { sessionId } = await agent.connection.newSession({ cwd: "/sdcard", mcpServers: [] });
      const r = await agent.connection.prompt({ sessionId, prompt: directive({ chunks: 1, chunkChars: 30000, text: '"' }) });
      assert.equal(r.stopReason, "end_turn");
      assert.equal(textOf(agent.updatesFor(sessionId)), '"'.repeat(30000));
      const longest = Math.max(...agent.lines.map((l) => l.length));
      assert.ok(longest <= 65_536, `longest line ${longest}`);
    });

    test("every stdout line is exactly one JSON-RPC 2.0 message", () => {
      assert.ok(agent.lines.length > 10);
      const extraKeys = new Set();
      for (const line of agent.lines) {
        const msg = JSON.parse(line);
        assert.equal(msg.jsonrpc, "2.0");
        for (const k of Object.keys(msg)) if (!["jsonrpc", "id", "method", "params", "result", "error"].includes(k)) extraKeys.add(k);
      }
      // 传输是 LineTransport（与手机上的电脑端网关相同），按具体类型编码：没有 SDK StdioTransport 那个 "type" 类鉴别字段（S3 问题 3）
      assert.deepEqual([...extraKeys], []);
    });
  });

  describe(`session auto-select extension (${target}, ${core})`, { timeout: 300_000 }, () => {
    test("is refused unless negotiated in initialize", async () => {
      const agent = await startAgent({ target });
      try {
        await agent.connection.initialize({ protocolVersion: PROTOCOL_VERSION, clientCapabilities: {} });
        await assert.rejects(
          agent.connection.newSession({ cwd: "/sdcard", mcpServers: [], _meta: { [META]: { autoSelect: { query: "hello" } } } }),
          (e) => e.code === -32602 && e.data.agentosCode === "invalid_params",
        );
      } finally {
        await agent.stop();
      }
    });

    test("creates a session, then selects it again, and reports the selection in _meta", { skip: skipUnless(target, "jev") }, async () => {
      const agent = await startAgent({ args: ["--jev=first"], target });
      try {
        await agent.connection.initialize({
          protocolVersion: PROTOCOL_VERSION,
          clientCapabilities: {},
          _meta: { [META]: { extensions: ["sessionAutoSelect"] } },
        });
        const auto = (query) => agent.connection.newSession({ cwd: "/sdcard", mcpServers: [], _meta: { [META]: { autoSelect: { query } } } });
        const selections = () =>
          agent.updates.filter((u) => u.update.sessionUpdate === "session_info_update").map((u) => u.update._meta?.[META]?.selection);

        const first = await auto("plan a trip to Kyoto");
        await until(() => selections().length === 1, 10_000, "first selection");
        assert.deepEqual(selections()[0], { sessionId: first.sessionId, created: true, method: "no_candidates" });
        await agent.connection.prompt({ sessionId: first.sessionId, prompt: [{ type: "text", text: "plan a trip to Kyoto" }] });

        const second = await auto("hotels in Kyoto?");
        assert.equal(second.sessionId, first.sessionId, "the existing session was selected");
        await until(() => selections().length === 2, 10_000, "second selection");
        assert.deepEqual(selections()[1], { sessionId: first.sessionId, created: false, method: "jev" });
        const r = await agent.connection.prompt({ sessionId: second.sessionId, prompt: [{ type: "text", text: "hotels in Kyoto?" }] });
        assert.equal(r.stopReason, "end_turn");
      } finally {
        await agent.stop();
      }
    });
  });
}
