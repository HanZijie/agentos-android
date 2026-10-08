"""Sample-apps acceptance: building blocks (A12). The driver is sample_apps_e2e.py, the scenarios are sample_apps_scenarios.py.

Goal of the acceptance: the alarm, calendar, notes, todo and sms apps (plugins/samples/*) can be driven completely by AgentOS through MCP, with
evidence. Everything here goes through adb only (no taps): debug broadcast receivers of AgentOS, the acp-bridge for the conversation,
and the **apps' own debug dump / reset receivers** for the ground truth (a real phone has no `sqlite3`, `run-as <pkg> sqlite3` fails there).

State sources (`adb shell am broadcast -n <package>/<receiver> --es cmd dump [--ei offset N --ei limit M]`, result in the broadcast's result data,
paged with `next_offset`, null = last page; `--es cmd reset` clears the app):
  alarm    org.agentos.sample.alarm/.debug.DebugToolReceiver
           dump  -> {"total","offset","limit","next_offset","alarms":[{id,time,label,days,repeat,enabled,vibrate,snooze_minutes,snoozed_until,next_fire_at,ringing}],
                     "scheduled":[{id,fire_at,registered}],"system":{next_alarm_clock,now,time_zone}}
                    `scheduled[].registered` is measured against AlarmManager (PendingIntent FLAG_NO_CREATE): it proves the alarm is really set in the system
           reset -> {"cleared":N,"remaining_registered":0}
  notes    org.agentos.sample.notes/.debug.DebugCallReceiver
           dump  -> {"notes":[{id,title,content,tags,color,pinned,archived,trashed,created_at,updated_at,trashed_at,...}] (all, incl. archived and trash),
                     "tags":[{name,count}],"total","offset","count","next_offset"}
           reset -> {"ok":true,"deleted":N}
  calendar org.agentos.sample.calendar/.debug.DebugReceiver
           dump  -> {"calendars":[{id,name,color,visible,is_default,event_count}],
                     "events":[{id (occurrence id), series_id, calendar_id, calendar_name, title, start, end (ISO-8601 with offset; all-day: local midnight and
                                23:59:59), all_day, location, description, color, reminder_minutes:[int], recurrence (wire string), recurrence_until (ISO | null),
                                timezone, hidden, created_at, updated_at}] (one row per series, its first occurrence, sorted by start then id),
                     "reminders_scheduled":[{id (occurrence id), title, minutes_before, fire_at, registered}]  -- the ONE reminder alarm the app has handed to
                                AlarmManager (registered = FLAG_NO_CREATE probe), "timezone","total","offset","limit","count","next_offset"}
                    limit: default 50, max 200
           reset -> {"cleared": <events>, "calendars_remaining", "remaining_scheduled": 0|1}   (deletes every event and every non-default calendar and
                    reschedules synchronously: no polling needed). `--es cmd clear` still exists (logcat only, not used here).
          The calendar keeps exactly one reminder alarm armed: the earliest upcoming reminder of all events. It is rescheduled with a 250 ms debounce after
          a data change, so the checks on it re-read the dump for a few seconds instead of trusting the first read.
  todo     org.agentos.sample.todo/.debug.DebugToolReceiver
           dump  -> {"todos":[{id,title,status (todo|doing|done|shelved),priority (high|medium|low),due (date | ISO with offset | null),due_all_day,tags,parent_id,
                     completed_at,overdue,created_at,updated_at,notes}] (every todo, subtasks and done ones included; the fields of todo_get),
                     "counts":{todo,doing,done,shelved},"total","offset","count","next_offset","now","time_zone"}
           reset -> {"cleared":N,"remaining":0,"remaining_in_db":0}   (synchronous: remaining_in_db counts the database rows)
  sms      org.agentos.sample.sms/.debug.DebugToolReceiver     (an independent sample app: its read tools are confirmed every time too)
           dump  -> {"mode":"full|partial|compose_only","permissions":{read_sms,send_sms},"settings":{mask_codes,allow_short_numbers,rate_limit},
                     "outbox":[{id,to,text,parts,state (queued|sent|delivered|failed),sent_parts,delivered_parts,error,created_at,updated_at}] (what THIS app sent,
                     newest first),"drafts":[{id,to,text,created_at}],"total","offset","count","next_offset","now","time_zone"}
                    no incoming messages in it: the dump does not read the system SMS store
           reset -> {"cleared":N,"outbox_remaining":0,"drafts_cleared":M}   (outbox and drafts only; never the system SMS store, the settings or the permissions)
           set   --es cmd set --es key mask_codes|allow_short_numbers|rate_limit --es value <v>  -> the new settings. Emulators call each other by their port
                    number ("5616"), which is a short number: allow_short_numbers has to be on to send to it.
          The sms steps run on emulators only (`adb -s emulator-NNNN emu sms send <from> <text>` makes the incoming messages; nothing here is ever sent to a real
          number); a real phone needs --sms-on-device.
The state readers return plain dicts (the driver's own shape, below), so the scenarios do not depend on the apps' JSON names.

The AgentOS debug receivers (main, app/src/debug; shapes checked against the code, A12 follow-up):
  ExtensionDebugReceiver  `--es op ... [--es id <plugin id or package name>] [--es name <tool>] [--ez absent true] [--el timeoutMs N]`
      list         -> {"ok":true,"plugins":[{id, packageName, name, enabled, status:"ready|unavailable|signature_changed|signature_unconfirmed", toolCount, ...}]}
      enable/disable -> {"ok":true,"plugin":{...}}   (error when status is not ready: the signature has to be confirmed first)
      catalog      -> {"ok":true,"catalog":{"version","tools":[{name: model-facing name, risk:"read|write|high", enabled, ...}],"policy":{...}}}
                      (only the tools that are available right now: that is what the model is offered)
      wait_catalog -> {"ok":true,"met":bool,"tools":[names]}  waits until `name` is in the catalog (or gone with absent=true), at most timeoutMs
  ConsentDebugReceiver    `--es op mode --es mode allow|allowOnce|deny|off`, `--es op status`, `--es op recent`
      recent -> {"ok":true,"mode":"ALLOW","recent":[{requestId, tool: model-facing name, risk: "READ|WRITE|HIGH", source: "来自插件「alarm」 · 服务器「alarm」",
                 args: one-line summary, options: [ConsentChoice names], answeredWith, end: "ANSWERED|TIMED_OUT|CANCELLED|CLOSED", notice}]}  oldest first, at most 50.
  Consent behaviour the driver relies on (integration, Pixel 8):
    - mode=allow answers WRITE with ALLOW_FOR_SESSION: the tool is remembered *for that ACP session*, so later calls in the same session are not asked
      (and switching to deny does not change that): the refusal paths run in a NEW session (session/new);
    - HIGH (*_delete) only offers ALLOW_ONCE / DENY: mode=allow answers ALLOW_ONCE and the call goes through;
    - every third-party tool is WRITE by default (also *_list / *_get), HIGH when destructiveHint (*_delete): queries are confirmed too.
"""
import json
import os
import re
import shlex
import time
from datetime import date, datetime, timedelta, timezone

