import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { SessionStore } from '../store.mjs';
import { SessionScheduler } from '../scheduler.mjs';

const flush = () => new Promise(resolve => setImmediate(resolve));
const pause = () => new Promise(resolve => setTimeout(resolve, 1));
class ControlledWorker {
  async start(assignment) { this.assignment = assignment; }
  prompt(input, sink) {
    this.input = input; this.sink = sink;
    return new Promise((resolve, reject) => { this.resolve = resolve; this.reject = reject; });
  }
  async cancel() { this.cancelCount = (this.cancelCount ?? 0) + 1; }
  async stop() { this.stopped = true; }
  finish(extra = {}) { this.resolve({ stopReason: 'end_turn', checkpoint: { marker: this.input.taskId }, ...extra }); }
  crash() { this.reject(new Error('crash')); }
}
function fixture(t, config = {}, retainEvents = 1000) {
  let clock = 1000;
  const store = new SessionStore(':memory:', { retainEvents });
  const workers = [];
  const scheduler = new SessionScheduler(store, () => { const w = new ControlledWorker(); workers.push(w); return w; },
    { now: () => clock, autoStart: false, config: { maxRunningSessions: 2, maxRunningSessionsPerUser: 2, ...config } });
  t.after(async () => { await scheduler.shutdown(); store.close(); });
  const create = (priority = 'normal', userId = 'user') => scheduler.createSession({ cwd: '/tmp', userId, priority }).sessionId;
  const submit = (id, key, requiredCapabilities = []) => scheduler.submitInput({ sessionId: id, clientRequestId: key,
    prompt: [{ type: 'text', text: key }], requiredCapabilities });
  return { store, scheduler, workers, create, submit, advance: ms => { clock += ms; } };
}

test('same Session FIFO and durable checkpoint with one running Session slot', async t => {
  const f = fixture(t, { maxRunningSessions: 1 }); const a = f.create();
  f.submit(a, 'a1'); f.submit(a, 'a2');
  f.scheduler.tick(); await flush();
  for (let i=0; i<50 && !f.workers[0]?.input; i++) await flush();
  assert.equal(f.workers[0].input.prompt[0].text, 'a1');
  const worker = f.workers[0];
  worker.finish();
  for (let i=0; i<200 && f.store.get(a).tasks[1].state !== 'running'; i++) { await pause(); f.scheduler.tick(); }
  assert.equal(f.store.get(a).tasks[1].state, 'running');
  for (let i=0; i<200 && worker.input?.prompt[0].text !== 'a2'; i++) await pause();
  assert.equal(worker.input?.prompt[0].text, 'a2');
  worker.finish();
  await flush(); f.scheduler.tick();
  assert.equal(f.store.get(a).tasks[0].state, 'completed');
  assert.equal(f.store.get(a).checkpoint.marker, f.store.get(a).tasks[1].id);
});

test('per-user cap and lowering global limit drains existing reservations', async t => {
  const f = fixture(t, { maxRunningSessions: 3, maxRunningSessionsPerUser: 1 });
  const a = f.create('normal', 'one'), b = f.create('normal', 'one'), c = f.create('normal', 'two');
  for (const id of [a,b,c]) f.submit(id, id);
  f.scheduler.tick(); await flush(); assert.equal(f.workers.length, 2);
  f.scheduler.configure({ maxRunningSessions: 1 }); f.scheduler.tick(); assert.equal(f.workers.length, 2);
  f.workers[0].finish(); await flush(); f.scheduler.tick(); assert.equal(f.scheduler.active.size, 1);
});

test('priority aging and rotation prevent a queued Session being bypassed forever', async t => {
  const f = fixture(t, { maxRunningSessions: 1, agingIntervalMs: 10 });
  const low = f.create('background'), high = f.create('urgent');
  f.submit(low, 'low'); f.submit(high, 'h1'); f.submit(high, 'h2');
  f.scheduler.tick(); await flush(); assert.equal(f.workers[0].assignment.sessionId, high);
  f.advance(30); f.workers[0].finish(); await flush(); f.scheduler.tick(); await flush();
  assert.equal(f.workers[1].assignment.sessionId, low);
});

test('dedup survives state changes, conflicts reject, canonical object keys do not matter', async t => {
  const f = fixture(t); const id = f.create(); const first = f.submit(id, 'one');
  f.scheduler.pause(id);
  assert.equal(f.submit(id, 'one').taskId, first.taskId);
  assert.equal(f.submit(id, 'one').deduplicated, true);
  assert.throws(() => f.scheduler.submitInput({ sessionId: id, clientRequestId: 'one', prompt: [{type:'text',text:'different'}] }), {code:'request_conflict'});
  assert.equal(f.store.get(id).tasks.length, 1);
  await f.scheduler.closeSession(id);
  assert.equal(f.submit(id, 'one').taskId, first.taskId);
  assert.throws(() => f.submit(id, 'two'), {code:'session_terminal'});
});

