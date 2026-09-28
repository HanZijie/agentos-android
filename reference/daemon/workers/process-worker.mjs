import { fork } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { SchedulerError } from '../errors.mjs';

/** Private IPC driver. Lifecycle, task IDs and durable state stay in the Scheduler. */
export class ProcessWorker {
  constructor({ kind = 'fake', entry = new URL('./worker-entry.mjs', import.meta.url), initTimeoutMs = 10000 } = {}) {
    this.kind = kind; this.entry = entry; this.initTimeoutMs = initTimeoutMs;
    this.requests = new Map(); this.nextId = 0; this.stopped = false;
  }
  onCrash(callback) { this.crashed = callback; }
  async start(assignment) {
    this.child = fork(fileURLToPath(this.entry), [this.kind], { stdio: ['ignore', 'ignore', 'pipe', 'ipc'], serialization: 'json', execArgv: [] });
    // Don't mirror model/tool stderr into public events. Drain bounded diagnostics only.
    this.child.stderr.on('data', chunk => { this.diagnostic = ((this.diagnostic ?? '') + chunk.toString()).slice(-4096); });
    this.exited = new Promise(resolve => this.child.once('exit', resolve));
    this.child.on('error', () => this.fail());
    this.child.on('exit', () => this.fail());
    this.child.on('message', message => void this.receive(message));
    let timer;
    try {
      await Promise.race([this.request('init', assignment), new Promise((_, reject) => {
        timer = setTimeout(() => reject(new SchedulerError('worker_start_timeout')), this.initTimeoutMs);
      })]);
    } finally { clearTimeout(timer); }
  }
  fail() {
    for (const pending of this.requests.values()) pending.reject(new SchedulerError('worker_lost'));
    this.requests.clear();
    if (!this.stopped && !this.notified) { this.notified = true; this.crashed?.(); }
  }
  send(message) {
    if (!this.child?.connected) throw new SchedulerError('worker_lost');
    this.child.send(message, error => { if (error) this.fail(); });
  }
  request(op, data = {}) {
    const id = ++this.nextId;
    return new Promise((resolve, reject) => {
      this.requests.set(id, { resolve, reject });
      try { this.send({ type: 'request', id, op, data }); }
      catch (e) { this.requests.delete(id); reject(e); }
    });
  }
  async receive(message) {
    if (message.type === 'response') {
      const pending = this.requests.get(message.id); if (!pending) return;
      this.requests.delete(message.id);
      if (message.error) pending.reject(new SchedulerError(message.error)); else pending.resolve(message.result);
    } else if (['event', 'permission'].includes(message.type)) {
      try {
        if (!this.sink || message.attemptId !== this.attemptId || this.stopped) throw new SchedulerError('stale_worker');
        const result = await this.sink[message.type](message.data);
        this.send({ type: 'ack', id: message.id, result });
      } catch (e) {
        try { this.send({ type: 'ack', id: message.id, error: e.code ?? 'worker_event_rejected' }); } catch { /* process already exited */ }
      }
    }
  }
  async prompt(input, sink) {
    this.sink = sink; this.attemptId = input.attemptId;
    try { return await this.request('prompt', input); }
    finally { this.sink = null; }
  }
  cancel() { return this.request('cancel'); }
  async stop() {
    if (this.stopPromise) return this.stopPromise;
    this.stopped = true;
    this.stopPromise = (async () => {
      if (!this.child) return;
      this.child.kill('SIGTERM');
      const timer = setTimeout(() => this.child.kill('SIGKILL'), 500);
      try { await this.exited; } finally { clearTimeout(timer); this.fail(); }
    })();
    return this.stopPromise;
  }
}
export const processWorkerFactory = session => new ProcessWorker({ kind: session.worker });
