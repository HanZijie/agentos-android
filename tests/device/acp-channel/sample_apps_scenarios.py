"""Sample-apps acceptance: the scenarios (A12). Building blocks are in sample_apps_lib.py, the driver is sample_apps_e2e.py.

Scripted mode (deterministic, fake model): every Step is one prompt turn. The prompt is a fake-model script (fake_model.py `toolCalls`);
AgentOS runs the real Pi + CapabilityBroker + ExtensionToolHost + the app's MCP service, and after the turn the driver reads the app's
database (sample_apps_lib state readers) and compares it with what the step must have done. Steps that need an id from an earlier step
read it from `ctx.vars` (captured from the tool result the app really returned).

Data written by a run carries the marker `e2e-<run id>` (alarm label, calendar and event titles, note text) so that leftovers can be
found and removed (cleanup()) even after a failed run.
"""
import json
import os
import time
from datetime import date, datetime, timedelta

import sample_apps_lib as L
from sample_apps_lib import Call, Check, DriverError, Step, eq, truth


NOT_OFFERED = "not found|tool_not_in_catalog"


def deny_pre(c):
    """mode=allow answers WRITE with 'allow for this session': the tool is then remembered in the ACP session and a later deny would not even be
    asked. Refusal paths therefore run in a NEW session (integration finding, Pixel 8)."""
    c.session.new_session()
    c.consent.set_mode("deny")


def deny_post(c):
    c.consent.set_mode("allow")


def declined(sample, tool, verify_state):
    """verify for a declined step: the request was recorded as answered DENY (what the user would have chosen), and the data did not change."""
    name = L.model_name(sample, tool)

    def verify(b, a, t, c):
        asked = c.asked(name)
        last = asked[-1] if asked else {}
        return [truth("the confirmation for %s was recorded and declined" % tool, "answeredWith DENY, end ANSWERED",
                      last.get("answeredWith") == "DENY" and last.get("end") == "ANSWERED", {k: last.get(k) for k in ("answeredWith", "end", "options")})] + verify_state(b, a, t, c)
    return verify


def var(v, name):
    if name not in v:
        raise DriverError("needs %r from an earlier step that did not produce it" % name)
    return v[name]


def series_of(event):
    """The id of the event series: a recurring event is reported with an occurrence id (`<series>@<key>`), `series_id` is the row id."""
    return str(event.get("series_id") or event["id"].split("@")[0])


def find(rows, id):
    return next((r for r in rows if r["id"] == id), None)


def fields(what, row, **expect):
    """One check per expected field of a database row (a missing row is one failed check)."""
    if row is None:
        return [Check("%s exists in the app database" % what, False, "a row", "no row")]
    return [eq("%s: %s" % (what, k), want, row.get(k)) for k, want in expect.items()]


def registered(a, id, at=None):
    """The alarm is really set in the system: the app's dump measures it against AlarmManager (PendingIntent FLAG_NO_CREATE)."""
    sched = (a.get("scheduled") or {}).get(id)
    ok = bool(sched and sched["registered"] and (at is None or ("T%s" % at) in (sched["fire_at"] or "")))
    what = "alarm %s is registered with the system AlarmManager%s" % (id, " for %s" % at if at else "")
    return truth(what, "scheduled[].registered true" + (", fire_at at %s" % at if at else ""), ok, sched)


def not_registered(a, id):
    """Switched off / deleted: no longer set in the system (absent from `scheduled`, or registered false)."""
    sched = (a.get("scheduled") or {}).get(id)
    return truth("alarm %s is no longer registered with the system AlarmManager" % id, "absent from scheduled, or registered false",
                 sched is None or not sched["registered"], sched)


MINUTE_MS = 60_000


def _armed_checks(ctx, armed, series_id, fire_ms, minutes_before, title, label):
    """What the calendar's single armed reminder alarm must look like (see [reminder_checks])."""
    out = [eq("%s: exactly one reminder alarm is armed (the app arms only the next reminder)" % label, 1, len(armed))]
    a0 = armed[0] if armed else None
    mine = a0 is not None and a0["series_id"] == series_id
    expected_at = ctx.iso_from_ms(fire_ms)
    if ctx.exclusive or mine:
        out.append(truth("%s: the armed reminder belongs to this event" % label, "series %s" % series_id, mine, a0 and a0["series_id"]))
    if mine:
        out += [truth("%s: fires at %s" % (label, expected_at), expected_at, a0["fire_at_ms"] == fire_ms, a0["fire_at"]),
                eq("%s: minutes before" % label, minutes_before, a0["minutes_before"])]
        if title is not None:
            out.append(eq("%s: the armed reminder shows the event's current title" % label, title, a0["title"]))
    elif not ctx.exclusive:
        # the apps were not empty at the start: an earlier reminder of someone else's event may hold the single slot
        out.append(truth("%s: another event's earlier reminder holds the single slot" % label, "fire_at before %s" % expected_at,
                         a0 is not None and a0["fire_at_ms"] < fire_ms, a0))
    out.append(truth("%s: the armed reminder is registered with the system AlarmManager" % label, "registered true", bool(a0 and a0["registered"]), a0))
    return out


def reminder_checks(ctx, series_id, fire_ms, minutes_before, title=None, label="reminder", timeout=5.0):
    """After an event was created or changed through MCP: the calendar's single armed reminder alarm is the next reminder of the event
    (`reminders_scheduled`: fire_at, minutes_before, title, registered = measured against AlarmManager).

    The app re-arms it with a 250 ms debounce after a data change, so this re-reads the dump for up to `timeout` seconds until it matches
    (a first read that still shows the old alarm is not a failure); what is returned is the last read. When the apps did not start empty
    (--no-reset) an earlier reminder of another event may hold the slot: then it must be earlier than this event's."""
    deadline = time.time() + timeout * L.SLEEP_SCALE
    while True:
        out = _armed_checks(ctx, ctx.state("calendar")["reminders"], series_id, fire_ms, minutes_before, title, label)
        if all(c.ok for c in out) or time.time() >= deadline:
            return out
        time.sleep(0.25 * L.SLEEP_SCALE)


def reminder_gone(ctx, series_id, label="reminder", timeout=5.0):
    """After an event was deleted: no registered reminder alarm of it is left (absent from `reminders_scheduled`, or registered false);
    when the apps started empty, nothing at all is armed."""
    deadline = time.time() + timeout * L.SLEEP_SCALE
    while True:
        snap = ctx.state("calendar")
        live = [r for r in snap["reminders"] if r["registered"]]
        mine = [r for r in live if r["series_id"] == series_id]
        existing = {e["id"] for e in snap["events"]}
        orphans = [r for r in live if r["series_id"] not in existing]
        out = [truth("%s: no reminder alarm of the deleted event is armed any more" % label, "absent from reminders_scheduled, or registered false", not mine, mine),
               truth("%s: every armed reminder alarm belongs to an event that still exists" % label, "no armed reminder of a deleted event", not orphans, orphans)]
        if ctx.exclusive:
            out.append(truth("%s: nothing is armed (the deleted event was the last one with a reminder)" % label, "no registered reminder alarm", not live, live))
        if all(c.ok for c in out) or time.time() >= deadline:
            return out
        time.sleep(0.25 * L.SLEEP_SCALE)


def unchanged(sample_key, name):
    def verify(before, after, turn, ctx):
        return [eq("%s data unchanged" % name, before[sample_key], after[sample_key])]
    return verify


# ---------------------------------------------------------------------- AgentOS side (not an app step)

WAIT_SCALE = 1.0  # unit tests shrink the catalog waits


CATALOG_WAIT_SECONDS = 30.0


def documented(samples=None):
    """{model-facing name: (plugin, tool)} of every tool docs/sample-apps.md section 4 requires, for the given sample apps (default: all three)."""
    return {L.model_name(name, t): (name, t) for name, smp in L.SAMPLES.items() if samples is None or name in samples for t in smp.tools}


class CatalogWait:
    """What [await_catalog] saw last: the catalog, what was still missing / still there, how long it waited."""

    def __init__(self, catalog, missing, still_there, waited_ms):
        self.catalog, self.missing, self.still_there, self.waited_ms = catalog, missing, still_there, waited_ms

    @property
    def ok(self):
        return not self.missing and not self.still_there

    def detail(self):
        """For a check's `actual`: which tools are still missing / still there, per plugin and by their own names, and how long was waited."""
        def by_plugin(names):
            out = {}
            for n in names:
                plugin, tool = documented().get(n, (n.split("__")[1] if n.count("__") >= 3 else "?", n))
                out.setdefault(plugin, []).append(tool)
            return out
        d = {"waitedMs": self.waited_ms}
        if self.missing:
            d["missing"] = by_plugin(self.missing)
        if self.still_there:
            d["stillOffered"] = by_plugin(self.still_there)
        return d


def await_catalog(ctx, present=(), absent=(), timeout=CATALOG_WAIT_SECONDS):
    """Wait until every name in `present` is in the catalog and none of `absent` is, at most `timeout` seconds.

    The three plugins' tools do not appear together (after `enable` each plugin is connected and listed on its own, one after the other), so a
    catalog read right after enable / disable is not evidence of anything. Each round reads the whole catalog (the final read is a complete one,
    not a probe of one tool) and, when something is still missing, waits inside the app for the next catalog change (ExtensionDebugReceiver
    `wait_catalog` for one pending tool, at most 5 s per round) instead of hammering it. On timeout it does not raise: it returns what it last
    saw, so the check that follows can name the tools that are still missing."""
    t0 = time.time()
    deadline = t0 + timeout * WAIT_SCALE
    while True:
        cat = ctx.ext.catalog()
        missing = [n for n in present if n not in cat]
        still = [n for n in absent if n in cat]
        if (not missing and not still) or time.time() >= deadline:
            return CatalogWait(cat, missing, still, round((time.time() - t0) * 1000))
        pending, gone = (missing[0], False) if missing else (still[0], True)
        left_ms = max(1, int((deadline - time.time()) * 1000))
        ctx.ext.wait_tool(pending, absent=gone, timeout_ms=min(left_ms, 5000))
        time.sleep(0.1 * WAIT_SCALE)


def run_check_step(id, title, fn, ctx):
    import time
    t0 = time.time()
    checks, error = [], None
    try:
        checks = fn(ctx)
    except Exception as e:  # noqa: BLE001
        error = "%s: %s" % (type(e).__name__, e)
    ok = error is None and bool(checks) and all(c.ok for c in checks)
    return {"id": id, "sample": None, "title": title, "ok": ok, "ms": round((time.time() - t0) * 1000), "error": error,
            "checks": [c.to_json() for c in checks], "turn": None}


LIVE_MODEL_ID = "MiniMax-M3"
LIVE_BASE_URL = "https://api.minimaxi.com"
FAKE_BASE_URL = "http://127.0.0.1:18787"


def setup_model(live, tunnel=False):
    """The phone's model source is what this run claims it is (read through DesktopGatewayDebugReceiver `status`; no key in it).
    Scenarios whose names do not start with live- / byok- run `ensureTestModel` first and put the loopback fake endpoint back, so the
    real model has to be configured last and checked right before the first prompt."""
    def fn(ctx):
        st = ctx.model_status()
        shown = {k: st.get(k) for k in ("modelUsable", "modelBaseUrl", "modelId")}
        out = [eq("the model source is usable", True, st.get("modelUsable") is True)]
        if live and not tunnel:
            want = os.environ.get("LIVE_MODEL", LIVE_MODEL_ID)
            out.append(truth("the model source is the REAL MiniMax endpoint (not the loopback fake)", "baseUrl starts with %s, modelId %s" % (LIVE_BASE_URL, want),
                             str(st.get("modelBaseUrl") or "").startswith(LIVE_BASE_URL) and st.get("modelId") == want, shown))
        elif live:
            out.append(truth("the model source is the loopback endpoint that is tunnelled to the real MiniMax (the real key never reaches the phone)",
                             "baseUrl %s" % FAKE_BASE_URL, st.get("modelBaseUrl") == FAKE_BASE_URL, shown))
        else:
            out.append(truth("the model source is the fake model endpoint", "baseUrl %s" % FAKE_BASE_URL, st.get("modelBaseUrl") == FAKE_BASE_URL, shown))
        return out
    return fn


