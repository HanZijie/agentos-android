// Unit tests for src/think-tags.js and src/compat.js (plain Node, no bundle needed).
//
//   node test/think-tags.mjs        (part of npm test)
import assert from "node:assert/strict";
import { AssistantMessageEventStream } from "@earendil-works/pi-ai/utils/event-stream";
import { splitLeadingThink, splitLeadingThinkStream } from "../src/think-tags.js";
import { addMissing, extraBody, thinkTagsEnabled } from "../src/compat.js";

let passed = 0;
const tests = [];
const test = (name, fn) => tests.push([name, fn]);

/**
 * A pi-ai-like stream: a text block (index 0) fed in `chunks`, optionally preceded by a provider
 * thinking block and followed by a tool call; `partial` mirrors the growing message like pi-ai.
 */
function providerStream(chunks, { toolCall = false, providerThinking = null } = {}) {
  const s = new AssistantMessageEventStream();
  const out = { role: "assistant", content: [], stopReason: "stop" };
  const snap = () => structuredClone(out);
  queueMicrotask(() => {
    s.push({ type: "start", partial: snap() });
    let idx = 0;
    if (providerThinking !== null) {
      out.content.push({ type: "thinking", thinking: "" });
      s.push({ type: "thinking_start", contentIndex: idx, partial: snap() });
      out.content[idx].thinking = providerThinking;
      s.push({ type: "thinking_delta", contentIndex: idx, delta: providerThinking, partial: snap() });
      s.push({ type: "thinking_end", contentIndex: idx, content: providerThinking, partial: snap() });
      idx++;
    }
    if (chunks.length > 0) {
      out.content.push({ type: "text", text: "" });
      s.push({ type: "text_start", contentIndex: idx, partial: snap() });
      for (const c of chunks) {
        out.content[idx].text += c;
        s.push({ type: "text_delta", contentIndex: idx, delta: c, partial: snap() });
      }
      s.push({ type: "text_end", contentIndex: idx, content: out.content[idx].text, partial: snap() });
      idx++;
    }
    if (toolCall) {
      const call = { type: "toolCall", id: "t1", name: "add", arguments: { a: 2, b: 3 } };
      out.content.push({ ...call, arguments: {} });
      s.push({ type: "toolcall_start", contentIndex: idx, partial: snap() });
      s.push({ type: "toolcall_delta", contentIndex: idx, delta: "{\"a\":2,\"b\":3}", partial: snap() });
      out.content[idx] = call;
      s.push({ type: "toolcall_end", contentIndex: idx, toolCall: call, partial: snap() });
      out.stopReason = "toolUse";
    }
    s.push({ type: "done", reason: out.stopReason, message: snap() });
    s.end();
  });
  return s;
}

async function run(chunks, opts) {
  const stream = splitLeadingThinkStream(providerStream(chunks, opts));
  const events = [];
  for await (const e of stream) events.push(e);
  const result = await stream.result();
  const joined = (type, idx) => events.filter((e) => e.type === type && (idx === undefined || e.contentIndex === idx)).map((e) => e.delta).join("");
  return { events, result, types: events.map((e) => `${e.type}${e.contentIndex ?? ""}`), thinking: joined("thinking_delta"), text: (idx) => joined("text_delta", idx) };
}

/** Splits `s` at the given (ascending) cut points. */
const cut = (s, ...points) => {
  const at = [0, ...points, s.length];
  return at.slice(0, -1).map((p, i) => s.slice(p, at[i + 1])).filter((x) => x.length > 0);
};

test("a leading think segment becomes a thinking block, across chunk boundaries at any point", async () => {
  const full = "<think>reason about it</think>\n\npong";
  for (let i = 1; i < full.length; i++) {
    for (const j of [i + 1, i + 3, i + 8]) {
      if (j >= full.length) continue;
      const r = await run(cut(full, i, j));
      assert.equal(r.thinking, "reason about it", `cuts ${i},${j}`);
      assert.equal(r.text(1), "pong", `cuts ${i},${j}`);
      assert.deepEqual(r.result.content, [{ type: "thinking", thinking: "reason about it" }, { type: "text", text: "pong" }], `cuts ${i},${j}`);
      assert.ok(!r.events.some((e) => (e.delta ?? "").includes("<") && e.type === "text_delta"), `no tag text at cuts ${i},${j}`);
    }
  }
});

test("event order and indices: thinking at 0, answer at 1", async () => {
  const r = await run(["<think>", "a", "b</think>", " x", "y"]);
  assert.deepEqual(r.types, ["start", "thinking_start0", "thinking_delta0", "thinking_delta0", "thinking_end0", "text_start1", "text_delta1", "text_delta1", "text_end1", "done"]);
  assert.equal(r.events.find((e) => e.type === "thinking_end").content, "ab");
  assert.equal(r.events.find((e) => e.type === "text_end").content, "xy");
  // partials already show the split
  assert.equal(r.events.find((e) => e.type === "text_delta").partial.content[0].type, "thinking");
});

test("whitespace before <think> and after </think> is dropped; one-character chunks work", async () => {
  const r = await run([..."\n  <think>plan</think>\n\n  answer"]);
  assert.equal(r.thinking, "plan");
  assert.equal(r.text(1), "answer");
});

