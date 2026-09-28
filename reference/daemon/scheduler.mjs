import { randomUUID, createHash } from 'node:crypto';
import { isAbsolute } from 'node:path';
import { check, SchedulerError } from './errors.mjs';

const priorities = ['background', 'normal', 'interactive', 'urgent'];
const terminal = new Set(['completed', 'failed']);
const taskTerminal = new Set(['completed', 'failed', 'cancelled']);
const defaults = { maxRunningSessions: 2, maxRunningSessionsPerUser: 2, agingIntervalMs: 30000,
  queueTimeoutMs: 0, executionTimeoutMs: 300000, cancelGraceMs: 5000, maxQueuedTasks: 1000 };
const selectionTextLimit = 8000;
function validateConfig(c) {
  for (const k of Object.keys(defaults)) check(Number.isSafeInteger(c[k]) && c[k] >=
    (['queueTimeoutMs', 'executionTimeoutMs'].includes(k) ? 0 : 1), 'invalid_config', k);
  return c;
}
function canonical(v) {
  if (Array.isArray(v)) return `[${v.map(canonical).join(',')}]`;
  if (v && typeof v === 'object') return `{${Object.keys(v).sort().map(k => `${JSON.stringify(k)}:${canonical(v[k])}`).join(',')}}`;
  return JSON.stringify(v);
}
function state(s, emit, next) {
  if (s.state !== next) { s.state = next; emit('session.state_changed', { state: next }); }
}
function idle(s, emit, now) {
  s.active = null;
  if (s.state === 'paused') return;
  s.readySince = now;
  state(s, emit, s.tasks.some(t => t.state === 'queued') ? 'queued' : 'created');
}
function revoke(s, emit, reason, predicate = () => true) {
  for (const lease of s.leases) if (lease.valid && predicate(lease)) {
    lease.valid = false;
    emit('capability.lease_revoked', { capabilities: lease.capabilities, reason });
  }
}

function promptText(prompt) {
  if (typeof prompt === 'string') return prompt.trim().slice(0, selectionTextLimit);
  if (!Array.isArray(prompt)) return '';
  return prompt.filter(block => block?.type === 'text' && typeof block.text === 'string')
    .map(block => block.text.trim()).filter(Boolean).join('\n').slice(0, selectionTextLimit);
}

function updateText(update) {
  if (!update || typeof update !== 'object') return '';
  const kind = update.sessionUpdate ?? update.type;
  if (kind !== 'agent_message_chunk' && kind !== 'agent_message' && kind !== 'message_update') return '';
  const content = update.content ?? update.message?.content;
  if (typeof content === 'string') return content;
  if (content && typeof content.text === 'string') return content.text;
  if (Array.isArray(content)) return content.filter(block => block?.type === 'text').map(block => block.text).join('');
  return '';
}

function newSelectionState() {
  return { firstTurn: null, firstQuery: null, firstAnswer: null, latestAnswer: null, recentTurns: [] };
}

function selectionState(s) {
  s.selection ??= newSelectionState();
  s.selection.recentTurns = Array.isArray(s.selection.recentTurns) ? s.selection.recentTurns : [];
  return s.selection;
}

function recordSelectionTurn(s, task, now) {
  const query = String(task.selectionQuery ?? '').trim().slice(0, selectionTextLimit);
  const answer = String(task.selectionAnswer ?? '').trim().slice(0, selectionTextLimit);
  if (!query && !answer) return;
  const selection = selectionState(s);
  const turn = { query, answer, completedAt: now };
  if (!selection.firstTurn) {
    // `firstQuery` is set at enqueue time, so a cancelled/failed first task
    // must not be replaced by a later completed turn.
    const firstQuery = selection.firstQuery || query;
    const firstAnswer = firstQuery === query ? answer : '';
    selection.firstTurn = { query: firstQuery, answer: firstAnswer };
    selection.firstQuery = firstQuery || null;
    selection.firstAnswer = firstAnswer || null;
  } else if (!selection.firstQuery && query) {
    selection.firstQuery = query;
  } else if (!selection.firstAnswer && answer) {
    selection.firstAnswer = answer;
    if (selection.firstTurn) selection.firstTurn.answer = answer;
  }
  selection.latestAnswer = answer || selection.latestAnswer || null;
  selection.recentTurns = [...selection.recentTurns, { query, answer }].slice(-2);
  s.lastActivityAt = now;
}

