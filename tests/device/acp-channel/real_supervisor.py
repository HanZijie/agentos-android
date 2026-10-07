#!/usr/bin/env python3
"""真机（Magisk / KernelSU）上的监督进程验证，只用 adb + su，不点界面。

  python3 tests/device/acp-channel/real_supervisor.py --serial <serial> [--label real]

前提：module zip 已刷入并重启过，监督进程在跑（/data/adb/agentos/status 存在），debug 包的 App 已装好。
三段：
  1. killWithTask：电脑端接入打开，acp-bridge 发一个较长的 prompt（运行时有任务），`su -c kill -9 <:agent pid>`。
     监督进程要在退避后重新拉起 :agent，日志里有“gone … need=1”和新 pid，状态文件回到 ok / runtime_up。
  2. crashLoop：任务恢复后继续杀，LOOP_MAX（5）次死亡后进入 safe_mode / crash_loop，之后不再拉起；
     删掉 safe_mode 文件（与模块“动作”按钮一致）后退出安全模式，状态回 ok / safe_mode_exited。
  3. rootForegroundNotRestricted：撤掉电池优化豁免并 force-stop 后，App 自己在后台要前台服务被系统拒绝
     （Background started FGS: Disallowed），监督进程的 promote 同样的命令由 root（u:r:magisk:s0）发出时被允许
     （Allowed）、服务进入前台。这是 S2“Magisk 下的 root 调用方同样不受后台限制”的真机确认。
结束时还原：关电脑端接入、清 adb reverse、电池优化豁免加回去、safe_mode 文件删掉。
"""
import argparse
import json
import os
import re
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import run as R  # noqa: E402
import desktop_idle as D  # noqa: E402

STATE = "/data/adb/agentos"
HB = f"/data/user_de/0/{R.APP_PKG}/files/supervisor/heartbeat"
AGENT = f"{R.APP_PKG}:agent"
START_FG = (f"am start-foreground-service --user 0 -n {R.APP_PKG}/.agent.AgentService "
            "-a org.agentos.action.SUPERVISOR_START --es org.agentos.extra.REASON promote --ei org.agentos.extra.ATTEMPT 0")


def su(adb, cmd, check=False, timeout=60):
    assert "'" not in cmd
    return adb.sh(f"su -c '{cmd}'", check=check, timeout=timeout)


def kv(text):
    out = {}
    for line in text.splitlines():
        if "=" in line:
            k, v = line.strip().split("=", 1)
            out[k] = v
    return out


def sup_status(adb):
    return kv(su(adb, f"cat {STATE}/status"))


def sup_log(adb):
    return su(adb, f"cat {STATE}/supervisor.log").splitlines()


def heartbeat(adb):
    return kv(su(adb, f"cat {HB}"))


def agent_pid(adb):
    out = adb.sh(f"pidof {AGENT}", check=False).strip().split()
    return int(out[0]) if out else None


def wait_for(fn, timeout, every=0.5):
    end = time.time() + timeout
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(every)
    return None


class Prompt:
    """在 bridge 上发一个 prompt，不阻塞；主线程轮询它的输出。"""

    def __init__(self, bridge, sid, script):
        self.bridge = bridge
        self.rid = bridge.next_id
        bridge.next_id += 1
        self.chunks = 0
        self.done = None
        bridge.send({"jsonrpc": "2.0", "id": self.rid, "method": "session/prompt",
                     "params": {"sessionId": sid, "prompt": [{"type": "text", "text": json.dumps(script)}]}})

    def pump(self, wait=0.2):
        import queue
        try:
            m = self.bridge.q.get(timeout=wait)
        except queue.Empty:
            return
        if m is None:
            self.done = {"closed": True}
            return
        if m.get("id") == self.rid and ("result" in m or "error" in m):
            self.done = {"stopReason": (m.get("result") or {}).get("stopReason"), "error": m.get("error")}
        u = (m.get("params") or {}).get("update") or {}
        if u.get("sessionUpdate") == "agent_message_chunk":
            self.chunks += 1


