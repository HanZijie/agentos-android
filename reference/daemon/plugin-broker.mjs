import { randomUUID, createHash } from 'node:crypto';
import { check } from './errors.mjs';

// Reference implementation of Plugin Injection Contract v1
// (system/agent/contracts/plugin-injection-v1.md). Process-local: the "bind"
// boundary is a factory returning { endpoint, linkToDeath, unbind }; Android
// Binder, freezer behavior and SELinux are platform work (aosp-todo.md §5).

const defaults = {
  descriptorMaxBytes: 65536, maxToolsPerPlugin: 64, maxResourcesPerPlugin: 16,
  inlinePayloadMaxBytes: 262144, handshakeTimeoutMs: 5000, pluginIdleUnbindMs: 60000,
  resourceReadTimeoutMs: 2000, resourceMaxBytesCap: 8192, reminderBudgetBytes: 32768,
};
const NAME = /^[a-z][a-z0-9_.-]{0,63}$/;
const SEAM_CODES = new Set(['invalid_request', 'unknown_tool', 'invalid_args', 'not_entitled',
  'busy', 'timeout', 'cancelled', 'unavailable', 'internal']);
const bytes = v => Buffer.byteLength(v, 'utf8');
const str = v => typeof v === 'string' && v.length > 0;

export function validateDescriptor(raw, { packageName, config }) {
  check(raw && typeof raw === 'object' && !Array.isArray(raw), 'descriptor_invalid');
  check(bytes(JSON.stringify(raw)) <= config.descriptorMaxBytes, 'descriptor_too_large');
  check(raw.protocolVersion === 3, 'protocol_mismatch');
  check(raw.pluginId === packageName, 'plugin_identity_mismatch');
  check(str(raw.displayName) && str(raw.version), 'descriptor_invalid');
  const tools = raw.tools ?? [], resources = raw.resources ?? [], mcpServers = raw.mcpServers ?? [];
  check(Array.isArray(tools) && tools.length <= config.maxToolsPerPlugin, 'descriptor_invalid');
  check(Array.isArray(resources) && resources.length <= config.maxResourcesPerPlugin, 'descriptor_invalid');
  check(Array.isArray(mcpServers), 'descriptor_invalid');
  const names = new Set();
  const name = n => { check(str(n) && NAME.test(n) && !names.has(n), 'descriptor_invalid', n); names.add(n); return n; };
  return {
    pluginId: raw.pluginId, displayName: raw.displayName, version: raw.version,
    tools: tools.map(t => {
      check(t && typeof t === 'object', 'descriptor_invalid');
      check(t.inputSchema && typeof t.inputSchema === 'object' && !Array.isArray(t.inputSchema), 'descriptor_invalid');
      const sideEffects = t.sideEffects === undefined ? 'external' : t.sideEffects;
      check(['none', 'external'].includes(sideEffects), 'descriptor_invalid');
      check(t.timeoutHintMs === undefined || (Number.isSafeInteger(t.timeoutHintMs) && t.timeoutHintMs > 0), 'descriptor_invalid');
      return { name: name(t.name), description: String(t.description ?? ''), inputSchema: t.inputSchema,
        sideEffects, timeoutHintMs: t.timeoutHintMs ?? null };
    }),
    resources: resources.map(r => {
      check(r && typeof r === 'object', 'descriptor_invalid');
      const refresh = r.refresh === undefined ? 'per_turn' : r.refresh;
      check(['per_turn', 'on_change'].includes(refresh), 'descriptor_invalid');
      const maxBytes = r.maxBytes === undefined ? config.resourceMaxBytesCap : r.maxBytes;
      check(Number.isSafeInteger(maxBytes) && maxBytes > 0 && maxBytes <= config.resourceMaxBytesCap, 'descriptor_invalid');
      return { name: name(r.name), description: String(r.description ?? ''), refresh, maxBytes };
    }),
    mcpServers: mcpServers.map(s => {
      check(s && str(s.id) && s.transport === 'streamable-http' && str(s.url), 'descriptor_invalid');
      return { id: s.id, transport: s.transport, url: s.url };
    }),
  };
}

/**
 * Trusted host API. Identity facts come from installPackage (the PackageManager
 * stand-in), never from descriptor JSON. Lease semantics stay in the Scheduler;
 * the broker consumes them through validateLease/hasActiveLeases and reports
 * revocations through onSessionRevoked.
 */
