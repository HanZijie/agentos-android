// Fake model endpoint for S8 (and later W3 tests).
//
// Serves both API families pi-ai uses in AgentOS:
//   POST /anthropic/v1/messages        (Anthropic Messages, SSE)
//   POST /openai/chat/completions      (OpenAI Chat Completions, SSE)
// Control:
//   GET  /__log     -> recorded requests (auth outcome, body, disconnects)
//   POST /__reset
//
// Behaviour is scripted by directives in the latest user message:
//   [tool:add A B]  first call -> tool_use add{a,b}; after the tool result -> "sum=<result>"
//   [slow]          40 text chunks, 100 ms apart (used for abort)
//   [fail500]       HTTP 500     [fail429] HTTP 429 with retry-after: 1
//   default         streams "echo:<user text> n=<message count>" in ~30 ms chunks
//
// Standalone: node test/fake-llm.mjs --port 8787   (key from FAKE_LLM_KEY, default below)
import http from "node:http";

export const DEFAULT_FAKE_KEY = "sk-fake-s8-not-a-secret";

function textOf(content) {
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content
    .map((c) => (c.type === "text" ? c.text : c.type === "tool_result" ? textOf(c.content) : ""))
    .join("");
}

/** Normalises both wire formats into {lastKind: "user"|"tool", lastText, userText, count}. */
function conversation(api, body) {
  const msgs = body.messages ?? [];
  const count = msgs.length;
  const last = msgs[msgs.length - 1] ?? {};
  let lastKind = "user";
  let lastText = "";
  if (api === "anthropic") {
    const blocks = Array.isArray(last.content) ? last.content : [];
    if (blocks.some((b) => b.type === "tool_result")) {
      lastKind = "tool";
      lastText = blocks.filter((b) => b.type === "tool_result").map((b) => textOf(b.content)).join("");
    } else lastText = textOf(last.content);
  } else {
    if (last.role === "tool") { lastKind = "tool"; lastText = textOf(last.content); }
    else lastText = textOf(last.content);
  }
  let userText = lastText;
  if (lastKind === "tool") {
    for (let i = msgs.length - 1; i >= 0; i--) {
      const m = msgs[i];
      const isUser = m.role === "user" && !(Array.isArray(m.content) && m.content.some((b) => b.type === "tool_result"));
      if (isUser) { userText = textOf(m.content); break; }
    }
  }
  return { lastKind, lastText, userText, count };
}

function plan(conv) {
  const tool = /\[tool:add\s+(-?\d+)\s+(-?\d+)\]/.exec(conv.userText);
  if (tool && conv.lastKind === "user") return { kind: "tool", name: "add", input: { a: Number(tool[1]), b: Number(tool[2]) } };
  if (tool && conv.lastKind === "tool") return { kind: "text", chunks: chunk(`sum=${conv.lastText}`, 4), gapMs: 20 };
  if (conv.userText.includes("[fail500]")) return { kind: "fail", status: 500 };
  if (conv.userText.includes("[fail429]")) return { kind: "fail", status: 429 };
  if (conv.userText.includes("[slow]")) return { kind: "text", chunks: Array.from({ length: 40 }, (_, i) => `tick${i} `), gapMs: 100 };
  return { kind: "text", chunks: chunk(`echo:${conv.userText} n=${conv.count}`, 8), gapMs: 30 };
}