import scripted_tools

APP_PKG = "org.agentos.app"
EXT_RECEIVER = os.environ.get("AGENTOS_EXT_RECEIVER", APP_PKG + "/.ext.ExtensionDebugReceiver")
CONSENT_RECEIVER = os.environ.get("AGENTOS_CONSENT_RECEIVER", APP_PKG + "/.agent.ConsentDebugReceiver")
GATEWAY_RECEIVER = APP_PKG + "/.agent.DesktopGatewayDebugReceiver"


class DriverError(Exception):
    """The driver could not do its job (not a failed expectation)."""


class Sample:
    def __init__(self, name, package, tools, receiver):
        self.name, self.package, self.tools, self.receiver = name, package, tools, receiver

    @property
    def component(self):
        """The app's debug receiver (dump / reset)."""
        return "%s/%s" % (self.package, self.receiver)

    @property
    def apk_name(self):
        return "%s-debug.apk" % self.name

    def apk_path(self, repo):
        return os.path.join(repo, "plugins", "samples", self.name, "build", "outputs", "apk", "debug", self.apk_name)


# docs/sample-apps.md section 4 (the minimum lists; the apps may offer more)
SAMPLES = {
    "alarm": Sample("alarm", "org.agentos.sample.alarm",
                    ["alarm_list", "alarm_get", "alarm_create", "alarm_update", "alarm_set_enabled", "alarm_delete", "alarm_next", "alarm_dismiss",
                     "alarm_system_next"],
                    ".debug.DebugToolReceiver"),
    "calendar": Sample("calendar", "org.agentos.sample.calendar",
                       ["calendar_list", "calendar_create", "calendar_delete", "event_list", "event_get", "event_create", "event_update",
                        "event_delete", "event_search", "agenda_today", "free_slots"],
                       ".debug.DebugReceiver"),
    "notes": Sample("notes", "org.agentos.sample.notes",
                    ["note_list", "note_get", "note_create", "note_update", "note_append", "note_search", "note_trash", "note_restore", "note_delete", "tag_list"],
                    ".debug.DebugCallReceiver"),
    "todo": Sample("todo", "org.agentos.sample.todo",
                   ["todo_list", "todo_get", "todo_create", "todo_update", "todo_set_status", "todo_delete", "todo_search", "todo_summary"],
                   ".debug.DebugToolReceiver"),
    "sms": Sample("sms", "org.agentos.sample.sms",
                  ["sms_thread_list", "sms_message_list", "sms_search", "sms_send", "sms_send_status", "sms_compose"],
                  ".debug.DebugToolReceiver"),
}

SMS_PERMISSIONS = ["android.permission.READ_SMS", "android.permission.SEND_SMS"]


def model_name(sample, tool):
    return scripted_tools.tool_name(sample, sample, tool)


def source_line(sample):
    """The source line of a confirmation (ConsentText.sourceLine): plugin and server are both the sample's name."""
    return "来自插件「%s」 · 服务器「%s」" % (sample, sample)


# The tools that carry destructiveHint=true without being called *_delete: sms_send (docs/next-apps-plan.md 4: every send is confirmed, never "always allow")
DESTRUCTIVE_TOOLS = {"sms_send"}


def expected_risk(tool):
    """RiskPolicy for third-party MCP tools (core/runtime broker/RiskPolicy.kt): WRITE by default, readOnlyHint never lowers it (so the read tools of the
    independent sample apps, sms_thread_list included, are WRITE = confirmed every time), destructiveHint (the *_delete tools and sms_send) raises to HIGH."""
    return "HIGH" if tool.endswith("_delete") or tool in DESTRUCTIVE_TOOLS else "WRITE"


# ---------------------------------------------------------------------- adb helpers

def shq(value):
    return shlex.quote(str(value))


