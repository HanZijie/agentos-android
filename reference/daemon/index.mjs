import { SessionStore } from './store.mjs';
import { SessionScheduler } from './scheduler.mjs';
import { ActiveSessionPool, SessionSelector } from './session-selector.mjs';
import { JevClientError, JevHttpClient } from './jev-client.mjs';
import { PluginBroker, validateDescriptor } from './plugin-broker.mjs';
import { processWorkerFactory } from './workers/process-worker.mjs';

export {
  SessionStore,
  SessionScheduler,
  ActiveSessionPool,
  SessionSelector,
  JevHttpClient,
  JevClientError,
  PluginBroker,
  validateDescriptor,
};

/**
 * Creates the process-local sideagentd data plane. Android Binder and the
 * frontend protocol can call this interface later without owning scheduling.
 */
export function createSideagentd({ dbPath, workerFactory = processWorkerFactory, config, now, retainEvents,
  jevModel, jev = {}, selectionConfig = {} } = {}) {
  if (!dbPath) throw new Error('dbPath is required');
  const store = new SessionStore(dbPath, { retainEvents });
  const model = jevModel ?? new JevHttpClient(jev);
  const sessionSelector = new SessionSelector(store, { ...selectionConfig, now, model });
  const scheduler = new SessionScheduler(store, workerFactory, { config, now, sessionSelector });
  return {
    store,
    scheduler,
    sessionSelector,
    async shutdown() { await scheduler.shutdown(); store.close(); },
  };
}

/**
 * Wires a PluginBroker to the Scheduler's frozen lease semantics
 * (session-scheduling-v1 §8): leases are validated against the Session record,
 * and endpoint revocations propagate to scheduler.revokePlugin.
 */
export function createPluginBroker(scheduler, store, options = {}) {
  const now = options.now ?? Date.now;
  return new PluginBroker({
    ...options,
    store,
    now,
    validateLease: ({ leaseId, sessionId, pluginSessionId, capability }) => {
      try {
        const s = store.get(sessionId);
        return s.leases.some(l => l.valid && l.leaseId === leaseId && l.pluginSessionId === pluginSessionId
          && l.workerGeneration === s.workerGeneration && l.expiresAt > now() && l.capabilities.includes(capability));
      } catch { return false; }
    },
    hasActiveLeases: pluginSessionId => store.list()
      .some(s => s.leases.some(l => l.valid && l.pluginSessionId === pluginSessionId && l.expiresAt > now())),
    onSessionRevoked: (pluginSessionId, reason) => {
      scheduler.revokePlugin(pluginSessionId, reason === 'binder_death' ? 'lease_revoked' : reason);
      options.onSessionRevoked?.(pluginSessionId, reason);
    },
  });
}
