#!/usr/bin/env python3
"""
Sample-apps acceptance (A12): can AgentOS operate the alarm, calendar and notes apps completely through MCP? Repeatable, with evidence.

adb only, no taps. Needs the AgentOS **debug** build and the three sample apps' **debug** builds on the device (the app databases are read
with `run-as`), `adb` and `node` (acp-bridge) on the computer. Do not run it on a phone you cannot afford to touch: it creates and removes
alarms / events / notes that carry the marker `e2e-<run id>`, and leaves other data alone (it still asserts nothing else changed in the steps
that must not write).

    ./gradlew :app:assembleDebug :plugins:samples:alarm:assembleDebug :plugins:samples:calendar:assembleDebug :plugins:samples:notes:assembleDebug \\
              :tests:device:acp-channel:client:assembleDebug
    python3 tests/device/acp-channel/sample_apps_e2e.py --serial $ANDROID_SERIAL                # scripted, deterministic (fake model)
    set -a; . ../.secrets/minimax.env; set +a
    python3 tests/device/acp-channel/sample_apps_e2e.py --serial $ANDROID_SERIAL --live         # natural language, real MiniMax

Flow (every part writes its checks into the result JSON, `results/raw/sample-apps-*.json`; exit code 0 when all checks pass):
  1. install (unless --no-install), start each sample app once, grant AgentOS what it needs, battery-optimisation exemption for AgentOS while it
     runs (a turn of more than about 10 s in the background is otherwise frozen, README "电脑端接入"); the phone's model source is the fake model
     endpoint (scripted) or the real MiniMax (--live; the key goes in through stdin and is removed afterwards).
  2. `ExtensionDebugReceiver list`: the three plugins are discovered and off by default (--allow-enabled skips the "off" check for reruns).
  3. `enable` each, `catalog`: all documented tools are offered under their final names (`mcp__alarm__alarm__alarm_create`...), risk level as
     RiskPolicy gives it for third-party MCP tools: WRITE, and HIGH for `*_delete` (destructiveHint).
  4. desktop access on, pair, `ConsentDebugReceiver mode=allow`, ACP session through acp-bridge.
  5. scripted: one fake-model script per step (deterministic CRUD of each app, failure paths: missing parameter, unknown id, declined
     confirmation, plugin switched off); after each step the app's own database is compared with what the step must have done.
     live: three natural-language prompts, the same database check, plus the tool sequence the model really chose and the times.
  6. clean up what this run created (also after a failed run), consent mode off, plugins off, desktop access off, exemption restored.

Debug receivers of AgentOS that this script talks to (C7b / D5.2): see sample_apps_lib.py (shapes assumed there). AGENTOS_EXT_RECEIVER and
AGENTOS_CONSENT_RECEIVER override the component names.
"""
import argparse
import json
import os
import re
import sys
import tempfile
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import sample_apps_lib as L  # noqa: E402
import sample_apps_scenarios as S  # noqa: E402

TEST_MODEL_KEY = "agtest-fake-model-key"


class Options:
    def __init__(self, live=False, only=None, allow_enabled=False, keep_data=False, step_timeout=90, live_timeout=240):
        self.live, self.only, self.allow_enabled, self.keep_data = live, only, allow_enabled, keep_data
        self.step_timeout, self.live_timeout = step_timeout, live_timeout


def log_line(m):
    print(m, flush=True)