def setup_reset(ctx):
    """Start from empty apps: the debug `reset` of the sample apps this run drives (everything in them is removed; the sms app only loses its own outbox and
    drafts, never the system SMS store), then read the state back."""
    out = []
    for r in L.reset_apps(ctx.adb, only=ctx.apps):
        out.append(truth("%s: reset" % r["app"], "the app reports it is empty" + (" and nothing is registered with AlarmManager" if r["app"] == "alarm" else ""), r["ok"], r["detail"]))
    snap = {}
    if "alarm" in ctx.apps:
        snap["alarm"] = len(ctx.state("alarm")["alarms"])
    if "notes" in ctx.apps:
        snap["notes"] = len(ctx.state("notes")["notes"])
    cal = ctx.state("calendar") if "calendar" in ctx.apps else None
    if cal is not None:
        snap["calendar events"] = len(cal["events"])
    if "todo" in ctx.apps:
        snap["todos"] = len(ctx.state("todo")["todos"])
    if "sms" in ctx.apps:
        snap["sms outbox"] = len(ctx.state("sms")["outbox"])
    out.append(truth("the %d apps are empty after the reset" % len(ctx.apps), "0 in each of %s" % ", ".join(snap), not any(snap.values()), snap))
    if cal is not None:
        out.append(truth("calendar: only the default calendar is left and no reminder alarm is armed", "1 default calendar, no registered reminder",
                         len(cal["calendars"]) == 1 and cal["calendars"][0]["is_default"] and not any(r["registered"] for r in cal["reminders"]),
                         {"calendars": [(c["name"], c["is_default"]) for c in cal["calendars"]], "reminders": cal["reminders"]}))
    return out


def setup_discover(allow_enabled):
    def fn(ctx):
        plugins = ctx.ext.plugins()
        out = []
        for name, s in ((n, L.SAMPLES[n]) for n in ctx.apps):
            p = next((x for x in plugins if x["package"] == s.package or (x["package"] is None and x["name"] == name)), None)
            out.append(Check("plugin %s is discovered (package %s)" % (name, s.package), p is not None, "listed by ExtensionDebugReceiver", [x["package"] or x["name"] for x in plugins]))
            if p is not None and not allow_enabled:
                out.append(eq("plugin %s is off by default" % name, False, p["enabled"]))
        return out
    return fn


def setup_enable(ctx):
    for name in ctx.apps:
        ctx.ext.enable(L.SAMPLES[name].package)
    wanted = list(documented(ctx.apps))
    w = await_catalog(ctx, present=wanted)
    plugins = ctx.ext.plugins()
    out = []
    for name in ctx.apps:
        smp = L.SAMPLES[name]
        p = next((x for x in plugins if x["package"] == smp.package), None)
        out.append(eq("plugin %s is on after enable" % name, True, bool(p and p["enabled"])))
    out.append(truth("every documented tool of the %d plugins is in the catalog (they appear one plugin after the other: waited up to %d s)" % (len(ctx.apps), CATALOG_WAIT_SECONDS),
                     "all %d tools present" % len(wanted), w.ok, w.detail()))
    return out


def setup_catalog(ctx):
    # not a single read: the plugins' tools show up one after another (see await_catalog)
    wanted = list(documented(ctx.apps))
    w = await_catalog(ctx, present=wanted)
    cat = w.catalog
    out = [truth("every documented tool was offered within %d s" % CATALOG_WAIT_SECONDS, "all %d tools present" % len(wanted), w.ok, w.detail())]
    extras = {}
    for name in ctx.apps:
        smp = L.SAMPLES[name]
        for t in smp.tools:
            n = L.model_name(name, t)
            out.append(eq("%s is offered as %s with risk" % (t, n), L.expected_risk(t), cat.get(n)))
        prefix = "mcp__%s__%s__" % (name, name)
        extras[name] = sorted(n[len(prefix):] for n in cat if n.startswith(prefix) and n[len(prefix):] not in smp.tools)
    ctx.vars["_extra_tools"] = extras
    return out


def setup_consent_allow(ctx):
    ctx.mark_consent_baseline()
    ctx.consent.set_mode("allow")
    return [truth("auto-consent is on (mode=allow)", "ConsentDebugReceiver accepted mode=allow", True, "ok")]


# ---------------------------------------------------------------------- alarm

def alarm_steps(ctx):
    mark = "e2e-" + ctx.run_id
    pkg = L.SAMPLES["alarm"].package
    S = []

    def v_create(b, a, t, c):
        row = find(a["alarms"], var(c.vars, "alarm_id"))
        return fields("alarm", row, time="07:15", label=mark, days=["mon", "tue"], enabled=True) + [eq("one alarm more", len(b["alarms"]) + 1, len(a["alarms"])),
                                                                                                   registered(a, c.vars["alarm_id"], "07:15")]

    S.append(Step("alarm.create", "alarm", "alarm_create: a 07:15 alarm on Mon+Tue",
                  [Call("alarm", "alarm_create", {"time": "07:15", "label": mark, "days": ["mon", "tue"]})],
                  capture=lambda tools, c: {"alarm_id": str(tools[0].json()["id"])}, verify=v_create))

    def v_list(b, a, t, c):
        r = t.tools[0].json() or {}
        ids = [x.get("id") for x in r.get("alarms", [])]
        return [eq("alarm_list count equals the alarms in the app's dump", len(a["alarms"]), r.get("count")),
                truth("the new alarm is in the list", "id %s in %s" % (c.vars["alarm_id"], ids), c.vars["alarm_id"] in ids, ids)]

    S.append(Step("alarm.list", "alarm", "alarm_list sees it", [Call("alarm", "alarm_list")], verify=v_list))

    S.append(Step("alarm.update", "alarm", "alarm_update: 08:30, new label (days stay)",
                  lambda v: [Call("alarm", "alarm_update", {"id": var(v, "alarm_id"), "time": "08:30", "label": mark + "-b"})],
                  verify=lambda b, a, t, c: fields("alarm", find(a["alarms"], c.vars["alarm_id"]), time="08:30", label=mark + "-b", days=["mon", "tue"], enabled=True)
                  + [registered(a, c.vars["alarm_id"], "08:30")]))

    S.append(Step("alarm.switch_off", "alarm", "alarm_set_enabled false",
                  lambda v: [Call("alarm", "alarm_set_enabled", {"id": var(v, "alarm_id"), "enabled": False})],
                  verify=lambda b, a, t, c: fields("alarm", find(a["alarms"], c.vars["alarm_id"]), enabled=False, time="08:30") + [not_registered(a, c.vars["alarm_id"])]))

    def v_next_off(b, a, t, c):
        r = t.tools[0].json()
        ours = isinstance(r, dict) and (r.get("alarm") or {}).get("id") == c.vars["alarm_id"]
        return [truth("a switched-off alarm is not the next one", "null or another alarm", not ours, r)]

    S.append(Step("alarm.next_while_off", "alarm", "alarm_next ignores the switched-off alarm", [Call("alarm", "alarm_next")], verify=v_next_off))

    S.append(Step("alarm.switch_on", "alarm", "alarm_set_enabled true",
                  lambda v: [Call("alarm", "alarm_set_enabled", {"id": var(v, "alarm_id"), "enabled": True})],
                  verify=lambda b, a, t, c: fields("alarm", find(a["alarms"], c.vars["alarm_id"]), enabled=True) + [registered(a, c.vars["alarm_id"], "08:30")]
                  + [eq("alarm_set_enabled was asked once in this session: 'allow for the session' made the second call go through unasked", 1,
                        len(c.asked(L.model_name("alarm", "alarm_set_enabled"))))]))

    def v_get(b, a, t, c):
        r = t.tools[0].json() or {}
        return [eq("alarm_get id", c.vars["alarm_id"], str(r.get("id"))), eq("alarm_get time", "08:30", r.get("time")),
                truth("an enabled alarm has a next_fire_at", "ISO-8601 time", bool(r.get("next_fire_at")), r.get("next_fire_at"))]

    S.append(Step("alarm.get", "alarm", "alarm_get shows the next ring time",
                  lambda v: [Call("alarm", "alarm_get", {"id": var(v, "alarm_id")})], verify=v_get))

    def v_next(b, a, t, c):
        r = t.tools[0].json()
        enabled_any = any(x["enabled"] for x in a["alarms"])
        shape = (r is None) if not enabled_any else (isinstance(r, dict) and bool(r.get("next_fire_at")) and (r.get("fires_in_minutes") or 0) >= 0)
        return [truth("alarm_next returns the next ring time (or null when nothing is on)", "object with next_fire_at when an alarm is on, null otherwise", shape, r)]

    S.append(Step("alarm.next", "alarm", "alarm_next with an alarm on", [Call("alarm", "alarm_next")], verify=v_next))

    def v_system_next(b, a, t, c):
        own, system = t.tools[0].json(), t.tools[1].json()
        shape = (isinstance(system, dict) and {"next_fire_at", "fires_in_minutes", "owned_by_this_app"} <= set(system) and isinstance(system["fires_in_minutes"], int)
                 and system["fires_in_minutes"] >= 0 and isinstance(system["owned_by_this_app"], bool))
        out = [truth("alarm_system_next returns next_fire_at, fires_in_minutes and owned_by_this_app (an alarm of this app is on, so the phone has a next alarm)",
                     "object with the three fields", shape, system)]
        if shape and isinstance(own, dict) and own.get("next_fire_at"):
            own_ms, sys_ms = L.iso_ms(own["next_fire_at"]), L.iso_ms(system["next_fire_at"])
            if system["owned_by_this_app"]:
                out.append(truth("owned by this app: it is the instant alarm_next gives (same minute)", own["next_fire_at"], abs(own_ms - sys_ms) < MINUTE_MS, system["next_fire_at"]))
            else:
                out.append(truth("owned by another app (the Clock app...): that alarm rings no later than this app's next one", "<= %s" % own["next_fire_at"], sys_ms <= own_ms,
                                 system["next_fire_at"]))
        else:
            out.append(truth("alarm_next gives this app's next alarm to compare with", "an object with next_fire_at", False, own))
        return out

    S.append(Step("alarm.system_next", "alarm", "alarm_system_next: the phone's next alarm, consistent with alarm_next",
                  [Call("alarm", "alarm_next"), Call("alarm", "alarm_system_next")], verify=v_system_next))

    S.append(Step("alarm.delete", "alarm", "alarm_delete (high risk: confirmed by the auto-consent)",
                  lambda v: [Call("alarm", "alarm_delete", {"id": var(v, "alarm_id")})],
                  verify=lambda b, a, t, c: [truth("the alarm is gone from the app's dump", "no alarm with id %s" % c.vars["alarm_id"], find(a["alarms"], c.vars["alarm_id"]) is None, None),
                                             eq("one alarm less", len(b["alarms"]) - 1, len(a["alarms"])), not_registered(a, c.vars["alarm_id"])]))

    def v_system_none(b, a, t, c):
        r = t.tools[0].json()
        if any(x["enabled"] for x in a["alarms"]):
            return [truth("this app still has an alarm on (--no-reset): alarm_system_next is an object or null", "object | null", r is None or isinstance(r, dict), r)]
        return [truth("no alarm of this app is on: alarm_system_next is null or belongs to another app", "null, or owned_by_this_app false",
                      r is None or (isinstance(r, dict) and r.get("owned_by_this_app") is False), r)]

    S.append(Step("alarm.system_next_after_delete", "alarm", "alarm_system_next after the alarm is gone: nothing of this app's is the phone's next alarm",
                  [Call("alarm", "alarm_system_next")], verify=v_system_none))

    # ---- failure paths
    S.append(Step("alarm.err.missing_time", "alarm", "alarm_create without time: error to the model, nothing written",
                  [Call("alarm", "alarm_create", {"label": mark}, ok=False)], verify=unchanged("alarms", "alarm")))
    S.append(Step("alarm.err.bad_time", "alarm", "alarm_create with time 25:99: error, nothing written",
                  [Call("alarm", "alarm_create", {"time": "25:99", "label": mark}, ok=False)], verify=unchanged("alarms", "alarm")))
    S.append(Step("alarm.err.unknown_id", "alarm", "alarm_get with an id that does not exist: error",
                  [Call("alarm", "alarm_get", {"id": "999999999"}, ok=False)], verify=unchanged("alarms", "alarm")))
    S.append(Step("alarm.denied", "alarm", "confirmation declined (new session, mode=deny): tool_denied reaches the model, nothing written",
                  [Call("alarm", "alarm_create", {"time": "06:00", "label": mark}, ok=False, error_has="tool_denied")],
                  pre=deny_pre, post=deny_post, verify=declined("alarm", "alarm_create", unchanged("alarms", "alarm"))))
    S += plugin_off_steps(ctx, "alarm", pkg, Call("alarm", "alarm_list"), "alarms", "alarm")
    return S


