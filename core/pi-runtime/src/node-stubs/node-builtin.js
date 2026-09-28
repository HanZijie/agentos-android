/*
 * Fallback for any other `node:*` import. build.mjs reports every module that
 * lands here; the S8 build expects none.
 */
const handler = {
  get(_target, key) {
    if (typeof key === "symbol" || key === "then" || key === "__esModule") return undefined;
    return () => {
      throw new Error(`Node built-in member ${String(key)} is not available in the AgentOS JS runtime`);
    };
  },
};
export default new Proxy({}, handler);
