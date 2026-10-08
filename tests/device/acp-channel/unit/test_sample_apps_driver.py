"""The acceptance driver against a fake phone (no device): the whole flow, the verdicts, and that failures are described by step."""
import json
import os
import sys
import time
import unittest
from datetime import date, datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)
import sample_apps_e2e as E  # noqa: E402
import sample_apps_lib as L  # noqa: E402
import sample_apps_scenarios as S  # noqa: E402
from fake_world import FakeEnv, FakePhone  # noqa: E402


S.WAIT_SCALE = 0.02
L.SLEEP_SCALE = 0.02


def run(phone=None, **opt):
    phone = phone or FakePhone()
    env = FakeEnv(phone)
    report = E.run_acceptance(env, E.Options(**opt), log=lambda m: None)
    return phone, env, report


def failed(report):
    return {s["id"] for s in report["steps"] if not s["ok"]}


class ScriptedRunTest(unittest.TestCase):
    def test_a_healthy_phone_passes_every_step(self):
        phone, env, report = run()
        self.assertEqual([], report["summary"]["failures"])
        self.assertTrue(report["ok"], report["summary"])
        self.assertEqual(report["summary"]["steps"], report["summary"]["passed"])
        ids = [s["id"] for s in report["steps"]]
        for expect in ("setup.discover", "setup.enable", "setup.catalog", "setup.consent", "alarm.create", "alarm.delete", "calendar.create_event",
                       "calendar.free_slots", "calendar.delete_calendar", "notes.trash_again", "notes.delete", "alarm.denied", "notes.plugin_off", "calendar.plugin_on"):
            self.assertIn(expect, ids)
        self.assertGreaterEqual(len(ids), 45)
        self.assertEqual(["prepare", "desktop", "model:fake", "session", "finish"], env.events, "desktop access first, the model after it")

    def test_every_step_has_checks_and_every_tool_step_has_its_turn(self):
        _, _, report = run()
        for s in report["steps"]:
            self.assertTrue(s["checks"], s["id"])
            if s["sample"]:
                self.assertIsNotNone(s["turn"], s["id"])
                self.assertEqual("end_turn", s["turn"]["stopReason"], s["id"])

    def test_the_run_leaves_the_apps_as_it_found_them_and_the_phone_off(self):
        phone, env, report = run()
        self.assertEqual([], phone.q("alarms.db", "SELECT * FROM alarms"))
        self.assertEqual(["cal-default"], [r["id"] for r in phone.q("calendar.db", "SELECT id FROM calendars")])
        self.assertEqual([], phone.q("calendar.db", "SELECT * FROM events"))
        self.assertEqual([], phone.q("notes.db", "SELECT * FROM notes"), "the final reset leaves the notes app empty")
        self.assertEqual({}, phone.orphans)
        self.assertEqual("off", phone.consent_mode)
        self.assertFalse(any(phone.enabled.values()), "plugins are switched off again at the end")

    def test_failure_paths_check_that_nothing_was_written(self):
        phone, env, report = run()
        for sid in ("alarm.err.missing_time", "alarm.err.bad_time", "alarm.err.unknown_id", "calendar.err.bad_time", "notes.err.delete_active", "notes.err.unknown_id"):
            step = next(s for s in report["steps"] if s["id"] == sid)
            self.assertTrue(step["ok"], sid)
            self.assertTrue(any("unchanged" in c["name"] or "stays" in c["name"] or "active" in c["name"] for c in step["checks"]) or step["id"].endswith("unknown_id"), sid)
        denied = next(s for s in report["steps"] if s["id"] == "alarm.denied")
        self.assertIn("tool_denied", denied["turn"]["tools"][0]["result"])
        high = [e for e in phone.consent_log if e["risk"] == "HIGH"]
        self.assertTrue(high)
        self.assertEqual({"ALLOW_ONCE"}, {e["answeredWith"] for e in high}, "HIGH requests are allowed once, never remembered")

    def test_the_catalog_check_reports_risk_per_tool(self):
        _, _, report = run()
        cat = next(s for s in report["steps"] if s["id"] == "setup.catalog")
        names = {c["name"]: c for c in cat["checks"]}
        delete = [c for n, c in names.items() if "alarm_delete" in n][0]
        self.assertEqual("HIGH", delete["expected"])
        self.assertEqual("HIGH", delete["actual"])
        listing = [c for n, c in names.items() if "note_list is offered" in n][0]
        self.assertEqual("WRITE", listing["expected"], "no annotation lowers a third-party tool below WRITE")
        self.assertEqual(1 + 44, len(cat["checks"]), "one check that every documented tool was offered (44 tools of the five apps), then one risk check per tool")


class ConsentBehaviourTest(unittest.TestCase):
    """The four things the integration found on the Pixel 8, as driver expectations."""

    def step(self, report, id):
        return next(s for s in report["steps"] if s["id"] == id)

    def test_refusal_paths_run_in_a_new_session(self):
        phone, env, report = run()
        self.assertTrue(report["ok"], report["summary"]["failures"])
        sessions = {sid for sid, text in env.bridge.prompts}
        self.assertGreater(len(sessions), 1, "the driver opened extra sessions")
        denied = [(sid, json.loads(text)) for sid, text in env.bridge.prompts if "alarm_create" in text and '"06:00"' in text]
        self.assertEqual(1, len(denied))
        first = env.bridge.prompts[0][0]
        self.assertNotEqual(first, denied[0][0], "alarm_create was allowed-for-session in the first session, so the refusal needs a new one")

    def test_without_a_new_session_a_remembered_tool_would_ignore_deny(self):
        # the phone model itself: this is what the driver is protecting itself from
        phone = FakePhone()
        phone.enabled = {s.package: True for s in L.SAMPLES.values()}
        phone.consent_mode = "allow"
        self.assertEqual("completed", phone.call_tool("mcp__alarm__alarm__alarm_create", {"time": "06:00"}, "s1")[0])
        phone.consent_mode = "deny"
        self.assertEqual("completed", phone.call_tool("mcp__alarm__alarm__alarm_create", {"time": "06:01"}, "s1")[0], "same session: remembered, not asked")
        self.assertEqual("failed", phone.call_tool("mcp__alarm__alarm__alarm_create", {"time": "06:02"}, "s2")[0], "new session: asked, declined")

    def test_high_tools_offer_once_or_deny_and_are_allowed_once(self):
        phone, env, report = run()
        deletes = [e for e in phone.consent_log if e["tool"].endswith("_delete") and e["answeredWith"]]
        self.assertTrue(deletes)
        for e in deletes:
            self.assertEqual(["ALLOW_ONCE", "DENY"], e["options"])
        self.assertEqual({"ALLOW_ONCE"}, {e["answeredWith"] for e in deletes if e["end"] == "ANSWERED" and e["options"] == ["ALLOW_ONCE", "DENY"] and e["answeredWith"] != "DENY"})

    def test_queries_of_third_party_apps_are_confirmed_too(self):
        phone, env, report = run()
        asked = {e["tool"] for e in phone.consent_log}
        self.assertIn("mcp__alarm__alarm__alarm_list", asked)
        self.assertIn("mcp__notes__notes__tag_list", asked)
        listing = next(e for e in phone.consent_log if e["tool"].endswith("alarm_list"))
        self.assertEqual("WRITE", listing["risk"])

    def test_the_audit_reads_source_risk_and_options_of_every_recorded_request(self):
        phone, env, report = run()
        for app in ("alarm", "calendar", "notes"):
            audit = next(s for s in report["steps"] if s["id"] == "audit." + app)
            self.assertTrue(audit["ok"], (app, audit["checks"]))
            self.assertEqual(5, len(audit["checks"]))
        e = next(e for e in phone.consent_log if e["tool"].endswith("alarm_create"))
        self.assertEqual("来自插件「alarm」 · 服务器「alarm」", e["source"])

    def test_the_audit_catches_a_wrong_source_line_a_lowered_risk_and_an_always_allow_answer(self):
        good = {"tool": "mcp__alarm__alarm__alarm_create", "risk": "WRITE", "source": L.source_line("alarm"), "options": ["ALLOW_ONCE", "ALLOW_FOR_SESSION", "DENY"],
                "answeredWith": "ALLOW_FOR_SESSION", "end": "ANSWERED"}
        self.assertTrue(all(c.ok for c in L.audit_consent([good], "alarm")))
        for change, name in (({"source": "来自插件「evil」 · 服务器「alarm」"}, "source"), ({"risk": "READ"}, "risk"),
                             ({"options": ["ALLOW_ONCE"]}, "offers"), ({"answeredWith": "ALWAYS_ALLOW"}, "answered"), ({"end": "TIMED_OUT"}, "answered")):
            checks = L.audit_consent([dict(good, **change)], "alarm")
            self.assertTrue(any(not c.ok and name in c.name for c in checks), (name, [c for c in checks if not c.ok]))
        high = dict(good, tool="mcp__alarm__alarm__alarm_delete", risk="HIGH", options=["ALLOW_ONCE", "ALLOW_FOR_SESSION", "DENY"], answeredWith="ALLOW_ONCE")
        self.assertTrue(any(not c.ok and "HIGH" in c.name for c in L.audit_consent([high], "alarm")), "HIGH must not offer ALLOW_FOR_SESSION")

    def test_a_declined_step_must_show_a_recorded_deny(self):
        phone, env, report = run()
        step = self.step(report, "alarm.denied")
        recorded = [c for c in step["checks"] if "recorded and declined" in c["name"]]
        self.assertEqual(1, len(recorded))
        self.assertTrue(recorded[0]["ok"])
        self.assertEqual("DENY", recorded[0]["actual"]["answeredWith"])

    def test_the_second_set_enabled_call_goes_through_unasked_in_the_same_session(self):
        phone, env, report = run()
        step = self.step(report, "alarm.switch_on")
        check = [c for c in step["checks"] if "asked once" in c["name"]]
        self.assertEqual(1, len(check))
        self.assertTrue(check[0]["ok"], check[0])

    def test_recent_of_an_earlier_run_does_not_count(self):
        phone = FakePhone()
        phone.consent_log.append({"requestId": "old_1", "tool": "mcp__alarm__alarm__alarm_create", "risk": "WRITE", "source": "x", "options": [], "answeredWith": None, "end": "TIMED_OUT"})
        _, _, report = run(phone)
        self.assertTrue(report["ok"], report["summary"]["failures"])


