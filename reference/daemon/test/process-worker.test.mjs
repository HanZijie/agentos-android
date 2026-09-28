import test from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as sleep } from 'node:timers/promises';
import { SessionStore } from '../store.mjs';
import { SessionScheduler } from '../scheduler.mjs';
import { processWorkerFactory } from '../workers/process-worker.mjs';

async function until(condition) {
  for(let i=0;i<300;i++) { if(condition()) return; await sleep(10); }
  throw new Error('Timed out waiting for state');
}
function setup(t, config = {}) {
  const store=new SessionStore(':memory:');
  const scheduler=new SessionScheduler(store,processWorkerFactory,{config});
  t.after(async()=>{await scheduler.shutdown();store.close();});
  const create=()=>scheduler.createSession({userId:'u',cwd:'/tmp'}).sessionId;
  const submit=(id,key,plan)=>scheduler.submitInput({sessionId:id,clientRequestId:key,prompt:[{type:'text',text:JSON.stringify(plan)}]});
  return {store,scheduler,create,submit};
}

test('fake subprocess events commit before completion and Session context spans prompts', async t=>{
  const f=setup(t); const a=f.create(), b=f.create(); f.submit(a,'a1',{text:'one'});f.submit(a,'a2',{text:'two'});f.submit(b,'b1',{});
  await until(()=>f.store.get(a).tasks.every(t=>t.state==='completed')&&f.store.get(b).state==='created');
  assert.equal(f.store.get(a).checkpoint.history.length,2);
  const events=f.store.events(a);
  assert.equal(events.filter(e=>e.eventType==='worker.update').length,2);
  const completed=events.findIndex(e=>e.eventType==='task.completed');
  assert.ok(completed>events.findIndex(e=>e.eventType==='worker.update'));
  const worker=f.scheduler.workers.get(a), pid=worker.child.pid;
  await f.scheduler.closeSession(a);
  assert.throws(()=>process.kill(pid,0),{code:'ESRCH'});
});

test('subprocess crash pauses unknown Attempt, preserves queued input and never auto-replays', async t=>{
  const f=setup(t); const id=f.create(); const first=f.submit(id,'crash',{crash:true});f.submit(id,'later',{});
  await until(()=>f.store.get(id).state==='paused'&&f.scheduler.active.size===0);
  assert.equal(f.store.get(id).tasks[0].state,'unknown');assert.equal(f.store.get(id).tasks[1].state,'queued');
  assert.equal(f.store.get(id).tasks[0].attempts.length,1);
  assert.equal(f.store.get(id).updates.length,1);
  assert.equal(f.submit(id,'crash',{crash:true}).taskId,first.taskId);
  f.scheduler.resolveRecovery(id,first.taskId);f.scheduler.resume(id);
  await until(()=>f.store.get(id).tasks[1].state==='completed');
});

test('real child cancellation and unresponsive cancellation grace', async t=>{
  const f=setup(t,{cancelGraceMs:60}); const id=f.create(); const task=f.submit(id,'slow',{delayMs:2000});
  await until(()=>f.store.get(id).updates.length===1);
  f.scheduler.cancelTask(id,task.taskId);await until(()=>f.store.get(id).tasks[0].state==='cancelled');
  const next=f.submit(id,'stuck',{delayMs:2000,ignoreCancel:true});
  await until(()=>f.store.get(id).updates.length===2);
  f.scheduler.cancelTask(id,next.taskId);
  await until(()=>f.store.get(id).state==='paused'&&f.scheduler.active.size===0);
  assert.equal(f.store.get(id).tasks[1].state,'unknown');assert.equal(f.scheduler.workers.size,0);
});

test('cancel during child startup never runs prompt', async t=>{
  const f=setup(t);const id=f.create();const task=f.submit(id,'a',{text:'must not run'});
  f.scheduler.tick();f.scheduler.cancelTask(id,task.taskId);
  await until(()=>f.store.get(id).tasks[0].state==='cancelled');
  assert.equal(f.store.get(id).updates.length,0);
});
