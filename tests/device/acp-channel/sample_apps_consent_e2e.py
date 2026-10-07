#!/usr/bin/env python3
"""真机全链路（只用 adb）：acp-bridge -> AgentOS(:agent, Pi) -> CapabilityBroker -> ConsentPort -> ExtensionToolHost -> 示例 App。

模型是电脑上的假模型（tests/device/acp-channel/fake_model.py 的 toolCalls 脚本），所以每一步都是确定性的。
用法：python3 /tmp/e2e_full.py <case>   case: denied | allowed
"""
import json, os, sys, tempfile, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as R  # noqa: E402
import desktop_idle as D  # noqa: E402

SERIAL = "38290DLJH0007B"
case = sys.argv[1] if len(sys.argv) > 1 else "denied"
adb = R.Adb(SERIAL)
checks = []


def check(label, ok, detail=""):
    checks.append((label, bool(ok)))
    print(f"  {'ok ' if ok else 'FAIL'} {label}  {str(detail)[:170]}", flush=True)


def n(p, t):
    return f"mcp__{p}__{p}__{t}"


fm = R.start_fake_model(adb, {D.TEST_MODEL_KEY: "test"})
state_dir = tempfile.TemporaryDirectory(prefix="e2e-bridge-")
bridge = None
try:
    adb.sh("input keyevent KEYCODE_WAKEUP", check=False)
    r = R.run_one(adb, "e2e-model", R.INAPP_ACTIVITY, "handshake", {}, 60)
    check("model configured (fake endpoint)", r and r.get("ok"), r and r.get("summary"))
    R.run_one(adb, "e2e-off", R.INAPP_ACTIVITY, "desktop-access", {"on": False}, 60)
    r = R.run_one(adb, "e2e-on", R.INAPP_ACTIVITY, "desktop-access", {"on": True}, 60)
    check("desktop access on", r and r.get("ok"))
    code = D.debug_op(adb, "pair").get("code")
    bridge = D.Bridge(adb, os.path.join(state_dir.name, "acp-bridge.json"), code=code, label="e2e-full")
    init = bridge.request("initialize", {"protocolVersion": 1, "clientCapabilities": {}}, 40)
    check("acp initialize", (init.get("msg") or {}).get("result", {}).get("protocolVersion") == 1)
    new = bridge.request("session/new", {"cwd": "/", "mcpServers": []}, 30)
    sid = ((new.get("msg") or {}).get("result") or {}).get("sessionId")
    check("session/new", bool(sid), sid)

    def run_script(script, timeout=60):
        t = time.time()
        updates = []
        r = bridge.request("session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text": json.dumps(script, ensure_ascii=False)}]},
                           timeout, on_note=lambda m: updates.append(m))
        msg = r.get("msg") or {}
        return {"stop": (msg.get("result") or {}).get("stopReason"), "error": msg.get("error"), "timeout": r.get("timeout", False),
                "text": D.chunk_text(r.get("notes", [])), "notes": r.get("notes", []), "ms": round((time.time() - t) * 1000)}

    if case == "denied":
        # 没有设置自动应答：确认走默认的“一律拒绝”（NotOpen.CONSENT），写工具不应执行
        res = run_script({"toolCalls": [{"name": n("alarm", "alarm_create"), "arguments": {"time": "06:15", "label": "E2E-DENIED"}}],
                          "final": "done"})
        check("prompt ended", res["stop"] in ("end_turn", "refusal", "cancelled") and not res["timeout"], f"stop={res['stop']} {res['ms']}ms")
        tool_updates = [m for m in res["notes"] if ((m.get("params") or {}).get("update") or {}).get("sessionUpdate") in ("tool_call", "tool_call_update")]
        check("tool_call updates reached the ACP client", len(tool_updates) >= 1, f"{len(tool_updates)} updates")
        flat = json.dumps(tool_updates, ensure_ascii=False)
        check("denied outcome visible to client", ("denied" in flat.lower()) or ("failed" in flat.lower()) or ("rejected" in flat.lower()), flat[:200])
        # 反向核对：App 里没有这个闹钟
        out = adb.sh("am broadcast -n org.agentos.app/.ext.ExtensionDebugReceiver --es op call --es name %s --es args '{}' --el timeoutMs 15000" % n("alarm", "alarm_list"), check=False)
        check("alarm NOT created while consent denies", "E2E-DENIED" not in out, "")
    if case == "allowed":
        def consent(mode):
            out = adb.sh("am broadcast -n org.agentos.app/.agent.ConsentDebugReceiver --es op mode --es mode %s" % mode, check=False)
            return out
        def ext_call(name, args):
            import re as _re
            out = adb.sh("am broadcast -n org.agentos.app/.ext.ExtensionDebugReceiver --es op call --es name %s --es args '%s' --el timeoutMs 15000" % (name, json.dumps(args, ensure_ascii=False)), check=False)
            m = _re.search(r'data="(.*)"\s*$', out.strip(), _re.S)
            d = json.loads(m.group(1).replace("\\/", "/")) if m else {}
            res = ((d.get("outcome") or {}).get("result")) or {}
            return res.get("details"), res.get("isError")
        def tool_results(res):
            out = []
            for m in res["notes"]:
                u = (m.get("params") or {}).get("update") or {}
                if u.get("sessionUpdate") == "tool_call_update" and u.get("status") in ("completed", "failed"):
                    out.append(u)
            return out

        out = consent("allow")
        check("consent mode=allow", '"mode":"ALLOW"' in out, out.strip()[-80:])

        # ---- 闹钟：建 -> 改 -> 关 -> 删（全经模型脚本，每一步的参数用上一步的结果）
        res = run_script({"toolCalls": [
            {"mcp": ["alarm", "alarm", "alarm_create"], "arguments": {"time": "06:40", "label": "E2E-MODEL", "days": ["mon", "wed"]}},
            {"mcp": ["alarm", "alarm", "alarm_update"], "arguments": {"id": "$result[0].id", "time": "06:50"}},
            {"mcp": ["alarm", "alarm", "alarm_set_enabled"], "arguments": {"id": "$result[0].id", "enabled": False}},
        ], "final": "alarm done ${result[0].id}"}, timeout=90)
        check("alarm script ended with final text", res["stop"] == "end_turn" and "alarm done" in res["text"], f"stop={res['stop']} text={res['text'][:60]!r} {res['ms']}ms")
        tr = tool_results(res)
        check("3 alarm tool results, all completed", len(tr) == 3 and all(u.get("status") == "completed" for u in tr), [u.get("status") for u in tr])
        data, err = ext_call("mcp__alarm__alarm__alarm_list", {})
        mine = [a for a in (data or {}).get("alarms", []) if a.get("label") == "E2E-MODEL"]
        check("alarm exists with updated time and disabled", len(mine) == 1 and mine[0]["time"] == "06:50" and mine[0]["enabled"] is False, mine)
        aid = mine[0]["id"] if mine else None

        # ---- 删除是 HIGH：自动应答只能“允许一次”，仍然放行
        res = run_script({"toolCalls": [{"mcp": ["alarm", "alarm", "alarm_delete"], "arguments": {"id": aid}}], "final": "deleted"}, timeout=60)
        check("HIGH tool (alarm_delete) allowed once by auto-responder", res["stop"] == "end_turn" and "deleted" in res["text"], f"stop={res['stop']}")
        data, err = ext_call("mcp__alarm__alarm__alarm_list", {})
        check("alarm gone after delete", not any(a.get("label") == "E2E-MODEL" for a in (data or {}).get("alarms", [])), "")

        # ---- 确认请求记录：来源与风险等级随请求一起显示
        out = adb.sh("am broadcast -n org.agentos.app/.agent.ConsentDebugReceiver --es op recent", check=False)
        import re as _re
        m = _re.search(r'data="(.*)"\s*$', out.strip(), _re.S)
        recent = (json.loads(m.group(1).replace("\\/", "/")).get("recent")) if m else []
        deletes = [e for e in recent if e["tool"].endswith("alarm_delete")]
        check("consent request recorded for alarm_delete as HIGH with source line", bool(deletes) and deletes[-1]["risk"] == "HIGH" and bool(deletes[-1]["source"]), deletes[-1] if deletes else recent[-2:])
        check("HIGH request offered no ALLOW_FOR_SESSION / ALWAYS_ALLOW", bool(deletes) and not ({"ALLOW_FOR_SESSION", "ALWAYS_ALLOW"} & set(deletes[-1]["options"])), deletes[-1]["options"] if deletes else "")
        creates = [e for e in recent if e["tool"].endswith("alarm_create")]
        # 真实 ApprovalWriter 接上后（C7b），带 source 的 WRITE 请求提供“始终允许”；自动应答从不选它（测试不改用户策略）
        check("WRITE request offers ALWAYS_ALLOW but the auto-responder never picked it", bool(creates) and "ALWAYS_ALLOW" in creates[-1]["options"] and creates[-1]["answeredWith"] != "ALWAYS_ALLOW", creates[-1] if creates else "")

        # ---- 拒绝：deny 之后写工具不执行
        consent("deny")
        # 前面 mode=allow 选了“本会话内不再询问”，alarm_create 在旧会话里已被记住：拒绝要在新会话里测
        new2 = bridge.request("session/new", {"cwd": "/", "mcpServers": []}, 30)
        sid = ((new2.get("msg") or {}).get("result") or {}).get("sessionId")
        check("fresh session for deny test", bool(sid), sid)
        res = run_script({"toolCalls": [{"mcp": ["alarm", "alarm", "alarm_create"], "arguments": {"time": "05:05", "label": "E2E-DENY"}}], "final": "x"}, timeout=60)
        data, err = ext_call("mcp__alarm__alarm__alarm_list", {})
        check("deny: alarm NOT created", not any(a.get("label") == "E2E-DENY" for a in (data or {}).get("alarms", [])), "")
        consent("off")
finally:
    if bridge:
        bridge.close()
    for op in ("revoke_all", "disable"):
        adb.sh(f"am broadcast -f 32 -n {D.DEBUG_RECEIVER} --es op {op}", check=False)
    adb.run("reverse", "--remove", f"tcp:{R.fake_model.DEVICE_PORT}", check=False)
    fm.stop()

bad = [c for c in checks if not c[1]]
print(f"\n{len(checks) - len(bad)}/{len(checks)} ok")
sys.exit(1 if bad else 0)