class FailureReportingTest(unittest.TestCase):
    def test_an_app_that_does_not_write_is_caught_at_its_step_with_expected_and_actual(self):
        phone, env, report = run(FakePhone(faults={"alarm-forgets-write"}))
        self.assertFalse(report["ok"])
        self.assertIn("alarm.create", failed(report))
        line = next(l for l in report["summary"]["failures"] if l.startswith("FAIL alarm.create"))
        self.assertIn("expected", line)
        self.assertIn("actual", line)
        step = next(s for s in report["steps"] if s["id"] == "alarm.create")
        bad = [c for c in step["checks"] if not c["ok"]]
        self.assertTrue(bad)
        # the later alarm steps depend on the id and fail with a clear reason instead of crashing the run
        self.assertTrue(any(s["id"].startswith("calendar.") and s["ok"] for s in report["steps"]), "the other apps are still checked")

    def test_a_missing_tool_in_the_catalog_is_named(self):
        phone, env, report = run(FakePhone(faults={"hide-event_delete"}))
        self.assertFalse(report["ok"])
        self.assertTrue(any("event_delete" in l for l in report["summary"]["failures"]), report["summary"]["failures"])

    def test_a_catalog_that_lowers_the_risk_is_reported(self):
        phone, env, report = run(FakePhone(faults={"risk-read"}))
        self.assertIn("setup.catalog", failed(report))

    def test_a_failing_app_dump_stops_the_run_at_the_reset_check(self):
        phone, env, report = run(FakePhone(faults={"dump-fails-notes"}))
        self.assertFalse(report["ok"])
        self.assertIn("setup.reset", failed(report))
        reset = next(s for s in report["steps"] if s["id"] == "setup.reset")
        self.assertIn("notes dump failed", reset["error"])
        self.assertTrue(all(not s["sample"] for s in report["steps"]), "no app step ran on an unreadable app")

    def test_a_failing_app_dump_is_a_driver_error_not_a_pass(self):
        phone, env, report = run(FakePhone(faults={"dump-fails-notes"}), reset=False)
        self.assertFalse(report["ok"])
        notes_steps = [s for s in report["steps"] if s["sample"] == "notes"]
        self.assertTrue(notes_steps)
        self.assertTrue(all(not s["ok"] for s in notes_steps))
        self.assertTrue(any("notes dump failed" in (s["error"] or "") for s in notes_steps))
        self.assertTrue(all("notes dump failed" in (s["error"] or "") or "needs '" in (s["error"] or "") for s in notes_steps))
        alarm = [s for s in report["steps"] if s["sample"] == "alarm"]
        self.assertTrue(all(s["ok"] for s in alarm), "an unreadable notes app does not spoil the alarm results")

    def test_leftovers_of_a_failed_run_are_cleaned_up(self):
        phone = FakePhone()
        env = FakeEnv(phone)
        mark_run = "abc123"
        # a "previous run" left a calendar and a note
        phone.t_calendar_create({"name": "e2e-%s-cal" % mark_run})
        phone.t_note_create({"content": "e2e-%s stale" % mark_run})
        phone.enabled = {s.package: True for s in L.SAMPLES.values()}
        phone.consent_mode = "allow"
        ctx = L.Context(phone, env.ext, env.consent, L.BridgeSession.open(env.bridge), date(2026, 10, 7), "+08:00", mark_run)
        found = S.leftovers(ctx)
        self.assertEqual({"calendar", "notes"}, set(found))
        removed = S.cleanup(ctx)
        self.assertEqual({"calendar": ["calendar_delete"], "notes": ["note_trash", "note_delete"]}, removed)
        self.assertEqual({}, S.leftovers(ctx))

    def test_keep_data_skips_the_cleanup(self):
        phone = FakePhone(faults={"alarm-forgets-write"})
        env = FakeEnv(phone)
        report = E.run_acceptance(env, E.Options(keep_data=True), log=lambda m: None)
        self.assertIsNone(report["leftovers"])

    def test_setup_failure_stops_before_any_app_step(self):
        phone = FakePhone(faults={"hide-alarm_get"})
        phone.enabled  # plugins start off
        env = FakeEnv(phone)
        # a plugin that is already on at the start fails the "off by default" check; the run stops after setup
        phone.enabled = {s.package: True for s in L.SAMPLES.values()}
        report = E.run_acceptance(env, E.Options(), log=lambda m: None)
        self.assertIn("setup.discover", failed(report))
        self.assertTrue(all(not s["sample"] for s in report["steps"]), "no app step ran")
        self.assertTrue(any("setup failed" in n for n in report["notes"]))
        self.assertTrue(E.run_acceptance(FakeEnv(phone), E.Options(allow_enabled=True), log=lambda m: None)["steps"][0]["ok"], "--allow-enabled skips that check")

    def test_only_selects_apps(self):
        _, _, report = run(only={"notes"})
        app_steps = {s["sample"] for s in report["steps"] if s["sample"]}
        self.assertEqual({"notes"}, app_steps)
        self.assertTrue(report["ok"])