def run(adb, log):
    checks, info = {}, {}

    def step(name, ok, detail=None):
        checks[name] = bool(ok)
        if detail is not None:
            info[name] = detail
        log(f"  {'ok ' if ok else 'FAIL'} {name}" + (f"  {json.dumps(detail, ensure_ascii=False)[:360]}" if detail is not None else ""))

    base = sup_status(adb)
    step("supervisorRunning", base.get("state") in ("ok", "backoff"), base)
    if base.get("state") not in ("ok", "backoff"):
        return {"ok": False, "checks": checks, "info": info}
    su(adb, f"cat /dev/null")  # touch su
    had_exemption = f"{R.APP_PKG}," in adb.sh("cmd deviceidle whitelist", check=False)
    adb.sh(f"cmd deviceidle whitelist +{R.APP_PKG}", check=False)
    adb.sh("pm grant org.agentos.app android.permission.POST_NOTIFICATIONS", check=False)

    state_dir = tempfile.TemporaryDirectory(prefix="real-sup-")
    bridge = None
    try:
        for op in ("revoke_all", "disable"):
            adb.sh(f"am broadcast -f 32 -n {D.DEBUG_RECEIVER} --es op {op}", check=False)
        r = R.run_one(adb, "sup-model", R.INAPP_ACTIVITY, "handshake", {}, 60)
        step("testModelReady", r and r.get("ok"), r and r.get("summary"))
        r = R.run_one(adb, "sup-on", R.INAPP_ACTIVITY, "desktop-access", {"on": True}, 60)
        step("desktopAccessOn", r and r.get("ok"))
        code = D.debug_op(adb, "pair").get("code")
        bridge = D.Bridge(adb, os.path.join(state_dir.name, "acp-bridge.json"), code=code, label="real-supervisor")
        init = bridge.request("initialize", {"protocolVersion": 1, "clientCapabilities": {}}, 30)
        new = bridge.request("session/new", {"cwd": "/", "mcpServers": []}, 30)
        sid = ((new.get("msg") or {}).get("result") or {}).get("sessionId")
        step("bridgeSession", bool(sid))
        if not sid:
            return {"ok": False, "checks": checks, "info": info}

        # ---------------------------------------------------------------- 1. 有任务时 :agent 被杀
        log("1. killWithTask")
        log_n0 = len(sup_log(adb))
        # 600 块 × 50 ms ≈ 30 s：被杀后恢复任务再跑一遍也只要 30 s
        pr = Prompt(bridge, sid, {"chunks": 600, "intervalMs": 50, "n": "sup-kill"})
        wait_for(lambda: pr.pump() or pr.chunks >= 3, 30, every=0.0)
        old_pid = agent_pid(adb)
        hb0 = heartbeat(adb)
        step("taskRunningBeforeKill", old_pid and int(hb0.get("tasks", "0")) >= 1 and hb0.get("pid") == str(old_pid),
             {"pid": old_pid, "hb": {k: hb0.get(k) for k in ("pid", "tasks", "fg", "state")}})
        t_kill = time.time()
        su(adb, f"kill -9 {old_pid}")
        new_pid = wait_for(lambda: (lambda p: p if p and p != old_pid else None)(agent_pid(adb)), 90, every=0.5)
        t_up = round(time.time() - t_kill, 1)
        lines = sup_log(adb)[log_n0:]
        gone = [l for l in lines if f"pid={old_pid} gone" in l]
        step("supervisorRelaunched", new_pid is not None, {"oldPid": old_pid, "newPid": new_pid, "secToUp": t_up})
        step("deathSeenWithTask", bool(gone) and "need=1" in gone[0], gone[:1])
        st = wait_for(lambda: (lambda s: s if s.get("state") == "ok" and s.get("reason") == "runtime_up" else None)(sup_status(adb)), 30)
        step("statusBackToOk", st is not None, {k: (st or sup_status(adb)).get(k) for k in ("state", "reason", "deaths", "pid")})
        hb1 = wait_for(lambda: (lambda h: h if new_pid and h.get("pid") == str(new_pid) else None)(heartbeat(adb)), 20)
        step("newRuntimeHeartbeat", hb1 is not None, hb1 and {k: hb1.get(k) for k in ("pid", "tasks", "fg", "state")})
        # 客户端那条连接随 :agent 一起死了：bridge 看到的是被关闭，不是挂住
        end = time.time() + 20
        while pr.done is None and time.time() < end:
            pr.pump(0.5)
        step("clientSawClose", pr.done is not None, pr.done)

        # ---------------------------------------------------------------- 2. 崩溃循环 → safe mode
        log("2. crashLoop")
        # 被杀的任务不会被恢复（客户端连接随进程一起死了），新进程 tasks=0：监督进程只在“有任务时死亡”才记一次死亡。
        # 所以每一轮都要连上新的 bridge（用保存的令牌）、发新任务、等到有任务再杀。LOOP_MAX=5 次死亡进安全模式。
        kills = 1
        bridge.close()
        bridge = None
        for i in range(2, 9):
            if sup_status(adb).get("state") == "safe_mode":
                break
            b = D.Bridge(adb, os.path.join(state_dir.name, "acp-bridge.json"), label=f"real-supervisor-{i}")
            init = b.request("initialize", {"protocolVersion": 1, "clientCapabilities": {}}, 40)
            new = b.request("session/new", {"cwd": "/", "mcpServers": []}, 30)
            sid2 = ((new.get("msg") or {}).get("result") or {}).get("sessionId")
            if not sid2:
                step(f"killable#{i}", False, {"init": bool(init.get("msg")), "stderr": b.stderr[-2:], "status": sup_status(adb)})
                b.close()
                break
            p2 = Prompt(b, sid2, {"chunks": 600, "intervalMs": 50, "n": f"sup-loop-{i}"})
            wait_for(lambda: p2.pump() or p2.chunks >= 3, 30, every=0.0)
            pid = agent_pid(adb)
            hb = heartbeat(adb)
            if not (pid and hb.get("pid") == str(pid) and int(hb.get("tasks", "0")) >= 1):
                step(f"killable#{i}", False, {"pid": pid, "hb": hb})
                b.close()
                break
            su(adb, f"kill -9 {pid}")
            kills += 1
            b.close()
            wait_for(lambda: sup_status(adb).get("state") == "safe_mode" or (agent_pid(adb) not in (None, pid)), 40)
        step("killsWithTask", kills >= 2, {"kills": kills})
        sm = wait_for(lambda: (lambda s: s if s.get("state") == "safe_mode" else None)(sup_status(adb)), 40)
        step("enteredSafeMode", sm is not None, {"kills": kills, "status": {k: (sm or sup_status(adb)).get(k) for k in ("state", "reason", "deaths")}})
        step("safeModeReasonCrashLoop", sm is not None and sm.get("reason") == "crash_loop")
        lines = sup_log(adb)
        step("safeModeLogged", any("SAFE MODE entered: crash_loop" in l for l in lines))
        time.sleep(1)
        before = agent_pid(adb)
        time.sleep(15)
        after = agent_pid(adb)
        step("noRestartInSafeMode", after is None or after == before, {"before": before, "after": after})
        step("safeFileWritten", "reason=crash_loop" in su(adb, f"cat {STATE}/safe_mode"))
        desc = su(adb, f"grep ^description= /data/adb/modules/agentos/module.prop")
        step("moduleDescriptionSafeMode", "安全模式" in desc, desc.strip()[:120])
        su(adb, f"rm -f {STATE}/safe_mode")
        ex = wait_for(lambda: (lambda s: s if s.get("state") == "ok" and s.get("reason") in ("safe_mode_exited", "runtime_up") else None)(sup_status(adb)), 30)
        step("safeModeExited", ex is not None, {k: (ex or sup_status(adb)).get(k) for k in ("state", "reason")})
        exit_lines = [l for l in sup_log(adb) if "state -> ok (safe_mode_exited)" in l or "start issued reason=safe_mode_exit" in l]
        step("safeModeExitLogged", len(exit_lines) >= 2, [l[:90] for l in exit_lines[-2:]])
    finally:
        if bridge:
            bridge.close()
        for op in ("revoke_all", "disable"):
            adb.sh(f"am broadcast -f 32 -n {D.DEBUG_RECEIVER} --es op {op}", check=False)
        su(adb, f"rm -f {STATE}/safe_mode")

    # -------------------------------------------------------------------- 3. root 拉前台服务不受后台限制
    log("3. rootForegroundNotRestricted")
    adb.sh(f"cmd deviceidle whitelist -{R.APP_PKG}", check=False)
    adb.sh(f"am force-stop {R.APP_PKG}", check=False)
    time.sleep(3)
    adb.run("logcat", "-c", check=False)
    # App 自己在后台要前台服务：调试入口开电脑端接入（需要前台），预期被系统拒
    D.debug_op(adb, "enable")
    time.sleep(3)
    own = adb.run("logcat", "-d", "-v", "brief", "-s", "ActivityManager:I", check=False)
    own_lines = [l for l in own.splitlines() if "Background started FGS" in l and R.APP_PKG in l]
    svc_own = D.agent_service(adb)
    step("appOwnStartDeniedInBackground", any("Disallowed" in l for l in own_lines) and not svc_own["foreground"],
         {"fgsLog": [re.sub(r"^.*Background started FGS", "Background started FGS", l)[:230] for l in own_lines[-2:]], "foreground": svc_own["foreground"]})
    # 与监督进程 promote 相同的命令，由 u:r:magisk:s0 的 root 发出
    adb.run("logcat", "-c", check=False)
    out = su(adb, START_FG)
    time.sleep(3)
    root = adb.run("logcat", "-d", "-v", "brief", "-s", "ActivityManager:I", check=False)
    root_lines = [l for l in root.splitlines() if "Background started FGS" in l and R.APP_PKG in l]
    svc_root = D.agent_service(adb)
    step("rootStartAllowed", any("Allowed" in l for l in root_lines) and svc_root["foreground"],
         {"fgsLog": [re.sub(r"^.*Background started FGS", "Background started FGS", l)[:260] for l in root_lines[-2:]],
          "foreground": svc_root["foreground"], "amOut": out.strip()[:120]})
    for op in ("revoke_all", "disable"):
        adb.sh(f"am broadcast -f 32 -n {D.DEBUG_RECEIVER} --es op {op}", check=False)
    adb.sh(f"cmd deviceidle whitelist +{R.APP_PKG}", check=False)
    if not had_exemption:
        adb.sh(f"cmd deviceidle whitelist -{R.APP_PKG}", check=False)
    return {"ok": all(checks.values()), "checks": checks, "info": info}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--label", default="")
    a = ap.parse_args()
    if not a.serial:
        sys.exit("need --serial or ANDROID_SERIAL")
    adb = R.Adb(a.serial)
    if "uid=0" not in su(adb, "id"):
        sys.exit("su is not available on this device")
    device = {"serial": a.serial, "model": adb.prop("ro.product.model"), "sdk": adb.prop("ro.build.version.sdk"),
              "release": adb.prop("ro.build.version.release"), "fingerprint": adb.prop("ro.build.fingerprint"),
              "suContext": su(adb, "cat /proc/self/attr/current").strip("\x00\n ")}
    print(json.dumps(device, ensure_ascii=False), flush=True)
    fm = R.start_fake_model(adb, {D.TEST_MODEL_KEY: "test"})
    try:
        r = run(adb, lambda m: print(m, flush=True))
    finally:
        adb.run("reverse", "--remove", f"tcp:{R.fake_model.DEVICE_PORT}", check=False)
        fm.stop()
    out = {"suite": "supervisor", "device": device, "time": time.strftime("%Y-%m-%dT%H:%M:%S%z"), **r}
    os.makedirs(os.path.join(HERE, "results", "raw"), exist_ok=True)
    tag = f"-{a.label}" if a.label else ""
    path = os.path.join(HERE, "results", "raw",
                        f"supervisor-{a.serial.replace(':', '_')}-api{device['sdk']}{tag}-{time.strftime('%Y%m%d-%H%M%S')}.json")
    with open(path, "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"\n{sum(r['checks'].values())}/{len(r['checks'])} ok  ->  {path}")
    sys.exit(0 if r["ok"] else 1)


if __name__ == "__main__":
    main()
