"""A fake phone for testing the acceptance driver without a device (A12).

It models what the driver touches, with the same shapes as the real thing:
  - the three apps' SQLite databases (same tables and columns as the real stores; the driver reads them through `run-as ... cat` = pull_private),
    changed by simple Python versions of the tools;
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
    def __init__(self, today=date(2026, 10, 7), faults=None):
        self.dir = tempfile.mkdtemp(prefix="fake-phone-")
        self.today = today
        self.faults = set(faults or [])
        self.enabled = {s.package: False for s in L.SAMPLES.values()}
        self.consent_mode = "off"
        self.consent_log = []       # ConsentDebugReceiver `recent` entries, oldest first (max 50)
        self.remembered = set()     # (session id, tool) answered with "allow for this session"
        self._req = 0
        self.desktop = False
        self.log = []          # every adb shell / run call, for assertions
        self.model_requests = []
        self._next_alarm = 1
        self._init_dbs()

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
    serial = "fake:0"

    def pull_private(self, package, relpath):
        pkg = next((s for s in L.SAMPLES.values() if s.package == package), None)
        name = os.path.basename(relpath)
        if pkg is None or not relpath.startswith("databases/") or name != pkg.db:
            return None
        if "no-db-" + pkg.name in self.faults:
            return None
        with open(self.db_path(name), "rb") as f:
            return f.read()

    # ------------------------------------------------------------------ debug receivers
    def _broadcast(self, cmd):
        parts = shlex.split(cmd)
        comp = parts[parts.index("-n") + 1]
        extras = {}
        i = 0
        while i < len(parts):
            if parts[i] in ("--es", "--ez", "--el"):
                v = parts[i + 2]
                extras[parts[i + 1]] = (v == "true") if parts[i] == "--ez" else (int(v) if parts[i] == "--el" else v)
                i += 3
            else:
                i += 1
        if comp.endswith("ExtensionDebugReceiver"):
            data = self._ext(extras)
        elif comp.endswith("ConsentDebugReceiver"):
            data = self._consent(extras)
        elif comp.endswith("DesktopGatewayDebugReceiver"):
            data = self._gateway(extras)
        else:
            return "Broadcast completed: result=0\n"
        code = 1 if data.get("ok") else 2
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
            return {"ok": True, "plugin": self._plugin_json(s)}
        if op == "catalog":
            return {"ok": True, "catalog": {"version": 1, "tools": [{"name": n, "risk": r.lower(), "enabled": True} for n, r in self.catalog().items()]}}
        if op == "wait_catalog":
            present = ex.get("name") in self.catalog()
            return {"ok": True, "met": present != bool(ex.get("absent", False))}
        return {"ok": False, "error": "unknown op: %s" % op}

    def catalog(self):
        out = {}
        for s in L.SAMPLES.values():
            if not self.enabled[s.package]:
                continue
            for t in s.tools + ([] if s.name != "alarm" else ["alarm_snooze"]):
                if "hide-" + t in self.faults:
                    continue
                n = scripted_tools.tool_name(s.name, s.name, t)
                out[n] = "HIGH" if t.endswith("_delete") else ("WRITE" if "risk-read" not in self.faults else "READ")
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
        return {"ok": True, "code": "123456"} if op == "pair" else {"ok": True, "enabled": self.desktop}

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
        return {"deleted": True, "alarm": self._alarm_json(r)}

    def t_alarm_next(self, a):
        rows = self.q("alarms.db", "SELECT * FROM alarms WHERE enabled=1 ORDER BY hour, minute")
        if not rows:
            return None
        j = self._alarm_json(rows[0])
        return {"alarm": j, "next_fire_at": j["next_fire_at"], "fires_in_minutes": 600}

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
        self.q("calendar.db", "UPDATE events SET title=?, location=? WHERE id=?", a.get("title", r["title"]), a.get("location", r["location"]), r["id"])
        return self._event_json(self._event(r["id"]))

    def t_event_delete(self, a):
        r = self._event(a.get("id", ""))
        self.q("calendar.db", "DELETE FROM events WHERE id=?", r["id"])
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
        self.bridge = FakeBridge(phone, live_plan)
        self.events = []

    def prepare_device(self, opts):
        self.events.append("prepare")

    def ensure_model(self, live):
        self.events.append("model:" + ("live" if live else "fake"))

    def open_session(self):
        self.events.append("session")
        return L.BridgeSession.open(self.bridge)

    def finish(self, notes):
        self.events.append("finish")
        self.consent.set_mode("off")
        for s in L.SAMPLES.values():
            self.ext.disable(s.package)
