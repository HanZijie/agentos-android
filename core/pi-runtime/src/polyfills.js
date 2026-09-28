/*
 * Web API polyfills for running pi-agent-core + pi-ai inside a bare JS engine
 * (QuickJS via quickjs-kt, or a Node `vm` context used as a stand-in).
 *
 * Rules:
 * - No I/O here. Timers and fetch are thin wrappers over host primitives
 *   (see host-bridge.js); the host implements them for real (OkHttp, coroutine delay).
 * - Every polyfill is installed only when the global is missing, so running the
 *   bundle in an engine that already has the API keeps the native one.
 */

const g = globalThis;

function define(name, value) {
  if (typeof g[name] === "undefined") {
    Object.defineProperty(g, name, { value, writable: true, configurable: true, enumerable: false });
  }
}

// ---------------------------------------------------------------------------
// console (QuickJS embedders usually do not provide one)
// ---------------------------------------------------------------------------
if (typeof g.console === "undefined") {
  const log = (level) => (...args) => {
    try {
      const text = args.map((a) => (typeof a === "string" ? a : safeString(a))).join(" ");
      if (typeof g.__host_emit === "function") g.__host_emit(JSON.stringify({ t: "log", level, msg: text }));
    } catch {}
  };
  define("console", { log: log("info"), info: log("info"), debug: log("debug"), warn: log("warn"), error: log("error"), trace: log("debug") });
}

function safeString(value) {
  if (value instanceof Error) return `${value.name}: ${value.message}`;
  try { return JSON.stringify(value); } catch { return String(value); }
}

// ---------------------------------------------------------------------------
// Timers: real delays implemented by the host (coroutine delay / Node timers)
// ---------------------------------------------------------------------------
{
  let nextTimerId = 1;
  const active = new Map();

  class Timeout {
    constructor(id) { this.id = id; }
    ref() { return this; }
    unref() { return this; }
    hasRef() { return true; }
    refresh() { return this; }
    [Symbol.toPrimitive]() { return this.id; }
  }

  function schedule(fn, ms, args, repeat) {
    if (typeof fn !== "function") throw new TypeError("Timer callback must be a function");
    const id = nextTimerId++;
    const delay = Math.max(0, Number(ms) || 0);
    const entry = { fn, args, repeat, delay };
    active.set(id, entry);
    arm(id, entry);
    return new Timeout(id);
  }

  function arm(id, entry) {
    Promise.resolve(g.__host_timer(id, entry.delay)).then((result) => {
      if (result === "cancelled" || active.get(id) !== entry) return;
      if (!entry.repeat) active.delete(id);
      try { entry.fn(...entry.args); }
      finally { if (entry.repeat && active.get(id) === entry) arm(id, entry); }
    }, () => { active.delete(id); });
  }

  function cancel(handle) {
    const id = handle == null ? undefined : Number(handle instanceof Timeout ? handle.id : handle);
    if (id === undefined || !active.has(id)) return;
    active.delete(id);
    try { g.__host_timer_cancel(id); } catch {}
  }

  define("setTimeout", (fn, ms, ...args) => schedule(fn, ms, args, false));
  define("setInterval", (fn, ms, ...args) => schedule(fn, ms, args, true));
  define("clearTimeout", cancel);
  define("clearInterval", cancel);
  define("setImmediate", (fn, ...args) => schedule(fn, 0, args, false));
  define("clearImmediate", cancel);
  define("__pi_activeTimerCount", () => active.size);
}

define("queueMicrotask", (fn) => { Promise.resolve().then(fn); });

