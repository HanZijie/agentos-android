#!/usr/bin/env python3
"""S3 第二部分的主机端驱动。

用法（在 spikes/S3 下）：
    ./gradlew :server:assembleDebug :client:assembleDebug        # 或 assembleRelease
    python3 tools/run_s3.py --serial <ANDROID_SERIAL> --build debug
    python3 tools/run_s3.py --serial <...> --build release --only handshake,stream

结果写到 results/raw/<serial>-api<sdk>-<build>-<时间>.json，并打印每个场景的摘要。
只依赖 Python 3.9 标准库和 adb；电脑端接入用例另外需要 node（desktop-client/）。
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SERVER_PKG = "org.agentos.spike.s3.agent"
CLIENT_PKG = "org.agentos.spike.s3.client"
ACTIVITY = CLIENT_PKG + "/.ScenarioActivity"

# 名称、场景、参数、超时（秒）
WINDOWS = [(8, 16384), (32, 65536), (64, 65536), (128, 131072)]


def matrix():
    m = [
        ("handshake", "handshake", {}, 60),
        ("stream-realtime", "stream", {"chunks": 500, "chunkChars": 16, "intervalMs": 10}, 120),
        ("stream-peak", "stream", {"chunks": 5000, "chunkChars": 32, "intervalMs": 0}, 120),
        ("stream-peak-bp", "stream", {"chunks": 20000, "chunkChars": 32, "intervalMs": 0, "bp": True}, 180),
        ("stream-bigchunks-bp", "stream", {"chunks": 500, "chunkChars": 16000, "intervalMs": 0, "bp": True, "bpChars": 65536}, 180),
        ("stream-cjk-bp", "stream", {"chunks": 5000, "chunkChars": 64, "intervalMs": 0, "bp": True, "cjk": True}, 180),
    ]
    for wm, wc in WINDOWS:
        m.append((f"stream-window-{wm}x{wc}", "stream",
                  {"chunks": 20000, "chunkChars": 64, "intervalMs": 0, "bp": True,
                   "cfg": {"windowMessages": wm, "windowChars": wc}}, 180))
    m += [
        ("stream-noflow", "stream", {"chunks": 5000, "chunkChars": 64, "intervalMs": 0,
                                     "cfg": {"flowControl": False, "enforceInboundLimits": False}}, 180),
        ("cancel-realtime", "cancel", {"intervalMs": 5, "cancelAfterChunks": 100}, 120),
        ("cancel-peak", "cancel", {"intervalMs": 0, "chunkChars": 32, "cancelAfterChunks": 200}, 180),
        ("cancel-peak-bp", "cancel", {"intervalMs": 0, "chunkChars": 32, "bp": True, "cancelAfterChunks": 200}, 120),
        ("reconnect", "reconnect", {"iterations": 20}, 300),
        ("server-kill", "server-kill", {"killAfterChunks": 50}, 120),
        ("client-kill", "client-kill", {"killAfterChunks": 50}, 120),
        ("oversize", "oversize", {}, 120),
        ("window-violation", "window-violation", {"units": 30000, "n": 200}, 120),
        ("bench-single", "bench-single", {}, 300),
        ("bench-burst", "bench-burst", {"n": 5000, "handlerDelayMicros": 2000}, 600),
        ("bench-throughput", "bench-throughput", {}, 300),
        ("desktop", "desktop", {}, 120),
    ]
    return m


def sweep():
    """速率扫描与窗口比较：找出不产生延迟堆积的速率上限，给窗口参数定值。"""
    m = []
    for wm, wc in [(64, 65536), (16, 32768)]:
        for rate in (200, 500, 1000, 2000, 4000):
            m.append((f"rate-{rate}-w{wm}x{wc}", "stream",
                      {"chunks": rate * 4, "chunkChars": 16, "intervalMs": 10, "burst": rate // 100, "bp": True,
                       "cfg": {"windowMessages": wm, "windowChars": wc}}, 120))
    for rep in (1, 2):
        for wm, wc in [(8, 16384), (16, 32768), (32, 32768), (64, 65536)]:
            m.append((f"peak-w{wm}x{wc}-r{rep}", "stream",
                      {"chunks": 20000, "chunkChars": 64, "intervalMs": 0, "bp": True,
                       "cfg": {"windowMessages": wm, "windowChars": wc}}, 180))
    for wm, wc in [(16, 32768), (64, 65536)]:
        m.append((f"big-w{wm}x{wc}", "stream",
                  {"chunks": 500, "chunkChars": 16000, "intervalMs": 0, "bp": True, "bpChars": 65536,
                   "cfg": {"windowMessages": wm, "windowChars": wc}}, 180))
    return m


class Adb:
    def __init__(self, serial):
        self.adb = shutil.which("adb") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
        self.serial = serial

    def run(self, *args, check=True, timeout=120, capture=True):
        cmd = [self.adb, "-s", self.serial] + list(args)
        p = subprocess.run(cmd, capture_output=capture, text=True, timeout=timeout)
        if check and p.returncode != 0:
            raise RuntimeError(f"{' '.join(cmd)} -> {p.returncode}: {p.stderr.strip()} {p.stdout.strip()}")
        return p.stdout

    def sh(self, cmd, **kw):
        return self.run("shell", cmd, **kw)

    def prop(self, name):
        return self.sh(f"getprop {name}").strip()


def collect_result(adb, run_id, timeout, want_phase=None):
    """轮询 logcat 里的 S3RESULT 分片，拼回完整 JSON。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = adb.run("logcat", "-d", "-v", "raw", "-s", "S3RESULT:I", check=False, timeout=30)
        parts = {}
        total = None
        for line in out.splitlines():
            if not line.startswith(run_id + " "):
                continue
            try:
                _, idx, data = line.split(" ", 2)
                i, n = idx.split("/")
                i, n = int(i), int(n)
            except ValueError:
                continue
            if total is not None and n != total:
                parts = {}
            total = n
            parts[i] = data
        if total and len(parts) == total:
            obj = json.loads("".join(parts[i] for i in range(1, total + 1)))
            if want_phase is None or obj.get("phase") == want_phase:
                return obj
        time.sleep(1)
    return None


