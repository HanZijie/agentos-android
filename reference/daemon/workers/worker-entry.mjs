import { FakeWorker } from './fake-worker.mjs';

let worker, attemptId, nextId = 0;
const pending = new Map();
const send = message => new Promise((resolve, reject) => {
  if (!process.connected) return reject(new Error('parent_disconnected'));
  process.send(message, error => error ? reject(error) : resolve());
});
const call = (type, data) => new Promise((resolve, reject) => {
  const id = ++nextId; pending.set(id, { resolve, reject });
  void send({ type, id, attemptId, data }).catch(reject);
});
process.on('message', async message => {
  if (message.type === 'ack') {
    const p = pending.get(message.id); if (!p) return;
    pending.delete(message.id);
    message.error ? p.reject(new Error(message.error)) : p.resolve(message.result);
    return;
  }
  if (message.type !== 'request') return;
  try {
    let result;
    if (message.op === 'init') {
      if (worker) throw new Error('already_initialized');
      const Worker = process.argv[2] === 'pi'
        ? (await import('../../../../runtime/src/pi-worker.js')).PiWorker : FakeWorker;
      worker = new Worker(); result = await worker.start(message.data);
    } else if (message.op === 'prompt') {
      attemptId = message.data.attemptId;
      result = await worker.prompt(message.data, { event: data => call('event', data), permission: data => call('permission', data) });
    } else if (message.op === 'cancel') result = await worker.cancel();
    else throw new Error('unknown_operation');
    await send({ type: 'response', id: message.id, result });
  } catch {
    // Conservative: a failed command may have touched tools; parent fences the attempt.
    await send({ type: 'response', id: message.id, error: 'worker_operation_failed' }).catch(() => {});
  }
});
// Child cannot outlive the daemon after parent IPC disappears.
process.on('disconnect', () => process.exit(0));