export class PluginBroker {
  constructor({ policy = () => true, validateLease = () => false, onSessionRevoked = () => {},
    hasActiveLeases = () => false, now = Date.now, config = {}, store = null } = {}) {
    this.policy = policy; this.validateLease = validateLease; this.now = now;
    this.onSessionRevoked = onSessionRevoked; this.hasActiveLeases = hasActiveLeases;
    this.store = store;
    this.config = { ...defaults, ...config };
    for (const [k, v] of Object.entries(this.config)) check(Number.isSafeInteger(v) && v > 0, 'invalid_config', k);
    this.packages = new Map();   // packageName -> {uid, signature, version, protectedByBindPermission, hasManifestAnchor, endpointFactory}
    this.enabled = new Set();    // `${userId}/${pluginId}`
    this.sessions = new Map();   // pluginSessionId -> session
    this.byPlugin = new Map();   // `${userId}/${pluginId}` -> pluginSessionId
    this.handshakes = new Map(); // `${userId}/${pluginId}` -> Promise
    this.pending = new Set();    // deferred waits, settled by sweep() on deadline
    this.diag = new Map();       // pluginId -> counters
    this.operations = new Map(); // process-local fallback when no SessionStore is supplied
    this.liveOperations = new Map(); // operationId -> { operation, promise }
  }
  counters(pluginId) {
    let d = this.diag.get(pluginId);
    if (!d) this.diag.set(pluginId, d = { invokes: 0, reads: 0, readBytes: 0, truncated: 0, omitted: 0,
      duplicateResults: 0, lateResults: 0, errors: {} });
    return d;
  }
  error(pluginId, code) { const d = this.counters(pluginId); d.errors[code] = (d.errors[code] ?? 0) + 1; }

  installPackage({ packageName, uid = 10000, signature = 'sig', version = '1',
    protectedByBindPermission = true, hasManifestAnchor = true, endpointFactory }) {
    check(str(packageName) && typeof endpointFactory === 'function', 'invalid_params');
    check(!this.packages.has(packageName), 'already_installed');
    this.packages.set(packageName, { packageName, uid, signature, version, protectedByBindPermission, hasManifestAnchor, endpointFactory });
  }
  uninstallPackage(packageName) {
    this.packages.delete(packageName);
    for (const key of [...this.enabled]) if (key.endsWith(`/${packageName}`)) this.enabled.delete(key);
    for (const s of [...this.sessions.values()]) if (s.pluginId === packageName) this.close(s, 'package_removed');
  }
  setEnabled(userId, pluginId, enabled) {
    check(str(userId) && str(pluginId), 'invalid_params');
    const key = `${userId}/${pluginId}`;
    if (enabled) { this.enabled.add(key); return; }
    this.enabled.delete(key);
    const id = this.byPlugin.get(key);
    if (id) this.close(this.sessions.get(id), 'user_disabled');
  }

  // One deferred wait per outstanding call; sweep() settles expired waits so
  // tests and the daemon drive time explicitly (same style as SessionScheduler).
  defer(deadline, pluginId, kind) {
    const d = { deadline, pluginId, kind, settled: false, expired: false };
    d.promise = new Promise((resolve, reject) => {
      d.resolve = v => { if (!d.settled) { d.settled = true; this.pending.delete(d); resolve(v); } };
      d.reject = e => { if (!d.settled) { d.settled = true; this.pending.delete(d); reject(e); } };
    });
    this.pending.add(d);
    return d;
  }
  sweep() {
    const now = this.now();
    for (const d of [...this.pending]) if (d.deadline <= now) {
      this.error(d.pluginId, 'timeout');
      d.expired = true;
      d.kind === 'handshake' ? d.reject(Object.assign(new Error('handshake_timeout'), { code: 'handshake_timeout' }))
        : d.resolve({ status: 'error', error: { code: 'timeout', message: 'deadline exceeded', retryable: false } });
    }
    for (const s of [...this.sessions.values()])
      if (s.state === 'active' && !this.hasActiveLeases(s.pluginSessionId)
        && s.inflight.size === 0 && s.lastUsed + this.config.pluginIdleUnbindMs <= now) this.close(s, 'idle');
  }

