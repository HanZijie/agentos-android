#!/usr/bin/env python3
"""ACP 通道的设备测试驱动（adb）。只依赖 Python 3.9 标准库和 adb。

两套用例：
  sdk  W5 回归：测试 Agent App（:agent 进程）↔ 第三方身份的测试客户端，覆盖 sdk:binder-channel 和 sdk:acp-android。
  app  W6 用例：AgentOS App 的 :agent（AcpService、AgentService、AgentControl）。宿主层是真正的 RuntimeEngine，
       Agent core 是 Pi（PiAgentCores：PiAdapter + QuickJsEngine），模型请求发到本脚本起的假模型端点（fake_model.py，
       经 adb reverse）。通道用例由注入 debug / releaseTest 包的
       in-app 执行器以 AgentOS 自己的 UID 跑；"非本 App 的 UID 被拒"由测试客户端以第三方 UID 跑。
       BYOK 用例的 key 是本脚本每次随机生成的测试 key，用例结束后在整个 logcat（-b all）和结果里搜它。

用法（仓库根目录）：
  ./gradlew :tests:device:acp-channel:agent:assembleDebug :tests:device:acp-channel:client:assembleDebug
  python3 tests/device/acp-channel/run.py --serial emulator-5572 --suite sdk --build debug
  ./gradlew :app:assembleDebug
  python3 tests/device/acp-channel/run.py --serial emulator-5572 --suite app

结果写到 tests/device/acp-channel/results/raw/（不进仓库），并逐条打印摘要；全部通过时退出码为 0。
"""
import argparse
import json
import os
import secrets
import shutil

import fake_model
import subprocess
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))

TEST_AGENT_PKG = "org.agentos.test.acp.agent"
CLIENT_PKG = "org.agentos.test.acp.client"
CLIENT_ACTIVITY = CLIENT_PKG + "/.ScenarioActivity"
APP_PKG = "org.agentos.app"
INAPP_ACTIVITY = APP_PKG + "/org.agentos.test.acp.inapp.AgentScenarioActivity"
RESULT_TAG = "ACPTEST"


def sdk_cases():
    """(名称, 执行器, 场景, 参数, 超时秒, 类型)。类型：check = 必须通过；negative = 只要求在超时内结束。"""
    c = CLIENT_ACTIVITY
    return [
        ("handshake", c, "handshake", {}, 60, "check"),
        ("stream-realtime", c, "stream", {"chunks": 500, "chunkChars": 16, "intervalMs": 10}, 120, "check"),
        ("stream-peak-bp", c, "stream", {"chunks": 20000, "chunkChars": 64, "intervalMs": 0, "bp": True}, 180, "check"),
        # 与 spikes/S3 的 stream-peak-bp 参数相同（每条 32 字符），用来对比 SDK 化前后的吞吐
        ("stream-peak32-bp", c, "stream", {"chunks": 20000, "chunkChars": 32, "intervalMs": 0, "bp": True}, 180, "check"),
        ("stream-bigchunks-bp", c, "stream",
         {"chunks": 500, "chunkChars": 16000, "intervalMs": 0, "bp": True, "bpChars": 65536}, 180, "check"),
        ("stream-cjk-bp", c, "stream", {"chunks": 5000, "chunkChars": 64, "intervalMs": 0, "bp": True, "cjk": True}, 180, "check"),
        ("stream-window-8x16384", c, "stream",
         {"chunks": 20000, "chunkChars": 64, "intervalMs": 0, "bp": True,
          "cfg": {"windowMessages": 8, "windowChars": 16384}}, 180, "check"),
        ("cancel-realtime", c, "cancel", {"intervalMs": 5, "cancelAfterChunks": 100}, 120, "check"),
        ("cancel-peak-bp", c, "cancel", {"intervalMs": 0, "chunkChars": 32, "bp": True, "cancelAfterChunks": 200}, 120, "check"),
        ("reconnect", c, "reconnect", {"iterations": 20}, 300, "check"),
        ("reconnect-noclose", c, "reconnect", {"iterations": 5, "closeFirst": False}, 120, "check"),
        ("server-kill", c, "server-kill", {"killAfterChunks": 50}, 120, "check"),
        ("client-kill", c, "client-kill", {"killAfterChunks": 50}, 120, "check"),
        ("oversize", c, "oversize", {}, 120, "check"),
        ("window-violation", c, "window-violation", {"units": 30000, "n": 200}, 120, "check"),
        ("stream-noflow", c, "stream",
         {"chunks": 5000, "chunkChars": 64, "intervalMs": 0,
          "cfg": {"flowControl": False, "enforceInboundLimits": False}}, 120, "negative"),
    ]


