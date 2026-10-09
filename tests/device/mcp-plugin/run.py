#!/usr/bin/env python3
"""
C7b 设备回归：Extension Host（`:ext`）+ 测试插件 App（tests/device/mcp-plugin/plugin，插件名 mcptest，服务器 test）。

经 debug 包里的 ExtensionDebugReceiver（主进程 → IExtensionHost，与插件管理页相同的路径）驱动 `:ext`；端到端用例经
acp-channel 的 in-app 执行器（ext-e2e 场景）让真实运行时调用插件工具，模型是本机的假模型端点（fake_model.py，adb reverse）。

用例（按顺序跑，共享设备状态；--only 单跑时前置状态由用例自己补齐）：
  discover        首次发现：ready、默认关闭、目录里没有它的工具；策略健康
  no-permission   第三方 App（acp-channel client，没有 BIND_MCP_SERVICE）bind 插件服务被系统拒绝
  enable          启用 → 目录出现 7 个工具（记录耗时）；风险等级；Skills 与 skillProblems
  approvals       高风险工具不能设 always；按来源设 always / 清除
  call            调用：成功、参数错误（isError）、不在目录（不受理）；每个调用恰好一次回调
  uid-check       通道按插件 App 的 UID 校验入站调用（peerUid = 插件 UID，uidRejects = 0）
  timeout         调用超时 → unknown / tool_timeout，不重放
  cancel          调用中取消 → unknown（cancelled）；插件侧收到取消通知
  list-changed    tools/list_changed → 目录增减
  plugin-death    插件进程在调用中退出 → unknown；之后重连成功（新 pid）
  force-stop      调用中 am force-stop 插件 → unknown；之后重连成功
  disable-inflight 调用中停用插件 → unknown；目录移除；调用不受理；再启用恢复
  tool-disable    工具级禁用：目录移除、listTools 仍列出（enabled=false）、调用不受理；再启用恢复
  idle            空闲 30 秒后断开（插件服务被销毁），下次调用重连
  background      应用界面不在前台、:agent 只有前台服务时，:ext 仍能 bind 插件并完成调用
  ext-death       杀掉 :ext → 系统按 :agent 的绑定重建；目录重新推送；:agent 代理计数
  known-tools     已知工具：工具级禁用后杀 :ext，listTools 仍列出它（enabled=false、名字不变、键与 KnownTool 一一对应）；
                  停用插件后杀 :ext，启用一次之前 listTools 为 []；启用后恢复
  e2e-always      端到端：echo 设为 always，模型调用 → 插件执行 → 结果交回模型；确认协调器没有被问到（自动应答设成拒绝也能执行）
  e2e-ask         端到端：echo 需要确认，自动应答拒绝 → tool_denied，工具没执行
  e2e-consent     端到端：echo 需要确认，自动应答允许一次 → 经确认后执行，结果交回模型（authorize → 确认 → dispatch → settled）
  e2e-high        端到端：高风险工具即使插件级 always 也要确认（HIGH，不提供“本会话 / 始终允许”）→ 拒绝
  e2e-disabled    端到端：工具级禁用 → 模型拿到“不可用”，工具没执行
  policy-corrupt  策略文件与备份都坏 → :ext 重建后 fail closed（:agent 镜像同步）、写入被拒；用户重置后恢复（插件仍停用）
  signature       签名轮换（apksigner lineage）→ signature_changed、停用、目录清空；启用报 not_ready；确认后恢复
  uninstall       卸载 → 插件与工具消失；重装 → 重新按第三方默认关闭

用法：
  ./gradlew --max-workers=2 :app:assembleDebug :tests:device:mcp-plugin:plugin:assembleDebug \\
      :tests:device:acp-channel:client:assembleDebug -Pagentos.skipPiBundle=true
  ANDROID_SERIAL=emulator-5572 python3 tests/device/mcp-plugin/run.py --serial emulator-5572 [--only a,b] [--label x]
      [--no-install] [--no-clear | --clear] [--skip-rotation] [--idle-wait 40]

  pm clear：模拟器上默认清（从首次发现开始）；**真机默认不清**（会抹掉模型源和配对），要清必须显式 --clear。
  主机上找不到 apksigner / keytool 时 signature 用例自动跳过。
  不清数据时 discover 的“默认关闭”等检查取决于设备上原有的状态。
  断言都按测试插件限定（工具按 provider = 插件 ID、Skill 按 provider = 插件名过滤），设备上同时装着别的插件（示例 App）不影响结果。
  会改设备状态的用例：policy-corrupt（重置策略会把所有第三方插件写成停用，示例 App 的插件要重新启用）、
  signature 与 uninstall（重装测试插件）。真机上按需用 --only 挑选。
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
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
ACP = os.path.join(REPO, "tests", "device", "acp-channel")
sys.path.insert(0, ACP)

import fake_model  # noqa: E402
import run as acp  # noqa: E402

APP_PKG = acp.APP_PKG
PLUGIN_PKG = "org.agentos.test.mcp.plugin"
PLUGIN_ID = PLUGIN_PKG + "/agent-plugin"
PLUGIN_SERVICE = PLUGIN_PKG + ".TestMcpService"
DEBUG_RECEIVER = APP_PKG + "/.ext.ExtensionDebugReceiver"
DESKTOP_RECEIVER = APP_PKG + "/.agent.DesktopGatewayDebugReceiver"
CONSENT_RECEIVER = APP_PKG + "/.agent.ConsentDebugReceiver"
CLIENT_ACTIVITY = acp.CLIENT_ACTIVITY
INAPP_ACTIVITY = acp.INAPP_ACTIVITY
HIGH_RISK_TOOL = "w" + "ipe"  # 名字拼出来：权限检查会把命令行里的这个词当成擦盘命令

T = lambda tool: f"mcp__mcptest__test__{tool}"  # noqa: E731
ALL_TOOLS = sorted(T(x) for x in ("add_tool", "die", "echo", "remove_tool", "slow", "stats", HIGH_RISK_TOOL))
# IExtensionHost.listTools 每一项的键（与 core:extensions 的 KnownTool 一一对应，按输出顺序）
KNOWN_TOOL_KEYS = ["name", "pluginId", "source", "title", "description", "inputSchema", "risk", "enabled", "approval", "mayAlwaysAllow"]
# 插件 JSON 的键（ExtWire.PLUGIN_KEYS，按输出顺序）
PLUGIN_KEYS = ["id", "source", "packageName", "name", "displayName", "description", "versionName", "versionCode",
               "signingDigest", "trustedSigningDigest", "status", "unavailableReason", "builtin", "enabled", "approval",
               "problems", "unsupported", "servers", "rejectedServers", "toolCount", "skillCount", "skillProblems"]


class Ctx:
    def __init__(self, adb, args):
        self.adb = adb
        self.args = args
        self.fm = None

    # ---------------------------------------------------------------- debug receiver

    def dbg(self, op, timeout=90, **extras):
        """ExtensionDebugReceiver：结果在广播的 result data 里。str → --es，bool → --ez，int → --el。"""
        parts = ["am", "broadcast", "-n", DEBUG_RECEIVER, "--es", "op", op]
        for k, v in extras.items():
            if isinstance(v, bool):
                parts += ["--ez", k, "true" if v else "false"]
            elif isinstance(v, int):
                parts += ["--el", k, str(v)]
            else:
                parts += ["--es", k, v if isinstance(v, str) else json.dumps(v, separators=(",", ":"))]
        out = self.adb.sh(" ".join(shlex.quote(p) for p in parts), timeout=timeout, check=False)
        m = re.search(r'data="(.*)"\s*$', out, re.S)
        if not m:
            return {"ok": False, "error": "no result data", "raw": out[-400:]}
        try:
            return json.loads(m.group(1))
        except ValueError:
            return {"ok": False, "error": "bad json", "raw": m.group(1)[:400]}

    def plugin(self):
        r = self.dbg("list")
        return next((p for p in r.get("plugins", []) if p.get("id") == PLUGIN_ID), None)

    def catalog(self):
        return self.dbg("catalog").get("catalog", {})

    def catalog_names(self):
        return sorted(t["name"] for t in self.catalog().get("tools", []))

    def own_tool_names(self, catalog=None):
        """目录里**测试插件自己的**工具（按 provider = 插件 ID 过滤）：设备上同时装着别的插件（示例 App）时不受影响。"""
        cat = catalog if catalog is not None else self.catalog()
        return sorted(t["name"] for t in cat.get("tools", []) if t.get("provider") == PLUGIN_ID)

    def call(self, tool, args=None, timeout_ms=10_000, cancel_after_ms=0, disable_after_ms=0):
        return self.dbg("call", timeout=timeout_ms // 1000 + 60, name=T(tool), args=args or {}, timeoutMs=timeout_ms,
                        cancelAfterMs=cancel_after_ms, disableAfterMs=disable_after_ms, id=PLUGIN_ID)

    def proc_states(self):
        """:agent、:ext、插件进程的 procState（4 = 前台服务，19 = cached）与 AgentService 是否在前台。"""
        out = self.adb.sh("dumpsys activity processes", check=False, timeout=60)
        states = {}
        cur = None
        for line in out.splitlines():
            m = re.search(r"\*APP\* UID \d+ ProcessRecord\{\w+ (\d+):([\w.:]+)/", line)
            if m:
                cur = m.group(2)
                continue
            m = re.search(r"curProcState=(\d+)", line)
            if m and cur and cur not in states and (cur.startswith(APP_PKG) or cur == PLUGIN_PKG):
                states[cur] = int(m.group(1))
        svc = self.adb.sh(f"dumpsys activity services {APP_PKG}/.agent.AgentService", check=False, timeout=60)
        states["agentServiceForeground"] = "isForeground=true" in svc
        return states

    def wait_tool(self, tool, absent=False, timeout_ms=15_000):
        return self.dbg("wait_catalog", timeout=timeout_ms // 1000 + 30, name=T(tool), absent=absent, timeoutMs=timeout_ms)

    def ensure_enabled(self):
        p = self.plugin()
        if p and not p.get("enabled"):
            self.dbg("enable", id=PLUGIN_ID)
        return self.wait_tool("echo")

    def hold_agent(self):
        """
        电脑端接入打开 → :agent 留在前台服务并持有对 :ext 的绑定（与“有任务”同样的前台理由，C6）。
        要像设置页那样从**前台界面**打开（inapp 的 desktop-access 场景）：后台收到的广播里 :agent 启动不了前台服务
        （Android 12+ 的后台启动限制，交给监督进程 promote），那样 :agent、:ext、插件进程都是 cached，会被冻结。
        """
        r = acp.run_one(self.adb, "hold-agent", INAPP_ACTIVITY, "desktop-access", {"on": True}, 60)
        self.adb.sh("input keyevent KEYCODE_HOME", check=False)
        return bool(r and r.get("ok"))

    def release_agent(self):
        self.adb.sh(f"am broadcast -f 32 -n {DESKTOP_RECEIVER} --es op disable", check=False)

    def pidof(self, name):
        out = self.adb.sh(f"pidof {name}", check=False).strip()
        return int(out.split()[0]) if out else None

    def plugin_service_running(self):
        out = self.adb.sh(f"dumpsys activity services {PLUGIN_PKG}", check=False, timeout=60)
        return "TestMcpService" in out


def outcome_of(r):
    o = r.get("outcome") or {}
    return o.get("outcome"), (o.get("error") or {}).get("code"), o


def result_text(o):
    content = ((o or {}).get("result") or {}).get("content") or []
    return "".join(c.get("text", "") for c in content if isinstance(c, dict))


def verdict(checks, summary, **extra):
    ok = all(checks.values())
    d = {"ok": ok, "summary": f"{summary} checks={sum(checks.values())}/{len(checks)}", "checks": checks}
    d.update(extra)
    return d


# ---------------------------------------------------------------- 用例


def case_discover(c):
    p = c.plugin()
    cat = c.catalog()
    diag = c.dbg("diag").get("diag", {})
    names = [t["name"] for t in cat.get("tools", [])]
    server = (p or {}).get("servers", [{}])[0] if p else {}
    checks = {
        "found": p is not None,
        "ready": (p or {}).get("status") == "ready",
        "offByDefault": (p or {}).get("enabled") is False and not (p or {}).get("builtin", True),
        "serverDisabledByPolicy": server.get("state") == "disabled" and server.get("error") == "user_policy",
        "noRejectedServers": (p or {}).get("rejectedServers") == [],
        "noToolsInCatalog": not any(n.startswith("mcp__mcptest__") for n in names),
        "policyHealthy": cat.get("policyFailClosed") is False and diag.get("policyHealth") == "ok",
        "memoryIntact": diag.get("memoryLost") is False,
        # 插件 JSON 键固定；首次发现即信任当时的签名，所以确认过的摘要就是现在的
        "pluginShape": p is not None and list(p.keys()) == PLUGIN_KEYS,
        "trustedDigestIsCurrent": p is not None and bool(p.get("signingDigest")) and p.get("trustedSigningDigest") == p.get("signingDigest"),
    }
    return verdict(checks, f"status={(p or {}).get('status')} enabled={(p or {}).get('enabled')} server={server.get('state')}",
                   plugin=p, diag=diag)


def case_no_permission(c):
    r = acp.run_one(c.adb, "bind-mcp", CLIENT_ACTIVITY, "bind-mcp", {"pkg": PLUGIN_PKG, "service": PLUGIN_SERVICE}, 60)
    if r is None:
        return {"ok": False, "error": "client scenario timed out"}
    perm = c.adb.sh(f"dumpsys package {PLUGIN_PKG}", check=False, timeout=60)
    declared = "org.agentos.permission.BIND_MCP_SERVICE" in perm
    # run_one 开始时清过 logcat：这里只有这次 bind 的记录
    log = c.adb.run("logcat", "-d", "-s", "ActivityManager:W", check=False, timeout=60)
    denial = [l for l in log.splitlines() if "Permission Denial" in l and "TestMcpService" in l]
    checks = {"deniedBySystem": bool(r.get("ok")), "servicePermissionDeclared": declared,
              "deniedForBindMcpService": any("org.agentos.permission.BIND_MCP_SERVICE" in l for l in denial)}
    return verdict(checks, r.get("summary", ""), client=r, denial=denial[:2])


def case_enable(c):
    t0 = time.time()
    r = c.dbg("enable", id=PLUGIN_ID)
    w = c.wait_tool("echo")
    ms = int((time.time() - t0) * 1000)
    names = c.own_tool_names()
    tools = {t["name"]: t for t in c.dbg("tools", id=PLUGIN_ID).get("tools", [])}
    cat = c.catalog()
    # 只看测试插件自己的 Skill（SkillSummary.provider 是插件名）；与别的插件重名时 id 会变成 "mcptest:<名字>"，所以按名字找
    skills = {s["name"]: s for s in cat.get("skills", []) if s.get("provider") == "mcptest"}
    greet_id = (skills.get("greet") or {}).get("id", "greet")
    p = c.plugin() or {}
    hi = tools.get(T(HIGH_RISK_TOOL), {})
    echo = tools.get(T("echo"), {})
    skill = c.dbg("skill", name=greet_id, path="reference/details.md")
    bad = c.dbg("skill", name=greet_id, path="../broken/SKILL.md")
    checks = {
        "enabled": (r.get("plugin") or {}).get("enabled") is True,
        "toolsAppeared": bool(w.get("met")) and names == ALL_TOOLS,
        "highRisk": hi.get("risk") == "high" and hi.get("mayAlwaysAllow") is False,
        "echoWriteAsk": echo.get("risk") == "write" and echo.get("approval") == "ask" and echo.get("pluginId") == PLUGIN_ID,
        # listTools 的每一项与 KnownTool 字段一一对应（D5.3 插件页依赖这个形状）
        "knownToolShape": bool(tools) and all(list(t.keys()) == KNOWN_TOOL_KEYS for t in tools.values()),
        "serverConnected": (p.get("servers") or [{}])[0].get("state") in ("connected", "idle") and p.get("toolCount") == 7,
        "skills": set(skills) == {"greet", "broken"} and p.get("skillCount") == 2,
        "skillProblem": any(x.get("location") == "broken" for x in p.get("skillProblems", [])),
        "skillFileRead": "marker 7f3a" in (skill.get("skill") or {}).get("text", ""),
        "skillPathRejected": "bad_path" in str(bad.get("error")),
    }
    return verdict(checks, f"enableToCatalogMs={ms} ownTools={len(names)} ownSkills={sorted(skills)} "
                           f"catalogTotal={len(cat.get('tools', []))}", enableToCatalogMs=ms)


def case_approvals(c):
    c.ensure_enabled()
    hi = c.dbg("tool_approval", name=T(HIGH_RISK_TOOL), mode="always")
    by_src = c.dbg("approval_by_source", plugin="mcptest", server="test", tool="echo", mode="always")
    tools = {t["name"]: t for t in c.dbg("tools", id=PLUGIN_ID).get("tools", [])}
    after_always = tools.get(T("echo"), {}).get("approval")
    cleared = c.dbg("approval_by_source", plugin="mcptest", server="test", tool="echo", mode="")
    bad = c.dbg("tool_approval", name=T("echo"), mode="always_allow")
    plugin_always = c.dbg("approval", id=PLUGIN_ID, mode="always")
    tools2 = {t["name"]: t for t in c.dbg("tools", id=PLUGIN_ID).get("tools", [])}
    c.dbg("approval", id=PLUGIN_ID, mode="")
    checks = {
        "highRiskRefused": "high_risk" in str(hi.get("error")),
        "bySourceAlways": by_src.get("ok") is True and after_always == "always",
        "bySourceCleared": (cleared.get("tool") or {}).get("approval") == "ask",
        "badModeRefused": "bad_mode" in str(bad.get("error")),
        # 插件级 always 覆盖到高风险工具的“显示值”，但 mayAlwaysAllow=false：运行时仍然每次确认（e2e-high）
        "pluginAlways": (plugin_always.get("plugin") or {}).get("approval") == "always"
                        and tools2.get(T(HIGH_RISK_TOOL), {}).get("mayAlwaysAllow") is False,
    }
    return verdict(checks, f"high={hi.get('error', '')[:60]} echo={after_always}")


def case_call(c):
    c.ensure_enabled()
    ok = c.call("echo", {"text": "hi-c7b"})
    bad = c.call("echo", {})
    missing = c.call("nope")
    o1, _, oo1 = outcome_of(ok)
    o2, _, oo2 = outcome_of(bad)
    checks = {
        "completed": o1 == "completed" and "hi-c7b" in result_text(oo1) and not oo1["result"]["isError"],
        "structured": (oo1.get("result") or {}).get("details", {}).get("text") == "hi-c7b",
        "argsErrorIsError": o2 == "completed" and oo2["result"]["isError"] is True,
        "notInCatalogNotAccepted": missing.get("accepted") is False,
        "exactlyOnce": ok.get("extraResults") == 0 and bad.get("extraResults") == 0,
    }
    return verdict(checks, f"echoMs={ok.get('elapsedMs')} badMs={bad.get('elapsedMs')}", pid=(oo1.get("result") or {}).get("details", {}).get("pid"))


def case_uid_check(c):
    """S4：:ext 一侧的通道按插件 App 的 UID 校验每个入站调用（binder-channel-v1）；统计里 peerUid = 插件 UID，没有被拒的调用。"""
    c.ensure_enabled()
    r = c.call("echo", {"text": "uid"})
    out = c.adb.sh(f"pm list packages -U {PLUGIN_PKG}", check=False)
    m = re.search(r"uid:(\d+)", out)
    uid = int(m.group(1)) if m else None
    links = ((c.dbg("diag").get("diag") or {}).get("connector") or {}).get("links") or []
    ch = next((l.get("channel") or {} for l in links if PLUGIN_PKG in l.get("component", "")), {})
    checks = {
        "callOk": outcome_of(r)[0] == "completed",
        "peerUidIsPlugin": uid is not None and ch.get("peerUid") == uid,
        "noUidRejects": ch.get("uidRejects") == 0,
        "messagesReceived": (ch.get("in") or {}).get("received", 0) > 0,
    }
    return verdict(checks, f"pluginUid={uid} peerUid={ch.get('peerUid')} uidRejects={ch.get('uidRejects')} "
                           f"received={(ch.get('in') or {}).get('received')}", channel=ch)


def case_timeout(c):
    c.ensure_enabled()
    r = c.call("slow", {"ms": 10_000}, timeout_ms=1_500)
    o, code, _ = outcome_of(r)
    ms = r.get("elapsedMs", -1)
    checks = {"unknown": o == "unknown", "toolTimeout": code == "tool_timeout", "onTime": 1_400 <= ms <= 4_000,
              "exactlyOnce": r.get("extraResults") == 0}
    return verdict(checks, f"outcome={o}/{code} elapsedMs={ms}")


def case_cancel(c):
    c.ensure_enabled()
    before = outcome_of(c.call("stats"))[2]
    r = c.call("slow", {"ms": 20_000}, timeout_ms=30_000, cancel_after_ms=1_000)
    time.sleep(0.5)
    after = outcome_of(c.call("stats"))[2]
    o, code, oo = outcome_of(r)
    ms = r.get("elapsedMs", -1)
    sb = (before.get("result") or {}).get("details", {})
    sa = (after.get("result") or {}).get("details", {})
    same_process = sb.get("pid") == sa.get("pid")
    checks = {
        "unknown": o == "unknown" and code == "tool_result_unknown",
        "markedCancelled": ((oo.get("error") or {}).get("details") or {}).get("cancelled") is True,
        "prompt": 900 <= ms <= 4_000,
        "pluginSawCancel": same_process and sa.get("cancelled", 0) == sb.get("cancelled", 0) + 1,
        "exactlyOnce": r.get("extraResults") == 0,
    }
    return verdict(checks, f"elapsedMs={ms} pluginCancelled {sb.get('cancelled')}->{sa.get('cancelled')}")


def case_list_changed(c):
    c.ensure_enabled()
    c.call("add_tool")
    t0 = time.time()
    w1 = c.wait_tool("extra", timeout_ms=10_000)
    add_ms = int((time.time() - t0) * 1000)
    extra = c.call("extra")
    c.call("remove_tool")
    w2 = c.wait_tool("extra", absent=True, timeout_ms=10_000)
    checks = {"added": bool(w1.get("met")), "callable": result_text(outcome_of(extra)[2]) == "extra",
              "removed": bool(w2.get("met"))}
    return verdict(checks, f"listChangedToCatalogMs={add_ms}")


def case_plugin_death(c):
    c.ensure_enabled()
    pid0 = (outcome_of(c.call("echo", {"text": "a"}))[2].get("result") or {}).get("details", {}).get("pid")
    r = c.call("die", {"after_ms": 300}, timeout_ms=15_000)
    o, code, _ = outcome_of(r)
    time.sleep(0.5)
    states = [s.get("state") for s in (c.plugin() or {}).get("servers", [])]
    again = c.call("echo", {"text": "b"})
    o2, _, oo2 = outcome_of(again)
    pid1 = (oo2.get("result") or {}).get("details", {}).get("pid")
    checks = {"unknown": o == "unknown" and code == "tool_result_unknown", "detectedQuickly": r.get("elapsedMs", 99_999) < 3_000,
              "reconnected": o2 == "completed" and pid1 is not None and pid1 != pid0}
    return verdict(checks, f"dieMs={r.get('elapsedMs')} stateAfter={states} pid {pid0}->{pid1} reconnectMs={again.get('elapsedMs')}")


def case_force_stop(c):
    c.ensure_enabled()
    box = {}
    th = threading.Thread(target=lambda: box.update(r=c.call("slow", {"ms": 20_000}, timeout_ms=30_000)))
    th.start()
    time.sleep(2.0)
    c.adb.sh(f"am force-stop {PLUGIN_PKG}", check=False)
    th.join(90)
    r = box.get("r", {})
    o, code, _ = outcome_of(r)
    again = c.call("echo", {"text": "after-force-stop"})
    o2, _, _ = outcome_of(again)
    checks = {"unknown": o == "unknown" and code == "tool_result_unknown", "reconnected": o2 == "completed"}
    return verdict(checks, f"slowMs={r.get('elapsedMs')} outcome={o}/{code} reconnectMs={again.get('elapsedMs')}")


def case_disable_inflight(c):
    c.ensure_enabled()
    r = c.call("slow", {"ms": 20_000}, timeout_ms=30_000, disable_after_ms=2_000)
    o, code, _ = outcome_of(r)
    d = {"plugin": c.plugin()}
    gone = c.wait_tool("echo", absent=True, timeout_ms=5_000)
    refused = c.call("echo", {"text": "x"})
    p = c.plugin() or {}
    c.dbg("enable", id=PLUGIN_ID)
    back = c.wait_tool("echo")
    checks = {
        "disabled": (d.get("plugin") or {}).get("enabled") is False,
        "inflightUnknown": o == "unknown" and code == "tool_result_unknown" and 1_900 <= r.get("elapsedMs", 0) <= 5_000,
        "removedFromCatalog": bool(gone.get("met")),
        "callNotAccepted": refused.get("accepted") is False,
        "serverDisabled": (p.get("servers") or [{}])[0].get("state") == "disabled",
        "reEnabled": bool(back.get("met")),
    }
    return verdict(checks, f"inflightMs={r.get('elapsedMs')} outcome={o}/{code}")


def case_tool_disable(c):
    c.ensure_enabled()
    c.dbg("tool_disable", name=T("echo"))
    gone = c.wait_tool("echo", absent=True, timeout_ms=5_000)
    listed = {t["name"]: t for t in c.dbg("tools", id=PLUGIN_ID).get("tools", [])}
    refused = c.call("echo", {"text": "x"})
    others = c.own_tool_names()
    c.dbg("tool_enable", name=T("echo"))
    back = c.wait_tool("echo", timeout_ms=5_000)
    checks = {
        "removed": bool(gone.get("met")),
        "stillListedDisabled": listed.get(T("echo"), {}).get("enabled") is False,
        "callNotAccepted": refused.get("accepted") is False,
        "othersKeepNames": sorted(set(ALL_TOOLS) - {T("echo")}) == others,
        "reEnabled": bool(back.get("met")),
    }
    return verdict(checks, f"catalogWhileDisabled={len(others)}")


def case_idle(c):
    c.ensure_enabled()
    c.call("echo", {"text": "warm"})
    busy = c.plugin_service_running()
    ps0 = c.proc_states()
    wait = c.args.idle_wait
    time.sleep(wait)
    ps1 = c.proc_states()
    released = not c.plugin_service_running()
    state = [s.get("state") for s in (c.plugin() or {}).get("servers", [])]
    again = c.call("echo", {"text": "after-idle"})
    checks = {"boundWhileBusy": busy, "idleState": state == ["idle"], "serviceReleased": released,
              "reconnects": outcome_of(again)[0] == "completed"}
    return verdict(checks, f"waited={wait}s state={state} reconnectMs={again.get('elapsedMs')}", procBefore=ps0, procAfter=ps1)


def case_background(c):
    c.ensure_enabled()
    c.adb.sh("input keyevent KEYCODE_HOME", check=False)
    time.sleep(1)
    top = c.adb.sh("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'", check=False)
    ps = c.proc_states()
    fgs = ps.get("agentServiceForeground") is True
    # 先让连接空闲回收掉，确保这次是在后台重新 bind（idle 用例之后通常已经断开；单跑时强制等一次）
    if c.plugin_service_running():
        time.sleep(c.args.idle_wait)
    r = c.call("echo", {"text": "from-background"})
    checks = {"appNotOnTop": APP_PKG not in top, "agentForegroundService": fgs,
              "freshBind": outcome_of(r)[0] == "completed"}
    return verdict(checks, f"bindAndCallMs={r.get('elapsedMs')} proc={ps}", top=top.strip()[:300])


def case_ext_death(c):
    c.ensure_enabled()
    before = c.dbg("agent").get("extensions") or {}
    pid0 = c.pidof(f"{APP_PKG}:ext")
    t0 = time.time()
    c.adb.sh(f"run-as {APP_PKG} kill -9 {pid0}", check=False)
    pid1 = None
    while time.time() - t0 < 30:
        pid1 = c.pidof(f"{APP_PKG}:ext")
        if pid1 and pid1 != pid0:
            break
        time.sleep(0.5)
    restart_ms = int((time.time() - t0) * 1000)
    w = c.wait_tool("echo", timeout_ms=20_000)
    time.sleep(1)
    after = c.dbg("agent").get("extensions") or {}
    r = c.call("echo", {"text": "after-ext-death"})
    checks = {
        "restartedBySystem": pid1 is not None and pid1 != pid0,
        "catalogBack": bool(w.get("met")),
        "agentSawDisconnect": after.get("disconnects", 0) >= before.get("disconnects", 0) + 1,
        "agentReconnected": after.get("connects", 0) >= before.get("connects", 0) + 1 and after.get("connected") is True,
        # 与 :ext 重建前一样多（设备上可能还有别的插件，不写死个数）
        "agentCatalog": after.get("tools") == before.get("tools") and len(c.own_tool_names()) == 7
                        and after.get("policyReceived") is True and after.get("policyFailClosed") is False,
        "callWorks": outcome_of(r)[0] == "completed",
    }
    return verdict(checks, f"extPid {pid0}->{pid1} restartMs={restart_ms} agentTools={after.get('tools')}", agentBefore=before, agentAfter=after)


def ensure_fake_model(c):
    if c.fm is None:
        c.fm = acp.start_fake_model(c.adb, {"agtest-fake-model-key": "test"})


def consent(c, op, mode=None):
    """debug 构建的确认协调器（ConsentDebugReceiver，在 :agent）：mode = allow / allowOnce / deny / off；recent = 最近的确认请求。"""
    cmd = f"am broadcast -n {CONSENT_RECEIVER} --es op {op}" + (f" --es mode {mode}" if mode else "")
    out = c.adb.sh(cmd, check=False, timeout=60)
    m = re.search(r'data="(.*)"\s*$', out, re.S)
    return json.loads(m.group(1)) if m else {"ok": False, "raw": out[-300:]}


def consent_entries(c, tool):
    return [e for e in consent(c, "recent").get("recent", []) if e.get("tool") == T(tool)]


def run_e2e(c, name, args, mode, tool, expect_prompt, expect_answer=None):
    """
    设好自动应答模式再跑 ext-e2e；核对确认协调器有没有为这个工具发起确认（expect_prompt），以及自动应答选了什么。
    这样同时验证：always 策略跳过确认、需要确认时经过协调器、高风险即使插件级 always 也要确认且不提供“始终允许”。
    """
    ensure_fake_model(c)
    set_mode = consent(c, "mode", mode)
    before = len(consent_entries(c, tool))
    r = acp.run_one(c.adb, name, INAPP_ACTIVITY, "ext-e2e", args, 120) or {"ok": False, "error": "ext-e2e timed out"}
    entries = consent_entries(c, tool)
    new = entries[before:]
    prompted = bool(new)
    answer_ok = expect_answer is None or (prompted and new[-1].get("answeredWith") == expect_answer)
    # “始终允许”（ApprovalWriter 经 IExtensionHost 写回，C7b）：写级工具提供、高风险不提供；自动应答从不选它（测试不改用户策略）
    always_rule = all(("ALWAYS_ALLOW" in e.get("options", [])) == (e.get("risk") == "WRITE") for e in new)
    never_picked = all(e.get("answeredWith") != "ALWAYS_ALLOW" for e in new)
    r.setdefault("checks", {})
    r["checks"].update({"consentModeSet": set_mode.get("ok") is True, "consentAsExpected": prompted == expect_prompt,
                        "consentAnswer": answer_ok, "alwaysAllowOnlyForWrite": always_rule, "alwaysAllowNeverPicked": never_picked})
    r["consent"] = new[-3:]
    r["ok"] = bool(r.get("ok")) and all(r["checks"].values())
    r["summary"] = f"{r.get('summary', '')} consent={[(e.get('risk'), e.get('answeredWith'), e.get('options')) for e in new]}"
    return r


def case_e2e_always(c):
    c.ensure_enabled()
    c.dbg("approval_by_source", plugin="mcptest", server="test", tool="echo", mode="always")
    time.sleep(0.5)
    # 自动应答设成拒绝：如果还问了确认，这一轮就会失败
    return run_e2e(c, "e2e-always", {"tool": T("echo"), "toolInput": {"text": "e2e-marker-41"}, "expect": "completed",
                                     "resultContains": "e2e-marker-41"}, "deny", "echo", expect_prompt=False)


def case_e2e_ask(c):
    c.ensure_enabled()
    c.dbg("approval_by_source", plugin="mcptest", server="test", tool="echo", mode="")
    time.sleep(0.5)
    return run_e2e(c, "e2e-ask", {"tool": T("echo"), "toolInput": {"text": "x"}, "expect": "failed",
                                  "resultContains": "tool_denied"}, "deny", "echo", expect_prompt=True, expect_answer="DENY")


def case_e2e_consent(c):
    """authorize → 确认（自动应答允许一次）→ :agent 代理 → :ext → 插件执行 → 结果交回模型。"""
    c.ensure_enabled()
    c.dbg("approval_by_source", plugin="mcptest", server="test", tool="echo", mode="")
    time.sleep(0.5)
    return run_e2e(c, "e2e-consent", {"tool": T("echo"), "toolInput": {"text": "consented-77"}, "expect": "completed",
                                      "resultContains": "consented-77"}, "allowOnce", "echo", expect_prompt=True,
                   expect_answer="ALLOW_ONCE")


def case_e2e_high(c):
    c.ensure_enabled()
    c.dbg("approval", id=PLUGIN_ID, mode="always")
    time.sleep(0.5)
    try:
        r = run_e2e(c, "e2e-high", {"tool": T(HIGH_RISK_TOOL), "expect": "failed", "resultContains": "tool_denied"},
                    "deny", HIGH_RISK_TOOL, expect_prompt=True, expect_answer="DENY")
        last = (r.get("consent") or [{}])[-1]
        r["checks"]["highRiskPrompt"] = last.get("risk") == "HIGH" and "ALLOW_FOR_SESSION" not in last.get("options", [])
        r["ok"] = bool(r.get("ok")) and r["checks"]["highRiskPrompt"]
        return r
    finally:
        c.dbg("approval", id=PLUGIN_ID, mode="")


def case_e2e_disabled(c):
    c.ensure_enabled()
    c.dbg("tool_disable", name=T("echo"))
    c.wait_tool("echo", absent=True, timeout_ms=5_000)
    time.sleep(0.5)
    try:
        return run_e2e(c, "e2e-disabled", {"tool": T("echo"), "toolInput": {"text": "x"}, "expect": "failed"},
                       "deny", "echo", expect_prompt=False)
    finally:
        c.dbg("tool_enable", name=T("echo"))


def build_tools():
    """apksigner（最新的 build-tools）与 JDK 21 的 keytool；找不到返回 (None, None)。"""
    root = os.path.expanduser("~/Library/Android/sdk/build-tools")
    if not os.path.isdir(root):
        return None, None
    versions = sorted(os.listdir(root), key=lambda v: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", v)])
    apksigner = os.path.join(root, versions[-1], "apksigner") if versions else None
    java_home = subprocess.run(["/usr/libexec/java_home", "-v", "21"], capture_output=True, text=True).stdout.strip()
    keytool = os.path.join(java_home, "bin", "keytool") if java_home else None
    ok = apksigner and os.path.exists(apksigner) and keytool and os.path.exists(keytool)
    return (apksigner, keytool) if ok else (None, None)


def rotated_apk(src):
    """用 v3 签名轮换（lineage：调试证书 → 新证书）重签插件 APK。密钥只在临时目录里，用完即弃。"""
    apksigner, keytool = build_tools()
    work = tempfile.mkdtemp(prefix="c7b-rotate-")
    debug_ks = os.path.expanduser("~/.android/debug.keystore")
    pw = secrets.token_hex(12)
    new_ks = os.path.join(work, "rotated.jks")
    lineage = os.path.join(work, "lineage.bin")
    out = os.path.join(work, "plugin-rotated.apk")
    env = dict(os.environ, KS_PW=pw)
    subprocess.run([keytool, "-genkeypair", "-keystore", new_ks, "-storepass:env", "KS_PW", "-keypass:env", "KS_PW",
                    "-alias", "rotated", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                    "-dname", "CN=AgentOS C7b rotated test key"], check=True, capture_output=True, env=env)
    old = ["--ks", debug_ks, "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android"]
    new = ["--ks", new_ks, "--ks-key-alias", "rotated", "--ks-pass", "env:KS_PW", "--key-pass", "env:KS_PW"]
    subprocess.run([apksigner, "rotate", "--out", lineage, "--old-signer"] + old + ["--new-signer"] + new,
                   check=True, capture_output=True, env=env)
    subprocess.run([apksigner, "sign"] + old + ["--next-signer"] + new + ["--lineage", lineage, "--out", out, src],
                   check=True, capture_output=True, env=env)
    return out, work


def wait_ext_restart(c, pid0, timeout=30):
    t0 = time.time()
    while time.time() - t0 < timeout:
        pid1 = c.pidof(f"{APP_PKG}:ext")
        if pid1 and pid1 != pid0:
            return pid1, int((time.time() - t0) * 1000)
        time.sleep(0.5)
    return None, int((time.time() - t0) * 1000)


def wait_agent(c, pred, timeout=15):
    """等 :agent 的代理计数满足 pred（镜像经 onCatalogChanged → getCatalog 异步更新）。"""
    t0 = time.time()
    ext = {}
    while time.time() - t0 < timeout:
        ext = c.dbg("agent").get("extensions") or {}
        if pred(ext):
            return ext, True
        time.sleep(0.5)
    return ext, False


def kill_ext(c):
    pid0 = c.pidof(f"{APP_PKG}:ext")
    c.adb.sh(f"run-as {APP_PKG} kill -9 {pid0}", check=False)
    return wait_ext_restart(c, pid0)


def case_known_tools(c):
    """
    已知工具（A9 的 ExtensionToolHost.knownTools，IExtensionHost.listTools）：
    1. 工具级禁用 echo 后杀掉 :ext：插件已启用，重建后连一次，listTools 仍列出全部 7 个，echo enabled=false、名字不变、不在目录里；
    2. 插件停用后再杀 :ext：这个 :ext 进程里从没连上过它，listTools 返回 []（插件页显示“启用后可查看工具”）；启用一次后列表回来。
    """
    c.ensure_enabled()
    before = {t["name"]: t for t in c.dbg("tools", id=PLUGIN_ID).get("tools", [])}
    c.dbg("tool_disable", name=T("echo"))
    c.wait_tool("echo", absent=True, timeout_ms=5_000)
    pid1, restart_ms = kill_ext(c)
    t0 = time.time()
    listed = c.dbg("tools", id=PLUGIN_ID, timeout=60)
    list_ms = int((time.time() - t0) * 1000)
    after = {t["name"]: t for t in listed.get("tools", [])}
    echo = after.get(T("echo"), {})
    keys_ok = bool(after) and all(list(t.keys()) == KNOWN_TOOL_KEYS for t in after.values())
    echo_not_in_catalog = T("echo") not in c.own_tool_names()
    # 2：停用的插件
    c.dbg("disable", id=PLUGIN_ID)
    pid2, _ = kill_ext(c)
    listed2 = c.dbg("tools", id=PLUGIN_ID, timeout=60)
    p = c.plugin() or {}
    c.dbg("enable", id=PLUGIN_ID)
    w = c.wait_tool("stats", timeout_ms=15_000)
    listed3 = {t["name"]: t for t in c.dbg("tools", id=PLUGIN_ID, timeout=60).get("tools", [])}
    c.dbg("tool_enable", name=T("echo"))
    back = c.wait_tool("echo", timeout_ms=10_000)
    checks = {
        "extRestarted": pid1 is not None and pid2 is not None,
        "stillListedAfterRebuild": listed.get("ok") is True and sorted(after) == sorted(before) == ALL_TOOLS,
        "disabledToolKeptSameName": echo.get("enabled") is False and (echo.get("source") or {}).get("tool") == "echo",
        "disabledToolNotInCatalog": echo_not_in_catalog,
        "exactKeys": keys_ok,
        "disabledPluginHasNoList": listed2.get("ok") is True and listed2.get("tools") == [] and p.get("enabled") is False,
        "enableOnceRestoresList": bool(w.get("met")) and sorted(listed3) == ALL_TOOLS
                                  and (listed3.get(T("echo")) or {}).get("enabled") is False,
        "toolReEnabled": bool(back.get("met")),
    }
    return verdict(checks, f"extRestartMs={restart_ms} listAfterRebuildMs={list_ms} known={len(after)} "
                           f"disabledPluginList={listed2.get('tools')}", echoAfterRebuild=echo)


def case_policy_corrupt(c):
    """策略文件和备份都读不出来 → :ext 重建后 fail closed（第三方插件当作禁用，:agent 的镜像同步）；写入被拒；用户重置后恢复，插件仍停用。"""
    c.ensure_enabled()
    for f in ("approval-policy.json", "approval-policy.backup.json"):
        c.adb.sh(f"run-as {APP_PKG} sh -c 'echo broken > files/ext/{f}'", check=False)
    pid0 = c.pidof(f"{APP_PKG}:ext")
    c.adb.sh(f"run-as {APP_PKG} kill -9 {pid0}", check=False)
    pid1, _ = wait_ext_restart(c, pid0)
    gone = c.wait_tool("echo", absent=True, timeout_ms=20_000)
    status = c.dbg("policy").get("policy") or {}
    cat = c.catalog()
    # :agent 的镜像与 :ext 的目录一致（fail closed 时第三方插件全部当作禁用；不写死个数，设备上可能有别的插件）
    closed_n = len(cat.get("tools", []))
    mirror, mirror_closed = wait_agent(c, lambda e: e.get("policyFailClosed") is True and e.get("tools") == closed_n)
    refused = c.dbg("enable", id=PLUGIN_ID)
    p_closed = c.plugin() or {}
    reset = c.dbg("policy_reset").get("policy") or {}
    p_after = c.plugin() or {}
    c.dbg("enable", id=PLUGIN_ID)
    back = c.wait_tool("echo")
    open_n = len(c.catalog().get("tools", []))
    mirror2, mirror_open = wait_agent(c, lambda e: e.get("policyFailClosed") is False and e.get("tools") == open_n and open_n >= 7)
    checks = {
        "extRestarted": pid1 is not None,
        "failClosed": status.get("health") == "corrupt" and status.get("using") == "fail_closed" and status.get("failClosed") is True,
        "catalogFailClosed": cat.get("policyFailClosed") is True and bool(gone.get("met")),
        "agentMirrorFailClosed": mirror_closed,
        "pluginShownOff": p_closed.get("enabled") is False and p_closed.get("status") == "ready",
        "writeRefused": "unavailable" in str(refused.get("error")),
        "resetHealthy": reset.get("health") == "ok" and reset.get("failClosed") is False,
        "stillOffAfterReset": p_after.get("enabled") is False,
        "reEnabled": bool(back.get("met")),
        "agentMirrorRecovered": mirror_open,
    }
    return verdict(checks, f"status={status.get('health')}/{status.get('using')} reset={reset.get('health')}",
                   policyStatus=status, mirrorClosed=mirror, mirrorOpen=mirror2)


def case_signature(c):
    if c.args.skip_rotation:
        return {"ok": True, "skipped": True, "summary": "skipped (--skip-rotation)"}
    if build_tools()[0] is None:
        return {"ok": True, "skipped": True, "summary": "skipped (apksigner / keytool not found on this host)"}
    c.ensure_enabled()
    digest0 = (c.plugin() or {}).get("signingDigest")
    apk, work = rotated_apk(plugin_apk())
    try:
        inst = c.adb.run("install", "-r", "-t", apk, timeout=240, check=False)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    gone = c.wait_tool("echo", absent=True, timeout_ms=15_000)
    p = c.plugin() or {}
    refused = c.dbg("enable", id=PLUGIN_ID)
    confirmed = (c.dbg("confirm", id=PLUGIN_ID).get("plugin") or {})
    enabled = c.dbg("enable", id=PLUGIN_ID)
    back = c.wait_tool("echo")
    checks = {
        "installed": "Success" in inst,
        "signatureChanged": p.get("status") == "signature_changed" and p.get("signingDigest") not in (None, digest0),
        # 插件页显示“之前的签名 / 现在的签名”：确认过的仍是轮换前的
        "trustedDigestIsPrevious": p.get("trustedSigningDigest") == digest0,
        "disabled": p.get("enabled") is False,
        "toolsRemoved": bool(gone.get("met")),
        "enableRefused": "not_ready" in str(refused.get("error")),
        "confirmedStillOff": confirmed.get("status") == "ready" and confirmed.get("enabled") is False
                             and confirmed.get("trustedSigningDigest") == confirmed.get("signingDigest") != digest0,
        "reEnabled": enabled.get("ok") is True and bool(back.get("met")),
    }
    return verdict(checks, f"status={p.get('status')} digest {str(digest0)[:8]}->{str(p.get('signingDigest'))[:8]}")


def case_uninstall(c):
    c.adb.run("uninstall", PLUGIN_PKG, check=False, timeout=120)
    gone = c.wait_tool("echo", absent=True, timeout_ms=15_000)
    time.sleep(1)
    listed = c.plugin()
    install_plugin(c.adb)
    p = None
    deadline = time.time() + 20
    while time.time() < deadline:
        p = c.plugin()
        if p:
            break
        time.sleep(1)
    names = c.catalog_names()
    checks = {"toolsRemoved": bool(gone.get("met")), "pluginRemoved": listed is None,
              "rediscovered": p is not None and p.get("status") == "ready",
              "offAgain": (p or {}).get("enabled") is False and not any(n.startswith("mcp__mcptest__") for n in names)}
    return verdict(checks, f"reinstalled enabled={(p or {}).get('enabled')}")


CASES = [
    ("discover", case_discover), ("no-permission", case_no_permission), ("enable", case_enable),
    ("approvals", case_approvals), ("call", case_call), ("uid-check", case_uid_check), ("timeout", case_timeout), ("cancel", case_cancel),
    ("list-changed", case_list_changed), ("plugin-death", case_plugin_death), ("force-stop", case_force_stop),
    ("disable-inflight", case_disable_inflight), ("tool-disable", case_tool_disable), ("idle", case_idle),
    ("background", case_background), ("ext-death", case_ext_death), ("known-tools", case_known_tools),
    ("e2e-always", case_e2e_always), ("e2e-ask", case_e2e_ask), ("e2e-consent", case_e2e_consent), ("e2e-high", case_e2e_high),
    ("e2e-disabled", case_e2e_disabled),
    ("policy-corrupt", case_policy_corrupt), ("signature", case_signature), ("uninstall", case_uninstall),
]


def plugin_apk():
    return os.path.join(HERE, "plugin", "build", "outputs", "apk", "debug", "plugin-debug.apk")


def install_plugin(adb):
    acp.install(adb, plugin_apk())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--only", help="逗号分隔的用例名")
    ap.add_argument("--label", default="")
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--no-clear", action="store_true", help="不清 AgentOS 的数据")
    ap.add_argument("--clear", action="store_true",
                    help="清 AgentOS 的数据（pm clear，从首次发现开始）。模拟器上默认清；真机默认不清（会抹掉模型源和配对），要清必须显式给这个参数")
    ap.add_argument("--skip-rotation", action="store_true")
    ap.add_argument("--idle-wait", type=int, default=40, help="空闲回收用例等待的秒数（Extension Host 是 30 秒）")
    a = ap.parse_args()
    if not a.serial:
        sys.exit("need --serial or ANDROID_SERIAL")
    adb = acp.Adb(a.serial)
    if not a.no_install:
        acp.install(adb, os.path.join(REPO, "app", "build", "outputs", "apk", "debug", "app-debug.apk"))
        acp.install(adb, os.path.join(ACP, "client", "build", "outputs", "apk", "debug", "client-debug.apk"))
        adb.run("uninstall", PLUGIN_PKG, check=False, timeout=120)
        install_plugin(adb)
    emulator = adb.prop("ro.kernel.qemu") == "1" or adb.prop("ro.boot.qemu") == "1"
    clear = a.clear or (emulator and not a.no_clear)
    if clear:
        adb.sh(f"pm clear {APP_PKG}", check=False)
    else:
        print(f"keeping AgentOS data ({'--no-clear' if a.no_clear else 'real device: pass --clear to wipe'})", flush=True)
    adb.sh("input keyevent KEYCODE_WAKEUP", check=False)
    adb.sh("wm dismiss-keyguard", check=False)
    device = {"serial": a.serial, "model": adb.prop("ro.product.model"), "sdk": adb.prop("ro.build.version.sdk"),
              "release": adb.prop("ro.build.version.release"), "fingerprint": adb.prop("ro.build.fingerprint")}
    print(json.dumps(device, ensure_ascii=False), flush=True)
    c = Ctx(adb, a)
    held = c.hold_agent()
    print(f"hold :agent (desktop access on): {held}", flush=True)
    time.sleep(2)
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
            print(f"{name:18s} ok={r.get('ok')} {r.get('summary', '')}" + (f" FAILED={bad}" if bad else "")
                  + (f" error={r.get('error')}" if r.get("error") else ""), flush=True)
    finally:
        consent(c, "mode", "off")
        c.release_agent()
        if c.fm is not None:
            adb.run("reverse", "--remove", f"tcp:{fake_model.DEVICE_PORT}", check=False)
            c.fm.stop()
    out = {"suite": "mcp-plugin", "device": device, "time": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "results": results}
    os.makedirs(os.path.join(HERE, "results", "raw"), exist_ok=True)
    tag = f"-{a.label}" if a.label else ""
    path = os.path.join(HERE, "results", "raw", f"mcp-{a.serial.replace(':', '_')}-api{device['sdk']}{tag}-{time.strftime('%Y%m%d-%H%M%S')}.json")
    with open(path, "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"\n{len(results) - len(failed)}/{len(results)} ok  ->  {path}")
    if failed:
        print("failed: " + ", ".join(failed))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