// ---------------------------------------------------------------------------
// UTF-8 TextEncoder / TextDecoder (TextDecoder supports { stream: true })
// ---------------------------------------------------------------------------
define("TextEncoder", class TextEncoder {
  get encoding() { return "utf-8"; }
  encode(input = "") {
    const str = String(input);
    const out = [];
    for (let i = 0; i < str.length; i++) {
      let cp = str.charCodeAt(i);
      if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < str.length) {
        const next = str.charCodeAt(i + 1);
        if (next >= 0xdc00 && next <= 0xdfff) { cp = 0x10000 + ((cp - 0xd800) << 10) + (next - 0xdc00); i++; }
        else cp = 0xfffd;
      } else if (cp >= 0xd800 && cp <= 0xdfff) cp = 0xfffd;
      if (cp < 0x80) out.push(cp);
      else if (cp < 0x800) out.push(0xc0 | (cp >> 6), 0x80 | (cp & 63));
      else if (cp < 0x10000) out.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
      else out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 63), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
    }
    return new Uint8Array(out);
  }
  encodeInto(input, dest) {
    const bytes = this.encode(input);
    const n = Math.min(bytes.length, dest.length);
    dest.set(bytes.subarray(0, n));
    return { read: String(input).length, written: n };
  }
});

define("TextDecoder", class TextDecoder {
  constructor(label = "utf-8", options = {}) {
    const l = String(label).toLowerCase();
    if (l !== "utf-8" && l !== "utf8" && l !== "unicode-1-1-utf-8") throw new RangeError(`Unsupported encoding: ${label}`);
    this.fatal = !!options.fatal;
    this.ignoreBOM = !!options.ignoreBOM;
    this._pending = [];
    this._bomSeen = false;
  }
  get encoding() { return "utf-8"; }
  decode(input, options = {}) {
    let bytes;
    if (input == null) bytes = [];
    else if (input instanceof ArrayBuffer) bytes = new Uint8Array(input);
    else if (ArrayBuffer.isView(input)) bytes = new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
    else bytes = input; // array-like (e.g. Uint8Array from another realm)
    const stream = !!options.stream;
    const buf = this._pending.length ? this._pending.concat(Array.from(bytes)) : bytes;
    this._pending = [];
    let out = "";
    let i = 0;
    const n = buf.length;
    const chunks = [];
    const cps = [];
    const flush = () => { if (cps.length) { chunks.push(String.fromCharCode.apply(null, cps)); cps.length = 0; } };
    while (i < n) {
      const b0 = buf[i];
      let need = 0, cp = 0;
      if (b0 < 0x80) { cps.push(b0); i++; if (cps.length > 8192) flush(); continue; }
      else if (b0 >= 0xc2 && b0 <= 0xdf) { need = 1; cp = b0 & 0x1f; }
      else if (b0 >= 0xe0 && b0 <= 0xef) { need = 2; cp = b0 & 0x0f; }
      else if (b0 >= 0xf0 && b0 <= 0xf4) { need = 3; cp = b0 & 0x07; }
      else { if (this.fatal) throw new TypeError("Invalid UTF-8"); cps.push(0xfffd); i++; continue; }
      if (i + need >= n) {
        // The sequence is cut off by the end of this chunk. In streaming mode keep
        // the bytes for the next call, as long as what we have is well-formed so far.
        let prefixOk = true;
        for (let k = i + 1; k < n; k++) if ((buf[k] & 0xc0) !== 0x80) prefixOk = false;
        if (prefixOk && stream) { this._pending = Array.prototype.slice.call(buf, i); break; }
      }
      let valid = true;
      for (let k = 1; k <= need; k++) {
        const b = buf[i + k];
        if (b === undefined || (b & 0xc0) !== 0x80) { valid = false; break; }
        cp = (cp << 6) | (b & 0x3f);
      }
      if (valid && ((need === 2 && (cp < 0x800 || (cp >= 0xd800 && cp <= 0xdfff))) || (need === 3 && (cp < 0x10000 || cp > 0x10ffff)))) valid = false;
      if (!valid) {
        if (this.fatal) throw new TypeError("Invalid UTF-8");
        cps.push(0xfffd); i++; continue;
      }
      if (cp >= 0x10000) { cp -= 0x10000; cps.push(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff)); }
      else cps.push(cp);
      i += need + 1;
      if (cps.length > 8192) flush();
    }
    flush();
    out = chunks.join("");
    if (!this._bomSeen && out.length) {
      this._bomSeen = true;
      if (!this.ignoreBOM && out.charCodeAt(0) === 0xfeff) out = out.slice(1);
    }
    if (!stream) {
      if (this._pending.length) { this._pending = []; if (this.fatal) throw new TypeError("Invalid UTF-8"); out += "\ufffd"; }
      this._bomSeen = false;
    }
    return out;
  }
});

