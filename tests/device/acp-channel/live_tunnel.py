#!/usr/bin/env python3
"""真实 MiniMax 的电脑端透传代理（只用于设备测试，不进 zip）。

用途：手机没有互联网时（WLAN 未连、移动数据在漫游下被关等），让设备测试仍能用**真实模型**：
  手机上的自定义模型源指向 http://127.0.0.1:18787（回环明文，App 已允许；就是 fake_model 用的那个源，key 是假的、模型名是 fake-model），
  `adb reverse tcp:18787 tcp:<本代理端口>` 把它接到这里，代理再把请求转发到 MiniMax：
    - 请求头 `x-api-key` 换成真实 key（来自环境变量 MINIMAX_API_KEY）——**真实 key 不会进手机**；
    - 请求体里的 `model` 换成 MiniMax 的模型 id（默认 MiniMax-M2.7）；
    - 其余（工具、消息、流式 SSE）原样透传。
注意这条路径验证的是“真实模型 + 真实运行时 + 真实工具”，传输换成了电脑上的隧道；没有走 App 自己的 HTTPS 出口（HostFetch 对真实端点的 TLS 路径
由 tests/device/acp-channel 的 live-minimax 用例在有网的手机上覆盖）。
日志只记方法、路径、状态码和耗时，不记头和体。
"""
import http.client
import json
import os
import ssl
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM_HOST = os.environ.get("TUNNEL_UPSTREAM_HOST", "api.minimaxi.com")
UPSTREAM_PREFIX = os.environ.get("TUNNEL_UPSTREAM_PREFIX", "/anthropic")
MODEL = os.environ.get("TUNNEL_MODEL", "MiniMax-M2.7")


class MiniMaxTunnel:
    def __init__(self, key):
        if not key:
            raise ValueError("MINIMAX_API_KEY is not set")
        self.key = key
        self.lock = threading.Lock()
        self.log = []
        outer = self

        class H(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *a):
                pass

            def do_POST(self):
                t0 = time.time()
                n = int(self.headers.get("content-length") or 0)
                body = self.rfile.read(n) if n else b""
                try:
                    obj = json.loads(body.decode("utf-8"))
                    obj["model"] = MODEL
                    body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
                except ValueError:
                    pass
                headers = {"content-type": self.headers.get("content-type", "application/json"), "x-api-key": outer.key,
                           "anthropic-version": self.headers.get("anthropic-version", "2023-06-01"), "accept": self.headers.get("accept", "*/*")}
                status = 502
                try:
                    c = http.client.HTTPSConnection(UPSTREAM_HOST, 443, timeout=120, context=ssl.create_default_context())
                    c.request("POST", UPSTREAM_PREFIX + self.path, body=body, headers=headers)
                    r = c.getresponse()
                    status = r.status
                    self.send_response(r.status)
                    self.send_header("content-type", r.getheader("content-type", "application/json"))
                    self.send_header("transfer-encoding", "chunked")
                    self.end_headers()
                    while True:
                        chunk = r.read1(8192) if hasattr(r, "read1") else r.read(8192)
                        if not chunk:
                            break
                        self.wfile.write(b"%x\r\n" % len(chunk) + chunk + b"\r\n")
                        self.wfile.flush()
                    self.wfile.write(b"0\r\n\r\n")
                    self.wfile.flush()
                    c.close()
                except Exception as e:  # noqa: BLE001
                    try:
                        msg = json.dumps({"type": "error", "error": {"type": "tunnel_error", "message": type(e).__name__}}).encode()
                        self.send_response(502)
                        self.send_header("content-type", "application/json")
                        self.send_header("content-length", str(len(msg)))
                        self.end_headers()
                        self.wfile.write(msg)
                    except Exception:  # noqa: BLE001
                        pass
                with outer.lock:
                    outer.log.append({"method": "POST", "path": self.path, "status": status, "ms": round((time.time() - t0) * 1000)})

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.server.daemon_threads = True
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self):
        self.thread.start()
        return self

    def stop(self):
        self.server.shutdown()
        self.server.server_close()

    def requests(self):
        with self.lock:
            return list(self.log)


if __name__ == "__main__":
    t = MiniMaxTunnel(os.environ.get("MINIMAX_API_KEY", "")).start()
    print("tunnel on 127.0.0.1:%d -> https://%s%s (model %s)" % (t.port, UPSTREAM_HOST, UPSTREAM_PREFIX, MODEL), flush=True)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        t.stop()
        sys.exit(0)
