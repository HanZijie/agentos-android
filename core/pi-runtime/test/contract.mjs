// Contract test for the bundle, run in a bare Node `vm` context (ECMAScript built-ins
// only, like QuickJS) with a Node implementation of the host primitives (vm-host.mjs).
//
//   node build.mjs && node test/contract.mjs        (npm test)
//
// The same scenarios run on QuickJS through the Kotlin host:
//   core/runtime/src/test/kotlin/org/agentos/runtime/pi/PiBundleContractTest.kt   (npm run test:quickjs)
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { startFakeLlm } from "./fake-llm.mjs";
import { VmPiEngine } from "./vm-host.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const assets = path.resolve(here, process.env.PI_BUNDLE_DIR ?? "../../../app/src/main/assets");
const reportDir = path.resolve(here, "../build");
if (!fs.existsSync(path.join(assets, "pi-agent.js"))) {
  console.error(`no bundle in ${assets}: run \`node build.mjs\` first`);
  process.exit(1);
}
const catalog = JSON.parse(fs.readFileSync(path.join(assets, "model-catalog.json"), "utf8"));
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
const hostCalls = [];
const engine = new VmPiEngine({
  bundlePath: path.join(assets, "pi-agent.js"),
  credentials: families.map((f) => ({ baseUrl: f.model.baseUrl, key: fake.key })),
  tools: {
    add: async ({ a, b }) => ({ content: [{ type: "text", text: String(a + b) }] }),
    secret: async () => ({ content: [{ type: "text", text: "raw-secret-output" }] }),
  },
  beforeToolCall: async (p) => { hostCalls.push(["before", p]); return p.name === "forbidden" ? { block: true, reason: "blocked by policy" } : {}; },
  afterToolCall: async (p) => {
    hostCalls.push(["after", p]);
    return p.name === "secret" ? { content: [{ type: "text", text: "redacted" }] } : {};
  },
  onEvent: (m) => events.push({ ...m, at: Date.now() }),
  onLog: (m) => { if (m.level === "error" || m.level === "warn") console.log(`  [js ${m.level}] ${m.msg}`); },
});
const startup = await engine.start();
console.log(`startup: ${JSON.stringify(startup)}`);

const intParams = { type: "object", properties: { a: { type: "integer" }, b: { type: "integer" } }, required: ["a", "b"] };
const TOOLS = [
  { name: "add", description: "Add two integers", parameters: intParams },
  { name: "forbidden", description: "Always blocked by the host", parameters: intParams },
  { name: "secret", description: "Output rewritten by afterToolCall", parameters: intParams },
];
let sidSeq = 0;
async function newSession(model, extra = {}) {
  const sid = `s${++sidSeq}`;
  await engine.request("create", { sid, model, systemPrompt: "You are a test agent.", tools: TOOLS, ...extra });
  return sid;
}
const eventsFor = (sid) => events.filter((e) => e.sid === sid && e.t === "event").map((e) => ({ ...e.e, at: e.at }));
const deltasFor = (sid) => eventsFor(sid).filter((e) => e.type === "message_update" && e.update.type === "text_delta");
const logSince = (n) => fake.log.slice(n);

await check("ping reports protocol and API families", async () => {
  const r = await engine.request("ping");
  assert(r.protocol === 1 && r.apis.join() === "anthropic-messages,openai-completions", JSON.stringify(r));
  return r;
});

