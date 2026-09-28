# sideagentd Scheduler prototype

This directory is the process-local first implementation of the AgentOS data
plane. It intentionally has no Android Binder, Activity dependency, or model
network dependency:

```text
SessionStore (SQLite WAL / durable events)
        ↓
SessionScheduler (serial per Session, bounded cross-Session concurrency)
        ↓
Worker (fake subprocess or Pi ACP worker)
```

The Scheduler owns Session state, task idempotency, priority/fairness,
cancellation, deadlines, crash fencing, event sequencing, snapshots, and
Plugin capability leases. A Worker owns only one assigned runtime attempt and
returns events/results to the Scheduler. Pi never owns AgentOS event storage,
recovery, frontend subscriptions, or deduplication.

## Automatic Session selection

`SessionSelector` builds a per-user view of Sessions active in the last 30
minutes, keeps at most 254 existing Sessions, and appends the fixed
`new_session` Choice. If fewer than 20 active Sessions exist, it can add the
newest non-terminal Sessions as labelled `stale` cold candidates; those records
are not active and do not extend the 30-minute TTL. It builds each candidate
Brief from the first query, first/latest answer, and last two completed
query/answer turns. The Jev adapter maps those candidates to the
`/v1/systemone` Choice schema and accepts only an exact returned `choiceId`.
When the complete request exceeds the configured input budget, Jev is called
in bounded candidate stages and then once more over the stage winners plus
`new_session`.

`createSideagentd()` wires `JevHttpClient` by default. The API key is read from
`AGENTOS_JEV_API_KEY` (or `TYPESAFE_API_KEY`) at process start; see
`jev.env.example`. Missing keys, timeouts, provider errors, and invalid choices
fall back to `new_session` and never block durable input enqueue.

The AOSP overlay contains the corresponding native data plane. The `sideagentd`
worker reads a mode-0600 `/data/agent/secrets/agent.env`, sends
Anthropic-compatible requests to MiniMax-M3, uses the Jev Choice endpoint for
the automatic Session path, and writes session/task/event state to
`/data/agent/state/sideagentd.state`. A daemon restart fences an unfinished
request as `unknown` and emits `task.recovery_required`; it never replays that
attempt automatically. A userdebug Cuttlefish acceptance run is provided by
`tools/aosp/test-agentos-runtime.py`.

```js
const receipt = await daemon.scheduler.submitAutoInput({
  userId: 'user-0', cwd: '/workspace', clientRequestId: 'request-1',
  prompt: [{ type: 'text', text: '继续处理构建问题' }],
});
```

Run the prototype daemon:

```bash
AGENTOS_SESSION_DB=/tmp/agentos.sqlite node system/agent/daemon/main.mjs
```

Worker kinds are selected by `createSession({ worker: "fake" | "pi" })`.
`fake` is used by scheduler tests; `pi` starts the existing Pi ACP adapter
through a child worker and carries a private Pi session checkpoint between
Attempts. Unknown Attempts are never automatically replayed.

## Plugin Broker

`plugin-broker.mjs` is the reference implementation of
[Plugin Injection Contract v1](../contracts/plugin-injection-v1.md): descriptor
v3 validation, per-user enablement, on-demand bind/handshake with fresh
`pluginSessionId`s, tool invocation (request ids, deadlines, cancellation,
idempotency keys, durable `tool_operations` records, and no auto-replay) and
resource collection into per-turn system-reminder blocks that never touch the
Session event history. External operations are fenced as `unknown` on daemon
restart or an unconfirmed deadline; reusing their idempotency key returns the
unknown result until an explicit reconciliation path is added. The "bind" boundary
is an injected factory returning `{ endpoint, linkToDeath, unbind }`; Android
Binder, freezer exemptions and SELinux stay platform work
([aosp-todo](../../../platform/aosp-integration/aosp-todo.md) §5).
`createPluginBroker(scheduler, store)` in `index.mjs` wires lease validation
and death revocation to the Scheduler's frozen lease semantics. Contract §13
reference tests 1–12 live in `test/plugin-broker.test.mjs` and persistence
coverage lives in `test/store.test.mjs`; feeding collected
reminders into the worker model input is the next integration step and is not
part of this broker.

Run all daemon tests:

```bash
node --test system/agent/daemon/test/*.test.mjs
```
