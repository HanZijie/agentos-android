#!/usr/bin/env python3
"""真机 + 真实 MiniMax：自然语言驱动三个示例 App（只用 adb，key 只经 stdin，不进命令行、日志和结果文件）。

用法：set -a; . .secrets/minimax.env; set +a; python3 sample_apps_live_nl.py [alarm|calendar|notes|all]
"""
import json, os, re, sys, tempfile, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as R  # noqa: E402
import desktop_idle as D  # noqa: E402

SERIAL = "38290DLJH0007B"
adb = R.Adb(SERIAL)
args = [a for a in sys.argv[1:] if not a.startswith("--")]
which = args[0] if args else "all"
TUNNEL = "--tunnel" in sys.argv  # 手机没有互联网时：模型请求经 adb reverse 走电脑上的代理（live_tunnel.py），真 key 不进手机
key = os.environ.get("MINIMAX_API_KEY", "")
if not key:
    sys.exit("MINIMAX_API_KEY is not set")
checks = []
report = {"prompts": []}


def check(label, ok, detail=""):
    checks.append((label, bool(ok)))
    print(f"  {'ok ' if ok else 'FAIL'} {label}  {str(detail)[:200]}", flush=True)


def bcast(cls, **kw):
    extra = "".join(f" --es {k} '{v}'" for k, v in kw.items())
    out = adb.sh(f"am broadcast -n {cls}{extra}", check=False)
    m = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
    return json.loads(m.group(1).replace("\\/", "/")) if m else {}


def ext(op, **kw):
    return bcast("org.agentos.app/.ext.ExtensionDebugReceiver", op=op, **kw)


def call(plugin, tool, args):
    out = adb.sh("am broadcast -n org.agentos.app/.ext.ExtensionDebugReceiver --es op call --es name mcp__%s__%s__%s --es args '%s' --el timeoutMs 15000"
                 % (plugin, plugin, tool, json.dumps(args, ensure_ascii=False)), check=False)
    m = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
    d = json.loads(m.group(1).replace("\\/", "/")) if m else {}
    return (((d.get("outcome") or {}).get("result")) or {}).get("details")