class RealAdb:
    """run.Adb under the name the driver uses (shell, run, prop). It has no way to read an app's private files: the state of the apps comes from
    their debug dump receivers (a real phone has no sqlite3 and `run-as` is not needed)."""

    def __init__(self, base):
        self.base = base
        self.adb, self.serial = base.adb, base.serial

    def sh(self, cmd, **kw):
        return self.base.sh(cmd, **kw)

    def run(self, *args, **kw):
        return self.base.run(*args, **kw)

    def prop(self, name):
        return self.base.prop(name)

    def devices(self):
        """Serials of the devices `adb devices` lists as online (this one included). The one place the driver looks beyond its own `-s <serial>`:
        it needs to know whether a second emulator exists to send an SMS to."""
        import subprocess
        out = subprocess.run([self.adb, "devices"], capture_output=True, text=True, timeout=30).stdout
        return [p[0] for p in (line.split() for line in out.splitlines()[1:]) if len(p) >= 2 and p[1] == "device"]


class LongExtra(int):
    """An `am` extra the receiver reads with getLongExtra (--el). A plain int is an Int extra (--ei): Android does not convert between them."""


def _extra(k, v):
    """am extra with the type the receiver reads it as: bool -> --ez, LongExtra -> --el, int -> --ei, everything else -> --es."""
    if isinstance(v, bool):
        return "--ez %s %s" % (k, "true" if v else "false")
    if isinstance(v, LongExtra):
        return "--el %s %d" % (k, v)
    if isinstance(v, int):
        return "--ei %s %d" % (k, v)
    return "--es %s %s" % (k, shq(v))


def broadcast(adb, component, extras=None, timeout=60):
    """`am broadcast` to a debug receiver of AgentOS; the result is the JSON in the broadcast's result data (never in the device log).
    Returns (resultCode, json). Raises DriverError when there is no result data (receiver missing / not exported / app not debuggable)."""
    parts = " ".join(_extra(k, v) for k, v in (extras or {}).items())
    out = adb.sh("am broadcast -f 32 -n %s %s" % (component, parts), check=False, timeout=timeout)
    m = re.search(r"Broadcast completed: result=(-?\d+)", out)
    i = out.find('data="')
    if not m or i < 0:
        raise DriverError("%s %s: no result data (is the receiver there? output: %s)" % (component, extras, out.strip()[:200]))
    j = out.rfind('"')
    try:
        return int(m.group(1)), json.loads(out[i + 6:j])
    except ValueError:
        raise DriverError("%s %s: result data is not JSON: %s" % (component, extras, out[i:i + 200])) from None


def _pick(d, *keys, default=None):
    for k in keys:
        if k in d and d[k] is not None:
            return d[k]
    return default


class ExtensionDebug:
    def __init__(self, adb):
        self.adb = adb

    def _op(self, op, plugin=None, **extras):
        ex = {"op": op}
        if plugin:
            ex["id"] = plugin          # a plugin id ("<package>/<assets dir>") or a package name
        ex.update({k: v for k, v in extras.items() if v is not None})
        code, data = broadcast(self.adb, EXT_RECEIVER, ex, timeout=90)
        if not data.get("ok", code == 1):
            raise DriverError("ExtensionDebugReceiver %s %s failed: %s" % (op, plugin or "", json.dumps(data, ensure_ascii=False)[:300]))
        return data

    def plugins(self):
        out = []
        for p in self._op("list").get("plugins") or []:
            out.append({"name": _pick(p, "name", "plugin", "pluginName"), "package": _pick(p, "packageName", "package", "pkg"),
                        "enabled": bool(_pick(p, "enabled", "isEnabled", default=False)), "status": _pick(p, "status"), "raw": p})
        return out

    def enable(self, package):
        return self._op("enable", package)

    def disable(self, package):
        return self._op("disable", package)

    def catalog(self):
        """{model-facing name: RISK} (upper case: READ / WRITE / HIGH)."""
        data = self._op("catalog")
        cat = data.get("catalog")
        tools = cat.get("tools") if isinstance(cat, dict) else _pick(data, "tools", default=[])
        return {t["name"]: str(_pick(t, "risk", default="")).upper() for t in tools or []}

    def wait_tool(self, name, absent=False, timeout_ms=15000):
        """Wait (in the app, not by polling) until `name` is in the catalog / gone from it. -> bool (met)."""
        data = self._op("wait_catalog", name=name, absent=True if absent else None, timeoutMs=LongExtra(timeout_ms))
        return bool(data.get("met"))


class ConsentDebug:
    def __init__(self, adb):
        self.adb = adb

    def set_mode(self, mode):
        code, data = broadcast(self.adb, CONSENT_RECEIVER, {"op": "mode", "mode": mode})
        if not data.get("ok", code == 1):
            raise DriverError("ConsentDebugReceiver mode=%s failed: %s" % (mode, json.dumps(data)[:300]))

    def recent(self):
        """Newest last. Each entry: {tool, risk, source, options, answeredWith, end, notice, args, requestId} (the receiver's own names)."""
        code, data = broadcast(self.adb, CONSENT_RECEIVER, {"op": "recent"})
        if not data.get("ok", code == 1):
            raise DriverError("ConsentDebugReceiver recent failed: %s" % json.dumps(data)[:300])
        return _pick(data, "recent", "entries", default=[])

    def asked(self, tool_name):
        """The recent requests for one model-facing tool name."""
        return [e for e in self.recent() if e.get("tool") == tool_name]


class GatewayDebug:
    def __init__(self, adb):
        self.adb = adb

    def op(self, op, **extras):
        code, data = broadcast(self.adb, GATEWAY_RECEIVER, dict(op=op, **extras))
        return data


# ---------------------------------------------------------------------- state readers (the apps' debug dump / reset)