def plugin_off_steps(ctx, sample, pkg, probe_call, state_key, label):
    smp = L.SAMPLES[sample]
    names = list(documented({sample}))

    def pre(c):
        c.ext.disable(pkg)
        c.vars["_off_wait"] = await_catalog(c, absent=names)

    def verify(b, a, t, c):
        w = c.vars.pop("_off_wait")
        return [truth("no %s tool is offered while the plugin is off (waited for the catalog to drop them)" % label, "none of mcp__%s__*" % sample, not w.still_there, w.detail()),
                truth("a call to it is refused (Pi: not found, or the broker: tool_not_in_catalog)", "text says so", bool(t.tools) and L.not_offered(t.tools[0].text), t.tools[0].text[:200] if t.tools else None),
                eq("%s data unchanged" % label, b[state_key], a[state_key])]

    off = Step("%s.plugin_off" % sample, sample, "plugin disabled: its tools vanish, a call is refused", [Call(sample, probe_call.tool, probe_call.arguments, ok=False, error_has=NOT_OFFERED)],
               pre=pre, verify=verify)

    def pre_on(c):
        c.ext.enable(pkg)
        c.vars["_on_wait"] = await_catalog(c, present=names)

    def verify_on(b, a, t, c):
        w = c.vars.pop("_on_wait")
        return [truth("all documented %s tools are back after re-enabling (waited up to %d s)" % (label, CATALOG_WAIT_SECONDS), "all %d tools" % len(smp.tools), w.ok, w.detail())]

    on = Step("%s.plugin_on" % sample, sample, "plugin enabled again: tools are back and work", [probe_call], pre=pre_on, verify=verify_on)
    return [off, on]


def consent_audit_step(sample, id=None):
    """A check step (no prompt): what ConsentDebugReceiver recorded for this app is what the design says (source line, risk, options, answers)."""
    return (id or "audit.%s" % sample, "confirmation requests of %s: source line, risk, options, answers" % sample,
            lambda ctx: L.audit_consent(ctx.consent_recent(), sample))


# ---------------------------------------------------------------------- calendar

def calendar_steps(ctx):
    mark = "e2e-" + ctx.run_id
    pkg = L.SAMPLES["calendar"].package
    d = ctx.day(10)
    S = []

    def v_cal(b, a, t, c):
        row = find(a["calendars"], var(c.vars, "cal_id"))
        return fields("calendar", row, name=mark + "-cal") + [eq("one calendar more", len(b["calendars"]) + 1, len(a["calendars"]))]

    S.append(Step("calendar.create_calendar", "calendar", "calendar_create", [Call("calendar", "calendar_create", {"name": mark + "-cal"})],
                  capture=lambda tools, c: {"cal_id": str(tools[0].json()["id"])}, verify=v_cal))

    def v_event(b, a, t, c):
        row = find(a["events"], var(c.vars, "event_id"))
        return fields("event", row, title=mark + " 周会", calendar_id=c.vars["cal_id"], location="A1", recurrence="weekly", reminders=[10], all_day=False,
                      start_ms=c.epoch_ms(d, "10:00"), end_ms=c.epoch_ms(d, "11:00")) + [eq("one event more", len(b["events"]) + 1, len(a["events"]))] \
            + reminder_checks(c, c.vars["event_id"], c.epoch_ms(d, "10:00") - 10 * MINUTE_MS, 10, title=mark + " 周会", label="create")

    S.append(Step("calendar.create_event", "calendar", "event_create: weekly 10:00-11:00 with a reminder, in the new calendar",
                  lambda v: [Call("calendar", "event_create", {"title": mark + " 周会", "start": ctx.local(d, "10:00"), "end": ctx.local(d, "11:00"), "calendar_id": var(v, "cal_id"),
                                                               "location": "A1", "reminder_minutes": [10], "recurrence": "weekly",
                                                               "recurrence_until": ctx.local(ctx.day(38), "23:59")})],
                  capture=lambda tools, c: {"event_id": series_of(tools[0].json())}, verify=v_event))

    def v_list(b, a, t, c):
        r = t.tools[0].json() or {}
        evs = r.get("events", [])
        return [eq("a weekly series shows up once per week in the range (3 occurrences in 15 days)", 3, len(evs)),
                truth("every occurrence belongs to the series", "series_id %s" % c.vars["event_id"], all(e.get("series_id") == c.vars["event_id"] for e in evs), [e.get("series_id") for e in evs])]

    S.append(Step("calendar.list_range", "calendar", "event_list over 15 days expands the series",
                  lambda v: [Call("calendar", "event_list", {"from": ctx.local(d, "00:00"), "to": ctx.local(ctx.day(25), "00:00"), "calendar_id": var(v, "cal_id")})], verify=v_list))

    def v_search(b, a, t, c):
        r = t.tools[0].json() or {}
        ids = [e.get("series_id") for e in r.get("events", [])]
        return [truth("event_search finds the series by its title", "series %s in results" % c.vars["event_id"], c.vars["event_id"] in ids, ids)]

    S.append(Step("calendar.search", "calendar", "event_search by title", [Call("calendar", "event_search", {"query": mark + " 周会"})], verify=v_search))

    def v_free(b, a, t, c):
        r = t.tools[0].json() or {}
        busy_from, busy_to = datetime.fromisoformat(ctx.local(d, "10:00")), datetime.fromisoformat(ctx.local(d, "11:00"))
        overlap = []
        for s in r.get("slots", []):
            s0, s1 = datetime.fromisoformat(s["start"]), datetime.fromisoformat(s["end"])
            if s0 < busy_to and s1 > busy_from:
                overlap.append(s)
        return [truth("free_slots has slots", "a non-empty list", bool(r.get("slots")), r.get("slots")),
                truth("no free slot overlaps the 10:00-11:00 event", "no overlap", not overlap, overlap)]

    S.append(Step("calendar.free_slots", "calendar", "free_slots on the day: the 10:00-11:00 event blocks its hour",
                  [Call("calendar", "free_slots", {"date": d.isoformat(), "duration_minutes": 60})], verify=v_free))

    S.append(Step("calendar.update_event", "calendar", "event_update: new title and place (series stays weekly, reminder alarm stays armed)",
                  lambda v: [Call("calendar", "event_update", {"id": var(v, "event_id"), "title": mark + " 周会(改)", "location": "B2"})],
                  verify=lambda b, a, t, c: fields("event", find(a["events"], c.vars["event_id"]), title=mark + " 周会(改)", location="B2", recurrence="weekly", reminders=[10],
                                                    start_ms=c.epoch_ms(d, "10:00"))
                  + reminder_checks(c, c.vars["event_id"], c.epoch_ms(d, "10:00") - 10 * MINUTE_MS, 10, title=mark + " 周会(改)", label="update")))

    def v_update_reminder(b, a, t, c):
        row = find(a["events"], c.vars["event_id"])
        shown = row and sorted(row["reminders"])
        return [truth("event: reminder_minutes are now [30, 5]", "[5, 30]", shown == [5, 30], row and row["reminders"])] \
            + reminder_checks(c, c.vars["event_id"], c.epoch_ms(d, "10:00") - 30 * MINUTE_MS, 30, title=mark + " 周会(改)", label="update reminder")

    S.append(Step("calendar.update_reminder", "calendar", "event_update reminder_minutes [30, 5]: the armed alarm moves to 30 minutes before",
                  lambda v: [Call("calendar", "event_update", {"id": var(v, "event_id"), "reminder_minutes": [30, 5]})], verify=v_update_reminder))

    d9 = ctx.day(9)

    def v_earlier(b, a, t, c):
        row = find(a["events"], var(c.vars, "event2_id"))
        return fields("event", row, title=mark + " 提前一天", calendar_id=c.vars["cal_id"], reminders=[15], recurrence="none", start_ms=c.epoch_ms(d9, "14:00")) \
            + [eq("one event more", len(b["events"]) + 1, len(a["events"]))] \
            + reminder_checks(c, c.vars["event2_id"], c.epoch_ms(d9, "14:00") - 15 * MINUTE_MS, 15, title=mark + " 提前一天", label="earlier event")

    S.append(Step("calendar.create_earlier", "calendar", "event_create a one-off event a day earlier: it takes the single armed reminder alarm",
                  lambda v: [Call("calendar", "event_create", {"title": mark + " 提前一天", "start": ctx.local(d9, "14:00"), "end": ctx.local(d9, "15:00"),
                                                               "calendar_id": var(v, "cal_id"), "reminder_minutes": [15]})],
                  capture=lambda tools, c: {"event2_id": series_of(tools[0].json())}, verify=v_earlier))

    def v_delete_earlier(b, a, t, c):
        return [truth("the earlier event is gone", "no row", find(a["events"], c.vars["event2_id"]) is None, None),
                eq("one event less", len(b["events"]) - 1, len(a["events"]))] \
            + reminder_checks(c, c.vars["event_id"], c.epoch_ms(d, "10:00") - 30 * MINUTE_MS, 30, title=mark + " 周会(改)", label="after deleting the earlier event")

    S.append(Step("calendar.delete_earlier", "calendar", "event_delete the earlier event (high risk): the alarm goes back to the weekly series",
                  lambda v: [Call("calendar", "event_delete", {"id": var(v, "event2_id")})], verify=v_delete_earlier))

    S.append(Step("calendar.delete_event", "calendar", "event_delete (high risk): its reminder alarm is gone",
                  lambda v: [Call("calendar", "event_delete", {"id": var(v, "event_id")})],
                  verify=lambda b, a, t, c: [truth("the event row is gone", "no row", find(a["events"], c.vars["event_id"]) is None, None),
                                             eq("one event less", len(b["events"]) - 1, len(a["events"]))]
                  + reminder_gone(c, c.vars["event_id"], label="delete")))

    S.append(Step("calendar.delete_calendar", "calendar", "calendar_delete (high risk)",
                  lambda v: [Call("calendar", "calendar_delete", {"id": var(v, "cal_id")})],
                  verify=lambda b, a, t, c: [truth("the calendar row is gone", "no row", find(a["calendars"], c.vars["cal_id"]) is None, None),
                                             eq("one calendar less", len(b["calendars"]) - 1, len(a["calendars"]))]))

    # ---- failure paths
    S.append(Step("calendar.err.missing_title", "calendar", "event_create without title: error, nothing written",
                  [Call("calendar", "event_create", {"start": ctx.local(d, "09:00")}, ok=False)], verify=unchanged("events", "calendar")))
    S.append(Step("calendar.err.bad_time", "calendar", "event_create with an unparsable start: error, nothing written",
                  [Call("calendar", "event_create", {"title": mark, "start": "tomorrow 3pm"}, ok=False)], verify=unchanged("events", "calendar")))
    S.append(Step("calendar.err.unknown_id", "calendar", "event_get with an unknown id: error",
                  [Call("calendar", "event_get", {"id": "no-such-event"}, ok=False)], verify=unchanged("events", "calendar")))
    S.append(Step("calendar.denied", "calendar", "confirmation declined: tool_denied, nothing written",
                  [Call("calendar", "event_create", {"title": mark + " denied", "start": ctx.local(d, "12:00")}, ok=False, error_has="tool_denied")],
                  pre=deny_pre, post=deny_post, verify=declined("calendar", "event_create", unchanged("events", "calendar"))))
    S += plugin_off_steps(ctx, "calendar", pkg, Call("calendar", "calendar_list"), "calendars", "calendar")
    return S


