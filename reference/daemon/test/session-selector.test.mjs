import test from 'node:test';
import assert from 'node:assert/strict';
import { SessionStore } from '../store.mjs';
import { SessionScheduler } from '../scheduler.mjs';
import {
  ACTIVE_SESSION_WINDOW_MS,
  DEFAULT_MAX_ACTIVE_SESSIONS,
  NEW_SESSION_CHOICE_ID,
  SessionSelector,
  buildSelectionBrief,
  estimateTokens,
} from '../session-selector.mjs';

function fixture(t, { model = null, maxActiveSessions = DEFAULT_MAX_ACTIVE_SESSIONS, maxInputTokens = 16_384 } = {}) {
  let clock = 1_000_000;
  const store = new SessionStore(':memory:');
  const selector = new SessionSelector(store, {
    model,
    now: () => clock,
    maxActiveSessions,
    maxInputTokens,
  });
  const scheduler = new SessionScheduler(store, () => ({
    async start() {}, async prompt() { return { stopReason: 'end_turn' }; }, async stop() {},
  }), { autoStart: false, now: () => clock, sessionSelector: selector });
  t.after(async () => { await scheduler.shutdown(); store.close(); });
  const add = (userId, lastActivityAt = clock, title = 'query') => {
    const id = scheduler.createSession({ userId, cwd: '/tmp' }).sessionId;
    store.transaction(id, session => {
      session.lastActivityAt = lastActivityAt;
      session.selection = {
        firstTurn: { query: `${title} first`, answer: `${title} first answer` },
        firstQuery: `${title} first`, firstAnswer: `${title} first answer`,
        latestAnswer: `${title} latest answer`,
        recentTurns: [{ query: `${title} recent`, answer: `${title} recent answer` }],
      };
    });
    return id;
  };
  return { store, selector, scheduler, add, advance: ms => { clock += ms; }, now: () => clock };
}

test('Brief contains first query, latest answer, and the last two completed turns', () => {
  const brief = buildSelectionBrief({
    firstTurn: { query: 'first query', answer: 'first answer' },
    latestAnswer: 'latest answer',
    recentTurns: [
      { query: 'older query', answer: 'older answer' },
      { query: 'newer query', answer: 'newer answer' },
      { query: 'ignored query', answer: 'ignored answer' },
    ],
  }, { maxTokens: 256 });
  assert.match(brief, /First query: first query/);
  assert.match(brief, /First answer: first answer/);
  assert.match(brief, /Latest answer: latest answer/);
  assert.match(brief, /Recent query 1: newer query/);
  assert.match(brief, /Recent answer 2: ignored answer/);
  assert.ok(estimateTokens(brief) <= 256);
});

test('pool is per-user, expires after 30 minutes, and caps at 254 plus new_session', t => {
  const f = fixture(t);
  for (let i = 0; i < DEFAULT_MAX_ACTIVE_SESSIONS + 2; i++) f.add('alice', f.now(), `s${i}`);
  f.add('bob', f.now(), 'other');
  const choices = f.selector.choices('alice');
  assert.equal(choices.length, DEFAULT_MAX_ACTIVE_SESSIONS + 1);
  assert.equal(choices.at(-1).choiceId, NEW_SESSION_CHOICE_ID);
  assert.ok(choices.slice(0, -1).every(choice => choice.sessionId !== 'other'));
  f.advance(ACTIVE_SESSION_WINDOW_MS + 1);
  assert.equal(f.selector.pool.list('alice').length, 0);
  const coldChoices = f.selector.choices('alice');
  assert.equal(coldChoices.length, 21);
  assert.ok(coldChoices.slice(0, -1).every(choice => choice.stale && !choice.active));
  assert.equal(coldChoices.at(-1).choiceId, NEW_SESSION_CHOICE_ID);
});

