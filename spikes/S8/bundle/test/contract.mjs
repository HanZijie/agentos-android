// S8 contract test for the bundle, run in a bare Node `vm` context with a
// Node implementation of the host primitives (test/vm-host.mjs).
//
//   node test/contract.mjs            (build first: node build.mjs)
//
// The same scenarios run on QuickJS via the JVM runner (../desktop) and the
// Android test app (../android); see ../README.md.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { startFakeLlm } from "./fake-llm.mjs";
import { VmPiEngine } from "./vm-host.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const dist = path.resolve(here, "../dist");
const catalog = JSON.parse(fs.readFileSync(path.join(dist, "model-catalog.json"), "utf8"));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const UNICODE_PROBE = "你好，世界 🙂👍🏽 naïve café 𝄞 한국어";

function presetModel(providerId, api) {
  const p = catalog.providers.find((x) => x.id === providerId);
  if (!p) throw new Error(`preset ${providerId} missing from catalog`);
  const m = p.models.find((x) => x.api === api) ?? p.models[0];
  return structuredClone(m);
}

const results = [];
async function check(name, fn) {
  const t0 = performance.now();
  try {
    const info = await fn();
    results.push({ name, ok: true, ms: Math.round(performance.now() - t0), info });
    console.log(`PASS ${name}${info ? "  " + JSON.stringify(info) : ""}`);
  } catch (e) {
    results.push({ name, ok: false, ms: Math.round(performance.now() - t0), error: String(e?.stack ?? e) });
    console.log(`FAIL ${name}\n     ${String(e?.stack ?? e).split("\n").slice(0, 6).join("\n     ")}`);
  }
}
function assert(cond, msg) { if (!cond) throw new Error(`assertion failed: ${msg}`); }

const fake = await startFakeLlm();
const families = [
  { api: "anthropic-messages", model: { ...presetModel("minimax", "anthropic-messages"), baseUrl: `${fake.url}/anthropic` } },
  { api: "openai-completions", model: { ...presetModel("deepseek", "openai-completions"), baseUrl: `${fake.url}/openai` } },
];

const events = [];
const engine = new VmPiEngine({
  bundlePath: path.join(dist, "pi-agent.js"),
  credentials: families.map((f) => ({ baseUrl: f.model.baseUrl, key: fake.key })),
  tools: { add: async ({ a, b }) => ({ content: [{ type: "text", text: String(a + b) }] }) },
  beforeToolCall: async (p) => (p.name === "forbidden" ? { block: true, reason: "nope" } : {}),
  onEvent: (m) => events.push({ ...m, at: Date.now() }),
  onLog: (m) => { if (m.level === "error" || m.level === "warn") console.log(`  [js ${m.level}] ${m.msg}`); },
});
const startup = await engine.start();
console.log(`startup: ${JSON.stringify(startup)}`);

const TOOLS = [{ name: "add", description: "Add two integers", parameters: { type: "object", properties: { a: { type: "integer" }, b: { type: "integer" } }, required: ["a", "b"] } }];
let sidSeq = 0;
async function newSession(model, extra = {}) {
  const sid = `s${++sidSeq}`;
  await engine.request("create", { sid, model, systemPrompt: "You are a test agent.", tools: TOOLS, ...extra });
  return sid;
}
const eventsFor = (sid) => events.filter((e) => e.sid === sid && e.t === "event").map((e) => ({ ...e.e, at: e.at }));
const logSince = (n) => fake.log.slice(n);

await check("ping", async () => engine.request("ping"));

