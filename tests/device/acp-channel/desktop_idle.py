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
 7. 带着一条已建立的电脑端连接点通知上的“关闭”（uiautomator）：开关关闭、连接被断开（acp-bridge 以“被手机关闭”退出）、
    宽限期后退出前台、通知消失；反向对照：随后进程确实会被冻结。
有任务时通知的副标题是“正在运行任务”（第 5 步的长回复期间检查），任务结束后去掉。

run.py --suite app 的 desktop-access 用例调用 [run_desktop]：debug 包跑全程；releaseTest 包没有 debug 入口、拿不到配对码，
跳过经 acp-bridge 的部分（第 4、5 步和重连），其余照跑（R8 下的前台、通知、“关闭”按钮）。单独运行时结果写
results/raw/desktop-<serial>-api<N>-debug[-label]-<时间>.json，全部通过时退出码 0。
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
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import run as R  # noqa: E402

BRIDGE = os.path.join(R.REPO, "tools", "acp-bridge", "acp-bridge.mjs")
DEBUG_RECEIVER = "org.agentos.app/.agent.DesktopGatewayDebugReceiver"
TEST_MODEL_KEY = "agtest-fake-model-key"
TITLE = "电脑端接入已开启"
TEXT = "允许电脑经 adb 连接。关闭会断开连接，并作废已配对的电脑。"
OFF = "关闭"
BUSY = "正在运行任务"
BRIDGE_CLOSED_BY_PHONE = 6  # tools/acp-bridge 的退出码


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
    sub = re.search(r"android\.subText=String \((.*?)\)\n", r)
    actions = re.findall(r'\[\d+\] "([^"]*)" -> PendingIntent', r)
    ch = re.search(r"Notification\(channel=(\S+)", r)
    return {"present": True, "count": len(recs), "channel": ch.group(1) if ch else None,
            "title": title.group(1) if title else None, "text": text.group(1) if text else None,
            "subText": sub.group(1) if sub else None, "actions": actions}


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


def prompt(bridge, sid, script, timeout=30, cancel_after_first_chunk=False, at_first_chunk=None):
    state = {"cancelled": False, "firstChunkMs": None}
    t = time.time()

    def on_note(m):
        u = (m.get("params") or {}).get("update") or {}
        if u.get("sessionUpdate") == "agent_message_chunk" and state["firstChunkMs"] is None:
            state["firstChunkMs"] = round((time.time() - t) * 1000)
            if at_first_chunk:
                at_first_chunk()
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

ROW_ID = "com.android.systemui:id/expandableNotificationRow"
EXPAND_IDS = ("android:id/expand_button", "android:id/expand_button_touch_container")
COLLAPSE_DESC = ("Collapse", "收起")


def _ui_tree(adb):
    adb.sh("uiautomator dump /sdcard/c6_ui.xml", check=False, timeout=60)
    xml = adb.sh("cat /sdcard/c6_ui.xml", check=False)
    i = xml.find("<hierarchy")
    if i < 0:
        return None, {}
    try:
        root = ET.fromstring(xml[i:])
    except ET.ParseError:
        return None, {}
    parents = {c: p for p in root.iter() for c in p}
    return root, parents


def _bounds(n):
    b = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", n.get("bounds", ""))
    return tuple(map(int, b.groups())) if b else None


def _center(n):
    x1, y1, x2, y2 = _bounds(n)
    return (x1 + x2) // 2, (y1 + y2) // 2


def _labels(n):
    return {n.get("text") or "", n.get("content-desc") or ""}


STACK_ID = "com.android.systemui:id/notification_stack_scroller"


def _open_shade(adb, reopen=False):
    if reopen:
        adb.sh("cmd statusbar collapse", check=False)
        time.sleep(1)
    adb.sh("input keyevent KEYCODE_WAKEUP", check=False)
    adb.sh("wm dismiss-keyguard", check=False)
    time.sleep(0.5)
    adb.sh("cmd statusbar expand-notifications", check=False)
    time.sleep(2.5)


