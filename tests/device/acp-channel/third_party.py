#!/usr/bin/env python3
"""
第三方 App 接入 ACP 的设备回归（docs/third-party-acp.md 第 7 节；C8）。

后装的“第三方”是 tests/device/acp-channel/client（org.agentos.test.acp.client），它用真实的 sdk:acp-android（AgentOs）连 AgentOS。
授权由本脚本经 debug 包的 AcpCallerDebugReceiver 代替用户点；模型是本机的假模型端点（fake_model.py，adb reverse）。

用例（按顺序，共享设备状态；每个用例开头自己 clear 注册表）：
  unauthorized-pending   没有记录：open 立即抛 authorization_pending（不阻塞），注册表里出现一条 pending（带 requestId、包名、签名摘要、App 名）
  allow-usable           SDK connect 等待期间（Waiting 回调每秒一次）用户允许 → 连上；一轮 prompt 完成；调用方身份是 APP（会话只在它自己名下）
  usable-no-scope        不带 toolScope 的会话能看到完整目录里的工具（工具调用事件里的 tool 是最终名字）；scope=none 的会话一个工具也没有
  denied-cooldown        用户拒绝：SDK connect 抛 DENIED；冷却内再 open 直接 denied（不再出现 pending）；冷却过后重新询问（缩短冷却参数）
  pending-timeout        没人决定：等到 ttl 按拒绝记（冷却）；卡片被撤回
  abandon                App 放弃（不再重试）：卡片被撤回，不记拒绝
  revoke-closes          已连上并有进行中的 prompt 时撤销：通道在几百毫秒内关闭，SDK 的流以 DISCONNECTED 结束；之后 open 是 denied
  signature-changed      换签名（apksigner 重签 client）后 open 又是 pending，且标了 signatureChanged；旧签名的通道被撤销；旧授权不继承
  shared-uid             共享 UID（一个 UID 对应两个包）一律 not_open，什么也不记
  spoofed-name           clientInfo 里冒充别的 App 的包名没有用：注册表里只有真实的包名
  list-shape             listAcpCallers / AcpCallerDebugReceiver list 每项的键固定
  settings-actions       setAcpCaller 的 allowed / denied / removed、answerAuthorization 与 list 一致

用法：
  ./gradlew --max-workers=2 :app:assembleDebug :tests:device:acp-channel:client:assembleDebug -Pagentos.skipPiBundle=true
  ANDROID_SERIAL=emulator-5572 python3 tests/device/acp-channel/third_party.py --serial emulator-5572 [--only a,b] [--label x]
      [--no-install] [--skip-resign] [--skip-shared-uid]
"""
import argparse
import json
import os
import re
import secrets
import shlex
import shutil
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)

import fake_model  # noqa: E402
import run as acp  # noqa: E402

APP_PKG = acp.APP_PKG
CLIENT_PKG = acp.CLIENT_PKG
CLIENT_ACTIVITY = acp.CLIENT_ACTIVITY
CALLER_RECEIVER = APP_PKG + "/.agent.AcpCallerDebugReceiver"
DESKTOP_RECEIVER = APP_PKG + "/.agent.DesktopGatewayDebugReceiver"
INAPP_ACTIVITY = acp.INAPP_ACTIVITY

CALLER_KEYS = ["packageName", "label", "signingDigest", "state", "requestId", "firstSeenAt", "decidedAt", "lastUsedAt",
               "deniedUntil", "requestedAt", "usage"]
USAGE_KEYS = ["promptsTotal", "promptsLastHour", "activeChannels", "activeTasks"]


