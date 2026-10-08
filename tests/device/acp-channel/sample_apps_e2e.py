#!/usr/bin/env python3
"""
Sample-apps acceptance (A12): can AgentOS operate the alarm, calendar, notes, todo and sms apps completely through MCP? Repeatable, with evidence.

adb only, no taps. Needs the AgentOS **debug** build and the five sample apps' **debug** builds on the device, `adb` and `node` (acp-bridge)
on the computer. The state of each app is read through the app's own debug `dump` receiver (a real phone has no sqlite3), see sample_apps_lib.py.

**It resets the sample apps before and after the run** (`--es cmd reset`: every alarm, note, calendar event and todo in them is removed and the
alarms are cancelled in the system; the sms app only loses its own outbox and drafts, never the system SMS store): run it on a test phone.
`--no-reset` keeps what is there and only removes what this run created (marker `e2e-<run id>`; the sms outbox rows cannot be removed one by one).

**The sms app runs on emulators only** (serial `emulator-NNNN`): it reads and sends real text messages. The incoming messages are made with
`adb emu sms send`, the send goes to a second emulator when `adb devices` lists one (`--sms-peer 5616|none|auto`). On a real phone the sms app is left out
of everything (not installed, enabled, reset or driven) and the report says "skipped"; `--sms-on-device` allows it (sends only to the number given).

    ./gradlew :app:assembleDebug :plugins:samples:alarm:assembleDebug :plugins:samples:calendar:assembleDebug :plugins:samples:notes:assembleDebug \\
              :plugins:samples:todo:assembleDebug :plugins:samples:sms:assembleDebug :tests:device:acp-channel:client:assembleDebug
    python3 tests/device/acp-channel/sample_apps_e2e.py --serial emulator-5604                  # scripted, deterministic (fake model), all five apps
    python3 tests/device/acp-channel/sample_apps_e2e.py --serial 38290DLJH0007B                 # a real phone: four apps, sms skipped
    set -a; . ../.secrets/minimax.env; set +a
    python3 tests/device/acp-channel/sample_apps_e2e.py --serial emulator-5604 --live           # natural language, real MiniMax-M3
    python3 tests/device/acp-channel/sample_apps_e2e.py --serial emulator-5604 --live --tunnel  # phone without internet (see live_tunnel.py)

Flow (every part writes its checks into the result JSON, `results/raw/sample-apps-*.json`; exit code 0 when all checks pass):
  1. install (unless --no-install), start each sample app once, grant AgentOS what it needs, battery-optimisation exemption for AgentOS while it
     runs (a turn of more than about 10 s in the background is otherwise frozen, README "电脑端接入"); the sms app also gets READ_SMS / SEND_SMS (`pm grant`).
  2. desktop access on from the foreground UI (inapp `desktop-access`: it runs `ensureTestModel` and puts the loopback fake model endpoint on
     the phone), THEN the model: the fake endpoint (scripted) or, with --live, the real MiniMax preset minimax-cn / MiniMax-M3 (key through stdin,
     removed afterwards). `setup.model` reads DesktopGatewayDebugReceiver `status` and checks the model source before anything is asked.
  3. reset of the apps; `ExtensionDebugReceiver list`: the plugins are discovered and off by default (--allow-enabled skips the "off" check).
  4. `enable` each, `catalog`: all 44 documented tools are offered under their final names (`mcp__alarm__alarm__alarm_create`...), risk level as
     RiskPolicy gives it for third-party MCP tools: WRITE (also the read tools, `sms_thread_list` included: readOnlyHint never lowers it), and HIGH for
     `*_delete` and `sms_send` (destructiveHint).
  5. pair, `ConsentDebugReceiver mode=allow`, ACP session through acp-bridge.
  6. scripted: one fake-model script per step (deterministic CRUD of each app, failure paths: missing parameter, unknown id, declined
     confirmation in a new session, plugin switched off); after each step the app's dump is compared with what the step must have done (alarms:
     also that they are really registered with AlarmManager). live: natural-language prompts (alarm, calendar, notes, todo, sms and the cross-app scenario
     of docs/next-apps-plan.md section 9); the verdict is the app state, not a fixed tool sequence (the model may look first), the sequence and the times
     are recorded. The live sms cases never send: the single-app one is declined by the driver, the cross-app one is refused by the app (short number).
  7. reset (or the marker cleanup), consent mode off, plugins off, desktop access off, exemption restored, live model removed.

AGENTOS_EXT_RECEIVER and AGENTOS_CONSENT_RECEIVER override the component names of the AgentOS debug receivers; LIVE_MODEL the live model id.
"""
import argparse
import json
import os
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
    def __init__(self, live=False, tunnel=False, only=None, allow_enabled=False, keep_data=False, reset=True, tell_date=False,
                 step_timeout=90, live_timeout=240, sms_on_device=False, sms_peer="auto"):
        self.live, self.tunnel, self.only, self.allow_enabled, self.keep_data = live, tunnel, only, allow_enabled, keep_data
        self.reset, self.tell_date, self.step_timeout, self.live_timeout = reset, tell_date, step_timeout, live_timeout
        self.sms_on_device, self.sms_peer = sms_on_device, sms_peer


