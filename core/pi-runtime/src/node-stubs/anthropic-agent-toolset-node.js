/*
 * Replaces @anthropic-ai/sdk/tools/agent-toolset/node.mjs (local bash/read/write
 * tools executed by the SDK on the Node host). AgentOS tools are MCP tools
 * executed by the Kotlin Broker, so this module is intentionally empty.
 */
export function betaAgentToolset20260401() {
  throw new Error("The Anthropic SDK local agent toolset is not available in the AgentOS JS runtime");
}