// ---------------------------------------------------------------------------
// Event / EventTarget / DOMException / AbortController / AbortSignal
// ---------------------------------------------------------------------------
define("DOMException", class DOMException extends Error {
  constructor(message = "", name = "Error") { super(message); this.name = name; }
  get code() { return this.name === "AbortError" ? 20 : this.name === "TimeoutError" ? 23 : 0; }
});

define("Event", class Event {
  constructor(type, init = {}) { this.type = String(type); this.bubbles = !!init.bubbles; this.cancelable = !!init.cancelable; this.defaultPrevented = false; this.target = null; this.currentTarget = null; this.timeStamp = Date.now(); }
  preventDefault() { if (this.cancelable) this.defaultPrevented = true; }
  stopPropagation() {}
  stopImmediatePropagation() { this._stop = true; }
});

define("EventTarget", class EventTarget {
  constructor() { Object.defineProperty(this, "_listeners", { value: new Map(), enumerable: false }); }
  addEventListener(type, listener, options) {
    if (!listener) return;
    const once = typeof options === "object" && options !== null && !!options.once;
    const signal = typeof options === "object" && options !== null ? options.signal : undefined;
    if (signal?.aborted) return;
    let list = this._listeners.get(type);
    if (!list) { list = []; this._listeners.set(type, list); }
    if (list.some((l) => l.listener === listener)) return;
    const record = { listener, once };
    list.push(record);
    signal?.addEventListener("abort", () => this.removeEventListener(type, listener), { once: true });
  }
  removeEventListener(type, listener) {
    const list = this._listeners.get(type);
    if (!list) return;
    const i = list.findIndex((l) => l.listener === listener);
    if (i >= 0) list.splice(i, 1);
  }
  dispatchEvent(event) {
    event.target = this; event.currentTarget = this;
    const handler = this["on" + event.type];
    const list = (this._listeners.get(event.type) || []).slice();
    if (typeof handler === "function") { try { handler.call(this, event); } catch (e) { reportError(e); } }
    for (const rec of list) {
      if (rec.once) this.removeEventListener(event.type, rec.listener);
      try {
        if (typeof rec.listener === "function") rec.listener.call(this, event);
        else if (rec.listener && typeof rec.listener.handleEvent === "function") rec.listener.handleEvent(event);
      } catch (e) { reportError(e); }
      if (event._stop) break;
    }
    return !event.defaultPrevented;
  }
});

function reportError(e) {
  try { console.error("listener error", e && e.stack ? e.stack : String(e)); } catch {}
}

{
  const AbortSignalImpl = class AbortSignal extends g.EventTarget {
    constructor() { super(); this.aborted = false; this.reason = undefined; this.onabort = null; }
    throwIfAborted() { if (this.aborted) throw this.reason; }
    static abort(reason) { const c = new g.AbortController(); c.abort(reason); return c.signal; }
    static timeout(ms) {
      const c = new g.AbortController();
      setTimeout(() => c.abort(new g.DOMException("The operation timed out.", "TimeoutError")), ms);
      return c.signal;
    }
    static any(signals) {
      const c = new g.AbortController();
      for (const s of signals) {
        if (s.aborted) { c.abort(s.reason); return c.signal; }
      }
      for (const s of signals) s.addEventListener("abort", () => c.abort(s.reason), { once: true });
      return c.signal;
    }
  };
  define("AbortSignal", AbortSignalImpl);
  define("AbortController", class AbortController {
    constructor() { this.signal = new g.AbortSignal(); }
    abort(reason) {
      const s = this.signal;
      if (s.aborted) return;
      s.aborted = true;
      s.reason = reason === undefined ? new g.DOMException("This operation was aborted", "AbortError") : reason;
      s.dispatchEvent(new g.Event("abort"));
    }
  });
}