for (const fam of families) {
  const tag = fam.api;

  await check(`${tag}: streaming text arrives chunk by chunk`, async () => {
    const sid = await newSession(fam.model);
    const n0 = fake.log.length;
    const r = await engine.request("prompt", { sid, text: `[echo] hello-${tag}` });
    const deltas = eventsFor(sid).filter((e) => e.type === "message_update" && e.update.type === "text_delta");
    const req = logSince(n0)[0];
    assert(r.stopReason === "stop", `stopReason ${r.stopReason} ${r.errorMessage ?? ""}`);
    assert(r.text.startsWith(`echo:[echo] hello-${tag}`), `text ${r.text}`);
    assert(deltas.length >= 4, `deltas ${deltas.length}`);
    assert(deltas[0].at < req.chunkTimes.at(-1), "first delta must reach the host before the server sent its last chunk");
    assert(req.stream === true && req.authOk && req.presentedKeyKind === "real", "server saw a streaming request with the real key");
    const types = eventsFor(sid).map((e) => e.type);
    for (const t of ["agent_start", "turn_start", "message_start", "message_update", "message_end", "turn_end", "agent_end"]) assert(types.includes(t), `missing ${t}`);
    return { deltas: deltas.length, firstDeltaBeforeLastChunkMs: req.chunkTimes.at(-1) - deltas[0].at };
  });

  await check(`${tag}: CJK and emoji survive host <-> JS <-> HTTP`, async () => {
    const sid = await newSession(fam.model);
    const n0 = fake.log.length;
    const text = `[echo] ${UNICODE_PROBE}`;
    const r = await engine.request("prompt", { sid, text });
    const streamed = eventsFor(sid).filter((e) => e.update?.type === "text_delta").map((e) => e.update.delta).join("");
    const sent = JSON.stringify(logSince(n0)[0].body.messages);
    assert(sent.includes(UNICODE_PROBE), "request body lost characters");
    assert(r.text.includes(UNICODE_PROBE), `reply ${JSON.stringify(r.text)}`);
    assert(streamed === r.text, "streamed deltas differ from final text");
  });

  await check(`${tag}: tool call round trip`, async () => {
    const sid = await newSession(fam.model);
    const n0 = fake.log.length;
    const r = await engine.request("prompt", { sid, text: "[tool:add 2 3] please add" });
    const ev = eventsFor(sid);
    const start = ev.find((e) => e.type === "tool_execution_start");
    const end = ev.find((e) => e.type === "tool_execution_end");
    const reqs = logSince(n0);
    assert(start && start.toolName === "add" && start.args.a === 2 && start.args.b === 3, `tool start ${JSON.stringify(start)}`);
    assert(end && !end.isError && end.result.content[0].text === "5", `tool end ${JSON.stringify(end)}`);
    assert(reqs.length === 2, `requests ${reqs.length}`);
    assert(r.text === "sum=5", `text ${r.text}`);
    return { requests: reqs.length };
  });

  await check(`${tag}: abort mid-stream ends as aborted with no request left`, async () => {
    const sid = await newSession(fam.model);
    const n0 = fake.log.length;
    const p = engine.request("prompt", { sid, text: "[slow] long answer" });
    for (let i = 0; i < 100 && !eventsFor(sid).some((e) => e.update?.type === "text_delta"); i++) await sleep(20);
    const tAbort = Date.now();
    await engine.request("abort", { sid });
    const r = await p;
    const tDone = Date.now();
    await sleep(300);
    const req = logSince(n0)[0];
    const stats = await engine.request("stats");
    assert(r.stopReason === "aborted", `stopReason ${r.stopReason}`);
    assert(req.closedEarly === true, "server should see the client close the connection");
    assert(engine.inflightHostRequests() === 0 && stats.inflightFetches === 0, `inflight host=${engine.inflightHostRequests()} js=${stats.inflightFetches}`);
    return { abortToReplyMs: tDone - tAbort, abortToServerCloseMs: req.closedAt - tAbort, ticksReceived: eventsFor(sid).filter((e) => e.update?.type === "text_delta").length };
  });

  for (const status of [500, 429]) {
    await check(`${tag}: HTTP ${status} is not retried by pi-ai or the SDK`, async () => {
      const sid = await newSession(fam.model);
      const n0 = fake.log.length;
      const r = await engine.request("prompt", { sid, text: `[fail${status}]` });
      await sleep(1500); // longer than retry-after: 1
      const reqs = logSince(n0);
      assert(r.stopReason === "error", `stopReason ${r.stopReason}`);
      assert(reqs.length === 1, `requests ${reqs.length}`);
      return { errorMessage: r.errorMessage?.slice(0, 80) };
    });
  }

  await check(`${tag}: rebuilt Agent from saved messages sends identical context`, async () => {
    const a = await newSession(fam.model);
    await engine.request("prompt", { sid: a, text: "[echo] first turn" });
    const history = await engine.request("history", { sid: a });
    const b = await newSession(fam.model, { messages: history });
    const n0 = fake.log.length;
    const ra = await engine.request("prompt", { sid: a, text: "[echo] second turn" });
    const rb = await engine.request("prompt", { sid: b, text: "[echo] second turn" });
    const [qa, qb] = logSince(n0);
    const strip = (body) => JSON.stringify({ system: body.system, messages: body.messages, tools: body.tools });
    assert(strip(qa.body) === strip(qb.body), "request context of the rebuilt agent differs");
    assert(ra.text === rb.text, `${ra.text} vs ${rb.text}`);
    return { historyMessages: history.length, replyN: rb.text.match(/n=(\d+)/)?.[1] };
  });

  await check(`${tag}: 3 concurrent sessions in one runtime do not cross-talk`, async () => {
    const sids = await Promise.all([1, 2, 3].map(() => newSession(fam.model)));
    const replies = await Promise.all(sids.map((sid, i) => engine.request("prompt", { sid, text: `[echo] marker-${tag}-${i}` })));
    replies.forEach((r, i) => {
      assert(r.text.includes(`marker-${tag}-${i}`), `reply ${i}: ${r.text}`);
      const streamed = eventsFor(sids[i]).filter((e) => e.update?.type === "text_delta").map((e) => e.update.delta).join("");
      assert(streamed === r.text, `streamed text of ${sids[i]} differs`);
      for (let j = 0; j < 3; j++) if (j !== i) assert(!streamed.includes(`marker-${tag}-${j} `) && !streamed.endsWith(`marker-${tag}-${j}`), `session ${i} saw marker ${j}`);
    });
    // overlap: the sessions really ran at the same time
    const spans = sids.map((sid) => { const ev = eventsFor(sid); return [ev[0].at, ev.at(-1).at]; });
    const overlap = Math.min(...spans.map((s) => s[1])) - Math.max(...spans.map((s) => s[0]));
    assert(overlap > 0, "sessions did not overlap in time");
    return { overlapMs: overlap };
  });
}

