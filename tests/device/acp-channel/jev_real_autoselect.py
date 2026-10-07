#!/usr/bin/env python3
"""真机 + 真实 Jev + 真实 MiniMax-M3：自动选会话（session/new 的 _meta."org.agentos".autoSelect.query）。

前提：Jev key 已用 JevDebugReceiver 配好（key 只经 stdin）；真实模型源最后配（desktop-access 场景会重置模型源）。
话题故意互不相关，所以对的选择是明确的：
  S1 东京三日游  S2 Kotlin 协程取消  S3 红烧肉做法
  问 “东京第二天去哪里” -> S1；问 “协程取消后 finally 怎么处理” -> S2；问 “写一首关于秋天的诗” -> new_session。
"""
import json, os, re, sys, tempfile, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as R  # noqa: E402
import desktop_idle as D  # noqa: E402

SERIAL = "38290DLJH0007B"
adb = R.Adb(SERIAL)
key = os.environ.get("MINIMAX_API_KEY", "")
if not key:
    sys.exit("MINIMAX_API_KEY is not set")
checks, report = [], {"cases": []}


def check(label, ok, detail=""):
    checks.append((label, bool(ok)))
    print(f"  {'ok ' if ok else 'FAIL'} {label}  {str(detail)[:210]}", flush=True)


def bcast(cls, **kw):
    extra = "".join(f" --es {k} '{v}'" for k, v in kw.items())
    out = adb.sh(f"am broadcast -f 32 -n {cls}{extra}", check=False)
    m = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
    return json.loads(m.group(1).replace("\\/", "/")) if m else {}


st = bcast("org.agentos.app/.agent.JevDebugReceiver", op="status")
check("Jev configured and usable (key masked, not shown)", st.get("usable") is True and st.get("keySet") is True,
      {k: st.get(k) for k in ("endpoint", "keyMasked", "usable")})

state_dir = tempfile.TemporaryDirectory(prefix="jev-real-")
bridge = None
try:
    adb.sh("input keyevent KEYCODE_WAKEUP", check=False)
    R.run_one(adb, "jev-off", R.INAPP_ACTIVITY, "desktop-access", {"on": False}, 60)
    R.run_one(adb, "jev-on", R.INAPP_ACTIVITY, "desktop-access", {"on": True}, 60)
    R.push_key(adb, "live_key", key)
    r = R.run_one(adb, "jev-model", R.INAPP_ACTIVITY, "live-model-set", {"provider": "minimax-cn", "model": "MiniMax-M3"}, 60)
    ms = bcast("org.agentos.app/.agent.DesktopGatewayDebugReceiver", op="status")
    check("model source is the REAL minimax-cn MiniMax-M3", ms.get("modelUsable") is True and str(ms.get("modelBaseUrl")).startswith("https://api.minimaxi.com") and ms.get("modelId") == "MiniMax-M3",
          {k: ms.get(k) for k in ("modelUsable", "modelBaseUrl", "modelId")})
    code = D.debug_op(adb, "pair").get("code")
    bridge = D.Bridge(adb, os.path.join(state_dir.name, "b.json"), code=code, label="jev-real")
    init = bridge.request("initialize", {"protocolVersion": 1, "clientCapabilities": {}, "_meta": {"org.agentos": {"extensions": ["sessionAutoSelect"]}}}, 40)
    caps = json.dumps(init.get("msg"), ensure_ascii=False)
    check("initialize ok (autoSelect extension offered)", (init.get("msg") or {}).get("result", {}).get("protocolVersion") == 1, "sessionAutoSelect" in caps)

    def new_session(query=None):
        params = {"cwd": "/", "mcpServers": []}
        if query is not None:
            params["_meta"] = {"org.agentos": {"autoSelect": {"query": query}}}
        t = time.time()
        r = bridge.request("session/new", params, 60)
        sid = ((r.get("msg") or {}).get("result") or {}).get("sessionId")
        sel = None
        for m in r.get("notes", []):
            u = (m.get("params") or {}).get("update") or {}
            s = ((u.get("_meta") or {}).get("org.agentos") or {}).get("selection")
            if s:
                sel = s
        return sid, sel, round((time.time() - t) * 1000), (r.get("msg") or {}).get("error")

    def ask(sid, text):
        r = bridge.request("session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text": text}]}, 120)
        return ((r.get("msg") or {}).get("result") or {}).get("stopReason"), D.chunk_text(r.get("notes", []))

    seeds = {}
    for name, q in (("tokyo", "我想去东京玩三天，帮我简单规划一下行程。"), ("kotlin", "Kotlin 协程被取消时，CancellationException 是怎么传播的？简单说明。"),
                    ("braise", "红烧肉怎么做才不柴？给我一个简短的步骤。")):
        sid, _, _, err = new_session()
        stop, ans = ask(sid, q)
        seeds[name] = sid
        check(f"seed session {name}: real M3 answered", stop == "end_turn" and len(ans) > 20, f"{sid} stop={stop} chars={len(ans)}")

    cases = [("东京第二天去哪里比较好？", "tokyo"), ("协程取消之后 finally 块里怎么处理挂起函数？", "kotlin"), ("红烧肉要炖多久？", "braise"), ("帮我写一首关于秋天的短诗。", None)]
    for query, want in cases:
        sid, sel, ms_, err = new_session(query)
        report["cases"].append({"query": query, "want": want, "gotSessionId": sid, "selection": sel, "ms": ms_, "error": err})
        if want:
            check(f"autoSelect '{query[:14]}…' -> existing session ({want})", sid == seeds[want], f"got={sid} want={seeds[want]} {ms_}ms sel={sel}")
        else:
            check(f"autoSelect '{query[:14]}…' -> a NEW session (no related candidate)", sid not in seeds.values() and bool(sid), f"got={sid} {ms_}ms sel={sel}")
finally:
    if bridge:
        bridge.close()
    for op in ("revoke_all", "disable"):
        adb.sh(f"am broadcast -f 32 -n {D.DEBUG_RECEIVER} --es op {op}", check=False)
    R.run_one(adb, "jev-clear", R.INAPP_ACTIVITY, "live-model-clear", {}, 60)
    jk = os.environ.get("JEV_API_KEY", "")
    for label, k in (("MiniMax key", key), ("Jev key", jk)):
        if k:
            leak = R.leak_scan(adb, k, report)
            check(f"{label}: 0 logcat hits, 0 result hits", leak["logcatHits"] == 0 and leak["resultHits"] == 0, {x: leak[x] for x in ("logcatHits", "resultHits")})

bad = [c for c in checks if not c[1]]
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results", "raw", f"jev-real-{SERIAL}-{time.strftime('%Y%m%d-%H%M%S')}.json")
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w", encoding="utf-8") as f:
    json.dump({"checks": [{"label": l, "ok": o} for l, o in checks], "report": report}, f, ensure_ascii=False, indent=1)
print(f"\n{len(checks) - len(bad)}/{len(checks)} ok  -> {out}")
sys.exit(1 if bad else 0)