/** Trusted host API, not an additional public frontend wire protocol. */
export class SessionScheduler {
  constructor(store, workerFactory, { config = {}, now = Date.now, autoStart = true,
    requestPermission = async () => ({ outcome: { outcome: 'cancelled' } }), sessionSelector = null } = {}) {
    this.store = store; this.workerFactory = workerFactory; this.now = now;
    this.sessionSelector = sessionSelector;
    this.config = validateConfig({ ...defaults, ...config });
    this.requestPermission = requestPermission;
    this.active = new Map(); this.workers = new Map(); this.stopping = false;
    this.counter = Math.max(0, ...store.list().map(s => s.lastDispatch));
    this.failure = null;
    this.recover();
    if (autoStart) this.start();
  }
  start() {
    check(!this.stopping, 'scheduler_stopped');
    this.timer ??= setInterval(() => this.safeTick(), 20);
    this.safeTick();
  }
  safeTick() {
    if (this.stopping || this.failure) return;
    try { this.tick(); } catch (e) { this.failure = e; }
  }
  wake() { if (this.timer) setImmediate(() => this.safeTick()); }
  recover() {
    for (const old of this.store.list()) this.store.transaction(old.id, (s, emit) => {
      revoke(s, emit, 'daemon_restart');
      s.workerGeneration = randomUUID();
      if (s.active) {
        const t = s.tasks.find(t => t.id === s.active);
        if (!taskTerminal.has(t.state)) {
          t.state = 'unknown'; t.attempts.at(-1).state = 'unknown';
          t.error = { code: 'worker_lost', retryable: false };
          s.pauseReason = 'recovery_required'; state(s, emit, 'paused');
          emit('task.recovery_required', { attemptId: t.attempts.at(-1).id, sideEffects: 'unknown', reason: 'daemon_restart' }, t.id);
        }
        s.active = null;
      }
    });
  }
  assertOpen() { check(!this.stopping, 'scheduler_stopped'); if (this.failure) throw this.failure; }
  createSession({ userId, cwd, priority = 'normal', worker = 'fake', runtimeConfig = {} }) {
    this.assertOpen();
    check(typeof userId === 'string' && userId.length > 0 && typeof cwd === 'string' && isAbsolute(cwd), 'invalid_params');
    check(priorities.includes(priority) && ['fake', 'pi'].includes(worker), 'invalid_params');
    check(runtimeConfig && typeof runtimeConfig === 'object' && !Array.isArray(runtimeConfig), 'invalid_config');
    // TODO: MCP/Plugin/Hooks configuration remains unimplemented. Do not silently accept it.
    check(Object.keys(runtimeConfig).length === 0, 'unsupported_runtime_config');
    const id = randomUUID();
    this.store.transaction(id, (s, emit) => emit('session.state_changed', { state: s.state }), {
      id, userId, cwd, worker, runtimeConfig, state: 'created', priority, sequence: '0', tasks: [], active: null,
      leases: [], checkpoint: null, updates: [], workerGeneration: randomUUID(), lastDispatch: 0, readySince: this.now(),
      createdAt: this.now(), lastActivityAt: null, selection: newSelectionState(),
    });
    return { sessionId: id };
  }

  /** Select an existing active Session or return the fixed new-session choice. */
  async selectSession({ userId, query, signal } = {}) {
    check(this.sessionSelector && typeof this.sessionSelector.select === 'function', 'selection_unconfigured');
    return this.sessionSelector.select({ userId, query, signal });
  }