def app_cases():
    a, c = INAPP_ACTIVITY, CLIENT_ACTIVITY
    return [
        # W6 清单：握手、非本 App 的 UID 被拒、超长消息、客户端被杀、:agent 被杀后重新 bind
        ("handshake", a, "handshake", {}, 60, "check"),
        ("foreign-uid-rejected", c, "foreign-open", {}, 60, "check"),
        ("foreign-no-leak", a, "server-stats-clean", {}, 60, "check"),
        ("oversize", a, "oversize", {}, 120, "check"),
        # 连接断开不取消任务（F7）：用有限长的 prompt，客户端死后等任务自己结束
        ("client-kill", a, "client-kill", {"killAfterChunks": 20, "chunks": 400, "intervalMs": 5}, 120, "check"),
        ("agent-kill-rebind", a, "server-kill", {"killAfterChunks": 50}, 120, "check"),
        # 其余通道行为
        ("stream-realtime", a, "stream", {"chunks": 300, "chunkChars": 16, "intervalMs": 10}, 120, "check"),
        ("cancel-realtime", a, "cancel", {"intervalMs": 5, "cancelAfterChunks": 100}, 120, "check"),
        ("reconnect", a, "reconnect", {"iterations": 10}, 300, "check"),
        ("window-violation", a, "window-violation", {"units": 30000, "n": 200}, 120, "check"),
        # AgentService 与监督契约（S2 a、b）
        ("cold-task-foreground", a, "task-foreground", {"cold": True}, 120, "check"),
        ("warm-task-foreground", a, "task-foreground", {"cold": False}, 120, "check"),
        ("supervisor-start-idle", a, "supervisor-start", {"reason": "boot"}, 120, "check"),
        ("restart-exit-info", a, "restart-exit-info", {}, 120, "check"),
        ("control-foreign-rejected", c, "control-foreign", {}, 60, "check"),
        # C3：用户主动停止后的恢复（previousExitStoppedByUser）、Store、BYOK
        ("user-stop-recovery", a, "user-stop", {}, 180, "userstop"),
        ("store-restart", a, "store-restart", {"fresh": True}, 180, "check"),
        ("byok-roundtrip", a, "byok-roundtrip", {"apiKeyFile": KEY_FILE}, 120, "byok"),
        ("byok-restart", a, "byok-restart", {"apiKeyFile": KEY_FILE}, 120, "byok"),
        # C3.1：清除 = 立即作废（长流式进行中清除）
        ("byok-clear-inflight", a, "byok-clear-inflight", {"apiKeyFile": KEY_FILE}, 120, "byok"),
        ("byok-clear", a, "byok-clear", {"apiKeyFile": KEY_FILE}, 120, "byok"),
        # C4：Pi Agent core 端到端（假模型端点经 adb reverse；live 用真实的 MiniMax 国内平台）
        ("pi-tool-round", a, "pi-tool-round", {}, 120, "check"),
        ("pi-context", a, "pi-context", {}, 120, "check"),
        ("recovery-context", a, "recovery-context", {}, 180, "check"),
        # C7：Extension Host（:ext）的 IExtensionHost：跨进程调用、错误码原样传回
        ("ext-host", a, "ext-host", {}, 60, "check"),
        # C6：电脑端接入打开期间 :agent 留在前台、不被 cached-apps freezer 冻结（desktop_idle.py；releaseTest 不走 acp-bridge）
        ("desktop-access", a, "desktop", {"idle": 60}, 900, "desktop"),
        ("live-minimax", a, "live-minimax", {"keyFile": "test/live_key"}, 240, "live"),
    ]


