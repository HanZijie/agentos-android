# Agent output stream v1

## Transport

The Android adapter returns a read-only `ParcelFileDescriptor` pipe from `subscribeOutput`. Each frame is a 4-byte big-endian unsigned length followed by one UTF-8 JSON envelope. The pipe carries events only; control operations remain Binder calls.

A slow reader must not block `sideagentd`. The adapter has a bounded per-subscription buffer. When it exceeds the limit, the subscription closes with `cursor_too_old`/`buffer_overflow`; the frontend reconnects with `getSnapshot` and `afterSequence`.

## Envelope

```json
{
  "protocolVersion": 1,
  "sessionId": "session-1",
  "taskId": "task-1",
  "requestId": "request-1",
  "sequence": 42,
  "eventType": "message_update",
  "timestamp": 1710000000000,
  "payload": {"text": "..."}
}
```

For `task.failed`, the envelope includes a structured error:

```json
{
  "protocolVersion": 1,
  "sessionId": "session-1",
  "taskId": "task-1",
  "requestId": "request-1",
  "sequence": 43,
  "eventType": "task.failed",
  "timestamp": 1710000000000,
  "payload": {},
  "error": {
    "code": "plugin_unavailable",
    "message": "Notes Plugin is not registered",
    "retryable": true
  }
}
```

## Event types

- `agent_start`, `agent_end`, `agent_settled`
- `turn_start`, `turn_end`
- `message_start`, `message_update`, `message_end`
- `tool_execution_start`, `tool_execution_update`, `tool_execution_end`
- `queue_update`, `compaction_start`, `compaction_end`
- `task.cancel_requested`, `task.cancelled`, `task.failed`
- `session.state_changed`, `task.queued`, `task.started`, `task.completed`, `task.recovery_required`
- `capability.lease_granted`, `capability.lease_revoked`

## Rules

- `sequence` only increases within one Session.
- Event names and request semantics follow [Agent Bus v1](agent-bus-v1.md).
- An event is written to Event Store before it is sent to subscribers.
- Subscribers provide `afterSequence`; the service can return a gap requiring a snapshot.
- `requestId` correlates one frontend input or cancel request; system events may omit it.
- `task.failed` carries `error.code`, `error.message`, and `error.retryable`.
- Cancellation is requested through Binder and confirmed by `task.cancelled`.
- `payload` must not contain API keys, OAuth tokens, or unauthorized Plugin-private data.
- Schema changes use `protocolVersion` and additive compatible fields.
