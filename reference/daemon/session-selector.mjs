import { check } from './errors.mjs';

/** The public choice id for starting a Session instead of reusing one. */
export const NEW_SESSION_CHOICE_ID = 'new_session';
export const ACTIVE_SESSION_WINDOW_MS = 30 * 60 * 1000;
export const DEFAULT_MAX_ACTIVE_SESSIONS = 254;
export const DEFAULT_MIN_RECENT_SESSIONS = 20;
export const DEFAULT_JEV_INPUT_TOKENS = 16_384;
export const DEFAULT_JEV_OUTPUT_TOKENS = 64;

const terminal = new Set(['completed', 'failed']);
const selectionMaxTextChars = 8_000;

function validateConfig(config) {
  for (const [name, value] of Object.entries(config)) {
    const minimum = name === 'minRecentSessions' ? 0 : 1;
    check(Number.isSafeInteger(value) && value >= minimum, 'invalid_config', name);
  }
  check(config.maxActiveSessions <= DEFAULT_MAX_ACTIVE_SESSIONS, 'invalid_config', 'maxActiveSessions');
  if (config.minRecentSessions !== undefined) {
    check(config.minRecentSessions <= config.maxActiveSessions, 'invalid_config', 'minRecentSessions');
  }
  return config;
}

function asText(value) {
  return typeof value === 'string' ? value.trim() : '';
}

/**
 * This is intentionally a conservative estimate. The Jev adapter must apply
 * its tokenizer as a second gate; this estimate keeps the reference request
 * bounded before it reaches a provider.
 */
export function estimateTokens(value) {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  return Math.max(1, Math.ceil(new TextEncoder().encode(text).length / 4));
}

export function truncateToTokens(value, maxTokens) {
  const text = String(value ?? '');
  if (!text || maxTokens <= 0) return '';
  if (estimateTokens(text) <= maxTokens) return text;
  let low = 0;
  let high = text.length;
  while (low < high) {
    const middle = Math.ceil((low + high) / 2);
    if (estimateTokens(text.slice(0, middle)) <= maxTokens) low = middle;
    else high = middle - 1;
  }
  return text.slice(0, low).trimEnd();
}

function truncateChars(value) {
  const text = asText(value);
  return text.length <= selectionMaxTextChars ? text : `${text.slice(0, selectionMaxTextChars)}…`;
}

function normaliseTurn(turn) {
  if (!turn || typeof turn !== 'object') return null;
  const query = truncateChars(turn.query);
  const answer = truncateChars(turn.answer);
  return query || answer ? { query, answer } : null;
}

/**
 * Build the bounded text shown to Jev for one candidate Session.
 *
 * The sections deliberately keep the requested evidence visible: the first
 * query, its answer when known, the latest answer, and the last two completed
 * query/answer pairs. The caller supplies the token budget because the whole
 * Jev request (all choices plus the new-session entry) has one shared limit.
 */
export function buildSelectionBrief(selection = {}, { maxTokens = 512 } = {}) {
  check(Number.isSafeInteger(maxTokens) && maxTokens > 0, 'invalid_config', 'maxTokens');
  const first = normaliseTurn(selection.firstTurn) ?? {
    query: truncateChars(selection.firstQuery),
    answer: truncateChars(selection.firstAnswer),
  };
  const recent = Array.isArray(selection.recentTurns)
    ? selection.recentTurns.map(normaliseTurn).filter(Boolean).slice(-2)
    : [];
  const latestAnswer = truncateChars(selection.latestAnswer || recent.at(-1)?.answer || first.answer);
  const sections = [];
  if (first.query) sections.push(`First query: ${first.query}`);
  if (first.answer) sections.push(`First answer: ${first.answer}`);
  if (latestAnswer) sections.push(`Latest answer: ${latestAnswer}`);
  recent.forEach((turn, index) => {
    if (turn.query) sections.push(`Recent query ${index + 1}: ${turn.query}`);
    if (turn.answer) sections.push(`Recent answer ${index + 1}: ${turn.answer}`);
  });
  if (!sections.length) return '';
  // Preserve a small fragment of every requested evidence section before
  // spending the remaining budget on longer text. A single tail truncation
  // would otherwise hide the recent turns whenever the first query is long.
  const perSection = Math.max(1, Math.floor(maxTokens / sections.length));
  const compact = sections.map(section => truncateToTokens(section, perSection));
  let brief = compact.join('\n');
  if (estimateTokens(brief) > maxTokens) brief = truncateToTokens(brief, maxTokens);
  return brief;
}

