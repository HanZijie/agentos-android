"""A fake phone for testing the acceptance driver without a device (A12).

It models what the driver touches, with the same shapes as the real thing:
  - the five apps' state (SQLite databases for alarm / calendar / notes / todo, same tables and columns as the real stores for the first three; plain
    Python state for sms: outbox, drafts, settings, permissions and the system inbox) changed by simple Python versions of the tools, and the apps' debug
    `dump` / `reset` receivers with the real JSON shapes (the driver never reads the databases: it has no way to, a real phone has no sqlite3);
  - the catalog the model is offered: after `enable` / `disable` the plugins' tools appear and disappear one plugin after the other, not together
    (`list_delay` / `drop_delay`: how many catalog reads later a plugin's tools show up / go away; 0 = at once, the default), as the real host does;
  - the calendar's single armed reminder alarm: the earliest upcoming reminder of all events, re-armed after a data change with a lag (the real app
    debounces by 250 ms), so a dump read right after a change can still show the old alarm;
  - AgentOS: plugin enable/disable, catalog with risk levels (ExtensionDebugReceiver), auto-consent (ConsentDebugReceiver), desktop gateway;
  - a bridge whose session/prompt plays the fake-model script (scripted_tools.plan_round, the real planner) round by round against those tools,
    and returns session/update notes the way the runtime maps them (tool_call pending -> tool_call_update completed/failed with text).
The tool semantics here only need to be good enough for the driver's expectations; they are NOT a spec of the apps. `faults` lets a test break
one behaviour (an app that forgets to write, a catalog without a tool, ...) to see the driver name the failing step.
"""
import json
import os
import re
import shlex
import sqlite3
import tempfile
import uuid
from datetime import date, datetime, timedelta, timezone

import scripted_tools
import sample_apps_lib as L

DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
TZ = timezone(timedelta(hours=8))


class ToolError(Exception):
    pass


