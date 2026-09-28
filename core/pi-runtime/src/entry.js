/*
 * AgentOS Pi runtime entry: upstream pi-agent-core `Agent` instances inside one JS runtime.
 *
 * Execution model ("pump", see docs/spikes/S8.md b): the host evaluates `__pi_main()`
 * once. It loops on `__host_next()` for commands and dispatches each one without
 * awaiting it, so several sessions (and an `abort` for a session that is mid-prompt)
 * make progress concurrently on the single JS thread. Replies go back through
 * `__host_emit({t:"reply"})`, lifecycle events through `__host_emit({t:"event"})`.
 *
 * Commands ({id, op, ...}); every command gets exactly one reply {t:"reply", id, ok, value|error}:
 *   ping                                         -> {protocol, apis}
 *   create   {sid, model, systemPrompt?, tools?, messages?, thinkingLevel?}
 *                                                -> {sid, messages}     (messages = restore)
 *   prompt   {sid, text, images?}                -> after agent_end: {stopReason, errorMessage?, text, usage?, messageCount, appended}
 *   abort    {sid}                               -> {}                  (the prompt then replies stopReason "aborted")
 *   setTools {sid, tools}                        -> {tools}             (takes effect on the next provider request)
 *   setModel {sid, model, thinkingLevel?}        -> {}
 *   setSystemPrompt {sid, systemPrompt}          -> {messageCount}      (appends a system message replacing the "agentos" section)
 *   history  {sid}                               -> messages[]          (full transcript, system message first)
 *   dispose  {sid}                               -> {disposed}
 *   stats                                        -> {sessions, inflightFetches, activeTimers}
 *
 * Events: {t:"event", sid, e} where e is a compacted Pi AgentEvent (see compactEvent).
 * Host calls: "tool", "beforeToolCall", "afterToolCall" (see core/pi-runtime/README.md).
 */
import "./polyfills.js";
import { Agent } from "pi-agent-core/agent";
import { toToolDeclaration } from "@earendil-works/pi-ai/utils/transcript";
import { call, emit, inflightFetchCount, nextCommand, PROTOCOL_VERSION } from "./host-bridge.js";
import { hostStreamFn, SUPPORTED_APIS } from "./stream-fn.js";

const sessions = new Map();

function toolFromDeclaration(sid, decl) {
  if (!decl?.name) throw new Error("tool declaration without a name");
  return {
    name: decl.name,
    label: decl.label ?? decl.name,
    description: decl.description ?? "",
    parameters: decl.parameters ?? { type: "object", properties: {} },
    executionMode: decl.executionMode ?? "sequential",
    async execute(toolCallId, args, signal) {
      if (signal?.aborted) throw new Error("Tool call aborted");
      const pending = call("tool", { sid, toolCallId, name: decl.name, args });
      const result = await (signal
        ? new Promise((resolve, reject) => {
            const onAbort = () => {
              emit({ t: "tool_cancel", sid, toolCallId });
              reject(new Error("Tool call aborted"));
            };
            signal.addEventListener("abort", onAbort, { once: true });
            pending.then(
              (v) => { signal.removeEventListener("abort", onAbort); resolve(v); },
              (e) => { signal.removeEventListener("abort", onAbort); reject(e); },
            );
          })
        : pending);
      const content = Array.isArray(result?.content) ? result.content : [{ type: "text", text: String(result?.text ?? "") }];
      if (result?.isError) {
        throw new Error(content.filter((c) => c.type === "text").map((c) => c.text).join("\n") || "Tool failed");
      }
      return { content, details: result?.details };
    },
  };
}

/** Keep events small: deltas instead of whole partial messages on every token. */
// Assistant stream updates that carry no information beyond message_start / message_end /
// toolcall_end are not sent to the host (core/contracts/events.md, section 3).
const DROPPED_UPDATES = new Set(["start", "text_start", "text_end", "thinking_start", "thinking_end", "toolcall_start", "toolcall_delta"]);