// ---------------------------------------------------------------------------
// structuredClone / crypto / base64
// ---------------------------------------------------------------------------
define("structuredClone", (value) => (value === undefined ? undefined : JSON.parse(JSON.stringify(value))));

if (typeof g.crypto === "undefined") define("crypto", {});
if (!g.crypto.getRandomValues) {
  g.crypto.getRandomValues = (arr) => {
    for (let i = 0; i < arr.length; i++) arr[i] = Math.floor(Math.random() * 256) & 0xff;
    return arr;
  };
}
if (!g.crypto.randomUUID) {
  g.crypto.randomUUID = () => {
    const b = g.crypto.getRandomValues(new Uint8Array(16));
    b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
    const h = Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
    return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
  };
}

const B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
define("btoa", (input) => {
  const s = String(input);
  let out = "";
  for (let i = 0; i < s.length; i += 3) {
    const a = s.charCodeAt(i), b = s.charCodeAt(i + 1), c = s.charCodeAt(i + 2);
    if (a > 255 || b > 255 || c > 255) throw new g.DOMException("Invalid character", "InvalidCharacterError");
    const n = (a << 16) | ((b || 0) << 8) | (c || 0);
    out += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] + (i + 1 < s.length ? B64[(n >> 6) & 63] : "=") + (i + 2 < s.length ? B64[n & 63] : "=");
  }
  return out;
});
define("atob", (input) => {
  const s = String(input).replace(/[\s=]+/g, "");
  let out = "";
  let bits = 0, acc = 0;
  for (let i = 0; i < s.length; i++) {
    const v = B64.indexOf(s[i]);
    if (v < 0) throw new g.DOMException("Invalid character", "InvalidCharacterError");
    acc = (acc << 6) | v; bits += 6;
    if (bits >= 8) { bits -= 8; out += String.fromCharCode((acc >> bits) & 0xff); }
  }
  return out;
});

// ---------------------------------------------------------------------------
// URL / URLSearchParams (enough for http(s) API clients)
// ---------------------------------------------------------------------------
define("URLSearchParams", class URLSearchParams {
  constructor(init) {
    this._list = [];
    this._url = null;
    if (init == null || init === "") return;
    if (typeof init === "string") this._parse(init);
    else if (typeof init[Symbol.iterator] === "function") for (const [k, v] of init) this._list.push([String(k), String(v)]);
    else for (const k of Object.keys(init)) this._list.push([k, String(init[k])]);
  }
  _parse(s) {
    this._list = [];
    const q = s.startsWith("?") ? s.slice(1) : s;
    if (!q) return;
    for (const part of q.split("&")) {
      if (!part) continue;
      const i = part.indexOf("=");
      const k = i < 0 ? part : part.slice(0, i);
      const v = i < 0 ? "" : part.slice(i + 1);
      this._list.push([decodeURIComponent(k.replace(/\+/g, " ")), decodeURIComponent(v.replace(/\+/g, " "))]);
    }
  }
  _update() { if (this._url) { const s = this.toString(); this._url._search = s ? "?" + s : ""; } }
  append(k, v) { this._list.push([String(k), String(v)]); this._update(); }
  delete(k) { this._list = this._list.filter(([x]) => x !== k); this._update(); }
  get(k) { const e = this._list.find(([x]) => x === k); return e ? e[1] : null; }
  getAll(k) { return this._list.filter(([x]) => x === k).map(([, v]) => v); }
  has(k) { return this._list.some(([x]) => x === k); }
  set(k, v) {
    const i = this._list.findIndex(([x]) => x === k);
    if (i < 0) this._list.push([String(k), String(v)]);
    else { this._list[i][1] = String(v); this._list = this._list.filter(([x], j) => x !== k || j === i); }
    this._update();
  }
  sort() { this._list.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)); this._update(); }
  forEach(fn, thisArg) { for (const [k, v] of this._list) fn.call(thisArg, v, k, this); }
  keys() { return this._list.map(([k]) => k)[Symbol.iterator](); }
  values() { return this._list.map(([, v]) => v)[Symbol.iterator](); }
  entries() { return this._list.map(([k, v]) => [k, v])[Symbol.iterator](); }
  [Symbol.iterator]() { return this.entries(); }
  get size() { return this._list.length; }
  toString() {
    const enc = (s) => encodeURIComponent(s).replace(/%20/g, "+").replace(/[!'()~]/g, (c) => "%" + c.charCodeAt(0).toString(16).toUpperCase());
    return this._list.map(([k, v]) => enc(k) + "=" + enc(v)).join("&");
  }
});