# ---------------------------------------------------------------------- notes

def notes_steps(ctx):
    mark = "e2e-" + ctx.run_id
    pkg = L.SAMPLES["notes"].package
    S = []
    body = "# %s 备忘\n第一行" % mark

    S.append(Step("notes.create", "notes", "note_create with a tag",
                  [Call("notes", "note_create", {"content": body, "tags": ["e2e"]})],
                  capture=lambda tools, c: {"note_id": str(tools[0].json()["id"])},
                  verify=lambda b, a, t, c: fields("note", find(a["notes"], var(c.vars, "note_id")), content=body, tags=["e2e"], status="active")
                  + [eq("one note more", len(b["notes"]) + 1, len(a["notes"]))]))

    S.append(Step("notes.append", "notes", "note_append adds a line at the end",
                  lambda v: [Call("notes", "note_append", {"id": var(v, "note_id"), "text": "追加的一行"})],
                  verify=lambda b, a, t, c: [truth("the body ends with the appended line", "…追加的一行",
                                                     (find(a["notes"], c.vars["note_id"]) or {}).get("content", "").endswith("追加的一行"),
                                                     (find(a["notes"], c.vars["note_id"]) or {}).get("content"))]))

    def v_search(b, a, t, c):
        r = t.tools[0].json() or {}
        ids = [x.get("id") for x in r.get("results", [])]
        return [truth("note_search finds it by the appended text", "id %s in results" % c.vars["note_id"], c.vars["note_id"] in ids, ids)]

    S.append(Step("notes.search", "notes", "note_search", [Call("notes", "note_search", {"query": "追加的一行"})], verify=v_search))

    S.append(Step("notes.retag", "notes", "note_update replaces the tag list",
                  lambda v: [Call("notes", "note_update", {"id": var(v, "note_id"), "tags": ["e2e", "工作"]})],
                  verify=lambda b, a, t, c: fields("note", find(a["notes"], c.vars["note_id"]), tags=["e2e", "工作"], status="active")))

    def v_tags(b, a, t, c):
        r = t.tools[0].json() or {}
        names = [x.get("name") for x in r.get("tags", [])]
        return [truth("tag_list shows the new tags", "e2e and 工作 in %s" % names, "e2e" in names and "工作" in names, names)]

    S.append(Step("notes.tag_list", "notes", "tag_list", [Call("notes", "tag_list")], verify=v_tags))

    for sid, tool, status, title in (("trash", "note_trash", "trashed", "note_trash moves it to the trash"), ("restore", "note_restore", "active", "note_restore brings it back"),
                                      ("trash_again", "note_trash", "trashed", "note_trash again")):
        S.append(Step("notes." + sid, "notes", title, lambda v, tool=tool: [Call("notes", tool, {"id": var(v, "note_id")})],
                      verify=lambda b, a, t, c, status=status: fields("note", find(a["notes"], c.vars["note_id"]), status=status)))

    S.append(Step("notes.delete", "notes", "note_delete from the trash is permanent (high risk)",
                  lambda v: [Call("notes", "note_delete", {"id": var(v, "note_id")})],
                  verify=lambda b, a, t, c: [truth("the note is gone from the app's dump", "no row", find(a["notes"], c.vars["note_id"]) is None, None),
                                             eq("one note less", len(b["notes"]) - 1, len(a["notes"]))]))

    # ---- failure paths
    S.append(Step("notes.create_keep", "notes", "note_create a note that must survive the next refusals",
                  [Call("notes", "note_create", {"content": mark + " keep me"}), ],
                  capture=lambda tools, c: {"keep_id": str(tools[0].json()["id"])},
                  verify=lambda b, a, t, c: fields("note", find(a["notes"], c.vars["keep_id"]), status="active")))
    S.append(Step("notes.err.delete_active", "notes", "note_delete on a note that is not in the trash: refused, the note stays",
                  lambda v: [Call("notes", "note_delete", {"id": var(v, "keep_id")}, ok=False, error_has="trash")],
                  verify=lambda b, a, t, c: fields("note", find(a["notes"], c.vars["keep_id"]), status="active") + [eq("notes unchanged", b["notes"], a["notes"])]))
    S.append(Step("notes.err.missing_text", "notes", "note_append without text: error, nothing written",
                  lambda v: [Call("notes", "note_append", {"id": var(v, "keep_id")}, ok=False)], verify=unchanged("notes", "notes")))
    S.append(Step("notes.err.unknown_id", "notes", "note_get with an unknown id: error",
                  [Call("notes", "note_get", {"id": "no-such-note"}, ok=False)], verify=unchanged("notes", "notes")))
    S.append(Step("notes.denied", "notes", "confirmation declined: tool_denied, nothing written",
                  [Call("notes", "note_create", {"content": mark + " denied"}, ok=False, error_has="tool_denied")],
                  pre=deny_pre, post=deny_post, verify=declined("notes", "note_create", unchanged("notes", "notes"))))
    S += plugin_off_steps(ctx, "notes", pkg, Call("notes", "tag_list"), "notes", "notes")
    return S


# ---------------------------------------------------------------------- todo

def mine(rows, mark, key="title"):
    """The rows this run created (the marker is in their title): lists and searches are judged on these only, so that an app that was not reset
    (--no-reset) does not change the verdict."""
    return [r for r in rows if mark in (r.get(key) or "")]


def ids_of(items):
    return sorted(str(x.get("id")) for x in items or [])


def search_hits(rows, query, status=None):
    """todo_search's rule over the dump: every word must match somewhere in the title, the notes or the tags (case-insensitive); all statuses unless given."""
    words = query.lower().split()
    out = []
    for r in rows:
        if status and r["status"] != status:
            continue
        hay = [r["title"].lower(), (r["notes"] or "").lower(), " ".join(r["tags"]).lower()]
        if all(any(w in h for h in hay) for w in words):
            out.append(r)
    return out