# BYOK 用例的测试 key：每次运行随机生成，不是任何真实 key。不放在 adb shell 的命令行里（API 37 的 adbd 会把整条
# 命令行写进 logcat），而是经 stdin 写进 App 私有目录下的这个文件（相对 files/），执行器读完立即删除。
KEY_FILE = "test/byok_key"


def make_test_key():
    return "agtest-" + secrets.token_urlsafe(36)


KEY_DROP = "content://org.agentos.test.acp.inapp.keydrop/"


def push_key(adb, slot, key):
    """adb shell content write：key 只走 stdin，命令行里只有 URI。写进 App 私有目录 files/test/<slot>
    （inapp 的 KeyDropProvider，要求 DUMP；debug 和不可调试的 releaseTest 包都能用）。
    用 adb shell（shell v2）而不是 exec-in：exec-in 不等设备上的命令结束就返回，key 会晚于用例落盘。"""
    want = len(key.encode("utf-8"))
    got = None
    for attempt in (1, 2):
        cmd = [adb.adb, "-s", adb.serial, "shell", f"content write --uri {KEY_DROP}{slot}"]
        p = subprocess.run(cmd, input=key, capture_output=True, text=True, timeout=60)
        if p.returncode != 0 or p.stderr.strip():
            raise RuntimeError(f"push key failed: {p.returncode} {p.stderr.strip()[:200]}")
        # 写完核对长度（KeyDropProvider.query 只返回长度，不返回内容）；不对就重写一次
        got = key_file_size(adb, slot)
        if got == want:
            return attempt
    raise RuntimeError(f"push key: file size {got}, expected {want}")


def key_file_size(adb, slot):
    out = adb.sh(f"content query --uri {KEY_DROP}{slot}", check=False)
    for part in out.replace(",", " ").split():
        if part.startswith("size="):
            try:
                return int(part[5:])
            except ValueError:
                return None
    return None


def live_key():
    """真实对话用的 key：只从环境变量读（例如先 set -a; . .secrets/minimax.env）。没有就跳过 live 用例。"""
    return os.environ.get("MINIMAX_API_KEY") or None


def start_fake_model(adb, keys):
    """电脑上的假模型端点 + adb reverse（设备上的 127.0.0.1:18787 → 这里）。"""
    fm = fake_model.FakeModel(keys).start()
    adb.run("reverse", f"tcp:{fake_model.DEVICE_PORT}", f"tcp:{fm.port}")
    return fm


def leak_scan(adb, key, result):
    """在整个 logcat（所有缓冲区）和结果 JSON 里找 key 的全文或中段（中段不含掩码用的首尾 4 位）。"""
    middle = key[4:-4]
    out = adb.run("logcat", "-d", "-b", "all", "-v", "threadtime", check=False, timeout=120)
    lines = [l for l in out.splitlines() if key in l or middle in l]
    dumped = json.dumps(result, ensure_ascii=False)
    # 命中的行原样写进结果会再泄漏一次：先把 key 换掉
    shown = [l.replace(key, "<KEY>").replace(middle, "<KEY-MIDDLE>")[:400] for l in lines[:10]]
    return {"logcatLines": len(out.splitlines()), "logcatHits": len(lines),
            "resultHits": int(key in dumped or middle in dumped), "hitLines": shown}