for (const fam of families) {
  const tag = fam.api;

  await check(`${tag}: streaming text arrives chunk by chunk`, async () => {
    const sid = await newSession(fam.model);
    const n0 = fake.log.length;
    const r = await engine.request("prompt", { sid, text: `[echo] hello-${tag}` });
    const deltas = deltasFor(sid);
    const req = logSince(n0)[0];
    assert(r.stopReason === "stop", `stopReason ${r.stopReason} ${r.errorMessage ?? ""}`);
    assert(r.text.startsWith(`echo:[echo] hello-${tag}`), `text ${r.text}`);
    assert(deltas.length >= 4, `deltas ${deltas.length}`);
    assert(deltas[0].at < req.chunkTimes.at(-1), "first delta must reach the host before the server sent its last chunk");
    assert(req.stream === true && req.authOk && req.presentedKeyKind === "real", "server saw a streaming request with the real key");
    assert(engine.fetchLog.at(-1).sid === sid, "fetch carries the session id");
    assert(r.appended.length === 2 && r.appended[0].role === "user" && r.appended[1].role === "assistant", `appended ${r.appended.map((m) => m.role)}`);
    assert(r.messageCount === 3, `messageCount ${r.messageCount} (system + user + assistant)`);
    const types = eventsFor(sid).map((e) => e.type);
    for (const t of ["agent_start", "turn_start", "message_start", "message_update", "message_end", "turn_end", "agent_end"]) assert(types.includes(t), `missing ${t}`);
    return { deltas: deltas.length, firstDeltaBeforeLastChunkMs: req.chunkTimes.at(-1) - deltas[0].at };
  });

  await check(`${tag}: CJK and emoji survive host <-> JS <-> HTTP`, async () => {
    const sid = await newSession(fam.model);
    const n0 = fake.log.length;
    const r = await engine.request("prompt", { sid, text: `[echo] ${UNICODE_PROBE}` });
    const streamed = deltasFor(sid).map((e) => e.update.delta).join("");
    assert(JSON.stringify(logSince(n0)[0].body.messages).includes(UNICODE_PROBE), "request body lost characters");
    assert(r.text.includes(UNICODE_PROBE), `reply ${JSON.stringify(r.text)}`);
    assert(streamed === r.text, "streamed deltas differ from final text");
  });

  await check(`${tag}: tool call round trip with before/after hooks`, async () => {
    const sid = await newSession(fam.model);
    const n0 = fake.log.length;
    hostCalls.length = 0;
    const r = await engine.request("prompt", { sid, text: "[tool:add 2 3] please add" });
    const ev = eventsFor(sid);
    const start = ev.find((e) => e.type === "tool_execution_start");
    const end = ev.find((e) => e.type === "tool_execution_end");
    assert(start && start.toolName === "add" && start.args.a === 2 && start.args.b === 3, `tool start ${JSON.stringify(start)}`);
    assert(end && !end.isError && end.result.content[0].text === "5", `tool end ${JSON.stringify(end)}`);
    assert(logSince(n0).length === 2, `requests ${logSince(n0).length}`);
    assert(r.text === "sum=5", `text ${r.text}`);
    assert(hostCalls.map((c) => c[0]).join() === "before,after", `host calls ${hostCalls.map((c) => c[0])}`);
    assert(hostCalls[1][1].result.content[0].text === "5" && hostCalls[1][1].isError === false, "afterToolCall sees the result");
    assert(r.appended.map((m) => m.role).join() === "user,assistant,toolResult,assistant", `appended ${r.appended.map((m) => m.role)}`);
  });

  await check(`${tag}: beforeToolCall can block a tool`, async () => {
    const sid = await newSession(fam.model);
    const r = await engine.request("prompt", { sid, text: "[tool:forbidden 1 1]" });
    const end = eventsFor(sid).find((e) => e.type === "tool_execution_end");
    assert(end && end.isError && JSON.stringify(end.result).includes("blocked by policy"), `tool end ${JSON.stringify(end)}`);
    assert(r.stopReason === "stop" && r.text.startsWith("sum="), `after block the model saw the error text: ${r.text}`);
    assert(!r.text.includes("sum=2"), "tool must not have run");
  });

  await check(`${tag}: afterToolCall can rewrite the result the model sees`, async () => {
    const sid = await newSession(fam.model);
    const n0 = fake.log.length;
    const r = await engine.request("prompt", { sid, text: "[tool:secret 1 1]" });
    const second = JSON.stringify(logSince(n0)[1].body.messages);
    assert(second.includes("redacted") && !second.includes("raw-secret-output"), "model must only see the rewritten output");
    assert(r.text === "sum=redacted", `text ${r.text}`);
  });

  await check(`${tag}: abort mid-stream ends as aborted with no request left`, async () => {
    const sid = await newSession(fam.model);
    const n0 = fake.log.length;
    const p = engine.request("prompt", { sid, text: "[slow] long answer" });
    for (let i = 0; i < 100 && deltasFor(sid).length === 0; i++) await sleep(20);
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
    return { abortToReplyMs: tDone - tAbort, abortToServerCloseMs: req.closedAt - tAbort };
  });

  for (const status of [500, 429]) {
    await check(`${tag}: HTTP ${status} is not retried by pi-ai or the SDK`, async () => {
      const sid = await newSession(fam.model);
      const n0 = fake.log.length;
      const r = await engine.request("prompt", { sid, text: `[fail${status}]` });
      await sleep(1500); // longer than retry-after: 1
      assert(r.stopReason === "error", `stopReason ${r.stopReason}`);
      assert(logSince(n0).length === 1, `requests ${logSince(n0).length}`);
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
    return { historyMessages: history.length };
  });

  await check(`${tag}: setTools changes the tools sent on the next request`, async () => {
    const sid = await newSession(fam.model);
    await engine.request("setTools", { sid, tools: [TOOLS[0]] });
    const n0 = fake.log.length;
    await engine.request("prompt", { sid, text: "[echo] tools?" });
    const names = JSON.stringify(logSince(n0)[0].body.tools ?? []);
    assert(names.includes("add") && !names.includes("forbidden") && !names.includes("secret"), names.slice(0, 200));
  });

  await check(`${tag}: 3 concurrent sessions in one runtime do not cross-talk`, async () => {
    const sids = await Promise.all([1, 2, 3].map(() => newSession(fam.model)));
    const replies = await Promise.all(sids.map((sid, i) => engine.request("prompt", { sid, text: `[echo] marker-${tag}-${i}` })));
    replies.forEach((r, i) => {
      assert(r.text.includes(`marker-${tag}-${i}`), `reply ${i}: ${r.text}`);
      const streamed = deltasFor(sids[i]).map((e) => e.update.delta).join("");
      assert(streamed === r.text, `streamed text of ${sids[i]} differs`);
    });
    const spans = sids.map((sid) => { const ev = eventsFor(sid); return [ev[0].at, ev.at(-1).at]; });
    const overlap = Math.min(...spans.map((s) => s[1])) - Math.max(...spans.map((s) => s[0]));
    assert(overlap > 0, "sessions did not overlap in time");
    return { overlapMs: overlap };
  });
}

await check("unknown op and unknown session reply with errors, pump keeps running", async () => {
  let e1, e2;
  try { await engine.request("nope"); } catch (e) { e1 = e; }
  try { await engine.request("prompt", { sid: "missing", text: "x" }); } catch (e) { e2 = e; }
  assert(e1 && /Unknown op/.test(e1.message) && e2 && /Unknown session/.test(e2.message), `${e1?.message} / ${e2?.message}`);
  assert((await engine.request("ping")).protocol === 1, "pump still answers");
});

await check("key never enters JS; placeholder never reaches the server", async () => {
  assert(engine.ingressViolations.length === 0, `violations ${engine.ingressViolations.length}`);
  const kinds = [...new Set(fake.log.map((e) => e.presentedKeyKind))];
  assert(kinds.length === 1 && kinds[0] === "real", `server saw key kinds ${kinds}`);
  return { requests: fake.log.length };
});

await check("no host requests or timers left over", async () => {
  await sleep(200);
  const stats = await engine.request("stats");
  assert(engine.inflightHostRequests() === 0, "host requests");
  assert(stats.inflightFetches === 0, "js fetches");
  assert(stats.activeTimers === 0 && engine.activeHostTimers() === 0, `timers js=${stats.activeTimers} host=${engine.activeHostTimers()}`);
  return { fetches: engine.stats.fetches, aborts: engine.stats.fetchAborts };
});

const stopped = await engine.stop();
await fake.close();
const failed = results.filter((r) => !r.ok);
fs.mkdirSync(reportDir, { recursive: true });
fs.writeFileSync(path.join(reportDir, "contract-node.json"), JSON.stringify({ runtime: `node ${process.version} vm context`, startup, results, mainResult: stopped }, null, 2));
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