def todo_steps(ctx):
    mark = "e2e-" + ctx.run_id
    pkg = L.SAMPLES["todo"].package
    due = ctx.day(7).isoformat()                   # the PRDs are due on one day (an all-day due)
    timed_day = ctx.day(8)
    timed = ctx.local(timed_day, "17:00")          # a due with a time and the device's offset
    past = ctx.day(-1).isoformat()                 # an all-day due that is over since this morning
    prd = [mark + " 写 PRD：搜索改版", mark + " 写 PRD：会员体系", mark + " 写 PRD：数据看板"]
    S = []

    def v_prd(b, a, t, c):
        out = [eq("three todos more", len(b["todos"]) + 3, len(a["todos"]))]
        for i, tid in enumerate(var(c.vars, "prd_ids")):
            out += fields("PRD todo %d" % (i + 1), find(a["todos"], tid), title=prd[i], status="todo", priority="high", due=due, due_all_day=True, parent_id=None,
                          completed_at=None, tags=["e2e"])
        return out

    S.append(Step("todo.create_prd", "todo", "todo_create x3: 写 PRD, an all-day due, priority high",
                  [Call("todo", "todo_create", {"title": t, "due": due, "priority": "high", "tags": ["e2e"]}) for t in prd],
                  capture=lambda tools, c: {"prd_ids": [str(x.json()["id"]) for x in tools]}, verify=v_prd))

    S.append(Step("todo.create_timed", "todo", "todo_create with a due that has a time and the device offset (priority defaults to medium)",
                  [Call("todo", "todo_create", {"title": mark + " 评审会材料", "due": timed})],
                  capture=lambda tools, c: {"timed_id": str(tools[0].json()["id"])},
                  verify=lambda b, a, t, c: fields("timed todo", find(a["todos"], var(c.vars, "timed_id")), title=mark + " 评审会材料", status="todo", priority="medium",
                                                   due_all_day=False, due_ms=c.epoch_ms(timed_day, "17:00"), tags=[], parent_id=None)
                  + [eq("one todo more", len(b["todos"]) + 1, len(a["todos"]))]))

    S.append(Step("todo.create_overdue", "todo", "todo_create with an all-day due that is already over (yesterday)",
                  [Call("todo", "todo_create", {"title": mark + " 已逾期的事", "due": past, "priority": "low"})],
                  capture=lambda tools, c: {"overdue_id": str(tools[0].json()["id"])},
                  verify=lambda b, a, t, c: fields("overdue todo", find(a["todos"], var(c.vars, "overdue_id")), due=past, due_all_day=True, priority="low", status="todo")))

    S.append(Step("todo.subtask", "todo", "todo_create with parent_id: a subtask under the first PRD",
                  lambda v: [Call("todo", "todo_create", {"title": mark + " 提纲", "parent_id": var(v, "prd_ids")[0]})],
                  capture=lambda tools, c: {"sub_id": str(tools[0].json()["id"])},
                  verify=lambda b, a, t, c: fields("subtask", find(a["todos"], var(c.vars, "sub_id")), title=mark + " 提纲", parent_id=c.vars["prd_ids"][0], status="todo")
                  + [eq("one todo more", len(b["todos"]) + 1, len(a["todos"]))]))

    # ---- failure paths: the error goes to the model, nothing is written
    S.append(Step("todo.err.nested_subtask", "todo", "a subtask of a subtask is refused (one level only), nothing written",
                  lambda v: [Call("todo", "todo_create", {"title": mark + " 孙任务", "parent_id": var(v, "sub_id")}, ok=False)], verify=unchanged("todos", "todo")))
    S.append(Step("todo.err.missing_title", "todo", "todo_create without title: error, nothing written",
                  [Call("todo", "todo_create", {}, ok=False)], verify=unchanged("todos", "todo")))
    S.append(Step("todo.err.bad_date", "todo", "todo_create with a due that is no calendar date (2026-02-30): error, nothing written",
                  [Call("todo", "todo_create", {"title": mark + " bad", "due": "2026-02-30"}, ok=False)], verify=unchanged("todos", "todo")))
    S.append(Step("todo.err.due_without_offset", "todo", "todo_create with a time but no offset: error (no guessing of the time zone), nothing written",
                  [Call("todo", "todo_create", {"title": mark + " bad", "due": "%sT17:00:00" % timed_day.isoformat()}, ok=False)], verify=unchanged("todos", "todo")))
    S.append(Step("todo.err.unknown_id", "todo", "todo_get with an id that does not exist: error",
                  [Call("todo", "todo_get", {"id": "ffffffff"}, ok=False)], verify=unchanged("todos", "todo")))
    S.append(Step("todo.err.set_status_unknown_id", "todo", "todo_set_status on an id that does not exist: error, nothing written",
                  [Call("todo", "todo_set_status", {"id": "ffffffff", "status": "done"}, ok=False)], verify=unchanged("todos", "todo")))
    S.append(Step("todo.err.missing_status", "todo", "todo_set_status without status: error, nothing written",
                  lambda v: [Call("todo", "todo_set_status", {"id": var(v, "prd_ids")[1]}, ok=False)], verify=unchanged("todos", "todo")))
    S.append(Step("todo.err.update_nothing", "todo", "todo_update with no field to change: error, nothing written",
                  lambda v: [Call("todo", "todo_update", {"id": var(v, "prd_ids")[1]}, ok=False)], verify=unchanged("todos", "todo")))
    S.append(Step("todo.err.search_without_query", "todo", "todo_search without query: error",
                  [Call("todo", "todo_search", {}, ok=False)], verify=unchanged("todos", "todo")))

    # ---- status flow on the first PRD: doing, done (completed_at), done again (nothing moves), back to todo (completed_at cleared)
    def status_step(sid, status, title, extra):
        S.append(Step("todo." + sid, "todo", title, lambda v: [Call("todo", "todo_set_status", {"id": var(v, "prd_ids")[0], "status": status})],
                      verify=lambda b, a, t, c: fields("first PRD", find(a["todos"], c.vars["prd_ids"][0]), status=status) + extra(b, a, t, c)))

    status_step("doing", "doing", "todo_set_status doing", lambda b, a, t, c: fields("first PRD", find(a["todos"], c.vars["prd_ids"][0]), completed_at=None))

    def v_done(b, a, t, c):
        row = find(a["todos"], c.vars["prd_ids"][0])
        shown = (t.tools[0].json() or {}).get("completed_at")
        return [truth("completed_at is written by todo_set_status done", "an ISO-8601 time", bool(row and row["completed_at"]), row and row["completed_at"]),
                eq("the tool result shows the same completed_at", row and row["completed_at"], shown)]

    status_step("done", "done", "todo_set_status done writes completed_at", v_done)
    S.append(Step("todo.done_again", "todo", "todo_set_status done on a done todo changes nothing (completed_at stays)",
                  lambda v: [Call("todo", "todo_set_status", {"id": var(v, "prd_ids")[0], "status": "done"})],
                  verify=lambda b, a, t, c: [eq("todos unchanged, completed_at included", b["todos"], a["todos"])]))
    status_step("reopen", "todo", "todo_set_status back to todo clears completed_at", lambda b, a, t, c: fields("first PRD", find(a["todos"], c.vars["prd_ids"][0]), completed_at=None))
    S.append(Step("todo.done_third", "todo", "todo_set_status done on the third PRD (it stays done for the list steps)",
                  lambda v: [Call("todo", "todo_set_status", {"id": var(v, "prd_ids")[2], "status": "done"})],
                  verify=lambda b, a, t, c: fields("third PRD", find(a["todos"], c.vars["prd_ids"][2]), status="done")
                  + [truth("it has completed_at", "an ISO-8601 time", bool((find(a["todos"], c.vars["prd_ids"][2]) or {}).get("completed_at")), None)]))

    # ---- update
    S.append(Step("todo.update", "todo", "todo_update: title, priority, notes, tags (due and status stay)",
                  lambda v: [Call("todo", "todo_update", {"id": var(v, "prd_ids")[1], "title": prd[1] + "（改）", "priority": "low", "notes": "e2e notes", "tags": ["e2e", "工作"]})],
                  verify=lambda b, a, t, c: fields("second PRD", find(a["todos"], c.vars["prd_ids"][1]), title=prd[1] + "（改）", priority="low", notes="e2e notes",
                                                  tags=["e2e", "工作"], due=due, due_all_day=True, status="todo")))
    S.append(Step("todo.clear_due", "todo", 'todo_update due "" clears the due date',
                  lambda v: [Call("todo", "todo_update", {"id": var(v, "prd_ids")[1], "due": ""})],
                  verify=lambda b, a, t, c: fields("second PRD", find(a["todos"], c.vars["prd_ids"][1]), due=None, due_all_day=False, title=prd[1] + "（改）")))

    def v_get(b, a, t, c):
        r = t.tools[0].json() or {}
        row = find(a["todos"], c.vars["prd_ids"][0])
        return [eq("todo_get id", c.vars["prd_ids"][0], r.get("id")),
                eq("todo_get shows the same status / priority / due as the app's dump", [row["status"], row["priority"], row["due"]], [r.get("status"), r.get("priority"), r.get("due")]),
                eq("todo_get lists the subtask", [c.vars["sub_id"]], ids_of(r.get("subtasks")))]

    S.append(Step("todo.get", "todo", "todo_get returns the todo with its subtasks",
                  lambda v: [Call("todo", "todo_get", {"id": var(v, "prd_ids")[0]})], verify=v_get))

    # ---- list / search / summary: judged against the app's dump (ground truth), on this run's rows
    def v_list_default(b, a, t, c):
        r = t.tools[0].json() or {}
        open_rows = [x for x in a["todos"] if x["status"] != "done"]
        out = [eq("todo_list total is the number of todos that are not done (done ones are hidden by default)", len(open_rows), r.get("total")),
               truth("no done todo in the list", "none", not any(x.get("status") == "done" for x in r.get("todos", [])), [x.get("id") for x in r.get("todos", []) if x.get("status") == "done"])]
        if not r.get("has_more"):
            out.append(eq("the list holds exactly the open todos of this run", ids_of(mine(open_rows, mark)), ids_of(mine(r.get("todos", []), mark))))
        return out

    S.append(Step("todo.list_default", "todo", "todo_list without arguments hides done todos", [Call("todo", "todo_list")], verify=v_list_default))

    def v_list_done(b, a, t, c):
        r = t.tools[0].json() or {}
        return [eq("todo_list include_done total is every todo", len(a["todos"]), r.get("total")),
                truth("the done PRD is in it", c.vars["prd_ids"][2], c.vars["prd_ids"][2] in ids_of(r.get("todos", [])) or bool(r.get("has_more")), ids_of(r.get("todos", [])))]

    S.append(Step("todo.list_include_done", "todo", "todo_list include_done:true shows everything", [Call("todo", "todo_list", {"include_done": True})], verify=v_list_done))

    def v_list_status(b, a, t, c):
        r = t.tools[0].json() or {}
        return [eq("status:done lists the done todos of this run", ids_of(mine([x for x in a["todos"] if x["status"] == "done"], mark)), ids_of(mine(r.get("todos", []), mark)))]

    S.append(Step("todo.list_status", "todo", "todo_list status:done", [Call("todo", "todo_list", {"status": "done"})], verify=v_list_status))

    def v_overdue(b, a, t, c):
        r = t.tools[0].json() or {}
        return [eq("overdue_only returns the one todo of this run whose due is over (the future ones are not in it)", [c.vars["overdue_id"]], ids_of(mine(r.get("todos", []), mark)))]

    S.append(Step("todo.list_overdue", "todo", "todo_list overdue_only", [Call("todo", "todo_list", {"overdue_only": True})], verify=v_overdue))

    def v_range(b, a, t, c):
        r = t.tools[0].json() or {}
        want = [x for x in mine(a["todos"], mark) if x["due"] == due and x["due_all_day"]]
        return [eq("due_after = due_before = the PRD date (inclusive bounds, include_done) returns exactly the todos due that day", ids_of(want), ids_of(mine(r.get("todos", []), mark))),
                truth("the timed todo of the next day and the overdue one are not in it", "neither", not ({c.vars["timed_id"], c.vars["overdue_id"]} & set(ids_of(r.get("todos", [])))), ids_of(r.get("todos", [])))]

    S.append(Step("todo.list_due_range", "todo", "todo_list due_after / due_before (inclusive) with include_done",
                  [Call("todo", "todo_list", {"due_after": due, "due_before": due, "include_done": True})], verify=v_range))

    S.append(Step("todo.list_subtasks", "todo", "todo_list parent_id returns the subtasks of that todo",
                  lambda v: [Call("todo", "todo_list", {"parent_id": var(v, "prd_ids")[0], "include_done": True})],
                  verify=lambda b, a, t, c: [eq("only the subtask", [c.vars["sub_id"]], ids_of((t.tools[0].json() or {}).get("todos")))]))

    def v_search(b, a, t, c):
        first, second = t.tools[0].json() or {}, t.tools[1].json() or {}
        return [eq("todo_search finds the todos whose title, notes or tags have every word", ids_of(mine(search_hits(a["todos"], mark + " PRD"), mark)), ids_of(mine(first.get("results", []), mark))),
                eq("todo_search status:done narrows it to the done one", ids_of(mine(search_hits(a["todos"], mark, "done"), mark)), ids_of(mine(second.get("results", []), mark))),
                truth("a hit says where it matched", "matched_in on every result", all(x.get("matched_in") for x in first.get("results", [])), [x.get("matched_in") for x in first.get("results", [])])]

    S.append(Step("todo.search", "todo", "todo_search (all words, any status) and with status:done",
                  [Call("todo", "todo_search", {"query": mark + " PRD"}), Call("todo", "todo_search", {"query": mark, "status": "done"})], verify=v_search))

    def v_summary(b, a, t, c):
        r = t.tools[0].json() or {}
        counts = r.get("counts") or {}
        return [eq("todo_summary counts equal the dump's counts", a["counts"], counts),
                eq("todo_summary total equals the number of todos in the dump (subtasks included)", len(a["todos"]), r.get("total")),
                eq("the counts add up to total", r.get("total"), sum(counts.values())),
                truth("one todo of this run is overdue", ">= 1", isinstance(r.get("overdue"), int) and r["overdue"] >= 1, r.get("overdue")),
                truth("due_today / due_this_week are numbers", "integers", all(isinstance(r.get(k), int) for k in ("due_today", "due_this_week")), [r.get("due_today"), r.get("due_this_week")]),
                eq("today is the device's date", c.today.isoformat(), r.get("today"))]

    S.append(Step("todo.summary", "todo", "todo_summary: counts equal the dump's", [Call("todo", "todo_summary")], verify=v_summary))

    # ---- delete: the parent takes its subtasks with it
    def v_delete_parent(b, a, t, c):
        r = t.tools[0].json() or {}
        pid, sid = c.vars["prd_ids"][0], c.vars["sub_id"]
        kids = [x for x in b["todos"] if x["parent_id"] == pid]
        return [eq("deleted counts the todo and its subtasks", 1 + len(kids), r.get("deleted")), eq("subtasks_deleted", len(kids), r.get("subtasks_deleted")),
                truth("the todo and its subtask are gone from the dump", "no rows", find(a["todos"], pid) is None and find(a["todos"], sid) is None, None),
                eq("two todos less", len(b["todos"]) - 1 - len(kids), len(a["todos"]))]

    S.append(Step("todo.delete_parent", "todo", "todo_delete (high risk) on a todo with a subtask: both are deleted",
                  lambda v: [Call("todo", "todo_delete", {"id": var(v, "prd_ids")[0]})], verify=v_delete_parent))
    S.append(Step("todo.delete_leaf", "todo", "todo_delete on a todo without subtasks",
                  lambda v: [Call("todo", "todo_delete", {"id": var(v, "timed_id")})],
                  verify=lambda b, a, t, c: [eq("deleted", 1, (t.tools[0].json() or {}).get("deleted")), truth("the row is gone", "no row", find(a["todos"], c.vars["timed_id"]) is None, None),
                                             eq("one todo less", len(b["todos"]) - 1, len(a["todos"]))]))

    # ---- declined confirmations (new session, mode=deny): the data stays
    S.append(Step("todo.denied", "todo", "confirmation declined: tool_denied, nothing written",
                  [Call("todo", "todo_create", {"title": mark + " denied"}, ok=False, error_has="tool_denied")],
                  pre=deny_pre, post=deny_post, verify=declined("todo", "todo_create", unchanged("todos", "todo"))))
    S.append(Step("todo.denied_delete", "todo", "todo_delete declined (high risk): tool_denied, the todo stays",
                  lambda v: [Call("todo", "todo_delete", {"id": var(v, "prd_ids")[1]}, ok=False, error_has="tool_denied")],
                  pre=deny_pre, post=deny_post,
                  verify=declined("todo", "todo_delete", lambda b, a, t, c: [eq("todos unchanged", b["todos"], a["todos"]),
                                                                              truth("the todo is still there", c.vars["prd_ids"][1], find(a["todos"], c.vars["prd_ids"][1]) is not None, None)])))
    S += plugin_off_steps(ctx, "todo", pkg, Call("todo", "todo_summary"), "todos", "todo")
    return S


