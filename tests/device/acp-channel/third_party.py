#!/usr/bin/env python3
"""
第三方 App 接入 ACP 的设备回归（docs/third-party-acp.md 第 7 节；C8）。

后装的“第三方”是 tests/device/acp-channel/client（org.agentos.test.acp.client），它用真实的 sdk:acp-android（AgentOs）连 AgentOS。
授权由本脚本经 debug 包的 AcpCallerDebugReceiver 代替用户点；模型是本机的假模型端点（fake_model.py，adb reverse）。

用例（按顺序，共享设备状态；每个用例开头自己 clear 注册表）：
  unauthorized-pending   没有记录：open 立即抛 authorization_pending（不阻塞），注册表里出现一条 pending（带 requestId、包名、签名摘要、App 名）
  allow-usable           SDK connect 等待期间（Waiting 回调每秒一次）用户允许 → 连上；一轮 prompt 完成；调用方身份是 APP（会话只在它自己名下）
  usable-no-scope        不带 toolScope 的会话和 toolScope=[]（零个工具）的会话都能连上、答完一轮（没有启用任何插件时）
  catalog-tools          启用测试插件后：不带 toolScope 的会话调到 echo（事件里是最终名字，结果回到模型）；toolScope 只含 stats 时 echo 调不到；
                         toolScope 含 echo 时调到，事件里是原始工具名和 ref
  denied-cooldown        用户拒绝：SDK connect 抛 DENIED；冷却内再 open 直接 denied（不再出现 pending）；冷却过后重新询问（缩短冷却参数）
  pending-timeout        没人决定：等到 ttl 按拒绝记（冷却）；卡片被撤回
  abandon                App 放弃（不再重试）：卡片被撤回，不记拒绝
  revoke-closes          已连上并有进行中的 prompt 时撤销：通道在几百毫秒内关闭，SDK 的流以 DISCONNECTED 结束；任务在 15 秒内被取消（运行时任务数归零）、
                         名额释放；之后 open 是 denied；再次允许后能开新通道并跑完一轮 prompt
  revoke-after-detach    App 自己先关了通道、任务还在跑（F7）：撤销时没有开着的通道，任务靠“见过的 UID”找到并取消
  signature-changed      换签名（apksigner 重签 client）后 open 又是 pending，且标了 signatureChanged；旧签名的通道被撤销；旧授权不继承
  shared-uid             共享 UID（一个 UID 对应两个包）一律 not_open，什么也不记；卸掉其中一个后同一个 App 按普通第三方走授权
  spoofed-name           clientInfo 里冒充别的 App 的包名没有用：注册表里只有真实的包名；工具确认卡的发起者一行和 callerPackage 是真实包名
  list-shape             listAcpCallers / AcpCallerDebugReceiver list 每项的键固定
  settings-actions       setAcpCaller 的 allowed / denied / removed、answerAuthorization 与 list 一致
  sessions               会话的完整生命周期，经真实的 Binder 和真实的 AgentOS（client 的 tp-sessions 场景）：新建、两轮、会话 ID、列表（带标题）、
                         另一条连接 load（历史完整、按序、之后能继续）、resume（不重放）、fork（新会话，带着已结束的几轮）、模式（只读 / 聊天 / 回默认，
                         load 能看到）、模型（自定义端点没有可选的，setModel 是 UNSUPPORTED）、自带 MCP 服务器（http、回环、云元数据、私网、userinfo、
                         CRLF 头、禁用头整批被拒绝且不留会话；合规但连不上的会话照常建立、状态里说明哪一个）、close 保留会话、delete 之后和从没有过一样
                         都是 SESSION_NOT_FOUND

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
CONSENT_RECEIVER = APP_PKG + "/.agent.ConsentDebugReceiver"
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
        r = self._caller(op, timeout, **extras)
        # The debug `allow --pkg` looks the package up by NAME. Android hides a package from AgentOS until that package has talked to it
        # (and a reinstall forgets that), so right after the suite reinstalled the client the lookup fails with not_found. Let the client call
        # open() once (that also leaves a pending entry, which `allow` then answers) and try again. The product path is by uid and unaffected.
        if op == "allow" and r.get("ok") is False and "not_found" in json.dumps(r) and extras.get("pkg") == CLIENT_PKG:
            self.scenario("tp-open", {}, timeout=60)
            r = self._caller(op, timeout, **extras)
        return r

    def _caller(self, op, timeout=60, **extras):
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

    def consent(self, op, **extras):
        """ConsentDebugReceiver (debug build, in :agent): mode / pending / respond."""
        parts = ["am", "broadcast", "-n", CONSENT_RECEIVER, "--es", "op", op] + [x for k, v in extras.items() for x in ("--es", k, str(v))]
        out = self.adb.sh(" ".join(shlex.quote(p) for p in parts), timeout=60, check=False)
        m = re.search(r'data="(.*)"\s*$', out, re.S)
        try:
            return json.loads(m.group(1)) if m else {"ok": False, "raw": out[-200:]}
        except ValueError:
            return {"ok": False, "error": "bad json", "raw": m.group(1)[:300]}

    def reset(self, cooldown_ms=None, ttl_ms=None):
        self.caller("clear")
        extras = {}
        if cooldown_ms is not None:
            extras["cooldownMs"] = cooldown_ms
        if ttl_ms is not None:
            extras["ttlMs"] = ttl_ms
        self.caller("config", **extras)

    def scenario(self, name, args=None, timeout=150, want_phase=None, activity=CLIENT_ACTIVITY):
        run_id = f"{name}-{int(time.time() * 1000) % 100000}"
        self.adb.run("logcat", "-c", check=False)
        payload = json.dumps(args or {}, separators=(",", ":"))
        self.adb.sh(f"am start -W -n {activity} --es scenario {name} --es run {run_id} --es args '{payload}'", timeout=60)
        return acp.collect_result(self.adb, run_id, timeout, want_phase, not_phase=None if want_phase else "ready")

    def start_scenario(self, name, args=None):
        """不等结果：返回 run_id，之后用 [result] 取。"""
        run_id = f"{name}-{int(time.time() * 1000) % 100000}"
        self.adb.run("logcat", "-c", check=False)
        payload = json.dumps(args or {}, separators=(",", ":"))
        self.adb.sh(f"am start -W -n {CLIENT_ACTIVITY} --es scenario {name} --es run {run_id} --es args '{payload}'", timeout=60)
        return run_id

    def result(self, run_id, timeout=150, want_phase=None):
        # without a wanted phase: the FINAL result. tp-hold logs a "ready" record first; taking it as the result raced with the final one
        return acp.collect_result(self.adb, run_id, timeout, want_phase, not_phase=None if want_phase else "ready")

    def first_record(self, run_id, timeout=60):
        """The first record of a run: the "ready" phase, or, when the scenario failed before getting there, its final record with the error."""
        return acp.collect_result(self.adb, run_id, timeout)

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

    def running_tasks(self):
        """AgentOS :agent 里还在跑 / 排队的任务数（in-app 的 desktop-status 场景读 runtimeStatus）。"""
        r = acp.run_one(self.adb, "tp-status", INAPP_ACTIVITY, "desktop-status", {}, 60) or {}
        return (r.get("runtime") or {}).get("tasks")


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


PLUGIN_PKG = "org.agentos.test.mcp.plugin"
PLUGIN_APK = os.path.join(REPO, "tests", "device", "mcp-plugin", "plugin", "build", "outputs", "apk", "debug", "plugin-debug.apk")
EXT_RECEIVER = APP_PKG + "/.ext.ExtensionDebugReceiver"
ECHO = "mcp__mcptest__test__echo"
STATS = "mcp__mcptest__test__stats"


def ext(c, op, **extras):
    parts = ["am", "broadcast", "-n", EXT_RECEIVER, "--es", "op", op]
    for k, v in extras.items():
        parts += (["--el", k, str(v)] if isinstance(v, int) and not isinstance(v, bool) else ["--es", k, str(v)])
    out = c.adb.sh(" ".join(shlex.quote(p) for p in parts), timeout=90, check=False)
    m = re.search(r'data="(.*)"\s*$', out, re.S)
    return json.loads(m.group(1)) if m else {"ok": False, "raw": out[-200:]}


def case_catalog_tools(c):
    """
    第三方 App 的会话能用目录里的工具（设计决定 2026-10-08：默认放开，toolScope 是调用方自己的选择）：
    不带 toolScope 的会话调到 echo，经过 Broker 的确认规则；带 toolScope 只含 stats 的会话请求 echo 时，echo 对模型就像不存在。
    """
    if not os.path.exists(PLUGIN_APK):
        return {"ok": True, "skipped": True, "summary": "skipped (the test plugin APK is not built)"}
    c.hold_agent()
    c.ensure_fake_model()
    c.reset()
    c.caller("allow", pkg=CLIENT_PKG)
    acp.install(c.adb, PLUGIN_APK)
    plugins = ext(c, "list").get("plugins") or []
    pid = next((p["id"] for p in plugins if p.get("packageName") == PLUGIN_PKG), None)
    if pid is None:
        return {"ok": False, "error": "the test plugin was not discovered"}
    ext(c, "enable", id=pid)
    ext(c, "wait_catalog", name=ECHO, timeoutMs=20000)
    # echo 设为 always，不需要有人点确认（确认规则本身由 A 的对照测试和 C7b 的 e2e 覆盖）
    ext(c, "approval_by_source", plugin="mcptest", server="test", tool="echo", mode="always")
    time.sleep(0.5)
    script = json.dumps({"chunks": 2, "intervalMs": 0, "tool": ECHO, "toolInput": {"text": "from-a-third-party"}}, separators=(",", ":"))
    open_scope = c.scenario("tp-connect", {"prompt": script, "promptTimeoutMs": 90000}, timeout=150) or {}
    scoped = c.scenario("tp-connect", {"prompt": script, "scope": "mcptest:stats", "promptTimeoutMs": 90000}, timeout=150) or {}
    exact = c.scenario("tp-connect", {"prompt": script, "scope": "mcptest:echo", "promptTimeoutMs": 90000}, timeout=150) or {}
    ext(c, "approval_by_source", plugin="mcptest", server="test", tool="echo", mode="")
    ext(c, "disable", id=pid)

    def statuses(r):
        return [e["status"] for e in r.get("toolEvents") or []]

    def results(r):
        return r.get("text") or ""
    checks = {
        "noScopeCompleted": "COMPLETED" in statuses(open_scope),
        "noScopeShowsFinalName": any(e.get("tool") == ECHO and e.get("ref") is None for e in open_scope.get("toolEvents") or []),
        "noScopeResultReachedModel": "from-a-third-party" in results(open_scope),
        "scopeWithoutEchoBlocksIt": "COMPLETED" not in statuses(scoped),
        "scopeWithEchoCompletes": "COMPLETED" in statuses(exact),
        "scopedEventShowsOriginalName": any(e.get("tool") == "echo" and e.get("ref") == "mcptest:echo" for e in exact.get("toolEvents") or []),
    }
    return verdict(checks, f"noScope={statuses(open_scope)} scopedToStats={statuses(scoped)} scopedToEcho={statuses(exact)}",
                   noScope=open_scope, scopedToStats=scoped, scopedToEcho=exact)


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


def new_revocations(c, before, pkg=CLIENT_PKG):
    """The revoke records (debug `list` -> revocations, in memory in :agent) for [pkg] that were not there in [before]."""
    seen = {x.get("at") for x in before}
    return [x for x in (c.caller("list").get("revocations") or []) if x.get("packageName") == pkg and x.get("at") not in seen]


def case_revoke_closes(c):
    c.hold_agent()
    c.ensure_fake_model()
    c.reset()
    c.caller("allow", pkg=CLIENT_PKG)
    # 50 s of model output: it cannot end by itself inside the 15 s window below, so tasks == 0 can only come from the cancel
    run_id = c.start_scenario("tp-hold", {"holdMs": 60000, "prompt": '{"chunks":1000,"intervalMs":50}'})
    first = c.first_record(run_id, timeout=60) or {}
    ready = first if first.get("phase") == "ready" else None
    time.sleep(2.0)
    before = c.channels().get("thirdParty")
    tasks_before = c.running_tasks()
    active_before = ((c.entry() or {}).get("usage") or {}).get("activeTasks")
    records_before = c.caller("list").get("revocations") or []
    t0 = time.time()
    rev = c.caller("revoke", pkg=CLIENT_PKG)
    closed = c.wait_for(lambda: c.channels().get("thirdParty") == 0, timeout=10, step=0.1)
    server_ms = int((time.time() - t0) * 1000)
    # take the client's final record NOW: polling running_tasks() below starts in-app scenarios, and each one clears logcat
    r = c.result(run_id, timeout=60) or {}
    cancelled = c.wait_for(lambda: c.running_tasks() == 0, timeout=15, step=0.5)
    cancel_ms = int((time.time() - t0) * 1000)
    record = c.wait_for(lambda: new_revocations(c, records_before), timeout=15, step=0.5) or [{}]
    e_after = c.entry() or {}
    after = c.scenario("tp-open", {"expect": "denied"}, timeout=60) or {}
    # the user allows it again: a new channel opens and a prompt runs, so the "one prompt at a time" slot of the revoked task was freed
    c.caller("allow", pkg=CLIENT_PKG)
    again = c.scenario("tp-connect", {"prompt": "after the revoke", "promptTimeoutMs": 60000}, timeout=120) or {}
    rec = record[-1]
    checks = {
        "channelWasOpen": ready is not None and before == 1,
        "taskWasRunning": isinstance(tasks_before, int) and tasks_before >= 1 and (active_before or 0) >= 1,
        "revokeAccepted": rev.get("ok") is True and (rev.get("caller") or {}).get("state") == "denied",
        "serverClosedChannelAtOnce": bool(closed) and server_ms < 3000,
        "tasksCancelled": bool(cancelled),
        "cancelRecorded": rec.get("closedChannels") == 1 and len(rec.get("cancelRequested") or []) >= 1 and "error" not in rec,
        "slotFreed": (e_after.get("usage") or {}).get("activeTasks") == 0,
        "sdkSawDisconnect": r.get("ok") is True and r.get("isConnected") is False,
        "promptEndedDisconnected": r.get("promptError") in ("DISCONNECTED", None),
        "afterRevokeDenied": bool(after.get("ok")),
        "reAllowedPromptRuns": again.get("ok") is True and again.get("stopReason") == "end_turn" and again.get("promptError") is None,
    }
    return verdict(checks, f"serverClosedMs={server_ms} tasksZeroAfterMs={cancel_ms if cancelled else None} clientClosedMs={r.get('closedAfterMs')} "
                           f"promptError={r.get('promptError')} tasksBefore={tasks_before} record={rec.get('cancelRequested')} waitedMs={rec.get('waitedMs')} "
                           f"again={again.get('stopReason')} firstRecord={first if ready is None else 'ready'}", result=r, record=rec, again=again, ready=ready,
                   channelsBefore=before, tasksBefore=tasks_before, activeBefore=active_before)


def case_revoke_after_detach(c):
    """
    The app closes its channel itself while its task runs (an app that exits does this): closing a channel does not cancel the task (F7).
    Revoking later has no open channel to read the owner from; the tasks must be found through the uids the package was seen with.
    """
    c.hold_agent()
    c.ensure_fake_model()
    c.reset()
    c.caller("allow", pkg=CLIENT_PKG)
    d = c.scenario("tp-detach", {"prompt": '{"chunks":1000,"intervalMs":50}'}, timeout=90) or {}
    time.sleep(1.0)
    channels = c.channels().get("thirdParty")
    tasks_before = c.running_tasks()
    records_before = c.caller("list").get("revocations") or []
    rev = c.caller("revoke", pkg=CLIENT_PKG)
    cancelled = c.wait_for(lambda: c.running_tasks() == 0, timeout=15, step=0.5)
    record = c.wait_for(lambda: new_revocations(c, records_before), timeout=15, step=0.5) or [{}]
    rec = record[-1]
    checks = {
        "detached": d.get("ok") is True and d.get("sawOutput") is True,
        "noChannelLeft": channels == 0,
        "taskOutlivedItsChannel": isinstance(tasks_before, int) and tasks_before >= 1,
        "revokeAccepted": rev.get("ok") is True and (rev.get("caller") or {}).get("state") == "denied",
        "tasksCancelled": bool(cancelled),
        "cancelRecorded": rec.get("closedChannels") == 0 and len(rec.get("cancelRequested") or []) >= 1 and "error" not in rec,
    }
    return verdict(checks, f"channels={channels} tasksBefore={tasks_before} record={rec.get('cancelRequested')} owners={rec.get('owners')} waitedMs={rec.get('waitedMs')}",
                   detach=d, record=rec)


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
        # 换了证书的包不能覆盖安装（签名不一致）：先卸载再装。注册表按（包名，签名摘要）记，卸载不动它——
        # 所以重装后 AgentOS 看到的是“同一个包名、另一个签名”，这正是要测的情形
        c.adb.run("uninstall", CLIENT_PKG, check=False, timeout=60)
        inst = c.adb.run("install", "-t", apk, timeout=240, check=False)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    # 卸载杀掉 client 的进程，旧通道随之关闭。再 open：签名变了，没有继承旧授权
    time.sleep(2)
    r = c.scenario("tp-open", {"expect": "authorization_pending"}, timeout=60) or {}
    listing = c.caller("list", pkg=CLIENT_PKG)
    e = c.entry() or {}
    changed = (listing.get("signatureChanged") or {}).get(CLIENT_PKG)
    checks = {
        "installed": "Success" in inst,
        "askedAgain": bool(r.get("ok")),
        "pendingNotAllowed": e.get("state") == "pending",
        "flaggedSignatureChanged": changed is True,
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
        r = c.scenario("tp-open", {"expect": "not_open"}, timeout=60, activity=SHARED_ACTIVITY) or {}
        remembered = c.caller("list").get("callers") or []
        # 对照：两个包里只剩一个时（卸掉 B），同一个 App 就不再共享 UID，按普通第三方走授权
        c.adb.run("uninstall", SHARED_B, check=False, timeout=60)
        alone = c.scenario("tp-open", {}, timeout=60, activity=SHARED_ACTIVITY) or {}
    finally:
        for pkg in (SHARED_A, SHARED_B):
            c.adb.run("uninstall", pkg, check=False, timeout=60)
        shutil.rmtree(work, ignore_errors=True)
    checks = {
        "sharedUidRefused": bool(r.get("ok")) and r.get("outcome") == "not_open",
        "nothingRemembered": not any(x["packageName"] in (SHARED_A, SHARED_B) for x in remembered),
        "aloneIsAskedNormally": alone.get("outcome") == "authorization_pending",
    }
    return verdict(checks, f"shared={r.get('outcome')} alone={alone.get('outcome')} uid={found[0]}", shared=r, alone=alone)


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
    # the confirmation card for a tool call by this app names the REAL package (the one resolved from the uid), never what the app said
    card = consent_card_of_third_party(c, "org.agentos.sample.notes")
    if card is not None:
        view = card.get("view") or {}
        checks.update({
            "cardSeen": bool(view),
            "cardCallerIsApp": view.get("callerKind") == "APP",
            "cardCallerPackageIsReal": view.get("callerPackage") == CLIENT_PKG,
            "initiatorLineNamesRealPackage": CLIENT_PKG in (view.get("initiatorLine") or ""),
            "cardHasNoClaimedName": "org.agentos.sample.notes" not in json.dumps(view),
        })
    note = "" if card is not None else " (card checks skipped: the test plugin APK is not built)"
    return verdict(checks, f"packages={pkgs} claimed={r.get('claimed')} initiator={((card or {}).get('view') or {}).get('initiatorLine')!r}{note}",
                   spoof=r, card=card)


def consent_card_of_third_party(c, claimed):
    """
    The third-party client calls a tool that needs confirmation; read the pending card from the debug consent coordinator, deny it.
    Returns {"view": <card json>, "answered": ...}; None when the test plugin APK is not built.
    """
    if not os.path.exists(PLUGIN_APK):
        return None
    c.hold_agent()
    c.ensure_fake_model()
    acp.install(c.adb, PLUGIN_APK)
    plugins = ext(c, "list").get("plugins") or []
    pid = next((p["id"] for p in plugins if p.get("packageName") == PLUGIN_PKG), None)
    if pid is None:
        return {"view": None, "error": "the test plugin was not discovered"}
    ext(c, "enable", id=pid)
    ext(c, "wait_catalog", name=ECHO, timeoutMs=20000)
    ext(c, "approval_by_source", plugin="mcptest", server="test", tool="echo", mode="")  # default rule: echo asks
    c.consent("mode", mode="off")  # nobody auto-answers: the card stays pending for us to read
    script = json.dumps({"chunks": 2, "intervalMs": 0, "tool": ECHO, "toolInput": {"text": "who-is-asking"}}, separators=(",", ":"))
    run_id = c.start_scenario("tp-connect", {"prompt": script, "promptTimeoutMs": 90000})
    view = c.wait_for(lambda: next((v for v in c.consent("pending").get("pending") or [] if v.get("callerKind") == "APP"), None), timeout=40)
    answered = c.consent("respond", id=view["requestId"], choice="DENY") if view else None
    c.result(run_id, timeout=120)
    ext(c, "disable", id=pid)
    return {"view": view, "answered": answered, "claimed": claimed}


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

SHARED_A = "org.agentos.test.acp.shared.a"
SHARED_B = "org.agentos.test.acp.shared.b"
SHARED_UID = "org.agentos.test.acp.shared"
SHARED_ACTIVITY = SHARED_A + "/org.agentos.test.acp.client.ScenarioActivity"


def case_sessions(c):
    c.hold_agent()
    c.ensure_fake_model()
    c.reset()
    c.caller("allow", pkg=CLIENT_PKG)
    r = c.scenario("tp-sessions", {}, timeout=420) or {}
    checks = dict(r.get("checks") or {})
    if not checks:
        checks["scenarioReturned"] = False
    checks["scenarioOk"] = r.get("ok") is True
    # AgentOS 里不留下这个 App 的 MCP 服务器的 URL 和头（只在内存里）：整个 logcat 里搜不到测试用的令牌
    log = c.adb.run("logcat", "-d", "-b", "all", check=False)
    log_text = log if isinstance(log, str) else getattr(log, "stdout", "") or ""
    checks["tokenNotInLogcat"] = "SECRET-DEVICE-TOKEN" not in log_text
    checks["hostNotInAgentLog"] = "no-such-host.invalid" not in "\n".join(l for l in log_text.splitlines() if "AgentOS" in l)
    return verdict(checks, f"notes={json.dumps(r.get('notes') or {}, ensure_ascii=False)[:300]}", scenario=r)


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


def shared_a_apk():
    return os.path.join(HERE, "shared", "build", "outputs", "apk", "debug", "shared-debug.apk")


def build_shared_uid_apks():
    """(临时目录, A 的 APK, B 的 APK)。A 是 :shared 模块的调试包；B 用 aapt2 现场生成：只有清单、同一个 sharedUserId，用同一把调试证书签名。"""
    t = build_tools()
    sdk = os.path.expanduser("~/Library/Android/sdk/platforms")
    platforms = sorted(os.listdir(sdk)) if os.path.isdir(sdk) else []
    android_jar = next((os.path.join(sdk, p, "android.jar") for p in reversed(platforms) if os.path.exists(os.path.join(sdk, p, "android.jar"))), None)
    if t is None or android_jar is None or not os.path.exists(shared_a_apk()):
        return None
    work = tempfile.mkdtemp(prefix="c8-shared-")
    manifest = os.path.join(work, "AndroidManifest.xml")
    with open(manifest, "w", encoding="utf-8") as f:
        f.write(f'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{SHARED_B}" android:sharedUserId="{SHARED_UID}">
    <uses-sdk android:minSdkVersion="35" android:targetSdkVersion="36"/>
    <application android:label="ACP shared-uid peer" android:hasCode="false"/>
</manifest>
''')
    raw = os.path.join(work, "b-raw.apk")
    aligned = os.path.join(work, "b-aligned.apk")
    signed = os.path.join(work, "b.apk")
    subprocess.run([t["aapt2"], "link", "-o", raw, "-I", android_jar, "--manifest", manifest, "--version-code", "1", "--version-name", "1"],
                   check=True, capture_output=True)
    subprocess.run([t["zipalign"], "-f", "4", raw, aligned], check=True, capture_output=True)
    ks = os.path.expanduser("~/.android/debug.keystore")
    subprocess.run([t["apksigner"], "sign", "--ks", ks, "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android",
                    "--out", signed, aligned], check=True, capture_output=True)
    return work, shared_a_apk(), signed


CASES = [
    ("unauthorized-pending", case_unauthorized_pending), ("allow-usable", case_allow_usable), ("usable-no-scope", case_usable_no_scope),
    ("denied-cooldown", case_denied_cooldown), ("pending-timeout", case_pending_timeout), ("abandon", case_abandon),
    ("revoke-closes", case_revoke_closes), ("revoke-after-detach", case_revoke_after_detach), ("spoofed-name", case_spoofed_name), ("list-shape", case_list_shape),
    ("catalog-tools", case_catalog_tools),
    ("settings-actions", case_settings_actions), ("sessions", case_sessions), ("shared-uid", case_shared_uid), ("signature-changed", case_signature_changed),
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