  async ensureSession(userId, pluginId) {
    check(str(userId) && str(pluginId), 'invalid_params');
    const key = `${userId}/${pluginId}`;
    const existing = this.byPlugin.get(key);
    if (existing) { const s = this.sessions.get(existing); s.lastUsed = this.now(); return this.view(s); }
    let pendingHandshake = this.handshakes.get(key);
    if (!pendingHandshake) {
      pendingHandshake = this.handshake(userId, pluginId, key).finally(() => this.handshakes.delete(key));
      this.handshakes.set(key, pendingHandshake);
    }
    return pendingHandshake;
  }
  async handshake(userId, pluginId, key) {
    const pkg = this.packages.get(pluginId);
    check(pkg, 'plugin_not_installed');
    check(pkg.hasManifestAnchor, 'plugin_not_installed');       // dynamic push registration is a dev adapter only
    check(pkg.protectedByBindPermission, 'endpoint_not_protected');
    check(this.enabled.has(key), 'plugin_not_enabled');
    const binding = pkg.endpointFactory(userId);                 // BIND_AUTO_CREATE stand-in: starts/unfreezes the process
    check(binding?.endpoint && typeof binding.linkToDeath === 'function' && typeof binding.unbind === 'function', 'plugin_unavailable');
    const pluginSessionId = randomUUID();
    const wait = this.defer(this.now() + this.config.handshakeTimeoutMs, pluginId, 'handshake');
    let descriptor;
    try {
      Promise.resolve()
        .then(() => binding.endpoint.openPluginSession(pluginSessionId, {
          protocolVersions: ['plugin-injection/1'], hostVersion: '0.1.0', userId,
        }, this.hostCallback(pluginSessionId)))
        .then(wait.resolve, wait.reject);
      const raw = await wait.promise;
      descriptor = validateDescriptor(raw, { packageName: pkg.packageName, config: this.config });
    } catch (e) { binding.unbind(); this.error(pluginId, e.code ?? 'internal'); throw e; }
    const grant = (kind, list) => list.filter(x => this.policy({ userId, pluginId, kind, name: x.name }) === true);
    const tools = grant('tool', descriptor.tools), resources = grant('resource', descriptor.resources);
    if (tools.length + resources.length === 0) { binding.unbind(); this.error(pluginId, 'granted_empty'); check(false, 'granted_empty'); }
    const session = {
      pluginSessionId, userId, pluginId, key, binding, endpoint: binding.endpoint, state: 'active',
      granted: { tools: new Map(tools.map(t => [t.name, t])), resources: new Map(resources.map(r => [r.name, r])) },
      mcpServers: descriptor.mcpServers, dirty: new Set(), inflight: new Map(), lastUsed: this.now(),
    };
    this.sessions.set(pluginSessionId, session); this.byPlugin.set(session.key, pluginSessionId);
    binding.linkToDeath(() => this.onDeath(pluginSessionId));
    try { session.endpoint.sessionGranted(pluginSessionId, tools.map(t => t.name), resources.map(r => r.name)); } catch { /* oneway */ }
    return this.view(session);
  }
  hostCallback(pluginSessionId) {
    const live = () => { const s = this.sessions.get(pluginSessionId); return s?.state === 'active' ? s : null; };
    return {
      notifyResourcesChanged: (id, names) => { const s = id === pluginSessionId && live();
        if (s) for (const n of names ?? []) if (s.granted.resources.has(n)) s.dirty.add(n); },
      notifyCapabilitiesChanged: id => { const s = id === pluginSessionId && live();
        if (s) this.close(s, 'capabilities_changed'); },       // re-handshake happens on next ensureSession
      requestClose: (id, reason) => { const s = id === pluginSessionId && live();
        if (s) this.close(s, `plugin_requested:${reason ?? 'unspecified'}`); },
    };
  }
  view(s) {
    return { pluginSessionId: s.pluginSessionId, pluginId: s.pluginId,
      grantedTools: [...s.granted.tools.keys()].map(n => `${s.pluginId}/${n}`),
      grantedResources: [...s.granted.resources.keys()].map(n => `${s.pluginId}/${n}`),
      mcpServers: s.mcpServers };
  }
  split(capability) {
    check(str(capability) && capability.includes('/'), 'invalid_request');
    const i = capability.indexOf('/');
    return { pluginId: capability.slice(0, i), name: capability.slice(i + 1) };
  }
  onDeath(pluginSessionId) {
    const s = this.sessions.get(pluginSessionId);
    if (!s || s.state !== 'active') return;
    // In-flight sinks stay registered: late results may still settle; new calls are rejected.
    s.state = 'dead';
    this.byPlugin.delete(s.key); this.handshakes.delete(s.key);
    try { s.binding.unbind(); } catch { /* already gone */ }
    this.onSessionRevoked(pluginSessionId, 'binder_death');
  }
  close(s, reason) {
    if (!s || s.state === 'closed') return;
    const wasActive = s.state === 'active';
    s.state = 'closed';
    this.byPlugin.delete(s.key); this.handshakes.delete(s.key);
    try { s.endpoint.closePluginSession(s.pluginSessionId, reason); } catch { /* oneway */ }
    try { s.binding.unbind(); } catch { /* already gone */ }
    if (wasActive) this.onSessionRevoked(s.pluginSessionId, reason);
  }