def log_line(m):
    print(m, flush=True)


def run_acceptance(env, opts, log=log_line):
    """The whole acceptance against `env` (see RealEnv for what it must offer). Returns the report dict (never raises for a failed check)."""
    run_id = uuid.uuid4().hex[:6]
    today, offset = L.device_today_and_offset(env.adb)
    apps, skipped = L.select_apps(env.adb, opts.sms_on_device)        # the sms app only on emulators (or with --sms-on-device)
    env.apps = apps                                                    # what gets installed, started, enabled and switched off again
    peer = L.find_sms_peer(env.adb, opts.sms_peer) if "sms" in apps else None
    steps, notes = [], []
    session = None
    ctx = None
    t0 = time.time()

    def record(result):
        steps.append(result)
        log("  %s %s%s" % ("ok  " if result["ok"] else "FAIL", result["id"], "" if result["ok"] else "  " + _first_failure(result)))

    try:
        env.prepare_device(opts)
        env.open_desktop(opts)      # first: its scenario puts the loopback fake model endpoint on the phone
        env.ensure_model(opts)      # then the model (the real one last)
        ctx = L.Context(env.adb, env.ext, env.consent, None, today, offset, run_id, log=log, gateway=env.gateway, exclusive=opts.reset, apps=apps, sms_peer=peer)
        ctx.skipped = skipped
        for item in skipped:
            log("  skip %s: %s" % (item["what"], item["reason"]))
        if peer:
            log("  sms steps send one message to emulator %s (the first other emulator in `adb devices`, or --sms-peer; --sms-peer none to send nothing)" % peer)
        n = len(apps)
        record(S.run_check_step("setup.model", "the phone's model source is the intended one", S.setup_model(opts.live, opts.tunnel), ctx))
        if opts.reset:
            record(S.run_check_step("setup.reset", "the %d apps start empty (debug reset)" % n, S.setup_reset, ctx))
        record(S.run_check_step("setup.discover", "%d plugins are discovered" % n + ("" if opts.allow_enabled else " and off by default"), S.setup_discover(opts.allow_enabled), ctx))
        record(S.run_check_step("setup.enable", "enable the %d plugins" % n, S.setup_enable, ctx))
        record(S.run_check_step("setup.catalog", "catalog: documented tools, final names, risk levels", S.setup_catalog, ctx))
        session = env.open_session()
        ctx.session = session
        record(S.run_check_step("setup.consent", "auto-consent allow", S.setup_consent_allow, ctx))
        if all(s["ok"] for s in steps):
            plan = S.live_cases(ctx, opts.tell_date) if opts.live else S.scripted_steps(ctx)
            for item in plan:
                if opts.only and not set(item_apps(item)) <= opts.only:
                    continue
                if isinstance(item, tuple):
                    record(S.run_check_step(item[0], item[1], item[2], ctx))     # (id, title, fn[, apps])
                elif opts.live:
                    record(S.run_live_case(item, ctx, opts.live_timeout))
                else:
                    record(L.run_step(item, ctx, opts.step_timeout))
            # live: the audit of an app is a plan item right after its case (ConsentDebugReceiver keeps only the latest 50 requests)
        else:
            notes.append("setup failed: the app steps were not run")
    except Exception as e:  # noqa: BLE001
        notes.append("driver error: %s: %s" % (type(e).__name__, e))
        steps.append({"id": "driver", "sample": None, "title": "driver", "ok": False, "ms": 0, "error": "%s: %s" % (type(e).__name__, e), "checks": [], "turn": None})
    leftovers = None
    try:
        if ctx is not None and not opts.keep_data:
            if opts.reset:
                record(S.run_check_step("teardown.reset", "the %d apps are left empty (debug reset)" % len(apps), S.setup_reset, ctx))
            elif session is not None:
                leftovers = S.cleanup(ctx)
                if leftovers:
                    notes.append("cleaned up leftovers: %s" % json.dumps(leftovers))
    except Exception as e:  # noqa: BLE001
        notes.append("cleanup failed: %s: %s (look for data marked e2e-%s)" % (type(e).__name__, e, run_id))
    finally:
        env.finish(notes)
    summary = L.summarize(steps)
    notes += ["skipped %s: %s" % (x["what"], x["reason"]) for x in (ctx.skipped if ctx is not None else skipped)]
    return {"suite": "sample-apps", "mode": "live" if opts.live else "scripted", "runId": run_id, "ok": summary["failed"] == 0 and bool(steps),
            "summary": summary, "apps": apps, "skipped": ctx.skipped if ctx is not None else skipped, "smsPeer": peer,
            "steps": steps, "leftovers": leftovers, "notes": notes, "totalSec": round(time.time() - t0, 1)}