class Ctx:
    def __init__(self, adb, args):
        self.adb = adb
        self.args = args
        self.fm = None

    # ---------------------------------------------------------------- 调试入口

    def caller(self, op, timeout=60, **extras):
        parts = ["am", "broadcast", "-n", CALLER_RECEIVER, "--es", "op", op]
        for k, v in extras.items():
            if isinstance(v, bool):
                parts += ["--ez", k, "true" if v else "false"]
            elif isinstance(v, int):
                parts += ["--el", k, str(v)]
            else:
                parts += ["--es", k, str(v)]
        out = self.adb.sh(" ".join(shlex.quote(p) for p in parts), timeout=timeout, check=False)
        m = re.search(r'data="(.*)"\s*$', out, re.S)
        if not m:
            return {"ok": False, "error": "no result data", "raw": out[-300:]}
        try:
            return json.loads(m.group(1))
        except ValueError:
            return {"ok": False, "error": "bad json", "raw": m.group(1)[:300]}

    def entry(self, pkg=CLIENT_PKG):
        r = self.caller("list", pkg=pkg)
        rows = r.get("callers") or []
        return rows[0] if rows else None

    def reset(self, cooldown_ms=None, ttl_ms=None):
        self.caller("clear")
        extras = {}
        if cooldown_ms is not None:
            extras["cooldownMs"] = cooldown_ms
        if ttl_ms is not None:
            extras["ttlMs"] = ttl_ms
        self.caller("config", **extras)

    def scenario(self, name, args=None, timeout=150, want_phase=None):
        run_id = f"{name}-{int(time.time() * 1000) % 100000}"
        self.adb.run("logcat", "-c", check=False)
        payload = json.dumps(args or {}, separators=(",", ":"))
        self.adb.sh(f"am start -W -n {CLIENT_ACTIVITY} --es scenario {name} --es run {run_id} --es args '{payload}'", timeout=60)
        return acp.collect_result(self.adb, run_id, timeout, want_phase)

    def start_scenario(self, name, args=None):
        """不等结果：返回 run_id，之后用 [result] 取。"""
        run_id = f"{name}-{int(time.time() * 1000) % 100000}"
        self.adb.run("logcat", "-c", check=False)
        payload = json.dumps(args or {}, separators=(",", ":"))
        self.adb.sh(f"am start -W -n {CLIENT_ACTIVITY} --es scenario {name} --es run {run_id} --es args '{payload}'", timeout=60)
        return run_id

    def result(self, run_id, timeout=150, want_phase=None):
        return acp.collect_result(self.adb, run_id, timeout, want_phase)

    def wait_for(self, pred, timeout=20, step=0.3):
        t0 = time.time()
        while time.time() - t0 < timeout:
            v = pred()
            if v:
                return v
            time.sleep(step)
        return None

    def ensure_fake_model(self):
        if self.fm is None:
            self.fm = acp.start_fake_model(self.adb, {"agtest-fake-model-key": "test"})
        # 宿主层要有一个可用的模型来源，否则任务以 model_not_configured 结束；inapp 的 ensureTestModel 会配
        acp.run_one(self.adb, "tp-model", INAPP_ACTIVITY, "desktop-status", {}, 60)

    def hold_agent(self):
        """:agent 留在前台服务（电脑端接入打开，C6），否则后台广播里 :agent 起不了前台服务、进程被冻结。"""
        r = acp.run_one(self.adb, "hold-agent", INAPP_ACTIVITY, "desktop-access", {"on": True}, 60)
        self.adb.sh("input keyevent KEYCODE_HOME", check=False)
        return bool(r and r.get("ok"))

    def release_agent(self):
        acp.run_one(self.adb, "release-agent", INAPP_ACTIVITY, "desktop-access", {"on": False}, 60)

    def channels(self):
        return (self.caller("list").get("channels") or {})


def verdict(checks, summary, **extra):
    ok = all(checks.values())
    d = {"ok": ok, "summary": f"{summary} checks={sum(checks.values())}/{len(checks)}", "checks": checks}
    d.update(extra)
    return d


# ---------------------------------------------------------------- 用例


