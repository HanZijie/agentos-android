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
from datetime import datetime

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


def wait_catalog(ctx, pred, timeout=10):
    import time
    end = time.time() + timeout * WAIT_SCALE
    cat = ctx.ext.catalog()
    while not pred(cat) and time.time() < end:
        time.sleep(0.5 * WAIT_SCALE)
        cat = ctx.ext.catalog()
    return cat


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
    """Start from empty apps: the debug `reset` of the three sample apps (everything in them is removed), then read the state back."""
    out = []
    for r in L.reset_apps(ctx.adb):
        out.append(truth("%s: reset" % r["app"], "the app reports it is empty" + (" and nothing is registered with AlarmManager" if r["app"] == "alarm" else ""), r["ok"], r["detail"]))
    cal = ctx.state("calendar")
    snap = {"alarm": len(ctx.state("alarm")["alarms"]), "notes": len(ctx.state("notes")["notes"]), "calendar events": len(cal["events"])}
    out.append(truth("the three apps are empty after the reset", "0 alarms, 0 notes, 0 events", not any(snap.values()), snap))
    out.append(truth("calendar: only the default calendar is left and no reminder alarm is armed", "1 default calendar, no registered reminder",
                     len(cal["calendars"]) == 1 and cal["calendars"][0]["is_default"] and not any(r["registered"] for r in cal["reminders"]),
                     {"calendars": [(c["name"], c["is_default"]) for c in cal["calendars"]], "reminders": cal["reminders"]}))
    return out


def setup_discover(allow_enabled):
    def fn(ctx):
        plugins = ctx.ext.plugins()
        out = []
        for name, s in L.SAMPLES.items():
            p = next((x for x in plugins if x["package"] == s.package or (x["package"] is None and x["name"] == name)), None)
            out.append(Check("plugin %s is discovered (package %s)" % (name, s.package), p is not None, "listed by ExtensionDebugReceiver", [x["package"] or x["name"] for x in plugins]))
            if p is not None and not allow_enabled:
                out.append(eq("plugin %s is off by default" % name, False, p["enabled"]))
        return out
    return fn


def setup_enable(ctx):
    for s in L.SAMPLES.values():
        ctx.ext.enable(s.package)
    cat = wait_catalog(ctx, lambda c: all(L.model_name(s.name, t) in c for s in L.SAMPLES.values() for t in s.tools), timeout=20)
    plugins = ctx.ext.plugins()
    out = []
    for name, s in L.SAMPLES.items():
        p = next((x for x in plugins if x["package"] == s.package), None)
        out.append(eq("plugin %s is on after enable" % name, True, bool(p and p["enabled"])))
    out.append(truth("the catalog is not empty after enable", "tools of all three plugins", bool(cat), len(cat)))
    return out