class StateReadError(DriverError):
    pass


DAY_CODES = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
DUMP_PAGE = 50          # rows per dump page (--ei limit); the notes dump also has its own character budget per page
MAX_DUMP_PAGES = 400


def dump_pages(adb, sample, key):
    """All pages of `<app> --es cmd dump`: returns the first page with its `key` list replaced by the rows of all pages.
    Follows `next_offset` (null = last page); a receiver that does not move forward is an error, not an endless loop."""
    offset, first, rows, seen = 0, None, [], 0
    while True:
        code, data = broadcast(adb, sample.component, {"cmd": "dump", "offset": offset, "limit": DUMP_PAGE})
        if code != 1 or "error" in data:
            raise StateReadError("%s dump failed: %s" % (sample.name, json.dumps(data, ensure_ascii=False)[:300]))
        if key not in data:
            raise StateReadError("%s dump has no %r: %s" % (sample.name, key, json.dumps(data, ensure_ascii=False)[:200]))
        first = first or data
        rows += data[key]
        nxt = data.get("next_offset")
        if nxt is None:
            break
        seen += 1
        if not isinstance(nxt, int) or nxt <= offset or seen > MAX_DUMP_PAGES:
            raise StateReadError("%s dump does not advance (offset %s -> next_offset %r)" % (sample.name, offset, nxt))
        offset = nxt
    total = first.get("total")
    if total is not None and total != len(rows):
        raise StateReadError("%s dump: total=%s but %d rows were read (the data changed while paging?)" % (sample.name, total, len(rows)))
    return dict(first, **{key: rows})


class AlarmState:
    """Alarms from the app's dump: `alarms` (stable fields only, so that "unchanged" comparisons do not trip over clocks),
    `scheduled` ({id: {fire_at, registered}}, measured against AlarmManager) and `system` (the phone's own next alarm; evidence only)."""
    sample = SAMPLES["alarm"]

    def __init__(self, adb):
        self.adb = adb

    def snapshot(self):
        d = dump_pages(self.adb, self.sample, "alarms")
        return {"alarms": [{"id": str(a["id"]), "time": a["time"], "label": a.get("label") or "", "days": list(a.get("days") or []),
                            "enabled": bool(a["enabled"]), "snooze_minutes": a.get("snooze_minutes")} for a in d["alarms"]],
                "scheduled": {str(x["id"]): {"fire_at": x.get("fire_at"), "registered": bool(x.get("registered"))} for x in d.get("scheduled") or []},
                "system": d.get("system") or {}}

    def reset(self):
        code, data = broadcast(self.adb, self.sample.component, {"cmd": "reset"})
        return code == 1 and data.get("remaining_registered") == 0, data


class NotesState:
    sample = SAMPLES["notes"]

    def __init__(self, adb):
        self.adb = adb

    @staticmethod
    def status(n):
        return "trashed" if n.get("trashed") else "archived" if n.get("archived") else "active"

    def snapshot(self):
        d = dump_pages(self.adb, self.sample, "notes")
        return {"notes": [{"id": n["id"], "title": n.get("title"), "content": n.get("content") or "", "tags": list(n.get("tags") or []),
                           "color": n.get("color"), "pinned": bool(n.get("pinned")), "status": self.status(n)} for n in d["notes"]],
                "tags": d.get("tags") or []}

    def reset(self):
        code, data = broadcast(self.adb, self.sample.component, {"cmd": "reset"})
        return code == 1 and data.get("ok") is True, data


def iso_ms(text):
    """ISO-8601 with an offset ("2026-10-17T10:00:00+08:00", also "...Z") -> epoch milliseconds; None for null."""
    if text is None:
        return None
    return int(datetime.fromisoformat(str(text).replace("Z", "+00:00")).timestamp() * 1000)


class CalendarState:
    """Calendar from the app's dump. `events`: one row per series (`id` is the series id: event_update / event_delete take it, the dump's own `id` is the
    first occurrence's id and is kept as `occurrence_id`); times are ISO strings from the app, and `start_ms` / `end_ms` / `until_ms` are the same
    instants as epoch milliseconds so the checks compare instants, not formatting. `reminders` is the app's `reminders_scheduled`: the single armed
    reminder alarm, `registered` measured against AlarmManager. Clocks (created_at / updated_at) are left out so that "unchanged" comparisons are stable."""
    sample = SAMPLES["calendar"]

    def __init__(self, adb):
        self.adb = adb

    def snapshot(self):
        d = dump_pages(self.adb, self.sample, "events")
        events = []
        for e in d["events"]:
            events.append({"id": str(e.get("series_id") or str(e["id"]).split("@")[0]), "occurrence_id": e["id"], "calendar_id": e["calendar_id"], "title": e["title"],
                           "description": e.get("description") or "", "location": e.get("location") or "", "all_day": bool(e.get("all_day")),
                           "start": e["start"], "end": e["end"], "start_ms": iso_ms(e["start"]), "end_ms": iso_ms(e["end"]),
                           "reminders": list(e.get("reminder_minutes") or []), "recurrence": e.get("recurrence"),
                           "recurrence_until": e.get("recurrence_until"), "until_ms": iso_ms(e.get("recurrence_until")),
                           "timezone": e.get("timezone"), "hidden": bool(e.get("hidden"))})
        reminders = []
        for r in d.get("reminders_scheduled") or []:
            reminders.append({"id": r["id"], "series_id": str(r["id"]).split("@")[0], "title": r.get("title"), "minutes_before": r.get("minutes_before"),
                              "fire_at": r.get("fire_at"), "fire_at_ms": iso_ms(r.get("fire_at")), "registered": bool(r.get("registered"))})
        return {"calendars": [{"id": c["id"], "name": c["name"], "visible": bool(c.get("visible")), "is_default": bool(c.get("is_default")),
                               "event_count": c.get("event_count")} for c in d.get("calendars") or []],
                "events": events, "reminders": reminders, "timezone": d.get("timezone")}

    def reset(self):
        code, data = broadcast(self.adb, self.sample.component, {"cmd": "reset"})
        # every event and every non-default calendar is gone (only the default calendar is left) and no reminder alarm is armed any more
        ok = code == 1 and data.get("remaining_scheduled") == 0 and data.get("calendars_remaining") == 1
        return ok, data


