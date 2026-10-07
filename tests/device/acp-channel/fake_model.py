"""Host-side fake model endpoint for the app suite (W6 / C4).

The AgentOS `:agent` runs the real Pi Agent core; its model requests go through HostFetch to a model
endpoint. The device test model is a custom Anthropic Messages endpoint at http://127.0.0.1:<DEVICE_PORT>
on the device, forwarded to this server with `adb reverse` (loopback cleartext is allowed in the debug
and releaseTest builds).

Wire format: Anthropic Messages SSE, the same events as core/runtime testFixtures FakeModelServer
(message_start, content_block_start / delta / stop, message_delta, message_stop).

What a request streams is decided from the latest real user message (the prompt text of the turn):
  - a JSON object is a script, the same scripts the channel cases send (tests/device agentCommand):
      chunks (20), chunkChars (0 = "chunk i "), intervalMs (20), burst (1), cjk, bigChunkChars,
      tool: NAME -> round 0 streams the text and ends with a tool_use NAME{} (stop_reason tool_use);
      toolInput: {...} -> the tool_use input (default {}; C7b: e2e calls of plugin tools with arguments);
                    the next round (after the tool result) streams "after-tool:<result text>".
      toolCalls: [{"name": FINAL_TOOL_NAME | "mcp": [plugin, server, tool], "arguments": {...}}, ...], final: TEXT
                 -> one tool_use per round in order (arguments may reference earlier results: "$result[0].id"), then the
                    final text; see scripted_tools.py for the placeholder rules. Every request record carries "plan"
                    (what this round streamed) and "toolResults" (the results that came back this turn).
  - anything else echoes 20 lines "echo[i]: <first 64 chars>\n", 20 ms apart.

Keys: only the keys given to the server are accepted (x-api-key); others get 401. Every request is
recorded with the kind of key it presented ("test", "byok", "none", "placeholder", "other"); keys are
never recorded. GET /_log returns the records, POST /_reset clears them.
"""
import json
import os
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scripted_tools  # noqa: E402

DEVICE_PORT = 18787