function promptText(prompt) {
  if (typeof prompt === 'string') return prompt.trim();
  if (!Array.isArray(prompt)) return '';
  return prompt.filter(block => block?.type === 'text' && typeof block.text === 'string')
    .map(block => block.text.trim()).filter(Boolean).join('\n');
}

function requestTokens(request) {
  const choices = request.choices.map(choice => ({ choiceId: choice.choiceId, brief: choice.brief }));
  return estimateTokens(JSON.stringify({ query: request.query, choices }));
}

/**
 * Keep a per-user, bounded view of recently active Sessions. The durable
 * Session record remains the source of truth. Each refresh reconstructs the
 * list from the store, so expired entries and user ids are not retained in an
 * unbounded in-memory cache after a daemon restart.
 */
export class ActiveSessionPool {
  constructor(store, {
    now = Date.now,
    activeWindowMs = ACTIVE_SESSION_WINDOW_MS,
    maxActiveSessions = DEFAULT_MAX_ACTIVE_SESSIONS,
    minRecentSessions = Number(process.env.AGENTOS_JEV_MIN_RECENT_SESSIONS ?? DEFAULT_MIN_RECENT_SESSIONS),
  } = {}) {
    check(store && typeof store.list === 'function', 'invalid_params');
    this.store = store;
    this.now = now;
    this.activeWindowMs = activeWindowMs;
    this.maxActiveSessions = maxActiveSessions;
    this.minRecentSessions = minRecentSessions;
    validateConfig({ activeWindowMs, maxActiveSessions, minRecentSessions });
  }

  records(userId) {
    check(typeof userId === 'string' && userId.length > 0, 'invalid_params');
    const cutoff = this.now() - this.activeWindowMs;
    return this.store.list()
      .filter(session => session.userId === userId && !terminal.has(session.state))
      .filter(session => asText(session.selection?.firstQuery))
      .map(session => ({ session, active: Number.isSafeInteger(session.lastActivityAt) && session.lastActivityAt >= cutoff }))
      .sort((a, b) => b.session.lastActivityAt - a.session.lastActivityAt || a.session.id.localeCompare(b.session.id));
  }

  choice(session, active) {
    return {
      choiceId: session.id,
      sessionId: session.id,
      brief: buildSelectionBrief(session.selection),
      lastActivityAt: session.lastActivityAt,
      active,
      stale: !active,
    };
  }

  /** Sessions that are currently active. A session outside the TTL is absent. */
  list(userId) {
    const active = this.records(userId).filter(record => record.active).slice(0, this.maxActiveSessions);
    return active.map(record => this.choice(record.session, true));
  }

  /**
   * Candidate view for Jev. The 30-minute TTL remains a hard boundary for the
   * active pool. If fewer than the configured recent floor are active, the
   * selector may show the newest non-terminal cold Sessions as stale fallback
   * choices; they never count as active and are labelled in the result.
   */
  selectionCandidates(userId) {
    const records = this.records(userId);
    const active = records.filter(record => record.active).slice(0, this.maxActiveSessions);
    const selected = new Set(active.map(record => record.session.id));
    const fallbackCount = Math.min(
      Math.max(0, this.minRecentSessions - active.length),
      Math.max(0, this.maxActiveSessions - active.length),
    );
    const fallback = records.filter(record => !selected.has(record.session.id)).slice(0, fallbackCount);
    return [
      ...active.map(record => this.choice(record.session, true)),
      ...fallback.map(record => this.choice(record.session, false)),
    ];
  }

  snapshot(userId) {
    const choices = this.list(userId);
    return { refreshedAt: this.now(), sessionIds: choices.map(choice => choice.sessionId) };
  }
}

function fixedNewChoice() {
  return { choiceId: NEW_SESSION_CHOICE_ID, sessionId: null, brief: 'Start a new Session', fixed: true };
}

function resultChoice(result) {
  if (typeof result === 'string') return result;
  if (result && typeof result === 'object') return result.choiceId ?? result.sessionId ?? '';
  return '';
}

/**
 * Model-facing Session selection. `model.choose(request)` is the only Jev
 * integration seam; it receives already-bounded, user-isolated choices and
 * must return one exact `choiceId`.
 */