def item_sample(item):
    """The app a plan item belongs to (check steps are tuples `(id, title, fn)`; the app is the first part of the id that names one: `audit.todo`,
    `audit.cross.sms`, `sms.prepare`)."""
    if isinstance(item, tuple):
        return next((part for part in item[0].split(".") if part in L.SAMPLES), None)
    return getattr(item, "sample", None)


def item_apps(item):
    """Every app a plan item touches (a cross-app live case and its audits touch several; for the others it is [item_sample])."""
    if isinstance(item, tuple) and len(item) > 3:
        return list(item[3])
    return list(getattr(item, "apps", None) or [item_sample(item)])


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
        self.tunnel = None
        self.bridge = None
        self.state_dir = tempfile.TemporaryDirectory(prefix="e2e-bridge-")
        self.apps = list(L.SAMPLES)         # the sample apps this run drives (run_acceptance narrows it: no sms on a real phone)
        self.had_exemption = False
        self.live_key = None
        self.tunnel_requests = None

    # ---- device
    def prepare_device(self, opts):
        R, adb = self.R, self.adb
        if not self.args.no_install:
            R.install(adb.base, os.path.join(R.REPO, "app", "build", "outputs", "apk", "debug", "app-debug.apk"))
            R.install(adb.base, os.path.join(R.REPO, "tests", "device", "acp-channel", "client", "build", "outputs", "apk", "debug", "client-debug.apk"))
            for name in self.apps:
                R.install(adb.base, L.SAMPLES[name].apk_path(R.REPO))
        for name in self.apps:
            adb.sh("monkey -p %s -c android.intent.category.LAUNCHER 1" % L.SAMPLES[name].package, check=False)
        if "sms" in self.apps:
            # an emulator: the SMS permissions are granted from the shell (the user would tap "grant"; an app installed with `adb install` is not under the
            # restricted-settings rule, sms README V1 row 1/5). The sms steps revoke them once and restore them.
            for perm in L.SMS_PERMISSIONS:
                adb.sh("pm grant %s %s" % (L.SAMPLES["sms"].package, perm), check=False)
        time.sleep(2)
        adb.sh("input keyevent KEYCODE_HOME", check=False)
        adb.sh("pm grant %s android.permission.POST_NOTIFICATIONS" % R.APP_PKG, check=False)
        self.had_exemption = ("%s," % R.APP_PKG) in adb.sh("cmd deviceidle whitelist", check=False)
        adb.sh("cmd deviceidle whitelist +%s" % R.APP_PKG, check=False)
        adb.sh("input keyevent KEYCODE_WAKEUP", check=False)
        adb.sh("wm dismiss-keyguard", check=False)
        adb.sh("am force-stop %s" % R.APP_PKG, check=False)

    def open_desktop(self, opts):
        """Desktop access on, from the foreground UI path (inapp `desktop-access`, the path that is known to work). That scenario runs
        `ensureTestModel` first: the loopback fake model endpoint is the phone's model source afterwards, so the model is configured after this."""
        R, adb = self.R, self.adb
        R.run_one(adb.base, "e2e-off", R.INAPP_ACTIVITY, "desktop-access", {"on": False}, 60)
        r = R.run_one(adb.base, "e2e-on", R.INAPP_ACTIVITY, "desktop-access", {"on": True}, 60)
        if not (r and r.get("ok")):
            raise L.DriverError("desktop access could not be switched on: %s" % (r and (r.get("summary") or r.get("error"))))
        adb.sh("input keyevent KEYCODE_HOME", check=False)

    def ensure_model(self, opts):
        R, adb = self.R, self.adb
        if opts.live:
            key = R.live_key()
            if not key:
                raise L.DriverError("--live needs MINIMAX_API_KEY in the environment (it is delivered through stdin, never on a command line)")
            self.live_key = key
            if opts.tunnel:
                # no internet on the phone: the model source stays the loopback endpoint, a proxy on the computer adds the real key
                import live_tunnel
                self.tunnel = live_tunnel.MiniMaxTunnel(key).start()
                adb.run("reverse", "tcp:%d" % self.fake_model_mod.DEVICE_PORT, "tcp:%d" % self.tunnel.port)
                R.run_one(adb.base, "e2e-tunnel-model", R.INAPP_ACTIVITY, "handshake", {}, 60)  # sets the loopback source; its fake-echo check cannot pass behind a real model
            else:
                R.push_key(adb.base, "live_key", key)
                model = os.environ.get("LIVE_MODEL", S.LIVE_MODEL_ID)
                r = R.run_one(adb.base, "e2e-live-model", R.INAPP_ACTIVITY, "live-model-set", {"provider": "minimax-cn", "model": model}, 60)
                if not (r and r.get("ok")):
                    raise L.DriverError("could not set the live model source: %s" % (r and (r.get("summary") or r.get("error"))))
        else:
            self.fake = R.start_fake_model(adb.base, {TEST_MODEL_KEY: "test"})
            r = R.run_one(adb.base, "e2e-model", R.INAPP_ACTIVITY, "handshake", {}, 60)  # also sets the model source to the fake endpoint
            if not (r and r.get("ok")):
                raise L.DriverError("fake model source could not be set up: %s" % (r and (r.get("summary") or r.get("error"))))

    def open_session(self):
        D = self.D
        paired = self.gateway.op("pair")
        code = paired.get("code")
        if not code:
            raise L.DriverError("no pairing code from DesktopGatewayDebugReceiver: %s" % json.dumps({k: v for k, v in paired.items() if k != "code"})[:200])
        self.bridge = D.Bridge(self.adb.base, os.path.join(self.state_dir.name, "acp-bridge.json"), code=code, label="sample-apps-e2e")
        return L.BridgeSession.open(self.bridge)

    def finish(self, notes):
        R = self.R
        for what, fn in (("consent off", lambda: self.consent.set_mode("off")),
                         ("plugins off", lambda: [self.ext.disable(L.SAMPLES[n].package) for n in self.apps]),
                         ("bridge close", lambda: self.bridge and self.bridge.close()),
                         ("desktop access off", lambda: self.gateway.op("disable")),
                         ("revoke pairings", lambda: self.gateway.op("revoke_all"))):
            try:
                fn()
            except Exception as e:  # noqa: BLE001
                notes.append("%s failed: %s: %s" % (what, type(e).__name__, e))
        if self.live_key and not self.tunnel:
            try:
                r = R.run_one(self.adb.base, "e2e-live-clear", R.INAPP_ACTIVITY, "live-model-clear", {}, 60)
                if not (r and r.get("ok")):
                    notes.append("the live model source was NOT cleared: %s" % (r and r.get("summary")))
            except Exception as e:  # noqa: BLE001
                notes.append("live model clear failed: %s: %s" % (type(e).__name__, e))
        if self.fake is not None or self.tunnel is not None:
            self.adb.run("reverse", "--remove", "tcp:%d" % self.fake_model_mod.DEVICE_PORT, check=False)
        if self.fake is not None:
            self.fake.stop()
        if self.tunnel is not None:
            self.tunnel_requests = self.tunnel.requests()
            self.tunnel.stop()
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
    ap.add_argument("--tunnel", action="store_true", help="with --live, for a phone without internet: the real key stays on the computer (live_tunnel.py)")
    ap.add_argument("--tell-date", action="store_true", help="with --live, start each prompt with today's date (otherwise the model has to find it out itself, agenda_today)")
    ap.add_argument("--only", help="comma separated: alarm,calendar,notes,todo,sms (the plan items of the other apps are not run; setup still covers all of them)")
    ap.add_argument("--sms-on-device", action="store_true", help="run the sms steps on a REAL phone too (default: only on emulators, serial emulator-NNNN: the sms app "
                    "reads and sends real text messages). Nothing is ever sent to a number the driver was not given")
    ap.add_argument("--sms-peer", default="auto", help="the number the sms steps send to: a second emulator's console port (5616), 'none' (only check the "
                    "confirmation and the refusal), or 'auto' = the first other emulator in `adb devices`")
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--allow-enabled", action="store_true", help="do not require the plugins to be off at the start (rerun after an aborted run)")
    ap.add_argument("--no-reset", action="store_true", help="do not reset the three apps before and after; only remove what this run created")
    ap.add_argument("--keep-data", action="store_true", help="do not remove anything afterwards (no final reset, no cleanup)")
    ap.add_argument("--label", default="")
    a = ap.parse_args()
    if not a.serial:
        sys.exit("need --serial or ANDROID_SERIAL")
    if a.tunnel and not a.live:
        sys.exit("--tunnel only makes sense with --live")
    import run as R
    adb = R.Adb(a.serial)
    device = device_info(adb)
    print(json.dumps(device, ensure_ascii=False), flush=True)
    if not a.no_reset:
        print("note: the alarm, calendar, notes, todo and sms apps on this phone are reset before and after the run (--no-reset keeps them; the sms reset only "
              "clears the app's own outbox and drafts)", flush=True)
    env = RealEnv(adb, a)
    opts = Options(live=a.live, tunnel=a.tunnel, only=set(a.only.split(",")) if a.only else None, allow_enabled=a.allow_enabled,
                   keep_data=a.keep_data, reset=not a.no_reset, tell_date=a.tell_date, sms_on_device=a.sms_on_device, sms_peer=a.sms_peer)
    report = run_acceptance(env, opts)
    if env.tunnel_requests is not None:
        report["tunnelRequests"] = env.tunnel_requests
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