define("URL", class URL {
  constructor(input, base) {
    const s = String(input).trim();
    const m = /^([a-zA-Z][a-zA-Z0-9+.-]*:)(.*)$/.exec(s);
    if (m) this._init(m[1].toLowerCase(), m[2]);
    else {
      if (base === undefined) throw new TypeError(`Invalid URL: ${s}`);
      const b = base instanceof URL ? base : new URL(String(base));
      this._protocol = b._protocol; this._username = b._username; this._password = b._password; this._host = b._host;
      if (s.startsWith("//")) this._init(b._protocol, s);
      else {
        let rest = s;
        let hash = "", search = "";
        const hi = rest.indexOf("#"); if (hi >= 0) { hash = rest.slice(hi); rest = rest.slice(0, hi); }
        const qi = rest.indexOf("?"); if (qi >= 0) { search = rest.slice(qi); rest = rest.slice(0, qi); }
        let path;
        if (rest === "") { path = b._pathname; if (!search && qi < 0) search = b._search; }
        else if (rest.startsWith("/")) path = rest;
        else path = b._pathname.slice(0, b._pathname.lastIndexOf("/") + 1) + rest;
        this._pathname = normalizePath(path);
        this._search = search === "?" ? "" : search;
        this._hash = hash === "#" ? "" : hash;
      }
    }
    this._sp = null;
  }
  _init(protocol, rest) {
    this._protocol = protocol;
    this._username = ""; this._password = ""; this._host = "";
    let r = rest;
    let hash = "", search = "";
    const hi = r.indexOf("#"); if (hi >= 0) { hash = r.slice(hi); r = r.slice(0, hi); }
    const qi = r.indexOf("?"); if (qi >= 0) { search = r.slice(qi); r = r.slice(0, qi); }
    if (r.startsWith("//")) {
      r = r.slice(2);
      const si = r.indexOf("/");
      let authority = si < 0 ? r : r.slice(0, si);
      r = si < 0 ? "" : r.slice(si);
      const at = authority.lastIndexOf("@");
      if (at >= 0) {
        const cred = authority.slice(0, at); authority = authority.slice(at + 1);
        const ci = cred.indexOf(":");
        this._username = ci < 0 ? cred : cred.slice(0, ci);
        this._password = ci < 0 ? "" : cred.slice(ci + 1);
      }
      this._host = authority.toLowerCase();
      const defaultPort = protocol === "https:" ? ":443" : protocol === "http:" ? ":80" : null;
      if (defaultPort && this._host.endsWith(defaultPort)) this._host = this._host.slice(0, -defaultPort.length);
      if (!this._host && (protocol === "http:" || protocol === "https:")) throw new TypeError("Invalid URL: missing host");
      this._pathname = normalizePath(r || "/");
    } else {
      this._pathname = r;
    }
    this._search = search === "?" ? "" : search;
    this._hash = hash === "#" ? "" : hash;
  }
  get protocol() { return this._protocol; }
  set protocol(v) { this._protocol = String(v).replace(/:?$/, ":"); }
  get username() { return this._username; }
  get password() { return this._password; }
  get host() { return this._host; }
  set host(v) { this._host = String(v); }
  get hostname() { const h = this._host; if (h.startsWith("[")) return h.slice(0, h.indexOf("]") + 1); const i = h.lastIndexOf(":"); return i >= 0 ? h.slice(0, i) : h; }
  set hostname(v) { const p = this.port; this._host = String(v) + (p ? ":" + p : ""); }
  get port() { const h = this._host; const i = h.lastIndexOf(":"); return i >= 0 && !h.slice(i).includes("]") ? h.slice(i + 1) : ""; }
  set port(v) { this._host = this.hostname + (v ? ":" + v : ""); }
  get pathname() { return this._pathname; }
  set pathname(v) { this._pathname = normalizePath(String(v).startsWith("/") ? String(v) : "/" + v); }
  get search() { return this._search; }
  set search(v) { const s = String(v); this._search = s === "" || s === "?" ? "" : s.startsWith("?") ? s : "?" + s; if (this._sp) this._sp._parse(this._search); }
  get hash() { return this._hash; }
  set hash(v) { const s = String(v); this._hash = s === "" || s === "#" ? "" : s.startsWith("#") ? s : "#" + s; }
  get searchParams() {
    if (!this._sp) { this._sp = new g.URLSearchParams(this._search); this._sp._url = this; }
    return this._sp;
  }
  get origin() { return this._host ? `${this._protocol}//${this._host}` : "null"; }
  get href() {
    const cred = this._username ? this._username + (this._password ? ":" + this._password : "") + "@" : "";
    return this._host || this._protocol === "file:" ? `${this._protocol}//${cred}${this._host}${this._pathname}${this._search}${this._hash}` : `${this._protocol}${this._pathname}${this._search}${this._hash}`;
  }
  set href(v) { const u = new URL(v); Object.assign(this, u); this._sp = null; }
  toString() { return this.href; }
  toJSON() { return this.href; }
  static canParse(input, base) { try { new URL(input, base); return true; } catch { return false; } }
});