test('recent fallback can be disabled for a hard 30-minute selection boundary', t => {
  const f = fixture(t);
  f.selector.pool.minRecentSessions = 0;
  f.add('alice');
  f.advance(ACTIVE_SESSION_WINDOW_MS + 1);
  assert.deepEqual(f.selector.choices('alice'), [{ choiceId: NEW_SESSION_CHOICE_ID, sessionId: null, brief: 'Start a new Session', fixed: true }]);
});

test('Jev choice is validated against the active pool and can route auto input', async t => {
  const f = fixture(t, { model: { choose: async request => {
    assert.equal(request.fixedChoiceId, NEW_SESSION_CHOICE_ID);
    assert.equal(request.choices.at(-1).choiceId, NEW_SESSION_CHOICE_ID);
    assert.ok(request.tokenBudget.maxInputTokens > 0);
    return { choiceId: request.choices[0].choiceId };
  } } });
  const existing = f.add('alice');
  const decision = await f.scheduler.selectSession({ userId: 'alice', query: 'continue this work' });
  assert.equal(decision.sessionId, existing);
  const receipt = await f.scheduler.submitAutoInput({
    userId: 'alice', cwd: '/tmp', clientRequestId: 'request-1',
    prompt: [{ type: 'text', text: 'continue this work' }],
  });
  assert.equal(receipt.sessionId, existing);
  assert.equal(receipt.selection.created, false);
});

test('a queued first query enters the active pool before the Agent answer completes', t => {
  const f = fixture(t);
  const id = f.scheduler.createSession({ userId: 'alice', cwd: '/tmp' }).sessionId;
  f.scheduler.submitInput({ sessionId: id, clientRequestId: 'first', prompt: [{ type: 'text', text: 'first query' }] });
  assert.equal(f.selector.choices('alice')[0].sessionId, id);
});

test('a cancelled first task does not replace the first query in the Brief', async t => {
  const f = fixture(t);
  const id = f.scheduler.createSession({ userId: 'alice', cwd: '/tmp' }).sessionId;
  const first = f.scheduler.submitInput({ sessionId: id, clientRequestId: 'first', prompt: [{ type: 'text', text: 'first query' }] });
  f.scheduler.cancelTask(id, first.taskId);
  f.scheduler.submitInput({ sessionId: id, clientRequestId: 'second', prompt: [{ type: 'text', text: 'second query' }] });
  f.scheduler.tick();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.store.get(id).selection.firstQuery, 'first query');
});

test('long candidate text is selected in bounded stages before the final new-session choice', async t => {
  const calls = [];
  const f = fixture(t, {
    maxInputTokens: 256,
    model: { choose: async request => {
      calls.push(request);
      assert.ok(request.choices.length > 0);
      return { choiceId: request.choices[0].choiceId };
    } },
  });
  for (let i = 0; i < 25; i++) f.add('alice', f.now(), `long-${i}-${'x'.repeat(500)}`);
  const decision = await f.selector.select({ userId: 'alice', query: 'continue the work' });
  assert.equal(decision.selectionMethod, 'jev_staged');
  assert.ok(decision.stageCount > 1);
  assert.ok(calls.length > 1);
  assert.equal(calls.at(-1).choices.at(-1).choiceId, NEW_SESSION_CHOICE_ID);
  assert.ok(calls.every(request => request.tokenBudget.maxInputTokens === 256));
});

test('missing Jev or invalid choice safely selects new_session', async t => {
  const noModel = fixture(t);
  const noModelDecision = await noModel.selector.select({ userId: 'alice', query: 'new topic' });
  assert.equal(noModelDecision.choiceId, NEW_SESSION_CHOICE_ID);
  assert.equal(noModelDecision.fallbackReason, 'jev_unconfigured');

  const invalid = fixture(t, { model: { choose: async () => ({ choiceId: 'not-a-session' }) } });
  invalid.add('alice');
  const decision = await invalid.selector.select({ userId: 'alice', query: 'new topic' });
  assert.equal(decision.choiceId, NEW_SESSION_CHOICE_ID);
  assert.equal(decision.fallbackReason, 'jev_invalid_choice');
});