state_dir = tempfile.TemporaryDirectory(prefix="live-nl-")
bridge = None
tunnel = None
try:
    adb.sh("input keyevent KEYCODE_WAKEUP", check=False)
    if TUNNEL:
        import live_tunnel
        tunnel = live_tunnel.MiniMaxTunnel(key).start()
        adb.run("reverse", f"tcp:{R.fake_model.DEVICE_PORT}", f"tcp:{tunnel.port}")
        # handshake 场景会先把模型源配成“假 key + 假模型名、指向回环”的自定义源（ensureTestModel），然后校验假模型的脚本化回显：
        # 背后是真模型时回显校验必然失败，所以这里不看 handshake 的结果，只看模型源本身的状态
        R.run_one(adb, "nl-tunnel-model", R.INAPP_ACTIVITY, "handshake", {}, 60)
        st = D.debug_op(adb, "status")
        check("model source = loopback custom endpoint (tunnelled to real MiniMax; real key never on the phone)",
              st.get("modelUsable") is True and st.get("modelBaseUrl") == "http://127.0.0.1:18787", {k: st.get(k) for k in ("modelUsable", "modelBaseUrl", "modelId")})
    else:
        R.push_key(adb, "live_key", key)
        r = R.run_one(adb, "nl-live-model", R.INAPP_ACTIVITY, "live-model-set", {"provider": "minimax-cn", "model": os.environ.get("LIVE_MODEL", "MiniMax-M3")}, 60)
        check("real MiniMax model configured (key via stdin)", r and r.get("ok"), r and r.get("summary"))
    bcast("org.agentos.app/.agent.ConsentDebugReceiver", op="mode", mode="allow")
    R.run_one(adb, "nl-off", R.INAPP_ACTIVITY, "desktop-access", {"on": False}, 60)
    R.run_one(adb, "nl-on", R.INAPP_ACTIVITY, "desktop-access", {"on": True}, 60)
    code = D.debug_op(adb, "pair").get("code")
    bridge = D.Bridge(adb, os.path.join(state_dir.name, "acp-bridge.json"), code=code, label="live-nl")
    bridge.request("initialize", {"protocolVersion": 1, "clientCapabilities": {}}, 40)
    new = bridge.request("session/new", {"cwd": "/", "mcpServers": []}, 30)
    sid = ((new.get("msg") or {}).get("result") or {}).get("sessionId")
    check("session/new", bool(sid), sid)

    def ask(text, timeout=150):
        t = time.time()
        r = bridge.request("session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text": text}]}, timeout)
        notes = r.get("notes", [])
        tools = []
        for m in notes:
            u = (m.get("params") or {}).get("update") or {}
            if u.get("sessionUpdate") == "tool_call":
                tools.append(u.get("title") or u.get("toolCallId"))
        stop = ((r.get("msg") or {}).get("result") or {}).get("stopReason")
        answer = D.chunk_text(notes)
        report["prompts"].append({"prompt": text, "stop": stop, "tools": tools, "ms": round((time.time() - t) * 1000), "answer": answer[:300]})
        return stop, tools, answer

    if which in ("alarm", "all"):
        print("== alarm (natural language)")
        stop, tools, ans = ask("请帮我设一个明天早上 7 点 15 分的闹钟，标签叫“晨跑”，只在工作日响。")
        check("prompt finished", stop == "end_turn", f"stop={stop} tools={[t.split('__')[-1] for t in tools]}")
        al = [a for a in (call("alarm", "alarm_list", {}) or {}).get("alarms", []) if "晨跑" in (a.get("label") or "")]
        check("alarm exists with label 晨跑", len(al) == 1, al)
        if al:
            a = al[0]
            check("time 07:15", a.get("time") == "07:15", a.get("time"))
            check("weekdays only", set(a.get("days") or []) == {"mon", "tue", "wed", "thu", "fri"}, a.get("days"))
            check("enabled", a.get("enabled") is True, a.get("enabled"))
        stop, tools, ans = ask("把晨跑闹钟改到 7 点半。")
        al = [a for a in (call("alarm", "alarm_list", {}) or {}).get("alarms", []) if "晨跑" in (a.get("label") or "")]
        check("alarm updated to 07:30", len(al) == 1 and al[0].get("time") == "07:30", [a.get("time") for a in al])
        stop, tools, ans = ask("删掉晨跑闹钟。")
        al = [a for a in (call("alarm", "alarm_list", {}) or {}).get("alarms", []) if "晨跑" in (a.get("label") or "")]
        check("alarm deleted", len(al) == 0, al)

    if which in ("notes", "all"):
        print("== notes (natural language)")
        stop, tools, ans = ask("帮我记一条备忘：新品发布会要准备三件事——演示稿、嘉宾名单、物料清单。打上“工作”标签。")
        check("prompt finished", stop == "end_turn", f"stop={stop} tools={[t.split('__')[-1] for t in tools]}")
        found = (call("notes", "note_search", {"query": "新品发布会"}) or {}).get("results", [])
        check("note found by search", len(found) >= 1, [x.get("title") for x in found])
        if found:
            nid = found[0]["id"]
            g = call("notes", "note_get", {"id": nid}) or {}
            body = g.get("content") or ""
            check("note content mentions all three items", all(k in body for k in ("演示稿", "嘉宾名单", "物料清单")), body[:100])
            check("note tagged 工作", "工作" in (g.get("tags") or []), g.get("tags"))
        stop, tools, ans = ask("在刚才那条新品发布会备忘后面补一句：发布日期定在下周五。")
        found = (call("notes", "note_search", {"query": "新品发布会"}) or {}).get("results", [])
        if found:
            g = call("notes", "note_get", {"id": found[0]["id"]}) or {}
            check("appended text present, not duplicated note", "下周五" in (g.get("content") or "") and len(found) == 1, f"{len(found)} notes")

    if which in ("calendar", "all"):
        print("== calendar (natural language)")
        today = time.strftime("%Y-%m-%d")
        stop, tools, ans = ask(f"今天是 {today}（东八区）。帮我在日历里加一个日程：下周三下午 3 点到 4 点和王总开会，地点 3 号会议室，提前 15 分钟提醒我。")
        check("prompt finished", stop == "end_turn", f"stop={stop} tools={[t.split('__')[-1] for t in tools]}")
        evs = (call("calendar", "event_search", {"query": "王总"}) or {}).get("events", [])
        check("event 王总 found", len(evs) >= 1, [e.get("title") for e in evs])
        if evs:
            e = evs[0]
            check("start at 15:00 (+08:00)", "T15:00" in (e.get("start") or ""), e.get("start"))
            check("location 3 号会议室", "3" in (e.get("location") or ""), e.get("location"))
            check("reminder 15 minutes", 15 in (e.get("reminder_minutes") or []), e.get("reminder_minutes"))
            call("calendar", "event_delete", {"id": e["id"]})
finally:
    if bridge:
        bridge.close()
    bcast("org.agentos.app/.agent.ConsentDebugReceiver", op="mode", mode="off")
    for op in ("revoke_all", "disable"):
        adb.sh(f"am broadcast -f 32 -n {D.DEBUG_RECEIVER} --es op {op}", check=False)
    if TUNNEL:
        adb.run("reverse", "--remove", f"tcp:{R.fake_model.DEVICE_PORT}", check=False)
        if tunnel is not None:
            report["tunnelRequests"] = tunnel.requests()
            tunnel.stop()
    else:
        R.run_one(adb, "nl-live-clear", R.INAPP_ACTIVITY, "live-model-clear", {}, 60)
    leak = R.leak_scan(adb, key, report)
    check("key leak scan: 0 logcat hits, 0 result hits", leak["logcatHits"] == 0 and leak["resultHits"] == 0,
          {k: leak[k] for k in ("logcatLines", "logcatHits", "resultHits")})

bad = [c for c in checks if not c[1]]
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results", "raw", f"live-nl-{SERIAL}-{time.strftime('%Y%m%d-%H%M%S')}.json")
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w", encoding="utf-8") as f:
    json.dump({"checks": [{"label": l, "ok": o} for l, o in checks], "report": report}, f, ensure_ascii=False, indent=1)
print(f"\n{len(checks) - len(bad)}/{len(checks)} ok  -> {out}")
sys.exit(1 if bad else 0)