test("reasoning then a tool call and no answer text: no empty text block, tool call keeps index 1", async () => {
  const r = await run(["<think>need the add tool</think>\n\n"], { toolCall: true });
  assert.deepEqual(r.types, ["start", "thinking_start0", "thinking_delta0", "thinking_end0", "toolcall_start1", "toolcall_delta1", "toolcall_end1", "done"]);
  assert.deepEqual(r.result.content.map((b) => b.type), ["thinking", "toolCall"]);
});

test("reasoning, answer text and a tool call: the tool call moves to index 2", async () => {
  const r = await run(["<think>x</think>Let me add."], { toolCall: true });
  assert.deepEqual(r.types.slice(-4), ["toolcall_start2", "toolcall_delta2", "toolcall_end2", "done"]);
  assert.deepEqual(r.result.content.map((b) => b.type), ["thinking", "text", "toolCall"]);
});

test("unclosed think (max tokens, abort): everything is reasoning, no answer block", async () => {
  const r = await run(["<think>abc</th"]);
  assert.equal(r.thinking, "abc</th");
  assert.deepEqual(r.result.content, [{ type: "thinking", thinking: "abc</th" }]);
});

test("untouched: plain text, lookalike prefixes, a think later in the text", async () => {
  for (const chunks of [["hello ", "world"], ["<th", "e end"], ["<b>bold</b>"], ["answer <think>x</think> more"], ["<", "thinker"], [" "]]) {
    const r = await run(chunks);
    const original = chunks.join("");
    assert.equal(r.text(0), original, JSON.stringify(chunks));
    assert.equal(r.thinking, "");
    assert.deepEqual(r.result.content, [{ type: "text", text: original }], JSON.stringify(chunks));
  }
});

test("untouched: the provider already streams thinking (reasoning_content)", async () => {
  const r = await run(["<think>x</think>y"], { providerThinking: "real reasoning" });
  assert.equal(r.thinking, "real reasoning");
  assert.equal(r.text(1), "<think>x</think>y");
  assert.equal(r.result.content[1].text, "<think>x</think>y");
});

test("splitLeadingThink on messages", () => {
  const m = (content) => ({ role: "assistant", content });
  assert.deepEqual(splitLeadingThink(m([{ type: "text", text: "<think>a</think>\nb" }])).content, [{ type: "thinking", thinking: "a" }, { type: "text", text: "b" }]);
  assert.deepEqual(splitLeadingThink(m([{ type: "text", text: "<think>a</think>" }, { type: "toolCall", id: "1" }])).content, [{ type: "thinking", thinking: "a" }, { type: "toolCall", id: "1" }]);
  const same = m([{ type: "text", text: "no <think>here</think>" }]);
  assert.equal(splitLeadingThink(same), same);
  const thinkingFirst = m([{ type: "thinking", thinking: "t" }, { type: "text", text: "<think>x</think>" }]);
  assert.equal(splitLeadingThink(thinkingFirst), thinkingFirst);
});

test("compat: reasoning_split for MiniMax OpenAI hosts, explicit extra body wins, only missing keys are added", () => {
  const oai = (baseUrl, compat) => ({ api: "openai-completions", provider: "custom", baseUrl, compat });
  for (const u of ["https://api.minimax.cn/v1", "https://api.minimaxi.com/v1", "https://api.minimax.io/v1", "https://API.MINIMAX.CN/v1/"]) {
    assert.deepEqual(extraBody(oai(u)), { reasoning_split: true }, u);
  }
  for (const u of ["https://evil-minimax.cn/v1", "https://api.minimax.cn.evil.com/v1", "https://api.deepseek.com/v1", "http://127.0.0.1:8080/v1", "https://user@x.com/minimax.cn"]) {
    assert.deepEqual(extraBody(oai(u)), {}, u);
  }
  assert.deepEqual(extraBody({ api: "anthropic-messages", baseUrl: "https://api.minimax.cn/anthropic" }), {});
  assert.deepEqual(extraBody(oai("https://api.minimax.cn/v1", { agentosExtraBody: { reasoning_split: false } })), { reasoning_split: false });
  assert.deepEqual(extraBody(oai("http://127.0.0.1/v1", { agentosExtraBody: { foo: 1 } })), { foo: 1 });
  assert.deepEqual(addMissing({ model: "m", stream: true }, { model: "x", reasoning_split: true }), { model: "m", stream: true, reasoning_split: true });
  const p = { a: 1 };
  assert.equal(addMissing(p, {}), p);
});

test("compat: think-tag split defaults on for custom OpenAI endpoints only", () => {
  assert.equal(thinkTagsEnabled({ api: "openai-completions", provider: "custom" }), true);
  assert.equal(thinkTagsEnabled({ api: "openai-completions", provider: "deepseek" }), false);
  assert.equal(thinkTagsEnabled({ api: "openai-completions", provider: "deepseek", compat: { agentosThinkTags: true } }), true);
  assert.equal(thinkTagsEnabled({ api: "openai-completions", provider: "custom", compat: { agentosThinkTags: false } }), false);
  assert.equal(thinkTagsEnabled({ api: "anthropic-messages", provider: "custom" }), false);
});

for (const [name, fn] of tests) {
  try {
    await fn();
    passed++;
    console.log(`PASS ${name}`);
  } catch (e) {
    console.log(`FAIL ${name}\n${e.stack}`);
    process.exitCode = 1;
  }
}
console.log(`\n${passed}/${tests.length} think-tags tests passed`);