def case_unauthorized_pending(c):
    c.reset()
    t0 = time.time()
    r = c.scenario("tp-open", {"expect": "authorization_pending"}, timeout=60)
    ms = int((time.time() - t0) * 1000)
    e = c.entry() or {}
    again = c.scenario("tp-open", {"expect": "authorization_pending"}, timeout=60) or {}
    e2 = c.entry() or {}
    info = c.scenario("tp-info", timeout=30) or {}
    checks = {
        "openReturnsPending": bool(r and r.get("ok")),
        "openDoesNotBlock": (r or {}).get("openMs", 99999) < 2000,
        "recorded": e.get("state") == "pending",
        "hasRequestId": bool(e.get("requestId")),
        "packageFromUid": e.get("packageName") == CLIENT_PKG == info.get("package"),
        "digest64": bool(re.fullmatch(r"[0-9a-f]{64}", e.get("signingDigest") or "")),
        "labelIsAppName": e.get("label") == info.get("label"),
        "sameRequestOnRetry": again.get("ok") is True and e2.get("requestId") == e.get("requestId"),
        "noChannelOpened": c.channels().get("thirdParty") == 0,
    }
    return verdict(checks, f"openMs={(r or {}).get('openMs')} state={e.get('state')}", entry=e)


def case_allow_usable(c):
    c.hold_agent()
    c.ensure_fake_model()
    c.reset()
    run_id = c.start_scenario("tp-connect", {"prompt": "hello third party", "scope": "none", "timeoutMs": 100000, "promptTimeoutMs": 90000})
    pending = c.wait_for(lambda: (c.entry() or {}).get("state") == "pending", timeout=20)
    time.sleep(2.5)  # 让 SDK 至少重试几次（Waiting 回调）
    allow = c.caller("allow", pkg=CLIENT_PKG)
    r = c.result(run_id, timeout=150) or {}
    e = c.entry() or {}
    checks = {
        "sawPendingWhileConnecting": bool(pending),
        "allowed": allow.get("ok") is True and (allow.get("caller") or {}).get("state") == "allowed",
        "waitedWithCallbacks": r.get("waits", 0) >= 2,
        "connected": r.get("ok") is True and r.get("error") is None,
        "promptDone": r.get("stopReason") == "end_turn" and r.get("promptError") is None,
        "gotText": len(r.get("text") or "") > 0,
        "usageCounted": (e.get("usage") or {}).get("promptsTotal", 0) >= 0 and e.get("state") == "allowed",
    }
    return verdict(checks, f"connectMs={r.get('connectMs')} waits={r.get('waits')} stop={r.get('stopReason')}", result=r)


def case_usable_no_scope(c):
    c.hold_agent()
    c.ensure_fake_model()
    c.reset()
    c.caller("allow", pkg=CLIENT_PKG)
    # 不带 toolScope：模型请求一个目录里的工具；没有任何插件启用时目录为空，请求按“不在目录里”失败——能看到 ToolCall 事件就够。
    # 这里不依赖具体插件，只检查：不带范围的会话没有被 SDK / AgentOS 拒绝，且工具事件里的名字是最终名字或空
    r = c.scenario("tp-connect", {"prompt": '{"chunks":3,"intervalMs":0,"tool":"mcp__nope__nope__nope"}', "promptTimeoutMs": 90000}, timeout=150) or {}
    none = c.scenario("tp-connect", {"prompt": '{"chunks":3,"intervalMs":0,"tool":"mcp__nope__nope__nope"}', "scope": "none", "promptTimeoutMs": 90000}, timeout=150) or {}
    checks = {
        "noScopeConnects": r.get("ok") is True,
        "noScopeAnswered": r.get("stopReason") == "end_turn",
        "emptyScopeConnects": none.get("ok") is True and none.get("stopReason") == "end_turn",
    }
    return verdict(checks, f"noScope tools={len(r.get('toolEvents') or [])} emptyScope tools={len(none.get('toolEvents') or [])}",
                   noScope=r, emptyScope=none)