# ---------------------------------------------------------------------- sms (emulators only)

SMS_SENDER = "+12025550143"      # who the incoming test messages come from: a made-up number (the 555-01xx range is reserved for fiction)
SMS_CODE = "482910"
MASK = "\u2022" * 6             # what the app shows instead of a six-digit verification code
SMS_SHORT_NUMBER = "10086"       # a service number: refused unless the user allows short numbers


def wait_until(fn, timeout, interval=0.5):
    """Poll `fn` until it is truthy or `timeout` seconds (scaled by the unit tests) are over; returns its last value."""
    deadline = time.time() + timeout * L.SLEEP_SCALE
    while True:
        v = fn()
        if v or time.time() >= deadline:
            return v
        time.sleep(interval * L.SLEEP_SCALE)


def sms_unchanged(b, a, t, c):
    return [eq("nothing was sent: the outbox rows are the same", b["outbox_keys"], a["outbox_keys"])]


def sms_consent_card(c, risk_tool, to, text, answered=None):
    """What the user was shown for sms_send (ConsentDebugReceiver `recent`): HIGH risk, only Allow once / Deny, and the recipient and the whole text in the arguments."""
    asked = c.asked(L.model_name("sms", risk_tool))
    last = asked[-1] if asked else {}
    out = [truth("the confirmation for %s was shown at risk HIGH with only Allow once / Deny" % risk_tool, "HIGH, [ALLOW_ONCE, DENY]",
                 last.get("risk") == "HIGH" and list(last.get("options") or []) == ["ALLOW_ONCE", "DENY"], {k: last.get(k) for k in ("risk", "options")}),
           truth("the confirmation shows the full recipient and the full text", "to=%s and text=%r in the arguments" % (to, text),
                 to in (last.get("args") or "") and text in (last.get("args") or ""), last.get("args")),
           truth("the confirmation names the plugin and server", L.source_line("sms"), last.get("source") == L.source_line("sms"), last.get("source"))]
    if answered:
        out.append(eq("the confirmation was answered %s" % answered, answered, last.get("answeredWith")))
    return out


def sms_mark(run_id):
    """The marker of the sms texts: the run id with its digits turned into letters (0-9 -> g-p). A run of 4 to 8 digits next to the words "verification code" is
    exactly what the app masks, and the marker has to survive that to be searched for."""
    return "e2e-" + "".join(chr(ord("g") + int(ch)) if ch.isdigit() else ch for ch in run_id)


def sms_steps(ctx):
    mark = sms_mark(ctx.run_id)
    pkg = L.SAMPLES["sms"].package
    peer = ctx.sms_peer
    S = []
    normal = "[%s] Meeting notes are ready. See you at 3pm." % mark
    code_msg = "[%s] Your verification code is %s. It expires in 5 minutes." % (mark, SMS_CODE)
    sent_text = mark + " 纪要已发出，请查收"
    denied_to = peer or SMS_SENDER
    denied_text = mark + " 请确认纪要"

    # ---- settings for the run (restored by sms.restore): codes masked, short numbers refused, a rate limit the run cannot hit
    def prepare(c):
        st = L.SmsState(c.adb)
        before = c.state("sms")
        c.vars["_sms_settings0"] = dict(before["settings"])
        for key, value in (("mask_codes", True), ("allow_short_numbers", False), ("rate_limit", 30)):
            st.set(key, value)
        now = c.state("sms")
        return [truth("the sms app has both SMS permissions (mode full): `pm grant` worked", "mode full, read_sms and send_sms true",
                      now["mode"] == "full" and now["permissions"] == {"read_sms": True, "send_sms": True}, {"mode": now["mode"], "permissions": now["permissions"]}),
                eq("settings for the run: codes masked, short numbers refused, rate limit 30", {"mask_codes": True, "allow_short_numbers": False, "rate_limit": 30}, now["settings"])]

    S.append(("sms.prepare", "sms: both permissions granted, settings for the run", prepare))

    # ---- failure paths of sms_send (HIGH risk: the confirmation is allowed once, then the tool refuses; nothing is sent)
    S.append(Step("sms.err.short_number", "sms", "sms_send to a service number (10086) is refused by default, nothing sent",
                  [Call("sms", "sms_send", {"to": SMS_SHORT_NUMBER, "text": mark + " hi"}, ok=False, error_has="short")], verify=sms_unchanged))
    S.append(Step("sms.err.missing_text", "sms", "sms_send without text: error, nothing sent",
                  [Call("sms", "sms_send", {"to": SMS_SENDER}, ok=False, error_has="text")], verify=sms_unchanged))
    S.append(Step("sms.err.missing_to", "sms", "sms_send without a recipient: error, nothing sent",
                  [Call("sms", "sms_send", {"text": mark + " hi"}, ok=False, error_has="recipient")], verify=sms_unchanged))
    S.append(Step("sms.err.too_long", "sms", "sms_send with a text of 501 characters: refused, nothing sent",
                  [Call("sms", "sms_send", {"to": SMS_SENDER, "text": "a" * 501}, ok=False, error_has="500|too long")], verify=sms_unchanged))

    # ---- incoming messages: made on the emulator, read through MCP
    def seed(c):
        for text in (normal, code_msg):
            L.emu_sms_send(c.adb, SMS_SENDER, text)
        st = L.SmsState(c.adb)

        def arrived():
            err, res = st.call("sms_search", {"query": mark})
            return not err and isinstance(res, dict) and res.get("count") == 2
        ok = bool(wait_until(arrived, 20))
        return [truth("both incoming messages are in the phone's SMS store (read in-process by the app's debug tool, 20 s at most)", "sms_search finds 2 messages with the marker", ok, None)]

    S.append(("sms.seed_inbox", "sms: `adb emu sms send` makes two incoming messages (one with a verification code)", seed))

    def thread_of(r):
        return next((x for x in (r or {}).get("threads", []) if L.SmsState and x.get("address", "").replace(" ", "")[-10:] == SMS_SENDER[-10:]), None)

    def v_threads(b, a, t, c):
        r = t.tools[0].json() or {}
        th = thread_of(r)
        return [truth("sms_thread_list has the conversation with the sender (two messages)", "a thread whose message_count >= 2", bool(th) and th.get("message_count", 0) >= 2, th),
                truth("no thread snippet shows the verification code", "%s in no snippet" % SMS_CODE, not any(SMS_CODE in x.get("snippet", "") for x in r.get("threads", [])),
                      [x.get("snippet") for x in r.get("threads", [])]),
                eq("the result says the codes are masked", "masked", (r.get("masking") or {}).get("verification_codes"))]

    S.append(Step("sms.threads", "sms", "sms_thread_list: the conversation is there, the code in a snippet is masked",
                  [Call("sms", "sms_thread_list")], verify=lambda b, a, t, c: v_threads(b, a, t, c) + sms_unchanged(b, a, t, c)))

    def v_messages(b, a, t, c):
        r = t.tools[0].json() or {}
        msgs = r.get("messages", [])
        coded = next((m for m in msgs if "verification code" in m.get("body", "")), {})
        plain = next((m for m in msgs if "Meeting notes" in m.get("body", "")), {})
        return [eq("sms_message_list has both messages", 2, len([m for m in msgs if mark in m.get("body", "")])),
                truth("the verification code is masked by default (the six digits are replaced, code_masked is true)", "%s in the body, %s not" % (MASK, SMS_CODE),
                      MASK in coded.get("body", "") and SMS_CODE not in coded.get("body", "") and coded.get("code_masked") is True, coded.get("body")),
                eq("a message without a code is returned as it is", normal, plain.get("body")),
                eq("the messages are incoming (type inbox)", ["inbox", "inbox"], sorted(m.get("type") for m in msgs if mark in m.get("body", ""))),
                eq("the result says the codes are masked", "masked", (r.get("masking") or {}).get("verification_codes"))]

    S.append(Step("sms.messages", "sms", "sms_message_list: the code is masked by default",
                  [Call("sms", "sms_message_list", {"address": SMS_SENDER})], verify=v_messages))

    def v_search(b, a, t, c):
        by_marker, by_code = (t.tools[0].json() or {}), (t.tools[1].json() or {})
        return [eq("sms_search finds both messages by the marker", 2, by_marker.get("count")),
                eq("searching for the digits of the code finds nothing while codes are masked (no probing)", 0, by_code.get("count"))]

    S.append(Step("sms.search", "sms", "sms_search: by text; the code digits cannot be searched while masked",
                  [Call("sms", "sms_search", {"query": mark}), Call("sms", "sms_search", {"query": SMS_CODE})], verify=v_search))

    def v_unmask(b, a, t, c):
        r = t.tools[0].json() or {}
        coded = next((m for m in r.get("messages", []) if "verification code" in m.get("body", "")), {})
        return [truth("with `mask_codes` off the code is readable", "%s in the body" % SMS_CODE, SMS_CODE in coded.get("body", "") and not coded.get("code_masked"), coded.get("body")),
                eq("the result says the codes are visible", "visible", (r.get("masking") or {}).get("verification_codes")),
                eq("the app's setting is off", False, a["settings"]["mask_codes"])]

    S.append(Step("sms.unmask", "sms", "sms_message_list with `mask_codes` switched off in the app: the code is readable",
                  [Call("sms", "sms_message_list", {"address": SMS_SENDER})], pre=lambda c: L.SmsState(c.adb).set("mask_codes", False),
                  post=lambda c: L.SmsState(c.adb).set("mask_codes", True), verify=v_unmask))

    def allow_short(c):
        L.SmsState(c.adb).set("allow_short_numbers", True)
        now = c.state("sms")["settings"]
        return [eq("allow_short_numbers is on (the user's setting; emulators call each other by their port number, 4 digits)", True, now["allow_short_numbers"]),
                eq("mask_codes is back on", True, now["mask_codes"])]

    S.append(("sms.allow_short", "sms: the user allows short numbers (the emulators' port numbers)", allow_short))

    # ---- sending: to the other emulator when there is one
    if peer:
        def v_send(b, a, t, c):
            known = {o["id"] for o in b["outbox"]}
            new = [o for o in a["outbox"] if o["id"] not in known]
            r = t.tools[0].json() or {}
            row = new[0] if new else None
            return [eq("one row more in the app's outbox", 1, len(new))] + fields("outbox row", row, to=peer, text=sent_text, parts=1) \
                + [truth("the row is queued, sent or delivered (never failed)", "queued | sent | delivered", bool(row) and row["state"] in ("queued", "sent", "delivered"), row and (row["state"], row["error"])),
                   eq("the result: submitted, not deduplicated, state queued", [True, False, "queued"], [r.get("submitted"), r.get("deduplicated"), r.get("state")]),
                   eq("the result's id is the outbox row's", row and row["id"], r.get("id"))] + sms_consent_card(c, "sms_send", peer, sent_text, "ALLOW_ONCE")

        S.append(Step("sms.send", "sms", "sms_send to the other emulator: high-risk confirmation with the full recipient and text, then the outbox row",
                      [Call("sms", "sms_send", {"to": peer, "text": sent_text})],
                      capture=lambda tools, c: {"sms_id": str(tools[0].json()["id"])}, verify=v_send))

        def settle(c):
            """Wait (15 s at most) for the delivery report: queued -> sent -> delivered. Not delivered is fine (the report may never come), failed is not."""
            sid = var(c.vars, "sms_id")
            wait_until(lambda: (find(c.state("sms")["outbox"], sid) or {}).get("state") in ("delivered", "failed"), 15, 1.0)

        def v_status(b, a, t, c):
            r = t.tools[0].json() or {}
            row = find(a["outbox"], c.vars["sms_id"])
            return [truth("sms_send_status: the message was handed to the network (sent or delivered)", "sent | delivered", r.get("state") in ("sent", "delivered"), {k: r.get(k) for k in ("state", "error")}),
                    eq("parts, sent_parts", [1, 1], [r.get("parts"), r.get("sent_parts")]),
                    truth("the app's dump shows the same message in state sent or delivered", "sent | delivered", bool(row) and row["state"] in ("sent", "delivered"), row and (row["state"], row["error"]))]

        S.append(Step("sms.status", "sms", "sms_send_status and the dump agree: sent (and delivered when the report arrived)",
                      lambda v: [Call("sms", "sms_send_status", {"id": var(v, "sms_id")})], pre=settle, verify=v_status))

        def v_dedup(b, a, t, c):
            r = t.tools[0].json() or {}
            return [eq("the same recipient and text again: deduplicated, the earlier id", [True, c.vars["sms_id"]], [r.get("deduplicated"), r.get("id")]),
                    eq("no second message was sent", b["outbox_keys"], a["outbox_keys"])]

        S.append(Step("sms.dedup", "sms", "sms_send with the same recipient and text again: deduplicated, nothing new in the outbox",
                      [Call("sms", "sms_send", {"to": peer, "text": sent_text})], verify=v_dedup))
    else:
        ctx.skipped.append({"what": "sms.send / sms.status / sms.dedup", "reason": "no second emulator in `adb devices` (or --sms-peer none): the real send, the "
                                                                                "delivery states and the duplicate check were not run; the confirmation and the refusal still are"})

    def v_denied(b, a, t, c):
        return sms_unchanged(b, a, t, c) + sms_consent_card(c, "sms_send", denied_to, denied_text)

    S.append(Step("sms.denied", "sms", "sms_send declined (new session, mode=deny): the card shows the full recipient and text, nothing sent",
                  [Call("sms", "sms_send", {"to": denied_to, "text": denied_text}, ok=False, error_has="tool_denied")],
                  pre=deny_pre, post=deny_post, verify=declined("sms", "sms_send", v_denied)))

    # ---- permissions revoked: compose-only mode
    def revoke(c):
        L.sms_permissions(c.adb, grant=False)

    def regrant(c):
        L.sms_permissions(c.adb, grant=True)

    def v_compose_only(b, a, t, c):
        compose = t.tools[3].json() or {}
        return [eq("mode is compose_only and both permissions are off", ["compose_only", {"read_sms": False, "send_sms": False}], [a["mode"], a["permissions"]]),
                truth("sms_thread_list names the compose-only mode in its error", "Compose-only", L.text_has(t.tools[0].text, "compose-only"), t.tools[0].text[:160]),
                truth("sms_send names it too, and nothing was sent", "Compose-only", L.text_has(t.tools[1].text, "compose-only"), t.tools[1].text[:160]),
                truth("sms_send_status names it", "Compose-only", L.text_has(t.tools[2].text, "compose-only"), t.tools[2].text[:160]),
                eq("sms_compose works without any SMS permission: it opened the composer", True, compose.get("opened")),
                eq("the draft is kept in the app", [(denied_to, mark + " draft")], [(x["to"], x["text"]) for x in a["drafts"] if mark in (x["text"] or "")])] + sms_unchanged(b, a, t, c)

    S.append(Step("sms.compose_only", "sms", "permissions revoked: mode compose_only, the other tools refuse clearly, sms_compose still works",
                  [Call("sms", "sms_thread_list", ok=False), Call("sms", "sms_send", {"to": denied_to, "text": denied_text + "!"}, ok=False),
                   Call("sms", "sms_send_status", {"id": "1"}, ok=False), Call("sms", "sms_compose", {"to": denied_to, "text": mark + " draft"})],
                  pre=revoke, post=regrant, verify=v_compose_only))

    def back(c):
        now = c.state("sms")
        return [eq("after `pm grant` the mode is full again", ["full", {"read_sms": True, "send_sms": True}], [now["mode"], now["permissions"]])]

    S.append(("sms.permissions_back", "sms: permissions granted again, mode full", back))
    S += plugin_off_steps(ctx, "sms", pkg, Call("sms", "sms_thread_list"), "outbox_keys", "sms")

    def restore(c):
        want = c.vars.get("_sms_settings0")
        if not want:
            return [truth("the settings from before the run are known", "recorded by sms.prepare", False, None)]
        st = L.SmsState(c.adb)
        for key, value in want.items():
            st.set(key, value)
        return [eq("the sms settings are as they were before the run", want, c.state("sms")["settings"])]

    S.append(("sms.restore", "sms: settings restored", restore))
    return S