def run_acceptance(env, opts, log=log_line):
    """The whole acceptance against `env` (see RealEnv for what it must offer). Returns the report dict (never raises for a failed check)."""
    run_id = uuid.uuid4().hex[:6]
    today, offset = L.device_today_and_offset(env.adb)
    steps, notes = [], []
    session = None
    ctx = None
    t0 = time.time()

    def record(result):
        steps.append(result)
        log("  %s %s%s" % ("ok  " if result["ok"] else "FAIL", result["id"], "" if result["ok"] else "  " + _first_failure(result)))

    try:
        env.prepare_device(opts)
        env.ensure_model(opts.live)
        ctx = L.Context(env.adb, env.ext, env.consent, None, today, offset, run_id, log=log)
        record(S.run_check_step("setup.discover", "three plugins are discovered" + ("" if opts.allow_enabled else " and off by default"), S.setup_discover(opts.allow_enabled), ctx))
        record(S.run_check_step("setup.enable", "enable the three plugins", S.setup_enable, ctx))
        record(S.run_check_step("setup.catalog", "catalog: documented tools, final names, risk levels", S.setup_catalog, ctx))
        session = env.open_session()
        ctx.session = session
        record(S.run_check_step("setup.consent", "auto-consent allow", S.setup_consent_allow, ctx))
        if all(s["ok"] for s in steps):
            plan = S.live_cases(ctx) if opts.live else S.scripted_steps(ctx)
            for item in plan:
                if opts.only and item_sample(item) not in opts.only:
                    continue
                if isinstance(item, tuple):
                    record(S.run_check_step(item[0], item[1], item[2], ctx))
                elif opts.live:
                    record(S.run_live_case(item, ctx, opts.live_timeout))
                else:
                    record(L.run_step(item, ctx, opts.step_timeout))
            if opts.live:
                for sample in sorted({i.sample for i in plan if opts.only is None or i.sample in opts.only}):
                    record(S.run_check_step(*S.consent_audit_step(sample), ctx))
        else:
            notes.append("setup failed: the app steps were not run")
    except Exception as e:  # noqa: BLE001
        notes.append("driver error: %s: %s" % (type(e).__name__, e))
        steps.append({"id": "driver", "sample": None, "title": "driver", "ok": False, "ms": 0, "error": "%s: %s" % (type(e).__name__, e), "checks": [], "turn": None})
    leftovers = None
    try:
        if ctx is not None and session is not None and not opts.keep_data:
            leftovers = S.cleanup(ctx)
            if leftovers:
                notes.append("cleaned up leftovers: %s" % json.dumps(leftovers))
    except Exception as e:  # noqa: BLE001
        notes.append("cleanup failed: %s: %s (look for data marked e2e-%s)" % (type(e).__name__, e, run_id))
    finally:
        env.finish(notes)
    summary = L.summarize(steps)
    return {"suite": "sample-apps", "mode": "live" if opts.live else "scripted", "runId": run_id, "ok": summary["failed"] == 0 and bool(steps),
            "summary": summary, "steps": steps, "leftovers": leftovers, "notes": notes, "totalSec": round(time.time() - t0, 1)}


def item_sample(item):
    """The app a plan item belongs to (check steps are tuples `(id, title, fn)` whose id is `audit.<app>`)."""
    if isinstance(item, tuple):
        return item[0].split(".", 1)[1]
    return getattr(item, "sample", None)


def _first_failure(result):
    if result.get("error"):
        return "driver error: " + result["error"]
    for c in result["checks"]:
        if not c["ok"]:
            return "%s: expected %s, actual %s" % (c["name"], json.dumps(c["expected"], ensure_ascii=False)[:120], json.dumps(c["actual"], ensure_ascii=False)[:120])
    return "no checks ran"


# ---------------------------------------------------------------------- the real device

class RealEnv:
    def __init__(self, adb, a, log=log_line):
        import desktop_idle as D
        import fake_model
        import run as R
        self.R, self.D, self.fake_model_mod = R, D, fake_model
        self.adb = L.RealAdb(adb)
        self.args, self.log = a, log
        self.ext = L.ExtensionDebug(self.adb)
        self.consent = L.ConsentDebug(self.adb)
        self.gateway = L.GatewayDebug(self.adb)
        self.fake = None
        self.bridge = None
        self.state_dir = tempfile.TemporaryDirectory(prefix="e2e-bridge-")
        self.had_exemption = False
        self.live_key = None

    # ---- device
    def prepare_device(self, opts):
        R, adb = self.R, self.adb
        if not self.args.no_install:
            R.install(adb.base, os.path.join(R.REPO, "app", "build", "outputs", "apk", "debug", "app-debug.apk"))
            R.install(adb.base, os.path.join(R.REPO, "tests", "device", "acp-channel", "client", "build", "outputs", "apk", "debug", "client-debug.apk"))
            for s in L.SAMPLES.values():
                R.install(adb.base, s.apk_path(R.REPO))
        for s in L.SAMPLES.values():
            adb.sh("monkey -p %s -c android.intent.category.LAUNCHER 1" % s.package, check=False)
        time.sleep(2)
        adb.sh("input keyevent KEYCODE_HOME", check=False)
        adb.sh("pm grant %s android.permission.POST_NOTIFICATIONS" % R.APP_PKG, check=False)
        self.had_exemption = ("%s," % R.APP_PKG) in adb.sh("cmd deviceidle whitelist", check=False)
        adb.sh("cmd deviceidle whitelist +%s" % R.APP_PKG, check=False)
        adb.sh("input keyevent KEYCODE_WAKEUP", check=False)
        adb.sh("wm dismiss-keyguard", check=False)
        adb.sh("am force-stop %s" % R.APP_PKG, check=False)

    def ensure_model(self, live):
        R, adb = self.R, self.adb
        if live:
            key = R.live_key()
            if not key:
                raise L.DriverError("--live needs MINIMAX_API_KEY in the environment (it is delivered through stdin, never on a command line)")
            self.live_key = key
            R.push_key(adb.base, "live_key", key)
            r = R.run_one(adb.base, "e2e-live-model", R.INAPP_ACTIVITY, "live-model-set", {}, 60)
            if not (r and r.get("ok")):
                raise L.DriverError("could not set the live model source: %s" % (r and (r.get("summary") or r.get("error"))))
        else:
            self.fake = R.start_fake_model(adb.base, {TEST_MODEL_KEY: "test"})
            r = R.run_one(adb.base, "e2e-model", R.INAPP_ACTIVITY, "handshake", {}, 60)  # also sets the model source to the fake endpoint
            if not (r and r.get("ok")):
                raise L.DriverError("fake model source could not be set up: %s" % (r and (r.get("summary") or r.get("error"))))

    def open_session(self):
        D = self.D
        self.gateway.op("enable")
        paired = self.gateway.op("pair")
        code = paired.get("code")
        if not code:
            raise L.DriverError("no pairing code from DesktopGatewayDebugReceiver: %s" % json.dumps({k: v for k, v in paired.items() if k != "code"})[:200])
        self.bridge = D.Bridge(self.adb.base, os.path.join(self.state_dir.name, "acp-bridge.json"), code=code, label="sample-apps-e2e")
        return L.BridgeSession.open(self.bridge)

    def finish(self, notes):
        R = self.R
        for what, fn in (("consent off", lambda: self.consent.set_mode("off")),
                         ("plugins off", lambda: [self.ext.disable(s.package) for s in L.SAMPLES.values()]),
                         ("bridge close", lambda: self.bridge and self.bridge.close()),
                         ("desktop access off", lambda: self.gateway.op("disable")),
                         ("revoke pairings", lambda: self.gateway.op("revoke_all"))):
            try:
                fn()
            except Exception as e:  # noqa: BLE001
                notes.append("%s failed: %s: %s" % (what, type(e).__name__, e))
        if self.args.live:
            try:
                r = R.run_one(self.adb.base, "e2e-live-clear", R.INAPP_ACTIVITY, "live-model-clear", {}, 60)
                if not (r and r.get("ok")):
                    notes.append("the live model source was NOT cleared: %s" % (r and r.get("summary")))
            except Exception as e:  # noqa: BLE001
                notes.append("live model clear failed: %s: %s" % (type(e).__name__, e))
        if self.fake is not None:
            self.adb.run("reverse", "--remove", "tcp:%d" % self.fake_model_mod.DEVICE_PORT, check=False)
            self.fake.stop()
        if not self.had_exemption:
            self.adb.sh("cmd deviceidle whitelist -%s" % R.APP_PKG, check=False)
        self.state_dir.cleanup()

    def leak_scan(self, report):
        if not self.live_key:
            return None
        return self.R.leak_scan(self.adb.base, self.live_key, report)


