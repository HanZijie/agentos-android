#!/usr/bin/env python3
"""Does the configuration that is now in AgentOS' settings actually work? One real MiniMax-M3 turn and one Jev session selection,
over the desktop gateway, on a real phone. Reads NOTHING secret (the keys are already in the phone's Keystore) and changes NO key:
it only turns the desktop access on for the check and off again. It never runs a scenario that resets the model source.

Usage: verify_real_config.py <serial>
"""
import json, os, re, sys, tempfile, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as R  # noqa: E402
import desktop_idle as D  # noqa: E402

SERIAL = sys.argv[1]
adb = R.Adb(SERIAL)
ok = True


def check(label, good, detail=""):
    global ok
    ok = ok and bool(good)
    print(("  ok   " if good else "  FAIL ") + label + ("  " + str(detail)[:220] if detail != "" else ""), flush=True)


def bcast(cls, **kw):
    extra = "".join(f" --es {k} '{v}'" for k, v in kw.items())
    out = adb.sh(f"am broadcast -f 32 -n {cls}{extra}", check=False)
    m = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
    return json.loads(m.group(1).replace("\\/", "/")) if m else {}


GW = D.DEBUG_RECEIVER
JEV = "org.agentos.app/.agent.JevDebugReceiver"
state = tempfile.TemporaryDirectory(prefix="cfg-verify-")
bridge = None
try:
    adb.sh("input keyevent KEYCODE_WAKEUP", check=False)
    bcast(GW, op="enable")
    ms = bcast(GW, op="status")
    check("before any prompt: model source is the real minimax-cn / MiniMax-M3",
          ms.get("modelUsable") is True and str(ms.get("modelBaseUrl")).startswith("https://api.minimaxi.com") and ms.get("modelId") == "MiniMax-M3",
          {k: ms.get(k) for k in ("modelUsable", "modelBaseUrl", "modelId")})
    js = bcast(JEV, op="status")
    check("Jev configured and usable (key masked)", js.get("usable") is True and js.get("keySet") is True, {k: js.get(k) for k in ("endpoint", "keyMasked", "usable")})
    code = bcast(GW, op="pair").get("code")
    bridge = D.Bridge(adb, os.path.join(state.name, "b.json"), code=code, label="cfg-verify")
    init = bridge.request("initialize", {"protocolVersion": 1, "clientCapabilities": {}, "_meta": {"org.agentos": {"extensions": ["sessionAutoSelect"]}}}, 40)
    check("initialize ok", (init.get("msg") or {}).get("result", {}).get("protocolVersion") == 1)

    r = bridge.request("session/new", {"cwd": "/", "mcpServers": []}, 60)
    sid = ((r.get("msg") or {}).get("result") or {}).get("sessionId")
    t = time.time()
    r = bridge.request("session/prompt", {"sessionId": sid, "prompt": [{"type": "text", "text": "用一句话介绍一下你自己。"}]}, 120)
    stop = ((r.get("msg") or {}).get("result") or {}).get("stopReason")
    text = D.chunk_text(r.get("notes", []))
    check("real MiniMax-M3 answered through the saved key (%d chars, %.1f s)" % (len(text), time.time() - t), stop == "end_turn" and len(text) > 5, "stop=%s" % stop)

    t = time.time()
    params = {"cwd": "/", "mcpServers": [], "_meta": {"org.agentos": {"autoSelect": {"query": "再用一句话介绍一下你自己。"}}}}
    r = bridge.request("session/new", params, 60)
    sid2 = ((r.get("msg") or {}).get("result") or {}).get("sessionId")
    err = (r.get("msg") or {}).get("error")
    check("Jev auto-select answered through the saved Jev key (%d ms)" % int((time.time() - t) * 1000), bool(sid2) and not err, "session=%s err=%s same_as_first=%s" % (sid2, err, sid2 == sid))
finally:
    if bridge:
        bridge.close()
    for op in ("revoke_all", "disable"):
        adb.sh(f"am broadcast -f 32 -n {GW} --es op {op}", check=False)
    after = bcast(GW, op="status")
    check("desktop access is off again, model source untouched",
          after.get("enabled") is False and after.get("modelUsable") is True and str(after.get("modelBaseUrl")).startswith("https://api.minimaxi.com"),
          {k: after.get(k) for k in ("enabled", "modelUsable", "modelBaseUrl")})
    check("Jev still configured afterwards", bcast(JEV, op="status").get("keySet") is True)

print("RESULT:", "ok" if ok else "FAILED")
sys.exit(0 if ok else 1)
