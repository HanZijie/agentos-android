/*
 * Replaces @anthropic-ai/sdk/internal/node.mjs.
 *
 * The SDK only reaches these modules from Node-only paths (credential files,
 * skill archives, the local agent toolset). In AgentOS those paths must never
 * run: key handling and I/O belong to the Kotlin host. Every member is a
 * function that throws, so an accidental call fails loudly instead of silently.
 */
function unavailable(moduleName) {
  return new Proxy(
    {},
    {
      get(_target, key) {
        if (typeof key === "symbol" || key === "then" || key === "__esModule") return undefined;
        return () => {
          throw new Error(`node:${moduleName}.${String(key)} is not available in the AgentOS JS runtime`);
        };
      },
    },
  );
}

export const child_process = unavailable("child_process");
export const crypto = unavailable("crypto");
export const fs = unavailable("fs");
export const os = unavailable("os");
export const path = unavailable("path");
export const stream = unavailable("stream");
export const util = unavailable("util");