class StateAndResetTest(unittest.TestCase):
    """The apps' own dump / reset are the source of truth (a real phone has no sqlite3); alarms are checked against AlarmManager."""

    def test_alarm_registration_is_checked_at_every_step(self):
        _, _, report = run()
        names = lambda sid: [c["name"] for c in next(s for s in report["steps"] if s["id"] == sid)["checks"]]  # noqa: E731
        self.assertTrue(any("registered with the system AlarmManager for 07:15" in n for n in names("alarm.create")))
        self.assertTrue(any("for 08:30" in n for n in names("alarm.update")))
        self.assertTrue(any("no longer registered" in n for n in names("alarm.switch_off")))
        self.assertTrue(any("registered" in n and "08:30" in n for n in names("alarm.switch_on")))
        self.assertTrue(any("no longer registered" in n for n in names("alarm.delete")))

    def test_an_alarm_that_is_stored_but_not_set_in_the_system_fails_the_create_step(self):
        _, _, report = run(FakePhone(faults={"alarm-not-registered"}))
        self.assertIn("alarm.create", failed(report))
        line = next(l for l in report["summary"]["failures"] if l.startswith("FAIL alarm.create"))
        self.assertIn("AlarmManager", line)

    def test_a_deleted_alarm_that_stays_registered_fails_the_delete_step(self):
        _, _, report = run(FakePhone(faults={"delete-keeps-registration"}))
        self.assertEqual({"alarm.delete"}, {i for i in failed(report) if i.startswith("alarm.")})

    def test_a_switched_off_alarm_that_stays_registered_fails_the_switch_off_step(self):
        _, _, report = run(FakePhone(faults={"switch-off-keeps-registration"}))
        self.assertIn("alarm.switch_off", failed(report))

    def test_every_dump_is_read_page_by_page(self):
        old = L.DUMP_PAGE
        L.DUMP_PAGE = 1
        phone = FakePhone()
        for i in range(3):
            phone.t_note_create({"content": "mine %d" % i})   # already there (--no-reset): every notes dump needs several pages
            phone.t_alarm_create({"time": "05:0%d" % i, "label": "mine"})
        try:
            phone, env, report = run(phone, reset=False)
        finally:
            L.DUMP_PAGE = old
        self.assertTrue(report["ok"], report["summary"]["failures"])
        dumps = [c for c in phone.log if "--es cmd dump" in c]
        self.assertTrue(any("--ei offset 2 " in c for c in dumps), "later pages were requested")
        self.assertTrue(all("--ei limit 1" in c for c in dumps))

    def test_paging_reads_all_rows(self):
        phone = FakePhone()
        for i in range(7):
            phone.t_note_create({"content": "n%d" % i})
        old = L.DUMP_PAGE
        L.DUMP_PAGE = 3
        try:
            snap = L.NotesState(phone).snapshot()
        finally:
            L.DUMP_PAGE = old
        self.assertEqual(["n%d" % i for i in range(7)], [n["content"] for n in snap["notes"]])

    def test_a_dump_that_does_not_advance_is_an_error_not_an_endless_loop(self):
        phone = FakePhone(faults={"dump-stuck"})
        for i in range(4):
            phone.t_note_create({"content": "n%d" % i})
        old = L.DUMP_PAGE
        L.DUMP_PAGE = 2
        try:
            with self.assertRaises(L.StateReadError) as cm:
                L.NotesState(phone).snapshot()
        finally:
            L.DUMP_PAGE = old
        self.assertIn("does not advance", str(cm.exception))

    def test_note_status_comes_from_the_archived_and_trashed_flags(self):
        phone = FakePhone()
        a = phone.t_note_create({"content": "a"})
        b = phone.t_note_create({"content": "b"})
        phone.t_note_trash({"id": b["id"]})
        phone.q("notes.db", "UPDATE notes SET status = 1 WHERE id = ?", a["id"])
        self.assertEqual({"a": "archived", "b": "trashed"}, {n["content"]: n["status"] for n in L.NotesState(phone).snapshot()["notes"]})

    def test_int_extras_are_sent_as_int_and_long_extras_as_long(self):
        self.assertEqual("--ei offset 5", L._extra("offset", 5))
        self.assertEqual("--el timeoutMs 15000", L._extra("timeoutMs", L.LongExtra(15000)))
        self.assertEqual("--ez absent true", L._extra("absent", True))
        self.assertEqual("--es cmd dump", L._extra("cmd", "dump"))

    def test_the_run_starts_and_ends_with_a_reset_of_the_three_apps(self):
        phone = FakePhone()
        phone.t_alarm_create({"time": "05:00", "label": "mine"})
        phone.t_note_create({"content": "mine"})
        phone.t_calendar_create({"name": "mine"})
        phone.t_event_create({"title": "mine", "start": "2026-10-09T10:00:00+08:00"})
        _, _, report = run(phone)
        self.assertTrue(report["ok"], report["summary"]["failures"])
        ids = [s["id"] for s in report["steps"]]
        self.assertEqual("setup.reset", ids[1])
        self.assertEqual("teardown.reset", ids[-1])
        self.assertEqual([], phone.q("alarms.db", "SELECT * FROM alarms"))
        self.assertEqual([], phone.q("notes.db", "SELECT * FROM notes"))
        self.assertEqual([], phone.q("calendar.db", "SELECT * FROM events"))
        self.assertEqual(["cal-default"], [r["id"] for r in phone.q("calendar.db", "SELECT id FROM calendars")])

    def test_no_reset_keeps_the_data_that_was_there_and_removes_only_this_runs(self):
        phone = FakePhone()
        phone.t_alarm_create({"time": "05:00", "label": "mine"})
        n = phone.t_note_create({"content": "mine"})
        _, _, report = run(phone, reset=False)
        self.assertTrue(report["ok"], report["summary"]["failures"])
        ids = [s["id"] for s in report["steps"]]
        self.assertNotIn("setup.reset", ids)
        self.assertNotIn("teardown.reset", ids)
        self.assertEqual(["mine"], [r["label"] for r in phone.q("alarms.db", "SELECT label FROM alarms")])
        self.assertEqual(["mine"], [r["content"] for r in phone.q("notes.db", "SELECT content FROM notes")])

    def test_keep_data_removes_nothing(self):
        phone = FakePhone(faults={"alarm-forgets-write"})
        report = E.run_acceptance(FakeEnv(phone), E.Options(keep_data=True), log=lambda m: None)
        self.assertNotIn("teardown.reset", [s["id"] for s in report["steps"]])
        self.assertIsNone(report["leftovers"])

    def test_a_reset_that_does_not_clear_the_system_alarms_fails_the_setup(self):
        _, _, report = run(FakePhone(faults={"reset-leaves-registered"}, ) if False else _alarm_phone_with_data(faults={"reset-leaves-registered"}))
        self.assertIn("setup.reset", failed(report))
        self.assertTrue(any("alarm: reset" in l for l in report["summary"]["failures"]), report["summary"]["failures"])

    def test_a_calendar_that_ignores_clear_fails_the_setup(self):
        phone = FakePhone(faults={"calendar-reset-noop"})
        phone.t_event_create({"title": "x", "start": "2026-10-09T10:00:00+08:00"})
        _, _, report = run(phone)
        self.assertIn("setup.reset", failed(report))

    def test_alarm_and_notes_are_never_read_by_copying_their_databases(self):
        # FakePhone.pull_private raises AssertionError for them: a whole healthy run proves the driver does not do it
        phone, env, report = run()
        self.assertTrue(report["ok"], report["summary"]["failures"])
        self.assertTrue(any("--es cmd dump" in c and "DebugToolReceiver" in c for c in phone.log))
        self.assertTrue(any("--es cmd dump" in c and "DebugCallReceiver" in c for c in phone.log))


def _alarm_phone_with_data(faults):
    phone = FakePhone(faults=faults)
    phone.t_alarm_create({"time": "05:00", "label": "mine"})
    return phone


