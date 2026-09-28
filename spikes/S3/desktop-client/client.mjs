// S3 第二部分：用官方 ACP TypeScript 客户端，经 adb forward 连到手机 :agent 的抽象 socket。
//
//   adb forward tcp:8765 localabstract:agentos-acp
//   node client.mjs            # 可选 PORT=8765
//
// 步骤：原始探测（手写一行 initialize，看服务端回的原始 JSON）→ SDK：initialize → session/new →
// prompt（流式）→ 长 prompt 中途 cancel → 同一会话再 prompt 一次。最后一行输出 JSON 结果。
import net from "node:net";
import { once } from "node:events";
import { Readable, Writable } from "node:stream";
import * as acp from "@agentclientprotocol/sdk";

const PORT = Number(process.env.PORT || 8765);
const now = () => Number(process.hrtime.bigint() / 1000n) / 1000; // ms

async function rawProbe() {
  const s = net.connect({ host: "127.0.0.1", port: PORT });
  await once(s, "connect");
  s.write(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: 1, clientCapabilities: {} } }) + "\n");
  let buf = "";
  const line = await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error("raw probe timeout")), 10000);
    s.on("data", (d) => {
      buf += d.toString("utf8");
      const i = buf.indexOf("\n");
      if (i >= 0) { clearTimeout(t); resolve(buf.slice(0, i)); }
    });
    s.on("error", reject);
  });
  s.destroy();
  const obj = JSON.parse(line);
  return { firstLine: line.slice(0, 300), extraKeys: Object.keys(obj).filter((k) => !["jsonrpc", "id", "result", "error"].includes(k)) };
}

async function main() {
  const result = { ok: false, port: PORT, steps: {} };
  const t0 = now();
  result.raw = await rawProbe();

  const socket = net.connect({ host: "127.0.0.1", port: PORT });
  await once(socket, "connect");
  const stream = acp.ndJsonStream(Writable.toWeb(socket), Readable.toWeb(socket));
  let chunks = 0;
  const conn = new acp.ClientSideConnection(() => ({
    async requestPermission() { return { outcome: { outcome: "cancelled" } }; },
    async sessionUpdate(p) { if (p.update.sessionUpdate === "agent_message_chunk") chunks++; },
  }), stream);

  let t = now();
  const init = await conn.initialize({ protocolVersion: acp.PROTOCOL_VERSION, clientCapabilities: {} });
  result.steps.initialize = { ms: now() - t, protocolVersion: init.protocolVersion, agent: init.agentInfo?.name ?? null };

  t = now();
  const { sessionId } = await conn.newSession({ cwd: "/", mcpServers: [] });
  result.steps.newSession = { ms: now() - t, sessionId };

  chunks = 0; t = now();
  const r1 = await conn.prompt({ sessionId, prompt: [{ type: "text", text: "hello from desktop over adb forward" }] });
  result.steps.prompt = { ms: now() - t, stopReason: r1.stopReason, chunks };

  chunks = 0; t = now();
  const p2 = conn.prompt({ sessionId, prompt: [{ type: "text", text: JSON.stringify({ chunks: 100000, intervalMs: 10 }) }] });
  while (chunks < 10) await new Promise((r) => setTimeout(r, 5));
  const tCancel = now();
  await conn.cancel({ sessionId });
  const r2 = await p2;
  result.steps.cancel = { chunksBeforeCancel: 10, chunksTotal: chunks, stopReason: r2.stopReason, cancelToStopMs: now() - tCancel };

  chunks = 0; t = now();
  const r3 = await conn.prompt({ sessionId, prompt: [{ type: "text", text: JSON.stringify({ chunks: 5, intervalMs: 0 }) }] });
  result.steps.afterCancel = { ms: now() - t, stopReason: r3.stopReason, chunks };

  result.ok = r1.stopReason === "end_turn" && r2.stopReason === "cancelled" && r3.stopReason === "end_turn";
  result.totalMs = now() - t0;
  socket.destroy();
  console.log(JSON.stringify(result));
}

main().then(() => process.exit(0)).catch((e) => {
  console.log(JSON.stringify({ ok: false, error: String(e?.stack || e) }));
  process.exit(1);
});
