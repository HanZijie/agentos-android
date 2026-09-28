// Node stand-in for the Kotlin host: runs dist/pi-agent.js inside a bare `vm`
// context (ECMAScript built-ins only — no fetch, timers, TextDecoder, URL...), so
// the bundle sees the same empty world it sees in QuickJS. Host primitives are
// implemented with Node APIs; the model key is injected inside __host_fetch.
import vm from "node:vm";
import fs from "node:fs";

export const PLACEHOLDER_API_KEY = "agentos-host-injected-key";

/**
 * credentials: [{ baseUrl, key }] — the key is injected only for requests whose URL
 * starts with baseUrl, and only in place of the placeholder (x-api-key / Bearer).
 */
export class VmPiEngine {
  constructor({ bundlePath, credentials = [], tools = {}, beforeToolCall, onEvent, onLog } = {}) {
    this.bundlePath = bundlePath;
    this.credentials = credentials;
    this.tools = tools;
    this.beforeToolCall = beforeToolCall;
    this.onEvent = onEvent ?? (() => {});
    this.onLog = onLog ?? (() => {});
    this.pending = new Map();
    this.commandQueue = [];
    this.commandWaiter = null;
    this.nextId = 1;
    this.timers = new Map();
    this.requests = new Map();
    this.ingressViolations = [];
    this.stats = { fetches: 0, fetchAborts: 0, bytesToJs: 0, emits: 0 };
  }

  audit(value) {
    // Everything that crosses host -> JS goes through here. If a real key ever
    // appears, JS could read it; record the violation.
    const text = typeof value === "string" ? value : value instanceof Uint8Array ? Buffer.from(value).toString("latin1") : value == null ? "" : JSON.stringify(value);
    for (const c of this.credentials) if (c.key && text.includes(c.key)) this.ingressViolations.push(text.slice(0, 80));
    return value;
  }

  credentialFor(url) {
    return this.credentials.find((c) => url.startsWith(c.baseUrl));
  }

  async start() {
    const t0 = performance.now();
    const source = fs.readFileSync(this.bundlePath, "utf8");
    const context = vm.createContext({});
    const g = vm.runInContext("globalThis", context);
    const InnerUint8Array = vm.runInContext("Uint8Array", context);
    this.context = context;
    this.global = g;

    g.__host_emit = (json) => {
      this.stats.emits++;
      const msg = JSON.parse(json);
      if (msg.t === "reply") {
        const p = this.pending.get(msg.id);
        if (!p) return;
        this.pending.delete(msg.id);
        msg.ok ? p.resolve(msg.value) : p.reject(Object.assign(new Error(msg.error?.message), { js: msg.error }));
      } else if (msg.t === "event" || msg.t === "tool_cancel") this.onEvent(msg);
      else if (msg.t === "ready") this.readyResolve?.(msg);
      else if (msg.t === "log") this.onLog(msg);
    };
    g.__host_next = () =>
      new Promise((resolve) => {
        if (this.commandQueue.length) resolve(this.audit(this.commandQueue.shift()));
        else this.commandWaiter = resolve;
      });
    g.__host_timer = (id, ms) =>
      new Promise((resolve) => {
        const handle = setTimeout(() => { this.timers.delete(id); resolve("fired"); }, ms);
        this.timers.set(id, { handle, resolve });
      });
    g.__host_timer_cancel = (id) => {
      const t = this.timers.get(id);
      if (!t) return;
      clearTimeout(t.handle);
      this.timers.delete(id);
      t.resolve("cancelled");
    };
    g.__host_fetch = async (reqId, json) => {
      const req = JSON.parse(json);
      this.stats.fetches++;
      const headers = new Headers(req.headers);
      const cred = this.credentialFor(req.url);
      for (const [name, value] of [...headers.entries()]) {
        if (value === PLACEHOLDER_API_KEY || value === `Bearer ${PLACEHOLDER_API_KEY}`) {
          if (!cred) throw new Error(`No credential for ${new URL(req.url).origin}`);
          headers.set(name, value.startsWith("Bearer ") ? `Bearer ${cred.key}` : cred.key);
        }
      }
      const controller = new AbortController();
      const entry = { controller, reader: null, startedAt: performance.now() };
      this.requests.set(reqId, entry);
      try {
        const res = await fetch(req.url, { method: req.method, headers, body: req.body ?? undefined, signal: controller.signal });
        entry.reader = res.body?.getReader() ?? null;
        entry.headersAt = performance.now();
        return this.audit(JSON.stringify({ status: res.status, statusText: res.statusText, headers: [...res.headers.entries()], url: res.url }));
      } catch (e) {
        this.requests.delete(reqId);
        throw new Error(e?.name === "AbortError" ? "aborted" : String(e?.message ?? e));
      }
    };
    g.__host_fetch_read = async (reqId) => {
      const entry = this.requests.get(reqId);
      if (!entry?.reader) return null;
      try {
        const { value, done } = await entry.reader.read();
        if (done) { this.requests.delete(reqId); return null; }
        this.stats.bytesToJs += value.length;
        this.audit(value);
        return new InnerUint8Array(value);
      } catch (e) {
        this.requests.delete(reqId);
        throw new Error(e?.name === "AbortError" ? "aborted" : String(e?.message ?? e));
      }
    };
    g.__host_fetch_abort = (reqId) => {
      const entry = this.requests.get(reqId);
      if (!entry) return;
      this.stats.fetchAborts++;
      entry.controller.abort();
      this.requests.delete(reqId);
    };
    g.__host_call = async (method, json) => {
      const payload = JSON.parse(json);
      if (method === "tool") {
        const fn = this.tools[payload.name];
        if (!fn) return this.audit(JSON.stringify({ isError: true, content: [{ type: "text", text: `unknown tool ${payload.name}` }] }));
        return this.audit(JSON.stringify(await fn(payload.args, payload)));
      }
      if (method === "beforeToolCall") return this.audit(JSON.stringify((await this.beforeToolCall?.(payload)) ?? {}));
      throw new Error(`unknown host method ${method}`);
    };

    const ready = new Promise((r) => (this.readyResolve = r));
    const t1 = performance.now();
    vm.runInContext(source, context, { filename: "pi-agent.js" });
    const t2 = performance.now();
    this.mainPromise = vm.runInContext("__pi_main()", context);
    this.mainPromise.catch((e) => { this.mainError = e; });
    await ready;
    const t3 = performance.now();
    this.startup = { readMs: t1 - t0, evalMs: t2 - t1, readyMs: t3 - t2, totalMs: t3 - t0 };
    return this.startup;
  }

  request(op, payload = {}) {
    const id = this.nextId++;
    const cmd = JSON.stringify({ id, op, ...payload });
    const promise = new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }));
    if (this.commandWaiter) { const w = this.commandWaiter; this.commandWaiter = null; w(this.audit(cmd)); }
    else this.commandQueue.push(cmd);
    return promise;
  }

  inflightHostRequests() { return this.requests.size; }
  activeHostTimers() { return this.timers.size; }

  async stop() {
    if (this.commandWaiter) { const w = this.commandWaiter; this.commandWaiter = null; w(null); }
    else this.commandQueue.push(null);
    const r = await this.mainPromise;
    for (const [, t] of this.timers) clearTimeout(t.handle);
    return r;
  }
}
