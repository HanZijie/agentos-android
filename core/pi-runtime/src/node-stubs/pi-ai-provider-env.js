/*
 * Replaces @earendil-works/pi-ai/dist/utils/provider-env.js.
 *
 * Upstream falls back to process.env and, under Bun, to /proc/self/environ via
 * require("node:fs"). In AgentOS the JS runtime has no environment and no keys:
 * only explicitly passed overrides are honoured.
 */
export function getProviderEnvValue(name, env) {
  return (env && env[name]) || undefined;
}