export class SessionSelector {
  constructor(store, {
    model = null,
    now = Date.now,
    activeWindowMs = ACTIVE_SESSION_WINDOW_MS,
    maxActiveSessions = DEFAULT_MAX_ACTIVE_SESSIONS,
    minRecentSessions = Number(process.env.AGENTOS_JEV_MIN_RECENT_SESSIONS ?? DEFAULT_MIN_RECENT_SESSIONS),
    maxInputTokens = Number(process.env.AGENTOS_JEV_INPUT_TOKENS ?? DEFAULT_JEV_INPUT_TOKENS),
    maxOutputTokens = Number(process.env.AGENTOS_JEV_OUTPUT_TOKENS ?? DEFAULT_JEV_OUTPUT_TOKENS),
  } = {}) {
    check(store && typeof store.list === 'function', 'invalid_params');
    check(!model || typeof model.choose === 'function', 'invalid_params');
    this.model = model;
    this.maxInputTokens = maxInputTokens;
    this.maxOutputTokens = maxOutputTokens;
    validateConfig({ activeWindowMs, maxActiveSessions, minRecentSessions, maxInputTokens, maxOutputTokens });
    this.pool = new ActiveSessionPool(store, { now, activeWindowMs, maxActiveSessions, minRecentSessions });
  }

  choices(userId) {
    return [...this.pool.selectionCandidates(userId), fixedNewChoice()];
  }

  boundedRequest(query, choices) {
    const queryText = truncateToTokens(query.trim(), Math.max(1, Math.floor(this.maxInputTokens / 4)));
    const source = choices.map(choice => ({ ...choice, brief: choice.brief }));
    const empty = source.map(choice => ({ ...choice, brief: '' }));
    const available = this.maxInputTokens - requestTokens({ query: queryText, choices: empty });
    if (available < 0) return null;
    const perChoice = source.length ? Math.floor(available / source.length) : 0;
    let boundedChoices = source.map(choice => ({ ...choice, brief: truncateToTokens(choice.brief, perChoice) }));
    let request = {
      kind: 'agent_session_selection',
      query: queryText,
      choices: boundedChoices,
      fixedChoiceId: boundedChoices.some(choice => choice.choiceId === NEW_SESSION_CHOICE_ID)
        ? NEW_SESSION_CHOICE_ID : null,
      allowNewSession: boundedChoices.some(choice => choice.choiceId === NEW_SESSION_CHOICE_ID),
      tokenBudget: { maxInputTokens: this.maxInputTokens, maxOutputTokens: this.maxOutputTokens },
    };
    // The equal allocation is conservative, but JSON overhead and CJK byte
    // estimates can still exceed the configured limit. Trim the longest brief
    // until the request is bounded; an undersized budget is handled by the
    // caller as a safe new-session fallback.
    while (requestTokens(request) > this.maxInputTokens) {
      const index = request.choices.reduce((best, choice, i, list) =>
        choice.brief.length > (list[best]?.brief.length ?? -1) ? i : best, -1);
      if (index < 0 || !request.choices[index].brief) break;
      request.choices[index].brief = truncateToTokens(request.choices[index].brief,
        Math.max(0, estimateTokens(request.choices[index].brief) - 1));
    }
    return requestTokens(request) <= this.maxInputTokens ? request : null;
  }

  needsStaging(query, choices) {
    const queryText = truncateToTokens(query.trim(), Math.max(1, Math.floor(this.maxInputTokens / 4)));
    return requestTokens({ query: queryText, choices }) > this.maxInputTokens;
  }

  batches(query, choices) {
    const batches = [];
    let current = [];
    for (const choice of choices) {
      const candidate = [...current, choice];
      // Use the bounded form for batching: a long individual brief may still
      // fit after a fair per-choice allocation, while the unbounded estimate
      // is only used to decide whether the overall request needs staging.
      if (current.length && !this.boundedRequest(query, candidate)) {
        batches.push(current);
        current = [choice];
      } else {
        current = candidate;
      }
    }
    if (current.length) batches.push(current);
    return batches;
  }

  async choose(request, allowedIds, allChoices, signal) {
    try {
      const decision = await this.model.choose(request, { signal });
      const choiceId = resultChoice(decision);
      if (!allowedIds.has(choiceId)) return { error: 'jev_invalid_choice' };
      return {
        choice: allChoices.find(choice => choice.choiceId === choiceId),
        requestTokens: requestTokens(request),
        model: decision?.model,
        confidence: decision?.confidence,
        probabilities: decision?.probabilities,
        usage: decision?.usage,
      };
    } catch (error) {
      return { error: 'jev_error', errorObject: error };
    }
  }

