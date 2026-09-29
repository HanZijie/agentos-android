#!/usr/bin/env python3
"""
C6（architecture F11 第 4 点）：电脑端接入打开期间 `:agent` 留在前台，不被 cached-apps freezer 冻结。

A6 查明：`:agent` 空闲（没有任务、没有被 Binder 绑定）时是 cached 进程，约 10 秒后被冻结，抽象 socket agentos-acp 上
的新连接和已建立会话的请求都得不到服务。修复后开关打开期间 AgentService 以前台服务运行，通知“电脑端接入已开启”。

流程（debug 包；主机上起 fake_model.py，经 adb reverse 给手机当模型端点）：
 1. 确保手机上的模型来源是测试端点（inapp 的 handshake 场景顺带调用 ensureTestModel）。
 2. 像设置页一样从前台界面打开开关（inapp desktop-access，IAgentControl v3），回到桌面；配对码从 debug 入口的广播结果里取
    （不经设备日志）。检查前台服务和通知（标题、正文、“关闭”按钮）。
 3. 空闲 IDLE 秒（不碰 App，只用 dumpsys 旁观：isFrozen、进程状态、前台服务）。
 4. 经 tools/acp-bridge 连接：配对握手 + initialize + session/new + 一轮对话（假模型端点）。
 5. 连接不断、再空闲 IDLE 秒，同一会话再来一轮；再测一次取消。
 6. 开关开着时杀掉 :agent，分别按“监督进程开机拉起”（SUPERVISOR_START）和“bind 冷启动”拉起：恢复后留在前台、重新监听，
    空闲 20 秒不被冻结；用保存的令牌重新连上。
 7. 点通知上的“关闭”（uiautomator）：开关关闭、宽限期后退出前台、通知消失；反向对照：随后进程确实会被冻结。

结果写 results/raw/desktop-<serial>-api<N>-debug[-label]-<时间>.json。全部通过时退出码 0。
"""
import argparse
import json
import os
import queue
import re
import subprocess
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import run as R  # noqa: E402

BRIDGE = os.path.join(R.REPO, "tools", "acp-bridge", "acp-bridge.mjs")
DEBUG_RECEIVER = "org.agentos.app/.agent.DesktopGatewayDebugReceiver"
TEST_MODEL_KEY = "agtest-fake-model-key"
TITLE = "电脑端接入已开启"
TEXT = "允许电脑经 adb 连接。关闭会断开连接，并作废已配对的电脑。"
OFF = "关闭"


# ---------------------------------------------------------------------- 旁观（只经 system_server，不碰 App 进程）

def agent_proc(adb):
    out = adb.sh("dumpsys activity processes org.agentos.app", check=False, timeout=60)
    m = re.search(r"ProcessRecord\{\S+ (\d+):org\.agentos\.app:agent/", out)
    if not m:
        return {"alive": False}
    block = out[m.start():]
    nxt = block.find("*APP*", 10)
    if nxt > 0:
        block = block[:nxt]
    frozen = re.search(r"isFrozen=(\w+)", block)
    ps = re.search(r"curProcState=(\d+)", block)
    adj = re.search(r"oom adj:.*? cur=(-?\d+)", block)
    return {"alive": True, "pid": int(m.group(1)),
            "isFrozen": (frozen.group(1) == "true") if frozen else None,
            "procState": int(ps.group(1)) if ps else None,
            "oomAdj": int(adj.group(1)) if adj else None}


def agent_service(adb):
    out = adb.sh("dumpsys activity services org.agentos.app/.agent.AgentService", check=False, timeout=60)
    fg = re.search(r"isForeground=(\w+)", out)
    ch = re.search(r"foregroundNoti=Notification\(channel=(\S+)", out)
    return {"running": "ServiceRecord{" in out, "foreground": bool(fg and fg.group(1) == "true"),
            "channel": ch.group(1) if ch else None}