def start_scenario(adb, scenario, args, run_id):
    payload = json.dumps(args, separators=(",", ":"))
    adb.sh(f"am start -W -n {ACTIVITY} --es scenario {scenario} --es run {run_id} --es args '{payload}'", timeout=60)


BINDER_KEYS = ("oneway spam", "too many oneway", "transactiontoolarge", "binder transaction failed",
               "failed_transaction", "binder: ", "fatal exception", "anr in")


def binder_warnings(adb):
    out = adb.run("logcat", "-d", check=False, timeout=60)
    return [l for l in out.splitlines() if any(k in l.lower() for k in BINDER_KEYS)][-30:]


def run_one(adb, name, scenario, args, timeout):
    run_id = f"{name}-{uuid.uuid4().hex[:6]}"
    adb.run("logcat", "-c", check=False)
    start_scenario(adb, scenario, args, run_id)
    r = collect_result(adb, run_id, timeout)
    if r is not None:
        r["logcatWarnings"] = binder_warnings(adb)
    return r


def run_client_kill(adb, name, args, timeout):
    run_id = f"{name}-{uuid.uuid4().hex[:6]}"
    adb.run("logcat", "-c", check=False)
    start_scenario(adb, "client-kill", args, run_id)
    dying = collect_result(adb, run_id, timeout, want_phase="dying")
    if dying is None:
        return {"ok": False, "error": "client did not reach dying phase"}
    time.sleep(3)
    stats = run_one(adb, name + "-check", "server-stats", {}, 60)
    if stats is None:
        return {"ok": False, "error": "server-stats timed out", "dying": dying}
    server = stats["server"]
    closes = [c for c in server.get("recentCloses", []) if c.get("closedAtNs", 0) >= dying["dyingAtNs"]]
    peer_died = [c for c in closes if c.get("cause", "").startswith("peer_died")]
    detect_ms = (peer_died[0]["closedAtNs"] - dying["dyingAtNs"]) / 1e6 if peer_died else None
    ok = bool(peer_died) and server.get("promptsActive") == 0 and server.get("liveChannels") == 0 \
        and server.get("connectionsOpen") == 0
    return {
        "ok": ok, "dying": dying, "detectMs": detect_ms, "closes": closes,
        "server": {k: server.get(k) for k in ("pid", "connectionsOpen", "liveChannels", "hostJobChildren",
                                              "promptsActive", "promptOutcomes", "threads", "pssKb")},
        "clientPidBefore": dying.get("pid"), "clientPidAfter": stats.get("client", {}).get("pid"),
    }


def run_desktop(adb, timeout):
    node = shutil.which("node")
    if not node:
        return {"ok": False, "error": "node not found"}
    client_dir = os.path.join(ROOT, "desktop-client")
    if not os.path.isdir(os.path.join(client_dir, "node_modules")):
        env = {k: v for k, v in os.environ.items() if k.lower() not in ("http_proxy", "https_proxy")}
        subprocess.run(["npm", "ci" if os.path.exists(os.path.join(client_dir, "package-lock.json")) else "install",
                        "--silent"], cwd=client_dir, check=True, env=env, timeout=300)
    adb.sh(f"am start-foreground-service -n {SERVER_PKG}/.GatewayService", timeout=30)
    time.sleep(2)
    port = 8765
    adb.run("forward", f"tcp:{port}", "localabstract:agentos-acp")
    try:
        p = subprocess.run([node, "client.mjs"], cwd=client_dir, capture_output=True, text=True,
                           timeout=timeout, env=dict(os.environ, PORT=str(port)))
        lines = [l for l in p.stdout.splitlines() if l.startswith("{")]
        res = json.loads(lines[-1]) if lines else {"ok": False, "error": p.stderr[-2000:]}
    finally:
        adb.run("forward", "--remove", f"tcp:{port}", check=False)
    socket_line = adb.sh("cat /proc/net/unix | grep agentos-acp", check=False).strip()
    res["abstractSocket"] = socket_line
    return res