function normalizePath(path) {
  const segs = path.split("/");
  const out = [];
  for (let i = 0; i < segs.length; i++) {
    const s = segs[i];
    if (s === "..") { if (out.length > 1) out.pop(); if (i === segs.length - 1) out.push(""); }
    else if (s === ".") { if (i === segs.length - 1) out.push(""); }
    else out.push(s);
  }
  const joined = out.join("/");
  return joined.startsWith("/") ? joined : "/" + joined;
}

// ---------------------------------------------------------------------------
// Headers
// ---------------------------------------------------------------------------
define("Headers", class Headers {
  constructor(init) {
    this._map = new Map(); // lower-name -> [name, [values]]
    if (init == null) return;
    if (init instanceof Headers || (typeof init.forEach === "function" && typeof init.get === "function" && typeof init.entries === "function")) {
      for (const [k, v] of init.entries()) this.append(k, v);
    } else if (Array.isArray(init) || typeof init[Symbol.iterator] === "function") {
      for (const pair of init) this.append(pair[0], pair[1]);
    } else {
      for (const k of Object.keys(init)) { const v = init[k]; if (v !== undefined) this.append(k, v); }
    }
  }
  append(name, value) {
    const key = String(name).toLowerCase();
    const v = String(value).trim();
    const e = this._map.get(key);
    if (e) e[1].push(v); else this._map.set(key, [key, [v]]);
  }
  set(name, value) { this._map.set(String(name).toLowerCase(), [String(name).toLowerCase(), [String(value).trim()]]); }
  get(name) { const e = this._map.get(String(name).toLowerCase()); return e ? e[1].join(", ") : null; }
  getSetCookie() { const e = this._map.get("set-cookie"); return e ? e[1].slice() : []; }
  has(name) { return this._map.has(String(name).toLowerCase()); }
  delete(name) { this._map.delete(String(name).toLowerCase()); }
  forEach(fn, thisArg) { for (const [k, v] of this.entries()) fn.call(thisArg, v, k, this); }
  *entries() { for (const k of [...this._map.keys()].sort()) yield [k, this._map.get(k)[1].join(", ")]; }
  *keys() { for (const [k] of this.entries()) yield k; }
  *values() { for (const [, v] of this.entries()) yield v; }
  [Symbol.iterator]() { return this.entries(); }
});