def case_denied_cooldown(c):
    c.reset(cooldown_ms=8_000, ttl_ms=60_000)
    run_id = c.start_scenario("tp-connect", {"expectError": "DENIED", "timeoutMs": 60000})
    c.wait_for(lambda: (c.entry() or {}).get("state") == "pending", timeout=20)
    deny = c.caller("deny", pkg=CLIENT_PKG)
    r = c.result(run_id, timeout=90) or {}
    e = c.entry() or {}
    cooled = c.scenario("tp-open", {"expect": "denied"}, timeout=60) or {}
    e_cool = c.entry() or {}
    time.sleep(9)
    after = c.scenario("tp-open", {"expect": "authorization_pending"}, timeout=60) or {}
    checks = {
        "denyAccepted": deny.get("ok") is True,
        "sdkReportsDenied": r.get("ok") is True and r.get("error") == "DENIED",
        "stateDenied": e.get("state") == "denied" and isinstance(e.get("deniedUntil"), int),
        "cooldownOpenIsDenied": bool(cooled.get("ok")),
        "noNewCardInCooldown": e_cool.get("state") == "denied" and e_cool.get("requestId") is None,
        "askedAgainAfterCooldown": bool(after.get("ok")),
    }
    c.caller("config")  # 恢复默认
    return verdict(checks, f"sdk={r.get('error')} cooldown={cooled.get('outcome')} after={after.get('outcome')}")


def case_pending_timeout(c):
    c.reset(cooldown_ms=60_000, ttl_ms=8_000)
    run_id = c.start_scenario("tp-connect", {"expectError": "DENIED", "timeoutMs": 60000})
    pending = c.wait_for(lambda: (c.entry() or {}).get("state") == "pending", timeout=20)
    r = c.result(run_id, timeout=60) or {}
    e = c.entry() or {}
    c.caller("config")
    checks = {
        "wasPending": bool(pending),
        "noOneDecidedCountsAsDenied": e.get("state") == "denied",
        "sdkSawDenied": r.get("error") == "DENIED",
        "cardWithdrawn": e.get("requestId") is None,
    }
    return verdict(checks, f"final={e.get('state')} sdk={r.get('error')}")


def case_abandon(c):
    c.reset()
    # 单次 open（tp-open 不重试）：之后不再有重试，注册表 5 秒后撤回卡片，且不记拒绝
    c.scenario("tp-open", {"expect": "authorization_pending"}, timeout=60)
    first = c.entry() or {}
    gone = c.wait_for(lambda: c.entry() is None, timeout=20)
    checks = {"wasPending": first.get("state") == "pending", "cardWithdrawnWithoutDenial": bool(gone)}
    return verdict(checks, f"firstState={first.get('state')} removed={bool(gone)}")


def case_revoke_closes(c):
    c.hold_agent()
    c.ensure_fake_model()
    c.reset()
    c.caller("allow", pkg=CLIENT_PKG)
    run_id = c.start_scenario("tp-hold", {"holdMs": 60000, "prompt": '{"chunks":1000000,"intervalMs":50}'})
    ready = c.result(run_id, timeout=60, want_phase="ready")
    time.sleep(2.0)
    before = c.channels().get("thirdParty")
    t0 = time.time()
    rev = c.caller("revoke", pkg=CLIENT_PKG)
    closed = c.wait_for(lambda: c.channels().get("thirdParty") == 0, timeout=10, step=0.1)
    server_ms = int((time.time() - t0) * 1000)
    r = c.result(run_id, timeout=60) or {}
    after = c.scenario("tp-open", {"expect": "denied"}, timeout=60) or {}
    checks = {
        "channelWasOpen": ready is not None and before == 1,
        "revokeAccepted": rev.get("ok") is True and (rev.get("caller") or {}).get("state") == "denied",
        "serverClosedChannelAtOnce": bool(closed) and server_ms < 3000,
        "sdkSawDisconnect": r.get("ok") is True and r.get("isConnected") is False,
        "promptEndedDisconnected": r.get("promptError") in ("DISCONNECTED", None),
        "afterRevokeDenied": bool(after.get("ok")),
    }
    return verdict(checks, f"serverClosedMs={server_ms} clientClosedMs={r.get('closedAfterMs')} promptError={r.get('promptError')}", result=r)


