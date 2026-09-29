/*
 * Model compat that pi-ai 0.86.1 does not have. Keys live in `model.compat` next to pi-ai's own
 * (pi-ai ignores unknown keys) and carry an `agentos` prefix so they never collide with them.
 *
 * - agentosExtraBody: object; its keys are added to the request body when the body does not
 *   have them yet (never overrides what pi-ai built: model, messages, tools, ...).
 * - agentosThinkTags: boolean; on the openai-completions route, a leading <think>…</think> in
 *   the answer becomes a thinking block (think-tags.js). Default: on for custom endpoints
 *   (provider "custom"), off for catalog presets.
 *
 * MiniMax's OpenAI-compatible API returns M2.x reasoning inline as <think>…</think> unless the
 * request sets `reasoning_split: true`, which moves it to `reasoning_content` /
 * `reasoning_details` (platform.minimax.io, Chat Completions API reference). pi-ai already reads
 * both, so for MiniMax hosts the flag is added by default; an explicit agentosExtraBody wins.
 */

const MINIMAX_HOST = /^https?:\/\/(?:[^/@:?#]+\.)?(?:minimax\.io|minimaxi\.com|minimax\.cn)(?::\d+)?(?:[/?#]|$)/i;

/** Keys to add to the request body for this model (possibly empty). */
export function extraBody(model) {
  const detected = model?.api === "openai-completions" && MINIMAX_HOST.test(String(model.baseUrl ?? "")) ? { reasoning_split: true } : {};
  const explicit = model?.compat?.agentosExtraBody;
  return { ...detected, ...(explicit && typeof explicit === "object" && !Array.isArray(explicit) ? explicit : {}) };
}

/** Whether a leading <think>…</think> is split into a thinking block for this model. */
export function thinkTagsEnabled(model) {
  if (model?.api !== "openai-completions") return false;
  const flag = model.compat?.agentosThinkTags;
  if (flag !== undefined) return flag === true;
  return model.provider === "custom";
}

/** `params` with the keys of `extra` it does not have yet; the same object when nothing is added. */
export function addMissing(params, extra) {
  const keys = Object.keys(extra ?? {}).filter((k) => !(k in params));
  if (keys.length === 0) return params;
  const next = { ...params };
  for (const k of keys) next[k] = extra[k];
  return next;
}