SLEEP_SCALE = 1.0  # unit tests shrink the waits


class TodoState:
    """Todos from the app's dump (`todos`: every todo, subtasks and done ones included, each with the fields of `todo_get`). Stable fields only, so that
    "unchanged" comparisons do not trip over clocks: `created_at`, `updated_at` and the derived `overdue` flag are left out. `completed_at` stays (it only
    changes when the status does; "setting the status it already has changes nothing" is checked with it). `due` is the app's own string (a plain date
    for an all-day due, ISO-8601 with the device offset otherwise), `due_ms` the same instant as epoch milliseconds for timed dues (None for all-day)."""
    sample = SAMPLES["todo"]

    def __init__(self, adb):
        self.adb = adb

    def snapshot(self):
        d = dump_pages(self.adb, self.sample, "todos")
        todos = []
        for t in d["todos"]:
            all_day = bool(t.get("due_all_day"))
            todos.append({"id": str(t["id"]), "title": t["title"], "notes": t.get("notes") or "", "status": t["status"], "priority": t["priority"],
                          "due": t.get("due"), "due_all_day": all_day, "due_ms": None if all_day or not t.get("due") else iso_ms(t["due"]),
                          "tags": list(t.get("tags") or []), "parent_id": t.get("parent_id"), "completed_at": t.get("completed_at")})
        return {"todos": todos, "counts": dict(d.get("counts") or {})}

    def reset(self):
        code, data = broadcast(self.adb, self.sample.component, {"cmd": "reset"})
        # the receiver counts the rows in the database itself, so the app is empty when this returns
        return code == 1 and data.get("remaining") == 0 and data.get("remaining_in_db") == 0, data


class SmsState:
    """The sms app's own records from its dump: `mode` (full / partial / compose_only), `permissions`, `settings`, the `outbox` (what THIS app sent, newest
    first) and the `drafts` sms_compose left. The dump has no incoming messages (it does not read the system SMS store). Clocks are left out. The state of an
    outbox row (queued -> sent -> delivered) changes by itself after a send: compare the rows by (id, to, text), not by state, when a step must not have
    sent anything."""
    sample = SAMPLES["sms"]

    def __init__(self, adb):
        self.adb = adb

    def snapshot(self):
        d = dump_pages(self.adb, self.sample, "outbox")
        return {"mode": d.get("mode"), "permissions": dict(d.get("permissions") or {}), "settings": dict(d.get("settings") or {}),
                "outbox": [{"id": str(o["id"]), "to": o["to"], "text": o["text"], "parts": o.get("parts"), "state": o["state"], "sent_parts": o.get("sent_parts"),
                            "delivered_parts": o.get("delivered_parts"), "error": o.get("error")} for o in d["outbox"]],
                "drafts": [{"id": str(x["id"]), "to": x["to"], "text": x.get("text")} for x in d.get("drafts") or []]}

    def reset(self):
        """Clears the outbox (with the send-rate and duplicate history) and the drafts; never the system SMS store, the settings or the permissions."""
        code, data = broadcast(self.adb, self.sample.component, {"cmd": "reset"})
        return code == 1 and data.get("outbox_remaining") == 0, data

    def set(self, key, value):
        """Debug `set` (mask_codes / allow_short_numbers true|false, rate_limit 1..30) -> the new settings."""
        code, data = broadcast(self.adb, self.sample.component, {"cmd": "set", "key": key, "value": str(value).lower() if isinstance(value, bool) else str(value)})
        if code != 1 or "error" in data:
            raise StateReadError("sms set %s=%s failed: %s" % (key, value, json.dumps(data, ensure_ascii=False)[:300]))
        return data

    def call(self, tool, args):
        """Debug `tool` (in-process, the same tools MCP registers) -> (isError, parsed result or text). Emulator only: the result would be printed by adb."""
        code, data = broadcast(self.adb, self.sample.component, {"tool": tool, "args": json.dumps(args, ensure_ascii=False)})
        text = data.get("result")
        try:
            return bool(data.get("isError", code != 1)), json.loads(text)
        except (TypeError, ValueError):
            return bool(data.get("isError", code != 1)), text


def reset_apps(adb, only=None):
    """Clear the sample apps (debug builds only): [{app, ok, detail}]. Used before and after a run (everything in the apps is removed;
    the sms app only loses its own outbox and drafts)."""
    out = []
    for name, cls in STATE_READERS.items():
        if only and name not in only:
            continue
        try:
            ok, detail = cls(adb).reset()
        except DriverError as e:
            ok, detail = False, {"error": str(e)}
        out.append({"app": name, "ok": bool(ok), "detail": detail})
    return out


STATE_READERS = {"alarm": AlarmState, "calendar": CalendarState, "notes": NotesState, "todo": TodoState, "sms": SmsState}


# ---------------------------------------------------------------------- results