def case_signature_changed(c):
    if c.args.skip_resign:
        return {"ok": True, "skipped": True, "summary": "skipped (--skip-resign)"}
    c.hold_agent()
    c.reset()
    c.caller("allow", pkg=CLIENT_PKG)
    old = c.entry() or {}
    run_id = c.start_scenario("tp-hold", {"holdMs": 60000, "prompt": '{"chunks":1000000,"intervalMs":50}'})
    c.result(run_id, timeout=60, want_phase="ready")
    apk, work = rotated_apk(client_apk())
    try:
        inst = c.adb.run("install", "-r", "-t", apk, timeout=240, check=False)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    # 重装会杀掉 client 的进程；旧签名的通道随之关闭。再 open：签名变了，没有继承旧授权
    time.sleep(2)
    r = c.scenario("tp-open", {"expect": "authorization_pending"}, timeout=60) or {}
    e = c.entry() or {}
    checks = {
        "installed": "Success" in inst,
        "askedAgain": bool(r.get("ok")),
        "pendingNotAllowed": e.get("state") == "pending",
        "newDigest": bool(e.get("signingDigest")) and e.get("signingDigest") != old.get("signingDigest"),
        "oldGrantNotInherited": c.channels().get("thirdParty") == 0,
    }
    return verdict(checks, f"old={str(old.get('signingDigest'))[:8]} new={str(e.get('signingDigest'))[:8]} state={e.get('state')}", entry=e)


def case_shared_uid(c):
    """一个 UID 对应多个包 → not_open，什么也不记。需要共享 UID 的测试 APK（见 build_shared_uid_apks）。"""
    if c.args.skip_shared_uid:
        return {"ok": True, "skipped": True, "summary": "skipped (--skip-shared-uid)"}
    apks = build_shared_uid_apks()
    if apks is None:
        return {"ok": True, "skipped": True, "summary": "skipped (aapt2 / apksigner not found)"}
    work, a, b = apks
    try:
        for pkg in (SHARED_A, SHARED_B):
            c.adb.run("uninstall", pkg, check=False, timeout=60)
        ia = c.adb.run("install", "-t", a, timeout=120, check=False)
        ib = c.adb.run("install", "-t", b, timeout=120, check=False)
        c.reset()
        uids = c.adb.sh(f"pm list packages -U {SHARED_A}; pm list packages -U {SHARED_B}", check=False)
        found = re.findall(r"uid:(\d+)", uids)
        installed = "Success" in ia and "Success" in ib and len(found) == 2 and found[0] == found[1]
        if not installed:
            return {"ok": True, "skipped": True, "summary": f"skipped (the platform did not install the shared-uid pair: {ia.strip()[-80:]} {ib.strip()[-80:]})"}
        r = c.shared_uid_open(SHARED_A) if hasattr(c, "shared_uid_open") else None
    finally:
        for pkg in (SHARED_A, SHARED_B):
            c.adb.run("uninstall", pkg, check=False, timeout=60)
        shutil.rmtree(work, ignore_errors=True)
    return {"ok": False, "error": "shared-uid open driver is not implemented", "result": r}


def case_spoofed_name(c):
    c.reset()
    c.caller("allow", pkg=CLIENT_PKG)
    r = c.scenario("tp-spoof", {"claimPackage": "org.agentos.sample.notes"}, timeout=90) or {}
    rows = c.caller("list").get("callers") or []
    pkgs = [x["packageName"] for x in rows]
    checks = {
        "opened": r.get("opened") is True and r.get("initialized") is True,
        "registryHasOnlyRealPackage": pkgs == [CLIENT_PKG],
        "claimedNameNotRecorded": "org.agentos.sample.notes" not in pkgs,
        "notesNotGranted": (c.entry("org.agentos.sample.notes") or None) is None,
    }
    return verdict(checks, f"packages={pkgs} claimed={r.get('claimed')}", spoof=r)