  sink(session, requestId, wait) {
    return {
      deliver: envelope => {
        const d = this.counters(session.pluginId);
        if (wait.settled || !session.inflight.has(requestId)) { d.lateResults += 1; return; }
        if (envelope?.status === 'ok') wait.resolve({ status: 'ok', resultJson: envelope.resultJson ?? null,
          attachments: envelope.attachments ?? [], content: envelope.content, mimeType: envelope.mimeType,
          generatedAt: envelope.generatedAt, truncated: envelope.truncated === true });
        else {
          const code = SEAM_CODES.has(envelope?.error?.code) ? envelope.error.code : 'internal';
          this.error(session.pluginId, code);
          wait.resolve({ status: 'error', error: { code, message: String(envelope?.error?.message ?? code),
            retryable: envelope?.error?.retryable === true, dataJson: envelope?.error?.dataJson ?? null } });
        }
      },
    };
  }
  async prepareCall(kind, { userId, sessionId, leaseId, capability, deadlineEpochMs }) {
    const { pluginId, name } = this.split(capability);
    check(Number.isSafeInteger(deadlineEpochMs) && deadlineEpochMs > this.now(), 'invalid_request');
    await this.ensureSession(userId, pluginId);
    const session = this.sessions.get(this.byPlugin.get(`${userId}/${pluginId}`));
    check(session?.state === 'active', 'plugin_unavailable');
    const declared = kind === 'tool' ? session.granted.tools.get(name) : session.granted.resources.get(name);
    check(declared, kind === 'tool' ? 'unknown_tool' : 'unknown_resource');
    check(this.validateLease({ leaseId, sessionId, pluginSessionId: session.pluginSessionId, capability }) === true, 'not_entitled');
    session.lastUsed = this.now();
    return { pluginId, name, session, declared };
  }
  async call(kind, { userId, sessionId, leaseId, capability, deadlineEpochMs }, dispatch,
    { prepared = null, requestId = randomUUID(), operation = null } = {}) {
    const selected = prepared ?? await this.prepareCall(kind, { userId, sessionId, leaseId, capability, deadlineEpochMs });
    const { pluginId, session, declared } = selected;
    const wait = this.defer(deadlineEpochMs, pluginId, kind);
    session.inflight.set(requestId, wait);
    try { dispatch(session, declared, requestId, this.sink(session, requestId, wait)); }
    catch (e) {
      if (e?.code) {
        session.inflight.delete(requestId);
        if (operation) this.finishOperation(operation, { status: 'error', error: {
          code: e.code, message: String(e.message ?? e.code), retryable: false,
        } });
        wait.reject(e); // host-side rejection, not an endpoint failure
      } else wait.resolve({ status: 'error', error: { code: 'unavailable', message: 'endpoint dispatch failed', retryable: true } });
    }
    const result = await wait.promise;
    session.inflight.delete(requestId);
    if (result.status === 'error' && result.error.code === 'timeout' && session.state === 'active') {
      try { session.endpoint.cancelInvoke(session.pluginSessionId, requestId); } catch { /* best effort */ }
    }
    if (operation) this.finishOperation(operation, result, wait.expired ? 'deadline' : null);
    return result;
  }