def setup_catalog(ctx):
    cat = ctx.ext.catalog()
    out = []
    extras = {}
    for name, s in L.SAMPLES.items():
        for t in s.tools:
            n = L.model_name(name, t)
            out.append(eq("%s is offered as %s with risk" % (t, n), L.expected_risk(t), cat.get(n)))
        prefix = "mcp__%s__%s__" % (name, name)
        extras[name] = sorted(n[len(prefix):] for n in cat if n.startswith(prefix) and n[len(prefix):] not in s.tools)
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

    S.append(Step("alarm.delete", "alarm", "alarm_delete (high risk: confirmed by the auto-consent)",
                  lambda v: [Call("alarm", "alarm_delete", {"id": var(v, "alarm_id")})],
                  verify=lambda b, a, t, c: [truth("the alarm is gone from the app's dump", "no alarm with id %s" % c.vars["alarm_id"], find(a["alarms"], c.vars["alarm_id"]) is None, None),
                                             eq("one alarm less", len(b["alarms"]) - 1, len(a["alarms"])), not_registered(a, c.vars["alarm_id"])]))

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
    s = L.SAMPLES[sample]
    probe = L.model_name(sample, probe_call.tool)

    def pre(c):
        c.ext.disable(pkg)
        c.ext.wait_tool(probe, absent=True)
        c.vars["_off_catalog"] = wait_catalog(c, lambda cat: not any(L.model_name(sample, t) in cat for t in s.tools))

    def verify(b, a, t, c):
        cat = c.vars.pop("_off_catalog", {})
        left = [n for n in cat if n.startswith("mcp__%s__%s__" % (sample, sample))]
        return [truth("no %s tool is offered while the plugin is off" % label, "none of mcp__%s__*" % sample, not left, left),
                truth("a call to it is refused (Pi: not found, or the broker: tool_not_in_catalog)", "text says so", bool(t.tools) and L.not_offered(t.tools[0].text), t.tools[0].text[:200] if t.tools else None),
                eq("%s data unchanged" % label, b[state_key], a[state_key])]

    off = Step("%s.plugin_off" % sample, sample, "plugin disabled: its tools vanish, a call is refused", [Call(sample, probe_call.tool, probe_call.arguments, ok=False, error_has=NOT_OFFERED)],
               pre=pre, verify=verify)

    def pre_on(c):
        c.ext.enable(pkg)
        c.ext.wait_tool(probe)
        c.vars["_on_catalog"] = wait_catalog(c, lambda cat: all(L.model_name(sample, t) in cat for t in s.tools))

    def verify_on(b, a, t, c):
        cat = c.vars.pop("_on_catalog", {})
        return [truth("all documented %s tools are back after re-enabling" % label, "all %d tools" % len(s.tools), all(L.model_name(sample, t) in cat for t in s.tools), sorted(n for n in cat if n.startswith("mcp__%s__" % sample))[:20])]

    on = Step("%s.plugin_on" % sample, sample, "plugin enabled again: tools are back and work", [probe_call], pre=pre_on, verify=verify_on)
    return [off, on]


def consent_audit_step(sample):
    """A check step (no prompt): what ConsentDebugReceiver recorded for this app is what the design says (source line, risk, options, answers)."""
    return ("audit.%s" % sample, "confirmation requests of %s: source line, risk, options, answers" % sample,
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


def scripted_steps(ctx):
    """Steps (`Step`) and check steps (a `(id, title, fn)` tuple, no prompt) in order; the audit of an app comes right after its steps
    (ConsentDebugReceiver keeps the latest 50 requests only)."""
    return alarm_steps(ctx) + [consent_audit_step("alarm")] + calendar_steps(ctx) + [consent_audit_step("calendar")] + notes_steps(ctx) + [consent_audit_step("notes")]


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
    def __init__(self, id, sample, prompt, verify, preface=""):
        self.id, self.sample, self.prompt, self.verify, self.preface = id, sample, prompt, verify, preface

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

    return [LiveCase("live.alarm", "alarm", "明早 7 点叫我起床", v_alarm, preface),
            LiveCase("live.calendar", "calendar", "下周三下午 3 点和王总开会，提前 15 分钟提醒", v_event, preface),
            LiveCase("live.notes", "notes", "记一条关于新品发布的备忘，打上工作标签", v_note, preface)]


def run_live_case(case, ctx, timeout=240):
    """One natural-language prompt to the real model; the checks read the app state. The tool sequence and timing are recorded."""
    import time
    t0 = time.time()
    checks, turn, error = [], None, None
    try:
        before = ctx.state(case.sample)
        turn = ctx.session.prompt(case.text, timeout)
        after = ctx.state(case.sample)
        # The model chooses its own way (it may look first: note_search before note_create, event_list before event_create, or retry after an error):
        # the verdict is the app state, not a fixed tool sequence. The sequence and the failed calls are recorded as information.
        checks.append(eq("turn ends normally", "end_turn", turn.stop_reason if not turn.timeout else "timeout"))
        checks += case.verify(before, after, turn, ctx)
    except Exception as e:  # noqa: BLE001
        error = "%s: %s" % (type(e).__name__, e)
    ok = error is None and bool(checks) and all(c.ok for c in checks)
    r = {"id": case.id, "sample": case.sample, "title": case.text, "ok": ok, "ms": round((time.time() - t0) * 1000), "error": error,
         "checks": [c.to_json() for c in checks], "turn": turn.to_json() if turn else None}
    if turn:
        r["toolSequence"] = [{"name": t.name, "status": t.status} for t in turn.tools]
        r["failedCalls"] = [{"name": t.name, "result": t.text[:200]} for t in turn.tools if t.status != "completed"]
    return r
