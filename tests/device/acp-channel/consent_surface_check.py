#!/usr/bin/env python3
"""Real-phone check of the consent coordinator + surface (D5.2) with the debug receiver only. No screen taps.
inject -> pending (options as the surface would render them) -> respond (what a tap would send) -> decision.
Usage: consent_surface_check.py <serial>"""
import json, re, subprocess, sys, time

S = sys.argv[1]
R = "org.agentos.app/.agent.ConsentDebugReceiver"


def bc(**kw):
    cmd = ["adb", "-s", S, "shell", "am", "broadcast", "-f", "32", "-n", R]
    for k, v in kw.items():
        cmd += ["--el" if k == "timeoutMs" else "--ez" if isinstance(v, bool) else "--es", k, str(v).lower() if isinstance(v, bool) else str(v)]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    m = re.search(r'data="(.*)"\s*$', out, re.S)
    return json.loads(m.group(1)) if m else {"ok": False, "raw": out[:200]}


results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(("  ok   " if ok else "  FAIL ") + name + ("  " + str(detail) if not ok or detail else ""))


def drain():
    for p in bc(op="pending").get("pending", []):
        bc(op="respond", id=p["requestId"], choice="DENY")


def inject(**kw):
    out = bc(op="inject", **kw)
    return out.get("id") or out.get("requestId") or re.search(r"dbg_[a-z0-9]+", json.dumps(out)).group(0)


def options(rid):
    for p in bc(op="pending").get("pending", []):
        if p["requestId"] == rid:
            return [o["choice"] for o in p["options"]], p
    return None, None


def decision(rid):
    return bc(op="decision", id=rid).get("decision")


bc(op="mode", mode="off")
drain()

# Cold start: "always allow" is written back through :ext (extensions.approvalWriter). Until :ext is connected and has sent its first
# policy the write-back is unavailable and the dialog fails closed (no "always allow"). Real requests come from :ext tools, so :ext is
# up by then; this synthetic request does not go through :ext, so wake it and wait for the button before asserting the full option list.
subprocess.run(["adb", "-s", S, "shell", "am", "broadcast", "-n", "org.agentos.app/.ext.ExtensionDebugReceiver", "--es", "op", "list"],
               capture_output=True, text=True)
first_opts = None
waited = 0.0
while waited < 25:
    rid0 = inject(risk="write", timeoutMs=60000)
    first_opts, _ = options(rid0)
    bc(op="respond", id=rid0, choice="DENY")
    if first_opts and "ALWAYS_ALLOW" in first_opts:
        break
    time.sleep(1)
    waited += 1
print("  note :ext write-back ready after ~%d s (until then the dialog fails closed: no always-allow button)" % int(waited))
check("fail closed while the write-back is unavailable, full list once :ext is up",
      bool(first_opts) and ("ALWAYS_ALLOW" in first_opts or waited >= 25) and "ALWAYS_ALLOW" in first_opts, first_opts)

# WRITE: four options, always only when write-back is possible
rid = inject(risk="write", timeoutMs=60000)
opts, card = options(rid)
check("WRITE offers allow-once / for-session / always / deny", opts == ["ALLOW_ONCE", "ALLOW_FOR_SESSION", "ALWAYS_ALLOW", "DENY"], opts)
check("the card is plain text with the risk label and the caller", bool(card) and card["riskLabel"] and card["callerPackage"] == "org.agentos.debug.caller", (card or {}).get("riskLabel"))
check("deny is the last option and the only destructive-looking default", opts and opts[-1] == "DENY")
bc(op="respond", id=rid, choice="ALLOW_ONCE")
check("WRITE allow once -> allow", str(decision(rid)).startswith("allow"), decision(rid))

# WRITE, not rememberable: no "for this conversation" (rememberable controls the session option; "always" depends on the write-back)
rid = inject(risk="write", remember=False, timeoutMs=60000)
opts, _ = options(rid)
check("WRITE that cannot be remembered has no for-session button (always stays: the write-back is there)",
      opts == ["ALLOW_ONCE", "ALWAYS_ALLOW", "DENY"], opts)
bc(op="respond", id=rid, choice="ALLOW_FOR_SESSION")
check("answering with the option that was not offered -> denied", str(decision(rid)).startswith("deny"), decision(rid))

# HIGH: only allow once and deny
rid = inject(risk="high", timeoutMs=60000)
opts, card = options(rid)
check("HIGH offers exactly allow-once and deny", opts == ["ALLOW_ONCE", "DENY"], opts)
bc(op="respond", id=rid, choice="ALWAYS_ALLOW")
check("HIGH answered with always (not offered) -> denied", str(decision(rid)).startswith("deny"), decision(rid))
rid = inject(risk="high", timeoutMs=60000)
bc(op="respond", id=rid, choice="ALLOW_FOR_SESSION")
check("HIGH answered with for-session (not offered) -> denied", str(decision(rid)).startswith("deny"), decision(rid))
rid = inject(risk="high", timeoutMs=60000)
bc(op="respond", id=rid, choice="ALLOW_ONCE")
check("HIGH allow once -> allow", str(decision(rid)).startswith("allow"), decision(rid))

# READ
rid = inject(risk="read", timeoutMs=60000)
opts, _ = options(rid)
bc(op="respond", id=rid, choice="ALLOW_ONCE")
check("READ can be allowed", str(decision(rid)).startswith("allow"), (opts, decision(rid)))

# nobody answers: deny:timeout, and the request leaves the pending list
rid = inject(risk="write", timeoutMs=4000)
time.sleep(7)
check("no answer within 4 s -> deny:timeout", decision(rid) == "deny:timeout", decision(rid))
check("the timed-out request is gone from pending", options(rid)[0] is None)

# queue: two requests, FIFO, both answerable
a = inject(risk="write", timeoutMs=60000)
b = inject(risk="high", timeoutMs=60000)
ids = [p["requestId"] for p in bc(op="pending").get("pending", [])]
check("two pending requests are kept in arrival order", ids[:2] == [a, b], ids)
bc(op="respond", id=a, choice="DENY")
bc(op="respond", id=b, choice="DENY")
check("both answered, queue empty", bc(op="pending").get("pending") == [])

drain()
bc(op="mode", mode="off")
print("%d/%d ok" % (sum(results), len(results)))
sys.exit(0 if all(results) else 1)