class Adb:
    def __init__(self, serial):
        self.adb = shutil.which("adb") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
        self.serial = serial

    def run(self, *args, check=True, timeout=120):
        cmd = [self.adb, "-s", self.serial] + list(args)
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        if check and p.returncode != 0:
            raise RuntimeError(f"{' '.join(cmd)} -> {p.returncode}: {p.stderr.strip()} {p.stdout.strip()}")
        return p.stdout

    def sh(self, cmd, **kw):
        return self.run("shell", cmd, **kw)

    def prop(self, name):
        return self.sh(f"getprop {name}").strip()


def collect_result(adb, run_id, timeout, want_phase=None):
    """轮询 logcat 里的 ACPTEST 分片，拼回完整 JSON。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = adb.run("logcat", "-d", "-v", "raw", "-s", f"{RESULT_TAG}:I", check=False, timeout=30)
        parts, total = {}, None
        for line in out.splitlines():
            if not line.startswith(run_id + " "):
                continue
            try:
                _, idx, data = line.split(" ", 2)
                i, n = (int(x) for x in idx.split("/"))
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


def start(adb, activity, scenario, args, run_id):
    payload = json.dumps(args, separators=(",", ":"))
    adb.sh(f"am start -W -n {activity} --es scenario {scenario} --es run {run_id} --es args '{payload}'", timeout=60)


WARN_KEYS = ("oneway spam", "too many oneway", "transactiontoolarge", "failed binder transaction",
             "fatal exception", "anr in", "foregroundservicedidnotstart", "did not then call service.startforeground")


def warnings(adb):
    out = adb.run("logcat", "-d", check=False, timeout=60)
    return [l for l in out.splitlines() if any(k in l.lower() for k in WARN_KEYS)][-30:]


def run_one(adb, name, activity, scenario, args, timeout):
    run_id = f"{name}-{uuid.uuid4().hex[:6]}"
    adb.run("logcat", "-c", check=False)
    start(adb, activity, scenario, args, run_id)
    r = collect_result(adb, run_id, timeout)
    if r is not None:
        r["logcatWarnings"] = warnings(adb)
    return r


def run_client_kill(adb, name, activity, args, timeout):
    """先让执行器在流式中途自杀，再起一个新的执行器读服务端状态。"""
    run_id = f"{name}-{uuid.uuid4().hex[:6]}"
    adb.run("logcat", "-c", check=False)
    start(adb, activity, "client-kill", args, run_id)
    dying = collect_result(adb, run_id, timeout, want_phase="dying")
    if dying is None:
        return {"ok": False, "error": "client did not reach dying phase"}
    # 通道要很快关掉；任务不随连接取消（F7），要等它自己跑完，所以轮询到 promptsActive=0（最多 30 秒）
    deadline = time.time() + 30
    polls = 0
    while True:
        time.sleep(2 if polls == 0 else 1)
        polls += 1
        stats = run_one(adb, name + "-check", activity, "server-stats", {}, 60)
        if stats is None:
            return {"ok": False, "error": "server-stats timed out", "dying": dying}
        server = stats["server"]
        closes = [c for c in server.get("recentCloses", []) if c.get("closedAtNs", 0) >= dying["dyingAtNs"]]
        peer_died = [c for c in closes if c.get("cause", "").startswith("peer_died")]
        detect_ms = (peer_died[0]["closedAtNs"] - dying["dyingAtNs"]) / 1e6 if peer_died else None
        ok = bool(peer_died) and server.get("promptsActive") == 0 and server.get("liveChannels") == 0 \
            and server.get("connectionsOpen") == 0
        if ok or time.time() > deadline:
            break
    return {
        "ok": ok, "detectMs": detect_ms, "closes": closes, "dying": dying,
        "server": {k: server.get(k) for k in ("pid", "connectionsOpen", "liveChannels", "hostJobChildren",
                                              "promptsActive", "promptOutcomes", "threads", "pssKb")},
        "clientPidBefore": dying.get("pid"), "clientPidAfter": stats.get("client", {}).get("pid"),
        "statsPolls": polls,
    }


def run_user_stop(adb, name, activity, timeout):
    """previousExitStoppedByUser：执行器布好两个运行中 + 一个排队的任务 → am force-stop → 新进程检查恢复结果。"""
    run_id = f"{name}-{uuid.uuid4().hex[:6]}"
    adb.run("logcat", "-c", check=False)
    start(adb, activity, "user-stop-arm", {}, run_id)
    armed = collect_result(adb, run_id, timeout, want_phase="armed")
    if armed is None or not armed.get("armed"):
        return {"ok": False, "error": "tasks were not armed", "armed": armed}
    adb.sh(f"am force-stop {APP_PKG}", check=False)
    time.sleep(2)
    check = run_one(adb, name + "-check", activity, "user-stop-check", {"sessions": armed.get("sessions", [])}, timeout)
    if check is None:
        return {"ok": False, "error": "user-stop-check timed out", "armed": armed}
    check["armed"] = armed
    return check


def summarize(name, r):
    if r is None:
        return f"{name:26s} TIMEOUT"
    s = f"{name:26s} ok={r.get('ok')}"
    pr = r.get("prompt") or r.get("cancelled")
    if isinstance(pr, dict) and "latency" in pr:
        lat = pr["latency"]
        s += (f" chunks={pr.get('chunks')} {pr.get('chunksPerSec', 0):.0f}/s"
              f" p50={lat.get('p50ms', 0):.2f} p99={lat.get('p99ms', 0):.1f}ms")
    for k in ("cancelToStopMs", "detectMs", "serviceRestartMs", "closeCause", "reason", "noLeak", "summary"):
        if k in r and r[k] is not None:
            v = r[k]
            s += f" {k}={round(v, 1) if isinstance(v, float) else v}"
    if not r.get("ok") and "error" in r:
        s += f" error={str(r['error'])[:240]}"
    return s


def install(adb, apk):
    if not os.path.exists(apk):
        sys.exit(f"missing {apk}; build it first")
    adb.run("install", "-r", "-t", apk, timeout=240)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--suite", choices=["sdk", "app"], default="sdk")
    ap.add_argument("--build", choices=["debug", "release"], default="debug",
                    help="sdk 用例的测试 App 构建类型；app 用例固定用 AgentOS 的 debug 包（in-app 执行器只在 debug 包里）")
    ap.add_argument("--app-build", choices=["debug", "releaseTest"], default="debug",
                    help="app 用例用的 AgentOS 包：debug，或 releaseTest（R8，调试证书签名，带 in-app 执行器）")
    ap.add_argument("--only", help="逗号分隔的用例名")
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--label", default="")
    a = ap.parse_args()
    if not a.serial:
        sys.exit("need --serial or ANDROID_SERIAL")
    adb = Adb(a.serial)
    tdir = os.path.join(REPO, "tests", "device", "acp-channel")
    if not a.no_install:
        install(adb, os.path.join(tdir, "client", "build", "outputs", "apk", a.build, f"client-{a.build}.apk"))
        if a.suite == "sdk":
            install(adb, os.path.join(tdir, "agent", "build", "outputs", "apk", a.build, f"agent-{a.build}.apk"))
        else:
            install(adb, os.path.join(REPO, "app", "build", "outputs", "apk", a.app_build, f"app-{a.app_build}.apk"))
    for pkg in (CLIENT_PKG, TEST_AGENT_PKG, APP_PKG):
        adb.sh(f"am force-stop {pkg}", check=False)
    adb.sh("input keyevent KEYCODE_WAKEUP", check=False)
    adb.sh("wm dismiss-keyguard", check=False)

    device = {
        "serial": a.serial, "model": adb.prop("ro.product.model"), "sdk": adb.prop("ro.build.version.sdk"),
        "release": adb.prop("ro.build.version.release"), "fingerprint": adb.prop("ro.build.fingerprint"),
        "buildType": adb.prop("ro.build.type"), "isEmulator": adb.prop("ro.kernel.qemu") == "1" or adb.prop("ro.boot.qemu") == "1",
    }
    print(json.dumps(device, ensure_ascii=False))
    only = set(a.only.split(",")) if a.only else None
    cases = sdk_cases() if a.suite == "sdk" else app_cases()
    test_key = make_test_key()
    real_key = live_key()
    fm = None
    if a.suite == "app":
        # 假模型端点认两把测试 key：通道用例的固定 key 和 BYOK 用例的随机 key
        fm = start_fake_model(adb, {"agtest-fake-model-key": "test", test_key: "byok"})
    results, failed = {}, []
    for name, activity, scenario, args, timeout, kind in cases:
        if only and name not in only:
            continue
        t0 = time.time()
        try:
            push_attempts = None
            if kind == "byok":
                push_attempts = push_key(adb, "byok_key", test_key)
            if kind == "live" and real_key:
                push_attempts = push_key(adb, "live_key", real_key)
            if scenario == "client-kill":
                r = run_client_kill(adb, name, activity, args, timeout)
            elif kind == "userstop":
                r = run_user_stop(adb, name, activity, timeout)
            elif kind == "desktop":
                import desktop_idle  # noqa: PLC0415（它也 import 本模块）
                idle = int(os.environ.get("AGENTOS_DESKTOP_IDLE", args.get("idle", 60)))
                r = desktop_idle.run_desktop(adb, idle, with_bridge=(a.app_build == "debug"), log=lambda m: print(m, flush=True))
            else:
                r = run_one(adb, name, activity, scenario, args, timeout)
        except Exception as e:  # noqa: BLE001
            r = {"ok": False, "error": f"driver: {e}"}
        if r is not None and kind in ("byok", "live") and (kind == "byok" or real_key):
            leak = leak_scan(adb, test_key if kind == "byok" else real_key, r)
            r["leakScan"] = leak
            r["summary"] = f"{r.get('summary', '')} logcatHits={leak['logcatHits']}/{leak['logcatLines']} resultHits={leak['resultHits']}"
            if leak["logcatHits"] or leak["resultHits"]:
                r["ok"] = False
        if r is not None:
            r["driverSec"] = round(time.time() - t0, 1)
            if push_attempts is not None:
                r["keyPushAttempts"] = push_attempts
            if kind == "negative":
                # 负向实验：关掉流控后允许失败，但必须在超时内结束（不能挂住）
                r["deliveredAll"] = r.get("ok")
                r["ok"] = True
        results[name] = r
        if not (r and r.get("ok")):
            failed.append(name)
        print(summarize(name, r), flush=True)
        adb.sh(f"am force-stop {CLIENT_PKG}", check=False)

    if fm is not None:
        adb.run("reverse", "--remove", f"tcp:{fake_model.DEVICE_PORT}", check=False)
        fm.stop()
    if a.suite == "app":
        # 兜底：不留任何测试 key / 真实 key 文件在设备上（用例读完本来就会删）
        dropped = run_one(adb, "drop-keys", INAPP_ACTIVITY, "drop-keys", {}, 60)
        print(f"drop-keys: {dropped.get('summary') if dropped else 'TIMEOUT'}")
    out = {"suite": a.suite, "build": a.build if a.suite == "sdk" else a.app_build, "device": device,
           "time": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "results": results}
    os.makedirs(os.path.join(tdir, "results", "raw"), exist_ok=True)
    tag = f"-{a.label}" if a.label else ""
    path = os.path.join(tdir, "results", "raw",
                        f"{a.suite}-{a.serial.replace(':', '_')}-api{device['sdk']}-{out['build']}{tag}-{time.strftime('%Y%m%d-%H%M%S')}.json")
    with open(path, "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"\n{len(results) - len(failed)}/{len(results)} ok  ->  {path}")
    if failed:
        print("failed: " + ", ".join(failed))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
