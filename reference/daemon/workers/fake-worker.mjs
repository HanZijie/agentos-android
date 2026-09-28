import { setTimeout as sleep } from 'node:timers/promises';

// Test/runtime double: no Pi imports, credentials, network or model calls.
export class FakeWorker {
  async start({ checkpoint }) { this.history = checkpoint?.history ?? []; }
  async prompt({ prompt }, { event }) {
    this.controller = new AbortController();
    this.history.push(prompt);
    const plan = JSON.parse(prompt.find(b => b.type === 'text')?.text ?? '{}');
    this.ignoreCancel = plan.ignoreCancel === true;
    try {
      await event({ sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: plan.text ?? 'fake started' } });
      if (plan.crash) process.exit(23);
      await sleep(plan.delayMs ?? 0, undefined, { signal: this.controller.signal });
      return { stopReason: 'end_turn', checkpoint: { history: this.history } };
    } catch (error) {
      if (error.name !== 'AbortError') throw error;
      return { stopReason: 'cancelled', checkpoint: { history: this.history } };
    } finally { this.controller = null; }
  }
  async cancel() { if (!this.ignoreCancel) this.controller?.abort(); }
  async stop() { this.controller?.abort(); }
}