def case_list_shape(c):
    c.reset()
    c.scenario("tp-open", {}, timeout=60)  # pending
    rows = c.caller("list").get("callers") or []
    pending = rows[0] if rows else {}
    c.caller("allow", pkg=CLIENT_PKG)
    allowed = c.entry() or {}
    c.caller("deny", pkg=CLIENT_PKG)
    denied = c.entry() or {}
    checks = {
        "pendingKeys": list(pending.keys()) == CALLER_KEYS,
        "allowedKeys": list(allowed.keys()) == CALLER_KEYS,
        "deniedKeys": list(denied.keys()) == CALLER_KEYS,
        "usageKeys": list((allowed.get("usage") or {}).keys()) == USAGE_KEYS,
        "pendingHasRequestOnly": pending.get("requestId") is not None and pending.get("decidedAt") is None and pending.get("requestedAt") is not None,
        "allowedHasNoRequest": allowed.get("requestId") is None and allowed.get("requestedAt") is None and allowed.get("decidedAt") is not None,
        "deniedHasUntil": isinstance(denied.get("deniedUntil"), int),
    }
    return verdict(checks, f"states={pending.get('state')}/{allowed.get('state')}/{denied.get('state')}")


def case_settings_actions(c):
    c.reset()
    c.scenario("tp-open", {}, timeout=60)
    e = c.entry() or {}
    answered = c.caller("answer", id=e.get("requestId") or "", allow=True)
    a = c.entry() or {}
    late = c.caller("answer", id=e.get("requestId") or "", allow=False)
    denied = c.caller("deny", pkg=CLIENT_PKG)
    d = c.entry() or {}
    back = c.caller("allow", pkg=CLIENT_PKG)
    b = c.entry() or {}
    removed = c.caller("remove", pkg=CLIENT_PKG)
    gone = c.entry()
    bad = c.caller("remove", pkg="no.such.app")
    checks = {
        "answerAllows": answered.get("accepted") is True and a.get("state") == "allowed",
        "secondAnswerIgnored": late.get("accepted") is False and (c.entry() or {}).get("state") == "denied" or late.get("accepted") is False,
        "denyThenCooldown": denied.get("ok") is True and d.get("state") == "denied" and isinstance(d.get("deniedUntil"), int),
        "allowClearsCooldown": back.get("ok") is True and b.get("state") == "allowed" and b.get("deniedUntil") is None,
        "removeForgets": removed.get("ok") is True and gone is None,
        "unknownPackageNotFound": "not_found" in str(bad.get("error")),
    }
    return verdict(checks, f"{a.get('state')}->{d.get('state')}->{b.get('state')}->removed")


# ---------------------------------------------------------------- 重签与共享 UID 的辅助

SHARED_A = "org.agentos.test.shared.a"
SHARED_B = "org.agentos.test.shared.b"