// ---------------------------------------------------------------------------
// ReadableStream (pull-based subset: getReader/read/cancel/async iteration)
// ---------------------------------------------------------------------------
define("ReadableStream", class ReadableStream {
  constructor(source = {}, _strategy) {
    this._source = source;
    this._queue = [];
    this._state = "readable"; // readable | closed | errored
    this._error = undefined;
    this._waiters = [];
    this._pulling = false;
    this._reader = null;
    const controller = {
      enqueue: (chunk) => { if (this._state !== "readable") return; this._queue.push(chunk); this._drain(); },
      close: () => { if (this._state !== "readable") return; this._state = "closed"; this._drain(); },
      error: (e) => { if (this._state !== "readable") return; this._state = "errored"; this._error = e; this._queue = []; this._drain(); },
      get desiredSize() { return 1; },
    };
    this._controller = controller;
    try {
      const r = source.start ? source.start(controller) : undefined;
      if (r && typeof r.then === "function") r.then(undefined, (e) => controller.error(e));
    } catch (e) { controller.error(e); }
  }
  get locked() { return this._reader !== null; }
  _drain() {
    while (this._waiters.length) {
      if (this._queue.length) this._waiters.shift().resolve({ value: this._queue.shift(), done: false });
      else if (this._state === "closed") this._waiters.shift().resolve({ value: undefined, done: true });
      else if (this._state === "errored") this._waiters.shift().reject(this._error);
      else break;
    }
    if (this._waiters.length && this._state === "readable") this._pull();
  }
  _pull() {
    if (this._pulling || !this._source.pull) return;
    this._pulling = true;
    let r;
    try { r = this._source.pull(this._controller); } catch (e) { this._pulling = false; this._controller.error(e); return; }
    Promise.resolve(r).then(() => { this._pulling = false; this._drain(); }, (e) => { this._pulling = false; this._controller.error(e); });
  }
  _read() {
    return new Promise((resolve, reject) => { this._waiters.push({ resolve, reject }); this._drain(); });
  }
  cancel(reason) {
    if (this._state === "readable") {
      this._state = "closed";
      this._queue = [];
      try { this._source.cancel && this._source.cancel(reason); } catch {}
    }
    this._drain();
    return Promise.resolve();
  }
  getReader() {
    if (this._reader) throw new TypeError("ReadableStream is locked");
    const stream = this;
    const reader = {
      read: () => stream._read(),
      releaseLock: () => { if (stream._reader === reader) stream._reader = null; },
      cancel: (reason) => stream.cancel(reason),
      get closed() { return stream._state === "closed" ? Promise.resolve() : stream._state === "errored" ? Promise.reject(stream._error) : new Promise(() => {}); },
    };
    this._reader = reader;
    return reader;
  }
  async *[Symbol.asyncIterator]() {
    const reader = this.getReader();
    try {
      for (;;) { const { value, done } = await reader.read(); if (done) return; yield value; }
    } finally { reader.releaseLock(); }
  }
  values() { return this[Symbol.asyncIterator](); }
  tee() { throw new TypeError("ReadableStream.tee is not supported in this runtime"); }
});