def device_info(adb):
    return {"serial": adb.serial, "model": adb.prop("ro.product.model"), "sdk": adb.prop("ro.build.version.sdk"),
            "release": adb.prop("ro.build.version.release"), "fingerprint": adb.prop("ro.build.fingerprint")}


def write_report(report, device, label, here=HERE):
    out = dict(report, device=device, time=time.strftime("%Y-%m-%dT%H:%M:%S%z"))
    d = os.path.join(here, "results", "raw")
    os.makedirs(d, exist_ok=True)
    tag = "-" + label if label else ""
    path = os.path.join(d, "sample-apps-%s-api%s-%s%s-%s.json" % (device["serial"].replace(":", "_"), device["sdk"], report["mode"], tag, time.strftime("%Y%m%d-%H%M%S")))
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    return path


def print_report(report, path, log=log_line):
    s = report["summary"]
    log("")
    log("%d/%d steps ok  (%s, %.0fs)  ->  %s" % (s["passed"], s["steps"], report["mode"], report["totalSec"], path))
    for line in s["failures"][:30]:
        log(line)
    for n in report["notes"]:
        log("note: " + n)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[1], formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--live", action="store_true", help="natural-language prompts to the real MiniMax (key from MINIMAX_API_KEY, delivered through stdin)")
    ap.add_argument("--only", help="comma separated: alarm,calendar,notes")
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--allow-enabled", action="store_true", help="do not require the plugins to be off at the start (rerun after an aborted run)")
    ap.add_argument("--keep-data", action="store_true", help="do not remove what the run created")
    ap.add_argument("--label", default="")
    a = ap.parse_args()
    if not a.serial:
        sys.exit("need --serial or ANDROID_SERIAL")
    import run as R
    adb = R.Adb(a.serial)
    device = device_info(adb)
    print(json.dumps(device, ensure_ascii=False), flush=True)
    env = RealEnv(adb, a)
    opts = Options(live=a.live, only=set(a.only.split(",")) if a.only else None, allow_enabled=a.allow_enabled, keep_data=a.keep_data)
    report = run_acceptance(env, opts)
    if a.live:
        leak = env.leak_scan(report)
        report["leakScan"] = leak
        if leak and (leak["logcatHits"] or leak["resultHits"]):
            report["ok"] = False
            report["notes"].append("THE REAL KEY APPEARS IN THE LOGCAT OR THE RESULT (leakScan): do not share this result")
    path = write_report(report, device, a.label)
    print_report(report, path)
    sys.exit(0 if report["ok"] else 1)


if __name__ == "__main__":
    main()
