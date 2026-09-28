/*
 * The only way JS talks to the outside world.
 *
 * The host (Kotlin QuickJsEngine on Android, the JVM desktop runner, or the Node
 * `vm` test host) installs these globals before the bundle is evaluated:
 *
 *   sync  __host_emit(json: string): void                 events / replies / logs
 *   async __host_next(): string | null                    next command for the pump (null = shut down)
 *   async __host_timer(id: number, ms: number): "fired" | "cancelled"
 *   sync  __host_timer_cancel(id: number): void
 *   async __host_fetch(reqId: number, requestJson: string): string   response head as JSON
 *   async __host_fetch_read(reqId: number): Uint8Array | null         next body chunk, null at EOF
 *   sync  __host_fetch_abort(reqId: number): void
 *   async __host_call(method: string, payloadJson: string): string    tools / hooks
 *
 * JS never holds a model key: pi-ai is called with PLACEHOLDER_API_KEY and the
 * host swaps it for the real key while the request passes through __host_fetch.
 */

export const PLACEHOLDER_API_KEY = "agentos-host-injected-key";

const g = globalThis;

export function emit(message) {
  g.__host_emit(JSON.stringify(message));
}

export async function nextCommand() {
  const raw = await g.__host_next();
  return raw == null ? null : JSON.parse(raw);
}

export async function call(method, payload) {
  const raw = await g.__host_call(method, JSON.stringify(payload ?? {}));
  return raw == null || raw === "" ? undefined : JSON.parse(raw);
}

let nextRequestId = 1;
const inflight = new Set();
export const inflightFetchCount = () => inflight.size;

function abortError(signal) {
  const reason = signal?.reason;
  if (reason instanceof Error) return reason;
  return new g.DOMException("This operation was aborted", "AbortError");
}

function toUint8(chunk) {
  if (chunk instanceof Uint8Array) return chunk;
  // Chunks from another realm (Node vm host) or plain arrays: copy into this realm.
  return new Uint8Array(chunk);
}

function bodyToString(body) {
  if (body == null) return null;
  if (typeof body === "string") return body;
  if (body instanceof g.URLSearchParams) return body.toString();
  if (ArrayBuffer.isView(body)) return new g.TextDecoder().decode(body);
  if (body instanceof ArrayBuffer) return new g.TextDecoder().decode(new Uint8Array(body));
  throw new TypeError("host fetch: only string / bytes request bodies are supported");
}

/**
 * Streaming fetch backed by the host (OkHttp on Android). Implements the subset
 * of the Fetch API that @anthropic-ai/sdk and openai use.
 */
export async function hostFetch(input, init = {}) {
  const url = typeof input === "string" ? input : input instanceof g.URL ? input.href : String(input.url ?? input);
  const method = String(init.method ?? input?.method ?? "GET").toUpperCase();
  const headers = new g.Headers(init.headers ?? input?.headers);
  const body = bodyToString(init.body ?? null);
  const signal = init.signal;
  if (signal?.aborted) throw abortError(signal);

  const reqId = nextRequestId++;
  inflight.add(reqId);
  let finished = false;
  let streamController = null;
  const finish = () => {
    if (finished) return;
    finished = true;
    inflight.delete(reqId);
    signal?.removeEventListener("abort", onAbort);
  };
  const onAbort = () => {
    try { g.__host_fetch_abort(reqId); } catch {}
    // Fail the body right away instead of waiting for the host read to come back,
    // so an aborted turn ends even if the host side is slow or gone.
    if (streamController) { try { streamController.error(abortError(signal)); } catch {} }
    finish();
  };
  signal?.addEventListener("abort", onAbort, { once: true });

  let head;
  try {
    const raw = await g.__host_fetch(reqId, JSON.stringify({ url, method, headers: [...headers.entries()], body }));
    head = JSON.parse(raw);
  } catch (error) {
    finish();
    if (signal?.aborted) throw abortError(signal);
    const err = new TypeError(`fetch failed: ${error?.message ?? error}`);
    err.cause = error;
    throw err;
  }

  const stream = new g.ReadableStream({
    start(controller) { streamController = controller; },
    async pull(controller) {
      try {
        const chunk = await g.__host_fetch_read(reqId);
        if (chunk == null) { finish(); controller.close(); }
        else controller.enqueue(toUint8(chunk));
      } catch (error) {
        finish();
        controller.error(signal?.aborted ? abortError(signal) : error);
      }
    },
    cancel() {
      if (!finished) { try { g.__host_fetch_abort(reqId); } catch {} }
      finish();
    },
  });
  return new g.Response(stream, {
    status: head.status,
    statusText: head.statusText ?? "",
    headers: head.headers,
    url: head.url ?? url,
  });
}