  finish(choice, allChoices, selectionMethod, requestTokensValue, extra = {}) {
    return {
      choiceId: choice.choiceId,
      sessionId: choice.sessionId,
      isNewSession: choice.choiceId === NEW_SESSION_CHOICE_ID,
      selectionMethod,
      choices: allChoices,
      requestTokens: requestTokensValue,
      ...extra,
    };
  }

  async select({ userId, query, signal } = {}) {
    check(typeof userId === 'string' && userId.length > 0 && typeof query === 'string' && query.trim(), 'invalid_params');
    const allChoices = this.choices(userId);
    if (!this.model) return this.fallback(allChoices, 'jev_unconfigured');
    const existing = allChoices.filter(choice => choice.choiceId !== NEW_SESSION_CHOICE_ID);
    const fixed = fixedNewChoice();
    const rawChoices = [...existing, fixed];
    const mustStage = this.needsStaging(query, rawChoices);
    if (!mustStage) {
      const request = this.boundedRequest(query, rawChoices);
      if (!request) return this.fallback(allChoices, 'jev_input_budget_too_small');
      const outcome = await this.choose(request, new Set(rawChoices.map(choice => choice.choiceId)), allChoices, signal);
      if (outcome.error) return this.fallback(allChoices, outcome.error, outcome.errorObject);
      return this.finish(outcome.choice, allChoices, 'jev', outcome.requestTokens, {
        model: outcome.model, confidence: outcome.confidence, probabilities: outcome.probabilities, usage: outcome.usage,
      });
    }

    // Long briefs are evaluated in batches without new_session. The winners
    // are then compared once more with the fixed new-session Choice, so a
    // staged run never creates a new Session merely because a batch was small.
    const stages = this.batches(query, existing);
    const winners = [];
    for (const stage of stages) {
      const request = this.boundedRequest(query, stage);
      if (!request) return this.fallback(allChoices, 'jev_input_budget_too_small');
      const outcome = await this.choose(request, new Set(stage.map(choice => choice.choiceId)), allChoices, signal);
      if (outcome.error) return this.fallback(allChoices, outcome.error, outcome.errorObject);
      winners.push(outcome.choice);
    }
    let finalists = [...new Map(winners.map(choice => [choice.choiceId, choice])).values()];
    let reductionRounds = 0;
    // A low token budget can make even the winner list too large for one
    // final Choice request. Reduce that list through more Jev rounds until
    // the fixed new-session entry fits beside it.
    while (!this.boundedRequest(query, [...finalists, fixed])) {
      const reductionStages = this.batches(query, finalists);
      if (reductionStages.length >= finalists.length) {
        return this.fallback(allChoices, 'jev_input_budget_too_small');
      }
      const reduced = [];
      for (const stage of reductionStages) {
        const request = this.boundedRequest(query, stage);
        if (!request) return this.fallback(allChoices, 'jev_input_budget_too_small');
        const outcome = await this.choose(request, new Set(stage.map(choice => choice.choiceId)), allChoices, signal);
        if (outcome.error) return this.fallback(allChoices, outcome.error, outcome.errorObject);
        reduced.push(outcome.choice);
      }
      finalists = [...new Map(reduced.map(choice => [choice.choiceId, choice])).values()];
      reductionRounds += 1;
    }
    finalists = [...finalists, fixed];
    const finalRequest = this.boundedRequest(query, finalists);
    if (!finalRequest) return this.fallback(allChoices, 'jev_input_budget_too_small');
    const final = await this.choose(finalRequest, new Set(finalists.map(choice => choice.choiceId)), allChoices, signal);
    if (final.error) return this.fallback(allChoices, final.error, final.errorObject);
    return this.finish(final.choice, allChoices, 'jev_staged', final.requestTokens,
      {
        stageCount: stages.length + reductionRounds + 1,
        model: final.model, confidence: final.confidence, probabilities: final.probabilities, usage: final.usage,
      });
  }

  fallback(choices, reason, error = null) {
    return {
      choiceId: NEW_SESSION_CHOICE_ID,
      sessionId: null,
      isNewSession: true,
      selectionMethod: 'fallback_new_session',
      fallbackReason: reason,
      choices,
      errorCode: error?.code ?? undefined,
    };
  }
}