def agent_notification(adb):
    out = adb.sh("dumpsys notification --noredact", check=False, timeout=60)
    recs = [r for r in out.split("NotificationRecord(")[1:] if "pkg=org.agentos.app " in r[:200]]
    if not recs:
        return {"present": False}
    r = recs[0][:6000]
    title = re.search(r"android\.title=String \((.*?)\)\n", r)
    text = re.search(r"android\.text=String \((.*?)\)\n", r)
    actions = re.findall(r'\[\d+\] "([^"]*)" -> PendingIntent', r)
    ch = re.search(r"Notification\(channel=(\S+)", r)
    return {"present": True, "count": len(recs), "channel": ch.group(1) if ch else None,
            "title": title.group(1) if title else None, "text": text.group(1) if text else None, "actions": actions}


def sample(adb, t0, phase):
    p = agent_proc(adb)
    s = agent_service(adb)
    return {"t": round(time.time() - t0, 1), "phase": phase, **p, "fgService": s["foreground"]}


def idle(adb, seconds, t0, phase, every=5):
    """不碰 App，每 [every] 秒旁观一次。"""
    out = []
    end = time.time() + seconds
    while True:
        out.append(sample(adb, t0, phase))
        left = end - time.time()
        if left <= 0:
            break
        time.sleep(min(every, left))
    return out


def debug_op(adb, op):
    """debug 包的 DesktopGatewayDebugReceiver：结果在广播的 result data 里（不进设备日志）。"""
    out = adb.sh(f"am broadcast -f 32 -n {DEBUG_RECEIVER} --es op {op}", check=False, timeout=60)
    m = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
    if not m:
        raise RuntimeError(f"debug receiver {op}: no result data")
    return json.loads(m.group(1))


# ---------------------------------------------------------------------- acp-bridge（电脑端的 stdio Agent 命令）

class Bridge:
    def __init__(self, adb, state, code=None, label="c6-desktop-idle"):
        env = dict(os.environ, ADB=adb.adb)
        env.pop("AGENTOS_PAIRING_CODE", None)
        if code:
            env["AGENTOS_PAIRING_CODE"] = code  # 只放环境变量，不上命令行
        self.t0 = time.time()
        self.p = subprocess.Popen(
            ["node", BRIDGE, "--serial", adb.serial, "--state", state, "--label", label, "--timeout", "15000"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, text=True, bufsize=1)
        self.q = queue.Queue()
        self.stderr = []
        self.next_id = 1
        threading.Thread(target=self._out, daemon=True).start()
        threading.Thread(target=self._err, daemon=True).start()

    def _out(self):
        for line in self.p.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                self.q.put(json.loads(line))
            except ValueError:
                self.q.put({"_raw": line[:200]})
        self.q.put(None)

    def _err(self):
        for line in self.p.stderr:
            self.stderr.append(line.rstrip()[:300])

    def send(self, obj):
        self.p.stdin.write(json.dumps(obj, ensure_ascii=False) + "\n")
        self.p.stdin.flush()

    def request(self, method, params, timeout, on_note=None):
        rid = self.next_id
        self.next_id += 1
        t = time.time()
        self.send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        notes = []
        deadline = t + timeout
        while True:
            left = deadline - time.time()
            if left <= 0:
                return {"timeout": True, "ms": round((time.time() - t) * 1000), "notes": notes}
            try:
                m = self.q.get(timeout=left)
            except queue.Empty:
                continue
            if m is None:
                return {"closed": True, "ms": round((time.time() - t) * 1000), "notes": notes}
            if m.get("id") == rid and ("result" in m or "error" in m):
                return {"msg": m, "ms": round((time.time() - t) * 1000), "notes": notes}
            if "method" in m and "id" in m:
                # Agent 发给客户端的请求（这里用不到）：一律回“不支持”
                self.send({"jsonrpc": "2.0", "id": m["id"], "error": {"code": -32601, "message": "not supported"}})
                continue
            notes.append(m)
            if on_note:
                on_note(m)

    def close(self):
        try:
            self.p.stdin.close()
        except OSError:
            pass
        try:
            return self.p.wait(20)
        except subprocess.TimeoutExpired:
            self.p.kill()
            return None


def chunk_text(notes):
    out = []
    for m in notes:
        u = (m.get("params") or {}).get("update") or {}
        if m.get("method") == "session/update" and u.get("sessionUpdate") == "agent_message_chunk":
            out.append((u.get("content") or {}).get("text", ""))
    return "".join(out)


def prompt(bridge, sid, script, timeout=30, cancel_after_first_chunk=False):
    state = {"cancelled": False, "firstChunkMs": None}
    t = time.time()

    def on_note(m):
        u = (m.get("params") or {}).get("update") or {}
        if u.get("sessionUpdate") == "agent_message_chunk" and state["firstChunkMs"] is None:
            state["firstChunkMs"] = round((time.time() - t) * 1000)
            if cancel_after_first_chunk and not state["cancelled"]:
                state["cancelled"] = True
                bridge.send({"jsonrpc": "2.0", "method": "session/cancel", "params": {"sessionId": sid}})

    r = bridge.request("session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text": json.dumps(script)}]},
                       timeout, on_note)
    msg = r.get("msg") or {}
    return {"stopReason": (msg.get("result") or {}).get("stopReason"), "error": msg.get("error"),
            "timeout": r.get("timeout", False), "closed": r.get("closed", False), "ms": r["ms"],
            "firstChunkMs": state["firstChunkMs"], "chars": len(chunk_text(r["notes"])), "cancelSent": state["cancelled"]}