// ---------------------------------------------------------------------------
// Blob (minimal; used only for type checks and small in-memory bodies)
// ---------------------------------------------------------------------------
define("Blob", class Blob {
  constructor(parts = [], options = {}) {
    const enc = new g.TextEncoder();
    const bufs = parts.map((p) => (typeof p === "string" ? enc.encode(p) : p instanceof Blob ? p._bytes : ArrayBuffer.isView(p) ? new Uint8Array(p.buffer, p.byteOffset, p.byteLength) : new Uint8Array(p)));
    const len = bufs.reduce((a, b) => a + b.length, 0);
    this._bytes = new Uint8Array(len);
    let o = 0; for (const b of bufs) { this._bytes.set(b, o); o += b.length; }
    this.type = options.type ? String(options.type).toLowerCase() : "";
  }
  get size() { return this._bytes.length; }
  async text() { return new g.TextDecoder().decode(this._bytes); }
  async arrayBuffer() { return this._bytes.slice().buffer; }
  slice(start, end, type) { const b = new Blob([], { type }); b._bytes = this._bytes.slice(start, end); return b; }
});

define("File", class File extends g.Blob {
  constructor(parts, name, options = {}) { super(parts, options); this.name = String(name); this.lastModified = options.lastModified ?? Date.now(); }
});

// The SDKs only use FormData for `instanceof` checks on the request path
// (multipart uploads are not used by AgentOS).
define("FormData", class FormData {
  constructor() { this._list = []; }
  append(name, value, filename) { this._list.push([String(name), value, filename]); }
  set(name, value, filename) { this.delete(name); this.append(name, value, filename); }
  get(name) { const e = this._list.find(([k]) => k === name); return e ? e[1] : null; }
  getAll(name) { return this._list.filter(([k]) => k === name).map(([, v]) => v); }
  has(name) { return this._list.some(([k]) => k === name); }
  delete(name) { this._list = this._list.filter(([k]) => k !== name); }
  *entries() { for (const [k, v] of this._list) yield [k, v]; }
  [Symbol.iterator]() { return this.entries(); }
});

// ---------------------------------------------------------------------------
// Response (body is a ReadableStream of Uint8Array, a string, or null)
// ---------------------------------------------------------------------------
define("Response", class Response {
  constructor(body = null, init = {}) {
    this.status = init.status ?? 200;
    this.statusText = init.statusText ?? "";
    this.headers = init.headers instanceof g.Headers ? init.headers : new g.Headers(init.headers);
    this.url = init.url ?? "";
    this.type = "basic";
    this.redirected = false;
    this.bodyUsed = false;
    if (body == null) this.body = null;
    else if (body instanceof g.ReadableStream) this.body = body;
    else {
      const bytes = typeof body === "string" ? new g.TextEncoder().encode(body) : body instanceof g.Blob ? body._bytes : ArrayBuffer.isView(body) ? new Uint8Array(body.buffer, body.byteOffset, body.byteLength) : body instanceof ArrayBuffer ? new Uint8Array(body) : new g.TextEncoder().encode(String(body));
      this.body = new g.ReadableStream({ start(c) { c.enqueue(bytes); c.close(); } });
    }
  }
  get ok() { return this.status >= 200 && this.status < 300; }
  async arrayBuffer() {
    const bytes = await this._bytes();
    return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
  }
  async _bytes() {
    if (this.bodyUsed) throw new TypeError("Body has already been consumed");
    this.bodyUsed = true;
    if (!this.body) return new Uint8Array(0);
    const reader = this.body.getReader();
    const parts = [];
    let len = 0;
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      const u = value instanceof Uint8Array ? value : new Uint8Array(value);
      parts.push(u); len += u.length;
    }
    reader.releaseLock();
    const out = new Uint8Array(len);
    let o = 0; for (const p of parts) { out.set(p, o); o += p.length; }
    return out;
  }
  async text() { return new g.TextDecoder().decode(await this._bytes()); }
  async json() { return JSON.parse(await this.text()); }
  async blob() { const b = new g.Blob([await this._bytes()], { type: this.headers.get("content-type") || "" }); return b; }
  clone() { throw new TypeError("Response.clone is not supported in this runtime"); }
  static json(data, init = {}) { const h = new g.Headers(init.headers); if (!h.has("content-type")) h.set("content-type", "application/json"); return new Response(JSON.stringify(data), { ...init, headers: h }); }
  static error() { return new Response(null, { status: 0 }); }
});

export {};