class Check:
    def __init__(self, name, ok, expected, actual):
        self.name, self.ok, self.expected, self.actual = name, bool(ok), expected, actual

    def to_json(self):
        return {"name": self.name, "ok": self.ok, "expected": _jsonable(self.expected), "actual": _jsonable(self.actual)}

    def __repr__(self):
        return "Check(%s, %s)" % (self.name, self.ok)


def _jsonable(v):
    try:
        json.dumps(v)
        return v
    except (TypeError, ValueError):
        return str(v)


def eq(name, expected, actual):
    return Check(name, expected == actual, expected, actual)


def truth(name, expected, ok, actual):
    """expected is a sentence describing what should hold."""
    return Check(name, ok, expected, actual)


class ToolRecord:
    def __init__(self, call_id, name):
        self.call_id, self.name = call_id, name
        self.status, self.input, self.text = None, None, ""

    def json(self):
        """The result text as JSON (None when it is not JSON)."""
        try:
            return json.loads(self.text)
        except ValueError:
            return None

    def to_json(self):
        return {"toolCallId": self.call_id, "name": self.name, "status": self.status, "input": _jsonable(self.input), "result": self.text[:600]}


class TurnResult:
    def __init__(self):
        self.stop_reason = None
        self.error = None
        self.timeout = False
        self.closed = False
        self.ms = 0
        self.text = ""
        self.tools = []

    def to_json(self):
        return {"stopReason": self.stop_reason, "error": self.error, "timeout": self.timeout, "closed": self.closed, "ms": self.ms,
                "text": self.text[:400], "tools": [t.to_json() for t in self.tools]}


def collect_turn(notes, response):
    """Fold the session/update notes and the prompt response of one turn into a TurnResult."""
    t = TurnResult()
    by_id = {}
    text = []
    for m in notes:
        u = (m.get("params") or {}).get("update") or {}
        kind = u.get("sessionUpdate")
        if kind == "agent_message_chunk":
            text.append((u.get("content") or {}).get("text", ""))
        elif kind in ("tool_call", "tool_call_update"):
            cid = u.get("toolCallId")
            rec = by_id.get(cid)
            if rec is None:
                rec = by_id[cid] = ToolRecord(cid, u.get("title") or "")
                t.tools.append(rec)
            if u.get("title"):
                rec.name = u["title"]
            if u.get("status"):
                rec.status = u["status"]
            if u.get("rawInput") is not None:
                rec.input = u["rawInput"]
            for c in u.get("content") or []:
                inner = c.get("content") if isinstance(c, dict) else None
                if isinstance(inner, dict) and inner.get("type") == "text":
                    rec.text = inner.get("text", "")
    t.text = "".join(text)
    msg = response.get("msg") or {}
    t.stop_reason = (msg.get("result") or {}).get("stopReason")
    t.error = msg.get("error")
    t.timeout = bool(response.get("timeout"))
    t.closed = bool(response.get("closed"))
    t.ms = response.get("ms", 0)
    return t


class BridgeSession:
    """One ACP session through the acp-bridge (desktop_idle.Bridge). prompt() sends the text and returns a TurnResult."""

    def __init__(self, bridge, session_id):
        self.bridge, self.session_id = bridge, session_id

    @classmethod
    def open(cls, bridge, timeout=60):
        init = bridge.request("initialize", {"protocolVersion": 1, "clientCapabilities": {}}, timeout)
        if (init.get("msg") or {}).get("result", {}).get("protocolVersion") != 1:
            raise DriverError("acp initialize failed: %s (bridge stderr: %s)" % (json.dumps(init.get("msg") or init)[:200], bridge.stderr[-2:]))
        new = bridge.request("session/new", {"cwd": "/", "mcpServers": []}, timeout)
        sid = ((new.get("msg") or {}).get("result") or {}).get("sessionId")
        if not sid:
            raise DriverError("session/new failed: %s" % json.dumps(new.get("msg") or new)[:200])
        return cls(bridge, sid)

    def new_session(self, timeout=60):
        """A fresh ACP session on the same connection (the per-session memory of 'allow for this session' starts empty)."""
        new = self.bridge.request("session/new", {"cwd": "/", "mcpServers": []}, timeout)
        sid = ((new.get("msg") or {}).get("result") or {}).get("sessionId")
        if not sid:
            raise DriverError("session/new failed: %s" % json.dumps(new.get("msg") or new)[:200])
        self.session_id = sid
        return sid

    def prompt(self, text, timeout=60):
        r = self.bridge.request("session/prompt", {"sessionId": self.session_id, "prompt": [{"type": "text", "text": text}]}, timeout)
        return collect_turn(r["notes"], r)

    def close(self):
        return self.bridge.close()


# ---------------------------------------------------------------------- scenario model

class Call:
    """One tool call of a scripted turn. `ok` is whether the tool is expected to succeed (ACP status completed) or fail (failed);
    `error_has` is a substring the failure text must contain."""

    def __init__(self, sample, tool, arguments=None, ok=True, error_has=None):
        self.sample, self.tool, self.arguments, self.ok, self.error_has = sample, tool, arguments or {}, ok, error_has

    @property
    def name(self):
        return model_name(self.sample, self.tool)

    def script_entry(self):
        return {"name": self.name, "arguments": self.arguments}


class Step:
    def __init__(self, id, sample, title, calls, verify=None, capture=None, pre=None, post=None, final="step-done"):
        self.id, self.sample, self.title, self.calls, self.verify, self.capture, self.pre, self.post, self.final = \
            id, sample, title, calls, verify, capture, pre, post, final


