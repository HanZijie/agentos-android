/*
 * Model compat that pi-ai 0.86.1 does not have. Keys live in `model.compat` next to pi-ai's own
 * (pi-ai ignores unknown keys) and carry an `agentos` prefix so they never collide with them.
 * This glue knows no provider: which models get which keys is decided where models are made
 * (ModelCatalog.customModel on the Kotlin side, for example MiniMax's OpenAI-compatible hosts).
 *
 * - agentosExtraBody: object; its keys are added to the request body when the body does not
 *   have them yet (through pi-ai's onPayload hook; never overrides what pi-ai built: model,
 *   messages, tools, ...). MiniMax: {"reasoning_split": true}.
 * - agentosThinkTags: boolean; on the openai-completions route, a leading <think>…</think> in
 *   the answer becomes a thinking block (think-tags.js). Default: on for custom endpoints
 *   (provider "custom"), off for catalog presets.
 * - agentosThinkTagsReplay: "drop" (default) or "keep"; what the next request does with such a
 *   thinking block: leave it out (Qwen3 and most reasoning models want no reasoning in the
 *   history), or put it back as <think>…</think> at the start of the answer (MiniMax-M2: "passed
 *   back in its original format").
 */

/** Keys to add to the request body for this model (possibly empty). */
export function extraBody(model) {
  const explicit = model?.compat?.agentosExtraBody;
  return explicit && typeof explicit === "object" && !Array.isArray(explicit) ? { ...explicit } : {};
}

/** Whether a leading <think>…</think> is split into a thinking block for this model. */
export function thinkTagsEnabled(model) {
  if (model?.api !== "openai-completions") return false;
  const flag = model.compat?.agentosThinkTags;
  if (flag !== undefined) return flag === true;
  return model.provider === "custom";
}

/** "keep" or "drop": replay of thinking that was split out of <think> tags. */
export function thinkTagsReplay(model) {
  return model?.compat?.agentosThinkTagsReplay === "keep" ? "keep" : "drop";
}

/** `params` with the keys of `extra` it does not have yet; the same object when nothing is added. */
export function addMissing(params, extra) {
  const keys = Object.keys(extra ?? {}).filter((k) => !(k in params));
  if (keys.length === 0) return params;
  const next = { ...params };
  for (const k of keys) next[k] = extra[k];
  return next;
}