function compactEvent(event) {
  switch (event.type) {
    case "message_update": {
      const e = event.assistantMessageEvent;
      if (DROPPED_UPDATES.has(e.type)) return null;
      const out = { type: e.type, contentIndex: e.contentIndex };
      if (e.delta !== undefined) out.delta = e.delta;
      if (e.type === "toolcall_end") out.toolCall = e.toolCall;
      if (e.type === "done" || e.type === "error") out.reason = e.reason;
      return { type: "message_update", role: event.message?.role, update: out };
    }
    case "message_start":
      return { type: "message_start", role: event.message?.role };
    case "message_end":
      return { type: "message_end", message: event.message };
    case "turn_end":
      return { type: "turn_end", stopReason: event.message?.stopReason, errorMessage: event.message?.errorMessage, toolResults: event.toolResults?.length ?? 0 };
    case "agent_end":
      return { type: "agent_end", messages: event.messages?.length ?? 0 };
    case "tool_execution_start":
      return { type: event.type, toolCallId: event.toolCallId, toolName: event.toolName, args: event.args };
    case "tool_execution_update":
      return { type: event.type, toolCallId: event.toolCallId, toolName: event.toolName, partialResult: event.partialResult };
    case "tool_execution_end":
      return { type: event.type, toolCallId: event.toolCallId, toolName: event.toolName, result: event.result, isError: event.isError };
    default:
      return { type: event.type };
  }
}

function requireSession(sid) {
  const s = sessions.get(sid);
  if (!s) throw new Error(`Unknown session: ${sid}`);
  return s;
}

function lastAssistant(messages) {
  for (let i = messages.length - 1; i >= 0; i--) if (messages[i].role === "assistant") return messages[i];
  return undefined;
}

/** Section name that carries the host's system prompt, so a later system message can replace it. */
const SYSTEM_SECTION = "agentos";

/**
 * The leading system message for a new session. Same as Pi's own initial message, except that
 * the prompt lives in the named section SYSTEM_SECTION instead of `content`: a later
 * `setSystemPrompt` then replaces it (pi-ai merges sections, later values win).
 */
function initialSystemMessage(systemPrompt, tools) {
  const declared = tools.map(toToolDeclaration);
  if (!systemPrompt && declared.length === 0) return [];
  return [{
    role: "system",
    content: "",
    ...(systemPrompt ? { sections: { [SYSTEM_SECTION]: systemPrompt } } : {}),
    ...(declared.length ? { toolsAdded: declared } : {}),
    timestamp: 0,
  }];
}

