/*
 * Model access for Pi: pi-ai protocol adapters for exactly two API families.
 * All I/O goes through the host fetch; the key is a placeholder that the host
 * replaces; retries are disabled here because the host owns retry/deadline/cancel.
 *
 * This is the custom `streamFn` handed to Pi. If an official SDK ever stops
 * working in QuickJS, the fallback (host-implemented protocol families) plugs in here.
 */
import { streamSimple as anthropicMessagesStream } from "@earendil-works/pi-ai/api/anthropic-messages";
import { streamSimple as openaiCompletionsStream } from "@earendil-works/pi-ai/api/openai-completions";
import { AssistantMessageEventStream } from "@earendil-works/pi-ai/utils/event-stream";
import { hostFetch, PLACEHOLDER_API_KEY } from "./host-bridge.js";

const FAMILIES = {
  "anthropic-messages": anthropicMessagesStream,
  "openai-completions": openaiCompletionsStream,
};

export const SUPPORTED_APIS = Object.keys(FAMILIES);

function errorStream(model, message) {
  const stream = new AssistantMessageEventStream();
  const error = {
    role: "assistant",
    content: [],
    api: model.api,
    provider: model.provider,
    model: model.id,
    usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
    stopReason: "error",
    errorMessage: message,
    timestamp: Date.now(),
  };
  stream.push({ type: "error", reason: "error", error });
  stream.end(error);
  return stream;
}

export function hostStreamFn(model, context, options = {}) {
  const impl = FAMILIES[model.api];
  if (!impl) return errorStream(model, `Unsupported model API in this build: ${model.api}`);
  const sid = options.sessionId ?? null;
  return impl(model, context, {
    ...options,
    apiKey: PLACEHOLDER_API_KEY,
    fetch: (input, init) => hostFetch(input, init, { sid }),
    maxRetries: 0,
  });
}