await check("key never enters JS; placeholder never reaches the server", async () => {
  assert(engine.ingressViolations.length === 0, `violations ${engine.ingressViolations.length}`);
  const kinds = [...new Set(fake.log.map((e) => e.presentedKeyKind))];
  assert(kinds.length === 1 && kinds[0] === "real", `server saw key kinds ${kinds}`);
  return { requests: fake.log.length };
});

await check("no host requests left over", async () => {
  await sleep(200);
  const stats = await engine.request("stats");
  assert(engine.inflightHostRequests() === 0, "host requests");
  assert(stats.inflightFetches === 0, "js fetches");
  return { jsTimers: stats.activeTimers, hostTimers: engine.activeHostTimers(), fetches: engine.stats.fetches, aborts: engine.stats.fetchAborts };
});

// Per-turn overhead in this harness: a turn through Pi vs. streaming the same response directly.
await check("per-turn overhead (Node vm host)", async () => {
  const fam = families[0];
  const sid = await newSession(fam.model);
  const turns = [];
  const direct = [];
  for (let i = 0; i < 5; i++) {
    const t0 = performance.now();
    await engine.request("prompt", { sid, text: `[echo] warm ${i}` });
    turns.push(performance.now() - t0);
    const t1 = performance.now();
    const res = await fetch(`${fake.url}/anthropic/v1/messages`, { method: "POST", headers: { "x-api-key": fake.key, "content-type": "application/json" }, body: JSON.stringify({ model: "x", stream: true, messages: [{ role: "user", content: `[echo] warm ${i}` }] }) });
    await res.text();
    direct.push(performance.now() - t1);
  }
  const median = (a) => a.slice().sort((x, y) => x - y)[Math.floor(a.length / 2)];
  return { turnMedianMs: Math.round(median(turns)), directMedianMs: Math.round(median(direct)), overheadMs: Math.round(median(turns) - median(direct)) };
});

const stopped = await engine.stop();
await fake.close();
const failed = results.filter((r) => !r.ok);
const out = { runtime: `node ${process.version} vm context`, startup, results, mainResult: stopped };
fs.writeFileSync(path.join(dist, "contract-node.json"), JSON.stringify(out, null, 2));
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
