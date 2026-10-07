"""Host-side fake Jev (System One) service for the app suite (D5.1).

The AgentOS `:agent` asks Jev to pick one of the caller's sessions for `session/new` with
`_meta."org.agentos".autoSelect` (core/contracts/session-selection.md). The device test points the Jev endpoint at
http://127.0.0.1:<DEVICE_PORT>/v1/systemone on the device, forwarded to this server with `adb reverse`
(loopback cleartext is allowed in the debug and releaseTest builds, F9).

Wire format (same as core/runtime HttpJevProvider): POST, `Authorization: Bearer <key>`, body
  {"state": <query>, "model": ..., "questions": {"session": {"type": "choice", "instructions": ..., "criteria": {<choiceId>: <brief>}}}}
answer
  {"answers": {"session": {"choice": <choiceId>}}}

What a request does is decided by a tag in the query (`state`), so the device scenario scripts it without a control channel:
  [jev:first]    answer the first existing session (default)   [jev:new]      answer new_session
  [jev:invalid]  answer an id that is not in the request        [jev:garbage]  200 with a body that is not JSON
  [jev:empty]    200 JSON without answers.session.choice        [jev:401]      401 {"error": "Invalid API key."}
  [jev:503]      503                                            [jev:hang]     answer after 6 s (longer than the 3 s HTTP timeout)

Keys: only the keys given to the server are accepted; others get 401. Every request is recorded with the kind of key it
presented ("test", "other", "none") and the choice ids (not the briefs); keys are never recorded.
GET /_log returns the records, POST /_reset clears them.
"""
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DEVICE_PORT = 18788
HANG_SECONDS = 6.0


class FakeJev:
    def __init__(self, keys):
        """keys: {key: kind}"""
        self.keys = dict(keys)
        self.lock = threading.Lock()
        self.log = []
        self.seq = 0
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), self._handler())
        self.server.daemon_threads = True
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, name="fake-jev", daemon=True)

    def start(self):
        self.thread.start()
        return self

    def stop(self):
        self.server.shutdown()
        self.server.server_close()

    def records(self):
        with self.lock:
            return [dict(r) for r in self.log]

    def _handler(self):
        fake = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, fmt, *args):
                pass

            def _send(self, status, body, ctype="application/json"):
                data = body if isinstance(body, bytes) else json.dumps(body).encode()
                try:
                    self.send_response(status)
                    self.send_header("content-type", ctype)
                    self.send_header("content-length", str(len(data)))
                    self.send_header("connection", "close")
                    self.end_headers()
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError, OSError):
                    pass
                self.close_connection = True

            def do_GET(self):
                if self.path.startswith("/_log"):
                    self._send(200, {"requests": fake.records()})
                else:
                    self._send(404, {"error": "not found"})

            def do_POST(self):
                length = int(self.headers.get("content-length") or 0)
                raw = self.rfile.read(length) if length else b""
                if self.path.startswith("/_reset"):
                    with fake.lock:
                        fake.log.clear()
                    self._send(200, {"ok": True})
                    return
                if not self.path.startswith("/v1/systemone"):
                    self._send(404, {"error": "unknown path"})
                    return
                auth = self.headers.get("authorization") or ""
                presented = auth[7:] if auth.lower().startswith("bearer ") else ""
                kind = fake.keys.get(presented, "none" if not presented else "other")
                try:
                    body = json.loads(raw.decode("utf-8") or "{}")
                except ValueError:
                    body = {}
                state = str(body.get("state") or "")
                criteria = (((body.get("questions") or {}).get("session") or {}).get("criteria")) or {}
                ids = list(criteria.keys())
                existing = [i for i in ids if i != "new_session"]
                mode = next((m for m in ("first", "new", "invalid", "garbage", "empty", "401", "503", "hang")
                             if f"[jev:{m}]" in state), "first")
                with fake.lock:
                    fake.seq += 1
                    rec = {"id": fake.seq, "t": time.time(), "key": kind, "mode": mode, "queryChars": len(state),
                           "choices": ids, "model": body.get("model"), "status": 0, "chosen": None}
                    fake.log.append(rec)
                if kind != "test":
                    rec["status"] = 401
                    self._send(401, {"error": "Invalid API key."})
                    return
                if mode == "401":
                    rec["status"] = 401
                    self._send(401, {"error": "Invalid API key."})
                    return
                if mode == "503":
                    rec["status"] = 503
                    self._send(503, {"error": "unavailable"})
                    return
                if mode == "hang":
                    time.sleep(HANG_SECONDS)
                if mode == "garbage":
                    rec["status"] = 200
                    self._send(200, b"<html>not json</html>", "text/html")
                    return
                if mode == "empty":
                    rec["status"] = 200
                    self._send(200, {"answers": {"session": {}}})
                    return
                choice = "new_session" if mode == "new" else ("ses_not_in_the_request" if mode == "invalid" else (existing[0] if existing else "new_session"))
                rec["status"] = 200
                rec["chosen"] = choice
                self._send(200, {"answers": {"session": {"choice": choice}}})

        return Handler


def main():
    """Standalone: start the service for one device (manual runs)."""
    import argparse
    import os
    import shutil
    import subprocess
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--key-env", default="AGENTOS_FAKE_JEV_KEY", help="environment variable holding the key the service accepts")
    a = ap.parse_args()
    key = os.environ.get(a.key_env) or "agtest-fake-jev-key"
    fj = FakeJev({key: "test"}).start()
    adb = shutil.which("adb") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
    if a.serial:
        subprocess.run([adb, "-s", a.serial, "reverse", f"tcp:{DEVICE_PORT}", f"tcp:{fj.port}"], check=True)
    print(f"fake jev on 127.0.0.1:{fj.port}" + (f", device {a.serial} 127.0.0.1:{DEVICE_PORT}" if a.serial else ""), flush=True)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass
    finally:
        if a.serial:
            subprocess.run([adb, "-s", a.serial, "reverse", "--remove", f"tcp:{DEVICE_PORT}"])
        fj.stop()


if __name__ == "__main__":
    main()