def summarize(name, r):
    if r is None:
        return f"{name:28s} TIMEOUT"
    ok = r.get("ok")
    s = f"{name:28s} ok={ok}"
    pr = r.get("prompt") or r.get("cancelled")
    if isinstance(pr, dict) and "latency" in pr:
        lat = pr["latency"]
        s += (f" chunks={pr.get('chunks')} {pr.get('chunksPerSec', 0):.0f}/s"
              f" lat p50={lat.get('p50ms', 0):.2f} p99={lat.get('p99ms', 0):.2f} max={lat.get('maxms', 0):.1f}ms"
              f" first10%={lat.get('first10pctMeanMs', 0):.1f} last10%={lat.get('last10pctMeanMs', 0):.1f}")
    if "mainThread" in r:
        s += f" main.maxLate={r['mainThread'].get('maxLateMs', 0):.1f}ms"
    for k in ("cancelToStopMs", "detectMs", "serviceRestartMs", "recoveredMs", "closeCause"):
        if k in r:
            s += f" {k}={r[k]}"
    if "error" in r and not ok:
        s += f" error={str(r['error'])[:200]}"
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--build", choices=["debug", "release"], default="debug")
    ap.add_argument("--only", help="逗号分隔的用例名（matrix 里的第一列）")
    ap.add_argument("--suite", choices=["full", "sweep"], default="full")
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--label", default="", help="写进结果文件名的附加标签，例如 emulator")
    a = ap.parse_args()
    if not a.serial:
        sys.exit("need --serial or ANDROID_SERIAL")
    adb = Adb(a.serial)
    if not a.no_install:
        for mod in ("server", "client"):
            apk = os.path.join(ROOT, mod, "build", "outputs", "apk", a.build, f"{mod}-{a.build}.apk")
            if not os.path.exists(apk):
                sys.exit(f"missing {apk}; build it first")
            adb.run("install", "-r", "-t", apk, timeout=180)
    adb.sh(f"am force-stop {CLIENT_PKG}", check=False)
    adb.sh(f"am force-stop {SERVER_PKG}", check=False)
    adb.sh("input keyevent KEYCODE_WAKEUP", check=False)
    adb.sh("wm dismiss-keyguard", check=False)

    device = {
        "serial": a.serial,
        "model": adb.prop("ro.product.model"),
        "sdk": adb.prop("ro.build.version.sdk"),
        "release": adb.prop("ro.build.version.release"),
        "fingerprint": adb.prop("ro.build.fingerprint"),
        "buildType": adb.prop("ro.build.type"),
        "kernel": adb.sh("uname -r", check=False).strip(),
        "isEmulator": adb.prop("ro.kernel.qemu") == "1" or adb.prop("ro.boot.qemu") == "1",
    }
    print(json.dumps(device, ensure_ascii=False))
    only = set(a.only.split(",")) if a.only else None
    results = {}
    for name, scenario, args, timeout in (sweep() if a.suite == "sweep" else matrix()):
        if only and name not in only:
            continue
        t0 = time.time()
        try:
            if scenario == "client-kill":
                r = run_client_kill(adb, name, args, timeout)
            elif scenario == "desktop":
                r = run_desktop(adb, timeout)
            else:
                r = run_one(adb, name, scenario, args, timeout)
        except Exception as e:  # noqa: BLE001
            r = {"ok": False, "error": f"driver: {e}"}
        if r is not None:
            r["driverSec"] = round(time.time() - t0, 1)
        results[name] = r
        print(summarize(name, r), flush=True)
        adb.sh(f"am force-stop {CLIENT_PKG}", check=False)

    out = {"device": device, "build": a.build, "time": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
           "results": results}
    os.makedirs(os.path.join(ROOT, "results", "raw"), exist_ok=True)
    tag = (f"-{a.suite}" if a.suite != "full" else "") + (f"-{a.label}" if a.label else "")
    path = os.path.join(ROOT, "results", "raw",
                        f"{a.serial.replace(':', '_')}-api{device['sdk']}-{a.build}{tag}-{time.strftime('%Y%m%d-%H%M%S')}.json")
    with open(path, "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    passed = sum(1 for r in results.values() if r and r.get("ok"))
    print(f"\n{passed}/{len(results)} ok  ->  {path}")


if __name__ == "__main__":
    main()