class TodoSmsStateTest(unittest.TestCase):
    """The state readers of the todo and sms apps: dump shapes of the two apps (their READMEs), stable fields, paging, reset."""

    def add_todo(self, phone, title, **kw):
        row = dict(id=title[:8].ljust(8, "0"), notes="", status="todo", priority="medium", due=None, due_all_day=0, tags="[]", parent_id=None, completed_at=None)
        row.update(kw, title=title)
        phone.q("todo.db", "INSERT INTO todos (id, title, notes, status, priority, due, due_all_day, tags, parent_id, completed_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                row["id"], row["title"], row["notes"], row["status"], row["priority"], row["due"], row["due_all_day"], row["tags"], row["parent_id"], row["completed_at"])

    def test_the_documented_tools_are_forty_four_with_the_two_new_apps(self):
        self.assertEqual(["alarm", "calendar", "notes", "todo", "sms"], list(L.SAMPLES))
        self.assertEqual(44, sum(len(s.tools) for s in L.SAMPLES.values()))
        self.assertIn("alarm_system_next", L.SAMPLES["alarm"].tools)
        self.assertEqual("org.agentos.sample.todo", L.SAMPLES["todo"].package)
        self.assertEqual("org.agentos.sample.sms", L.SAMPLES["sms"].package)
        self.assertEqual("org.agentos.sample.todo/.debug.DebugToolReceiver", L.SAMPLES["todo"].component)
        self.assertEqual("org.agentos.sample.sms/.debug.DebugToolReceiver", L.SAMPLES["sms"].component)
        self.assertEqual("todo-debug.apk", L.SAMPLES["todo"].apk_name)
        self.assertEqual(["alarm", "calendar", "notes", "todo", "sms"], list(L.STATE_READERS))

    def test_risk_follows_risk_policy_for_third_party_tools(self):
        # readOnlyHint never lowers a third-party tool below WRITE (also sms_thread_list); destructiveHint (the *_delete tools and sms_send) raises it to HIGH
        for tool in ("todo_list", "todo_summary", "todo_create", "todo_set_status", "sms_thread_list", "sms_message_list", "sms_search", "sms_send_status",
                     "sms_compose", "alarm_system_next"):
            self.assertEqual("WRITE", L.expected_risk(tool), tool)
        for tool in ("todo_delete", "sms_send", "alarm_delete", "event_delete", "note_delete"):
            self.assertEqual("HIGH", L.expected_risk(tool), tool)

    def test_the_todo_snapshot_has_the_driver_shape_without_clocks(self):
        phone = FakePhone()
        self.add_todo(phone, "PRD one", priority="high", due="2026-10-14", due_all_day=1, tags='["e2e"]')
        self.add_todo(phone, "timed", due="2026-10-15T17:00:00+08:00", status="done", completed_at="2026-10-07T12:00:00+08:00")
        self.add_todo(phone, "child", parent_id="PRD one0")
        snap = L.TodoState(phone).snapshot()
        a, b, c = snap["todos"]
        self.assertEqual(("PRD one0", "PRD one", "todo", "high", "2026-10-14", True, None, ["e2e"], None), (a["id"], a["title"], a["status"], a["priority"], a["due"], a["due_all_day"], a["due_ms"], a["tags"], a["parent_id"]))
        self.assertEqual(L.iso_ms("2026-10-15T09:00:00Z"), b["due_ms"], "a timed due also comes as an instant")
        self.assertEqual("2026-10-07T12:00:00+08:00", b["completed_at"])
        self.assertEqual("PRD one0", c["parent_id"])
        for row in snap["todos"]:
            for gone in ("created_at", "updated_at", "overdue"):
                self.assertNotIn(gone, row)
        self.assertEqual({"todo": 2, "doing": 0, "done": 1, "shelved": 0}, snap["counts"])

    def test_the_todo_dump_is_read_page_by_page_and_reset_empties_the_app(self):
        phone = FakePhone()
        for i in range(5):
            self.add_todo(phone, "t%d" % i)
        old = L.DUMP_PAGE
        L.DUMP_PAGE = 2
        try:
            snap = L.TodoState(phone).snapshot()
        finally:
            L.DUMP_PAGE = old
        self.assertEqual(["t%d" % i for i in range(5)], [t["title"] for t in snap["todos"]])
        self.assertEqual(3, len([c for c in phone.log if "org.agentos.sample.todo/.debug.DebugToolReceiver" in c and "--es cmd dump" in c]))
        ok, data = L.TodoState(phone).reset()
        self.assertTrue(ok, data)
        self.assertEqual({"cleared": 5, "remaining": 0, "remaining_in_db": 0}, data)
        self.assertEqual([], L.TodoState(phone).snapshot()["todos"])
        phone2 = FakePhone(faults={"todo-reset-noop"})
        self.add_todo(phone2, "x")
        bad, data = L.TodoState(phone2).reset()
        self.assertFalse(bad)
        self.assertEqual(1, data["remaining_in_db"])

    def test_a_failing_todo_dump_is_an_error_not_an_empty_list(self):
        with self.assertRaises(L.StateReadError) as cm:
            L.TodoState(FakePhone(faults={"dump-fails-todo"})).snapshot()
        self.assertIn("todo dump failed", str(cm.exception))

    def test_the_sms_snapshot_has_mode_permissions_settings_outbox_and_drafts(self):
        phone = FakePhone()
        phone.sms["outbox"] = [{"id": "2", "to": "5616", "text": "b", "parts": 1, "state": "delivered", "sent_parts": 1, "delivered_parts": 1, "error": None},
                               {"id": "1", "to": "5616", "text": "a", "parts": 1, "state": "queued", "sent_parts": 0, "delivered_parts": 0, "error": None}]
        phone.sms["drafts"] = [{"id": "1", "to": "5616", "text": "draft"}]
        snap = L.SmsState(phone).snapshot()
        self.assertEqual("full", snap["mode"])
        self.assertEqual({"read_sms": True, "send_sms": True}, snap["permissions"])
        self.assertEqual({"mask_codes": True, "allow_short_numbers": False, "rate_limit": 5}, snap["settings"])
        self.assertEqual(["2", "1"], [o["id"] for o in snap["outbox"]], "newest first, as the app dumps it")
        self.assertEqual("delivered", snap["outbox"][0]["state"])
        self.assertEqual([{"id": "1", "to": "5616", "text": "draft"}], snap["drafts"])
        for row in snap["outbox"]:
            self.assertNotIn("created_at", row)
        phone.sms["granted"] = {p: False for p in L.SMS_PERMISSIONS}
        self.assertEqual("compose_only", L.SmsState(phone).snapshot()["mode"])
        phone.sms["granted"][L.SMS_PERMISSIONS[0]] = True
        self.assertEqual("partial", L.SmsState(phone).snapshot()["mode"])

    def test_the_sms_reset_clears_only_the_outbox_and_the_drafts_and_never_the_settings(self):
        phone = FakePhone()
        phone.sms["outbox"] = [{"id": "1", "to": "5616", "text": "a", "parts": 1, "state": "sent", "sent_parts": 1, "delivered_parts": 0, "error": None}]
        phone.sms["drafts"] = [{"id": "1", "to": "5616", "text": "d"}]
        phone.sms["inbox"] = [{"address": "+12025550143", "body": "hello"}]
        L.SmsState(phone).set("allow_short_numbers", True)
        ok, data = L.SmsState(phone).reset()
        self.assertTrue(ok, data)
        self.assertEqual({"cleared": 1, "outbox_remaining": 0, "drafts_cleared": 1}, data)
        self.assertEqual(1, len(phone.sms["inbox"]), "the system SMS store is not touched")
        self.assertTrue(phone.sms["settings"]["allow_short_numbers"])
        phone2 = FakePhone(faults={"sms-reset-noop"})
        phone2.sms["outbox"] = [{"id": "1", "to": "5616", "text": "a", "parts": 1, "state": "sent", "sent_parts": 1, "delivered_parts": 0, "error": None}]
        ok, data = L.SmsState(phone2).reset()
        self.assertFalse(ok)
        self.assertEqual(1, data["outbox_remaining"])

    def test_settings_are_changed_with_the_debug_set_command_and_typed_values(self):
        phone = FakePhone()
        state = L.SmsState(phone)
        self.assertEqual({"mask_codes": True, "allow_short_numbers": True, "rate_limit": 5}, state.set("allow_short_numbers", True))
        self.assertEqual({"mask_codes": False, "allow_short_numbers": True, "rate_limit": 5}, state.set("mask_codes", False))
        self.assertEqual(30, state.set("rate_limit", 30)["rate_limit"])
        self.assertTrue(any("--es cmd set --es key mask_codes --es value false" in c for c in phone.log), [c for c in phone.log if "cmd set" in c])
        with self.assertRaises(L.StateReadError):
            state.set("volume", 3)

    def test_the_sms_dump_is_paged_through_next_offset(self):
        phone = FakePhone()
        phone.sms["outbox"] = [{"id": str(i), "to": "5616", "text": "m%d" % i, "parts": 1, "state": "sent", "sent_parts": 1, "delivered_parts": 0, "error": None} for i in range(5, 0, -1)]
        old = L.DUMP_PAGE
        L.DUMP_PAGE = 2
        try:
            snap = L.SmsState(phone).snapshot()
        finally:
            L.DUMP_PAGE = old
        self.assertEqual(["5", "4", "3", "2", "1"], [o["id"] for o in snap["outbox"]])

    def test_devices_lists_the_serial_and_the_other_emulators(self):
        self.assertEqual(["emulator-5554"], FakePhone().devices())
        self.assertEqual(["emulator-5554", "emulator-5556"], FakePhone(other_devices=["emulator-5556"]).devices())


class CalendarDumpTest(unittest.TestCase):
    """The calendar is read through its own dump / reset like the other two (no copying of its database), and its single armed reminder alarm is checked."""

    def step(self, report, id):
        return next(s for s in report["steps"] if s["id"] == id)

    def test_the_calendar_is_read_through_its_dump_and_the_database_is_never_copied(self):
        phone, env, report = run()
        self.assertTrue(report["ok"], report["summary"]["failures"])
        self.assertFalse(hasattr(L.RealAdb, "pull_private"), "the driver has no way to read an app's private files any more")
        self.assertFalse(hasattr(phone, "pull_private"))
        self.assertFalse([c for c in phone.log if "run-as" in c or "exec-out" in c])
        dumps = [c for c in phone.log if "calendar/.debug.DebugReceiver" in c.replace("org.agentos.sample.", "")]
        self.assertTrue(any("--es cmd dump" in c for c in dumps))
        self.assertTrue(any("--es cmd reset" in c for c in dumps), "reset, not the old clear")
        self.assertFalse(any("--es cmd clear" in c for c in dumps))

    def test_the_snapshot_has_the_driver_shape_with_series_ids_and_instants(self):
        phone = FakePhone()
        e = phone.t_event_create({"title": "weekly", "start": "2026-10-14T10:00:00+08:00", "end": "2026-10-14T11:00:00+08:00", "recurrence": "weekly",
                                  "recurrence_until": "2026-11-14T00:00:00+08:00", "reminder_minutes": [10, 60], "location": "A1"})
        snap = L.CalendarState(phone).snapshot()
        row = snap["events"][0]
        self.assertEqual(e["series_id"], row["id"], "id is the series id, which event_update / event_delete take")
        self.assertIn("@", row["occurrence_id"])
        self.assertEqual(int(datetime(2026, 10, 14, 10, 0, tzinfo=timezone(timedelta(hours=8))).timestamp() * 1000), row["start_ms"])
        self.assertEqual(row["start_ms"] + 3600000, row["end_ms"])
        self.assertEqual("2026-10-14T10:00:00+08:00", row["start"])
        self.assertEqual([10, 60], row["reminders"])
        self.assertEqual("weekly", row["recurrence"])
        self.assertEqual(L.iso_ms("2026-11-14T00:00:00+08:00"), row["until_ms"])
        self.assertEqual("A1", row["location"])
        self.assertFalse(row["hidden"])
        self.assertNotIn("created_at", row, "clocks are left out so unchanged comparisons are stable")
        self.assertEqual(["日历"], [c["name"] for c in snap["calendars"]])
        self.assertTrue(snap["calendars"][0]["is_default"])
        self.assertEqual(1, snap["calendars"][0]["event_count"])

    def test_iso_times_become_instants(self):
        self.assertEqual(L.iso_ms("2026-10-14T10:00:00+08:00"), L.iso_ms("2026-10-14T02:00:00Z"))
        self.assertEqual(L.iso_ms("2026-10-14T10:00:00+08:00"), L.iso_ms("2026-10-14T02:00:00+00:00"))
        self.assertIsNone(L.iso_ms(None))

    def test_every_event_is_read_even_when_the_dump_comes_in_pages(self):
        phone = FakePhone()
        for i in range(5):
            phone.t_event_create({"title": "e%d" % i, "start": "2026-10-%02dT10:00:00+08:00" % (10 + i)})
        old = L.DUMP_PAGE
        L.DUMP_PAGE = 2
        try:
            snap = L.CalendarState(phone).snapshot()
        finally:
            L.DUMP_PAGE = old
        self.assertEqual(["e%d" % i for i in range(5)], [e["title"] for e in snap["events"]])
        dumps = [c for c in phone.log if "--es cmd dump" in c and "DebugReceiver" in c]
        self.assertEqual(3, len(dumps))
        self.assertTrue(all("--ei limit 2" in c for c in dumps))

    def test_reset_reports_what_the_app_reports(self):
        phone = FakePhone()
        phone.t_calendar_create({"name": "extra"})
        phone.t_event_create({"title": "x", "start": "2026-10-14T10:00:00+08:00", "reminder_minutes": [10]})
        ok, data = L.CalendarState(phone).reset()
        self.assertTrue(ok, data)
        self.assertEqual({"cleared": 1, "calendars_remaining": 1, "remaining_scheduled": 0}, data)
        self.assertEqual([], L.CalendarState(phone).snapshot()["reminders"])
        bad, data = L.CalendarState(FakePhone(faults={"calendar-reset-leaves-registered"})).reset()
        self.assertFalse(bad)
        self.assertEqual(1, data["remaining_scheduled"])

    def test_setup_reset_checks_the_default_calendar_and_the_armed_alarm(self):
        phone = FakePhone(faults={"calendar-reset-noop"})
        phone.t_calendar_create({"name": "extra"})
        phone.t_event_create({"title": "x", "start": "2026-10-14T10:00:00+08:00", "reminder_minutes": [10]})
        _, _, report = run(phone)
        self.assertIn("setup.reset", failed(report))
        names = [c["name"] for c in self.step(report, "setup.reset")["checks"] if not c["ok"]]
        self.assertTrue(any("calendar: reset" in n for n in names), names)   # calendars_remaining is 2, not 1
        self.assertTrue(any("only the default calendar" in n for n in names), names)

    def test_the_reminder_steps_exist_and_a_healthy_run_passes_them(self):
        phone, env, report = run()
        self.assertTrue(report["ok"], report["summary"]["failures"])
        for sid in ("calendar.create_event", "calendar.update_event", "calendar.update_reminder", "calendar.create_earlier", "calendar.delete_earlier", "calendar.delete_event"):
            step = self.step(report, sid)
            self.assertTrue(step["ok"], (sid, [c for c in step["checks"] if not c["ok"]]))
        names = [c["name"] for c in self.step(report, "calendar.create_event")["checks"]]
        self.assertTrue(any("fires at 2026-10-17T09:50:00+08:00" in n for n in names), names)
        self.assertTrue(any("registered with the system AlarmManager" in n for n in names))
        self.assertTrue(any("exactly one reminder alarm is armed" in n for n in names))
        moved = [c["name"] for c in self.step(report, "calendar.update_reminder")["checks"]]
        self.assertTrue(any("fires at 2026-10-17T09:30:00+08:00" in n for n in moved), moved)
        earlier = [c["name"] for c in self.step(report, "calendar.create_earlier")["checks"]]
        self.assertTrue(any("fires at 2026-10-16T13:45:00+08:00" in n for n in earlier), earlier)
        gone = [c["name"] for c in self.step(report, "calendar.delete_event")["checks"]]
        self.assertTrue(any("no reminder alarm of the deleted event" in n for n in gone))
        self.assertTrue(any("nothing is armed" in n for n in gone), "the apps started empty: nothing at all is armed after the last delete")

    def test_the_single_armed_alarm_moves_to_the_earlier_event_and_back(self):
        phone, env, report = run()
        self.assertTrue(report["ok"])
        # the fake phone's own history of what was armed: S1 (10 min), S1 (30 min), the earlier event, S1 again, nothing
        armed_ids = []
        # replay the same calls directly on a fresh phone to see the sequence
        p = FakePhone()
        s1 = p.t_event_create({"title": "s1", "start": "2026-10-17T10:00:00+08:00", "recurrence": "weekly", "reminder_minutes": [30, 5]})["series_id"]
        p._sync_armed(force=True)
        self.assertEqual((s1, 30), (p.armed["series"], p.armed["minutes_before"]))
        e2 = p.t_event_create({"title": "e2", "start": "2026-10-16T14:00:00+08:00", "reminder_minutes": [15]})["series_id"]
        p._sync_armed(force=True)
        self.assertEqual((e2, 15), (p.armed["series"], p.armed["minutes_before"]))
        p.t_event_delete({"id": e2})
        p._sync_armed(force=True)
        self.assertEqual(s1, p.armed["series"])
        p.t_event_delete({"id": s1})
        p._sync_armed(force=True)
        self.assertIsNone(p.armed)

    def test_a_dump_read_right_after_a_change_may_still_show_the_old_alarm_and_the_driver_waits_for_it(self):
        phone = FakePhone()
        phone.t_event_create({"title": "x", "start": "2026-10-17T10:00:00+08:00", "reminder_minutes": [10]})
        first = L.CalendarState(phone).snapshot()["reminders"]
        second = L.CalendarState(phone).snapshot()["reminders"]
        self.assertEqual([], first, "the 250 ms debounce: the first read after the change shows nothing yet")
        self.assertEqual(1, len(second))
        # ... and the driver's check re-reads instead of failing on the first read
        phone2 = FakePhone()
        phone2.enabled = {s.package: True for s in L.SAMPLES.values()}
        phone2.consent_mode = "allow"
        phone2.t_event_create({"title": "x", "start": "2026-10-17T10:00:00+08:00", "reminder_minutes": [10]})
        ctx = L.Context(phone2, L.ExtensionDebug(phone2), L.ConsentDebug(phone2), None, date(2026, 10, 7), "+08:00", "r1", exclusive=True)
        fire = ctx.epoch_ms(date(2026, 10, 17), "10:00") - 600000
        checks = S.reminder_checks(ctx, phone2.q("calendar.db", "SELECT id FROM events")[0]["id"], fire, 10)
        self.assertTrue(all(c.ok for c in checks), [c for c in checks if not c.ok])

    def test_an_app_that_never_arms_the_reminder_fails_the_create_step_with_expected_and_actual(self):
        _, _, report = run(FakePhone(faults={"reminder-never-armed"}))
        self.assertIn("calendar.create_event", failed(report))
        line = next(l for l in report["summary"]["failures"] if l.startswith("FAIL calendar.create_event"))
        self.assertIn("exactly one reminder alarm is armed", line)
        self.assertIn("expected 1", line)
        self.assertIn("actual 0", line)

    def test_a_reminder_that_is_recorded_but_not_registered_with_alarmmanager_fails(self):
        _, _, report = run(FakePhone(faults={"reminder-not-registered"}))
        self.assertIn("calendar.create_event", failed(report))
        bad = [c["name"] for c in self.step(report, "calendar.create_event")["checks"] if not c["ok"]]
        self.assertTrue(any("registered with the system AlarmManager" in n for n in bad), bad)

    def test_two_armed_alarms_fail_the_single_alarm_rule(self):
        _, _, report = run(FakePhone(faults={"two-armed"}))
        bad = [c for c in self.step(report, "calendar.create_event")["checks"] if not c["ok"]]
        self.assertTrue(any("exactly one" in c["name"] and c["expected"] == 1 and c["actual"] == 2 for c in bad), bad)

    def test_a_delete_that_leaves_the_alarm_armed_fails_the_delete_steps(self):
        _, _, report = run(FakePhone(faults={"delete-keeps-armed"}))
        self.assertIn("calendar.delete_earlier", failed(report))
        self.assertIn("calendar.delete_event", failed(report))
        gone = [c for c in self.step(report, "calendar.delete_event")["checks"] if not c["ok"]]
        # the alarm that stayed armed is the earlier (already deleted) event's: caught as an orphan and as "something is still armed"
        self.assertTrue(any("belongs to an event that still exists" in c["name"] and c["actual"] for c in gone), gone)
        self.assertTrue(any("nothing is armed" in c["name"] and c["actual"] for c in gone), gone)
        earlier = [c for c in self.step(report, "calendar.delete_earlier")["checks"] if not c["ok"]]
        self.assertTrue(any("belongs to this event" in c["name"] for c in earlier), earlier)

    def test_a_reminder_that_was_not_moved_by_event_update_fails(self):
        phone = FakePhone()
        original = phone.t_event_update
        phone.t_event_update = lambda a: (original({k: v for k, v in a.items() if k != "reminder_minutes"}))   # the app ignores reminder_minutes
        _, _, report = run(phone)
        self.assertIn("calendar.update_reminder", failed(report))
        bad = [c["name"] for c in self.step(report, "calendar.update_reminder")["checks"] if not c["ok"]]
        self.assertTrue(any("reminder_minutes are now [30, 5]" in n for n in bad), bad)

    def test_when_the_apps_did_not_start_empty_an_earlier_reminder_of_another_event_may_hold_the_slot(self):
        phone = FakePhone()
        phone.t_event_create({"title": "mine", "start": "2026-10-08T09:00:00+08:00", "reminder_minutes": [10]})   # earlier than anything the run creates
        phone._sync_armed(force=True)
        _, _, report = run(phone, reset=False)
        self.assertTrue(report["ok"], report["summary"]["failures"])
        names = [c["name"] for c in self.step(report, "calendar.create_event")["checks"]]
        self.assertTrue(any("another event's earlier reminder holds the single slot" in n for n in names), names)
        self.assertEqual(["mine"], [r["title"] for r in phone.q("calendar.db", "SELECT title FROM events")], "only this run's events were removed")

    def test_the_live_event_must_have_its_15_minute_reminder_armed(self):
        plan = LiveRunTest().plan()
        env = FakeEnv(FakePhone(), live_plan=plan)
        report = E.run_acceptance(env, E.Options(live=True), log=lambda m: None)
        step = self.step(report, "live.calendar")
        self.assertTrue(step["ok"], step["checks"])
        self.assertTrue(any("live reminder: fires at 2026-10-14T14:45:00+08:00" in c["name"] for c in step["checks"]), [c["name"] for c in step["checks"]])
        plan[S.LIVE_PROMPTS["calendar"]] = lambda ph: [("mcp__calendar__calendar__event_create", {"title": "和王总开会", "start": "2026-10-14T15:00:00+08:00", "reminder_minutes": [10]})]
        report = E.run_acceptance(FakeEnv(FakePhone(), live_plan=plan), E.Options(live=True), log=lambda m: None)
        self.assertIn("live.calendar", failed(report))


class CatalogRaceTest(unittest.TestCase):
    """The three plugins' tools show up one plugin after the other after `enable` (real phone: setup.enable returned after 539 ms, setup.catalog read the
    catalog 149 ms later and every notes tool was missing). Nothing may read the catalog once and believe it."""

    ALARM, CALENDAR, NOTES = "org.agentos.sample.alarm", "org.agentos.sample.calendar", "org.agentos.sample.notes"

    def staggered(self, **kw):
        phone = FakePhone(**kw)
        phone.list_delay = {self.ALARM: 0, self.CALENDAR: 2, self.NOTES: 4}
        return phone

    def step(self, report, id):
        return next(s for s in report["steps"] if s["id"] == id)

    def test_a_single_catalog_read_right_after_enable_misses_the_late_plugins(self):
        phone = self.staggered()
        ext = L.ExtensionDebug(phone)
        for smp in L.SAMPLES.values():
            ext.enable(smp.package)
        first = ext.catalog()
        self.assertTrue(any(n.startswith("mcp__alarm__") for n in first))
        self.assertFalse(any(n.startswith("mcp__notes__") for n in first), "this is the read that failed on the real phone")
        self.assertFalse(any(n.startswith("mcp__calendar__") for n in first))

    def test_a_full_run_passes_although_the_tools_of_the_second_and_third_plugin_show_up_late(self):
        phone = self.staggered()
        _, env, report = run(phone)
        self.assertTrue(report["ok"], report["summary"]["failures"])
        names = [c["name"] for c in self.step(report, "setup.catalog")["checks"]]
        self.assertTrue(any("every documented tool was offered within 30 s" in n for n in names), names)
        enable = self.step(report, "setup.enable")
        self.assertTrue(enable["ok"], [c for c in enable["checks"] if not c["ok"]])

    def test_enable_and_catalog_wait_for_the_last_tool_of_every_plugin(self):
        phone = self.staggered()
        phone.list_delay = {self.ALARM: 3, self.CALENDAR: 7, self.NOTES: 11}
        ctx = L.Context(phone, L.ExtensionDebug(phone), L.ConsentDebug(phone), None, date(2026, 10, 7), "+08:00", "r1")
        out = S.setup_enable(ctx)
        self.assertTrue(all(c.ok for c in out), [c for c in out if not c.ok])
        out = S.setup_catalog(ctx)
        self.assertTrue(all(c.ok for c in out), [c for c in out if not c.ok])
        self.assertEqual(1 + 44, len(out))

    def test_a_tool_that_never_appears_is_named_with_its_plugin_after_the_wait(self):
        phone = self.staggered(faults={"hide-note_trash", "hide-event_get"})
        _, _, report = run(phone)
        self.assertIn("setup.enable", failed(report))
        self.assertIn("setup.catalog", failed(report))
        bad = next(c for c in self.step(report, "setup.catalog")["checks"] if not c["ok"] and "every documented tool" in c["name"])
        self.assertEqual({"notes": ["note_trash"], "calendar": ["event_get"]}, bad["actual"]["missing"])
        self.assertIn("waitedMs", bad["actual"])
        line = next(l for l in report["summary"]["failures"] if l.startswith("FAIL setup.catalog"))
        self.assertIn("note_trash", line)
        self.assertTrue(all(not s["sample"] for s in report["steps"]), "no app step ran on a catalog that is not complete")

    def test_the_wait_is_a_complete_catalog_read_and_bounded(self):
        phone = self.staggered(faults={"hide-note_trash"})
        ctx = L.Context(phone, L.ExtensionDebug(phone), L.ConsentDebug(phone), None, date(2026, 10, 7), "+08:00", "r1")
        for smp in L.SAMPLES.values():
            ctx.ext.enable(smp.package)
        t0 = time.time()
        w = S.await_catalog(ctx, present=list(S.documented()))
        self.assertLess(time.time() - t0, 5.0, "bounded by CATALOG_WAIT_SECONDS (scaled down in the unit tests)")
        self.assertFalse(w.ok)
        self.assertEqual(["mcp__notes__notes__note_trash"], w.missing)
        self.assertEqual(len(S.documented()) - 1, len([n for n in S.documented() if n in w.catalog]), "everything else was in the catalog it returns")

    def test_plugin_off_waits_for_the_tools_to_go_and_plugin_on_for_them_to_return(self):
        phone = self.staggered()
        phone.drop_delay = {self.ALARM: 3, self.CALENDAR: 3, self.NOTES: 3}
        _, _, report = run(phone)
        self.assertTrue(report["ok"], report["summary"]["failures"])
        for sid in ("alarm.plugin_off", "alarm.plugin_on", "calendar.plugin_off", "calendar.plugin_on", "notes.plugin_off", "notes.plugin_on"):
            self.assertTrue(self.step(report, sid)["ok"], sid)
        off = [c["name"] for c in self.step(report, "notes.plugin_off")["checks"]]
        self.assertTrue(any("waited for the catalog to drop them" in n for n in off), off)

    def test_tools_that_never_go_away_after_disable_are_named(self):
        phone = FakePhone()
        phone.drop_delay = {self.NOTES: 10 ** 6}
        _, _, report = run(phone)
        step = self.step(report, "notes.plugin_off")
        self.assertFalse(step["ok"])
        bad = next(c for c in step["checks"] if not c["ok"] and "no notes tool is offered" in c["name"])
        self.assertIn("stillOffered", bad["actual"])
        self.assertIn("notes", bad["actual"]["stillOffered"])

    def test_re_enabling_a_plugin_whose_tools_come_back_late_passes(self):
        phone = FakePhone()
        _, _, report = run(phone)       # healthy baseline
        self.assertTrue(report["ok"])
        phone = FakePhone()
        phone.list_delay = {self.NOTES: 6}
        _, _, report = run(phone)
        self.assertTrue(self.step(report, "notes.plugin_on")["ok"], [c for c in self.step(report, "notes.plugin_on")["checks"] if not c["ok"]])


class LivePromptTest(unittest.TestCase):
    """The prompts carry every fact the checks read: a model that has to ask first creates nothing, which is valid behaviour and not an app fault."""

    def test_the_alarm_prompt_has_the_time_the_day_and_that_it_rings_once(self):
        p = S.LIVE_PROMPTS["alarm"]
        for fact in ("明天", "早上 7 点", "闹钟", "只响这一次"):
            self.assertIn(fact, p)

    def test_the_calendar_prompt_has_who_when_how_long_where_and_the_reminder(self):
        p = S.LIVE_PROMPTS["calendar"]
        for fact in ("下周三", "下午 3 点", "4 点", "王总", "3 号会议室", "提前 15 分钟"):
            self.assertIn(fact, p)

    def test_the_notes_prompt_has_the_text_and_the_tag(self):
        p = S.LIVE_PROMPTS["notes"]
        for fact in ("新品发布", "演示稿", "嘉宾名单", "物料清单", "“工作”标签"):
            self.assertIn(fact, p)

    def test_the_prompts_are_natural_chinese_sentences(self):
        import re
        for app, p in S.LIVE_PROMPTS.items():
            self.assertRegex(p, r"[\u4e00-\u9fff]{6}", app)
            self.assertFalse(re.search(r"[A-Za-z_]{3,}", p), "no tool names or English in %s: %r" % (app, p))
            self.assertTrue(p.endswith(("。", "标签。")), p)
            self.assertLess(len(p), 80)

    def test_the_checks_still_read_the_app_state_and_pass_for_a_model_that_did_what_the_sentence_says(self):
        d = {
            S.LIVE_PROMPTS["alarm"]: lambda ph: [("mcp__alarm__alarm__alarm_create", {"time": "07:00"})],
            S.LIVE_PROMPTS["calendar"]: lambda ph: [("mcp__calendar__calendar__event_create", {
                "title": "和王总开会", "start": "2026-10-14T15:00:00+08:00", "end": "2026-10-14T16:00:00+08:00", "location": "3 号会议室", "reminder_minutes": [15]})],
            S.LIVE_PROMPTS["notes"]: lambda ph: [("mcp__notes__notes__note_create", {"content": "新品发布会要准备：演示稿、嘉宾名单、物料清单", "tags": ["工作"]})],
        }
        report = E.run_acceptance(FakeEnv(FakePhone(), live_plan=d), E.Options(live=True), log=lambda m: None)
        self.assertTrue(report["ok"], report["summary"]["failures"])
        self.assertEqual([S.LIVE_PROMPTS["alarm"], S.LIVE_PROMPTS["calendar"], S.LIVE_PROMPTS["notes"]], [t for _, t in [(0, x["title"]) for x in report["steps"] if x["id"].startswith("live.")]])


class ModelSourceTest(unittest.TestCase):
    def test_the_model_source_is_checked_before_anything_is_asked(self):
        _, env, report = run()
        step = next(s for s in report["steps"] if s["id"] == "setup.model")
        self.assertTrue(step["ok"])
        self.assertEqual("http://127.0.0.1:18787", step["checks"][1]["actual"]["modelBaseUrl"])
        self.assertEqual("setup.model", report["steps"][0]["id"])

    def test_live_wants_the_real_minimax_preset(self):
        phone = FakePhone()
        env = FakeEnv(phone, live_plan=LiveRunTest().plan())
        report = E.run_acceptance(env, E.Options(live=True), log=lambda m: None)
        step = next(s for s in report["steps"] if s["id"] == "setup.model")
        self.assertTrue(step["ok"], step["checks"])
        shown = next(c["actual"] for c in step["checks"] if "REAL MiniMax" in c["name"])
        self.assertEqual({"modelUsable": True, "modelBaseUrl": "https://api.minimaxi.com/anthropic", "modelId": "MiniMax-M3"}, shown)
        self.assertEqual(["prepare", "desktop", "model:live", "session", "finish"], env.events, "the real model is configured after the desktop access")

    def test_a_model_source_that_was_put_back_to_the_loopback_fake_stops_the_live_run(self):
        phone = FakePhone(faults={"model-reset-to-fake"})
        report = E.run_acceptance(FakeEnv(phone, live_plan=LiveRunTest().plan()), E.Options(live=True), log=lambda m: None)
        self.assertIn("setup.model", failed(report))
        self.assertTrue(all(not s["sample"] for s in report["steps"]), "no prompt was sent to the wrong model")
        line = next(l for l in report["summary"]["failures"] if l.startswith("FAIL setup.model"))
        self.assertIn("api.minimaxi.com", line)
        self.assertEqual([], phone.consent_log)

    def test_tunnel_mode_expects_the_loopback_endpoint(self):
        report = E.run_acceptance(FakeEnv(FakePhone(), live_plan=LiveRunTest().plan()), E.Options(live=True, tunnel=True), log=lambda m: None)
        step = next(s for s in report["steps"] if s["id"] == "setup.model")
        self.assertTrue(step["ok"], step["checks"])
        self.assertTrue(any("tunnelled" in c["name"] for c in step["checks"]))

    def test_a_fake_run_refuses_a_phone_that_still_has_a_real_model(self):
        phone = FakePhone()
        env = FakeEnv(phone)
        env.ensure_model = lambda opts: setattr(phone, "model", {"modelUsable": True, "modelBaseUrl": "https://api.minimaxi.com/anthropic", "modelId": "MiniMax-M3"})
        report = E.run_acceptance(env, E.Options(), log=lambda m: None)
        self.assertIn("setup.model", failed(report))


class LiveRunTest(unittest.TestCase):
    def plan(self):
        d = date(2026, 10, 14)  # next Wednesday
        return {
            S.LIVE_PROMPTS["alarm"]: lambda ph: [("mcp__alarm__alarm__alarm_create", {"time": "07:00"})],
            S.LIVE_PROMPTS["calendar"]: lambda ph: [("mcp__calendar__calendar__event_create", {"title": "和王总开会", "start": "2026-10-14T15:00:00+08:00", "reminder_minutes": [15]})],
            S.LIVE_PROMPTS["notes"]: lambda ph: [("mcp__notes__notes__note_search", {"query": "新品发布"}), ("mcp__notes__notes__note_create", {"content": "新品发布备忘", "tags": ["工作"]})],
        }

    def test_live_cases_read_the_app_state_and_record_the_tool_sequence(self):
        phone = FakePhone()
        env = FakeEnv(phone, live_plan=self.plan())
        report = E.run_acceptance(env, E.Options(live=True), log=lambda m: None)
        self.assertEqual("live", report["mode"])
        self.assertEqual(["model:live"], [e for e in env.events if e.startswith("model")])
        live = [s for s in report["steps"] if s["id"].startswith("live.")]
        self.assertEqual(["live.alarm", "live.calendar", "live.notes"], [s["id"] for s in live])
        self.assertEqual([], [(s["id"], E._first_failure(s)) for s in live if not s["ok"]])
        notes = next(s for s in live if s["id"] == "live.notes")
        self.assertEqual([{"name": "mcp__notes__notes__note_search", "status": "completed"}, {"name": "mcp__notes__notes__note_create", "status": "completed"}], notes["toolSequence"])
        self.assertTrue(all(isinstance(s["ms"], int) for s in live))

    def test_a_model_that_picks_the_wrong_time_fails_with_what_it_did(self):
        plan = self.plan()
        plan[S.LIVE_PROMPTS["alarm"]] = lambda ph: [("mcp__alarm__alarm__alarm_create", {"time": "17:00"})]
        report = E.run_acceptance(FakeEnv(FakePhone(), live_plan=plan), E.Options(live=True), log=lambda m: None)
        self.assertEqual({"live.alarm"}, failed(report))
        line = next(l for l in report["summary"]["failures"] if l.startswith("FAIL live.alarm"))
        self.assertIn("07:00", line)

    def test_the_verdict_is_the_app_state_not_a_fixed_tool_sequence(self):
        plan = self.plan()
        # the model looks first, hits an error, retries: different sequence, same result
        plan[S.LIVE_PROMPTS["notes"]] = lambda ph: [
            ("mcp__notes__notes__note_list", {}), ("mcp__notes__notes__note_get", {"id": "nope"}), ("mcp__notes__notes__note_search", {"query": "新品发布"}),
            ("mcp__notes__notes__note_create", {"content": "新品发布备忘", "tags": ["工作"]})]
        report = E.run_acceptance(FakeEnv(FakePhone(), live_plan=plan), E.Options(live=True), log=lambda m: None)
        self.assertTrue(report["ok"], report["summary"]["failures"])
        notes = next(s for s in report["steps"] if s["id"] == "live.notes")
        self.assertEqual(4, len(notes["toolSequence"]))
        self.assertEqual(1, len(notes["failedCalls"]), "the failed call is recorded as information")
        self.assertEqual("mcp__notes__notes__note_get", notes["failedCalls"][0]["name"])

    def test_the_prompts_are_the_natural_ones_and_the_date_is_only_added_on_request(self):
        phone = FakePhone()
        env = FakeEnv(phone, live_plan=self.plan())
        E.run_acceptance(env, E.Options(live=True), log=lambda m: None)
        self.assertEqual([S.LIVE_PROMPTS["alarm"], S.LIVE_PROMPTS["calendar"], S.LIVE_PROMPTS["notes"]], [t for _, t in env.bridge.prompts])
        env2 = FakeEnv(FakePhone(), live_plan={})
        E.run_acceptance(env2, E.Options(live=True, tell_date=True), log=lambda m: None)
        self.assertTrue(all(t.startswith("今天是 2026-10-07（设备时区 UTC+08:00）。") for _, t in env2.bridge.prompts), env2.bridge.prompts)

    def test_the_alarm_must_really_be_set_in_the_system(self):
        report = E.run_acceptance(FakeEnv(FakePhone(faults={"alarm-not-registered"}), live_plan=self.plan()), E.Options(live=True), log=lambda m: None)
        self.assertIn("live.alarm", failed(report))

    def test_a_model_that_calls_no_tool_fails(self):
        plan = self.plan()
        plan[S.LIVE_PROMPTS["notes"]] = lambda ph: []
        report = E.run_acceptance(FakeEnv(FakePhone(), live_plan=plan), E.Options(live=True), log=lambda m: None)
        self.assertEqual({"live.notes", "audit.notes"}, failed(report), "the audit also notices that nothing was ever confirmed for notes")

    def test_the_result_never_contains_a_key(self):
        report = E.run_acceptance(FakeEnv(FakePhone(), live_plan=self.plan()), E.Options(live=True), log=lambda m: None)
        text = json.dumps(report, ensure_ascii=False)
        for needle in ("sk-", "MINIMAX_API_KEY", "x-api-key", "agtest-"):
            self.assertNotIn(needle, text)


class ReportFileTest(unittest.TestCase):
    def test_the_report_is_written_under_results_raw_with_the_device(self):
        import tempfile
        _, _, report = run()
        with tempfile.TemporaryDirectory() as d:
            path = E.write_report(report, {"serial": "emulator-5590", "sdk": "36"}, "t", here=d)
            self.assertTrue(path.startswith(os.path.join(d, "results", "raw")))
            self.assertIn("sample-apps-emulator-5590-api36-scripted-t-", path)
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
        self.assertEqual("emulator-5590", data["device"]["serial"])
        self.assertEqual(report["summary"]["steps"], len(data["steps"]))
        self.assertTrue(all("checks" in s for s in data["steps"]))


if __name__ == "__main__":
    unittest.main()
