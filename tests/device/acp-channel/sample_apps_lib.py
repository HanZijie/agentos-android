"""Sample-apps acceptance: building blocks (A12). The driver is sample_apps_e2e.py, the scenarios are sample_apps_scenarios.py.

Goal of the acceptance: the alarm, calendar and notes apps (plugins/samples/*) can be driven completely by AgentOS through MCP, with
evidence. Everything here goes through adb only (no taps): debug broadcast receivers of AgentOS, the acp-bridge for the conversation,
and a **direct read of each app's SQLite file** for the ground truth.

Why the database and not the apps' own debug entries (checked against the three app worktrees, A12):
  - alarm `DebugToolReceiver` and notes `DebugCallReceiver` call the *tool layer* and write one line to logcat (about 4 KB per line,
    longer lines are cut): they would verify the tools with the tools, and lists get truncated;
  - calendar `DebugReceiver` has only seed / clear / remind_test (no read command), `McpSelfTestReceiver` runs its own tool tour.
  The apps are debug builds, so `run-as <pkg> cat databases/<db>` gives the real file (copied to the computer, read with sqlite3):
  independent of the code under test, no size limit. The state readers are small classes with one method (`snapshot`), so an app
  dump entry can replace them later.

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
import sqlite3
import subprocess
import tempfile
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
    def __init__(self, name, package, db, tools):
        self.name, self.package, self.db, self.tools = name, package, db, tools

    @property
    def apk_name(self):
        return "%s-debug.apk" % self.name

    def apk_path(self, repo):
        return os.path.join(repo, "plugins", "samples", self.name, "build", "outputs", "apk", "debug", self.apk_name)


# docs/sample-apps.md section 4 (the minimum lists; the apps may offer more)
SAMPLES = {
    "alarm": Sample("alarm", "org.agentos.sample.alarm", "alarms.db",
                    ["alarm_list", "alarm_get", "alarm_create", "alarm_update", "alarm_set_enabled", "alarm_delete", "alarm_next", "alarm_dismiss"]),
    "calendar": Sample("calendar", "org.agentos.sample.calendar", "calendar.db",
                       ["calendar_list", "calendar_create", "calendar_delete", "event_list", "event_get", "event_create", "event_update",
                        "event_delete", "event_search", "agenda_today", "free_slots"]),
    "notes": Sample("notes", "org.agentos.sample.notes", "notes.db",
                    ["note_list", "note_get", "note_create", "note_update", "note_append", "note_search", "note_trash", "note_restore", "note_delete", "tag_list"]),
}


def model_name(sample, tool):
    return scripted_tools.tool_name(sample, sample, tool)


def source_line(sample):
    """The source line of a confirmation (ConsentText.sourceLine): plugin and server are both the sample's name."""
    return "来自插件「%s」 · 服务器「%s」" % (sample, sample)


def expected_risk(tool):
    """RiskPolicy for third-party MCP tools: WRITE by default, readOnlyHint never lowers it, destructiveHint (the *_delete tools) raises to HIGH."""
    return "HIGH" if tool.endswith("_delete") else "WRITE"


# ---------------------------------------------------------------------- adb helpers

def shq(value):
    return shlex.quote(str(value))


class RealAdb:
    """run.Adb plus the two things this driver needs: raw bytes (exec-out) and a convenience for the app's private files."""

    def __init__(self, base):
        self.base = base
        self.adb, self.serial = base.adb, base.serial

    def sh(self, cmd, **kw):
        return self.base.sh(cmd, **kw)

    def run(self, *args, **kw):
        return self.base.run(*args, **kw)

    def prop(self, name):
        return self.base.prop(name)

    def pull_private(self, package, relpath):
        """Bytes of a file in a debuggable app's private directory, or None when it cannot be read."""
        p = subprocess.run([self.adb, "-s", self.serial, "exec-out", "run-as", package, "cat", relpath], capture_output=True, timeout=120)
        return p.stdout if p.returncode == 0 and p.stdout else None


def _extra(k, v):
    """am extra with the type the receiver reads it as: bool -> --ez, int -> --el, everything else -> --es."""
    if isinstance(v, bool):
        return "--ez %s %s" % (k, "true" if v else "false")
    if isinstance(v, int):
        return "--el %s %d" % (k, v)
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
        data = self._op("wait_catalog", name=name, absent=True if absent else None, timeoutMs=int(timeout_ms))
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


# ---------------------------------------------------------------------- state readers (the app databases)

class StateReadError(DriverError):
    pass


DAY_CODES = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]