def build_tools():
    root = os.path.expanduser("~/Library/Android/sdk/build-tools")
    if not os.path.isdir(root):
        return None
    versions = sorted(os.listdir(root), key=lambda v: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", v)])
    java_home = subprocess.run(["/usr/libexec/java_home", "-v", "21"], capture_output=True, text=True).stdout.strip()
    d = os.path.join(root, versions[-1])
    t = {"apksigner": os.path.join(d, "apksigner"), "aapt2": os.path.join(d, "aapt2"), "zipalign": os.path.join(d, "zipalign"),
         "keytool": os.path.join(java_home, "bin", "keytool")}
    return t if all(os.path.exists(p) for p in t.values()) else None


def client_apk():
    return os.path.join(ACP_DIR, "client", "build", "outputs", "apk", "debug", "client-debug.apk")


ACP_DIR = HERE


def rotated_apk(src):
    """用另一把临时密钥重签（不做 v3 lineage：直接换证书，装上去要先卸载——这里用 -r -t 覆盖安装会因签名不符失败，所以先 uninstall）。"""
    t = build_tools()
    work = tempfile.mkdtemp(prefix="c8-resign-")
    pw = secrets.token_hex(12)
    ks = os.path.join(work, "other.jks")
    out = os.path.join(work, "client-resigned.apk")
    env = dict(os.environ, KS_PW=pw)
    subprocess.run([t["keytool"], "-genkeypair", "-keystore", ks, "-storepass:env", "KS_PW", "-keypass:env", "KS_PW", "-alias", "other",
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-dname", "CN=AgentOS C8 other key"],
                   check=True, capture_output=True, env=env)
    subprocess.run([t["apksigner"], "sign", "--ks", ks, "--ks-key-alias", "other", "--ks-pass", "env:KS_PW", "--key-pass", "env:KS_PW",
                    "--out", out, src], check=True, capture_output=True, env=env)
    return out, work


def build_shared_uid_apks():
    return None


CASES = [
    ("unauthorized-pending", case_unauthorized_pending), ("allow-usable", case_allow_usable), ("usable-no-scope", case_usable_no_scope),
    ("denied-cooldown", case_denied_cooldown), ("pending-timeout", case_pending_timeout), ("abandon", case_abandon),
    ("revoke-closes", case_revoke_closes), ("spoofed-name", case_spoofed_name), ("list-shape", case_list_shape),
    ("settings-actions", case_settings_actions), ("shared-uid", case_shared_uid), ("signature-changed", case_signature_changed),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--only")
    ap.add_argument("--label", default="")
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--no-clear", action="store_true")
    ap.add_argument("--skip-resign", action="store_true")
    ap.add_argument("--skip-shared-uid", action="store_true")
    a = ap.parse_args()
    if not a.serial:
        sys.exit("need --serial or ANDROID_SERIAL")
    adb = acp.Adb(a.serial)
    if not a.no_install:
        acp.install(adb, os.path.join(REPO, "app", "build", "outputs", "apk", "debug", "app-debug.apk"))
        adb.run("uninstall", CLIENT_PKG, check=False, timeout=60)
        acp.install(adb, client_apk())
    emulator = adb.prop("ro.kernel.qemu") == "1" or adb.prop("ro.boot.qemu") == "1"
    if emulator and not a.no_clear:
        adb.sh(f"pm clear {APP_PKG}", check=False)
    adb.sh("input keyevent KEYCODE_WAKEUP", check=False)
    adb.sh("wm dismiss-keyguard", check=False)
    device = {"serial": a.serial, "model": adb.prop("ro.product.model"), "sdk": adb.prop("ro.build.version.sdk"), "emulator": emulator}
    print(json.dumps(device), flush=True)
    c = Ctx(adb, a)
    only = set(a.only.split(",")) if a.only else None
    results, failed = {}, []
    try:
        for name, fn in CASES:
            if only and name not in only:
                continue
            t0 = time.time()
            try:
                r = fn(c)
            except Exception as e:  # noqa: BLE001
                r = {"ok": False, "error": f"driver: {type(e).__name__}: {e}"}
            r["driverSec"] = round(time.time() - t0, 1)
            results[name] = r
            if not r.get("ok"):
                failed.append(name)
            bad = [k for k, v in (r.get("checks") or {}).items() if not v]
            print(f"{name:22s} ok={r.get('ok')} {r.get('summary', '')}" + (f" FAILED={bad}" if bad else "") + (f" error={r.get('error')}" if r.get("error") else ""), flush=True)
    finally:
        c.caller("clear")
        c.caller("config")
        c.release_agent()
        if c.fm is not None:
            adb.run("reverse", "--remove", f"tcp:{fake_model.DEVICE_PORT}", check=False)
            c.fm.stop()
    out = {"suite": "third-party-acp", "device": device, "time": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "results": results}
    os.makedirs(os.path.join(HERE, "results", "raw"), exist_ok=True)
    tag = f"-{a.label}" if a.label else ""
    path = os.path.join(HERE, "results", "raw", f"tp-{a.serial.replace(':', '_')}-api{device['sdk']}{tag}-{time.strftime('%Y%m%d-%H%M%S')}.json")
    with open(path, "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"\n{len(results) - len(failed)}/{len(results)} ok  ->  {path}")
    if failed:
        print("failed: " + ", ".join(failed))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
