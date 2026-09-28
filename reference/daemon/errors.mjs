export class SchedulerError extends Error {
  constructor(code, message = code) { super(message); this.code = code; }
}
export function check(condition, code, message) {
  if (!condition) throw new SchedulerError(code, message);
}
export function sequence(value) {
  check(typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value), 'invalid_cursor');
  const n = BigInt(value);
  check(n <= 9223372036854775807n, 'invalid_cursor');
  return n;
}
