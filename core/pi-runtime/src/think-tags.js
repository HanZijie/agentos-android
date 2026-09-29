/*
 * Leading <think>…</think> on the OpenAI Chat Completions route.
 *
 * Some OpenAI-compatible providers put the model's reasoning into `content`, wrapped in
 * <think>…</think> at the start of the answer (MiniMax M2.x without `reasoning_split`, many
 * vLLM / gateway deployments of reasoning models). pi-ai streams that as plain text, so the UI
 * would show the reasoning and its closing tag as part of the answer, and the next request would
 * send it back as assistant text.
 *
 * This module turns such a leading segment into a thinking block:
 * - streaming: text deltas of the first content block are buffered until it is clear whether they
 *   start with <think> (after optional whitespace); inside, deltas become thinking deltas, holding
 *   back a tail that could be the start of </think> across chunk boundaries; after </think>,
 *   leading whitespace is skipped and the rest is the answer text. Later blocks shift by one index
 *   when an answer text block follows the thinking block;
 * - messages (partials, final message, error): the same split as a pure function;
 * - only the very start of the answer counts: a <think> later in the text, a first block that is
 *   not text, or a provider that already streams thinking deltas leaves everything untouched;
 * - the thinking block has no signature, so pi-ai does not send it back on the next request
 *   (openai-completions replays only thinking it can map to a reasoning field).
 */
import { AssistantMessageEventStream } from "@earendil-works/pi-ai/utils/event-stream";

const OPEN = "<think>";
const CLOSE = "</think>";

/** Splits a leading <think>…</think> in the first (text) content block into a thinking block. */
export function splitLeadingThink(message) {
  const content = message?.content;
  if (!Array.isArray(content) || content.length === 0) return message;
  const first = content[0];
  if (!first || first.type !== "text") return message;
  const lead = String(first.text ?? "").trimStart();
  if (!lead.startsWith(OPEN)) return message;
  const body = lead.slice(OPEN.length);
  const end = body.indexOf(CLOSE);
  // Unclosed (cut off by max tokens or an abort): all of it is reasoning.
  const thinking = end >= 0 ? body.slice(0, end) : body;
  const rest = end >= 0 ? body.slice(end + CLOSE.length).trimStart() : "";
  const blocks = [{ type: "thinking", thinking }];
  if (rest.length > 0) blocks.push({ ...first, text: rest });
  return { ...message, content: [...blocks, ...content.slice(1)] };
}

/** Length of the longest suffix of `s` that is a proper prefix of </think>. */
function partialCloseLength(s) {
  for (let n = Math.min(CLOSE.length - 1, s.length); n > 0; n--) {
    if (CLOSE.startsWith(s.slice(s.length - n))) return n;
  }
  return 0;
}

/**
 * Wraps a pi-ai AssistantMessageEventStream: a leading <think>…</think> in the first text block
 * becomes thinking_start / thinking_delta / thinking_end, and the final message gets the split.
 */