  operationHash(argsJson) { return createHash('sha256').update(argsJson).digest('hex'); }
  operationScope({ userId, sessionId, pluginId, capability, idempotencyKey }) {
    return [userId, sessionId, pluginId, capability, idempotencyKey].join('\u0000');
  }
  operationRecord({ userId, sessionId, pluginId, capability, idempotencyKey, argsJson, requestId }) {
    const argsHash = this.operationHash(argsJson);
    const scope = this.operationScope({ userId, sessionId, pluginId, capability, idempotencyKey });
    if (this.store) return this.store.createToolOperation({ userId, sessionId, pluginId, capability,
      idempotencyKey, argsHash, requestId, now: this.now() });
    const existing = this.operations.get(scope);
    if (existing) {
      check(existing.argsHash === argsHash, 'idempotency_conflict');
      return { operation: existing, created: false };
    }
    const operation = { operationId: randomUUID(), userId, sessionId, pluginId, capability,
      idempotencyKey, argsHash, requestId, state: 'pending', response: null, error: null,
      unknownReason: null, createdAt: this.now(), updatedAt: this.now() };
    this.operations.set(scope, operation);
    return { operation, created: true };
  }
  operationById(operation) {
    if (this.store) return this.store.getToolOperationById(operation.operationId) ?? operation;
    return operation;
  }
  finishOperation(operation, result, unknownReason = null) {
    const state = unknownReason ? 'unknown' : result?.status === 'ok' ? 'succeeded' : 'failed';
    const error = unknownReason
      ? { code: 'operation_unknown', message: `external operation outcome is unknown (${unknownReason})`, retryable: false }
      : result?.error ?? null;
    if (this.store) {
      try { return this.store.updateToolOperation(operation.operationId, {
        state, response: unknownReason ? undefined : result, error, unknownReason, now: this.now(),
      }); } catch (e) {
        // A result can race a restart/reconciliation. The durable row remains
        // the source of truth; never turn a successful endpoint response into
        // a second dispatch because a bookkeeping update lost that race.
        if (e?.code !== 'operation_terminal') throw e;
        return this.store.getToolOperationById(operation.operationId);
      }
    }
    if (operation.state === 'unknown' && state === 'unknown') return operation;
    operation.state = state; operation.response = unknownReason ? operation.response : result;
    operation.error = error; operation.unknownReason = unknownReason; operation.updatedAt = this.now();
    return operation;
  }
  operationResult(operation) {
    const current = this.operationById(operation);
    if (current.state === 'unknown') return { status: 'error', error: {
      code: 'operation_unknown', message: 'external operation outcome is unknown; reconcile before retrying',
      retryable: false, dataJson: JSON.stringify({ operationId: current.operationId }),
    } };
    return current.response ?? { status: 'error', error: current.error ?? {
      code: 'operation_unknown', message: 'operation has no terminal response', retryable: false,
    } };
  }
  async invokeTool({ userId, sessionId, leaseId, capability, argsJson = '{}', idempotencyKey = null, deadlineEpochMs }) {
    check(str(argsJson) && bytes(argsJson) <= this.config.inlinePayloadMaxBytes, 'invalid_request');
    const prepared = await this.prepareCall('tool', { userId, sessionId, leaseId, capability, deadlineEpochMs });
    const { pluginId, name } = this.split(capability);
    const tool = prepared.declared;
    check(tool.sideEffects === 'none' || str(idempotencyKey), 'invalid_request', 'external tool requires idempotencyKey');
    const requestId = randomUUID();
    const recorded = tool.sideEffects === 'external'
      ? this.operationRecord({ userId, sessionId, pluginId, capability, idempotencyKey, argsJson, requestId }) : null;
    const operation = recorded?.operation ?? null;
    if (operation) {
      const current = this.operationById(operation);
      if (current.state !== 'pending') return this.operationResult(current);
      const live = this.liveOperations.get(current.operationId);
      if (live) return live.promise;
      // A pending row without a live waiter can only come from an older owner.
      // SessionStore normally fences it on open; keep the fallback conservative.
      if (!recorded.created) return this.operationResult(this.finishOperation(current, null, 'stale_pending'));
    }
    const promise = this.call('tool', { userId, sessionId, leaseId, capability, deadlineEpochMs },
      (session, declared, reqId, sink) => {
        this.counters(session.pluginId).invokes += 1;
        session.endpoint.beginInvoke(session.pluginSessionId, leaseId, reqId, declared.name, argsJson,
          idempotencyKey, deadlineEpochMs, sink);
      }, { prepared, requestId, operation });
    if (operation) this.liveOperations.set(operation.operationId, { operation, promise });
    try { return await promise; }
    finally { if (operation && this.liveOperations.get(operation.operationId)?.promise === promise) this.liveOperations.delete(operation.operationId); }
  }