const ops = {
  ping: async () => ({ protocol: PROTOCOL_VERSION, apis: SUPPORTED_APIS }),

  create: async ({ sid, model, systemPrompt, tools, messages, thinkingLevel }) => {
    if (typeof sid !== "string" || !sid) throw new Error("create: sid is required");
    if (sessions.has(sid)) throw new Error(`Session already exists: ${sid}`);
    if (!model?.api || !model?.id) throw new Error("create: model is required");
    const agentTools = (tools ?? []).map((d) => toolFromDeclaration(sid, d));
    const agent = new Agent({
      streamFn: hostStreamFn,
      sessionId: sid,
      toolExecution: "sequential",
      initialState: {
        model,
        thinkingLevel: thinkingLevel ?? "off",
        tools: agentTools,
        // A restored transcript is used verbatim; a new one starts with our system message.
        messages: messages ?? initialSystemMessage(systemPrompt ?? "", agentTools),
      },
      // After an abort, end the run at the next turn boundary instead of starting another
      // provider request that would only produce an empty aborted assistant message.
      shouldStopAfterTurn: async (_context, signal) => signal?.aborted === true,
      beforeToolCall: async ({ toolCall, args }) => {
        const decision = await call("beforeToolCall", { sid, toolCallId: toolCall.id, name: toolCall.name, args });
        if (!decision?.block) return undefined;
        return { block: true, reason: decision.reason ?? "Blocked by host", ...(decision.terminate ? { terminate: true } : {}) };
      },
      afterToolCall: async ({ toolCall, args, result, isError }) => {
        const override = await call("afterToolCall", { sid, toolCallId: toolCall.id, name: toolCall.name, args, result, isError });
        if (!override || typeof override !== "object") return undefined;
        const out = {};
        for (const key of ["content", "details", "isError", "terminate"]) if (override[key] !== undefined) out[key] = override[key];
        return Object.keys(out).length ? out : undefined;
      },
    });
    const unsubscribe = agent.subscribe((event) => {
      const e = compactEvent(event);
      if (e) emit({ t: "event", sid, e });
    });
    sessions.set(sid, { agent, unsubscribe });
    return { sid, messages: agent.state.messages.length };
  },

  prompt: async ({ sid, text, images }) => {
    const { agent } = requireSession(sid);
    const before = agent.state.messages.length;
    await agent.prompt(String(text ?? ""), images ?? []);
    await agent.waitForIdle();
    const messages = agent.state.messages;
    const last = lastAssistant(messages);
    return {
      stopReason: last?.stopReason,
      errorMessage: last?.errorMessage,
      text: (last?.content ?? []).filter((c) => c.type === "text").map((c) => c.text).join(""),
      usage: last?.usage,
      messageCount: messages.length,
      appended: messages.slice(before),
    };
  },

  setSystemPrompt: async ({ sid, systemPrompt }) => {
    const { agent } = requireSession(sid);
    if (agent.state.isStreaming) throw new Error("setSystemPrompt: a prompt is running");
    agent.state.messages = [
      ...agent.state.messages,
      { role: "system", content: "", sections: { [SYSTEM_SECTION]: String(systemPrompt ?? "") }, timestamp: Date.now() },
    ];
    return { messageCount: agent.state.messages.length };
  },

  abort: async ({ sid }) => {
    requireSession(sid).agent.abort();
    return {};
  },

  setTools: async ({ sid, tools }) => {
    const { agent } = requireSession(sid);
    agent.state.tools = (tools ?? []).map((d) => toolFromDeclaration(sid, d));
    return { tools: agent.state.tools.length };
  },

  setModel: async ({ sid, model, thinkingLevel }) => {
    const { agent } = requireSession(sid);
    if (!model?.api || !model?.id) throw new Error("setModel: model is required");
    agent.state.model = model;
    if (thinkingLevel !== undefined) agent.state.thinkingLevel = thinkingLevel;
    return {};
  },

  history: async ({ sid }) => requireSession(sid).agent.state.messages,

  dispose: async ({ sid }) => {
    const s = sessions.get(sid);
    if (!s) return { disposed: false };
    s.agent.abort();
    await s.agent.waitForIdle();
    s.unsubscribe();
    sessions.delete(sid);
    return { disposed: true };
  },

  stats: async () => ({
    sessions: sessions.size,
    inflightFetches: inflightFetchCount(),
    activeTimers: globalThis.__pi_activeTimerCount(),
  }),
};

function errorToJson(error) {
  return { message: error?.message ?? String(error), name: error?.name, stack: error?.stack };
}

globalThis.__pi_main = async function main() {
  emit({ t: "ready", protocol: PROTOCOL_VERSION, apis: SUPPORTED_APIS });
  for (;;) {
    const cmd = await nextCommand();
    if (cmd == null) break;
    const handler = Object.prototype.hasOwnProperty.call(ops, cmd.op) ? ops[cmd.op] : undefined;
    const run = handler ? Promise.resolve().then(() => handler(cmd)) : Promise.reject(new Error(`Unknown op: ${cmd.op}`));
    run.then(
      (value) => emit({ t: "reply", id: cmd.id, ok: true, value }),
      (error) => emit({ t: "reply", id: cmd.id, ok: false, error: errorToJson(error) }),
    );
  }
  for (const [, s] of sessions) s.agent.abort();
  for (const [, s] of sessions) await s.agent.waitForIdle();
  sessions.clear();
  return "stopped";
};