test('queued cancellation, running cancellation and no duplicate runtime abort', async t => {
  const f = fixture(t); const id = f.create(); const a = f.submit(id, 'a'), b = f.submit(id, 'b');
  f.scheduler.cancelTask(id, b.taskId); assert.equal(f.store.get(id).tasks[1].state, 'cancelled');
  f.scheduler.tick(); await flush();
  f.scheduler.cancelTask(id, a.taskId); f.scheduler.cancelTask(id, a.taskId); await flush();
  assert.equal(f.workers[0].cancelCount, 1);
  assert.equal(f.scheduler.getSnapshot(id).state, 'cancelling');
  assert.throws(() => f.submit(id, 'c'), {code:'request_conflict'});
  f.workers[0].finish(); await flush();
  assert.equal(f.store.get(id).tasks[0].state, 'cancelled');
  assert.equal(f.scheduler.getSnapshot(id).state, 'created');
});

test('queue/execution deadlines persist; grace timeout pauses unknown work and fences late events', async t => {
  const f = fixture(t, { queueTimeoutMs: 10, executionTimeoutMs: 20, cancelGraceMs: 5 });
  const a = f.create(), b = f.create(); f.submit(a,'a'); f.submit(b,'b'); f.scheduler.pause(b);
  f.scheduler.tick(); await flush(); f.advance(11); f.scheduler.tick();
  assert.equal(f.store.get(b).tasks[0].error.code, 'queue_timeout');
  f.advance(10); f.scheduler.tick(); await flush(); assert.equal(f.store.get(a).state, 'cancelling');
  f.advance(6); f.scheduler.tick(); await flush();
  assert.equal(f.store.get(a).state, 'paused'); assert.equal(f.store.get(a).tasks[0].state, 'unknown');
  assert.ok(f.workers[0].stopped);
  assert.throws(() => f.workers[0].sink.event({ text: 'late' }), {code:'stale_worker'});
  f.workers[0].finish(); await flush(); assert.equal(f.store.get(a).tasks[0].state, 'unknown');
  assert.throws(() => f.scheduler.resume(a), {code:'recovery_required'});
  f.scheduler.resolveRecovery(a, f.store.get(a).tasks[0].id); f.scheduler.resume(a);
  assert.equal(f.store.get(a).state, 'created');
  assert.equal(f.store.get(a).tasks[0].attempts[0].state, 'unknown');
});

test('execution timeout acknowledged by worker is failed, not cancelled or unknown', async t => {
  const f = fixture(t, { executionTimeoutMs: 5 }); const id = f.create(); f.submit(id,'a');
  f.scheduler.tick(); await flush(); f.advance(5); f.scheduler.tick(); await flush(); f.workers[0].finish(); await flush();
  assert.equal(f.store.get(id).tasks[0].error.code, 'execution_timeout');
  assert.equal(f.store.get(id).tasks[0].state, 'failed');
});

test('cancel timeout retains reservation until worker stop is confirmed', async t => {
  const f = fixture(t, { maxRunningSessions: 1, cancelGraceMs: 1 });
  const a=f.create('urgent'), b=f.create(); const task=f.submit(a,'a'); f.submit(b,'b');
  f.scheduler.tick(); await flush();
  let stopped; f.workers[0].stop = () => new Promise(resolve => { stopped = resolve; });
  f.scheduler.cancelTask(a,task.taskId); f.advance(2); f.scheduler.tick(); await flush();
  f.scheduler.tick(); assert.equal(f.workers.length,1);
  stopped(); await flush(); f.scheduler.tick(); await flush(); assert.equal(f.workers.length,2);
});