# ---------------------------------------------------------------------- 通知上的“关闭”（uiautomator）

def tap_off_in_shade(adb):
    adb.sh("input keyevent KEYCODE_WAKEUP", check=False)
    adb.sh("wm dismiss-keyguard", check=False)
    adb.sh("cmd statusbar expand-notifications", check=False)
    time.sleep(2)
    found = None
    for attempt in range(2):
        adb.sh("uiautomator dump /sdcard/c6_ui.xml", check=False, timeout=60)
        xml = adb.sh("cat /sdcard/c6_ui.xml", check=False)
        nodes = re.findall(r"<node [^>]*>", xml)
        has_title = any(f'text="{TITLE}"' in n for n in nodes)
        for n in nodes:
            if f'text="{OFF}"' in n and "action" in n and has_title:
                b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
                if b:
                    x1, y1, x2, y2 = map(int, b.groups())
                    found = ((x1 + x2) // 2, (y1 + y2) // 2)
                    break
        if found:
            break
        # 通知折叠着：点它的展开按钮再找
        for n in nodes:
            if 'resource-id="android:id/expand_button"' in n:
                b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
                if b:
                    x1, y1, x2, y2 = map(int, b.groups())
                    adb.sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}", check=False)
                    time.sleep(1.5)
                    break
    if found:
        adb.sh(f"input tap {found[0]} {found[1]}", check=False)
    time.sleep(1)
    adb.sh("cmd statusbar collapse", check=False)
    adb.sh("rm -f /sdcard/c6_ui.xml", check=False)
    return found