function chunk(text, parts) {
  const size = Math.max(1, Math.ceil(text.length / parts));
  const out = [];
  for (let i = 0; i < text.length; i += size) out.push(text.slice(i, i + size));
  return out;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function startFakeLlm({ port = 0, key = DEFAULT_FAKE_KEY, host = "127.0.0.1" } = {}) {
  const log = [];
  let seq = 0;

  const server = http.createServer(async (req, res) => {
    if (req.method === "GET" && req.url === "/__log") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify(log));
      return;
    }
    if (req.method === "POST" && req.url === "/__reset") {
      log.length = 0;
      res.writeHead(204).end();
      return;
    }
    const api = req.url.startsWith("/anthropic/") ? "anthropic" : req.url.startsWith("/openai/") ? "openai" : null;
    if (!api || req.method !== "POST") { res.writeHead(404).end(); return; }

    const raw = await new Promise((resolve) => { let s = ""; req.setEncoding("utf8"); req.on("data", (d) => (s += d)); req.on("end", () => resolve(s)); });
    let body = {};
    try { body = JSON.parse(raw); } catch {}
    const presented = api === "anthropic" ? req.headers["x-api-key"] : (req.headers["authorization"] ?? "").replace(/^Bearer\s+/i, "");
    const entry = {
      id: ++seq,
      api,
      path: req.url,
      startedAt: Date.now(),
      authOk: presented === key,
      presentedKeyKind: presented === key ? "real" : presented ? (presented.includes("agentos-host-injected") ? "placeholder" : "other") : "none",
      stream: body.stream === true,
      model: body.model,
      body,
      userAgent: req.headers["user-agent"],
      retryCountHeader: req.headers["x-stainless-retry-count"],
      closedEarly: false,
      finished: false,
    };
    log.push(entry);
    let clientGone = false;
    res.on("close", () => { if (!entry.finished) { clientGone = true; entry.closedEarly = true; entry.closedAt = Date.now(); } });

    if (!entry.authOk) {
      entry.finished = true;
      res.writeHead(401, { "content-type": "application/json" });
      res.end(JSON.stringify(api === "anthropic" ? { type: "error", error: { type: "authentication_error", message: "invalid x-api-key" } } : { error: { message: "Incorrect API key", type: "invalid_request_error" } }));
      return;
    }

    const conv = conversation(api, body);
    const p = plan(conv);
    entry.plan = p.kind;
    if (p.kind === "fail") {
      entry.finished = true;
      const headers = { "content-type": "application/json" };
      if (p.status === 429) headers["retry-after"] = "1";
      res.writeHead(p.status, headers);
      res.end(JSON.stringify(api === "anthropic" ? { type: "error", error: { type: p.status === 429 ? "rate_limit_error" : "api_error", message: `fake ${p.status}` } } : { error: { message: `fake ${p.status}`, type: "server_error" } }));
      return;
    }

    res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache", connection: "keep-alive" });
    const send = (s) => { if (!clientGone) res.write(s); };
    const model = body.model ?? "fake";
    entry.chunkTimes = [];

    if (api === "anthropic") {
      const ev = (type, data) => send(`event: ${type}\ndata: ${JSON.stringify({ type, ...data })}\n\n`);
      ev("message_start", { message: { id: `msg_${entry.id}`, type: "message", role: "assistant", model, content: [], stop_reason: null, stop_sequence: null, usage: { input_tokens: 10 + conv.count, output_tokens: 1 } } });
      if (p.kind === "tool") {
        ev("content_block_start", { index: 0, content_block: { type: "tool_use", id: `toolu_${entry.id}`, name: p.name, input: {} } });
        for (const part of chunk(JSON.stringify(p.input), 3)) { await sleep(10); ev("content_block_delta", { index: 0, delta: { type: "input_json_delta", partial_json: part } }); }
        ev("content_block_stop", { index: 0 });
        ev("message_delta", { delta: { stop_reason: "tool_use", stop_sequence: null }, usage: { output_tokens: 12 } });
      } else {
        ev("content_block_start", { index: 0, content_block: { type: "text", text: "" } });
        for (const part of p.chunks) {
          if (clientGone) break;
          await sleep(p.gapMs);
          entry.chunkTimes.push(Date.now());
          ev("content_block_delta", { index: 0, delta: { type: "text_delta", text: part } });
        }
        ev("content_block_stop", { index: 0 });
        ev("message_delta", { delta: { stop_reason: "end_turn", stop_sequence: null }, usage: { output_tokens: p.chunks.length } });
      }
      ev("message_stop", {});
    } else {
      const base = { id: `chatcmpl-${entry.id}`, object: "chat.completion.chunk", created: Math.floor(Date.now() / 1000), model };
      const data = (choices, extra = {}) => send(`data: ${JSON.stringify({ ...base, choices, ...extra })}\n\n`);
      if (p.kind === "tool") {
        data([{ index: 0, delta: { role: "assistant", tool_calls: [{ index: 0, id: `call_${entry.id}`, type: "function", function: { name: p.name, arguments: "" } }] }, finish_reason: null }]);
        for (const part of chunk(JSON.stringify(p.input), 3)) { await sleep(10); data([{ index: 0, delta: { tool_calls: [{ index: 0, function: { arguments: part } }] }, finish_reason: null }]); }
        data([{ index: 0, delta: {}, finish_reason: "tool_calls" }]);
      } else {
        data([{ index: 0, delta: { role: "assistant", content: "" }, finish_reason: null }]);
        for (const part of p.chunks) {
          if (clientGone) break;
          await sleep(p.gapMs);
          entry.chunkTimes.push(Date.now());
          data([{ index: 0, delta: { content: part }, finish_reason: null }]);
        }
        data([{ index: 0, delta: {}, finish_reason: "stop" }]);
      }
      data([], { usage: { prompt_tokens: 10 + conv.count, completion_tokens: 5, total_tokens: 15 + conv.count } });
      send("data: [DONE]\n\n");
    }
    entry.finished = true;
    entry.endedAt = Date.now();
    res.end();
  });

  await new Promise((resolve) => server.listen(port, host, resolve));
  const address = server.address();
  const url = `http://${host === "0.0.0.0" ? "127.0.0.1" : host}:${address.port}`;
  return {
    url,
    port: address.port,
    key,
    log,
    reset: () => { log.length = 0; },
    close: () => new Promise((r) => { server.closeAllConnections?.(); server.close(() => r()); }),
  };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const i = process.argv.indexOf("--port");
  const port = i >= 0 ? Number(process.argv[i + 1]) : 8787;
  const hostArg = process.argv.indexOf("--host");
  const host = hostArg >= 0 ? process.argv[hostArg + 1] : "127.0.0.1";
  const fake = await startFakeLlm({ port, host, key: process.env.FAKE_LLM_KEY || DEFAULT_FAKE_KEY });
  console.log(`fake LLM listening on ${fake.url} (anthropic: ${fake.url}/anthropic, openai: ${fake.url}/openai)`);
}