export function splitLeadingThinkStream(source) {
  const out = new AssistantMessageEventStream();
  // idle -> detect -> (think -> lead -> text) | pass ; off when the first block is not text or
  // the provider streams thinking itself; done after the first text block.
  let state = "idle";
  let textIdx = -1;
  let shift = 0;
  let buf = "";
  let pending = "";
  let thinking = "";
  let text = "";

  const view = (e) => splitLeadingThink(e.partial);
  const emit = (e) => out.push(e);

  function thinkingDelta(delta, partial) {
    if (!delta) return;
    thinking += delta;
    emit({ type: "thinking_delta", contentIndex: textIdx, delta, partial });
  }

  function feedLead(s, partial) {
    const trimmed = s.replace(/^\s+/, "");
    if (!trimmed) return;
    state = "text";
    emit({ type: "text_start", contentIndex: textIdx + 1, partial });
    text += trimmed;
    emit({ type: "text_delta", contentIndex: textIdx + 1, delta: trimmed, partial });
  }

  function feedThink(s, partial) {
    pending += s;
    const i = pending.indexOf(CLOSE);
    if (i >= 0) {
      thinkingDelta(pending.slice(0, i), partial);
      emit({ type: "thinking_end", contentIndex: textIdx, content: thinking, partial });
      const after = pending.slice(i + CLOSE.length);
      pending = "";
      state = "lead";
      feedLead(after, partial);
      return;
    }
    const keep = partialCloseLength(pending);
    thinkingDelta(pending.slice(0, pending.length - keep), partial);
    pending = pending.slice(pending.length - keep);
  }

  function onDelta(e) {
    const partial = view(e);
    switch (state) {
      case "detect": {
        buf += e.delta;
        const t = buf.trimStart();
        if (t.length === 0 || (t.length < OPEN.length && OPEN.startsWith(t))) return; // undecided
        if (t.startsWith(OPEN)) {
          state = "think";
          emit({ type: "thinking_start", contentIndex: textIdx, partial });
          feedThink(t.slice(OPEN.length), partial);
        } else {
          state = "pass";
          emit({ type: "text_start", contentIndex: textIdx, partial });
          emit({ type: "text_delta", contentIndex: textIdx, delta: buf, partial });
        }
        return;
      }
      case "think":
        feedThink(e.delta, partial);
        return;
      case "lead":
        feedLead(e.delta, partial);
        return;
      case "text":
        text += e.delta;
        emit({ type: "text_delta", contentIndex: textIdx + 1, delta: e.delta, partial });
        return;
      default: // pass
        emit({ ...e, partial });
    }
  }

  function onEnd(e) {
    const partial = view(e);
    switch (state) {
      case "detect": // too short to decide: it is plain text
        emit({ type: "text_start", contentIndex: textIdx, partial });
        if (buf) emit({ type: "text_delta", contentIndex: textIdx, delta: buf, partial });
        emit({ ...e, partial });
        break;
      case "think": // never closed: everything was reasoning
        thinkingDelta(pending, partial);
        pending = "";
        emit({ type: "thinking_end", contentIndex: textIdx, content: thinking, partial });
        break;
      case "lead": // nothing but whitespace after </think>: no answer text block
        break;
      case "text":
        emit({ type: "text_end", contentIndex: textIdx + 1, content: text, partial });
        shift = 1;
        break;
      default: // pass
        emit({ ...e, partial });
    }
    state = "done";
  }

  (async () => {
    let finished = false;
    try {
      for await (const e of source) {
        if (e.type === "done") {
          finished = true;
          emit({ ...e, message: splitLeadingThink(e.message) });
          continue;
        }
        if (e.type === "error") {
          finished = true;
          emit({ ...e, error: splitLeadingThink(e.error) });
          continue;
        }
        const own = e.contentIndex === textIdx && state !== "off" && state !== "done";
        if (e.type === "text_start" && state === "idle" && e.contentIndex === 0) {
          state = "detect";
          textIdx = e.contentIndex;
          continue; // decided with the first deltas
        }
        if (state === "idle" && (e.type === "thinking_start" || e.type === "toolcall_start" || e.type === "text_start")) {
          state = "off";
        }
        if (own && e.type === "text_delta") {
          onDelta(e);
          continue;
        }
        if (own && e.type === "text_end") {
          onEnd(e);
          continue;
        }
        const shifted = shift > 0 && typeof e.contentIndex === "number" && e.contentIndex > textIdx
          ? { ...e, contentIndex: e.contentIndex + shift }
          : e;
        emit(state === "off" ? e : { ...shifted, partial: view(e) });
      }
    } catch (err) {
      // Never let a rejection escape: in QuickJS an unhandled rejection ends the pump (S8).
      if (!finished) {
        const error = {
          role: "assistant", content: [], api: "openai-completions", provider: "", model: "",
          usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
          stopReason: "error", errorMessage: `think-tag split failed: ${err?.message ?? err}`, timestamp: Date.now(),
        };
        emit({ type: "error", reason: "error", error });
      }
    } finally {
      out.end();
    }
  })();
  return out;
}