class Context:
    """Everything a step needs: the device pieces, the variables captured by earlier steps, the dates."""

    def __init__(self, adb, ext, consent, session, today, tz_offset, run_id, log=print, gateway=None, exclusive=False, apps=None, sms_peer=None):
        self.adb, self.ext, self.consent, self.session, self.gateway = adb, ext, consent, session, gateway
        # the sample apps this run drives (all five; the sms app only on emulators or with --sms-on-device, see [select_apps])
        self.apps = list(apps) if apps is not None else list(SAMPLES)
        # the number (= console port) of a second emulator the sms app can send to, None when there is none: then the send / delivery steps are not run
        self.sms_peer = sms_peer
        self.skipped = []           # [{"what", "reason"}]: parts of the acceptance that were not run (they go into the report)
        # the apps were reset before the run: what is in them is this run's data only (the calendar's single armed alarm is then one of this run's events)
        self.exclusive = exclusive
        self.today, self.tz_offset, self.run_id, self.log = today, tz_offset, run_id, log
        self.vars = {}
        self.consent_seen = set()   # requestIds already in ConsentDebugReceiver `recent` before this run asked anything
        self.states = {name: cls(adb) for name, cls in STATE_READERS.items()}

    def state(self, sample):
        return self.states[sample].snapshot()

    def model_status(self):
        """DesktopGatewayDebugReceiver `status`: modelUsable / modelBaseUrl / modelId of the phone's model source (no key in it)."""
        if self.gateway is None:
            raise DriverError("no gateway debug access in this context")
        return self.gateway.op("status")

    def mark_consent_baseline(self):
        self.consent_seen = {e.get("requestId") for e in self.consent.recent()}

    def consent_recent(self):
        """Confirmation requests recorded since [mark_consent_baseline] (this run's)."""
        return [e for e in self.consent.recent() if e.get("requestId") not in self.consent_seen]

    def asked(self, tool_name):
        return [e for e in self.consent_recent() if e.get("tool") == tool_name]

    def day(self, plus):
        return self.today + timedelta(days=plus)

    def local(self, d, hhmm):
        """ISO-8601 local date-time with the device's offset."""
        return "%sT%s:00%s" % (d.isoformat(), hhmm, self.tz_offset)

    def tz(self):
        sign = -1 if self.tz_offset.startswith("-") else 1
        oh, om = (int(x) for x in self.tz_offset[1:].split(":"))
        return timezone(sign * timedelta(hours=oh, minutes=om))

    def epoch_ms(self, d, hhmm):
        h, m = (int(x) for x in hhmm.split(":"))
        return int(datetime(d.year, d.month, d.day, h, m, tzinfo=self.tz()).timestamp() * 1000)

    def iso_from_ms(self, ms):
        """An instant as ISO-8601 in the device's offset (what the apps print)."""
        return datetime.fromtimestamp(ms / 1000, tz=self.tz()).isoformat()

    def package(self, sample):
        return SAMPLES[sample].package


def run_step(step, ctx, timeout=90):
    """Run one scripted step; returns the step result (dict). A step never raises for a failed expectation: it records it."""
    t0 = time.time()
    checks, turn, error = [], None, None
    before = after = None
    try:
        if step.pre:
            step.pre(ctx)
        calls = step.calls(ctx.vars) if callable(step.calls) else step.calls
        before = ctx.state(step.sample)
        script = {"toolCalls": [c.script_entry() for c in calls], "final": step.final}
        turn = ctx.session.prompt(json.dumps(script, ensure_ascii=False), timeout)
        checks += turn_checks(step, calls, turn)
        if step.capture:
            try:
                ctx.vars.update(step.capture(turn.tools, ctx))
            except Exception as e:  # noqa: BLE001
                checks.append(Check("capture", False, "the result of the step has the value the next steps need", "%s: %s" % (type(e).__name__, e)))
        after = ctx.state(step.sample)
        if step.verify:
            checks += step.verify(before, after, turn, ctx)
    except Exception as e:  # noqa: BLE001
        error = "%s: %s" % (type(e).__name__, e)
    finally:
        if step.post:
            try:
                step.post(ctx)
            except Exception as e:  # noqa: BLE001
                error = (error + "; " if error else "") + "post: %s: %s" % (type(e).__name__, e)
    ok = error is None and all(c.ok for c in checks) and bool(checks)
    return {"id": step.id, "sample": step.sample, "title": step.title, "ok": ok, "ms": round((time.time() - t0) * 1000),
            "error": error, "checks": [c.to_json() for c in checks], "turn": turn.to_json() if turn else None}


def text_has(text, spec):
    """`a|b` = any of the alternatives, case-insensitive."""
    low = (text or "").lower()
    return any(alt.strip().lower() in low for alt in spec.split("|"))


def not_offered(text):
    """A call to a tool that is not in the catalog: Pi refuses it itself ("Tool X not found") or the broker does ([agentos:tool_not_in_catalog])."""
    return text_has(text, "not found|tool_not_in_catalog")


def turn_checks(step, calls, turn):
    out = [eq("turn ends normally", "end_turn", turn.stop_reason if not turn.timeout else "timeout")]
    if turn.text.startswith("script-error"):
        out.append(Check("script ran to its end", False, step.final, turn.text))
    else:
        out.append(eq("model's final text", step.final, turn.text))
    out.append(eq("number of tool calls", len(calls), len(turn.tools)))
    for i, c in enumerate(calls):
        if i >= len(turn.tools):
            break
        rec = turn.tools[i]
        out.append(eq("call %d is %s" % (i, c.tool), c.name, rec.name))
        want = "completed" if c.ok else "failed"
        out.append(eq("call %d %s %s" % (i, c.tool, "succeeds" if c.ok else "is refused or fails"), want, rec.status))
        if c.error_has:
            out.append(truth("call %d error text mentions %r" % (i, c.error_has), "contains %r" % c.error_has, text_has(rec.text, c.error_has), rec.text[:200]))
    return out


