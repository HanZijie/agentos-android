# AgentOS Pi ACP adapter

This package exposes Pi as an ACP v1 Agent over stdio JSON-RPC. It keeps the adapter small: ACP lifecycle and event translation live here, while Pi owns model calls, tools, and session execution.

## Run

```bash
npm install
npm run build
node dist/src/index.js
```

The process reads ACP JSONL from stdin and writes ACP JSONL to stdout. It is intended to run inside `sideagentd` or as a local development process; Android Binder/WebSocket transport belongs to the service boundary.

## Current support

- ACP v1 `initialize`, `session/new`, `session/prompt`, `session/cancel`, `session/close`, and `authenticate`.
- Text, images, resource links, embedded text resources, assistant/thought streaming, tool-call updates, and `session/request_permission`.
- Pi's `AgentSession` SDK with Pi's standard tools and session persistence.

The adapter intentionally does not advertise session loading/resume, MCP, terminal delegation, or filesystem delegation until each behavior has a concrete implementation and test coverage.

## Configuration seam

The runtime keeps an internal placeholder only:

```ts
interface PiRuntimeConfigPlaceholder {
  mcp?: unknown;
  plugins?: unknown;
  hooks?: unknown;
}
```

Non-empty placeholder configuration is rejected explicitly. This prevents a caller from believing that MCP, Plugin, or Hooks configuration took effect while leaving room to add the real configuration contract later.

## Validation

```bash
npm run check
npm run build
```
