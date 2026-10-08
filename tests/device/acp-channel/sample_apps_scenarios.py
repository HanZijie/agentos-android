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


def scripted_steps(ctx):
    """Steps (`Step`) and check steps (a `(id, title, fn)` tuple, no prompt) in order; the audit of an app comes right after its steps
    (ConsentDebugReceiver keeps the latest 50 requests only). Only the apps this run drives (ctx.apps)."""
    builders = {"alarm": alarm_steps, "calendar": calendar_steps, "notes": notes_steps, "todo": todo_steps}
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

    return [LiveCase("live.alarm", "alarm", LIVE_PROMPTS["alarm"], v_alarm, preface),
            LiveCase("live.calendar", "calendar", LIVE_PROMPTS["calendar"], v_event, preface),
            LiveCase("live.notes", "notes", LIVE_PROMPTS["notes"], v_note, preface)]


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