class FakeModel:
    def __init__(self, keys):
        """keys: {key: kind}"""
        self.keys = dict(keys)
        self.lock = threading.Lock()
        self.log = []
        self.seq = 0
        handler = self._handler()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.server.daemon_threads = True
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, name="fake-model", daemon=True)

    def start(self):
        self.thread.start()
        return self

    def stop(self):
        self.server.shutdown()
        self.server.server_close()

    def add_key(self, key, kind):
        with self.lock:
            self.keys[key] = kind

    def records(self):
        with self.lock:
            return [dict(r) for r in self.log]

    # ------------------------------------------------------------------ handler

    def _handler(self):
        fake = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, fmt, *args):  # quiet
                pass

            def _json(self, status, obj):
                data = json.dumps(obj).encode()
                self.send_response(status)
                self.send_header("content-type", "application/json")
                self.send_header("content-length", str(len(data)))
                self.send_header("connection", "close")
                self.end_headers()
                self.wfile.write(data)
                self.close_connection = True

            def do_GET(self):
                if self.path.startswith("/_log"):
                    self._json(200, {"requests": fake.records()})
                else:
                    self._json(404, {"error": "not found"})

            def do_POST(self):
                length = int(self.headers.get("content-length") or 0)
                raw = self.rfile.read(length) if length else b""
                if self.path.startswith("/_reset"):
                    with fake.lock:
                        fake.log.clear()
                    self._json(200, {"ok": True})
                    return
                if "/v1/messages" not in self.path:
                    self._json(404, {"type": "error", "error": {"type": "not_found_error", "message": "unknown path"}})
                    return
                presented = self.headers.get("x-api-key") or ""
                if presented in fake.keys:
                    kind = fake.keys[presented]
                elif not presented:
                    kind = "none"
                elif "agentos-host-injected" in presented:
                    kind = "placeholder"
                else:
                    kind = "other"
                try:
                    body = json.loads(raw.decode("utf-8") or "{}")
                except ValueError:
                    body = {}
                user, since, messages = conversation(body)
                results = turn_tool_results(body)
                with fake.lock:
                    fake.seq += 1
                    rec = {"id": fake.seq, "t": time.time(), "key": kind, "model": body.get("model"),
                           "userText": user[:200], "userChars": len(user), "round": since,
                           "messages": messages, "toolResults": [{"isError": r["isError"], "text": r["text"][:4000]} for r in results],
                           "plan": None, "status": 0, "sent": 0, "completed": False, "disconnected": False}
                    fake.log.append(rec)
                if kind not in ("test", "byok"):
                    rec["status"] = 401
                    self._json(401, {"type": "error", "error": {"type": "authentication_error", "message": "invalid x-api-key"}})
                    return
                rec["status"] = 200
                try:
                    self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                except OSError:
                    pass
                try:
                    self._stream(rec, body, user, since, messages, results)
                    rec["completed"] = True
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, socket.timeout, OSError):
                    rec["disconnected"] = True
                self.close_connection = True

            # -------------------------------------------------------------- SSE

            def _chunk(self, text):
                data = text.encode("utf-8")
                self.wfile.write(b"%X\r\n%s\r\n" % (len(data), data))
                self.wfile.flush()

            def _ev(self, typ, data):
                d = {"type": typ}
                d.update(data)
                self._chunk("event: %s\ndata: %s\n\n" % (typ, json.dumps(d, ensure_ascii=False)))

            def _stream(self, rec, body, user, since, messages, results):
                self.send_response(200)
                self.send_header("content-type", "text/event-stream")
                self.send_header("cache-control", "no-cache")
                self.send_header("connection", "close")
                self.send_header("transfer-encoding", "chunked")
                self.end_headers()
                self._ev("message_start", {"message": {
                    "id": "msg_%d" % rec["id"], "type": "message", "role": "assistant", "model": body.get("model", "fake"),
                    "content": [], "stop_reason": None, "stop_sequence": None,
                    "usage": {"input_tokens": 10 + len(body.get("messages") or []), "output_tokens": 1}}})
                script = parse_script(user)
                index = 0
                out = 0
                stop = "end_turn"

                def text_block(parts_iter):
                    nonlocal index, out
                    i = index
                    index += 1
                    self._ev("content_block_start", {"index": i, "content_block": {"type": "text", "text": ""}})
                    for part, pause in parts_iter:
                        self._ev("content_block_delta", {"index": i, "delta": {"type": "text_delta", "text": part}})
                        rec["sent"] += 1
                        out += 1
                        if pause > 0:
                            time.sleep(pause)
                    self._ev("content_block_stop", {"index": i})

                if scripted_tools.is_plan_script(script):
                    plan = scripted_tools.plan_round(script, since, results)
                    rec["plan"] = dict(plan, round=since)
                    if plan["kind"] == "tool":
                        i = index
                        index += 1
                        self._ev("content_block_start", {"index": i, "content_block": {
                            "type": "tool_use", "id": "toolu_%d" % rec["id"], "name": plan["name"], "input": {}}})
                        raw = json.dumps(plan["arguments"], ensure_ascii=False)
                        half = max(1, len(raw) // 2)  # two deltas, like a real stream: the client has to join partial JSON
                        for part in (raw[:half], raw[half:]):
                            if part:
                                self._ev("content_block_delta", {"index": i, "delta": {"type": "input_json_delta", "partial_json": part}})
                        self._ev("content_block_stop", {"index": i})
                        stop = "tool_use"
                    else:
                        text_block([(plan["text"], 0)])
                elif script is None:
                    text_block(("echo[%d]: %s\n" % (i, user[:64]), 0.02) for i in range(20))
                elif script.get("tool") and since >= 1:
                    result = last_tool_result(body)
                    text_block([("after-tool:" + result[:120], 0)])
                else:
                    text_block(script_parts(script))
                    if script.get("tool"):
                        i = index
                        index += 1
                        self._ev("content_block_start", {"index": i, "content_block": {
                            "type": "tool_use", "id": "toolu_%d" % rec["id"], "name": str(script["tool"]), "input": {}}})
                        tool_input = script.get("toolInput") if isinstance(script.get("toolInput"), dict) else {}
                        self._ev("content_block_delta", {"index": i, "delta": {"type": "input_json_delta",
                                                                                "partial_json": json.dumps(tool_input)}})
                        self._ev("content_block_stop", {"index": i})
                        stop = "tool_use"
                self._ev("message_delta", {"delta": {"stop_reason": stop, "stop_sequence": None}, "usage": {"output_tokens": max(1, out)}})
                self._ev("message_stop", {})
                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()

        return Handler


def parse_script(user):
    s = user.strip()
    if not s.startswith("{"):
        return None
    try:
        o = json.loads(s)
    except ValueError:
        return None
    return o if isinstance(o, dict) else None


def script_parts(o):
    chunks = max(0, int(o.get("chunks", 20)))
    chunk_chars = max(0, int(o.get("chunkChars", 0)))
    interval = max(0, int(o.get("intervalMs", 20))) / 1000.0
    burst = max(1, int(o.get("burst", 1)))
    cjk = bool(o.get("cjk", False))
    big = max(0, int(o.get("bigChunkChars", 0)))
    filler = (("中" if cjk else "x") * chunk_chars) if chunk_chars > 0 else None
    for i in range(chunks):
        pause = interval if (interval > 0 and i % burst == burst - 1) else 0
        yield (filler if filler is not None else "chunk %d " % i), pause
    if big > 0:
        yield "y" * big, 0


def _text_of(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text")
    return ""


def conversation(body):
    """(latest real user text, assistant messages after it, summary of all messages)."""
    msgs = body.get("messages") or []
    summary = []
    for m in msgs:
        c = m.get("content")
        kinds = [b.get("type") for b in c if isinstance(b, dict)] if isinstance(c, list) else ["text"]
        summary.append({"role": m.get("role"), "text": _text_of(c)[:300], "blocks": kinds})
    user_at = -1
    for i in range(len(msgs) - 1, -1, -1):
        m = msgs[i]
        if m.get("role") != "user":
            continue
        c = m.get("content")
        if isinstance(c, list) and c and all(isinstance(b, dict) and b.get("type") == "tool_result" for b in c):
            continue
        user_at = i
        break
    if user_at < 0:
        return "", 0, summary
    since = sum(1 for m in msgs[user_at + 1:] if m.get("role") == "assistant")
    return _text_of(msgs[user_at].get("content")), since, summary


def turn_tool_results(body):
    """The tool results of the current turn (after the latest real user message), in order: [{"isError", "text"}]."""
    msgs = body.get("messages") or []
    user_at = -1
    for i in range(len(msgs) - 1, -1, -1):
        m = msgs[i]
        if m.get("role") != "user":
            continue
        c = m.get("content")
        if isinstance(c, list) and c and all(isinstance(b, dict) and b.get("type") == "tool_result" for b in c):
            continue
        user_at = i
        break
    out = []
    for m in msgs[user_at + 1:]:
        c = m.get("content")
        if m.get("role") != "user" or not isinstance(c, list):
            continue
        for b in c:
            if isinstance(b, dict) and b.get("type") == "tool_result":
                inner = b.get("content")
                out.append({"isError": bool(b.get("is_error")), "text": inner if isinstance(inner, str) else _text_of(inner)})
    return out


def last_tool_result(body):
    for m in reversed(body.get("messages") or []):
        c = m.get("content")
        if isinstance(c, list):
            for b in c:
                if isinstance(b, dict) and b.get("type") == "tool_result":
                    inner = b.get("content")
                    txt = _text_of(inner) if not isinstance(inner, str) else inner
                    return ("error:" if b.get("is_error") else "ok:") + txt
    return "none"


def main():
    """Standalone: start the endpoint for one device and keep it running (for tests/acp-conformance device mode and
    manual runs). The device test model (tests/device/acp-channel inapp ensureTestModel) uses the fixed test key."""
    import argparse
    import os
    import shutil
    import subprocess
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"), required=False)
    a = ap.parse_args()
    fm = FakeModel({"agtest-fake-model-key": "test"}).start()
    adb = shutil.which("adb") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
    if a.serial:
        subprocess.run([adb, "-s", a.serial, "reverse", f"tcp:{DEVICE_PORT}", f"tcp:{fm.port}"], check=True)
    print(f"fake model on 127.0.0.1:{fm.port}" + (f", device {a.serial} 127.0.0.1:{DEVICE_PORT}" if a.serial else ""), flush=True)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass
    finally:
        if a.serial:
            subprocess.run([adb, "-s", a.serial, "reverse", "--remove", f"tcp:{DEVICE_PORT}"])
        fm.stop()


if __name__ == "__main__":
    main()
