import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';
import { SessionStore } from '../store.mjs';
import { PluginBroker } from '../plugin-broker.mjs';

test('tool operation pending rows survive and are fenced unknown on store reopen', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'agentos-operation-'));
  const filename = join(dir, 'state.sqlite');
  const first = new SessionStore(filename);
  const created = first.createToolOperation({ userId: 'u', sessionId: 's', pluginId: 'p',
    capability: 'p/write', idempotencyKey: 'k',
    argsHash: createHash('sha256').update('{"v":1}').digest('hex'), requestId: 'req', now: 1000 });
  assert.equal(created.operation.state, 'pending');
  first.close();

  const reopened = new SessionStore(filename);
  try {
    const operation = reopened.getToolOperation({ userId: 'u', sessionId: 's', pluginId: 'p',
      capability: 'p/write', idempotencyKey: 'k' });
    assert.equal(operation.state, 'unknown');
    assert.equal(operation.unknownReason, 'daemon_restart');
    assert.equal(operation.error.code, 'operation_unknown');
    assert.throws(() => reopened.updateToolOperation(operation.operationId, { state: 'pending', now: 1001 }),
      { code: 'operation_terminal' });

    let invokes = 0;
    const endpoint = {
      openPluginSession: () => ({ protocolVersion: 3, pluginId: 'p', displayName: 'P', version: '1',
        tools: [{ name: 'write', description: '', inputSchema: { type: 'object' }, sideEffects: 'external' }] }),
      sessionGranted() {},
      beginInvoke() { invokes += 1; },
      closePluginSession() {},
    };
    const broker = new PluginBroker({ store: reopened, validateLease: () => true });
    broker.installPackage({ packageName: 'p', endpointFactory: () => ({ endpoint,
      linkToDeath: () => {}, unbind: () => {} }) });
    broker.setEnabled('u', 'p', true);
    const result = await broker.invokeTool({ userId: 'u', sessionId: 's', leaseId: 'l',
      capability: 'p/write', argsJson: '{"v":1}', idempotencyKey: 'k', deadlineEpochMs: Date.now() + 1000 });
    assert.equal(result.error.code, 'operation_unknown');
    assert.equal(invokes, 0);
    await broker.shutdown();
  } finally {
    reopened.close();
    rmSync(dir, { recursive: true, force: true });
  }
});
