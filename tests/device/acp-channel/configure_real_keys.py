#!/usr/bin/env python3
"""Put the real MiniMax (CN) key and the Jev key into AgentOS' settings on a real phone, and KEEP them there.

Same storage path as the settings screens (IAgentControl.setModelSource / JevSources.set), driven over adb with the debug entry
points. Keys come from the environment only (set -a; . .secrets/minimax.env; . .secrets/jev.env; set +a), travel through stdin
(content write), are never printed, and are not cleared afterwards (unlike jev_real_autoselect.py / sample_apps_e2e.py).
Order matters: scenarios whose name does not start with live-/byok- reset the model source to the loopback fake endpoint, so the
model source is set LAST and nothing else is run on the phone afterwards.

Usage: configure_real_keys.py <serial>
"""
import json, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as R  # noqa: E402

SERIAL = sys.argv[1]
adb = R.Adb(SERIAL)
mm = os.environ.get("MINIMAX_API_KEY", "")
jev = os.environ.get("JEV_API_KEY", "")
if not mm or not jev:
    sys.exit("MINIMAX_API_KEY and JEV_API_KEY must be set in the environment")


def mask(k):
    return k[:4] + "..." + k[-4:] if len(k) > 8 else "(short)"


def bcast(cls, **kw):
    extra = "".join(f" --es {k} '{v}'" for k, v in kw.items())
    out = adb.sh(f"am broadcast -f 32 -n {cls}{extra}", check=False)
    m = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
    return json.loads(m.group(1).replace("\\/", "/")) if m else {}


ok = True


def check(label, good, detail=""):
    global ok
    ok = ok and bool(good)
    print(("  ok   " if good else "  FAIL ") + label + ("  " + str(detail)[:200] if detail != "" else ""), flush=True)


# 1) Jev first (does not touch the model source)
R.push_key(adb, "jev_key", jev)
r = bcast("org.agentos.app/.agent.JevDebugReceiver", op="set")
check("Jev key set through JevSources.set", r.get("ok") is True and r.get("keySet") is True and r.get("usable") is True,
      {k: r.get(k) for k in ("ok", "endpoint", "keyMasked", "usable", "error")})

# 2) the model source LAST: the real minimax-cn / MiniMax-M3 (the preset the settings screen offers)
R.push_key(adb, "live_key", mm)
res = R.run_one(adb, "cfg-real-model", R.INAPP_ACTIVITY, "live-model-set", {"provider": "minimax-cn", "model": "MiniMax-M3"}, 60)
print("  scenario:", str(res.get("summary") or res.get("result") or res)[:160])
ms = bcast("org.agentos.app/.agent.DesktopGatewayDebugReceiver", op="status")
check("model source is the real minimax-cn / MiniMax-M3, usable",
      ms.get("modelUsable") is True and str(ms.get("modelBaseUrl")).startswith("https://api.minimaxi.com") and ms.get("modelId") == "MiniMax-M3",
      {k: ms.get(k) for k in ("modelUsable", "modelBaseUrl", "modelId")})

# 3) both stay configured; the key files that were dropped are gone from the app's private dir
for slot in ("jev_key", "live_key"):
    # KeyDropProvider.query answers size=-1 when the file does not exist (it is read and deleted by the receiver / scenario)
    check("dropped key file %s is gone after use (size=-1 means no such file)" % slot, R.key_file_size(adb, slot) == -1, R.key_file_size(adb, slot))
js = bcast("org.agentos.app/.agent.JevDebugReceiver", op="status")
check("Jev still configured (masked: %s)" % mask(jev), js.get("keySet") is True and js.get("usable") is True and js.get("keyMasked") not in (None, ""), js.get("keyMasked"))

# 4) no key in logcat (all buffers)
for label, k in (("MiniMax key", mm), ("Jev key", jev)):
    leak = R.leak_scan(adb, k, {})
    check("%s: 0 logcat hits" % label, leak["logcatHits"] == 0, leak["logcatHits"])

print("RESULT:", "ok" if ok else "FAILED")
sys.exit(0 if ok else 1)