# ---------------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--idle", type=int, default=60, help="两段空闲各多少秒（默认 60）")
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--label", default="")
    a = ap.parse_args()
    if not a.serial:
        sys.exit("need --serial or ANDROID_SERIAL")
    adb = R.Adb(a.serial)
    if not a.no_install:
        R.install(adb, os.path.join(R.REPO, "app", "build", "outputs", "apk", "debug", "app-debug.apk"))
    adb.sh(f"am force-stop {R.APP_PKG}", check=False)
    # 首次引导里请求的通知权限（F2）；电池优化豁免故意不给：验证的是从前台界面打开这条正常路径
    adb.sh(f"pm grant {R.APP_PKG} android.permission.POST_NOTIFICATIONS", check=False)
    adb.sh(f"cmd deviceidle whitelist -{R.APP_PKG}", check=False)
    adb.sh("input keyevent KEYCODE_WAKEUP", check=False)
    adb.sh("wm dismiss-keyguard", check=False)
    device = {"serial": a.serial, "model": adb.prop("ro.product.model"), "sdk": adb.prop("ro.build.version.sdk"),
              "release": adb.prop("ro.build.version.release"), "fingerprint": adb.prop("ro.build.fingerprint")}
    print(json.dumps(device, ensure_ascii=False), flush=True)

    t0 = time.time()
    checks, info, samples = {}, {}, []
    fm = R.start_fake_model(adb, {TEST_MODEL_KEY: "test"})
    state_dir = tempfile.TemporaryDirectory(prefix="c6-bridge-")
    state = os.path.join(state_dir.name, "acp-bridge.json")
    bridge = None

    def step(name, ok, detail=None):
        checks[name] = bool(ok)
        if detail is not None:
            info[name] = detail
        print(f"  {'ok ' if ok else 'FAIL'} {name}" + (f"  {json.dumps(detail, ensure_ascii=False)[:300]}" if detail is not None else ""), flush=True)

    def home():
        adb.sh("input keyevent KEYCODE_HOME", check=False)

    try:
        r = R.run_one(adb, "c6-model", R.INAPP_ACTIVITY, "handshake", {}, 60)
        step("testModelReady", r and r.get("ok"), r and r.get("summary"))
        R.run_one(adb, "c6-off0", R.INAPP_ACTIVITY, "desktop-access", {"on": False}, 60)
        r = R.run_one(adb, "c6-on", R.INAPP_ACTIVITY, "desktop-access", {"on": True}, 60)
        step("enabledFromUi", r and r.get("ok"), r and r.get("summary"))
        home()
        code = debug_op(adb, "pair").get("code")
        step("pairingCode", bool(code))
        n = agent_notification(adb)
        s = agent_service(adb)
        step("notificationShown", n.get("present") and n.get("title") == TITLE and n.get("text") == TEXT and OFF in n.get("actions", [])
             and n.get("channel") == "desktop_access" and s["foreground"] and s["channel"] == "desktop_access",
             {k: n.get(k) for k in ("title", "channel", "actions")})

        # 空闲（没有连接）
        print(f"idle {a.idle}s (no connection)…", flush=True)
        samples += idle(adb, a.idle, t0, "idle-before-connect")

        # 电脑端连接：配对握手 + initialize + session/new + 一轮对话
        bridge = Bridge(adb, state, code=code)
        init = bridge.request("initialize", {"protocolVersion": 1, "clientCapabilities": {}}, 30)
        init_ok = (init.get("msg") or {}).get("result", {}).get("protocolVersion") == 1
        step("handshakeAfterIdle", init_ok, {"msFromBridgeStart": round((time.time() - bridge.t0) * 1000), "initMs": init["ms"],
                                             "timeout": init.get("timeout", False), "stderr": bridge.stderr[-3:]})
        sid = None
        if init_ok:
            new = bridge.request("session/new", {"cwd": "/", "mcpServers": []}, 30)
            sid = ((new.get("msg") or {}).get("result") or {}).get("sessionId")
        step("sessionNew", bool(sid))
        if sid:
            p1 = prompt(bridge, sid, {"chunks": 5, "intervalMs": 20, "n": "c6-first"})
            step("promptAfterIdle", p1["stopReason"] == "end_turn" and p1["chars"] > 0, p1)

            print(f"idle {a.idle}s (connection open)…", flush=True)
            samples += idle(adb, a.idle, t0, "idle-connected")
            p2 = prompt(bridge, sid, {"chunks": 5, "intervalMs": 20, "n": "c6-second"})
            step("promptOnEstablishedSessionAfterIdle", p2["stopReason"] == "end_turn" and p2["chars"] > 0, p2)
            p3 = prompt(bridge, sid, {"chunks": 400, "intervalMs": 20, "n": "c6-cancel"}, cancel_after_first_chunk=True)
            step("cancel", p3["stopReason"] == "cancelled" and p3["cancelSent"] and p3["ms"] < 5_000, p3)
        rc = bridge.close()
        step("bridgeClosedCleanly", rc == 0, {"exit": rc})
        bridge = None

        # 开关开着时 :agent 被杀、再被拉起（监督进程开机拉起 / bind 冷启动）
        for path in ("boot", "bind"):
            r = R.run_one(adb, f"c6-restart-{path}", R.INAPP_ACTIVITY, "desktop-restart", {"path": path}, 90)
            step(f"restart-{path}", r and r.get("ok"), r and r.get("summary"))
            home()
            samples += idle(adb, 20, t0, f"idle-after-restart-{path}")
        # 重启后用保存的令牌重新连上（不再需要配对码）
        bridge = Bridge(adb, state)
        init = bridge.request("initialize", {"protocolVersion": 1, "clientCapabilities": {}}, 30)
        step("reconnectWithTokenAfterRestart", (init.get("msg") or {}).get("result", {}).get("protocolVersion") == 1,
             {"initMs": init["ms"], "stderr": bridge.stderr[-2:]})
        bridge.close()
        bridge = None

        on = [x for x in samples]
        step("neverFrozenWhileOn", on and all(x.get("alive") and x.get("isFrozen") is False for x in on),
             {"samples": len(on), "frozen": sum(1 for x in on if x.get("isFrozen"))})
        step("foregroundServiceWhileOn", on and all(x.get("fgService") and (x.get("procState") or 99) <= 4 for x in on),
             {"procStates": sorted({x.get("procState") for x in on}), "oomAdj": sorted({x.get("oomAdj") for x in on})})

        # 通知上的“关闭”
        tapped = tap_off_in_shade(adb)
        t_off = time.time()
        st = debug_op(adb, "status")
        step("offViaNotification", bool(tapped) and st.get("enabled") is False and st.get("listening") is False,
             {"tapped": tapped, "enabled": st.get("enabled"), "pairings": len(st.get("pairings", []))})
        left = None
        while time.time() - t_off < 10:
            s = agent_service(adb)
            n = agent_notification(adb)
            if not s["foreground"] and not n.get("present"):
                left = round(time.time() - t_off, 1)
                break
            time.sleep(0.5)
        step("leftForegroundAfterOff", left is not None, {"sec": left})
        # 反向对照：关掉之后没有别的东西让进程保持解冻（通知按钮给的 30 秒临时白名单过后应当被冻结）
        frozen_at = None
        while time.time() - t_off < 90:
            p = agent_proc(adb)
            if p.get("isFrozen"):
                frozen_at = round(time.time() - t_off, 1)
                break
            time.sleep(3)
        step("frozenAfterOff", frozen_at is not None, {"sec": frozen_at, "last": agent_proc(adb)})
    finally:
        if bridge:
            bridge.close()
        try:
            debug_op(adb, "disable")
        except Exception:
            pass
        adb.run("reverse", "--remove", f"tcp:{R.fake_model.DEVICE_PORT}", check=False)
        fm.stop()
        state_dir.cleanup()

    ok = bool(checks) and all(checks.values())
    out = {"suite": "desktop", "build": "debug", "device": device, "time": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
           "ok": ok, "checks": checks, "info": info, "samples": samples}
    os.makedirs(os.path.join(HERE, "results", "raw"), exist_ok=True)
    tag = f"-{a.label}" if a.label else ""
    path = os.path.join(HERE, "results", "raw",
                        f"desktop-{a.serial.replace(':', '_')}-api{device['sdk']}-debug{tag}-{time.strftime('%Y%m%d-%H%M%S')}.json")
    with open(path, "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"\n{sum(checks.values())}/{len(checks)} ok  ->  {path}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