def tap_off_in_shade(adb, open_shade=True):
    """
    在通知栏里点本 App 通知上的“关闭”。先按标题找到这条通知所在的行，只在这一行里找按钮：IMPORTANCE_LOW 的通知在
    有的配置上（Pixel_8a）落在“静音”分组里、是折叠的，按钮要展开后才出现——点这一行的展开按钮，不行就对标题向下滑。
    界面树里没有通知列表（通知栏没展开好）就收起再重新下拉；有通知列表但看不到标题，只在列表范围内滚动，
    不做盲目的全屏手势。返回 (点击坐标或 None, 每一步的动作和看到的通知标题)。
    """
    if open_shade:  # 测试驱动本身时可以传 False：在已经展开、并且手动折叠了这条通知的通知栏上直接找
        _open_shade(adb)
    found, tried = None, []
    for _ in range(6):
        root, parents = _ui_tree(adb)
        stack = next((n for n in root.iter("node") if n.get("resource-id") == STACK_ID), None) if root is not None else None
        titles = [n.get("text") for n in root.iter("node") if n.get("resource-id") == "android:id/title"][:6] if root is not None else []
        title = next((n for n in root.iter("node") if n.get("text") == TITLE), None) if root is not None else None
        if title is None:
            if stack is None:
                tried.append({"do": "reopen", "stack": False, "titles": titles})
                _open_shade(adb, reopen=True)
            else:
                tried.append({"do": "scroll-stack", "stack": True, "titles": titles})
                x1, y1, x2, y2 = _bounds(stack)
                x = (x1 + x2) // 2
                adb.sh(f"input swipe {x} {y1 + (y2 - y1) * 3 // 4} {x} {y1 + (y2 - y1) // 3} 400", check=False)
                time.sleep(1.5)
            continue
        row, p = None, title
        while p is not None:
            if p.get("resource-id") == ROW_ID:
                row = p
                break
            p = parents.get(p)
        scope = row if row is not None else root
        off = next((n for n in scope.iter("node") if OFF in _labels(n) and n is not title and _bounds(n)), None)
        if off is not None:
            found = _center(off)
            tried.append({"do": "tap-off"})
            adb.sh(f"input tap {found[0]} {found[1]}", check=False)
            break
        exp = next((n for n in scope.iter("node") if n.get("resource-id") in EXPAND_IDS and _bounds(n)
                    and not (_labels(n) & set(COLLAPSE_DESC))), None) if row is not None else None
        if exp is not None and not any(t.get("do") == "expand" for t in tried):
            tried.append({"do": "expand"})
            x, y = _center(exp)
            adb.sh(f"input tap {x} {y}", check=False)
        else:
            tried.append({"do": "swipe-down"})
            x, y = _center(title)
            adb.sh(f"input swipe {x} {y} {x} {y + 400} 300", check=False)
        time.sleep(1.5)
    time.sleep(1)
    adb.sh("cmd statusbar collapse", check=False)
    adb.sh("rm -f /sdcard/c6_ui.xml", check=False)
    return found, tried


# ---------------------------------------------------------------------- 主流程

