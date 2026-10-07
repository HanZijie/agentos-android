"""The acceptance driver against a fake phone (no device): the whole flow, the verdicts, and that failures are described by step."""
import json
import os
import sys
import unittest
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)
import sample_apps_e2e as E  # noqa: E402
import sample_apps_lib as L  # noqa: E402
import sample_apps_scenarios as S  # noqa: E402
from fake_world import FakeEnv, FakePhone  # noqa: E402


S.WAIT_SCALE = 0.02


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
        self.assertEqual(["prepare", "model:fake", "session", "finish"], env.events)

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
        self.assertEqual([], phone.q("notes.db", "SELECT * FROM notes WHERE status != 2 AND content LIKE '%e2e-%'") and ["x"])
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
        self.assertEqual("ALLOW_ONCE", phone.consent_log[-1]["answeredWith"], "the last request of the run is the HIGH note_delete, allowed once")

    def test_the_catalog_check_reports_risk_per_tool(self):
        _, _, report = run()
        cat = next(s for s in report["steps"] if s["id"] == "setup.catalog")
        names = {c["name"]: c for c in cat["checks"]}
        delete = [c for n, c in names.items() if "alarm_delete" in n][0]
        self.assertEqual("HIGH", delete["expected"])
        self.assertEqual("HIGH", delete["actual"])
        listing = [c for n, c in names.items() if "note_list is offered" in n][0]
        self.assertEqual("WRITE", listing["expected"], "no annotation lowers a third-party tool below WRITE")
        self.assertEqual(29, len(cat["checks"]))


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

    def test_unreadable_app_database_is_a_driver_error_not_a_pass(self):
        phone, env, report = run(FakePhone(faults={"no-db-notes"}))
        self.assertFalse(report["ok"])
        notes_steps = [s for s in report["steps"] if s["sample"] == "notes"]
        self.assertTrue(notes_steps)
        self.assertTrue(all(not s["ok"] for s in notes_steps))
        self.assertTrue(any("cannot read databases/notes.db" in (s["error"] or "") for s in notes_steps))
        self.assertTrue(all("cannot read databases/notes.db" in (s["error"] or "") or "needs '" in (s["error"] or "") for s in notes_steps))
        alarm = [s for s in report["steps"] if s["sample"] == "alarm"]
        self.assertTrue(all(s["ok"] for s in alarm), "an unreadable notes database does not spoil the alarm results")

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


class LiveRunTest(unittest.TestCase):
    def plan(self):
        d = date(2026, 10, 14)  # next Wednesday
        return {
            "明早 7 点叫我起床": lambda ph: [("mcp__alarm__alarm__alarm_create", {"time": "07:00"})],
            "下周三下午 3 点和王总开会，提前 15 分钟提醒": lambda ph: [("mcp__calendar__calendar__event_create", {"title": "和王总开会", "start": "2026-10-14T15:00:00+08:00", "reminder_minutes": [15]})],
            "记一条关于新品发布的备忘，打上工作标签": lambda ph: [("mcp__notes__notes__note_search", {"query": "新品发布"}), ("mcp__notes__notes__note_create", {"content": "新品发布备忘", "tags": ["工作"]})],
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
        plan["明早 7 点叫我起床"] = lambda ph: [("mcp__alarm__alarm__alarm_create", {"time": "17:00"})]
        report = E.run_acceptance(FakeEnv(FakePhone(), live_plan=plan), E.Options(live=True), log=lambda m: None)
        self.assertEqual({"live.alarm"}, failed(report))
        line = next(l for l in report["summary"]["failures"] if l.startswith("FAIL live.alarm"))
        self.assertIn("07:00", line)

    def test_a_model_that_calls_no_tool_fails(self):
        plan = self.plan()
        plan["记一条关于新品发布的备忘，打上工作标签"] = lambda ph: []
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