def scripted_steps(ctx):
    """Steps (`Step`) and check steps (a `(id, title, fn)` tuple, no prompt) in order; the audit of an app comes right after its steps
    (ConsentDebugReceiver keeps the latest 50 requests only). Only the apps this run drives (ctx.apps)."""
    builders = {"alarm": alarm_steps, "calendar": calendar_steps, "notes": notes_steps, "todo": todo_steps, "sms": sms_steps}
    plan = []
    for app in ctx.apps:
        if app in builders:
            plan += builders[app](ctx) + [consent_audit_step(app)]
    return plan


# ---------------------------------------------------------------------- leftovers

def leftovers(ctx):
    """Rows this run created (marker in the text) that are still there: {app: [descriptions]}."""
    mark = "e2e-" + ctx.run_id
    out = {}
    a = ctx.state("alarm")["alarms"]
    out["alarm"] = [("alarm_delete", {"id": x["id"]}) for x in a if mark in x["label"]]
    c = ctx.state("calendar")
    ev = [("event_delete", {"id": e["id"]}) for e in c["events"] if mark in e["title"]]
    cal = [("calendar_delete", {"id": x["id"]}) for x in c["calendars"] if mark in x["name"]]
    out["calendar"] = ev + cal
    n = ctx.state("notes")["notes"]
    out["notes"] = [("note_trash", {"id": x["id"]}) for x in n if mark in x["content"] and x["status"] != "trashed"] \
        + [("note_delete", {"id": x["id"]}) for x in n if mark in x["content"]]
    if "todo" in ctx.apps:
        # a parent takes its subtasks with it; the todos of this run all carry the marker, so the top-level ones are enough
        out["todo"] = [("todo_delete", {"id": x["id"]}) for x in ctx.state("todo")["todos"] if mark in x["title"] and not x["parent_id"]]
    return {k: v for k, v in out.items() if v}


def cleanup(ctx, timeout=90):
    """Remove what a (failed) run left behind, with the same path as the run: a scripted turn per app. Returns what was found."""
    found = leftovers(ctx)
    for app, calls in found.items():
        script = {"toolCalls": [{"name": L.model_name(app, tool), "arguments": args} for tool, args in calls], "final": "cleanup-done"}
        ctx.session.prompt(json.dumps(script, ensure_ascii=False), timeout)
    return {app: [t for t, _ in calls] for app, calls in found.items()}


# ---------------------------------------------------------------------- live (real model, natural language)

class LiveCase:
    """One natural-language prompt. `apps` (a cross-app case) makes `verify` get {app: state} dicts for before / after instead of one app's state;
    `pre` / `post` run around the prompt (post also after a failure)."""

    def __init__(self, id, sample, prompt, verify, preface="", apps=None, pre=None, post=None):
        self.id, self.sample, self.prompt, self.verify, self.preface = id, sample, prompt, verify, preface
        self.apps, self.pre, self.post = apps, pre, post

    @property
    def text(self):
        """What is sent: the natural-language prompt, with the date in front when the run was started with --tell-date."""
        return self.preface + self.prompt


def next_weekday(today, weekday, next_week):
    """The date of `weekday` (Mon=0) in the next ISO week (next_week) or the next strictly-later occurrence."""
    from datetime import timedelta
    if next_week:
        monday = today - timedelta(days=today.weekday())
        return monday + timedelta(days=7 + weekday)
    delta = (weekday - today.weekday()) % 7 or 7
    return today + timedelta(days=delta)


# What a person would say, with every fact the check needs in the sentence: a prompt that leaves out something the model must have (the note's
# text, the meeting's end and place) gets a clarifying question back, which is valid model behaviour and not an app fault. The verdict stays the app
# state; the sentences stay natural Chinese.
LIVE_PROMPTS = {
    # the check: a new alarm at 07:00, switched on, one-time (no repeat days), really set in the system
    "alarm": "帮我设一个明天早上 7 点的闹钟，叫我起床，只响这一次。",
    # the check: a new event with 王总 in it, next Wednesday 15:00, a 15 minute reminder (armed in the system)
    "calendar": "下周三下午 3 点到 4 点和王总开会，地点在 3 号会议室，提前 15 分钟提醒我。",
    # the check: a new note that mentions 新品发布, tagged 工作
    "notes": "帮我记一条备忘：新品发布会要准备三件事——演示稿、嘉宾名单、物料清单。打上“工作”标签。",
}


def live_cases(ctx, tell_date=False):
    preface = "今天是 %s（设备时区 UTC%s）。" % (ctx.today.isoformat(), ctx.tz_offset) if tell_date else ""

    def v_alarm(b, a, t, c):
        new = [x for x in a["alarms"] if x["id"] not in {y["id"] for y in b["alarms"]}]
        ours = [x for x in new if x["time"] == "07:00"]
        return [truth("an alarm at 07:00 was created", "one new alarm with time 07:00", bool(ours), new),
                truth("it is enabled and one-time (no repeat days), for 'tomorrow morning'", "enabled, days []", bool(ours) and ours[0]["enabled"] and ours[0]["days"] == [], ours),
                truth("it is really set in the system (AlarmManager), at 07:00", "scheduled[].registered true, fire_at at 07:00",
                      bool(ours) and registered(a, ours[0]["id"], "07:00").ok, (a.get("scheduled") or {}).get(ours[0]["id"]) if ours else None)]

    def v_event(b, a, t, c):
        known = {y["id"] for y in b["events"]}
        new = [e for e in a["events"] if e["id"] not in known]
        wed = {next_week_wed.isoformat() for next_week_wed in (next_weekday(c.today, 2, True), next_weekday(c.today, 2, False))}
        hit = [e for e in new if "王总" in e["title"] or "王总" in (e["description"] or "")]
        starts = []
        for e in hit:
            local = datetime.fromtimestamp(e["start_ms"] / 1000, tz=datetime.fromisoformat(c.local(c.today, "00:00")).tzinfo)
            starts.append(local.strftime("%Y-%m-%d %H:%M"))
        right_time = any(s[11:] == "15:00" and s[:10] in wed for s in starts)
        return [truth("a new event mentions 王总", "one new event with 王总 in its title", bool(hit), [e["title"] for e in new]),
                truth("it starts next Wednesday 15:00 (next ISO week, or the next Wednesday after today)", "date in %s, 15:00" % sorted(wed), right_time, starts),
                truth("it has a 15 minute reminder", "reminders contains 15", any(15 in e["reminders"] for e in hit), [e["reminders"] for e in hit])] \
            + (reminder_checks(c, hit[0]["id"], hit[0]["start_ms"] - 15 * MINUTE_MS, 15, label="live reminder") if hit and 15 in hit[0]["reminders"] else [])

    def v_note(b, a, t, c):
        known = {y["id"] for y in b["notes"]}
        new = [n for n in a["notes"] if n["id"] not in known]
        hit = [n for n in new if "新品发布" in n["title"] + n["content"]]
        return [truth("a new note about 新品发布", "one new note mentioning it", bool(hit), [n["title"] for n in new]),
                truth("tagged 工作", "tags contains 工作", any("工作" in n["tags"] for n in hit), [n["tags"] for n in hit])]

    plan = []
    for app, prompt, verify in (("alarm", LIVE_PROMPTS["alarm"], v_alarm), ("calendar", LIVE_PROMPTS["calendar"], v_event), ("notes", LIVE_PROMPTS["notes"], v_note)):
        if app in ctx.apps:
            plan += [LiveCase("live." + app, app, prompt, verify, preface), consent_audit_step(app)]
    plan += live_cases_next(ctx, preface)
    return plan