class FakePhone:
    def __init__(self, today=date(2026, 10, 7), faults=None, serial="emulator-5554", other_devices=None):
        self.dir = tempfile.mkdtemp(prefix="fake-phone-")
        self.today = today
        self.faults = set(faults or [])
        self._enabled = {s.package: False for s in L.SAMPLES.values()}
        self.consent_mode = "off"
        self.consent_log = []       # ConsentDebugReceiver `recent` entries, oldest first (max 50)
        self.remembered = set()     # (session id, tool) answered with "allow for this session"
        self._req = 0
        self.visible = set()        # packages whose tools are in the catalog right now (enabled, and the host has listed them)
        self.list_delay = {}        # package -> catalog reads after enable until its tools appear
        self.drop_delay = {}        # package -> catalog reads after disable until its tools are gone
        self._pending = []          # [package, appears(bool), reads left]
        self.armed = None           # the calendar's armed reminder: {series, start_ms, minutes_before, fire_ms}
        self.dirty = False          # a data change the calendar has not re-armed for yet
        self.rearm_lag = 0          # dump reads that still show the old alarm after a change
        self.orphans = {}           # alarm id -> fire_at that stays registered with AlarmManager after a (faulty) delete / switch-off
        self.model = {"modelUsable": True, "modelBaseUrl": "http://127.0.0.1:18787", "modelId": "fake-model"}
        self.desktop = False
        self.log = []          # every adb shell / run call, for assertions
        self.model_requests = []
        self._next_alarm = 1
        self.serial = serial
        self.other_devices = list(other_devices or [])      # serials `adb devices` lists besides this one (a second emulator to send an SMS to)
        self.sms = {"outbox": [], "drafts": [], "inbox": [], "next_id": 1, "settings": {"mask_codes": True, "allow_short_numbers": False, "rate_limit": 5},
                    "granted": {"android.permission.READ_SMS": True, "android.permission.SEND_SMS": True}}
        self.sms_tool_calls = []        # (tool, args) of the debug `tool` receiver
        self._init_dbs()

    @property
    def enabled(self):
        """package -> the user switched the plugin on. Assigning a whole dict (a test arranging the phone) also makes those plugins' tools visible at once."""
        return self._enabled

    @enabled.setter
    def enabled(self, value):
        self._enabled = dict(value)
        self.visible = {pkg for pkg, on in self._enabled.items() if on}
        self._pending = []

    # ------------------------------------------------------------------ databases
    def db_path(self, name):
        return os.path.join(self.dir, name)

    def _init_dbs(self):
        c = sqlite3.connect(self.db_path("alarms.db"))
        c.execute("CREATE TABLE alarms (id INTEGER PRIMARY KEY AUTOINCREMENT, hour INTEGER NOT NULL, minute INTEGER NOT NULL, label TEXT NOT NULL DEFAULT '', days INTEGER NOT NULL DEFAULT 0, enabled INTEGER NOT NULL DEFAULT 1, vibrate INTEGER NOT NULL DEFAULT 1, snooze_minutes INTEGER NOT NULL DEFAULT 10, ringtone_uri TEXT, snoozed_until INTEGER, fire_at INTEGER, created_at INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0)")
        c.commit()
        c.close()
        c = sqlite3.connect(self.db_path("calendar.db"))
        c.execute("CREATE TABLE calendars (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, color INTEGER NOT NULL, visible INTEGER NOT NULL DEFAULT 1, is_default INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)")
        c.execute("CREATE TABLE events (id TEXT PRIMARY KEY NOT NULL, calendar_id TEXT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', location TEXT NOT NULL DEFAULT '', all_day INTEGER NOT NULL DEFAULT 0, start_utc INTEGER NOT NULL, end_utc INTEGER NOT NULL, tz TEXT NOT NULL, start_day INTEGER NOT NULL DEFAULT 0, end_day INTEGER NOT NULL DEFAULT 0, color INTEGER, reminders TEXT NOT NULL DEFAULT '', recurrence TEXT NOT NULL DEFAULT 'none', recurrence_until INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        c.execute("INSERT INTO calendars VALUES ('cal-default', '日历', 1, 1, 1, 0)")
        c.commit()
        c.close()
        c = sqlite3.connect(self.db_path("todo.db"))
        c.execute("CREATE TABLE todos (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, title TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'todo', priority TEXT NOT NULL DEFAULT 'medium', due TEXT, due_all_day INTEGER NOT NULL DEFAULT 0, tags TEXT NOT NULL DEFAULT '[]', parent_id TEXT, completed_at TEXT)")
        c.commit()
        c.close()
        c = sqlite3.connect(self.db_path("notes.db"))
        c.execute("CREATE TABLE notes (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, tags TEXT NOT NULL, color TEXT NOT NULL, pinned INTEGER NOT NULL, status INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, trashed_at INTEGER, revision INTEGER NOT NULL)")
        c.commit()
        c.close()

    def q(self, db, sql, *args):
        c = sqlite3.connect(self.db_path(db))
        c.row_factory = sqlite3.Row
        try:
            cur = c.execute(sql, args)
            rows = cur.fetchall()
            c.commit()
            return rows
        finally:
            c.close()

    # ------------------------------------------------------------------ the adb surface used by the driver
    def sh(self, cmd, check=True, timeout=0):
        self.log.append(cmd)
        if cmd.startswith("date +%Y-%m-%d"):
            return self.today.isoformat() + "\n"
        if cmd.startswith("date +%z"):
            return "+0800\n"
        if cmd.startswith("am broadcast"):
            return self._broadcast(cmd)
        return ""

    def run(self, *args, **kw):
        self.log.append(" ".join(args))
        return ""

    def prop(self, name):
        return {"ro.product.model": "FakePhone", "ro.build.version.sdk": "36", "ro.build.version.release": "16", "ro.build.fingerprint": "fake"}.get(name, "")

    adb = "fake-adb"

    def devices(self):
        return [self.serial] + self.other_devices

    # ------------------------------------------------------------------ debug receivers
    def _broadcast(self, cmd):
        parts = shlex.split(cmd)
        comp = parts[parts.index("-n") + 1]
        extras = {}
        self.extra_kinds = {}
        i = 0
        while i < len(parts):
            if parts[i] in ("--es", "--ez", "--el", "--ei"):
                v = parts[i + 2]
                extras[parts[i + 1]] = (v == "true") if parts[i] == "--ez" else (int(v) if parts[i] in ("--el", "--ei") else v)
                self.extra_kinds[parts[i + 1]] = parts[i]
                i += 3
            else:
                i += 1
        if comp.endswith("ExtensionDebugReceiver"):
            data = self._ext(extras)
        elif comp.endswith("ConsentDebugReceiver"):
            data = self._consent(extras)
        elif comp.endswith("DesktopGatewayDebugReceiver"):
            data = self._gateway(extras)
        elif comp.startswith("org.agentos.sample.todo/"):
            data = self._todo_app(extras)
        elif comp.startswith("org.agentos.sample.sms/"):
            data = self._sms_app(extras)
        elif comp.endswith("/.debug.DebugToolReceiver"):
            data = self._alarm_app(extras)
        elif comp.endswith("/.debug.DebugCallReceiver"):
            data = self._notes_app(extras)
        elif comp.endswith("/.debug.DebugReceiver"):
            data = self._calendar_app(extras)
            if data is None:
                return "Broadcasting: Intent { }\nBroadcast completed: result=0\n"   # seed / clear / remind_test answer in logcat only
        else:
            return "Broadcast completed: result=0\n"
        code = 1 if data.get("ok", "error" not in data) else 2
        return 'Broadcasting: Intent { }\nBroadcast completed: result=%d, data="%s"' % (code, json.dumps(data, ensure_ascii=False).replace("/", "\\/"))

    def _plugin_json(self, s):
        return {"id": s.package + "/agent-plugin", "packageName": s.package, "name": s.name, "enabled": self.enabled[s.package], "status": "ready",
                "toolCount": len(s.tools)}

    def _plugin_for(self, ident):
        return next((s for s in L.SAMPLES.values() if ident in (s.package, s.package + "/agent-plugin")), None)

    def _ext(self, ex):
        op = ex.get("op")
        if op == "list":
            return {"ok": True, "plugins": [self._plugin_json(s) for s in L.SAMPLES.values()]}
        if op in ("enable", "disable"):
            s = self._plugin_for(ex.get("id"))
            if s is None:
                return {"ok": False, "error": "agentos.ext.not_found: unknown plugin"}
            self.enabled[s.package] = op == "enable"
            self._schedule(s.package, op == "enable")
            return {"ok": True, "plugin": self._plugin_json(s)}
        if op == "catalog":
            self._tick()
            return {"ok": True, "catalog": {"version": 1, "tools": [{"name": n, "risk": r.lower(), "enabled": True} for n, r in self.catalog().items()]}}
        if op == "wait_catalog":
            # like the app: waits for catalog changes until the tool is (or is no longer) there; nothing left to change = it would wait out the timeout
            want_absent = bool(ex.get("absent", False))
            for _ in range(200):        # the app's timeout, as a number of change cycles
                if (ex.get("name") in self.catalog()) != want_absent:
                    return {"ok": True, "met": True}
                if not self._pending:
                    return {"ok": True, "met": False}
                self._tick()
            return {"ok": True, "met": (ex.get("name") in self.catalog()) != want_absent}
        return {"ok": False, "error": "unknown op: %s" % op}

    def _schedule(self, package, appears):
        self._pending = [x for x in self._pending if x[0] != package]
        delay = (self.list_delay if appears else self.drop_delay).get(package, 0)
        if delay <= 0:
            (self.visible.add if appears else self.visible.discard)(package)
        else:
            self._pending.append([package, appears, delay])

    def _tick(self):
        """One catalog change cycle: every pending appearance / disappearance comes one read closer."""
        for item in list(self._pending):
            item[2] -= 1
            if item[2] <= 0:
                (self.visible.add if item[1] else self.visible.discard)(item[0])
                self._pending.remove(item)

    def catalog(self):
        out = {}
        for s in L.SAMPLES.values():
            if s.package not in self.visible:
                continue
            for t in s.tools + ([] if s.name != "alarm" else ["alarm_snooze"]):
                if "hide-" + t in self.faults:
                    continue
                n = scripted_tools.tool_name(s.name, s.name, t)
                out[n] = "HIGH" if L.expected_risk(t) == "HIGH" else ("WRITE" if "risk-read" not in self.faults else "READ")
        return out

    def _consent(self, ex):
        if ex.get("op") == "mode":
            m = {"allow": "ALLOW", "allowonce": "ALLOW_ONCE", "deny": "DENY", "off": "OFF"}.get(str(ex.get("mode")).lower())
            if m is None:
                return {"ok": False, "error": "mode must be allow|allowOnce|deny|off"}
            self.consent_mode = str(ex["mode"]).lower() if m != "ALLOW_ONCE" else "allowonce"
            return {"ok": True, "mode": m}
        if ex.get("op") == "status":
            return {"ok": True, "mode": self.consent_mode.upper()}
        if ex.get("op") == "recent":
            return {"ok": True, "mode": self.consent_mode.upper(), "recent": list(self.consent_log[-50:])}
        return {"ok": False, "error": "unknown op"}

    def _gateway(self, ex):
        op = ex.get("op")
        if op == "enable":
            self.desktop = True
        elif op in ("disable", "revoke_all"):
            self.desktop = False
        if op == "status":
            return dict(self.model, ok=True, enabled=self.desktop)
        return {"ok": True, "code": "123456"} if op == "pair" else {"ok": True, "enabled": self.desktop}

    # ------------------------------------------------------------------ the sample apps' own debug receivers (dump / reset), same JSON as the real ones
    def _page(self, extras, total):
        """(offset, limit, next_offset) like the real receivers: int extras only (a --el extra would not be read by getIntExtra)."""
        for k in ("offset", "limit"):
            if k in extras and self.extra_kinds.get(k) != "--ei":
                raise AssertionError("%s must be an Int extra (--ei), got %s" % (k, self.extra_kinds.get(k)))
        offset = max(0, min(int(extras.get("offset", 0)), total))
        limit = max(1, min(int(extras.get("limit", 50)), 200))
        nxt = offset + limit if offset + limit < total else None
        if "dump-stuck" in self.faults and nxt is not None:
            nxt = offset
        return offset, limit, nxt

    def _alarm_app(self, ex):
        cmd = ex.get("cmd")
        if cmd == "reset":
            n = len(self.q("alarms.db", "SELECT id FROM alarms"))
            self.q("alarms.db", "DELETE FROM alarms")
            left = 1 if "reset-leaves-registered" in self.faults and n else 0
            self.orphans = {"stuck": "2026-10-08T00:00:00+08:00"} if left else {}
            return {"cleared": n, "remaining_registered": left}
        if cmd != "dump":
            return {"error": "unknown cmd '%s'; use dump or reset" % cmd}
        rows = self.q("alarms.db", "SELECT * FROM alarms ORDER BY hour, minute, id")
        offset, limit, nxt = self._page(ex, len(rows))
        scheduled = []
        for r in rows:
            if r["enabled"] or "switch-off-keeps-registration" in self.faults:
                scheduled.append({"id": str(r["id"]), "fire_at": "2026-10-08T%02d:%02d:00+08:00" % (r["hour"], r["minute"]), "registered": "alarm-not-registered" not in self.faults})
        scheduled += [{"id": i, "fire_at": f, "registered": True} for i, f in self.orphans.items()]
        if "dump-fails-alarm" in self.faults:
            return {"error": "IllegalStateException: boom"}
        return {"total": len(rows), "offset": offset, "limit": limit, "next_offset": nxt,
                "alarms": [self._alarm_json(r) | {"repeat": "once", "vibrate": True, "snooze_minutes": r["snooze_minutes"], "snoozed_until": None, "ringing": False} for r in rows[offset:offset + limit]],
                "scheduled": scheduled, "system": {"next_alarm_clock": None, "now": "2026-10-07T12:00:00+08:00", "time_zone": "Asia/Shanghai"}}

    def _notes_app(self, ex):
        cmd = ex.get("cmd")
        if cmd == "reset":
            n = len(self.q("notes.db", "SELECT id FROM notes"))
            self.q("notes.db", "DELETE FROM notes")
            return {"ok": True, "deleted": n}
        if cmd != "dump":
            return {"ok": False, "error": "unknown cmd: %s (use dump or reset)" % cmd}
        if "dump-fails-notes" in self.faults:
            return {"ok": False, "error": "boom"}
        rows = self.q("notes.db", "SELECT rowid AS rid, * FROM notes ORDER BY rid")
        offset, limit, nxt = self._page(ex, len(rows))
        page = rows[offset:offset + limit]
        tags = {}
        for r in rows:
            for t in json.loads(r["tags"]):
                tags[t] = tags.get(t, 0) + 1
        return {"notes": [{"id": r["id"], "title": r["title"], "content": r["content"], "tags": json.loads(r["tags"]), "color": r["color"], "pinned": bool(r["pinned"]),
                           "archived": r["status"] == 1, "trashed": r["status"] == 2, "created_at": "2026-10-07T12:00:00+08:00", "updated_at": "2026-10-07T12:00:00+08:00",
                           "content_length": len(r["content"]), "revision": r["revision"]} for r in page],
                "tags": [{"name": k, "count": v} for k, v in sorted(tags.items())], "total": len(rows), "offset": offset, "count": len(page), "next_offset": nxt}

    def _todo_json(self, r, full=False):
        """A todo the way todo_get / dump print it (full) or in the compact list form."""
        out = {"id": r["id"], "title": r["title"], "status": r["status"], "priority": r["priority"]}
        if full:
            out.update({"due": r["due"], "due_all_day": bool(r["due_all_day"]), "tags": json.loads(r["tags"]), "parent_id": r["parent_id"],
                        "completed_at": r["completed_at"], "overdue": False, "created_at": "2026-10-07T12:00:00+08:00", "updated_at": "2026-10-07T12:00:00+08:00",
                        "notes": r["notes"]})
        return out

    def _todo_app(self, ex):
        cmd = ex.get("cmd")
        if cmd == "reset":
            n = len(self.q("todo.db", "SELECT id FROM todos"))
            if "todo-reset-noop" not in self.faults:
                self.q("todo.db", "DELETE FROM todos")
            left = len(self.q("todo.db", "SELECT id FROM todos"))
            return {"cleared": n, "remaining": left, "remaining_in_db": left}
        if cmd != "dump":
            return {"ok": False, "error": "unknown cmd: %s (use dump or reset)" % cmd}
        if "dump-fails-todo" in self.faults:
            return {"ok": False, "error": "boom"}
        rows = self.q("todo.db", "SELECT * FROM todos ORDER BY seq")
        offset, limit, nxt = self._page(ex, len(rows))
        page = rows[offset:offset + limit]
        counts = {k: len([r for r in rows if r["status"] == k]) for k in ("todo", "doing", "done", "shelved")}
        return {"todos": [self._todo_json(r, full=True) for r in page], "counts": counts, "total": len(rows), "offset": offset, "count": len(page),
                "next_offset": nxt, "now": "2026-10-07T12:00:00+08:00", "time_zone": "Asia/Shanghai"}

    # ---- sms app: the outbox / drafts / settings / permissions are the app's own records; the inbox is the system SMS store (not in the dump)
    def sms_mode(self):
        read, send = (self.sms["granted"][p] for p in L.SMS_PERMISSIONS)
        return "full" if read and send else "compose_only" if not read and not send else "partial"

    def _sms_app(self, ex):
        cmd = ex.get("cmd")
        st = self.sms
        if cmd == "reset":
            n, d = len(st["outbox"]), len(st["drafts"])
            if "sms-reset-noop" not in self.faults:
                st["outbox"], st["drafts"] = [], []
            return {"cleared": n, "outbox_remaining": len(st["outbox"]), "drafts_cleared": d}
        if cmd == "set":
            key, value = ex.get("key"), ex.get("value")
            if key in ("mask_codes", "allow_short_numbers"):
                st["settings"][key] = str(value).lower() == "true"
            elif key == "rate_limit":
                st["settings"][key] = int(value)
            else:
                return {"error": "unknown key '%s'; use mask_codes, allow_short_numbers or rate_limit" % key}
            return dict(st["settings"])
        if cmd != "dump":
            return {"error": "unknown cmd '%s'; use dump, reset or set" % cmd}
        if "dump-fails-sms" in self.faults:
            return {"error": "IllegalStateException: boom"}
        rows = st["outbox"]
        offset, limit, nxt = self._page(ex, len(rows))
        return {"mode": self.sms_mode(), "permissions": {"read_sms": st["granted"][L.SMS_PERMISSIONS[0]], "send_sms": st["granted"][L.SMS_PERMISSIONS[1]]},
                "settings": dict(st["settings"]), "outbox": [dict(o, created_at="2026-10-07T12:00:00+08:00", updated_at="2026-10-07T12:00:00+08:00") for o in rows[offset:offset + limit]],
                "drafts": [dict(d, created_at="2026-10-07T12:00:00+08:00") for d in st["drafts"]], "total": len(rows), "offset": offset, "count": len(rows[offset:offset + limit]),
                "next_offset": nxt, "now": "2026-10-07T12:00:00+08:00", "time_zone": "Asia/Shanghai"}

    NOW_MS = int(datetime(2026, 10, 7, 12, 0, tzinfo=TZ).timestamp() * 1000)

    def _occurrence_id(self, r, start):
        return "%s@%d" % (r["id"], start)

    def _compute_armed(self):
        """The earliest upcoming reminder of all events (what ReminderPlanner.next gives)."""
        best = None
        for r, s0 in self._occurrences(self.NOW_MS - 86400000, self.NOW_MS + 400 * 86400000):
            for m in [int(x) for x in r["reminders"].split(",") if x]:
                fire = s0 - m * 60000
                if fire > self.NOW_MS and (best is None or (fire, r["title"], m) < (best["fire_ms"], best["title"], best["minutes_before"])):
                    best = {"series": r["id"], "start_ms": s0, "title": r["title"], "minutes_before": m, "fire_ms": fire}
        return best

    def _events_changed(self, deleted=False):
        """A change to events or calendars: the calendar re-arms its reminder alarm a moment later (debounced)."""
        if deleted and "delete-keeps-armed" in self.faults:
            return
        self.dirty = True
        self.rearm_lag = 1

    def _sync_armed(self, force=False):
        if not self.dirty:
            return
        if force or self.rearm_lag == 0:
            if "reminder-never-armed" not in self.faults or force:
                self.armed = self._compute_armed()
            self.dirty = False
        else:
            self.rearm_lag -= 1

    def _calendar_app(self, ex):
        cmd = ex.get("cmd")
        if cmd == "clear":      # logs only
            if "calendar-reset-noop" not in self.faults:
                self.q("calendar.db", "DELETE FROM events")
                self.q("calendar.db", "DELETE FROM calendars WHERE is_default = 0")
                self._events_changed()
            return None
        if cmd == "reset":
            n = len(self.q("calendar.db", "SELECT id FROM events"))
            if "calendar-reset-noop" not in self.faults:
                self.q("calendar.db", "DELETE FROM events")
                self.q("calendar.db", "DELETE FROM calendars WHERE is_default = 0")
                self.dirty = True
                self._sync_armed(force=True)            # reset reschedules synchronously
            left = 1 if (self.armed is not None or "calendar-reset-leaves-registered" in self.faults) else 0
            return {"cleared": n, "calendars_remaining": len(self.q("calendar.db", "SELECT id FROM calendars")), "remaining_scheduled": left}
        if cmd != "dump":
            return None
        if "dump-fails-calendar" in self.faults:
            return {"error": "IllegalStateException: boom"}
        self._sync_armed()
        rows = self.q("calendar.db", "SELECT * FROM events ORDER BY start_utc, id")
        offset, limit, nxt = self._page(ex, len(rows))
        cals = self.q("calendar.db", "SELECT * FROM calendars ORDER BY is_default DESC, created_at")
        hidden = {c["id"] for c in cals if not c["visible"]}
        counts = {c["id"]: len([r for r in rows if r["calendar_id"] == c["id"]]) for c in cals}
        names = {c["id"]: c["name"] for c in cals}
        events = []
        for r in rows[offset:offset + limit]:
            e = self._event_json(r)
            e.update({"id": self._occurrence_id(r, r["start_utc"]), "series_id": r["id"], "calendar_id": r["calendar_id"], "calendar_name": names.get(r["calendar_id"]),
                      "end": self._iso(r["end_utc"]), "all_day": bool(r["all_day"]), "description": r["description"], "color": "#4285F4",
                      "recurrence_until": self._iso(r["recurrence_until"]) if r["recurrence_until"] else None, "timezone": r["tz"],
                      "hidden": r["calendar_id"] in hidden, "created_at": "2026-10-07T12:00:00+08:00", "updated_at": "2026-10-07T12:00:00+08:00"})
            events.append(e)
        reminders = []
        if self.armed is not None:
            cur = self.q("calendar.db", "SELECT title FROM events WHERE id = ?", self.armed["series"])
            reminders.append({"id": "%s@%d" % (self.armed["series"], self.armed["start_ms"]), "title": cur[0]["title"] if cur else None,
                              "minutes_before": self.armed["minutes_before"], "fire_at": self._iso(self.armed["fire_ms"]),
                              "registered": "reminder-not-registered" not in self.faults})
            if "two-armed" in self.faults:
                reminders.append(dict(reminders[0], id=reminders[0]["id"] + "-b"))
        return {"calendars": [{"id": c["id"], "name": c["name"], "color": "#34A853", "visible": bool(c["visible"]), "is_default": bool(c["is_default"]),
                               "event_count": counts[c["id"]]} for c in cals],
                "events": events, "reminders_scheduled": reminders, "timezone": "Asia/Shanghai", "total": len(rows), "offset": offset, "limit": limit,
                "count": len(events), "next_offset": nxt}

    # ------------------------------------------------------------------ running a tool the way AgentOS would
    def call_tool(self, name, args, session="sess-1"):
        """-> (status, text) as the ACP update would carry them. The confirmation behaves like the real broker + AutoConsentResponder:
        READ is not asked; WRITE offers ALLOW_ONCE / ALLOW_FOR_SESSION / DENY, HIGH only ALLOW_ONCE / DENY; mode allow answers WRITE with
        ALLOW_FOR_SESSION (remembered for this session: later calls are not asked at all, whatever the mode is by then) and HIGH with ALLOW_ONCE."""
        catalog = self.catalog()
        if name not in catalog:
            return "failed", "Tool %s not found" % name
        risk = catalog[name]
        if risk != "READ" and (session, name) not in self.remembered:
            options = ["ALLOW_ONCE", "DENY"] if risk == "HIGH" else ["ALLOW_ONCE", "ALLOW_FOR_SESSION", "DENY"]
            mode = self.consent_mode
            answer = {"allow": "ALLOW_ONCE" if risk == "HIGH" else "ALLOW_FOR_SESSION", "allowonce": "ALLOW_ONCE", "deny": "DENY"}.get(mode)
            self._req += 1
            plugin = name.split("__")[1]
            self.consent_log.append({"requestId": "req_%d" % self._req, "tool": name, "risk": risk, "source": L.source_line(plugin), "args": "{}",
                                     "options": options, "answeredWith": answer, "end": "ANSWERED" if answer else "TIMED_OUT", "notice": None})
            del self.consent_log[:-50]
            if answer is None:
                return "failed", "[agentos:tool_denied] The user did not respond to the confirmation in time."
            if answer == "DENY":
                return "failed", "[agentos:tool_denied] The user declined this tool call."
            if answer == "ALLOW_FOR_SESSION":
                self.remembered.add((session, name))
        s, tool = next((s, name.split("__", 3)[3]) for s in L.SAMPLES.values() if name.startswith("mcp__%s__" % s.name))
        try:
            return "completed", json.dumps(getattr(self, "t_" + tool)(args), ensure_ascii=False)
        except ToolError as e:
            return "failed", str(e)
        except AttributeError as e:
            return "failed", "unknown tool %s (%s)" % (tool, e)

    # ---- alarm
    def t_alarm_create(self, a):
        if "time" not in a:
            raise ToolError("Missing required parameter 'time'")
        m = re.fullmatch(r"([01]\d|2[0-3]):([0-5]\d)", str(a["time"]))
        if not m:
            raise ToolError("time must be HH:mm (24-hour)")
        mask = sum(1 << DAYS.index(d) for d in a.get("days", []))
        if "alarm-forgets-write" in self.faults:
            return {"id": "77", "time": a["time"]}
        self.q("alarms.db", "INSERT INTO alarms (hour, minute, label, days) VALUES (?,?,?,?)", int(m.group(1)), int(m.group(2)), a.get("label", ""), mask)
        row = self.q("alarms.db", "SELECT max(id) AS i FROM alarms")[0]["i"]
        return {"id": str(row), "time": a["time"], "enabled": True}

    def _alarm(self, a):
        rows = self.q("alarms.db", "SELECT * FROM alarms WHERE id = ?", a.get("id", ""))
        if not rows:
            raise ToolError("No alarm with id '%s'" % a.get("id"))
        return rows[0]

    def _alarm_json(self, r):
        return {"id": str(r["id"]), "time": "%02d:%02d" % (r["hour"], r["minute"]), "label": r["label"], "enabled": bool(r["enabled"]),
                "days": [DAYS[i] for i in range(7) if r["days"] & (1 << i)],
                "next_fire_at": "2026-10-08T%02d:%02d:00+08:00" % (r["hour"], r["minute"]) if r["enabled"] else None}

    def t_alarm_list(self, a):
        rows = self.q("alarms.db", "SELECT * FROM alarms ORDER BY id")
        return {"count": len(rows), "alarms": [self._alarm_json(r) for r in rows]}

    def t_alarm_get(self, a):
        return self._alarm_json(self._alarm(a))

    def t_alarm_update(self, a):
        r = self._alarm(a)
        h, mi = r["hour"], r["minute"]
        if "time" in a:
            h, mi = (int(x) for x in a["time"].split(":"))
        self.q("alarms.db", "UPDATE alarms SET hour=?, minute=?, label=? WHERE id=?", h, mi, a.get("label", r["label"]), r["id"])
        return self._alarm_json(self._alarm(a))

    def t_alarm_set_enabled(self, a):
        r = self._alarm(a)
        if "enabled" not in a:
            raise ToolError("Missing required parameter 'enabled' (boolean)")
        self.q("alarms.db", "UPDATE alarms SET enabled=? WHERE id=?", 1 if a["enabled"] else 0, r["id"])
        return self._alarm_json(self._alarm(a))

    def t_alarm_delete(self, a):
        r = self._alarm(a)
        self.q("alarms.db", "DELETE FROM alarms WHERE id=?", r["id"])
        if "delete-keeps-registration" in self.faults:
            self.orphans[str(r["id"])] = "2026-10-08T%02d:%02d:00+08:00" % (r["hour"], r["minute"])
        return {"deleted": True, "alarm": self._alarm_json(r)}

    def t_alarm_next(self, a):
        rows = self.q("alarms.db", "SELECT * FROM alarms WHERE enabled=1 ORDER BY hour, minute")
        if not rows:
            return None
        j = self._alarm_json(rows[0])
        return {"alarm": j, "next_fire_at": j["next_fire_at"], "fires_in_minutes": 600}

    def t_alarm_system_next(self, a):
        """The phone's next alarm of any app: here this app's alarms and `other_alarm` (the Clock app's), which a test can set."""
        rows = self.q("alarms.db", "SELECT * FROM alarms WHERE enabled=1 ORDER BY hour, minute")
        mine = self._alarm_json(rows[0])["next_fire_at"] if rows else None
        other = getattr(self, "other_alarm", None)
        if "system-next-bad-shape" in self.faults:
            return {"next_fire_at": mine}
        if "system-next-later-and-not-mine" in self.faults and mine:
            return {"next_fire_at": "2026-10-08T23:59:00+08:00", "fires_in_minutes": 900, "owned_by_this_app": False}
        if "system-next-owned-but-gone" in self.faults and not rows:
            return {"next_fire_at": "2026-10-08T05:00:00+08:00", "fires_in_minutes": 5, "owned_by_this_app": True}
        if other and (mine is None or other < mine):
            return {"next_fire_at": other, "fires_in_minutes": 300, "owned_by_this_app": False}
        return None if mine is None else {"next_fire_at": mine, "fires_in_minutes": 600, "owned_by_this_app": True}

    def t_alarm_dismiss(self, a):
        raise ToolError("No alarm is ringing right now")

    # ---- calendar
    def _ms(self, iso):
        try:
            dt = datetime.fromisoformat(iso)
        except ValueError:
            raise ToolError("Invalid time '%s': use ISO-8601" % iso) from None
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=TZ)
        return int(dt.timestamp() * 1000)

    def _iso(self, ms):
        return datetime.fromtimestamp(ms / 1000, tz=TZ).isoformat()

    def t_calendar_list(self, a):
        return {"calendars": [{"id": r["id"], "name": r["name"]} for r in self.q("calendar.db", "SELECT * FROM calendars")]}

    def t_calendar_create(self, a):
        if "name" not in a:
            raise ToolError("Missing required parameter 'name'")
        cid = "cal-" + uuid.uuid4().hex[:6]
        self.q("calendar.db", "INSERT INTO calendars VALUES (?,?,?,1,0,0)", cid, a["name"], 5)
        return {"id": cid, "name": a["name"]}

    def t_calendar_delete(self, a):
        rows = self.q("calendar.db", "SELECT * FROM calendars WHERE id=?", a.get("id", ""))
        if not rows:
            raise ToolError("No calendar with id '%s'" % a.get("id"))
        if rows[0]["is_default"]:
            raise ToolError("The default calendar cannot be deleted")
        n = len(self.q("calendar.db", "SELECT id FROM events WHERE calendar_id=?", a["id"]))
        self.q("calendar.db", "DELETE FROM events WHERE calendar_id=?", a["id"])
        self.q("calendar.db", "DELETE FROM calendars WHERE id=?", a["id"])
        self._events_changed(deleted=True)
        return {"deleted": True, "id": a["id"], "deleted_events": n}

    def _event_json(self, r, start=None):
        s = r["start_utc"] if start is None else start
        return {"id": r["id"] if start is None or start == r["start_utc"] else "%s@%d" % (r["id"], s), "series_id": r["id"], "title": r["title"],
                "start": self._iso(s), "end": self._iso(s + r["end_utc"] - r["start_utc"]), "location": r["location"],
                "reminder_minutes": [int(x) for x in r["reminders"].split(",") if x], "recurrence": r["recurrence"]}

    def t_event_create(self, a):
        if "title" not in a:
            raise ToolError("Missing required parameter 'title'")
        if "start" not in a:
            raise ToolError("Missing required parameter 'start'")
        start = self._ms(a["start"])
        end = self._ms(a["end"]) if "end" in a else start + 3600000
        until = self._ms(a["recurrence_until"]) if a.get("recurrence_until") else None
        eid = "ev-" + uuid.uuid4().hex[:6]
        self.q("calendar.db", "INSERT INTO events VALUES (?,?,?,?,?,0,?,?,?,0,0,NULL,?,?,?,0,0)", eid, a.get("calendar_id", "cal-default"), a["title"], a.get("description", ""), a.get("location", ""),
               start, end, "Asia/Shanghai", ",".join(str(x) for x in a.get("reminder_minutes", [])), a.get("recurrence", "none"), until)
        self._events_changed()
        return self._event_json(self._event(eid))

    def _event(self, eid):
        rows = self.q("calendar.db", "SELECT * FROM events WHERE id=?", str(eid).split("@")[0])
        if not rows:
            raise ToolError("No event with id '%s'" % eid)
        return rows[0]

    def t_event_get(self, a):
        return self._event_json(self._event(a.get("id", "")))

    def t_event_update(self, a):
        r = self._event(a.get("id", ""))
        reminders = ",".join(str(x) for x in a["reminder_minutes"]) if "reminder_minutes" in a else r["reminders"]
        self.q("calendar.db", "UPDATE events SET title=?, location=?, reminders=? WHERE id=?", a.get("title", r["title"]), a.get("location", r["location"]), reminders, r["id"])
        self._events_changed()
        return self._event_json(self._event(r["id"]))

    def t_event_delete(self, a):
        r = self._event(a.get("id", ""))
        self.q("calendar.db", "DELETE FROM events WHERE id=?", r["id"])
        self._events_changed(deleted=True)
        return {"deleted": True, "id": a["id"], "series_id": r["id"], "title": r["title"]}

    def _occurrences(self, lo, hi):
        out = []
        for r in self.q("calendar.db", "SELECT * FROM events"):
            s = r["start_utc"]
            step = {"none": None, "daily": 86400000, "weekly": 7 * 86400000}.get(r["recurrence"])
            while s < hi:
                if s + (r["end_utc"] - r["start_utc"]) > lo:
                    out.append((r, s))
                if step is None:
                    break
                s += step
                if r["recurrence_until"] and s > r["recurrence_until"]:
                    break
        return sorted(out, key=lambda x: x[1])

    def t_event_list(self, a):
        lo = self._ms(a["from"]) if "from" in a else 0
        hi = self._ms(a["to"]) if "to" in a else 1 << 60
        evs = [self._event_json(r, s) for r, s in self._occurrences(lo, hi) if a.get("calendar_id") in (None, r["calendar_id"])]
        return {"count": len(evs), "truncated": False, "events": evs}

    def t_event_search(self, a):
        if "query" not in a:
            raise ToolError("Missing required parameter 'query'")
        rows = [r for r in self.q("calendar.db", "SELECT * FROM events") if a["query"].lower() in (r["title"] + r["location"] + r["description"]).lower()]
        return {"count": len(rows), "truncated": False, "events": [self._event_json(r) for r in rows]}

    def t_agenda_today(self, a):
        return {"date": self.today.isoformat(), "timezone": "Asia/Shanghai", "count": 0, "events": []}

    def t_free_slots(self, a):
        d = date.fromisoformat(a["date"])
        day0 = int(datetime(d.year, d.month, d.day, 9, tzinfo=TZ).timestamp() * 1000)
        day1 = int(datetime(d.year, d.month, d.day, 18, tzinfo=TZ).timestamp() * 1000)
        busy = [(max(s, day0), min(s + r["end_utc"] - r["start_utc"], day1)) for r, s in self._occurrences(day0, day1)]
        slots, cur = [], day0
        need = int(a["duration_minutes"]) * 60000
        for b0, b1 in sorted(busy):
            if b0 - cur >= need:
                slots.append({"start": self._iso(cur), "end": self._iso(b0)})
            cur = max(cur, b1)
        if day1 - cur >= need:
            slots.append({"start": self._iso(cur), "end": self._iso(day1)})
        return {"date": a["date"], "timezone": "Asia/Shanghai", "slots": slots, "busy": []}

    # ---- todo (the semantics of plugins/samples/todo/README.md, as far as the driver's expectations need them)
    PRIORITY_RANK = {"high": 0, "medium": 1, "low": 2}
    STATUSES = ("todo", "doing", "done", "shelved")

    def _todo(self, a):
        rows = self.q("todo.db", "SELECT * FROM todos WHERE id = ?", str(a.get("id", "")))
        if not rows:
            raise ToolError("No todo with id '%s'" % a.get("id"))
        return rows[0]

    def _parse_due(self, text, all_day=None):
        """-> (due string as the app stores it, all_day). A date (all-day) or an ISO-8601 date-time WITH an offset; anything else is an error."""
        t = str(text).strip()
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", t):
            try:
                date.fromisoformat(t)
            except ValueError:
                raise ToolError("Invalid due '%s': not a calendar date" % t) from None
            if all_day is False:
                raise ToolError("due_all_day=false needs a time: pass due as an ISO-8601 date-time with UTC offset.")
            return t, True
        if "todo-accepts-bad-due" in self.faults and re.fullmatch(r"\d{4}-\d{2}-\d{2}T[\d:]+", t):
            return t, False
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?(Z|[+-]\d{2}:\d{2})", t):
            raise ToolError("Invalid due '%s': use YYYY-MM-DD or an ISO-8601 date-time with a UTC offset, e.g. 2026-10-12T17:00:00+08:00" % t)
        try:
            dt = datetime.fromisoformat(t.replace("Z", "+00:00"))
        except ValueError:
            raise ToolError("Invalid due '%s'" % t) from None
        if all_day:
            return t[:10], True
        return dt.astimezone(TZ).replace(microsecond=0).isoformat(), False

    def _due_cmp_key(self, due, all_day, bound, bound_all_day):
        """Both sides as comparable values: instants when both are timed, local dates when one of them is a date."""
        if all_day or bound_all_day:
            local = due[:10] if all_day else datetime.fromisoformat(due).astimezone(TZ).date().isoformat()
            return local, bound[:10] if bound_all_day else datetime.fromisoformat(bound).astimezone(TZ).date().isoformat()
        return datetime.fromisoformat(due).timestamp(), datetime.fromisoformat(bound).timestamp()

    def _todo_overdue(self, r):
        if r["status"] not in ("todo", "doing") or not r["due"]:
            return False
        if r["due_all_day"]:
            return r["due"] < self.today.isoformat()
        return datetime.fromisoformat(r["due"]).timestamp() * 1000 < self.NOW_MS

    def _compact_todo(self, r, rows):
        out = self._todo_json(r)
        if r["due"]:
            out["due"], out["due_all_day"] = r["due"], bool(r["due_all_day"])
        if json.loads(r["tags"]):
            out["tags"] = json.loads(r["tags"])
        if r["parent_id"]:
            out["parent_id"] = r["parent_id"]
        if r["completed_at"]:
            out["completed_at"] = r["completed_at"]
        if self._todo_overdue(r):
            out["overdue"] = True
        kids = [x for x in rows if x["parent_id"] == r["id"]]
        if kids:
            out["subtask_total"], out["subtask_done"] = len(kids), len([x for x in kids if x["status"] == "done"])
        return out

    def _check_enum(self, name, value, allowed):
        if value not in allowed:
            raise ToolError("Invalid %s '%s': use one of %s" % (name, value, ", ".join(allowed)))
        return value

    def t_todo_create(self, a):
        title = a.get("title")
        if not isinstance(title, str) or not title.strip():
            raise ToolError("Missing required argument: title")
        if len(title) > 200:
            raise ToolError("title is too long (200 characters at most)")
        priority = self._check_enum("priority", a.get("priority", "medium"), tuple(self.PRIORITY_RANK))
        status = self._check_enum("status", a.get("status", "todo"), self.STATUSES)
        due, all_day = (None, False)
        if a.get("due"):
            due, all_day = self._parse_due(a["due"], a.get("due_all_day"))
        parent = a.get("parent_id") or None
        if parent:
            p = self._todo({"id": parent})
            if p["parent_id"] and "todo-nested-subtask-allowed" not in self.faults:
                raise ToolError("A subtask cannot have subtasks: only one level is supported")
        tid = uuid.uuid4().hex[:8]
        done_at = datetime.fromtimestamp(self.NOW_MS / 1000, tz=TZ).isoformat() if status == "done" else None
        if "todo-forgets-write" not in self.faults:
            self.q("todo.db", "INSERT INTO todos (id, title, notes, status, priority, due, due_all_day, tags, parent_id, completed_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                   tid, title.strip(), a.get("notes", ""), status, priority, due, 1 if all_day else 0, json.dumps(a.get("tags", []), ensure_ascii=False), parent, done_at)
            return self._todo_json(self._todo({"id": tid}), full=True)
        return {"id": tid, "title": title.strip(), "status": status, "priority": priority}

    def t_todo_get(self, a):
        r = self._todo(a)
        rows = self.q("todo.db", "SELECT * FROM todos ORDER BY seq")
        out = self._todo_json(r, full=True)
        out["subtasks"] = [self._compact_todo(x, rows) for x in rows if x["parent_id"] == r["id"]]
        return out

    def t_todo_update(self, a):
        r = self._todo(a)
        sets, vals = [], []
        if "title" in a:
            sets.append("title=?"), vals.append(a["title"])
        if "notes" in a:
            sets.append("notes=?"), vals.append(a["notes"])
        if "priority" in a:
            sets.append("priority=?"), vals.append(self._check_enum("priority", a["priority"], tuple(self.PRIORITY_RANK)))
        if "tags" in a:
            sets.append("tags=?"), vals.append(json.dumps(a["tags"], ensure_ascii=False))
        if "status" in a:
            sets.append("status=?"), vals.append(self._check_enum("status", a["status"], self.STATUSES))
        if "due" in a:
            if a["due"] == "":
                sets += ["due=NULL", "due_all_day=0"]
            else:
                due, all_day = self._parse_due(a["due"], a.get("due_all_day"))
                sets += ["due=?", "due_all_day=?"]
                vals += [due, 1 if all_day else 0]
        if not sets:
            raise ToolError("Nothing to update: pass at least one of title, notes, priority, due, tags, parent_id, status.")
        self.q("todo.db", "UPDATE todos SET %s WHERE id=?" % ", ".join(sets), *vals, r["id"])
        return self._todo_json(self._todo({"id": r["id"]}), full=True)

    def t_todo_set_status(self, a):
        r = self._todo(a)
        if "status" not in a:
            raise ToolError("Missing required argument: status")
        status = self._check_enum("status", a["status"], self.STATUSES)
        if status != r["status"]:
            done_at = datetime.fromtimestamp(self.NOW_MS / 1000, tz=TZ).isoformat() if status == "done" and "todo-done-no-completed-at" not in self.faults else None
            self.q("todo.db", "UPDATE todos SET status=?, completed_at=? WHERE id=?", status, done_at, r["id"])
        return self._todo_json(self._todo({"id": r["id"]}), full=True)

    def t_todo_delete(self, a):
        r = self._todo(a)
        kids = [x for x in self.q("todo.db", "SELECT * FROM todos WHERE parent_id = ?", r["id"])]
        if "todo-delete-no-cascade" in self.faults:
            kids = []
        for k in kids:
            self.q("todo.db", "DELETE FROM todos WHERE id=?", k["id"])
        self.q("todo.db", "DELETE FROM todos WHERE id=?", r["id"])
        return {"deleted": 1 + len(kids), "id": r["id"], "title": r["title"], "subtasks_deleted": len(kids)}

    def t_todo_list(self, a):
        rows = self.q("todo.db", "SELECT * FROM todos ORDER BY seq")
        status = self._check_enum("status", a["status"], self.STATUSES) if a.get("status") else None
        hits = []
        for r in rows:
            if status and r["status"] != status:
                continue
            if not status and r["status"] == "done" and not a.get("include_done"):
                continue
            if a.get("priority") and r["priority"] != a["priority"]:
                continue
            if a.get("tag") and a["tag"].lower() not in [t.lower() for t in json.loads(r["tags"])]:
                continue
            if a.get("parent_id") and r["parent_id"] != a["parent_id"]:
                continue
            if a.get("overdue_only") and not self._todo_overdue(r):
                continue
            for key, sign in (("due_after", 1), ("due_before", -1)):
                if a.get(key):
                    if not r["due"]:
                        break
                    bound, bound_all_day = self._parse_due(a[key])
                    mine, theirs = self._due_cmp_key(r["due"], bool(r["due_all_day"]), bound, bound_all_day)
                    if (mine < theirs) if sign == 1 else (mine > theirs):
                        break
            else:
                hits.append(r)
        hits.sort(key=lambda r: (self.PRIORITY_RANK[r["priority"]], r["due"] is None, self._due_cmp_key(r["due"], bool(r["due_all_day"]), r["due"], bool(r["due_all_day"]))[0] if r["due"] else 0))
        limit, offset = int(a.get("limit", 50)), int(a.get("offset", 0))
        page = hits[offset:offset + limit]
        out = {"todos": [self._compact_todo(r, rows) for r in page], "total": len(hits), "offset": offset, "limit": limit, "count": len(page),
               "has_more": offset + len(page) < len(hits)}
        if out["has_more"]:
            out["next_offset"] = offset + len(page)
        return out

    def t_todo_search(self, a):
        q = a.get("query")
        if not isinstance(q, str) or not q.strip():
            raise ToolError("query must not be blank." if q is not None else "Missing required argument: query")
        words = q.lower().split()
        rows = self.q("todo.db", "SELECT * FROM todos ORDER BY seq")
        hits = []
        for r in rows:
            if a.get("status") and r["status"] != a["status"]:
                continue
            hay = {"title": r["title"].lower(), "notes": r["notes"].lower(), "tags": " ".join(json.loads(r["tags"])).lower()}
            if all(any(w in v for v in hay.values()) for w in words):
                hits.append((r, [k for k, v in hay.items() if any(w in v for w in words)]))
        hits.sort(key=lambda x: "title" not in x[1])
        limit = int(a.get("limit", 20))
        res = []
        for r, where in hits[:limit]:
            item = self._compact_todo(r, rows)
            item["matched_in"] = where
            res.append(item)
        return {"query": q.strip(), "results": res, "total": len(hits), "count": len(res), "has_more": len(hits) > limit}

    def t_todo_summary(self, a):
        rows = self.q("todo.db", "SELECT * FROM todos")
        counts = {k: len([r for r in rows if r["status"] == k]) for k in self.STATUSES}
        if "todo-summary-wrong-count" in self.faults:
            counts["todo"] += 1
        week_start = self.today - timedelta(days=self.today.weekday())
        open_rows = [r for r in rows if r["status"] in ("todo", "doing") and r["due"]]
        day_of = lambda r: r["due"][:10] if r["due_all_day"] else datetime.fromisoformat(r["due"]).astimezone(TZ).date().isoformat()  # noqa: E731
        live = [r for r in open_rows if not self._todo_overdue(r)]
        return {"total": len(rows), "counts": counts, "overdue": len([r for r in open_rows if self._todo_overdue(r)]),
                "due_today": len([r for r in live if day_of(r) == self.today.isoformat()]),
                "due_this_week": len([r for r in live if self.today.isoformat() <= day_of(r) <= (week_start + timedelta(days=6)).isoformat()]),
                "today": self.today.isoformat(), "week_start": week_start.isoformat(), "week_end": (week_start + timedelta(days=6)).isoformat(), "time_zone": "Asia/Shanghai"}

    # ---- notes
    def _note(self, a):
        rows = self.q("notes.db", "SELECT * FROM notes WHERE id=?", a.get("id", ""))
        if not rows:
            raise ToolError("No note with id '%s'" % a.get("id"))
        return rows[0]

    def _note_json(self, r):
        return {"id": r["id"], "title": r["title"], "content": r["content"], "tags": json.loads(r["tags"]), "status": ["active", "archived", "trashed"][r["status"]]}

    def t_note_create(self, a):
        if not a.get("content"):
            raise ToolError("Missing required parameter 'content'")
        nid = "n-" + uuid.uuid4().hex[:6]
        title = a.get("title") or a["content"].splitlines()[0].lstrip("# ")
        self.q("notes.db", "INSERT INTO notes VALUES (?,?,?,?,?,0,0,0,0,NULL,1)", nid, title, a["content"], json.dumps(a.get("tags", []), ensure_ascii=False), "default")
        return self._note_json(self._note({"id": nid}))

    def t_note_get(self, a):
        return self._note_json(self._note(a))

    def t_note_append(self, a):
        r = self._note(a)
        if not a.get("text"):
            raise ToolError("Missing required parameter 'text'")
        self.q("notes.db", "UPDATE notes SET content=? WHERE id=?", r["content"] + a.get("separator", "\n") + a["text"], r["id"])
        return self._note_json(self._note(a))

    def t_note_update(self, a):
        r = self._note(a)
        tags = json.dumps(a["tags"], ensure_ascii=False) if "tags" in a else r["tags"]
        self.q("notes.db", "UPDATE notes SET tags=?, content=? WHERE id=?", tags, a.get("content", r["content"]), r["id"])
        return self._note_json(self._note(a))

    def t_note_search(self, a):
        if "query" not in a:
            raise ToolError("Missing required parameter 'query'")
        hits = [r for r in self.q("notes.db", "SELECT * FROM notes WHERE status != 2") if a["query"] in r["title"] + r["content"] + r["tags"]]
        return {"results": [{"id": r["id"], "title": r["title"]} for r in hits], "total": len(hits), "count": len(hits), "has_more": False}

    def t_note_list(self, a):
        rows = self.q("notes.db", "SELECT * FROM notes WHERE status = 0")
        return {"notes": [self._note_json(r) for r in rows], "total": len(rows), "count": len(rows), "has_more": False}

    def t_note_trash(self, a):
        r = self._note(a)
        self.q("notes.db", "UPDATE notes SET status=2 WHERE id=?", r["id"])
        return self._note_json(self._note(a))

    def t_note_restore(self, a):
        r = self._note(a)
        self.q("notes.db", "UPDATE notes SET status=0 WHERE id=?", r["id"])
        return self._note_json(self._note(a))

    def t_note_delete(self, a):
        r = self._note(a)
        if r["status"] != 2:
            raise ToolError("Only notes in the trash can be deleted permanently: use note_trash first")
        self.q("notes.db", "DELETE FROM notes WHERE id=?", r["id"])
        return {"deleted": True, "id": r["id"]}

    def t_tag_list(self, a):
        counts = {}
        for r in self.q("notes.db", "SELECT tags FROM notes WHERE status != 2"):
            for t in json.loads(r["tags"]):
                counts[t] = counts.get(t, 0) + 1
        return {"tags": [{"name": k, "count": v} for k, v in sorted(counts.items())], "total": len(counts)}


class FakeBridge:
    """Stands in for desktop_idle.Bridge: session/prompt plays a fake-model script against the phone."""

    def __init__(self, phone, live_plan=None):
        self.phone, self.stderr, self.closed = phone, [], False
        self.sessions = 0
        self.live_plan = live_plan      # {prompt text: callable(phone) -> [(tool name, args)]}: what a "model" would do
        self.prompts = []

    def request(self, method, params, timeout, on_note=None):
        if method == "initialize":
            return {"msg": {"result": {"protocolVersion": 1}}, "ms": 1, "notes": []}
        if method == "session/new":
            self.sessions += 1
            return {"msg": {"result": {"sessionId": "sess-%d" % self.sessions}}, "ms": 1, "notes": []}
        if method == "session/prompt":
            text = params["prompt"][0]["text"]
            self.prompts.append((params["sessionId"], text))
            return self._prompt(text, params["sessionId"])
        raise AssertionError("unexpected method " + method)

    def _prompt(self, text, sid="sess-1"):
        notes = []

        def upd(**u):
            notes.append({"method": "session/update", "params": {"sessionId": sid, "update": u}})

        try:
            script = json.loads(text)
        except ValueError:
            script = None
        results, rnd = [], 0
        if script is None:
            plan = (self.live_plan or {}).get(text, lambda phone: [])
            for name, args in plan(self.phone):
                cid = "c%d" % rnd
                upd(sessionUpdate="tool_call", toolCallId=cid, title=name, status="pending", rawInput=args)
                st, out = self.phone.call_tool(name, args, sid)
                upd(sessionUpdate="tool_call_update", toolCallId=cid, status=st, content=[{"type": "content", "content": {"type": "text", "text": out}}])
                rnd += 1
            upd(sessionUpdate="agent_message_chunk", content={"type": "text", "text": "好的，已处理。"})
            return {"msg": {"result": {"stopReason": "end_turn"}}, "ms": 5, "notes": notes}
        while True:
            plan = scripted_tools.plan_round(script, rnd, results)
            if plan["kind"] == "tool":
                cid = "c%d" % rnd
                upd(sessionUpdate="tool_call", toolCallId=cid, title=plan["name"], status="pending", rawInput=plan["arguments"])
                st, out = self.phone.call_tool(plan["name"], plan["arguments"], sid)
                upd(sessionUpdate="tool_call_update", toolCallId=cid, status="in_progress")
                upd(sessionUpdate="tool_call_update", toolCallId=cid, status=st, content=[{"type": "content", "content": {"type": "text", "text": out}}])
                results.append({"isError": st == "failed", "text": out})
                rnd += 1
                continue
            upd(sessionUpdate="agent_message_chunk", content={"type": "text", "text": plan["text"]})
            return {"msg": {"result": {"stopReason": "end_turn"}}, "ms": 5, "notes": notes}

    def close(self):
        self.closed = True
        return 0


class FakeEnv:
    """The RealEnv interface of sample_apps_e2e on top of a FakePhone."""

    def __init__(self, phone, live_plan=None):
        self.phone = phone
        self.adb = phone
        self.ext = L.ExtensionDebug(phone)
        self.consent = L.ConsentDebug(phone)
        self.gateway = L.GatewayDebug(phone)
        self.bridge = FakeBridge(phone, live_plan)
        self.apps = list(L.SAMPLES)
        self.events = []

    def prepare_device(self, opts):
        self.events.append("prepare")

    def open_desktop(self, opts):
        # the real scenario runs ensureTestModel: the loopback fake endpoint is the model source afterwards
        self.events.append("desktop")
        self.phone.desktop = True
        self.phone.model = {"modelUsable": True, "modelBaseUrl": "http://127.0.0.1:18787", "modelId": "fake-model"}

    def ensure_model(self, opts):
        self.events.append("model:" + ("tunnel" if opts.tunnel else "live" if opts.live else "fake"))
        if opts.live and not opts.tunnel and "model-reset-to-fake" not in self.phone.faults:
            self.phone.model = {"modelUsable": True, "modelBaseUrl": "https://api.minimaxi.com/anthropic", "modelId": "MiniMax-M3"}

    def open_session(self):
        self.events.append("session")
        return L.BridgeSession.open(self.bridge)

    def finish(self, notes):
        self.events.append("finish")
        self.consent.set_mode("off")
        for name in self.apps:
            self.ext.disable(L.SAMPLES[name].package)