test('snapshot + retained event cursor, two subscribers, disconnect never cancels a Task', async t => {
  const f = fixture(t, {}, 8); const id=f.create();
  const snapshot=f.scheduler.getSnapshot(id);
  const one=f.scheduler.subscribe(id,snapshot.snapshotSequence,{maxBuffer:30});
  const two=f.scheduler.subscribe(id,snapshot.snapshotSequence,{maxBuffer:1});
  f.submit(id,'one');
  await assert.rejects(two.next(),{code:'buffer_overflow'});
  assert.equal((await one.next()).value.eventType,'task.queued'); await one.return();
  f.scheduler.tick(); await flush(); assert.equal(f.workers[0].cancelCount,undefined);
  for(let n=0;n<10;n++) await f.workers[0].sink.event({sessionUpdate:'agent_message_chunk',content:{type:'text',text:String(n)}});
  assert.throws(()=>f.store.events(id,'0'),{code:'cursor_too_old'});
  const snap=f.scheduler.getSnapshot(id); assert.equal(snap.updates.length,10);
  const sub=f.scheduler.subscribe(id,snap.snapshotSequence);
  f.workers[0].finish(); await flush(); assert.equal((await sub.next()).value.sequence,String(BigInt(snap.snapshotSequence)+1n));
  await sub.return();
});

test('lease is bound to Session and generation, revoked capability cancels active Task', async t => {
  const f=fixture(t); const a=f.create(), b=f.create();
  f.scheduler.grantLease(a,{pluginId:'notes',pluginSessionId:'p1',capabilities:['notes.read'],ttlMs:100});
  f.submit(a,'a',['notes.read']); f.submit(b,'b',['notes.read']); f.scheduler.tick(); await flush();
  assert.equal(f.store.get(b).state,'paused'); assert.equal(f.workers.length,1);
  f.scheduler.revokePlugin('p1'); await flush(); assert.equal(f.store.get(a).state,'cancelling');
  f.workers[0].finish(); await flush(); assert.equal(f.store.get(a).tasks[0].error.code,'lease_revoked');
  const newId=f.scheduler.grantLease(b,{pluginId:'notes',pluginSessionId:'p2',capabilities:['notes.read'],ttlMs:100});
  assert.notEqual(newId,f.store.get(a).leases[0].leaseId);
  f.scheduler.resume(b); f.scheduler.tick(); await flush(); assert.equal(f.workers.length,2);
});

test('lease expiry while executing follows cancellation and required lease blocks dispatch', async t => {
  const f=fixture(t); const id=f.create();
  f.scheduler.grantLease(id,{pluginId:'p',pluginSessionId:'p1',capabilities:['p.read'],ttlMs:5});
  f.submit(id,'a',['p.read']); f.scheduler.tick(); await flush(); f.advance(5); f.scheduler.tick(); await flush();
  assert.equal(f.store.get(id).leases[0].valid,false); assert.equal(f.workers[0].cancelCount,1);
});

test('paused input stays queued; close cancels queue; Session failure is terminal', async t => {
  const f=fixture(t); const id=f.create(); f.scheduler.pause(id); f.submit(id,'a'); f.scheduler.tick();
  assert.equal(f.workers.length,0); f.scheduler.resume(id); await f.scheduler.closeSession(id);
  assert.equal(f.store.get(id).state,'completed'); assert.equal(f.store.get(id).tasks[0].state,'cancelled');
  const bad=f.create(); f.submit(bad,'b'); await f.scheduler.failSession(bad); assert.equal(f.store.get(bad).state,'failed');
});

test('SQLite rollback, exclusive owner, restart pauses in-flight work and preserves receipts/checkpoints', async () => {
  const dir=mkdtempSync(join(tmpdir(),'sideagent-store-')), file=join(dir,'sessions.db');
  let store=new SessionStore(file), scheduler;
  try {
    assert.throws(()=>new SessionStore(file),{code:'store_locked'});
    scheduler=new SessionScheduler(store,()=>new ControlledWorker(),{autoStart:false});
    const id=scheduler.createSession({userId:'one',cwd:'/tmp'}).sessionId;
    const receipt=scheduler.submitInput({sessionId:id,clientRequestId:'a',prompt:[{type:'text',text:'a'}]});
    const before=store.snapshot(id);
    assert.throws(()=>store.transaction(id,(s,emit)=>{s.state='failed';emit('should.rollback');throw new Error('rollback');}));
    assert.deepEqual(store.snapshot(id),before);
    scheduler.tick(); await flush();
    // Simulate loss of daemon state before an Attempt terminal event was committed.
    store.close(); store=new SessionStore(file);
    const recovered=new SessionScheduler(store,()=>new ControlledWorker(),{autoStart:false});
    assert.equal(store.get(id).tasks[0].state,'unknown'); assert.equal(store.get(id).state,'paused');
    recovered.tick(); assert.equal(recovered.active.size,0);
    const again=recovered.submitInput({sessionId:id,clientRequestId:'a',prompt:[{type:'text',text:'a'}]});
    assert.equal(again.taskId,receipt.taskId); assert.equal(again.deduplicated,true);
    await recovered.shutdown();
  } finally { store.close(); rmSync(dir,{recursive:true,force:true}); }
});