  /**
   * Reference data-plane entry point for a frontend that does not yet know a
   * Session id. Selection happens before the durable task enqueue. If Jev is
   * unavailable or returns an invalid choice, the selector safely creates a
   * new Session and reports the fallback reason in the receipt.
   */
  async submitAutoInput({ userId, cwd, prompt, clientRequestId, requiredCapabilities = [],
    priority = 'normal', worker = 'fake', runtimeConfig = {}, signal } = {}) {
    check(this.sessionSelector && typeof this.sessionSelector.select === 'function', 'selection_unconfigured');
    const query = promptText(prompt);
    check(query, 'invalid_params');
    const selection = await this.selectSession({ userId, query, signal });
    let sessionId = selection.sessionId;
    let created = false;
    if (!sessionId) {
      sessionId = this.createSession({ userId, cwd, priority, worker, runtimeConfig }).sessionId;
      created = true;
    } else {
      const selected = this.store.get(sessionId);
      check(selected.userId === userId && !terminal.has(selected.state), 'forbidden');
    }
    const receipt = this.submitInput({ sessionId, clientRequestId, prompt, requiredCapabilities });
    return { ...receipt, selection: { ...selection, sessionId, created } };
  }
  submitInput({ sessionId, clientRequestId, prompt, requiredCapabilities = [] }) {
    this.assertOpen();
    check(typeof clientRequestId === 'string' && clientRequestId.length > 0 && Array.isArray(prompt) && prompt.length > 0, 'invalid_params');
    check(Array.isArray(requiredCapabilities) && requiredCapabilities.every(c => typeof c === 'string'), 'invalid_params');
    const input = JSON.parse(JSON.stringify({ prompt, requiredCapabilities }));
    const hash = createHash('sha256').update(canonical(input)).digest('hex');
    const result = this.store.transaction(sessionId, (s, emit) => {
      const existing = s.tasks.find(t => t.clientRequestId === clientRequestId);
      if (existing) {
        check(existing.hash === hash, 'request_conflict');
        s.lastActivityAt = this.now();
        return { ...existing.receipt, deduplicated: true };
      }
      check(!terminal.has(s.state), 'session_terminal');
      check(!s.closing && s.state !== 'cancelling', 'request_conflict');
      check(s.tasks.filter(t => t.state === 'queued').length < this.config.maxQueuedTasks, 'scheduler_backpressure');
      const t = { id: randomUUID(), messageId: randomUUID(), clientRequestId, hash, ...input, state: 'queued',
        attempts: [], acceptedAt: this.now(), queueDeadline: this.config.queueTimeoutMs ? this.now() + this.config.queueTimeoutMs : null,
        selectionQuery: promptText(prompt), selectionAnswer: '' };
      s.lastActivityAt = this.now();
      const selection = selectionState(s);
      if (!selection.firstQuery && t.selectionQuery) selection.firstQuery = t.selectionQuery;
      s.tasks.push(t);
      emit('task.queued', { clientRequestId, messageId: t.messageId }, t.id);
      if (s.state === 'created') { s.readySince = this.now(); state(s, emit, 'queued'); }
      t.receipt = { sessionId, taskId: t.id, messageId: t.messageId, accepted: true, deduplicated: false, sessionState: s.state };
      return t.receipt;
    });
    this.wake(); return result;
  }
  getSnapshot(id) { return this.store.snapshot(id); }
  subscribe(id, after, options) { return this.store.subscribe(id, after, options); }
  configure(patch) { this.config = validateConfig({ ...this.config, ...patch }); this.wake(); }
  pause(id) {
    this.assertOpen();
    this.store.transaction(id, (s, emit) => {
      check(['created', 'queued', 'paused'].includes(s.state), 'invalid_state');
      s.pauseReason = 'manual'; state(s, emit, 'paused');
    });
  }
  resume(id) {
    this.assertOpen();
    this.store.transaction(id, (s, emit) => {
      check(s.state === 'paused', 'invalid_state');
      check(!this.active.has(id) && !s.tasks.some(t => t.state === 'unknown'), 'recovery_required');
      s.pauseReason = null; s.state = 'created';
      emit('session.state_changed', { state: 'created' }); idle(s, emit, this.now());
    });
    this.wake();
  }
  // Safe first recovery action: fail unknown work explicitly; never auto-replay effects.
  resolveRecovery(id, taskId) {
    this.assertOpen();
    check(!this.active.has(id), 'worker_still_running');
    this.store.transaction(id, (s, emit) => {
      const t = s.tasks.find(t => t.id === taskId);
      check(t?.state === 'unknown', 'invalid_state');
      t.state = 'failed'; t.error = { code: 'recovery_failed', retryable: false };
      emit('task.failed', { error: t.error, attemptState: 'unknown' }, t.id);
      // Preserve the attempt's unknown status for side-effect reconciliation.
    });
  }
  cancelTask(id, taskId, reason = 'cancelled') {
    this.assertOpen();
    const receipt = this.store.transaction(id, (s, emit) => {
      const t = s.tasks.find(t => t.id === taskId); check(t, 'task_not_found');
      if (taskTerminal.has(t.state)) return { accepted: false, state: t.state, alreadyRequested: true };
      check(t.state !== 'unknown', 'recovery_required');
      if (t.state === 'cancelling') return { accepted: true, alreadyRequested: true };
      emit('task.cancel_requested', { reason }, t.id);
      if (t.state === 'queued') {
        t.state = 'cancelled'; emit('task.cancelled', {}, t.id);
        if (!s.active) idle(s, emit, this.now());
      } else {
        t.state = 'cancelling'; t.cancelReason = reason;
        t.cancelDeadline = this.now() + this.config.cancelGraceMs;
        state(s, emit, 'cancelling');
      }
      return { accepted: true, alreadyRequested: false };
    });
    const run = this.active.get(id);
    if (run?.taskId === taskId && !receipt.alreadyRequested) {
      run.abort.abort();
      if (run.ready) Promise.resolve().then(() => run.worker.cancel()).catch(() => this.lose(id, run, 'worker_lost'));
    }
    this.wake(); return { sessionId: id, taskId, ...receipt };
  }
  async closeSession(id) {
    this.assertOpen();
    const s = this.store.get(id);
    if (s.state === 'completed') return;
    check(s.state !== 'failed', 'session_terminal');
    check(!s.tasks.some(t => t.state === 'unknown'), 'recovery_required');
    this.store.transaction(id, s => { s.closing = true; });
    for (const t of s.tasks) if (!taskTerminal.has(t.state)) this.cancelTask(id, t.id);
    const run = this.active.get(id);
    if (run) await run.done;
    check(!this.store.get(id).tasks.some(t => t.state === 'unknown'), 'recovery_required');
    await this.stopWorker(id);
    this.store.transaction(id, (s, emit) => { revoke(s, emit, 'session_closed'); state(s, emit, 'completed'); });
  }
  async failSession(id, code = 'session_failure') {
    this.assertOpen();
    const s = this.store.get(id); check(!terminal.has(s.state), 'session_terminal');
    this.store.transaction(id, s => { s.closing = true; });
    if (this.active.has(id)) await this.lose(id, this.active.get(id), code);
    await this.stopWorker(id);
    this.store.transaction(id, (s, emit) => {
      for (const t of s.tasks) if (t.state === 'queued') {
        t.state = 'failed'; t.error = { code, retryable: false }; emit('task.failed', { error: t.error, attemptState: null }, t.id);
      }
      revoke(s, emit, code); state(s, emit, 'failed');
    });
  }
  grantLease(id, { pluginId, pluginSessionId, capabilities, ttlMs }) {
    this.assertOpen();
    check(typeof pluginId === 'string' && pluginId && typeof pluginSessionId === 'string' && pluginSessionId &&
      Array.isArray(capabilities) && capabilities.length > 0 && capabilities.every(c => typeof c === 'string') &&
      Number.isSafeInteger(ttlMs) && ttlMs > 0, 'invalid_params');
    return this.store.transaction(id, (s, emit) => {
      check(!terminal.has(s.state), 'session_terminal');
      const lease = { leaseId: randomUUID(), userId: s.userId, pluginId, pluginSessionId, sessionId: id,
        workerGeneration: s.workerGeneration, capabilities: [...capabilities], issuedAt: this.now(), expiresAt: this.now() + ttlMs, valid: true };
      s.leases.push(lease); emit('capability.lease_granted', { capabilities }); return lease.leaseId;
    });
  }
  hasCapabilities(s, names) {
    return names.every(name => s.leases.some(l => l.valid && l.userId === s.userId && l.sessionId === s.id &&
      l.workerGeneration === s.workerGeneration && l.expiresAt > this.now() && l.capabilities.includes(name)));
  }
  revokePlugin(pluginSessionId, reason = 'lease_revoked') {
    this.assertOpen();
    for (const s of this.store.list()) {
      this.store.transaction(s.id, (s, emit) => revoke(s, emit, reason, l => l.pluginSessionId === pluginSessionId));
      const updated = this.store.get(s.id);
      const t = updated.tasks.find(t => t.id === updated.active);
      if (t?.state === 'running' && !this.hasCapabilities(updated, t.requiredCapabilities)) this.cancelTask(s.id, t.id, 'lease_revoked');
    }
    this.wake();
  }
  tick() {
    this.assertOpen();
    const now = this.now();
    for (const s of this.store.list()) {
      if (terminal.has(s.state)) continue;
      this.store.transaction(s.id, (s, emit) => {
        revoke(s, emit, 'expired', l => l.expiresAt <= now);
        for (const t of s.tasks) if (t.state === 'queued' && t.queueDeadline !== null && t.queueDeadline <= now) {
          t.state = 'failed'; t.error = { code: 'queue_timeout', retryable: true };
          emit('task.failed', { error: t.error, attemptState: null }, t.id);
        }
        if (s.state === 'queued' && !s.tasks.some(t => t.state === 'queued')) idle(s, emit, now);
      });
      const current = this.store.get(s.id);
      const t = current.tasks.find(t => t.id === current.active);
      if (t?.state === 'running') {
        if (!this.hasCapabilities(current, t.requiredCapabilities)) this.cancelTask(s.id, t.id, 'lease_revoked');
        else if (t.executionDeadline !== null && t.executionDeadline <= now) this.cancelTask(s.id, t.id, 'execution_timeout');
      }
      if (t?.state === 'cancelling' && t.cancelDeadline <= now) {
        const run = this.active.get(s.id); if (run) void this.lose(s.id, run, 'cancel_timeout');
      }
    }
    const effective = s => Math.min(3, priorities.indexOf(s.priority) + Math.floor((now - s.readySince) / this.config.agingIntervalMs));
    const candidates = this.store.list().filter(s => s.state === 'queued' && !s.closing && !this.active.has(s.id))
      .sort((a, b) => effective(b) - effective(a) || a.lastDispatch - b.lastDispatch || a.id.localeCompare(b.id));
    for (const s of candidates) {
      if (this.active.size >= this.config.maxRunningSessions) break;
      if ([...this.active.values()].filter(r => r.userId === s.userId).length >= this.config.maxRunningSessionsPerUser) continue;
      const t = s.tasks.find(t => t.state === 'queued');
      if (!this.hasCapabilities(s, t.requiredCapabilities)) {
        this.store.transaction(s.id, (s, emit) => { s.pauseReason = 'capability_unavailable'; state(s, emit, 'paused'); });
        continue;
      }
      this.dispatch(s, t);
    }
  }
  dispatch(s, task) {
    const attemptId = randomUUID();
    this.store.transaction(s.id, (s, emit) => {
      const t = s.tasks.find(t => t.id === task.id); t.state = 'running';
      t.executionDeadline = this.config.executionTimeoutMs ? this.now() + this.config.executionTimeoutMs : null;
      t.attempts.push({ id: attemptId, state: 'running', workerGeneration: s.workerGeneration });
      s.active = t.id; s.lastDispatch = ++this.counter; state(s, emit, 'running');
      emit('task.started', { attemptId, workerGeneration: s.workerGeneration }, t.id);
    });
    let finish;
    const run = { taskId: task.id, attemptId, userId: s.userId, abort: new AbortController(),
      ready: false, lost: false, done: new Promise(resolve => { finish = resolve; }), finish: () => finish() };
    this.active.set(s.id, run);
    void this.execute(s.id, run).catch(e => { this.failure = e; });
  }
  async execute(id, run) {
    try {
      let worker = this.workers.get(id);
      if (!worker) {
        const s = this.store.get(id);
        worker = this.workerFactory(s);
        this.workers.set(id, worker); run.worker = worker;
        worker.onCrash?.(() => {
          const active = this.active.get(id);
          if (active) void this.lose(id, active, 'worker_lost');
          else {
            this.workers.delete(id);
            this.store.transaction(id, (s, emit) => { revoke(s, emit, 'worker_lost'); s.workerGeneration = randomUUID(); });
          }
        });
        await worker.start({ sessionId: id, cwd: s.cwd, checkpoint: s.checkpoint, generation: s.workerGeneration, runtimeConfig: s.runtimeConfig });
      }
      run.worker = worker; run.ready = true;
      if (run.lost) return;
      // Cancellation during startup never forwards the prompt to Pi.
      if (run.abort.signal.aborted) { this.complete(id, run, { stopReason: 'cancelled' }); return; }
      const s = this.store.get(id), task = s.tasks.find(t => t.id === run.taskId);
      const result = await worker.prompt({ taskId: task.id, attemptId: run.attemptId, prompt: task.prompt }, {
        event: update => this.recordEvent(id, run, update),
        permission: request => this.permission(id, run, request),
      });
      if (!run.lost) this.complete(id, run, result);
    } catch (e) {
      if (!run.lost) await this.lose(id, run, 'worker_lost');
    }
  }
  recordEvent(id, run, update) {
    check(this.active.get(id) === run && !run.lost, 'stale_worker');
    this.store.transaction(id, (s, emit) => {
      // ACP update is payload, not the AgentOS envelope. Runtime IDs never replace our IDs.
      s.updates.push({ taskId: run.taskId, update });
      const task = s.tasks.find(t => t.id === run.taskId);
      const text = updateText(update);
      if (task && text) task.selectionAnswer = `${task.selectionAnswer ?? ''}${text}`.slice(-selectionTextLimit);
      s.lastActivityAt = this.now();
      emit('worker.update', { attemptId: run.attemptId, update }, run.taskId);
    });
  }
  async permission(id, run, request) {
    if (run.abort.signal.aborted || run.lost) return { outcome: { outcome: 'cancelled' } };
    this.recordEvent(id, run, { sessionUpdate: 'permission_request', toolCall: request.toolCall, options: request.options });
    return this.requestPermission({ sessionId: id, taskId: run.taskId, request, signal: run.abort.signal });
  }
  complete(id, run, result) {
    if (this.active.get(id) !== run || run.lost) return;
    this.store.transaction(id, (s, emit) => {
      const t = s.tasks.find(t => t.id === run.taskId);
      const reason = t.cancelReason;
      t.state = reason ? (reason === 'cancelled' ? 'cancelled' : 'failed') : (result.error ? 'failed' : 'completed');
      t.attempts.at(-1).state = t.state;
      if (reason && reason !== 'cancelled') t.error = { code: reason, retryable: false };
      if (result.error) t.error = result.error;
      if (result.checkpoint !== undefined) s.checkpoint = result.checkpoint;
      if (t.state === 'completed') recordSelectionTurn(s, t, this.now());
      emit(`task.${t.state}`, { stopReason: result.stopReason, error: t.error, attemptState: t.state }, t.id);
      idle(s, emit, this.now());
    });
    this.active.delete(id); run.finish(); this.wake();
  }
  async lose(id, run, reason) {
    if (run.lost || this.active.get(id) !== run) return run.done;
    run.lost = true; run.abort.abort();
    try {
      this.store.transaction(id, (s, emit) => {
        const t = s.tasks.find(t => t.id === run.taskId);
        t.state = 'unknown'; t.attempts.at(-1).state = 'unknown'; t.error = { code: reason, retryable: false };
        revoke(s, emit, reason); s.workerGeneration = randomUUID(); s.pauseReason = 'recovery_required';
        s.active = null; state(s, emit, 'paused');
        emit('task.recovery_required', { reason, attemptId: run.attemptId, sideEffects: 'unknown' }, t.id);
      });
      // Keep the reservation until stop confirms process exit. Late events are fenced above.
      await this.stopWorker(id);
      this.active.delete(id); run.finish(); this.wake();
    } catch (e) { this.failure = e; run.finish(); }
  }
  async stopWorker(id) {
    const worker = this.workers.get(id);
    if (worker) { await worker.stop(); this.workers.delete(id); }
  }
  async shutdown() {
    if (this.stopping) return;
    this.stopping = true; clearInterval(this.timer); this.timer = null;
    await Promise.all([...this.active].map(([id, run]) => this.lose(id, run, 'daemon_shutdown')));
    await Promise.all([...this.workers.keys()].map(id => this.stopWorker(id)));
  }
}
