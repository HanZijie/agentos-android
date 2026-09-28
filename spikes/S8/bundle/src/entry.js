/*
 * S8 entry: runs upstream pi-agent-core `Agent` instances inside one JS runtime.
 *
 * Execution model ("pump"): the host evaluates `__pi_main()` once. It loops on
 * `__host_next()` for commands and dispatches each one without awaiting it, so
 * several sessions (and an `abort` for a session that is mid-prompt) can make
 * progress concurrently on the single JS thread. Results go back through
 * `__host_emit({t:"reply"})`, lifecycle events through `__host_emit({t:"event"})`.
 *
 * Commands (JSON):
 *   {id, op:"create",  sid, model, systemPrompt?, tools?, messages?, thinkingLevel?}
 *   {id, op:"prompt",  sid, text, images?}          -> reply after agent_end
 *   {id, op:"abort",   sid}
 *   {id, op:"history", sid}                          -> messages (for persistence)
 *   {id, op:"dispose", sid}
 *   {id, op:"stats"}
 *   {id, op:"ping"}
 */
import "./polyfills.js";
import { Agent } from "pi-agent-core/agent";
import { call, emit, inflightFetchCount, nextCommand } from "./host-bridge.js";
import { hostStreamFn, SUPPORTED_APIS } from "./stream-fn.js";

const sessions = new Map();

function toolFromDeclaration(sid, decl) {
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
function compactEvent(event) {
  switch (event.type) {
    case "message_update": {
      const e = event.assistantMessageEvent;
      const out = { type: e.type, contentIndex: e.contentIndex };
      if (e.delta !== undefined) out.delta = e.delta;
      if (e.type === "toolcall_end") out.toolCall = e.toolCall;
      if (e.type === "done") out.reason = e.reason;
      if (e.type === "error") out.reason = e.reason;
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

const ops = {
  ping: async () => ({ pong: true, apis: SUPPORTED_APIS }),

  create: async ({ sid, model, systemPrompt, tools, messages, thinkingLevel }) => {
    if (sessions.has(sid)) throw new Error(`Session already exists: ${sid}`);
    if (!model?.api || !model?.id) throw new Error("create: model is required");
    const agent = new Agent({
      streamFn: hostStreamFn,
      sessionId: sid,
      toolExecution: "sequential",
      initialState: {
        model,
        systemPrompt: systemPrompt ?? "",
        thinkingLevel: thinkingLevel ?? "off",
        tools: (tools ?? []).map((d) => toolFromDeclaration(sid, d)),
        messages: messages ?? [],
      },
      beforeToolCall: async ({ toolCall, args }) => {
        const decision = await call("beforeToolCall", { sid, toolCallId: toolCall.id, name: toolCall.name, args });
        return decision?.block ? { block: true, reason: decision.reason ?? "Blocked by host" } : undefined;
      },
    });
    const unsubscribe = agent.subscribe((event) => {
      emit({ t: "event", sid, e: compactEvent(event) });
    });
    sessions.set(sid, { agent, unsubscribe });
    return { sid, messages: agent.state.messages.length };
  },

  prompt: async ({ sid, text, images }) => {
    const { agent } = requireSession(sid);
    await agent.prompt(text, images ?? []);
    await agent.waitForIdle();
    const last = lastAssistant(agent.state.messages);
    return {
      stopReason: last?.stopReason,
      errorMessage: last?.errorMessage,
      text: (last?.content ?? []).filter((c) => c.type === "text").map((c) => c.text).join(""),
      messages: agent.state.messages.length,
    };
  },

  abort: async ({ sid }) => {
    requireSession(sid).agent.abort();
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

  // S8 only: a rejection nobody handles, still unhandled after a host round trip.
  // Probes how the embedding treats unhandled rejections (quickjs-kt fails the root evaluation).
  debugUnhandledRejection: async () => {
    Promise.reject(new Error("S8 deliberate unhandled rejection"));
    await new Promise((resolve) => setTimeout(resolve, 20));
    return { injected: true };
  },
};

function errorToJson(error) {
  return { message: error?.message ?? String(error), name: error?.name, stack: error?.stack };
}

globalThis.__pi_main = async function main() {
  emit({ t: "ready", apis: SUPPORTED_APIS });
  for (;;) {
    const cmd = await nextCommand();
    if (cmd == null) break;
    const handler = ops[cmd.op];
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