def audit_consent(entries, sample):
    """Checks over the confirmation requests ConsentDebugReceiver `recent` recorded for one sample app (what the user would have been shown).

    Expected (integration, Pixel 8): the source line names the plugin and server; every tool is WRITE (queries too) except `*_delete` which is HIGH;
    HIGH offers exactly ALLOW_ONCE / DENY, WRITE at least ALLOW_ONCE / DENY; the auto-responder answered with an offered option, never ALWAYS_ALLOW,
    and no request ended in a timeout."""
    prefix = "mcp__%s__%s__" % (sample, sample)
    mine = [e for e in entries if str(e.get("tool", "")).startswith(prefix)]
    short = lambda e: {k: e.get(k) for k in ("tool", "risk", "options", "answeredWith", "end")}  # noqa: E731
    out = [truth("%s: confirmation requests were recorded" % sample, ">= 1 request", bool(mine), len(mine))]
    if not mine:
        return out
    out.append(truth("%s: every request names its source plugin and server" % sample, source_line(sample),
                     all(e.get("source") == source_line(sample) for e in mine), sorted({str(e.get("source")) for e in mine})))
    wrong = [short(e) for e in mine if str(e.get("risk", "")).upper() != expected_risk(str(e["tool"])[len(prefix):])]
    out.append(truth("%s: risk is HIGH for *_delete and WRITE for every other tool (queries are confirmed too)" % sample, "no deviation", not wrong, wrong[:5]))
    bad_opts = []
    for e in mine:
        opts = list(e.get("options") or [])
        high = str(e.get("risk", "")).upper() == "HIGH"
        if high and opts != ["ALLOW_ONCE", "DENY"]:
            bad_opts.append(short(e))
        if not high and not {"ALLOW_ONCE", "DENY"} <= set(opts):
            bad_opts.append(short(e))
    out.append(truth("%s: HIGH offers only ALLOW_ONCE/DENY, WRITE offers at least ALLOW_ONCE/DENY" % sample, "no deviation", not bad_opts, bad_opts[:5]))
    bad_ans = [short(e) for e in mine if e.get("answeredWith") is None or e.get("answeredWith") == "ALWAYS_ALLOW" or e.get("answeredWith") not in (e.get("options") or [])
               or e.get("end") != "ANSWERED"]
    out.append(truth("%s: answered by the auto-responder with an offered option (never ALWAYS_ALLOW), none timed out" % sample, "no deviation", not bad_ans, bad_ans[:5]))
    return out


def summarize(results):
    failed = [r for r in results if not r["ok"]]
    lines = []
    for r in failed:
        if r.get("error"):
            lines.append("FAIL %s: driver error: %s" % (r["id"], r["error"]))
        for c in r["checks"]:
            if not c["ok"]:
                lines.append("FAIL %s: %s: expected %s, actual %s" % (r["id"], c["name"], json.dumps(c["expected"], ensure_ascii=False)[:200], json.dumps(c["actual"], ensure_ascii=False)[:200]))
        if not r["checks"] and not r.get("error"):
            lines.append("FAIL %s: no checks ran" % r["id"])
    return {"steps": len(results), "passed": len(results) - len(failed), "failed": len(failed), "failures": lines}


def device_today_and_offset(adb):
    """The device's local date and UTC offset ("+08:00")."""
    d = adb.sh("date +%Y-%m-%d", check=False).strip()
    z = adb.sh("date +%z", check=False).strip()
    try:
        today = date.fromisoformat(d)
    except ValueError:
        raise DriverError("cannot read the device date: %r" % d) from None
    if not re.fullmatch(r"[+-]\d{4}", z):
        raise DriverError("cannot read the device time zone: %r" % z)
    return today, "%s%s:%s" % (z[0], z[1:3], z[3:5])


# ---------------------------------------------------------------------- which apps run on which device

def is_emulator(serial):
    return str(serial).startswith("emulator-")


def select_apps(adb, sms_on_device=False):
    """-> (apps, skipped). The sms app sends and reads real text messages: its steps run on emulators only (serial `emulator-NNNN`; the incoming messages
    come from `adb emu sms send`, the recipient is another emulator), on a real phone only with --sms-on-device. Otherwise it is left out of everything
    (not installed, not enabled, not reset, no steps) and the report says so."""
    apps = list(SAMPLES)
    skipped = []
    if "sms" in apps and not is_emulator(adb.serial) and not sms_on_device:
        apps.remove("sms")
        skipped.append({"what": "sms", "reason": "%s is not an emulator: the sms app reads and sends real text messages, so its steps run on emulators only "
                                                  "(--sms-on-device allows a real phone)" % adb.serial})
    return apps, skipped


def find_sms_peer(adb, spec="auto"):
    """The number of the emulator the sms steps send to: `spec` is a console port ("5616"), "none", or "auto" = the first other emulator `adb devices`
    lists (an emulator's number is its console port: `emulator-5616` is reached as 5616). None when there is none."""
    if spec == "none":
        return None
    if spec not in (None, "auto"):
        return str(spec)
    others = [x for x in adb.devices() if is_emulator(x) and x != adb.serial]
    return others[0].split("-", 1)[1] if others else None