# The todo, sms and cross-app prompts. The sms ones carry the recipient's number (a second emulator's port, or a made-up one: the driver never sends a live
# message to anything else). "PRD" is the product manager's own word for the document; the three names make the sentence complete.
LIVE_PROMPTS_NEXT = {
    # the check: a new todo about 复盘, priority high, with a due date within two weeks
    "todo": "帮我加一条待办：周五之前把季度复盘写完，优先级高。",
    # the check: sms_send was attempted and its confirmation shown with the number and the text; the driver declines it, nothing is sent
    "sms": "给 {number} 发一条短信，内容是：纪要已发出，请查收。",
    # plan section 9. The short message goes out now (an assistant cannot wait for the end of a meeting).
    "cross": "下周三下午 3 点和王总开需求评审会，提前半小时叫我；先把三个 PRD（搜索改版、会员体系、数据看板）列成待办，下周五前写完；开完会要给王总（{number}）发短信确认纪要，这条现在就帮我发出去。",
    "cross_without_sms": "下周三下午 3 点和王总开需求评审会，提前半小时叫我；先把三个 PRD（搜索改版、会员体系、数据看板）列成待办，下周五前写完。",
}


def live_sms_number(ctx):
    """The number written into the live sms prompts: the second emulator's port, otherwise a made-up emulator port that is not this emulator's own."""
    return ctx.sms_peer or ("5556" if str(ctx.adb.serial).endswith("5554") else "5554")


def live_sms_prepare(ctx):
    """Before a live case that may reach sms_send: short numbers refused (the app then refuses an emulator port even if the confirmation is allowed), a rate limit
    that cannot get in the way. The settings from before the run are kept in ctx.vars and put back by [live_sms_restore]."""
    st = L.SmsState(ctx.adb)
    ctx.vars.setdefault("_sms_settings0", dict(ctx.state("sms")["settings"]))
    for key, value in (("allow_short_numbers", False), ("rate_limit", 30)):
        st.set(key, value)


def live_sms_restore(ctx):
    want = ctx.vars.get("_sms_settings0")
    if want:
        st = L.SmsState(ctx.adb)
        for key, value in want.items():
            st.set(key, value)


def cross_audit(app):
    """The audit after the cross-app case: the same checks as audit.<app>, but an app the model did not touch (it may have used the calendar's reminder instead of
    an alarm) or whose requests are older than the receiver's window is not a failure."""
    def fn(ctx):
        entries = ctx.consent_recent()
        if not any(str(e.get("tool", "")).startswith("mcp__%s__%s__" % (app, app)) for e in entries):
            return [truth("%s: no confirmation request in the recorded window (not used, or older than the latest 50)" % app, "nothing to audit", True, 0)]
        return L.audit_consent(entries, app)
    return fn


def asked_since(ctx, key, tool):
    """The confirmation requests for one tool recorded after the marker `key` was set in ctx.vars (a set of requestIds seen before)."""
    seen = ctx.vars.get(key, set())
    return [e for e in ctx.consent_recent() if e.get("tool") == tool and e.get("requestId") not in seen]


def mark_asked(ctx, key):
    ctx.vars[key] = {e.get("requestId") for e in ctx.consent_recent()}


def live_cases_next(ctx, preface=""):
    """The live cases of the todo and sms apps and the cross-app case of docs/next-apps-plan.md section 9. The verdict is the state of the apps."""
    number = live_sms_number(ctx)
    today = ctx.today

    def v_todo(b, a, t, c):
        known = {y["id"] for y in b["todos"]}
        hit = [x for x in a["todos"] if x["id"] not in known and "复盘" in x["title"] + (x["notes"] or "")]
        due_ok = [x for x in hit if x["due"] and today <= date.fromisoformat(x["due"][:10]) <= today + timedelta(days=14)]
        return [truth("a new todo about 复盘", "one new todo with 复盘 in its title", bool(hit), [x["title"] for x in a["todos"] if x["id"] not in known]),
                truth("priority high", "high", any(x["priority"] == "high" for x in hit), [x["priority"] for x in hit]),
                truth("it has a due date within the next two weeks ('before Friday')", "%s .. %s" % (today, today + timedelta(days=14)), bool(due_ok), [x["due"] for x in hit])]

    def v_sms(b, a, t, c):
        asked = asked_since(c, "_asked_before_live_sms", L.model_name("sms", "sms_send"))
        text = "纪要已发出"
        return [truth("sms_send was attempted: its high-risk confirmation was shown", "at least one request at risk HIGH, options ALLOW_ONCE / DENY",
                      bool(asked) and all(e.get("risk") == "HIGH" and list(e.get("options") or []) == ["ALLOW_ONCE", "DENY"] for e in asked),
                      [{k: e.get(k) for k in ("risk", "options")} for e in asked]),
                truth("the confirmation shows the number and the text", "%s and %s in the arguments" % (number, text),
                      bool(asked) and all(number in (e.get("args") or "") and text in (e.get("args") or "") for e in asked), [e.get("args") for e in asked]),
                truth("the driver declined every attempt", "answeredWith DENY", bool(asked) and all(e.get("answeredWith") == "DENY" for e in asked), [e.get("answeredWith") for e in asked]),
                eq("nothing was sent", b["outbox_keys"], a["outbox_keys"])]

    wed = {d.isoformat() for d in (next_weekday(today, 2, True), next_weekday(today, 2, False))}

    def v_cross(b, a, t, c):
        cal_b, cal_a, al_b, al_a, td_b, td_a = b["calendar"], a["calendar"], b["alarm"], a["alarm"], b["todo"], a["todo"]
        events = [e for e in cal_a["events"] if e["id"] not in {y["id"] for y in cal_b["events"]}]
        meeting = [e for e in events if "王总" in e["title"] + (e["description"] or "") or "评审" in e["title"]]
        tz = datetime.fromisoformat(c.local(today, "00:00")).tzinfo
        starts = [datetime.fromtimestamp(e["start_ms"] / 1000, tz=tz).strftime("%Y-%m-%d %H:%M") for e in meeting]
        alarms = [x for x in al_a["alarms"] if x["id"] not in {y["id"] for y in al_b["alarms"]}]
        at_1430 = [x for x in alarms if x["time"] == "14:30" and x["enabled"]]
        reminded = [e for e in meeting if 30 in e["reminders"]]
        names = ("搜索改版", "会员体系", "数据看板")
        todos = [x for x in td_a["todos"] if x["id"] not in {y["id"] for y in td_b["todos"]} and not x["parent_id"]]
        prds = [x for x in todos if "PRD" in x["title"].upper() or any(n in x["title"] for n in names)]
        asked = asked_since(c, "_asked_before_cross", L.model_name("sms", "sms_send")) if "sms" in c.apps else []
        out = [eq("one meeting event (not two)", 1, len(meeting)),
               truth("it starts next Wednesday 15:00", "date in %s, 15:00" % sorted(wed), any(x[11:] == "15:00" and x[:10] in wed for x in starts), starts),
               truth("'half an hour before' exists exactly once: a 14:30 alarm OR a 30 minute reminder of the event, not both, not twice",
                     "1 entry", len(at_1430) + (1 if reminded else 0) == 1 and len(alarms) <= 1, {"alarms_14_30": len(at_1430), "new_alarms": len(alarms), "event_reminders": [e["reminders"] for e in meeting]}),
               eq("three todos for the three PRDs (no duplicates)", 3, len(prds)),
               truth("each has a due date within two weeks", "%s .. %s" % (today, today + timedelta(days=14)),
                     len(prds) > 0 and all(x["due"] and today <= date.fromisoformat(x["due"][:10]) <= today + timedelta(days=14) for x in prds), [x["due"] for x in prds])]
        if at_1430:
            out.append(registered(al_a, at_1430[0]["id"], "14:30"))
        if meeting and reminded:
            out += reminder_checks(c, meeting[0]["id"], meeting[0]["start_ms"] - 30 * MINUTE_MS, 30, label="cross reminder")
        if "sms" in c.apps:
            sms_b, sms_a = b["sms"], a["sms"]
            out += [truth("sms_send was attempted and its confirmation shown at risk HIGH with the number", "request at HIGH, %s in the arguments" % number,
                          bool(asked) and all(e.get("risk") == "HIGH" and number in (e.get("args") or "") for e in asked), [{k: e.get(k) for k in ("risk", "args")} for e in asked]),
                    # mode=allow answers the HIGH confirmation with "allow once"; what keeps the message from leaving is the app: an emulator port is a short number
                    # and short numbers are refused (live_sms_prepare switched allow_short_numbers off). Nothing is ever sent by a live case.
                    eq("nothing was sent (the app refuses the short number)", sms_b["outbox_keys"], sms_a["outbox_keys"])]
        return out

    plan = []
    if "todo" in ctx.apps:
        plan += [LiveCase("live.todo", "todo", LIVE_PROMPTS_NEXT["todo"], v_todo, preface), consent_audit_step("todo")]
    if "sms" in ctx.apps:
        plan += [LiveCase("live.sms", "sms", LIVE_PROMPTS_NEXT["sms"].format(number=number), v_sms, preface,
                          pre=lambda c: (live_sms_prepare(c), mark_asked(c, "_asked_before_live_sms"), deny_pre(c)),
                          post=lambda c: (deny_post(c), live_sms_restore(c))), consent_audit_step("sms")]
    if {"alarm", "calendar", "todo"} <= set(ctx.apps):
        apps = ["calendar", "alarm", "todo"] + (["sms"] if "sms" in ctx.apps else [])
        prompt = LIVE_PROMPTS_NEXT["cross" if "sms" in apps else "cross_without_sms"].format(number=number)
        plan += [LiveCase("live.cross", None, prompt, v_cross, preface, apps=apps,
                          pre=lambda c: (live_sms_prepare(c) if "sms" in apps else None, mark_asked(c, "_asked_before_cross")),
                          post=live_sms_restore if "sms" in apps else None)]
        plan += [("audit.cross.%s" % app, "confirmation requests of %s so far (the receiver keeps the latest 50): source line, risk, options, answers" % app, cross_audit(app), apps)
                 for app in apps]
    return plan


def run_live_case(case, ctx, timeout=240):
    """One natural-language prompt to the real model; the checks read the app state. The tool sequence and timing are recorded."""
    import time
    t0 = time.time()
    checks, turn, error = [], None, None
    try:
        if case.pre:
            case.pre(ctx)
        snap = (lambda: {a: ctx.state(a) for a in case.apps}) if case.apps else (lambda: ctx.state(case.sample))
        before = snap()
        turn = ctx.session.prompt(case.text, timeout)
        after = snap()
        # The model chooses its own way (it may look first: note_search before note_create, event_list before event_create, or retry after an error):
        # the verdict is the app state, not a fixed tool sequence. The sequence and the failed calls are recorded as information.
        checks.append(eq("turn ends normally", "end_turn", turn.stop_reason if not turn.timeout else "timeout"))
        checks += case.verify(before, after, turn, ctx)
    except Exception as e:  # noqa: BLE001
        error = "%s: %s" % (type(e).__name__, e)
    finally:
        if case.post:
            try:
                case.post(ctx)
            except Exception as e:  # noqa: BLE001
                error = (error + "; " if error else "") + "post: %s: %s" % (type(e).__name__, e)
    ok = error is None and bool(checks) and all(c.ok for c in checks)
    r = {"id": case.id, "sample": case.sample, "title": case.text, "ok": ok, "ms": round((time.time() - t0) * 1000), "error": error,
         "checks": [c.to_json() for c in checks], "turn": turn.to_json() if turn else None}
    if case.apps:
        r["apps"] = list(case.apps)
    if turn:
        r["toolSequence"] = [{"name": t.name, "status": t.status} for t in turn.tools]
        r["failedCalls"] = [{"name": t.name, "result": t.text[:200]} for t in turn.tools if t.status != "completed"]
    return r