def run_desktop(adb, idle_sec=60, with_bridge=True, log=print):
    """前提：debug 或 releaseTest 包已装好，fake_model 已经 adb reverse 到手机（认测试 key）。返回一个用例结果。"""
    adb.sh(f"pm grant {R.APP_PKG} android.permission.POST_NOTIFICATIONS", check=False)
    # 故意不带电池优化豁免（验证从前台界面打开这条路径）；设备上原来有的话，跑完还原
    had_exemption = f"{R.APP_PKG}," in adb.sh("cmd deviceidle whitelist", check=False)
    adb.sh(f"cmd deviceidle whitelist -{R.APP_PKG}", check=False)
    adb.sh("input keyevent KEYCODE_WAKEUP", check=False)
    adb.sh("wm dismiss-keyguard", check=False)
    t0 = time.time()
    checks, info, samples = {}, {}, []
    state_dir = tempfile.TemporaryDirectory(prefix="c6-bridge-")
    state = os.path.join(state_dir.name, "acp-bridge.json")
    bridge = None

    def step(name, ok, detail=None):
        checks[name] = bool(ok)
        if detail is not None:
            info[name] = detail
        log(f"  {'ok ' if ok else 'FAIL'} {name}" + (f"  {json.dumps(detail, ensure_ascii=False)[:300]}" if detail is not None else ""))

    def home():
        adb.sh("input keyevent KEYCODE_HOME", check=False)

    try:
        r = R.run_one(adb, "c6-model", R.INAPP_ACTIVITY, "handshake", {}, 60)
        step("testModelReady", r and r.get("ok"), r and r.get("summary"))
        R.run_one(adb, "c6-off0", R.INAPP_ACTIVITY, "desktop-access", {"on": False}, 60)
        r = R.run_one(adb, "c6-on", R.INAPP_ACTIVITY, "desktop-access", {"on": True}, 60)
        step("enabledFromUi", r and r.get("ok"), r and r.get("summary"))
        home()
        code = None
        if with_bridge:
            code = debug_op(adb, "pair").get("code")
            step("pairingCode", bool(code))
        n = agent_notification(adb)
        s = agent_service(adb)
        step("notificationShown", n.get("present") and n.get("title") == TITLE and n.get("text") == TEXT and OFF in n.get("actions", [])
             and n.get("channel") == "desktop_access" and not n.get("subText") and s["foreground"] and s["channel"] == "desktop_access",
             {k: n.get(k) for k in ("title", "subText", "channel", "actions")})

        log(f"idle {idle_sec}s (no connection)…")
        samples += idle(adb, idle_sec, t0, "idle-before-connect")

        if with_bridge:
            bridge = Bridge(adb, state, code=code)
            init = bridge.request("initialize", {"protocolVersion": 1, "clientCapabilities": {}}, 30)
            init_ok = (init.get("msg") or {}).get("result", {}).get("protocolVersion") == 1
            step("handshakeAfterIdle", init_ok, {"msFromBridgeStart": round((time.time() - bridge.t0) * 1000), "initMs": init["ms"],
                                                 "timeout": init.get("timeout", False), "stderr": bridge.stderr[-2:]})
            sid = None
            if init_ok:
                new = bridge.request("session/new", {"cwd": "/", "mcpServers": []}, 30)
                sid = ((new.get("msg") or {}).get("result") or {}).get("sessionId")
            step("sessionNew", bool(sid))
            if sid:
                p1 = prompt(bridge, sid, {"chunks": 5, "intervalMs": 20, "n": "c6-first"})
                step("promptAfterIdle", p1["stopReason"] == "end_turn" and p1["chars"] > 0, p1)

                log(f"idle {idle_sec}s (connection open)…")
                samples += idle(adb, idle_sec, t0, "idle-connected")
                p2 = prompt(bridge, sid, {"chunks": 5, "intervalMs": 20, "n": "c6-second"})
                step("promptOnEstablishedSessionAfterIdle", p2["stopReason"] == "end_turn" and p2["chars"] > 0, p2)

                # 长回复进行中：两种前台理由并存，通知副标题是“正在运行任务”；取消后去掉
                busy_note = {}

                def at_first_chunk():
                    # 通知更新合并 0.5 秒（AgentProcess.NOTICE_DEBOUNCE_MS），慢设备上 dumpsys 也要一两秒：轮询到出现为止
                    deadline = time.time() + 6
                    while time.time() < deadline:
                        busy_note.clear()
                        busy_note.update(agent_notification(adb))
                        if busy_note.get("subText") == BUSY:
                            break
                        time.sleep(0.3)

                p3 = prompt(bridge, sid, {"chunks": 400, "intervalMs": 20, "n": "c6-cancel"},
                            cancel_after_first_chunk=True, at_first_chunk=at_first_chunk)
                step("cancel", p3["stopReason"] == "cancelled" and p3["cancelSent"] and p3["ms"] < 5_000, p3)
                after = {}
                deadline = time.time() + 8
                while time.time() < deadline:
                    after = agent_notification(adb)
                    if not after.get("subText"):
                        break
                    time.sleep(0.5)
                step("busyShownWithDesktopOn", busy_note.get("title") == TITLE and busy_note.get("subText") == BUSY
                     and after.get("title") == TITLE and not after.get("subText"),
                     {"whileBusy": busy_note.get("subText"), "afterwards": after.get("subText")})
            rc = bridge.close()
            step("bridgeClosedCleanly", rc == 0, {"exit": rc})
            bridge = None

        # 开关开着时 :agent 被杀、再被拉起（监督进程开机拉起 / bind 冷启动）
        for path in ("boot", "bind"):
            r = R.run_one(adb, f"c6-restart-{path}", R.INAPP_ACTIVITY, "desktop-restart", {"path": path}, 90)
            step(f"restart-{path}", r and r.get("ok"), r and r.get("summary"))
            home()
            samples += idle(adb, 20, t0, f"idle-after-restart-{path}")

        if with_bridge:
            # 重启后用保存的令牌重新连上（不再需要配对码），这条连接留着，下面由“关闭”断开
            bridge = Bridge(adb, state)
            init = bridge.request("initialize", {"protocolVersion": 1, "clientCapabilities": {}}, 30)
            new = bridge.request("session/new", {"cwd": "/", "mcpServers": []}, 30)
            step("reconnectWithTokenAfterRestart", (init.get("msg") or {}).get("result", {}).get("protocolVersion") == 1
                 and ((new.get("msg") or {}).get("result") or {}).get("sessionId"),
                 {"initMs": init["ms"], "stderr": bridge.stderr[-2:]})

        step("neverFrozenWhileOn", samples and all(x.get("alive") and x.get("isFrozen") is False for x in samples),
             {"samples": len(samples), "frozen": sum(1 for x in samples if x.get("isFrozen"))})
        step("foregroundServiceWhileOn", samples and all(x.get("fgService") and (x.get("procState") or 99) <= 4 for x in samples),
             {"procStates": sorted({x.get("procState") for x in samples}), "oomAdj": sorted({x.get("oomAdj") for x in samples})})

        # 通知上的“关闭”
        tapped, tap_path = tap_off_in_shade(adb)
        t_off = time.time()
        if bridge:
            try:
                rc = bridge.p.wait(10)
            except subprocess.TimeoutExpired:
                rc = None
            step("offClosesEstablishedConnection", rc == BRIDGE_CLOSED_BY_PHONE,
                 {"exit": rc, "sec": round(time.time() - t_off, 1), "stderr": bridge.stderr[-1:]})
            bridge = None
        left = None
        while time.time() - t_off < 10:
            s = agent_service(adb)
            n = agent_notification(adb)
            if not s["foreground"] and not n.get("present"):
                left = round(time.time() - t_off, 1)
                break
            time.sleep(0.5)
        step("leftForegroundAfterOff", left is not None, {"sec": left})
        # 开关状态：debug 包问 debug 入口；releaseTest 经 inapp（它会 bind :agent，所以放在反向对照之后）
        if with_bridge:
            st = debug_op(adb, "status")
            step("offViaNotification", bool(tapped) and st.get("enabled") is False and st.get("listening") is False,
                 {"tapped": tapped, "path": tap_path, "enabled": st.get("enabled"), "pairings": len(st.get("pairings", []))})
        # 反向对照：关掉之后没有别的东西让进程保持解冻（点通知按钮给 App 30 秒临时白名单，之后应当被冻结）
        frozen_at = None
        while time.time() - t_off < 90:
            p = agent_proc(adb)
            if p.get("isFrozen"):
                frozen_at = round(time.time() - t_off, 1)
                break
            time.sleep(3)
        step("frozenAfterOff", frozen_at is not None, {"sec": frozen_at})
        if not with_bridge:
            r = R.run_one(adb, "c6-off-check", R.INAPP_ACTIVITY, "desktop-status", {}, 60)
            step("offViaNotification", bool(tapped) and r and r.get("enabled") is False and r.get("listening") is False,
                 {"tapped": tapped, "path": tap_path, "check": r and r.get("summary")})
    finally:
        if bridge:
            bridge.close()
        if with_bridge:
            try:
                debug_op(adb, "disable")
            except Exception:  # noqa: BLE001
                pass
        else:
            try:
                R.run_one(adb, "c6-off-final", R.INAPP_ACTIVITY, "desktop-access", {"on": False}, 60)
            except Exception:  # noqa: BLE001
                pass
        if had_exemption:
            adb.sh(f"cmd deviceidle whitelist +{R.APP_PKG}", check=False)
        state_dir.cleanup()

    ok = bool(checks) and all(checks.values())
    passed = sum(checks.values())
    summary = (f"checks={passed}/{len(checks)} bridge={with_bridge} samples={len(samples)} "
               f"frozen={sum(1 for x in samples if x.get('isFrozen'))} "
               f"handshakeMs={(info.get('handshakeAfterIdle') or {}).get('msFromBridgeStart')} "
               f"offToBackgroundSec={(info.get('leftForegroundAfterOff') or {}).get('sec')} "
               f"frozenAfterOffSec={(info.get('frozenAfterOff') or {}).get('sec')}")
    return {"ok": ok, "summary": summary, "checks": checks, "info": info, "samples": samples}


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
    device = {"serial": a.serial, "model": adb.prop("ro.product.model"), "sdk": adb.prop("ro.build.version.sdk"),
              "release": adb.prop("ro.build.version.release"), "fingerprint": adb.prop("ro.build.fingerprint")}
    print(json.dumps(device, ensure_ascii=False), flush=True)
    fm = R.start_fake_model(adb, {TEST_MODEL_KEY: "test"})
    try:
        r = run_desktop(adb, a.idle, with_bridge=True, log=lambda m: print(m, flush=True))
    finally:
        adb.run("reverse", "--remove", f"tcp:{R.fake_model.DEVICE_PORT}", check=False)
        fm.stop()
    out = {"suite": "desktop", "build": "debug", "device": device, "time": time.strftime("%Y-%m-%dT%H:%M:%S%z"), **r}
    os.makedirs(os.path.join(HERE, "results", "raw"), exist_ok=True)
    tag = f"-{a.label}" if a.label else ""
    path = os.path.join(HERE, "results", "raw",
                        f"desktop-{a.serial.replace(':', '_')}-api{device['sdk']}-debug{tag}-{time.strftime('%Y%m%d-%H%M%S')}.json")
    with open(path, "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"\n{sum(r['checks'].values())}/{len(r['checks'])} ok  ->  {path}")
    sys.exit(0 if r["ok"] else 1)


if __name__ == "__main__":
    main()