def _open_copy(adb, sample):
    """Copy databases/<db> (and -wal / -shm when there are any) to a temp dir and open the copy; the caller closes via [_Db]."""
    tmp = tempfile.mkdtemp(prefix="e2e-db-")
    main = adb.pull_private(sample.package, "databases/" + sample.db)
    if not main:
        raise StateReadError("cannot read databases/%s of %s (is the debug build installed, and has the app been started once?)" % (sample.db, sample.package))
    path = os.path.join(tmp, sample.db)
    with open(path, "wb") as f:
        f.write(main)
    for suffix in ("-wal", "-shm"):
        extra = adb.pull_private(sample.package, "databases/" + sample.db + suffix)
        if extra:
            with open(path + suffix, "wb") as f:
                f.write(extra)
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    return tmp, conn


class _Db:
    def __init__(self, adb, sample):
        self.adb, self.sample = adb, sample

    def __enter__(self):
        self.tmp, self.conn = _open_copy(self.adb, self.sample)
        return self.conn

    def __exit__(self, *exc):
        self.conn.close()
        for n in os.listdir(self.tmp):
            os.remove(os.path.join(self.tmp, n))
        os.rmdir(self.tmp)


def _ints(text):
    """The reminders column: '10,60' or '[10,60]' or ''."""
    t = (text or "").strip()
    if not t:
        return []
    if t.startswith("["):
        return [int(x) for x in json.loads(t)]
    return [int(x) for x in t.split(",") if x.strip()]


class AlarmState:
    sample = SAMPLES["alarm"]

    def __init__(self, adb):
        self.adb = adb

    def snapshot(self):
        with _Db(self.adb, self.sample) as c:
            rows = c.execute("SELECT id, hour, minute, label, days, enabled, snooze_minutes FROM alarms ORDER BY id").fetchall()
        return {"alarms": [{"id": str(r["id"]), "time": "%02d:%02d" % (r["hour"], r["minute"]), "label": r["label"] or "",
                            "days": [DAY_CODES[i] for i in range(7) if r["days"] & (1 << i)], "enabled": bool(r["enabled"]),
                            "snooze_minutes": r["snooze_minutes"]} for r in rows]}


class NotesState:
    sample = SAMPLES["notes"]
    STATUS = {0: "active", 1: "archived", 2: "trashed"}

    def __init__(self, adb):
        self.adb = adb

    def snapshot(self):
        with _Db(self.adb, self.sample) as c:
            rows = c.execute("SELECT id, title, content, tags, color, pinned, status FROM notes").fetchall()
        return {"notes": [{"id": r["id"], "title": r["title"], "content": r["content"], "tags": json.loads(r["tags"] or "[]"),
                           "color": r["color"], "pinned": bool(r["pinned"]), "status": self.STATUS.get(r["status"], str(r["status"]))} for r in rows]}


class CalendarState:
    sample = SAMPLES["calendar"]

    def __init__(self, adb):
        self.adb = adb

    def snapshot(self):
        with _Db(self.adb, self.sample) as c:
            cals = c.execute("SELECT id, name, visible, is_default FROM calendars").fetchall()
            evs = c.execute("SELECT id, calendar_id, title, description, location, all_day, start_utc, end_utc, tz, reminders, recurrence, recurrence_until FROM events").fetchall()
        return {"calendars": [{"id": r["id"], "name": r["name"], "visible": bool(r["visible"]), "is_default": bool(r["is_default"])} for r in cals],
                "events": [{"id": r["id"], "calendar_id": r["calendar_id"], "title": r["title"], "description": r["description"], "location": r["location"],
                            "all_day": bool(r["all_day"]), "start_utc": r["start_utc"], "end_utc": r["end_utc"], "tz": r["tz"],
                            "reminders": _ints(r["reminders"]), "recurrence": r["recurrence"], "recurrence_until": r["recurrence_until"]} for r in evs]}


STATE_READERS = {"alarm": AlarmState, "calendar": CalendarState, "notes": NotesState}


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

    def __init__(self, adb, ext, consent, session, today, tz_offset, run_id, log=print):
        self.adb, self.ext, self.consent, self.session = adb, ext, consent, session
        self.today, self.tz_offset, self.run_id, self.log = today, tz_offset, run_id, log
        self.vars = {}
        self.consent_seen = set()   # requestIds already in ConsentDebugReceiver `recent` before this run asked anything
        self.states = {name: cls(adb) for name, cls in STATE_READERS.items()}

    def state(self, sample):
        return self.states[sample].snapshot()

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

    def epoch_ms(self, d, hhmm):
        h, m = (int(x) for x in hhmm.split(":"))
        sign = -1 if self.tz_offset.startswith("-") else 1
        oh, om = (int(x) for x in self.tz_offset[1:].split(":"))
        tz = timezone(sign * timedelta(hours=oh, minutes=om))
        return int(datetime(d.year, d.month, d.day, h, m, tzinfo=tz).timestamp() * 1000)

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
