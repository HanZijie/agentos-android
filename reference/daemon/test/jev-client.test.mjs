import test from 'node:test';
import assert from 'node:assert/strict';
import { JevClientError, JevHttpClient, requestPayload } from '../jev-client.mjs';

const request = {
  query: 'continue the previous work',
  choices: [
    { choiceId: 'session-1', brief: 'First query: fix the build' },
    { choiceId: 'new_session', brief: 'Start a new Session', fixed: true },
  ],
};

test('maps selector choices to a Jev Choice question without exposing credentials', async () => {
  let seen;
  const client = new JevHttpClient({
    apiKey: 'test-secret',
    fetchImpl: async (url, options) => {
      seen = { url, options, body: JSON.parse(options.body) };
      return { ok: true, status: 200, async json() {
        return { model: 'jev-1.13.0', answers: { session: { type: 'choice', choice: 'session-1', confidence: 0.9 } }, usage: { input_tokens: 10 } };
      } };
    },
  });
  const answer = await client.choose(request);
  assert.equal(answer.choiceId, 'session-1');
  assert.equal(seen.url, 'https://omnilabs.vibeadmin.cn/v1/systemone');
  assert.equal(seen.options.headers.Authorization, 'Bearer test-secret');
  assert.deepEqual(seen.body.questions.session.criteria, {
    'session-1': 'First query: fix the build', new_session: 'Start a new Session',
  });
  assert.equal(JSON.stringify(seen.body).includes('test-secret'), false);
});

test('rejects missing keys, HTTP failures, and malformed answers with stable codes', async () => {
  await assert.rejects(
    new JevHttpClient({ apiKey: '', fetchImpl: async () => { throw new Error('should not fetch'); } }).choose(request),
    error => error instanceof JevClientError && error.code === 'jev_key_missing',
  );
  await assert.rejects(
    new JevHttpClient({ apiKey: 'x', fetchImpl: async () => ({ ok: false, status: 429 }) }).choose(request),
    error => error instanceof JevClientError && error.code === 'jev_http_error' && error.status === 429,
  );
  await assert.rejects(
    new JevHttpClient({ apiKey: 'x', fetchImpl: async () => ({ ok: true, async json() { return { answers: {} }; } }) }).choose(request),
    error => error instanceof JevClientError && error.code === 'jev_invalid_response',
  );
});

test('Choice criteria are bounded to Jev cardinality', () => {
  const choices = Array.from({ length: 256 }, (_, index) => ({ choiceId: `s-${index}`, brief: 'x' }));
  assert.throws(() => requestPayload({ query: 'q', choices }), { code: 'invalid_params' });
});

test('aborts a slow request within the configured timeout', async () => {
  const client = new JevHttpClient({
    apiKey: 'x', timeoutMs: 5,
    fetchImpl: async (_url, options) => new Promise((_, reject) => {
      options.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true });
    }),
  });
  await assert.rejects(client.choose(request), error => error.code === 'jev_timeout');
});