  // Turn-boundary assembly (contract §8). Returns reminder blocks for the next
  // model call only; never touches the Session store, events or history.
  async collectReminders({ userId, sessionId, entries = [] }) {
    const wanted = new Map();
    for (const e of entries) {
      const { pluginId, name } = this.split(e.capability);
      if (!wanted.has(pluginId)) wanted.set(pluginId, new Map());
      wanted.get(pluginId).set(name, e.leaseId);
    }
    const reminders = [], omitted = [];
    const omit = (pluginId, resource, reason) => { omitted.push({ pluginId, resource, reason });
      this.counters(pluginId).omitted += 1; };
    for (const pluginId of [...wanted.keys()].sort()) {
      const names = wanted.get(pluginId);
      let session;
      try {
        await this.ensureSession(userId, pluginId);
        session = this.sessions.get(this.byPlugin.get(`${userId}/${pluginId}`));
        check(session?.state === 'active', 'plugin_unavailable');
      } catch (e) { for (const n of names.keys()) omit(pluginId, n, e.code ?? 'plugin_unavailable'); continue; }
      for (const res of session.granted.resources.values()) {  // descriptor declaration order
        if (!names.has(res.name)) continue;
        if (res.refresh === 'on_change' && !session.dirty.has(res.name)) continue;
        const capability = `${pluginId}/${res.name}`;
        const deadline = this.now() + this.config.resourceReadTimeoutMs;
        const result = await this.call('resource',
          { userId, sessionId, leaseId: names.get(res.name), capability, deadlineEpochMs: deadline },
          (s, r, requestId, sink) => { this.counters(pluginId).reads += 1;
            s.endpoint.beginReadResource(s.pluginSessionId, names.get(res.name), requestId, r.name, r.maxBytes, deadline, sink); })
          .catch(e => ({ status: 'error', error: { code: e.code ?? 'internal' } }));
        if (result.status !== 'ok' || typeof result.content !== 'string') { omit(pluginId, res.name, result.error?.code ?? 'invalid_response'); continue; }
        session.dirty.delete(res.name);
        let content = result.content, truncated = result.truncated;
        if (bytes(content) > res.maxBytes) { content = Buffer.from(content, 'utf8').subarray(0, res.maxBytes).toString('utf8'); truncated = true; }
        const d = this.counters(pluginId); d.readBytes += bytes(content); if (truncated) d.truncated += 1;
        reminders.push({ pluginId, resource: res.name, mimeType: str(result.mimeType) ? result.mimeType : 'text/plain',
          content, truncated, generatedAt: result.generatedAt ?? null, untrusted: true });
      }
    }
    let budget = this.config.reminderBudgetBytes;               // deterministic: order already pluginId-lex + declaration
    const kept = [];
    for (const r of reminders) {
      if (bytes(r.content) <= budget) { budget -= bytes(r.content); kept.push(r); }
      else omit(r.pluginId, r.resource, 'budget');
    }
    return { reminders: kept, omitted };
  }

  dump() {
    const plugins = {};
    for (const [pluginId, pkg] of this.packages) plugins[pluginId] = {
      version: pkg.version,
      enabledUsers: [...this.enabled].filter(k => k.endsWith(`/${pluginId}`)).map(k => k.slice(0, -pluginId.length - 1)),
      activeSessions: [...this.sessions.values()].filter(s => s.pluginId === pluginId && s.state === 'active')
        .map(s => ({ pluginSessionId: s.pluginSessionId, userId: s.userId,
          grantedTools: s.granted.tools.size, grantedResources: s.granted.resources.size, inflight: s.inflight.size })),
      counters: this.diag.get(pluginId) ?? null,               // counts and codes only, never payload or content
    };
    return { plugins };
  }
  async shutdown() {
    for (const { operation } of this.liveOperations.values()) this.finishOperation(operation, null, 'daemon_shutdown');
    for (const s of [...this.sessions.values()]) this.close(s, 'daemon_shutdown');
  }
}
